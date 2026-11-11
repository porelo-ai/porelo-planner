package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;

/** Steps on the planner's own files that Windows refuses while another program has them open. */
final class SharedFiles {

    private static final Duration SHARED_FOR = Duration.ofSeconds(2);

    private static final Duration SHARED_PAUSE = Duration.ofMillis(50);

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
     * scanner or a window has the file open, then fails with the last refusal.
     *
     * @param step the step
     * @throws IOException the last refusal
     */
    static void whileShared(final FileStep step) throws IOException {
        final long end = System.nanoTime() + SHARED_FOR.toNanos();
        while (true) {
            try {
                step.take();
                return;
            } catch (final FileSystemException e) {
                if (System.nanoTime() > end || e instanceof NoSuchFileException) {
                    throw e;
                }
                try {
                    Thread.sleep(SHARED_PAUSE.toMillis());
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
