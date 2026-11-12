package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Ending the planner once its input closes, over fakes and a probe process. */
class InputEndTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void anEndThatFailsStillEndsTheProcessWithAFailureCode() throws Exception {
        // A planner whose stop fails would run on after the window is gone, unseen.
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        InputEnd.watch(
                new ByteArrayInputStream(new byte[0]),
                () -> {
                    throw new IllegalStateException("the context cannot be closed");
                },
                exit::complete);
        assertThat(exit.get(WAIT.toSeconds(), TimeUnit.SECONDS))
                .as("the exit code after the end failed")
                .isEqualTo(1);
    }

    @Test
    void theEndsCodeReachesTheProcessAfterItsServerHasStopped() throws Exception {
        // A failure code lost to the process ending by itself once its server stopped would tell
        // the window that a failed or broken planner ended cleanly.
        final Process probe =
                new ProcessBuilder(
                                ProcessHandle.current().info().command().orElseThrow(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                InputEndProbe.class.getName())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            probe.getOutputStream().close();
            assertThat(probe.waitFor(30, TimeUnit.SECONDS))
                    .as("the probe ended once its input closed")
                    .isTrue();
            assertThat(probe.exitValue())
                    .as("the probe's exit code, the code its end answered")
                    .isEqualTo(InputEndProbe.CODE);
        } finally {
            probe.destroyForcibly().waitFor();
        }
    }

    @Test
    void anInputThatCannotBeReadEndsThePlannerWithAFailureCode() throws Exception {
        // An input that breaks is ended on as a closed one is, but a code of 0 would hide it.
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final AtomicBoolean ended = new AtomicBoolean();
        final InputStream broken =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("the pipe is broken");
                    }

                    @Override
                    public int read(final byte[] buffer, final int offset, final int length)
                            throws IOException {
                        throw new IOException("the pipe is broken");
                    }
                };
        InputEnd.watch(
                broken,
                () -> {
                    ended.set(true);
                    return 0;
                },
                exit::complete);
        assertThat(exit.get(WAIT.toSeconds(), TimeUnit.SECONDS))
                .as("the exit code after the input could not be read")
                .isNotZero();
        assertThat(ended).as("the planner was stopped before the process ended").isTrue();
    }
}
