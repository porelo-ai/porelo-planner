package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.CommandQueue;
import org.fuin.porelo.planner.store.DirectQueue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.jspecify.annotations.Nullable;

/**
 * The stand-in queue with what a test needs around it and the stand-in does not do: the groups it
 * took, a group held at the queue until released, as a store call under way, and an outcome or a
 * throw given once in place of writing.
 */
final class HeldQueue implements CommandQueue {

    private final DirectQueue direct;

    /** The groups taken, in order. */
    final List<CommandGroup> groups = new ArrayList<>();

    /** Counted down when a held group reaches the queue. */
    volatile @Nullable CountDownLatch entered;

    /** Awaited by a held group before it is written. */
    volatile @Nullable CountDownLatch release;

    /** What the next group is answered instead of being written, once. */
    volatile @Nullable CommandOutcome answer;

    /** What the next submit throws instead of answering, once. */
    volatile @Nullable Throwable failure;

    HeldQueue(final PlanningStore store) {
        this.direct = new DirectQueue(store);
    }

    /** Holds the next group until the second latch is counted down; the first tells it came. */
    CountDownLatch[] holdNext() {
        final CountDownLatch in = new CountDownLatch(1);
        final CountDownLatch out = new CountDownLatch(1);
        entered = in;
        release = out;
        return new CountDownLatch[] {in, out};
    }

    @Override
    public CommandOutcome submit(final CommandGroup group) {
        synchronized (groups) {
            groups.add(group);
        }
        final CountDownLatch in = entered;
        final CountDownLatch out = release;
        if (in != null && out != null) {
            entered = null;
            release = null;
            in.countDown();
            try {
                if (!out.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The held group was never released");
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        final Throwable thrown = failure;
        if (thrown != null) {
            failure = null;
            if (thrown instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) thrown;
        }
        final CommandOutcome given = answer;
        if (given != null) {
            answer = null;
            return given;
        }
        return direct.submit(group);
    }
}
