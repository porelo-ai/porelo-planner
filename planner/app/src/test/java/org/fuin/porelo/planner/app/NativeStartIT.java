package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The native executable, started and called as the window will; run by {@code -Pnative verify}. */
class NativeStartIT {

    private static final Duration START = Duration.ofSeconds(10);

    private static final Duration STOP = Duration.ofSeconds(5);

    @Test
    void theNativeExecutableIsFoundThroughItsAddressFileAndLetsInOnlyItsToken() throws Exception {
        // An executable that builds but does not start - missing metadata, a wrong CPU target -
        // ships broken; one without the token check lets every account on this computer in; one
        // that ignores being stopped outlives the window that started it.
        final String first = startCallAndStop(true);
        final String second = startCallAndStop(false);
        assertThat(second)
                .as("the token of a second start; one made at build time would be the same")
                .isNotEqualTo(first);
    }

    /** Starts the planner, calls it with and without its token, stops it; returns the token. */
    private static String startCallAndStop(final boolean callWithoutToken) throws Exception {
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        assertThat(executable).as("the native executable the build wrote").isExecutable();
        final Path runtime = RuntimeDirectories.create();
        final Path output = Files.createTempFile("porelo-planner-start", ".log");
        try {
            final ProcessBuilder builder =
                    new ProcessBuilder(executable.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            builder.environment().put("XDG_RUNTIME_DIR", runtime.toString());
            final Process planner = builder.start();
            final RuntimeDirectories.Address address;
            try {
                address = addressWithin(runtime, planner, output);
                assertThat(health(address.port(), address.token()))
                        .as(
                                "the health answer with the token; the planner wrote:%n%s",
                                Files.readString(output))
                        .isEqualTo("200 {\"status\":\"UP\"}");
                if (callWithoutToken) {
                    assertThat(health(address.port(), null))
                            .as("the health answer without the token")
                            .isEqualTo("401 ");
                }
            } finally {
                stop(planner);
            }
            assertThat(runtime.resolve(AddressFile.DIRECTORY).resolve(AddressFile.FILE))
                    .as("the address file after the planner stopped")
                    .doesNotExist();
            return address.token();
        } finally {
            Files.deleteIfExists(output);
            RuntimeDirectories.delete(runtime);
        }
    }

    private static void stop(final Process planner) throws InterruptedException {
        planner.destroy();
        final boolean stopped = planner.waitFor(STOP.toSeconds(), TimeUnit.SECONDS);
        if (!stopped) {
            planner.destroyForcibly().waitFor();
        }
        assertThat(stopped).as("the planner stopped within %s of being asked", STOP).isTrue();
    }

    /** The address the planner wrote once it listens, or a failure saying why there is none. */
    private static RuntimeDirectories.Address addressWithin(
            final Path runtime, final Process planner, final Path output) throws Exception {
        final Path file = runtime.resolve(AddressFile.DIRECTORY).resolve(AddressFile.FILE);
        final long end = System.nanoTime() + START.toNanos();
        while (System.nanoTime() < end && planner.isAlive()) {
            if (Files.exists(file)) {
                return RuntimeDirectories.read(runtime);
            }
            Thread.sleep(100);
        }
        throw new AssertionError(
                (planner.isAlive()
                                ? "no address file within " + START
                                : "the planner ended with exit code " + planner.exitValue())
                        + "; it wrote:\n"
                        + Files.readString(output));
    }

    /** The status and body of a health call. */
    private static String health(final int port, final @Nullable String token)
            throws IOException, InterruptedException {
        final HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                        .timeout(Duration.ofSeconds(1));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        final HttpResponse<String> answer =
                HttpClient.newHttpClient()
                        .send(request.build(), HttpResponse.BodyHandlers.ofString());
        return answer.statusCode() + " " + answer.body();
    }
}
