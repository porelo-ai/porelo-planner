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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The answers the planner's mock mode gives to what the planning screen asks at a board's start,
 * held against the copies the screen's own test reads them from.
 */
class MockAnswersTest {

    /** The screen's copies of the mock's answers, which its HTTP source is tested against. */
    static final Path SERVED = Path.of("../../screen/test/served/mock");

    /** Set to write the copies from the running mock instead of checking them. */
    static final String RECORD = "planner.mock.record";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How long a call may wait for the mock's answer before the test fails instead of hanging. */
    private static final java.time.Duration WAIT = java.time.Duration.ofSeconds(20);

    private static final String SOURCE =
            "{\"repository\": \"https://example.org/porelo/porelo.git\", \"branch\": \"main\"}";

    private static Path runtime;

    private static ConfigurableApplicationContext mock;

    @BeforeAll
    static void startInMockMode() throws IOException {
        runtime = RuntimeDirectories.create();
        mock =
                PlannerApplication.run(
                        "--planner.runtime-directory=" + runtime,
                        "--planner.log.base=" + runtime,
                        "--mock");
    }

    @AfterAll
    static void stop() throws IOException {
        mock.close();
        RuntimeDirectories.delete(runtime);
    }

    // Prevents the screen from being tested against answers the mock no longer gives, so the
    // window's planning tests fail on a field the screen reads under another name.
    @Test
    void theScreensCopiesAreWhatTheMockAnswers() throws Exception {
        final Map<String, String> answers = answers();

        if (Boolean.getBoolean(RECORD)) {
            record(System.getenv(), SERVED, answers);
            return;
        }
        for (final Map.Entry<String, String> answer : answers.entrySet()) {
            final Path copy = SERVED.resolve(answer.getKey());
            assertThat(Files.isRegularFile(copy))
                    .as("the screen's copy %s; record it with -D%s=true", copy, RECORD)
                    .isTrue();
            assertThat(Files.readString(copy, StandardCharsets.UTF_8))
                    .as(
                            "the screen's copy %s against the mock's answer; record it again with"
                                    + " -D%s=true",
                            copy, RECORD)
                    .isEqualTo(answer.getValue());
        }
        try (Stream<Path> files = Files.list(SERVED)) {
            assertThat(files.map(file -> file.getFileName().toString()).sorted().toList())
                    .as("the screen's copies, none the mock no longer answers")
                    .containsExactlyElementsOf(answers.keySet().stream().sorted().toList());
        }
    }

    // Prevents a build from rewriting the copies it checks and so passing whatever the mock says.
    @Test
    void recordingIsRefusedWhereTheEnvironmentNamesCi(@TempDir final Path dir) throws IOException {
        final Map<String, String> answers = Map.of("stories.json", "{}\n");

        final IllegalStateException refused =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> record(Map.of("CI", "true"), dir, answers));

        assertThat(refused.getMessage()).as("why").contains("CI");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.toList()).as("nothing written").isEmpty();
        }
    }

    /** Writes the copies, never where a build runs. */
    static void record(
            final Map<String, String> environment,
            final Path target,
            final Map<String, String> answers)
            throws IOException {
        if (environment.containsKey("CI")) {
            throw new IllegalStateException(
                    "the mock's answers are recorded only by hand, never where CI is set");
        }
        Files.createDirectories(target);
        for (final Map.Entry<String, String> answer : answers.entrySet()) {
            Files.writeString(
                    target.resolve(answer.getKey()), answer.getValue(), StandardCharsets.UTF_8);
        }
    }

    /** What the screen asks at a board's start, and of one issue and one move, by file name. */
    private static Map<String, String> answers() throws Exception {
        final Map<String, String> answers = new LinkedHashMap<>();
        assertThat(call("PUT", "/projects/demo", SOURCE).statusCode())
                .as("the project given, as the screen gives it")
                .isEqualTo(204);
        answers.put("workflow.json", body(call("GET", "/projects/demo/workflow", null)));
        answers.put("stories.json", body(call("GET", "/projects/demo/stories", null)));
        answers.put("board-event.json", firstBoardEvent());
        answers.put("issue-B541.json", body(call("GET", "/projects/demo/issues/B541", null)));
        final String preview = body(call("POST", "/projects/demo/moves/preview", MOVE_B542));
        answers.put("preview-B542.json", preview);
        answers.put(
                "move-B542.json",
                body(call("POST", "/projects/demo/moves", saveAsTheScreenSends(preview))));
        return answers;
    }

    /** The data of the first board event on the project's stream, as the screen reads it. */
    private static String firstBoardEvent() throws Exception {
        final RuntimeDirectories.Address address = RuntimeDirectories.read(runtime);
        final HttpResponse<Stream<String>> stream =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + address.port()
                                                                + "/projects/demo/events"))
                                        .timeout(WAIT)
                                        .header("Authorization", "Bearer " + address.token())
                                        .build(),
                                HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = stream.body()) {
            final CompletableFuture<String> data =
                    CompletableFuture.supplyAsync(
                            () ->
                                    lines.dropWhile(line -> !line.equals("event:board"))
                                            .filter(line -> line.startsWith("data:"))
                                            .findFirst()
                                            .orElseThrow()
                                            .substring("data:".length()));
            return pretty(data.get(20, TimeUnit.SECONDS));
        }
    }

    private static String body(final HttpResponse<String> answer) {
        assertThat(answer.statusCode()).as("the status of %s", answer.uri()).isEqualTo(200);
        return pretty(answer.body());
    }

    /** One JSON text per file, laid out so a change reads as a diff of the lines it touched. */
    private static String pretty(final String json) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(json)) + "\n";
    }

    /** The save the screen sends after the preview: the batch, the digest and no confirmation. */
    private static String saveAsTheScreenSends(final String preview) {
        final ObjectNode save = (ObjectNode) JSON.readTree(Previewed.moves(MOVE_B542, preview));
        save.put("confirm", false);
        return JSON.writeValueAsString(save);
    }

    private static final String MOVE_B542 =
            "{\"revision\": \"mock@1\", \"moves\": [{\"issue\": \"B542\", \"to\": \"Review\"}]}";

    private static HttpResponse<String> call(
            final String method, final String path, final String body)
            throws IOException, InterruptedException {
        final RuntimeDirectories.Address address = RuntimeDirectories.read(runtime);
        final HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + address.port() + path))
                        .timeout(WAIT)
                        .header("Authorization", "Bearer " + address.token())
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
