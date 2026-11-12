package org.fuin.porelo.planner.app;

import java.util.concurrent.CountDownLatch;

/**
 * A process shaped like the planner: a server thread keeps it alive, and closing it stops that
 * thread first and takes a while longer before it answers its code.
 */
final class InputEndProbe {

    /** The code the probe's end answers. */
    static final int CODE = 3;

    private InputEndProbe() {}

    /**
     * Serves until the input closes, then ends with {@link #CODE}.
     *
     * @param args none
     */
    public static void main(final String[] args) {
        final CountDownLatch stopped = new CountDownLatch(1);
        Thread.ofPlatform()
                .name("server")
                .start(
                        () -> {
                            try {
                                stopped.await();
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
        InputEnd.watch(
                System.in,
                () -> {
                    stopped.countDown();
                    try {
                        // The rest of a context's close, after its server has stopped.
                        Thread.sleep(500);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return CODE;
                },
                System::exit);
    }
}
