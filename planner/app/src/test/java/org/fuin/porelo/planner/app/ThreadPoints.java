package org.fuin.porelo.planner.app;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Work on a thread of its own, and a wait for the point that thread reached: parked or blocked
 * inside a named method. A test forces its order on that point, never on how long a thread takes,
 * where the code under test has no hook a latch could use.
 *
 * @param <T> what the work answers
 * @param thread the thread the work runs on
 * @param task the work, answered through it
 */
record ThreadPoints<T>(Thread thread, FutureTask<T> task) {

    private static final Set<Thread.State> WAITING =
            Set.of(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED);

    /** Starts work on a thread of its own. */
    static <T> ThreadPoints<T> start(final Callable<T> work) {
        final FutureTask<T> task = new FutureTask<>(work);
        return new ThreadPoints<>(Thread.ofPlatform().daemon().start(task), task);
    }

    /** Starts work that answers nothing on a thread of its own. */
    static ThreadPoints<Void> run(final Runnable work) {
        return start(
                () -> {
                    work.run();
                    return null;
                });
    }

    /**
     * Waits until the thread is parked or blocked inside the method of that name, the point it must
     * reach before the test goes on; fails when it ends or does not get there in 10 s.
     */
    void reaches(final String method) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!(WAITING.contains(thread.getState())
                && Arrays.stream(thread.getStackTrace())
                        .anyMatch(frame -> frame.getMethodName().equals(method)))) {
            if (!thread.isAlive() || System.nanoTime() > deadline) {
                throw new AssertionError("The thread did not wait inside " + method);
            }
            Thread.onSpinWait();
        }
    }
}
