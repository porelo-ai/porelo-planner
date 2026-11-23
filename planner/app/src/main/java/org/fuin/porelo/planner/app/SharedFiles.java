package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import org.fuin.porelo.wire.LimitFactors;

/** Steps on the planner's own files that Windows refuses while another program has them open. */
final class SharedFiles {

    /** How often a refused step is taken in all, before the factor {@code retries}. */
    static final int SHARED_TRIES = 40;

    /** The pause between two tries, before the factor {@code retries}. */
    static final Duration SHARED_PAUSE = Duration.ofMillis(50);

    private SharedFiles() {}

    /** A step on a file. */
    interface FileStep {

        /**
         * Takes it.
         *
         * @throws IOException when it is refused
         */
        void take() throws IOException;
    }

    /**
     * Takes a step again for a short while when it is refused, as Windows refuses one while a
     * scanner or a window has the file open, then fails with the last refusal; how often, and the
     * pause between, follow the factor {@code retries} of the planner that takes it.
     *
     * @param step the step
     * @param factors the factors of the planner that takes it
     * @throws IOException the last refusal
     */
    static void whileShared(final FileStep step, final LimitFactors factors) throws IOException {
        whileShared(step, factors, Thread::sleep);
    }

    /** Waits between two tries; a test's waits nothing. */
    @FunctionalInterface
    interface Pause {

        /**
         * Waits.
         *
         * @param wait how long
         * @throws InterruptedException when the step is stopped while it waits
         */
        void take(Duration wait) throws InterruptedException;
    }

    /**
     * Takes a step as above, as often and with as long a pause as the factor {@code retries} says.
     *
     * @param step the step
     * @param factors the factors
     * @param pause waits between two tries
     * @throws IOException the last refusal
     */
    static void whileShared(final FileStep step, final LimitFactors factors, final Pause pause)
            throws IOException {
        final int tries = factors.tries(SHARED_TRIES);
        final Duration wait = factors.retryPause(SHARED_PAUSE);
        for (int attempt = 1; ; attempt++) {
            try {
                step.take();
                return;
            } catch (final FileSystemException e) {
                if (attempt >= tries || e instanceof NoSuchFileException) {
                    throw e;
                }
                try {
                    pause.take(wait);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
