package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void reset() throws Exception {
        // The mock board saves moves, so each test starts from the first board as the window's do.
        assertThat(call(runtime, "POST", "/mock/reset", null).statusCode())
                .as("the reset before a test")
                .isEqualTo(204);
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
        call(runtime, "PUT", "/projects/moved", SOURCE);
        call(runtime, "PUT", "/projects/worked", SOURCE);
        final HttpResponse<String> move =
                move(
                        runtime,
                        "/projects/moved",
                        "{\"revision\": \"mock@1\", \"moves\": [{\"issue\": \"PJ74\", \"to\":"
                                + " \"Estimated\"}]}");
        final HttpResponse<String> worker =
                call(
                        runtime,
                        "PUT",
                        "/projects/worked/issues/PJ74/worker",
                        "{\"worker\": \"MANUAL\", \"revision\": \"mock@1\"}");
        assertThat(worker.statusCode())
                .as("the status of a worker change, against a move's: %s", worker.body())
                .isEqualTo(move.statusCode());
        assertThat(JSON.readTree(worker.body()).path("revision").asString())
                .as("the revision after a worker change, against a move's")
                .isEqualTo(JSON.readTree(move.body()).path("revision").asString());
        final JsonNode issue =
                json(call(runtime, "GET", "/projects/worked/issues/PJ74", null)).get("issue");
        assertThat(issue.get("worker").asString())
                .as("the worker read after the change")
                .isEqualTo("MANUAL");
    }

    @Test
    void theMockAnswersAPreviewFromItsBoardAndMovesNothing() throws Exception {
        // Harm: the window's tests of the warning would pass against a mock that saved on preview.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final HttpResponse<String> preview =
                call(
                        runtime,
                        "POST",
                        "/projects/any/moves/preview",
                        "{\"revision\": \"mock@1\", \"moves\": [{\"issue\": \"B542\", \"to\":"
                                + " \"Review\"}]}");
        assertThat(preview.statusCode()).as("the status: %s", preview.body()).isEqualTo(200);
        final JsonNode answer = JSON.readTree(preview.body());
        assertThat(answer.at("/issues/0/id").asString())
                .as("the issue the preview moves")
                .isEqualTo("B542");
        assertThat(answer.at("/issues/0/to").asString()).isEqualTo("Review");
        assertThat(answer.path("digest").asString()).matches("[0-9a-f]{64}");
        assertThat(revision("any")).as("the board after the preview").isEqualTo("mock@1");
    }

    @Test
    void aMoveShowsInTheNextReadAndOnTheStream() throws Exception {
        // The window's tests of a save would pass against a mock that never saved.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final RuntimeDirectories.Address address = RuntimeDirectories.read(runtime);
        final HttpResponse<java.util.stream.Stream<String>> stream =
                Signing.stream(address, "/projects/any/events");
        try (java.util.stream.Stream<String> lines = stream.body()) {
            final CompletableFuture<Boolean> told =
                    CompletableFuture.supplyAsync(
                            () -> lines.anyMatch(line -> line.equals("id:mock@2")));
            final HttpResponse<String> move =
                    move(
                            runtime,
                            "/projects/any",
                            "{\"revision\": \"mock@1\", \"moves\": [{\"issue\": \"B542\", \"to\":"
                                    + " \"Review\"}]}");
            assertThat(json(move).get("revision").asString())
                    .as("the revision the move answers")
                    .isEqualTo("mock@2");
            final JsonNode issue =
                    json(call(runtime, "GET", "/projects/any/issues/B542", null)).get("issue");
            assertThat(issue.get("state").asString())
                    .as("the moved issue in the next read")
                    .isEqualTo("Review");
            assertThat(told.get(20, TimeUnit.SECONDS))
                    .as("the new revision told on the open stream")
                    .isTrue();
        }
    }

    @Test
    void theFixedStoryAnswersPartlySavedNamingWhatWasPushedAndWhatFailed() throws Exception {
        // The window's handling of a half-made save could not be tested against the mock.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final HttpResponse<String> move =
                move(
                        runtime,
                        "/projects/any",
                        "{\"revision\": \"mock@1\", \"moves\": [{\"story\": \"ST292\", \"to\":"
                                + " \"Review\"}]}");
        assertThat(move.statusCode()).as("the status: %s", move.body()).isEqualTo(502);
        final JsonNode problem = JSON.readTree(move.body());
        assertThat(problem.path("type").asString())
                .as("the refusal")
                .isEqualTo("urn:porelo-planner:PartlySaved");
        assertThat(problem.path("parameters").path("pushed").asString())
                .as("the repositories pushed")
                .isEqualTo("porelo");
        assertThat(problem.path("parameters").path("failed").asString())
                .as("the repository that failed")
                .isEqualTo("porelo-host");
        assertThat(
                        json(call(runtime, "GET", "/projects/any/stories", null))
                                .get("revision")
                                .asString())
                .as("the revision holding what was pushed")
                .isEqualTo("mock@2");
    }

    @Test
    void aParentClosedOverItsOpenSubIssueIsRefused() throws Exception {
        // Without a sub-issue in the mock, the window could not test this refusal at all.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final HttpResponse<String> move =
                move(
                        runtime,
                        "/projects/any",
                        "{\"revision\": \"mock@1\", \"moves\": [{\"issue\": \"B540\", \"to\":"
                                + " \"Finished\"}]}");
        assertThat(JSON.readTree(move.body()).path("type").asString())
                .as("the refusal of a parent closed over its open sub-issue: %s", move.body())
                .isEqualTo("urn:porelo-planner:MoveNotAllowed");
        assertThat(
                        json(call(runtime, "GET", "/projects/any/issues/B540", null))
                                .get("issue")
                                .get("state")
                                .asString())
                .as("the parent after the refusal")
                .isEqualTo("Implementing");
    }

    @Test
    void aResetBringsBackTheFirstRevisionAndOneProjectsMoveLeavesAnother() throws Exception {
        // A test's moves seen by the next test or another project make the window's tests flaky.
        call(runtime, "PUT", "/projects/moved", SOURCE);
        call(runtime, "PUT", "/projects/other", SOURCE);
        move(
                runtime,
                "/projects/moved",
                "{\"revision\": \"mock@1\", \"moves\": [{\"issue\": \"B542\", \"to\":"
                        + " \"Review\"}]}");
        assertThat(revision("moved")).as("the moved project's revision").isEqualTo("mock@2");
        assertThat(revision("other")).as("the other project's revision").isEqualTo("mock@1");

        call(runtime, "POST", "/mock/reset", null);
        call(runtime, "PUT", "/projects/moved", SOURCE);

        assertThat(revision("moved")).as("the revision after the reset").isEqualTo("mock@1");
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
    void theMockSavesAHandedInIdeaOnItsBoardOnceUntilAReset() throws Exception {
        // The window's tests of a discussion's end need the story on the board, made once.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final String body =
                "{\"offer\": \"4f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f\", \"idea\": {\"story\":"
                        + " {\"title\": \"Tag stories\", \"gain\": \"G\", \"test\": \"T\","
                        + " \"priority\": \"High\"}, \"issues\": [{\"repository\": \"porelo\","
                        + " \"title\": \"Tags\", \"mustBeTrue\": \"W\", \"worker\": \"Agent\"}]}}";
        try {
            final HttpResponse<String> first = call(runtime, "POST", "/projects/any/ideas", body);
            assertThat(first.statusCode())
                    .as("the status of a hand-in: %s", first.body())
                    .isEqualTo(201);
            final String story = JSON.readTree(first.body()).path("id").asString();
            assertThat(
                            JSON.readTree(call(runtime, "POST", "/projects/any/ideas", body).body())
                                    .path("id")
                                    .asString())
                    .as("a second hand-in answers the same story")
                    .isEqualTo(story);
            final JsonNode saved =
                    json(call(runtime, "GET", "/projects/any/stories/" + story, null)).get("story");
            assertThat(saved.path("state").asString()).as("the story's state").isEqualTo("Idea");
            assertThat(saved.path("issues"))
                    .as("its one issue, in Idea in the repository named")
                    .singleElement()
                    .satisfies(
                            issue -> {
                                assertThat(issue.path("repository").asString())
                                        .as("its repository")
                                        .isEqualTo("porelo");
                                assertThat(issue.path("state").asString())
                                        .as("its state")
                                        .isEqualTo("Idea");
                            });
            final List<String> stories = new ArrayList<>();
            json(call(runtime, "GET", "/projects/any/stories", null))
                    .get("stories")
                    .forEach(each -> stories.add(each.get("id").asString()));
            assertThat(stories).as("the board holds one story of the idea").containsOnlyOnce(story);
        } finally {
            call(runtime, "POST", "/mock/reset", null);
        }
        call(runtime, "PUT", "/projects/any", SOURCE);
        assertThat(
                        json(call(runtime, "GET", "/projects/any/stories", null))
                                .get("revision")
                                .asString())
                .as("a reset restores the mock board")
                .isEqualTo("mock@1");
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
            final HttpResponse<java.util.stream.Stream<String>> stream =
                    Signing.stream(address, "/projects/any/events");
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
                new ArrayList<>(
                        List.of(
                                "--planner.runtime-directory=" + runtime,
                                // A start without --mock keeps a log, never in the person's folder.
                                "--planner.log.base=" + runtime));
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

    private static String revision(final String project) throws Exception {
        return json(call(runtime, "GET", "/projects/" + project + "/stories", null))
                .get("revision")
                .asString();
    }

    private static JsonNode json(final HttpResponse<String> answer) {
        assertThat(answer.statusCode()).as("the status of %s", answer.uri()).isEqualTo(200);
        return JSON.readTree(answer.body());
    }

    /** Moves as the window does: the batch's preview first, then the save with its digest. */
    private static HttpResponse<String> move(
            final Path runtime, final String project, final String batch)
            throws IOException, InterruptedException {
        final HttpResponse<String> preview =
                call(runtime, "POST", project + "/moves/preview", batch);
        return call(runtime, "POST", project + "/moves", Previewed.moves(batch, preview.body()));
    }

    private static HttpResponse<String> call(
            final Path runtime, final String method, final String path, final String body)
            throws IOException, InterruptedException {
        final RuntimeDirectories.Address address = RuntimeDirectories.read(runtime);
        final Signing.Signed request =
                Signing.sign(address, method, path, "application/json", body);
        request.builder().header("Accept", "application/json");
        return request.send();
    }
}
