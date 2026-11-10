package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The native executable, started and called as the window will; run by {@code -Pnative verify}. */
class NativeStartIT {

    private static final Duration START = Duration.ofSeconds(10);

    private static final Duration STOP = Duration.ofSeconds(5);

    @Test
    void theNativeExecutableStartsSaysItIsUpAndStopsWhenAsked() throws Exception {
        // An executable that builds but does not start - missing metadata, a wrong CPU target -
        // ships broken; one that ignores being stopped outlives the window that started it.
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        assertThat(executable).as("the native executable the build wrote").isExecutable();
        final int port = freePort();
        final Path output = Files.createTempFile("porelo-planner-start", ".log");
        try {
            final Process planner =
                    new ProcessBuilder(executable.toString(), "--server.port=" + port)
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
            try {
                final String answer = healthWithin(port, planner);
                assertThat(answer)
                        .as("the health answer; the planner wrote:%n%s", Files.readString(output))
                        .isEqualTo("200 {\"status\":\"UP\"}");
            } finally {
                planner.destroy();
                final boolean stopped = planner.waitFor(STOP.toSeconds(), TimeUnit.SECONDS);
                if (!stopped) {
                    planner.destroyForcibly().waitFor();
                }
                assertThat(stopped)
                        .as("the planner stopped within %s of being asked", STOP)
                        .isTrue();
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /** The first answer with status 200, or why there was none. */
    private static String healthWithin(final int port, final Process planner)
            throws InterruptedException {
        final HttpClient client = HttpClient.newHttpClient();
        final HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                        .timeout(Duration.ofSeconds(1))
                        .build();
        final long end = System.nanoTime() + START.toNanos();
        String last = "no answer within " + START;
        while (System.nanoTime() < end && planner.isAlive()) {
            try {
                final HttpResponse<String> answer =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                last = answer.statusCode() + " " + answer.body();
                if (answer.statusCode() == 200) {
                    return last;
                }
            } catch (IOException notYet) {
                // Not listening yet.
            }
            Thread.sleep(100);
        }
        return planner.isAlive() ? last : "the planner ended with exit code " + planner.exitValue();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
