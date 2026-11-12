package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ends the planner once the input the window holds is closed, a hard kill of the window too. */
final class InputEnd {

    private static final Logger LOG = LoggerFactory.getLogger(InputEnd.class);

    private InputEnd() {}

    /**
     * Reads the input to its end on a daemon thread, then ends the planner and exits with the end's
     * code.
     *
     * @param input the planner's standard input, a pipe the window holds
     * @param end ends the planner and answers its exit code
     * @param exit ends the process with a code
     * @return the reading thread, already started
     */
    static Thread watch(final InputStream input, final IntSupplier end, final IntConsumer exit) {
        final Thread reader =
                Thread.ofPlatform()
                        .daemon()
                        .name("planner-input-end")
                        .unstarted(
                                () -> {
                                    final boolean closed = drain(input);
                                    // Not a daemon, as this thread would make it: once the stop
                                    // has closed the server, nothing else keeps the process up to
                                    // take the code.
                                    Thread.ofPlatform()
                                            .daemon(false)
                                            .name("planner-end")
                                            .start(() -> exit.accept(end(closed, end)));
                                });
        reader.start();
        return reader;
    }

    // A stop that fails must still end the process, or the planner runs on unseen.
    private static int end(final boolean closed, final IntSupplier end) {
        int code;
        try {
            code = end.getAsInt();
        } catch (final RuntimeException | Error failed) {
            LOG.error("The planner could not be stopped as asked; the process ends.", failed);
            code = 1;
        }
        if (!closed && code == 0) {
            code = 1;
        }
        return code;
    }

    /** Reads to the input's end; answers whether it closed rather than broke. */
    private static boolean drain(final InputStream input) {
        final byte[] buffer = new byte[256];
        try {
            while (input.read(buffer) >= 0) {
                // What the window writes carries no meaning; only the input's end does.
            }
            LOG.info("The window's input closed; the planner ends.");
            return true;
        } catch (final IOException gone) {
            // A broken input ends the planner as a closed one does, but not quietly.
            LOG.warn("The window's input cannot be read; the planner ends: {}", gone.getMessage());
            return false;
        }
    }
}
