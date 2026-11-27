package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.git.ServedRepositories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The native executable, started and called as the window will; run by {@code -Pnative verify}. A
 * test tagged {@code linux} needs the test's Git server, which serves over Linux paths alone.
 */
class NativeStartIT {

    private static final Duration START = Duration.ofSeconds(10);

    /** How long the planner lets one PowerShell call take before it refuses to start. */
    private static final Duration POWERSHELL_LIMIT = Duration.ofSeconds(60);

    /** The PowerShell calls of a start: the runtime, log and state directories. */
    private static final int PLANNER_CALLS = 3;

    /** The PowerShell calls of a start with --mock: the runtime directory alone. */
    private static final int MOCK_CALLS = 1;

    private static final Duration STOP = Duration.ofSeconds(5);

    private static final Duration END = Duration.ofSeconds(10);

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
    @Tag("linux")
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
                final RuntimeDirectories.Address address =
                        addressWithin(runtime, planner, output, PLANNER_CALLS);
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
                        .contains("\"id\":\"ST7\"", "\"closedAt\":null", "\"work\":null");
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
    @Tag("linux")
    void theNativeExecutableReadsALocalProjectThroughTheCLibrary(@TempDir final Path dir)
            throws Exception {
        // The local store opens, judges, locks and reads its files through the C library's own
        // calls; one the image does not register fails only in a native run, and the window
        // would get a planner that answers no local project.
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        final Path runtime = RuntimeDirectories.create();
        final Path output = Files.createTempFile("porelo-planner-local", ".log");
        final Path data =
                Files.createDirectory(
                        dir.resolve("data"),
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rwx------")));
        try (FixtureProject project =
                FixtureProject.serve(
                        dir,
                        """
                        repositories:
                            engine:
                                central: "https://github.com/porelo-ai/engine"
                                issues: ["E"]
                        """,
                        """
                        tracker:
                            kind: local
                        """)) {
            final ProcessBuilder builder =
                    new ProcessBuilder(executable.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().putAll(project.environment());
            builder.environment().put("XDG_RUNTIME_DIR", runtime.toString());
            builder.environment().put("XDG_DATA_HOME", data.toString());
            final Process planner = builder.start();
            try {
                final RuntimeDirectories.Address address =
                        addressWithin(runtime, planner, output, PLANNER_CALLS);
                assertThat(call(address, "PUT", "/projects/alpha", project.source()))
                        .as("the project given; the planner wrote:%n" + Files.readString(output))
                        .startsWith("204 ");
                assertThat(call(address, "GET", "/projects/alpha/workflow", null))
                        .as("the local tracker; the planner wrote:%n" + Files.readString(output))
                        .startsWith("200 ")
                        .contains("\"kind\":\"local\"");
                final Path items =
                        Files.createDirectories(
                                data.resolve("porelo-planner/items/alpha"),
                                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                        java.nio.file.attribute.PosixFilePermissions.fromString(
                                                "rwx------")));
                Files.writeString(
                        items.resolve("engine-1.md"),
                        "```json\n{\n  \"format\": \"local-item-1\",\n  \"kind\": \"issue\",\n"
                                + "  \"title\": \"Read through the C library\",\n"
                                + "  \"repository\": \"engine\",\n  \"number\": 1,\n"
                                + "  \"parent\": null,\n  \"issues\": [],\n"
                                + "  \"blockedBy\": []\n}\n```\n");
                // A tracked project's refresh answers before its read, so the list is asked until
                // the read has landed.
                assertThat(call(address, "POST", "/projects/alpha/refresh", null))
                        .as("the refresh; the planner wrote:%n" + Files.readString(output))
                        .startsWith("200 ");
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String listed = call(address, "GET", "/projects/alpha/issues?story=none", null);
                while (!listed.contains("engine#1") && System.nanoTime() < deadline) {
                    Thread.sleep(200);
                    listed = call(address, "GET", "/projects/alpha/issues?story=none", null);
                }
                assertThat(listed)
                        .as("the item read; the planner wrote:%n" + Files.readString(output))
                        .startsWith("200 ")
                        .contains("\"id\":\"engine#1\"", "Read through the C library");
            } finally {
                stop(planner);
            }
        } finally {
            Files.deleteIfExists(output);
            RuntimeDirectories.delete(runtime);
        }
    }

    @Test
    @Tag("linux")
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
                final RuntimeDirectories.Address address =
                        addressWithin(runtime, planner, output, PLANNER_CALLS);
                assertThat(call(address, "PUT", "/projects/alpha", project.source()))
                        .as("the test's own planning store")
                        .startsWith("204 ");
                // The idea format is a resource; an image without it cannot build the brief.
                assertThat(
                                call(
                                        address,
                                        "POST",
                                        "/projects/alpha/ideas/discussion",
                                        "{\"idea\":\"An idea\"}"))
                        .as("a discussion's brief carries the idea format from the image")
                        .startsWith("200 ")
                        .contains("\"kind\":\"discussion\"", "idea-1.json");
                final String stories = call(address, "GET", "/projects/alpha/stories", null);
                final String revision =
                        json.readTree(stories.substring(4)).path("revision").asString();
                final String batch =
                        json.writeValueAsString(
                                java.util.Map.of(
                                        "revision",
                                        revision,
                                        "moves",
                                        List.of(
                                                java.util.Map.of(
                                                        "issue", "XY07", "to", "Backlog"))));
                final String preview =
                        call(address, "POST", "/projects/alpha/moves/preview", batch);
                assertThat(preview).as("the move's preview").startsWith("200 ");
                assertThat(
                                call(
                                        address,
                                        "POST",
                                        "/projects/alpha/moves",
                                        Previewed.moves(batch, preview.substring(4))))
                        .as("preparing work uses the real move contract")
                        .startsWith("200 ");
                final String prepared = call(address, "GET", "/projects/alpha/stories", null);
                final tools.jackson.databind.JsonNode seen =
                        json.readTree(Seens.ofAnswer(prepared.substring(4)));
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
                                                "seen",
                                                seen)));
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
                                                "build-box",
                                                "machine",
                                                "Build box")));
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
                                                "machine",
                                                "Build box",
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
    void theNativeExecutableEndsWithItsInputOnlyWhenToldTo() throws Exception {
        // The window starts the native executable; one that outlives the window serves the next,
        // newer window from an older build, and one started by hand must not end with its input.
        assertThat(endsWhenItsInputCloses(List.of(PlannerApplication.END_WITH_INPUT)))
                .as(
                        "a planner started with %s, after its input closed",
                        PlannerApplication.END_WITH_INPUT)
                .isTrue();
        assertThat(endsWhenItsInputCloses(List.of()))
                .as("a planner started without it, after its input closed")
                .isFalse();
    }

    /** Starts the executable, closes its input, and answers whether it ended with its address. */
    private static boolean endsWhenItsInputCloses(final List<String> options) throws Exception {
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        final Path runtime = RuntimeDirectories.create();
        final Path output = Files.createTempFile("porelo-planner-input", ".log");
        try {
            final List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.addAll(options);
            final ProcessBuilder builder =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            // The test's address file and log, never the person's.
            builder.environment().putAll(RuntimeDirectories.environment(runtime));
            final Process planner = builder.start();
            try {
                addressWithin(runtime, planner, output, PLANNER_CALLS);
                planner.getOutputStream().close();
                final boolean ended = planner.waitFor(END.toSeconds(), TimeUnit.SECONDS);
                if (ended) {
                    assertThat(planner.exitValue())
                            .as("the exit code of a planner whose input closed")
                            .isZero();
                    assertThat(runtime.resolve(AddressFile.DIRECTORY).resolve(AddressFile.FILE))
                            .as("the address file after the planner ended with its input")
                            .doesNotExist();
                }
                return ended;
            } finally {
                planner.destroyForcibly().waitFor();
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
        // On Windows the account's own base is LOCALAPPDATA, which --mock must be apart from.
        final @Nullable Path own = RuntimeDirectories.WINDOWS ? RuntimeDirectories.create() : null;
        final Path output = Files.createTempFile("porelo-planner-mock", ".log");
        try {
            final ProcessBuilder builder =
                    new ProcessBuilder(executable.toString(), "--mock")
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().putAll(RuntimeDirectories.system());
            if (own != null) {
                // An account's start always has these; PowerShell without them is the one start
                // seen to miss its bound, so the start is tested as an account makes it.
                for (final String name : List.of("TEMP", "TMP", "USERPROFILE", "APPDATA")) {
                    final String value = System.getenv(name);
                    if (value != null && !value.isBlank()) {
                        builder.environment().put(name, value);
                    }
                }
                builder.environment().put("LOCALAPPDATA", own.toString());
            }
            builder.environment().put("XDG_RUNTIME_DIR", runtime.toString());
            final Process planner = builder.start();
            try {
                final RuntimeDirectories.Address address =
                        addressWithin(runtime, planner, output, MOCK_CALLS);
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
                assertThat(call(address, "POST", "/mock/projects/any/reviewer", null))
                        .as("a reviewer made by the planner's flow in the image" + log)
                        .startsWith("204 ");
                assertThat(call(address, "GET", "/projects/any/stories/ST14/work", null))
                        .as("the reviewer's order served" + log)
                        .startsWith("200 ")
                        .contains("\"role\":{\"name\":\"reviewer\"", "\"ownTask\":true");
                assertThat(call(address, "POST", "/mock/reset", null))
                        .as("a reset" + log)
                        .startsWith("204 ");
            } finally {
                stop(planner);
            }
        } finally {
            Files.deleteIfExists(output);
            RuntimeDirectories.delete(runtime);
            if (own != null) {
                RuntimeDirectories.delete(own);
            }
        }
    }

    @Test
    @Tag("linux")
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
                final RuntimeDirectories.Address address =
                        addressWithin(runtime, planner, output, PLANNER_CALLS);
                final String log = "; the planner wrote:%n" + Files.readString(output);
                assertThat(call(address, "PUT", "/projects/alpha", project.source()))
                        .as("the project given" + log)
                        .startsWith("204 ");
                final HttpResponse<java.util.stream.Stream<String>> stream =
                        Signing.stream(address, "/projects/alpha/events");
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

    /** The status and body of a signed call, its answer held to its proof. */
    private static String call(
            final RuntimeDirectories.Address address,
            final String method,
            final String path,
            final @Nullable String body)
            throws IOException, InterruptedException {
        final Signing.Signed request =
                Signing.sign(address, method, path, "application/json", body);
        request.builder().timeout(Duration.ofSeconds(30));
        final HttpResponse<String> answer = request.send();
        return answer.statusCode() + " " + answer.body();
    }

    @Test
    void theNativeExecutableWritesAndReadsBackEveryBoundTypeAsTheJvmDoes(@TempDir final Path dir)
            throws Exception {
        // Harm: a type the image cannot bind, such as a GitHub answer or a record of the board's
        // copy, found only when a person's planner reads or writes it.
        final Path executable = Path.of(System.getProperty("planner.executable", "missing"));
        assertThat(executable).as("the native executable the build wrote").isExecutable();
        final java.io.ByteArrayOutputStream expected = new java.io.ByteArrayOutputStream();
        assertThat(
                        SerializationCheck.run(
                                new java.io.PrintStream(
                                        expected, true, java.nio.charset.StandardCharsets.UTF_8),
                                System.err))
                .as("the JVM's own check")
                .isZero();
        final Path out = dir.resolve("out");
        final Path err = dir.resolve("err");
        final Process process =
                new ProcessBuilder(executable.toString(), SerializationCheck.NAME)
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile())
                        .start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the check ends by itself").isTrue();
        assertThat(process.exitValue()).as("the image's check, %s", Files.readString(err)).isZero();
        assertThat(Files.readString(out))
                .as("each type with as many values as the JVM writes")
                .isEqualTo(expected.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource({
        "range, false, 0",
        "range, true, 1",
        "event, false, 0",
        "event, true, 1",
        "missing-event, false, 2"
    })
    void theNativeExecutableChecksCommitsAsTheJvmDoes(
            final String mode, final boolean outside, final int code, @TempDir final Path dir)
            throws Exception {
        // Harm: a native commit check passes an unsigned outside commit or cannot read an event.
        final ServedRepositories repositories = new ServedRepositories(dir);
        final String name = "Describe the work";
        repositories.repository(name, Map.of("sample", "before"));
        final String base = repositories.head(name);
        repositories.repository(name, Map.of("sample", "after"));
        final String head = repositories.head(name);
        final Path root = repositories.work(name);
        final List<String> args = new ArrayList<>(List.of("check-commits"));
        final Map<String, String> environment = new HashMap<>();
        if (mode.equals("range")) {
            args.add(base + ".." + head);
            if (outside) args.add("--outside");
        } else {
            args.add("--event");
            final Path event = dir.resolve("event.json");
            if (mode.equals("event")) {
                Files.writeString(
                        event,
                        """
                        {"pull_request": {
                          "base": {"sha": "%s"},
                          "head": {"sha": "%s", "repo": {"full_name": "example/sample"}},
                          "author_association": "%s", "user": {"login": "jane"}
                        }}
                        """
                                .formatted(base, head, outside ? "CONTRIBUTOR" : "MEMBER"));
            }
            environment.putAll(
                    Map.of(
                            "GITHUB_EVENT_PATH", event.toString(),
                            "GITHUB_EVENT_NAME", "pull_request",
                            "GITHUB_SHA", head,
                            "GITHUB_REPOSITORY", "example/sample",
                            "GITHUB_SERVER_URL", "https://github.com"));
        }
        final CommandAnswer expected = command(root, "jvm", args, environment);
        assertThat(expected.code()).as("the fixture's JVM result: %s", expected).isEqualTo(code);
        final CommandAnswer actual = command(root, "native", args, environment);

        assertThat(actual).as("the native exit code and both streams").isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theNativeExecutableRejectsARemovedCommandBeforeStartingTheServer(
            final boolean withLimits, @TempDir final Path dir) throws Exception {
        // Harm: an old script starts a native server and hangs instead of receiving exit 2.
        final List<String> args =
                withLimits ? List.of("--limits", "services=2.0", "stories") : List.of("stories");
        final CommandAnswer expected = command(dir, "jvm", args, Map.of());
        assertThat(expected.code()).as("the removed command exits before Spring").isEqualTo(2);
        assertThat(expected.out()).as("no command or server output").isEmpty();
        assertThat(expected.err())
                .as("the removed command is named in the refusal")
                .contains("stories", "no longer part of the planner");

        assertThat(command(dir, "native", args, Map.of()))
                .as("the native rejection matches the JVM")
                .isEqualTo(expected);
    }

    /** What a command answered: its exit code and its two streams. */
    private record CommandAnswer(int code, String out, String err) {}

    /** Runs the same command in each runtime with only the workflow variables the fixture gives. */
    private static CommandAnswer command(
            final Path root,
            final String runtime,
            final List<String> args,
            final Map<String, String> environment)
            throws Exception {
        final List<String> launch = new ArrayList<>();
        if (runtime.equals("native")) {
            final Path executable =
                    Path.of(System.getProperty("planner.executable", "missing")).toAbsolutePath();
            assertThat(executable).as("the native executable the build wrote").isExecutable();
            launch.add(executable.toString());
        } else {
            launch.add(ProcessHandle.current().info().command().orElseThrow());
            launch.add("-XX:-UsePerfData");
            launch.add("-cp");
            launch.add(System.getProperty("java.class.path"));
            launch.add(PlannerApplication.class.getName());
        }
        launch.addAll(args);
        final Path out = root.resolve(runtime + ".out");
        final Path err = root.resolve(runtime + ".err");
        final ProcessBuilder builder =
                new ProcessBuilder(launch)
                        .directory(root.toFile())
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile());
        builder.environment().keySet().removeIf(variable -> variable.startsWith("GITHUB_"));
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().putAll(RuntimeDirectories.environment(root));
        builder.environment().putAll(environment);
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("%s %s ends by itself", runtime, args)
                    .isTrue();
            return new CommandAnswer(
                    process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            process.destroyForcibly();
        }
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
            // The test's address file, log and work record, never the person's.
            builder.environment().putAll(RuntimeDirectories.environment(runtime));
            final Process planner = builder.start();
            final RuntimeDirectories.Address address;
            try {
                address = addressWithin(runtime, planner, output, PLANNER_CALLS);
                // An executable without its build resource fails to start or names another build.
                assertThat(health(address.port(), address.token()))
                        .as(
                                "the health answer with the token; the planner wrote:%n%s",
                                Files.readString(output))
                        .isEqualTo("200 " + expectedHealth());
                if (callWithoutToken) {
                    assertThat(health(address.port(), null))
                            .as("the health answer without the token")
                            .isEqualTo("401 ");
                    // A proof the image cannot answer leaves the window refusing its own planner.
                    assertThat(Signing.askProof(address.port(), address.token()))
                            .as("the native executable's proof")
                            .isEqualTo("200 proven");
                }
            } finally {
                stop(planner);
            }
            // A stop on Windows is TerminateProcess, which runs no shutdown hook; the next start
            // removes the file under its lock.
            if (!RuntimeDirectories.WINDOWS) {
                assertThat(runtime.resolve(AddressFile.DIRECTORY).resolve(AddressFile.FILE))
                        .as("the address file after the planner stopped")
                        .doesNotExist();
            }
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

    /**
     * The address the planner wrote once it listens, or a failure saying why there is none.
     *
     * @param calls the PowerShell calls the start makes on Windows
     */
    private static RuntimeDirectories.Address addressWithin(
            final Path runtime, final Process planner, final Path output, final int calls)
            throws Exception {
        final Path file = runtime.resolve(AddressFile.DIRECTORY).resolve(AddressFile.FILE);
        // On Windows each call may take its whole limit twice, a silent one started once more,
        // before the planner refuses to start itself, so the planner's own verdict, not this
        // bound, ends a slow start.
        final Duration bound =
                RuntimeDirectories.WINDOWS
                        ? POWERSHELL_LIMIT.multipliedBy(2L * calls).plus(Duration.ofSeconds(30))
                        : START;
        final long end = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < end && planner.isAlive()) {
            if (Files.exists(file)) {
                if (RuntimeDirectories.WINDOWS) {
                    // A passing start shows its PowerShell and slow start step lines too, so any
                    // Build measures them.
                    // Read leniently: a console line in another code page must not fail a passing
                    // start.
                    timingLines(new String(Files.readAllBytes(output), StandardCharsets.UTF_8))
                            .forEach(
                                    line -> System.out.println(output.getFileName() + ": " + line));
                }
                return RuntimeDirectories.read(runtime);
            }
            Thread.sleep(100);
        }
        final String wrote = Files.readString(output);
        throw new AssertionError(
                (planner.isAlive()
                                ? "no address file within " + bound
                                : "the planner ended with exit code " + planner.exitValue())
                        + "; its last PowerShell line: "
                        + lastPowerShellLine(wrote)
                        + "; it wrote:\n"
                        + wrote);
    }

    /** The last line the planner logged of a PowerShell call, or that it logged none. */
    private static String lastPowerShellLine(final String wrote) {
        return powerShellLines(wrote).reduce((first, second) -> second).orElse("none");
    }

    /** The lines the planner logged of its PowerShell calls, in order. */
    private static Stream<String> powerShellLines(final String wrote) {
        return wrote.lines().filter(line -> line.contains("PowerShell "));
    }

    /** The lines the planner logged of its PowerShell calls and slow start steps, in order. */
    private static Stream<String> timingLines(final String wrote) {
        return wrote.lines()
                .filter(line -> line.contains("PowerShell ") || line.contains("Start step "));
    }

    /** The health answer of this build, from the version and commit the build computed. */
    private static String expectedHealth() {
        final String version = System.getProperty("planner.version", "missing");
        final String commit = System.getProperty("planner.commit", "");
        return "{\"status\":\"UP\",\"version\":\""
                + version
                + "\",\"commit\":"
                + (commit.isEmpty() ? "null" : "\"" + commit + "\"")
                + "}";
    }

    /** The status and body of a health call, signed under the token when there is one. */
    private static String health(final int port, final @Nullable String token)
            throws IOException, InterruptedException {
        if (token != null) {
            final Signing.Signed signed = Signing.sign(port, token, "GET", "/health", null, null);
            signed.builder().timeout(Duration.ofSeconds(1));
            final HttpResponse<String> answer = signed.send();
            return answer.statusCode() + " " + answer.body();
        }
        final HttpResponse<String> answer =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + "/health"))
                                        .timeout(Duration.ofSeconds(1))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        return answer.statusCode() + " " + answer.body();
    }
}
