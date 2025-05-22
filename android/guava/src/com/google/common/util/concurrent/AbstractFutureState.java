/*
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.util.concurrent;

import static com.google.common.util.concurrent.AbstractFuture.getDoneValue;
import static com.google.common.util.concurrent.AbstractFuture.notInstanceOfDelegatingToFuture;
import static java.lang.Boolean.parseBoolean;
import static java.security.AccessController.doPrivileged;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater;
import static java.util.logging.Level.SEVERE;

import com.google.common.annotations.GwtCompatible;
import com.google.common.util.concurrent.AbstractFuture.Listener;
import com.google.common.util.concurrent.internal.InternalFutureFailureAccess;
import com.google.j2objc.annotations.ReflectionSupport;
import com.google.j2objc.annotations.RetainedLocalRef;
import java.lang.reflect.Field;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.Nullable;
import sun.misc.Unsafe;

/** Supertype of {@link AbstractFuture} that contains platform-specific functionality. */
// Whenever both tests are cheap and functional, it's faster to use &, | instead of &&, ||
@SuppressWarnings("ShortCircuitBoolean")
@GwtCompatible(emulated = true)
@ReflectionSupport(value = ReflectionSupport.Level.FULL)
abstract class AbstractFutureState<V extends @Nullable Object> extends InternalFutureFailureAccess
    implements ListenableFuture<V> {
  /**
   * Performs a {@linkplain java.lang.invoke.VarHandle#compareAndSet compare-and-set} operation on
   * the {@link #listeners} field.
   */
  final boolean casListeners(@Nullable Listener expect, Listener update) {
    return ATOMIC_HELPER.casListeners(this, expect, update);
  }

  /**
   * Performs a {@linkplain java.lang.invoke.VarHandle#getAndSet get-and-set} operation on the
   * {@link #listeners} field..
   */
  final @Nullable Listener gasListeners(Listener update) {
    return ATOMIC_HELPER.gasListeners(this, update);
  }

  /**
   * Performs a {@linkplain java.lang.invoke.VarHandle#compareAndSet compare-and-set} operation on
   * the {@link #value} field of {@code future}.
   */
  static boolean casValue(AbstractFutureState<?> future, @Nullable Object expect, Object update) {
    return ATOMIC_HELPER.casValue(future, expect, update);
  }

  /** Returns the value of the future, using a volatile read. */
  final @Nullable Object value() {
    return value;
  }

  /** Returns the head of the listener stack, using a volatile read. */
  final @Nullable Listener listeners() {
    return listeners;
  }

  /** Releases all threads in the {@link #waiters} list, and clears the list. */
  final void releaseWaiters() {
    Waiter head = gasWaiters(Waiter.TOMBSTONE);
    for (Waiter currentWaiter = head; currentWaiter != null; currentWaiter = currentWaiter.next) {
      currentWaiter.unpark();
    }
  }

  // Gets and Timed Gets
  //
  // * Be responsive to interruption
  // * Don't create Waiter nodes if you aren't going to park, this helps reduce contention on the
  //   waiters field.
  // * Future completion is defined by when #value becomes non-null/non DelegatingToFuture
  // * Future completion can be observed if the waiters field contains a TOMBSTONE

  // Timed Get
  // There are a few design constraints to consider
  // * We want to be responsive to small timeouts, unpark() has non trivial latency overheads (I
  //   have observed 12 micros on 64-bit linux systems to wake up a parked thread). So if the
  //   timeout is small we shouldn't park(). This needs to be traded off with the cpu overhead of
  //   spinning, so we use SPIN_THRESHOLD_NANOS which is what AbstractQueuedSynchronizer uses for
  //   similar purposes.
  // * We want to behave reasonably for timeouts of 0
  // * We are more responsive to completion than timeouts. This is because parkNanos depends on
  //   system scheduling and as such we could either miss our deadline, or unpark() could be delayed
  //   so that it looks like we timed out even though we didn't. For comparison FutureTask respects
  //   completion preferably and AQS is non-deterministic (depends on where in the queue the waiter
  //   is). If we wanted to be strict about it, we could store the unpark() time in the Waiter node
  //   and we could use that to make a decision about whether or not we timed out prior to being
  //   unparked.

  @SuppressWarnings({
    "LabelledBreakTarget", // TODO(b/345814817): Maybe fix?
    "nullness", // TODO(b/147136275): Remove once our checker understands & and |.
  })
  @ParametricNullness
  final V blockingGet(long timeout, TimeUnit unit)
      throws InterruptedException, TimeoutException, ExecutionException {
    // NOTE: if timeout < 0, remainingNanos will be < 0 and we will fall into the while(true) loop
    // at the bottom and throw a timeoutexception.
    long timeoutNanos = unit.toNanos(timeout); // we rely on the implicit null check on unit.
    long remainingNanos = timeoutNanos;
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    @RetainedLocalRef Object localValue = value;
    if (localValue != null & notInstanceOfDelegatingToFuture(localValue)) {
      return getDoneValue(localValue);
    }
    // we delay calling nanoTime until we know we will need to either park or spin
    long endNanos = remainingNanos > 0 ? System.nanoTime() + remainingNanos : 0;
    long_wait_loop:
    if (remainingNanos >= SPIN_THRESHOLD_NANOS) {
      Waiter oldHead = waiters;
      if (oldHead != Waiter.TOMBSTONE) {
        Waiter node = new Waiter();
        do {
          node.setNext(oldHead);
          if (casWaiters(oldHead, node)) {
            while (true) {
              OverflowAvoidingLockSupport.parkNanos(this, remainingNanos);
              // Check interruption first, if we woke up due to interruption we need to honor that.
              if (Thread.interrupted()) {
                removeWaiter(node);
                throw new InterruptedException();
              }

              // Otherwise re-read and check doneness. If we loop then it must have been a spurious
              // wakeup
              localValue = value;
              if (localValue != null & notInstanceOfDelegatingToFuture(localValue)) {
                return getDoneValue(localValue);
              }

              // timed out?
              remainingNanos = endNanos - System.nanoTime();
              if (remainingNanos < SPIN_THRESHOLD_NANOS) {
                // Remove the waiter, one way or another we are done parking this thread.
                removeWaiter(node);
                break long_wait_loop; // jump down to the busy wait loop
              }
            }
          }
          oldHead = waiters; // re-read and loop.
        } while (oldHead != Waiter.TOMBSTONE);
      }
      // re-read value, if we get here then we must have observed a TOMBSTONE while trying to add a
      // waiter.
      // requireNonNull is safe because value is always set before TOMBSTONE.
      return getDoneValue(requireNonNull(value));
    }
    // If we get here then we have remainingNanos < SPIN_THRESHOLD_NANOS and there is no node on the
    // waiters list
    while (remainingNanos > 0) {
      localValue = value;
      if (localValue != null & notInstanceOfDelegatingToFuture(localValue)) {
        return getDoneValue(localValue);
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      remainingNanos = endNanos - System.nanoTime();
    }

    String futureToString = toString();
    String unitString = unit.toString().toLowerCase(Locale.ROOT);
    String message = "Waited " + timeout + " " + unit.toString().toLowerCase(Locale.ROOT);
    // Only report scheduling delay if larger than our spin threshold - otherwise it's just noise
    if (remainingNanos + SPIN_THRESHOLD_NANOS < 0) {
      // We over-waited for our timeout.
      message += " (plus ";
      long overWaitNanos = -remainingNanos;
      long overWaitUnits = unit.convert(overWaitNanos, NANOSECONDS);
      long overWaitLeftoverNanos = overWaitNanos - unit.toNanos(overWaitUnits);
      boolean shouldShowExtraNanos =
          overWaitUnits == 0 || overWaitLeftoverNanos > SPIN_THRESHOLD_NANOS;
      if (overWaitUnits > 0) {
        message += overWaitUnits + " " + unitString;
        if (shouldShowExtraNanos) {
          message += ",";
        }
        message += " ";
      }
      if (shouldShowExtraNanos) {
        message += overWaitLeftoverNanos + " nanoseconds ";
      }

      message += "delay)";
    }
    // It's confusing to see a completed future in a timeout message; if isDone() returns false,
    // then we know it must have given a pending toString value earlier. If not, then the future
    // completed after the timeout expired, and the message might be success.
    if (isDone()) {
      throw new TimeoutException(message + " but future completed as timeout expired");
    }
    throw new TimeoutException(message + " for " + futureToString);
  }

  @ParametricNullness
  @SuppressWarnings("nullness") // TODO(b/147136275): Remove once our checker understands & and |.
  final V blockingGet() throws InterruptedException, ExecutionException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    @RetainedLocalRef Object localValue = value;
    if (localValue != null & notInstanceOfDelegatingToFuture(localValue)) {
      return getDoneValue(localValue);
    }
    Waiter oldHead = waiters;
    if (oldHead != Waiter.TOMBSTONE) {
      Waiter node = new Waiter();
      do {
        node.setNext(oldHead);
        if (casWaiters(oldHead, node)) {
          // we are on the stack, now wait for completion.
          while (true) {
            LockSupport.park(this);
            // Check interruption first, if we woke up due to interruption we need to honor that.
            if (Thread.interrupted()) {
              removeWaiter(node);
              throw new InterruptedException();
            }
            // Otherwise re-read and check doneness. If we loop then it must have been a spurious
            // wakeup
            localValue = value;
            if (localValue != null & notInstanceOfDelegatingToFuture(localValue)) {
              return getDoneValue(localValue);
            }
          }
        }
        oldHead = waiters; // re-read and loop.
      } while (oldHead != Waiter.TOMBSTONE);
    }
    // re-read value, if we get here then we must have observed a TOMBSTONE while trying to add a
    // waiter.
    // requireNonNull is safe because value is always set before TOMBSTONE.
    return getDoneValue(requireNonNull(value));
  }

  /** Constructor for use by {@link AbstractFuture}. */
  AbstractFutureState() {}

  /*
   * We put various static objects here rather than in AbstractFuture so that they're initialized in
   * time for AbstractFutureState to potentially use them during class initialization.
   * (AbstractFutureState class initialization can log, and that logging could in theory call into
   * AbstractFuture, which wouldn't yet have had the chance to perform any class initialization of
   * its own.)
   */

  /** A special value to represent {@code null}. */
  static final Object NULL = new Object();

  /*
   * Despite declaring this field in AbstractFutureState, we still use the logger for
   * AbstractFuture: Users may have tests or log configuration that expects that to be the logger
   * used for exceptions from listeners, as it's been in the past.
   */
  static final LazyLogger log = new LazyLogger(AbstractFuture.class);

  static final boolean GENERATE_CANCELLATION_CAUSES;

  static {
    // System.getProperty may throw if the security policy does not permit access.
    boolean generateCancellationCauses;
    try {
      generateCancellationCauses =
          parseBoolean(System.getProperty("guava.concurrent.generate_cancellation_cause", "false"));
    } catch (SecurityException e) {
      generateCancellationCauses = false;
    }
    GENERATE_CANCELLATION_CAUSES = generateCancellationCauses;
  }

  /** Waiter links form a Treiber stack, in the {@link #waiters} field. */
  static final class Waiter {
    static final Waiter TOMBSTONE = new Waiter(false /* ignored param */);

    volatile @Nullable Thread thread;
    volatile @Nullable Waiter next;

    /**
     * Constructor for the TOMBSTONE, avoids use of ATOMIC_HELPER in case this class is loaded
     * before the ATOMIC_HELPER. Apparently this is possible on some android platforms.
     */
    Waiter(boolean unused) {}

    Waiter() {
      // avoid volatile write, write is made visible by subsequent CAS on waiters field
      putThread(this, Thread.currentThread());
    }

    // non-volatile write to the next field. Should be made visible by subsequent CAS on waiters
    // field.
    void setNext(@Nullable Waiter next) {
      putNext(this, next);
    }

    void unpark() {
      // This is racy with removeWaiter. The consequence of the race is that we may spuriously call
      // unpark even though the thread has already removed itself from the list. But even if we did
      // use a CAS, that race would still exist (it would just be ever so slightly smaller).
      Thread w = thread;
      if (w != null) {
        thread = null;
        LockSupport.unpark(w);
      }
    }
  }

  /*
   * Now that we've initialized everything else, we can run the initialization code for
   * ATOMIC_HELPER. That initialization code may log after we assign to ATOMIC_HELPER.
   */

  private static final AtomicHelper ATOMIC_HELPER;

  static {
    AtomicHelper helper;
    Throwable thrownUnsafeFailure = null;
    Throwable thrownAtomicReferenceFieldUpdaterFailure = null;

    try {
      helper = new UnsafeAtomicHelper();
    } catch (Exception | Error unsafeFailure) { // sneaky checked exception
      thrownUnsafeFailure = unsafeFailure;
      // Catch absolutely everything and fall through to AtomicReferenceFieldUpdaterAtomicHelper.
      try {
        helper = new AtomicReferenceFieldUpdaterAtomicHelper();
      } catch (Exception // sneaky checked exception
          | Error atomicReferenceFieldUpdaterFailure) {
        // Some Android 5.0.x Samsung devices have bugs in JDK reflection APIs that cause
        // getDeclaredField to throw a NoSuchFieldException when the field is definitely there.
        // For these users fallback to a suboptimal implementation, based on synchronized. This
        // will be a definite performance hit to those users.
        thrownAtomicReferenceFieldUpdaterFailure = atomicReferenceFieldUpdaterFailure;
        helper = new SynchronizedHelper();
      }
    }
    ATOMIC_HELPER = helper;

    // Prevent rare disastrous classloading in first call to LockSupport.park.
    // See: https://bugs.openjdk.org/browse/JDK-8074773
    @SuppressWarnings("unused")
    Class<?> ensureLoaded = LockSupport.class;

    // Log after all static init is finished; if an installed logger uses any Futures methods, it
    // shouldn't break in cases where reflection is missing/broken.
    if (thrownAtomicReferenceFieldUpdaterFailure != null) {
      log.get().log(SEVERE, "UnsafeAtomicHelper is broken!", thrownUnsafeFailure);
      log.get()
          .log(
              SEVERE,
              "AtomicReferenceFieldUpdaterAtomicHelper is broken!",
              thrownAtomicReferenceFieldUpdaterFailure);
    }
  }

  // TODO(lukes): investigate using the @Contended annotation on these fields when jdk8 is
  // available.
  /**
   * This field encodes the current state of the future.
   *
   * <p>The valid values are:
   *
   * <ul>
   *   <li>{@code null} initial state, nothing has happened.
   *   <li>{@link Cancellation} terminal state, {@code cancel} was called.
   *   <li>{@link Failure} terminal state, {@code setException} was called.
   *   <li>{@link DelegatingToFuture} intermediate state, {@code setFuture} was called.
   *   <li>{@link #NULL} terminal state, {@code set(null)} was called.
   *   <li>Any other non-null value, terminal state, {@code set} was called with a non-null
   *       argument.
   * </ul>
   */
  private volatile @Nullable Object value;

  /** All listeners. */
  private volatile @Nullable Listener listeners;

  /** All waiting threads. */
  private volatile @Nullable Waiter waiters;

  /** Non-volatile write of the thread to the {@link Waiter#thread} field. */
  private static void putThread(Waiter waiter, Thread newValue) {
    ATOMIC_HELPER.putThread(waiter, newValue);
  }

  /** Non-volatile write of the waiter to the {@link Waiter#next} field. */
  private static void putNext(Waiter waiter, @Nullable Waiter newValue) {
    ATOMIC_HELPER.putNext(waiter, newValue);
  }

  /**
   * Performs a {@linkplain java.lang.invoke.VarHandle#compareAndSet compare-and-set} operation on
   * the {@link #waiters} field.
   */
  private boolean casWaiters(@Nullable Waiter expect, @Nullable Waiter update) {
    return ATOMIC_HELPER.casWaiters(this, expect, update);
  }

  /**
   * Performs a {@linkplain java.lang.invoke.VarHandle#getAndSet get-and-set} operation on the
   * {@link #waiters} field.
   */
  private final @Nullable Waiter gasWaiters(Waiter update) {
    return ATOMIC_HELPER.gasWaiters(this, update);
  }

  /**
   * Marks the given node as 'deleted' (null waiter) and then scans the list to unlink all deleted
   * nodes. This is an O(n) operation in the common case (and O(n^2) in the worst), but we are saved
   * by two things.
   *
   * <ul>
   *   <li>This is only called when a waiting thread times out or is interrupted. Both of which
   *       should be rare.
   *   <li>The waiters list should be very short.
   * </ul>
   */
  private void removeWaiter(Waiter node) {
    node.thread = null; // mark as 'deleted'
    restart:
    while (true) {
      Waiter pred = null;
      Waiter curr = waiters;
      if (curr == Waiter.TOMBSTONE) {
        return; // give up if someone is calling complete
      }
      Waiter succ;
      while (curr != null) {
        succ = curr.next;
        if (curr.thread != null) { // we aren't unlinking this node, update pred.
          pred = curr;
        } else if (pred != null) { // We are unlinking this node and it has a predecessor.
          pred.next = succ;
          if (pred.thread == null) { // We raced with another node that unlinked pred. Restart.
            continue restart;
          }
        } else if (!casWaiters(curr, succ)) { // We are unlinking head
          continue restart; // We raced with an add or complete
        }
        curr = succ;
      }
      break;
    }
  }

  // A heuristic for timed gets. If the remaining timeout is less than this, spin instead of
  // blocking. This value is what AbstractQueuedSynchronizer uses.
  private static final long SPIN_THRESHOLD_NANOS = 1000L;

  private abstract static class AtomicHelper {
    /** Non-volatile write of the thread to the {@link Waiter#thread} field. */
    abstract void putThread(Waiter waiter, Thread newValue);

    /** Non-volatile write of the waiter to the {@link Waiter#next} field. */
    abstract void putNext(Waiter waiter, @Nullable Waiter newValue);

    /** Performs a CAS operation on the {@link AbstractFutureState#waiters} field. */
    abstract boolean casWaiters(
        AbstractFutureState<?> future, @Nullable Waiter expect, @Nullable Waiter update);

    /** Performs a CAS operation on the {@link AbstractFutureState#listeners} field. */
    abstract boolean casListeners(
        AbstractFutureState<?> future, @Nullable Listener expect, Listener update);

    /** Performs a GAS operation on the {@link AbstractFutureState#waiters} field. */
    abstract @Nullable Waiter gasWaiters(AbstractFutureState<?> future, Waiter update);

    /** Performs a GAS operation on the {@link AbstractFutureState#listeners} field. */
    abstract @Nullable Listener gasListeners(AbstractFutureState<?> future, Listener update);

    /** Performs a CAS operation on the {@link AbstractFutureState#value} field. */
    abstract boolean casValue(
        AbstractFutureState<?> future, @Nullable Object expect, Object update);
  }

  /**
   * {@link AtomicHelper} based on {@link sun.misc.Unsafe}.
   *
   * <p>Static initialization of this class will fail if the {@link sun.misc.Unsafe} object cannot
   * be accessed.
   */
  @SuppressWarnings("SunApi") // b/345822163
  private static final class UnsafeAtomicHelper extends AtomicHelper {
    static final Unsafe UNSAFE;
    static final long LISTENERS_OFFSET;
    static final long WAITERS_OFFSET;
    static final long VALUE_OFFSET;
    static final long WAITER_THREAD_OFFSET;
    static final long WAITER_NEXT_OFFSET;

    static {
      Unsafe unsafe = null;
      try {
        unsafe = Unsafe.getUnsafe();
      } catch (SecurityException tryReflectionInstead) {
        try {
          unsafe =
              doPrivileged(
                  (PrivilegedExceptionAction<Unsafe>)
                      () -> {
                        Class<Unsafe> k = Unsafe.class;
                        for (Field f : k.getDeclaredFields()) {
                          f.setAccessible(true);
                          Object x = f.get(null);
                          if (k.isInstance(x)) {
                            return k.cast(x);
                          }
                        }
                        throw new NoSuchFieldError("the Unsafe");
                      });
        } catch (PrivilegedActionException e) {
          throw new RuntimeException("Could not initialize intrinsics", e.getCause());
        }
      }
      try {
        Class<?> abstractFutureState = AbstractFutureState.class;
        WAITERS_OFFSET = unsafe.objectFieldOffset(abstractFutureState.getDeclaredField("waiters"));
        LISTENERS_OFFSET =
            unsafe.objectFieldOffset(abstractFutureState.getDeclaredField("listeners"));
        VALUE_OFFSET = unsafe.objectFieldOffset(abstractFutureState.getDeclaredField("value"));
        WAITER_THREAD_OFFSET = unsafe.objectFieldOffset(Waiter.class.getDeclaredField("thread"));
        WAITER_NEXT_OFFSET = unsafe.objectFieldOffset(Waiter.class.getDeclaredField("next"));
        UNSAFE = unsafe;
      } catch (NoSuchFieldException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    void putThread(Waiter waiter, Thread newValue) {
      UNSAFE.putObject(waiter, WAITER_THREAD_OFFSET, newValue);
    }

    @Override
    void putNext(Waiter waiter, @Nullable Waiter newValue) {
      UNSAFE.putObject(waiter, WAITER_NEXT_OFFSET, newValue);
    }

    @Override
    boolean casWaiters(
        AbstractFutureState<?> future, @Nullable Waiter expect, @Nullable Waiter update) {
      return UNSAFE.compareAndSwapObject(future, WAITERS_OFFSET, expect, update);
    }

    @Override
    boolean casListeners(
        AbstractFutureState<?> future, @Nullable Listener expect, Listener update) {
      return UNSAFE.compareAndSwapObject(future, LISTENERS_OFFSET, expect, update);
    }

    @Override
    @Nullable Listener gasListeners(AbstractFutureState<?> future, Listener update) {
      while (true) {
        Listener listener = future.listeners;
        if (update == listener) {
          return listener;
        }
        if (casListeners(future, listener, update)) {
          return listener;
        }
      }
    }

    @Override
    @Nullable Waiter gasWaiters(AbstractFutureState<?> future, Waiter update) {
      while (true) {
        Waiter waiter = future.waiters;
        if (update == waiter) {
          return waiter;
        }
        if (casWaiters(future, waiter, update)) {
          return waiter;
        }
      }
    }

    @Override
    boolean casValue(AbstractFutureState<?> future, @Nullable Object expect, Object update) {
      return UNSAFE.compareAndSwapObject(future, VALUE_OFFSET, expect, update);
    }
  }

  /** {@link AtomicHelper} based on {@link AtomicReferenceFieldUpdater}. */
  private static final class AtomicReferenceFieldUpdaterAtomicHelper extends AtomicHelper {
    private static final AtomicReferenceFieldUpdater<Waiter, @Nullable Thread> waiterThreadUpdater =
        AtomicReferenceFieldUpdater.<Waiter, @Nullable Thread>newUpdater(
            Waiter.class, Thread.class, "thread");
    private static final AtomicReferenceFieldUpdater<Waiter, @Nullable Waiter> waiterNextUpdater =
        AtomicReferenceFieldUpdater.<Waiter, @Nullable Waiter>newUpdater(
            Waiter.class, Waiter.class, "next");
    private static final AtomicReferenceFieldUpdater<
            ? super AbstractFutureState<?>, @Nullable Waiter>
        waitersUpdater = waitersUpdaterFromWithinAbstractFutureState();
    private static final AtomicReferenceFieldUpdater<
            ? super AbstractFutureState<?>, @Nullable Listener>
        listenersUpdater = listenersUpdaterFromWithinAbstractFutureState();
    private static final AtomicReferenceFieldUpdater<
            ? super AbstractFutureState<?>, @Nullable Object>
        valueUpdater = valueUpdaterFromWithinAbstractFutureState();

    @Override
    void putThread(Waiter waiter, Thread newValue) {
      waiterThreadUpdater.lazySet(waiter, newValue);
    }

    @Override
    void putNext(Waiter waiter, @Nullable Waiter newValue) {
      waiterNextUpdater.lazySet(waiter, newValue);
    }

    @Override
    boolean casWaiters(
        AbstractFutureState<?> future, @Nullable Waiter expect, @Nullable Waiter update) {
      return waitersUpdater.compareAndSet(future, expect, update);
    }

    @Override
    boolean casListeners(
        AbstractFutureState<?> future, @Nullable Listener expect, Listener update) {
      return listenersUpdater.compareAndSet(future, expect, update);
    }

    @Override
    @Nullable Listener gasListeners(AbstractFutureState<?> future, Listener update) {
      return listenersUpdater.getAndSet(future, update);
    }

    @Override
    @Nullable Waiter gasWaiters(AbstractFutureState<?> future, Waiter update) {
      return waitersUpdater.getAndSet(future, update);
    }

    @Override
    boolean casValue(AbstractFutureState<?> future, @Nullable Object expect, Object update) {
      return valueUpdater.compareAndSet(future, expect, update);
    }
  }

  // Returns an {@link AtomicReferenceFieldUpdater} for {@link #waiters}.
  private static AtomicReferenceFieldUpdater<? super AbstractFutureState<?>, @Nullable Waiter>
      waitersUpdaterFromWithinAbstractFutureState() {
    return newUpdater(AbstractFutureState.class, Waiter.class, "waiters");
  }

  // Returns an {@link AtomicReferenceFieldUpdater} for {@link #listeners}.
  private static AtomicReferenceFieldUpdater<? super AbstractFutureState<?>, @Nullable Listener>
      listenersUpdaterFromWithinAbstractFutureState() {
    return newUpdater(AbstractFutureState.class, Listener.class, "listeners");
  }

  // Returns an {@link AtomicReferenceFieldUpdater} for {@link #value}.
  private static AtomicReferenceFieldUpdater<? super AbstractFutureState<?>, @Nullable Object>
      valueUpdaterFromWithinAbstractFutureState() {
    return newUpdater(AbstractFutureState.class, Object.class, "value");
  }

  /**
   * {@link AtomicHelper} based on {@code synchronized} and volatile writes.
   *
   * <p>This is an implementation of last resort for when certain basic VM features are broken (like
   * AtomicReferenceFieldUpdater).
   */
  private static final class SynchronizedHelper extends AtomicHelper {
    @Override
    void putThread(Waiter waiter, Thread newValue) {
      waiter.thread = newValue;
    }

    @Override
    void putNext(Waiter waiter, @Nullable Waiter newValue) {
      waiter.next = newValue;
    }

    @Override
    boolean casWaiters(
        AbstractFutureState<?> future, @Nullable Waiter expect, @Nullable Waiter update) {
      synchronized (future) {
        if (future.waiters == expect) {
          future.waiters = update;
          return true;
        }
        return false;
      }
    }

    @Override
    boolean casListeners(
        AbstractFutureState<?> future, @Nullable Listener expect, Listener update) {
      synchronized (future) {
        if (future.listeners == expect) {
          future.listeners = update;
          return true;
        }
        return false;
      }
    }

    @Override
    @Nullable Listener gasListeners(AbstractFutureState<?> future, Listener update) {
      synchronized (future) {
        Listener old = future.listeners;
        if (old != update) {
          future.listeners = update;
        }
        return old;
      }
    }

    @Override
    @Nullable Waiter gasWaiters(AbstractFutureState<?> future, Waiter update) {
      synchronized (future) {
        Waiter old = future.waiters;
        if (old != update) {
          future.waiters = update;
        }
        return old;
      }
    }

    @Override
    boolean casValue(AbstractFutureState<?> future, @Nullable Object expect, Object update) {
      synchronized (future) {
        if (future.value == expect) {
          future.value = update;
          return true;
        }
        return false;
      }
    }
  }
}
