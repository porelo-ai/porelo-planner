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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The planner started with {@code --mock}, called over HTTP as the window's tests call it. */
class MockModeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The window's own mock board, which its planning tests assert against. */
    private static final Path WINDOW_MOCK = Path.of("../../screen/lib/src/fake/planning_data.dart");

    private static final String SOURCE =
            "{\"repository\": \"https://example.org/porelo/porelo.git\"}";

    private static Path runtime;

    private static ConfigurableApplicationContext mock;

    @BeforeAll
    static void startInMockMode() throws IOException {
        runtime = RuntimeDirectories.create();
        mock = start(runtime, "--mock");
    }

    @AfterAll
    static void stop() throws IOException {
        mock.close();
        RuntimeDirectories.delete(runtime);
    }

    @Test
    void theMockModeServesTheWindowsMockBoardForAnyProjectWithoutGit() throws Exception {
        // The window's planning tests assert the mock's stories; a board that drifts from them,
        // or a project sent to Git, fails those tests or reaches the network.
        final JsonNode window = windowMock();
        assertThat(call(runtime, "PUT", "/projects/any", SOURCE).statusCode())
                .as("giving a project whose repository is never reached, in the mock mode")
                .isEqualTo(204);

        final JsonNode stories = json(call(runtime, "GET", "/projects/any/stories", null));
        assertThat(ids(stories.get("stories")))
                .as("the stories served, against the window's mock board in its order")
                .isNotEmpty()
                .isEqualTo(ids(window.get("stories")));
        assertThat(stories.get("revision").asString())
                .as("the mock board's fixed revision")
                .isEqualTo("mock@1");

        final JsonNode workflow = json(call(runtime, "GET", "/projects/any/workflow", null));
        assertThat(states(workflow))
                .as("the states and their kinds, against the window's mock board")
                .isNotEmpty()
                .isEqualTo(states(window));

        final JsonNode issue =
                json(call(runtime, "GET", "/projects/any/issues/B541", null)).get("issue");
        assertThat(issue.get("repository").asString())
                .as("the repository of an issue of the mock board")
                .isEqualTo("porelo");
        assertThat(issue.get("blockedBy").get(0).asString())
                .as("what an issue of the mock board waits on")
                .isEqualTo("B540");
        assertThat(issue.get("whatMustBeTrue").asString())
                .as("an issue's paragraph of what must be true")
                .isEqualTo("The work meets the story description.");
    }

    @Test
    void everyIssueOfTheMockBoardNamesItsWorkerAndSomeArePeoples() throws Exception {
        // A mock issue without a worker, or none a person works, leaves the window's tests of a
        // person's issue nothing to show.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final List<String> workers = new ArrayList<>();
        json(call(runtime, "GET", "/projects/any/stories", null))
                .get("stories")
                .forEach(
                        story ->
                                story.get("issues")
                                        .forEach(
                                                issue ->
                                                        workers.add(
                                                                String.valueOf(
                                                                        issue.path("worker")
                                                                                .asString(null)))));
        assertThat(workers)
                .as("the workers of the mock board's issues")
                .isNotEmpty()
                .containsOnly("AGENT", "MANUAL")
                .contains("AGENT", "MANUAL");
    }

    @Test
    void theMockAnswersAWorkerChangeAsItAnswersAMove() throws Exception {
        // The window tests its handling of a save against the mock; a different answer hides it.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final HttpResponse<String> move =
                call(
                        runtime,
                        "POST",
                        "/projects/any/moves",
                        "{\"revision\": \"mock@1\", \"moves\": [{\"issue\": \"PJ74\", \"to\":"
                                + " \"Estimated\"}]}");
        final HttpResponse<String> worker =
                call(
                        runtime,
                        "PUT",
                        "/projects/any/issues/PJ74/worker",
                        "{\"worker\": \"MANUAL\", \"revision\": \"mock@1\"}");
        assertThat(worker.statusCode())
                .as("the status of a worker change, against a move's: %s", worker.body())
                .isEqualTo(move.statusCode());
        assertThat(JSON.readTree(worker.body()).path("type").asString())
                .as("the refusal of a worker change, against a move's")
                .isEqualTo(JSON.readTree(move.body()).path("type").asString());
    }

    @Test
    void theMockAnswersAPriorityChangeAsNotOffered() throws Exception {
        // The mock saves nothing; a priority answered as saved would let the window's tests pass a
        // save that never happened.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final HttpResponse<String> priority =
                call(
                        runtime,
                        "PUT",
                        "/projects/any/stories/ST294/priority",
                        "{\"priority\": \"Lowest\", \"revision\": \"mock@1\"}");
        assertThat(priority.statusCode())
                .as("the status of a priority change: %s", priority.body())
                .isEqualTo(501);
        assertThat(JSON.readTree(priority.body()).path("type").asString())
                .as("the refusal of a priority change")
                .isEqualTo("urn:porelo-planner:NotOffered");
    }

    @Test
    void aResetForgetsTheProjectsGivenSoEachTestStartsAlike() throws Exception {
        // Projects kept across the window's tests would leak a test's project into the next and
        // run into the planner's bound on projects after a few tests.
        assertThat(call(runtime, "PUT", "/projects/kept", SOURCE).statusCode())
                .as("a project given")
                .isEqualTo(204);
        assertThat(call(runtime, "POST", "/mock/reset", null).statusCode())
                .as("the status of a reset in the mock mode")
                .isEqualTo(204);
        final HttpResponse<String> after = call(runtime, "GET", "/projects/kept/stories", null);
        assertThat(after.statusCode()).as("a read of the project after the reset").isEqualTo(404);
        assertThat(after.body()).as("its refusal").contains("urn:porelo-planner:NoSuchProject");
        for (int i = 0; i < 12; i++) {
            assertThat(call(runtime, "PUT", "/projects/p" + i, SOURCE).statusCode())
                    .as("project %s given after a reset", i)
                    .isEqualTo(204);
            call(runtime, "POST", "/mock/reset", null);
        }
    }

    @Test
    void theMockModeRefusesTheSourcesThePlannerRefuses() throws Exception {
        // The window tests its refusals against the mock; a mock that takes anything hides them.
        assertThat(call(runtime, "PUT", "/projects/any", "{\"repository\": \"/home/person/p\"}"))
                .as("a local path")
                .extracting(HttpResponse::statusCode, HttpResponse::body)
                .satisfies(answer -> assertThat(answer.get(1).toString()).contains("NotACloneUrl"))
                .first()
                .isEqualTo(400);
        assertThat(
                        call(
                                        runtime,
                                        "PUT",
                                        "/projects/any",
                                        "{\"repository\": \"https://example.org/p/r.git\", "
                                                + "\"branch\": \"--upload-pack=x\"}")
                                .body())
                .as("a branch that is an option")
                .contains("urn:porelo-planner:InvalidName");
    }

    @Test
    void aMockProfileWithoutTheArgumentLeavesThePlannerReal() throws Exception {
        // Only --mock may serve the stand-in board; an environment naming a profile must not.
        final Path plain = RuntimeDirectories.create();
        try (ConfigurableApplicationContext planner =
                start(plain, "--spring.profiles.active=mock")) {
            assertThat(call(plain, "PUT", "/projects/any", "{\"repository\": \"/p\"}").statusCode())
                    .as("a local path given to a planner started with the profile alone")
                    .isEqualTo(400);
            assertThat(call(plain, "POST", "/mock/reset", null).statusCode())
                    .as("the reset of a planner started with the profile alone")
                    .isEqualTo(404);
        } finally {
            RuntimeDirectories.delete(plain);
        }
    }

    @Test
    void withoutTheMockModeTheResetIsNotFound() throws Exception {
        // A person's planner must offer nothing that throws its boards away.
        final Path plain = RuntimeDirectories.create();
        try (ConfigurableApplicationContext planner = start(plain)) {
            final HttpResponse<String> answer = call(plain, "POST", "/mock/reset", null);
            assertThat(answer.statusCode())
                    .as("the status of a reset without the mock mode")
                    .isEqualTo(404);
        } finally {
            RuntimeDirectories.delete(plain);
        }
    }

    @Test
    void aPlannerWithAWindowFollowingItStopsAtOnce() throws Exception {
        // A stream that holds the stop keeps the planner running after its window asked it to end.
        final Path own = RuntimeDirectories.create();
        try {
            final ConfigurableApplicationContext planner = start(own, "--mock");
            call(own, "PUT", "/projects/any", SOURCE);
            final RuntimeDirectories.Address address = RuntimeDirectories.read(own);
            final HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + address.port()
                                                    + "/projects/any/events"))
                            .header("Authorization", "Bearer " + address.token())
                            .build();
            final HttpResponse<java.util.stream.Stream<String>> stream =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofLines());
            assertThat(stream.statusCode()).as("the stream's status").isEqualTo(200);
            final long start = System.nanoTime();
            planner.close();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                    .as("the stop with a stream open")
                    .isLessThan(java.time.Duration.ofSeconds(5));
            stream.body().close();
        } finally {
            RuntimeDirectories.delete(own);
        }
    }

    private static ConfigurableApplicationContext start(final Path runtime, final String... more) {
        final List<String> args =
                new ArrayList<>(List.of("--planner.runtime-directory=" + runtime));
        args.addAll(List.of(more));
        return PlannerApplication.run(args.toArray(String[]::new));
    }

    private static JsonNode windowMock() throws IOException {
        final String dart = Files.readString(WINDOW_MOCK, StandardCharsets.UTF_8);
        final String open = "jsonDecode(r'''";
        final int start = dart.indexOf(open);
        final int end = dart.indexOf("\n''')", start);
        assertThat(start)
                .as("the start of the window's mock board in %s", WINDOW_MOCK)
                .isPositive();
        return JSON.readTree(dart.substring(start + open.length(), end));
    }

    private static List<String> ids(final JsonNode stories) {
        final List<String> ids = new ArrayList<>();
        stories.forEach(
                story -> {
                    final List<String> issues = new ArrayList<>();
                    story.get("issues").forEach(issue -> issues.add(issue.get("id").asString()));
                    ids.add(
                            story.get("id").asString()
                                    + " "
                                    + story.get("state").asString()
                                    + " "
                                    + issues);
                });
        return ids;
    }

    private static List<String> states(final JsonNode workflow) {
        final List<String> states = new ArrayList<>();
        workflow.get("states")
                .forEach(
                        state ->
                                states.add(
                                        state.get("name").asString()
                                                + " "
                                                + state.get("kind").asString()));
        return states;
    }

    private static JsonNode json(final HttpResponse<String> answer) {
        assertThat(answer.statusCode()).as("the status of %s", answer.uri()).isEqualTo(200);
        return JSON.readTree(answer.body());
    }

    private static HttpResponse<String> call(
            final Path runtime, final String method, final String path, final String body)
            throws IOException, InterruptedException {
        final RuntimeDirectories.Address address = RuntimeDirectories.read(runtime);
        final HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + address.port() + path))
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
