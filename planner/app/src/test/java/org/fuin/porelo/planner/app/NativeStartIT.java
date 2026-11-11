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
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void theNativeExecutableServesAProjectReadFromGit(@TempDir final Path dir) throws Exception {
        // Reading Git, YAML and answering JSON reach code only a native run shows to lack
        // metadata; the window would get a planner that starts and answers nothing.
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        final Path runtime = RuntimeDirectories.create();
        final Path output = Files.createTempFile("porelo-planner-reads", ".log");
        try (FixtureProject project = FixtureProject.serve(dir)) {
            final ProcessBuilder builder =
                    new ProcessBuilder(executable.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().putAll(project.environment());
            builder.environment().put("XDG_RUNTIME_DIR", runtime.toString());
            final Process planner = builder.start();
            try {
                final RuntimeDirectories.Address address = addressWithin(runtime, planner, output);
                final String log = "; the planner wrote:%n" + Files.readString(output);
                assertThat(call(address, "PUT", "/projects/alpha", project.source()))
                        .as("the project given" + log)
                        .startsWith("204 ");
                assertThat(call(address, "GET", "/projects/alpha/workflow", null))
                        .as("the workflow" + log)
                        .startsWith("200 ")
                        .contains(
                                "\"kind\":\"WORKING\"",
                                "\"kind\":\"CLOSED\"",
                                "\"per\":\"repository\"");
                assertThat(call(address, "GET", "/projects/alpha/stories", null))
                        .as("the stories" + log)
                        .startsWith("200 ")
                        .contains("\"id\":\"ST7\"", "\"stateGiven\":false", "\"work\":null");
                assertThat(call(address, "GET", "/projects/alpha/stories/ST7", null))
                        .as("one story" + log)
                        .contains("A story served to the window.");
                assertThat(call(address, "GET", "/projects/alpha/stories/ST7/issues", null))
                        .as("a story's issues" + log)
                        .contains("\"id\":\"XY07\"", "\"parent\":null");
                assertThat(call(address, "GET", "/projects/alpha/issues/XY07", null))
                        .as("one issue" + log)
                        .contains("\"whatMustBeTrue\":\"It is served.\"");
                assertThat(call(address, "GET", "/projects/alpha/issues/XY99", null))
                        .as("an unknown issue" + log)
                        .startsWith("404 ")
                        .contains("urn:porelo-planner:NoSuchIssue");
            } finally {
                stop(planner);
            }
        } finally {
            Files.deleteIfExists(output);
            RuntimeDirectories.delete(runtime);
        }
    }

    @Test
    void theNativeExecutablePersistsOffersAndReportsAgainstARealStore(@TempDir final Path dir)
            throws Exception {
        // Prevents missing native JSON bindings from losing offers, task bindings or accepted ends.
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        final Path runtime = RuntimeDirectories.create();
        final Path output = Files.createTempFile("porelo-planner-work", ".log");
        final tools.jackson.databind.json.JsonMapper json =
                tools.jackson.databind.json.JsonMapper.builder().build();
        try (FixtureProject project = FixtureProject.serve(dir)) {
            final ProcessBuilder builder =
                    new ProcessBuilder(executable.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().putAll(project.environment());
            builder.environment().put("XDG_RUNTIME_DIR", runtime.toString());
            builder.environment().put("GIT_AUTHOR_NAME", "A Person");
            builder.environment().put("GIT_AUTHOR_EMAIL", "person@example.org");
            builder.environment().put("GIT_COMMITTER_NAME", "A Person");
            builder.environment().put("GIT_COMMITTER_EMAIL", "person@example.org");
            final Process planner = builder.start();
            try {
                final RuntimeDirectories.Address address = addressWithin(runtime, planner, output);
                assertThat(call(address, "PUT", "/projects/alpha", project.source()))
                        .as("the test's own planning store")
                        .startsWith("204 ");
                final String stories = call(address, "GET", "/projects/alpha/stories", null);
                final String revision =
                        json.readTree(stories.substring(4)).path("revision").asString();
                assertThat(
                                call(
                                        address,
                                        "POST",
                                        "/projects/alpha/moves",
                                        json.writeValueAsString(
                                                java.util.Map.of(
                                                        "revision",
                                                        revision,
                                                        "moves",
                                                        java.util.List.of(
                                                                java.util.Map.of(
                                                                        "issue", "XY07", "to",
                                                                        "Backlog"))))))
                        .as("preparing work uses the real move contract")
                        .startsWith("200 ");
                final String prepared = call(address, "GET", "/projects/alpha/stories", null);
                final String base =
                        json.readTree(prepared.substring(4)).path("revision").asString();
                final String order =
                        call(
                                address,
                                "POST",
                                "/projects/alpha/stories/ST7/work",
                                json.writeValueAsString(
                                        java.util.Map.of(
                                                "project",
                                                "alpha",
                                                "story",
                                                "ST7",
                                                "to",
                                                "Implementing",
                                                "mode",
                                                "UNATTENDED",
                                                "revision",
                                                base)));
                assertThat(order).as("the offer is persisted before answering").startsWith("200 ");
                final String offer =
                        json.readTree(order.substring(4)).at("/tasks/0/offer").asString();
                final String started =
                        call(
                                address,
                                "POST",
                                "/projects/alpha/tasks/build/events",
                                json.writeValueAsString(
                                        java.util.Map.of(
                                                "offer",
                                                offer,
                                                "event",
                                                "STARTED",
                                                "container",
                                                "build-box")));
                assertThat(started)
                        .as("a native start reads the saved offer and binds it")
                        .startsWith("200 ")
                        .contains("\"atWork\":true");
                final String ended =
                        call(
                                address,
                                "POST",
                                "/projects/alpha/tasks/build/events",
                                json.writeValueAsString(
                                        java.util.Map.of(
                                                "offer",
                                                offer,
                                                "event",
                                                "ENDED",
                                                "container",
                                                "build-box",
                                                "ended",
                                                java.util.Map.of(
                                                        "at",
                                                        "2026-01-01T00:00:00Z",
                                                        "finished",
                                                        true,
                                                        "why",
                                                        "",
                                                        "answer",
                                                        "Built",
                                                        "unpushed",
                                                        0))));
                assertThat(ended)
                        .as("an accepted native end saves and serializes its disposition")
                        .startsWith("200 ")
                        .contains("\"finished\":true", "\"tasks\":[]");
                assertThat(call(address, "GET", "/projects/alpha/stories/ST7/work", null))
                        .as("a later call reads the final record from disk")
                        .startsWith("200 ")
                        .contains("Built", "\"atWork\":false");
            } finally {
                stop(planner);
            }
        } finally {
            Files.deleteIfExists(output);
            RuntimeDirectories.delete(runtime);
        }
    }

    @Test
    void theNativeExecutableServesTheMockBoardWithMock() throws Exception {
        // The mock board is a resource and its records are read by reflection; a native image
        // told of neither starts and leaves the window's tests without a board.
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        final Path runtime = RuntimeDirectories.create();
        final Path output = Files.createTempFile("porelo-planner-mock", ".log");
        try {
            final ProcessBuilder builder =
                    new ProcessBuilder(executable.toString(), "--mock")
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().put("PATH", "/usr/bin:/bin");
            builder.environment().put("XDG_RUNTIME_DIR", runtime.toString());
            final Process planner = builder.start();
            try {
                final RuntimeDirectories.Address address = addressWithin(runtime, planner, output);
                final String log = "; the planner wrote:%n" + Files.readString(output);
                assertThat(
                                call(
                                        address,
                                        "PUT",
                                        "/projects/any",
                                        "{\"repository\":"
                                                + " \"https://example.org/porelo/porelo.git\"}"))
                        .as("a project given in the mock mode" + log)
                        .startsWith("204 ");
                assertThat(call(address, "GET", "/projects/any/stories", null))
                        .as("the mock board's stories" + log)
                        .startsWith("200 ")
                        .contains("\"revision\":\"mock@1\"");
                assertThat(call(address, "POST", "/mock/reset", null))
                        .as("a reset" + log)
                        .startsWith("204 ");
            } finally {
                stop(planner);
            }
        } finally {
            Files.deleteIfExists(output);
            RuntimeDirectories.delete(runtime);
        }
    }

    @Test
    void theNativeExecutableStreamsAPushMadeElsewhere(@TempDir final Path dir) throws Exception {
        // The board goes out through the event stream, which no return type names; a native image
        // told nothing of it would end the stream at its first event.
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        final Path runtime = RuntimeDirectories.create();
        final Path output = Files.createTempFile("porelo-planner-events", ".log");
        try (FixtureProject project = FixtureProject.serve(dir)) {
            final ProcessBuilder builder =
                    new ProcessBuilder(executable.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().putAll(project.environment());
            builder.environment().put("XDG_RUNTIME_DIR", runtime.toString());
            final Process planner = builder.start();
            try {
                final RuntimeDirectories.Address address = addressWithin(runtime, planner, output);
                final String log = "; the planner wrote:%n" + Files.readString(output);
                assertThat(call(address, "PUT", "/projects/alpha", project.source()))
                        .as("the project given" + log)
                        .startsWith("204 ");
                final HttpRequest request =
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + address.port()
                                                        + "/projects/alpha/events"))
                                .header("Authorization", "Bearer " + address.token())
                                .build();
                final HttpResponse<java.util.stream.Stream<String>> stream =
                        HttpClient.newHttpClient()
                                .send(request, HttpResponse.BodyHandlers.ofLines());
                assertThat(stream.statusCode()).as("the stream's status" + log).isEqualTo(200);
                final java.util.Iterator<String> lines = stream.body().iterator();
                final String first = nextBoard(lines);
                project.pushElsewhere();
                assertThat(call(address, "POST", "/projects/alpha/refresh", null))
                        .as("the refresh" + log)
                        .startsWith("200 ");
                final String second = nextBoard(lines);
                assertThat(second).as("a second board after the push").isNotEqualTo(first);
                stream.body().close();
            } finally {
                stop(planner);
            }
        } finally {
            Files.deleteIfExists(output);
            RuntimeDirectories.delete(runtime);
        }
    }

    /** The data line of the next board event, read on with a bound, so a silent stream fails. */
    private static String nextBoard(final java.util.Iterator<String> lines) throws Exception {
        final java.util.concurrent.FutureTask<String> read =
                new java.util.concurrent.FutureTask<>(
                        () -> {
                            boolean board = false;
                            while (lines.hasNext()) {
                                final String line = lines.next();
                                if (line.equals("event:board")) {
                                    board = true;
                                } else if (board && line.startsWith("data:")) {
                                    return line;
                                }
                            }
                            throw new AssertionError("the stream ended without a board");
                        });
        Thread.ofVirtual().start(read);
        return read.get(30, TimeUnit.SECONDS);
    }

    /** The status and body of a call with the token. */
    private static String call(
            final RuntimeDirectories.Address address,
            final String method,
            final String path,
            final @Nullable String body)
            throws IOException, InterruptedException {
        final HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + address.port() + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + address.token())
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build();
        final HttpResponse<String> answer =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return answer.statusCode() + " " + answer.body();
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
