package org.fuin.porelo.planner.store.github;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Writes a project's board copy behind its saves: only the newest job waiting runs, so a write or a
 * removal given later always wins over one given before it, and no save waits for the disk. Once
 * finished it takes no job, so a store retired never writes over the copy of the store that
 * replaced it.
 */
final class CopyWriter {

    private final Executor executor;

    private final AtomicReference<@Nullable Runnable> next = new AtomicReference<>();

    /** Held while a job runs, so the jobs run one at a time in the order they were taken. */
    private final Object running = new Object();

    /** Whether the store retired, after which no job is taken. */
    private volatile boolean finished;

    /**
     * Makes the writer.
     *
     * @param executor runs the writes; {@code Runnable::run} writes in the caller
     */
    CopyWriter(final Executor executor) {
        this.executor = executor;
    }

    /**
     * Gives the newest job, replacing any one still waiting.
     *
     * @param job writes or removes the copy
     * @return false when the writer is finished and the job is not taken
     */
    boolean offer(final Runnable job) {
        if (finished) {
            return false;
        }
        if (next.getAndSet(job) == null) {
            executor.execute(this::drain);
        }
        return true;
    }

    /**
     * Runs the job waiting, if any, in the caller, after the one running ends, and takes no job
     * after it.
     */
    void finish() {
        finished = true;
        drain();
    }

    private void drain() {
        synchronized (running) {
            Runnable job = next.getAndSet(null);
            while (job != null) {
                job.run();
                job = next.getAndSet(null);
            }
        }
    }
}
