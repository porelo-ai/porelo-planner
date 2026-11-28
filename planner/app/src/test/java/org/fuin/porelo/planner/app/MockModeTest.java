package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLEncoder;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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

        // The board comes in pages, so every page up to the last is read before comparing.
        final JsonNode stories = allStories("any");
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
    void everyProjectTheWindowGivesIsKeptWithoutACount() throws Exception {
        // Harm: a person who plans in more projects than a fixed count is refused the next one.
        for (int project = 1; project <= 12; project++) {
            assertThat(call(runtime, "PUT", "/projects/many" + project, SOURCE).statusCode())
                    .as("giving project %d", project)
                    .isEqualTo(204);
        }
        for (int project = 1; project <= 12; project++) {
            assertThat(
                            call(runtime, "GET", "/projects/many" + project + "/stories", null)
                                    .statusCode())
                    .as("reading project %d", project)
                    .isEqualTo(200);
        }
    }

    @Test
    void theMockListsAnIssueInNoStoryThroughTheBoardApi() throws Exception {
        // Harm: the window's tests cannot expose work omitted from every story.
        call(runtime, "PUT", "/projects/any", SOURCE);

        final JsonNode answer = json(call(runtime, "GET", "/projects/any/issues?story=none", null));

        assertThat(answer.path("revision").asString())
                .as("the mock board's revision")
                .isEqualTo("mock@1");
        assertThat(answer.path("issues").valueStream().map(i -> i.path("id").asString()).toList())
                .as("the mock's issue that no story names")
                .containsExactly("B900");
        assertThat(answer.at("/issues/0/worker").asString())
                .as("the same issue fields the mock serves for story issues")
                .isEqualTo("AGENT");
    }

    @Test
    void everyIssueOfTheMockBoardNamesItsWorkerAndSomeArePeoples() throws Exception {
        // A mock issue without a worker, or none a person works, leaves the window's tests of a
        // person's issue nothing to show.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final List<String> workers = new ArrayList<>();
        allStories("any")
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
                        "{\"moves\": [{\"issue\": \"PJ74\", \"to\":" + " \"Estimated\"}]}");
        final HttpResponse<String> worker =
                call(
                        runtime,
                        "PUT",
                        "/projects/worked/issues/PJ74/worker",
                        "{\"worker\": \"MANUAL\", \"seen\": "
                                + seen(runtime, "/projects/worked")
                                + "}");
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
                        "{\"moves\": [{\"issue\": \"B542\", \"to\":" + " \"Review\"}]}");
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
                            "{\"moves\": [{\"issue\": \"B542\", \"to\":" + " \"Review\"}]}");
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
                        "{\"moves\": [{\"story\": \"ST292\", \"to\": \"Rejected\"}]}");
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
        assertThat(allStories("any").get("revision").asString())
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
                        "{\"moves\": [{\"issue\": \"B540\", \"to\":" + " \"Finished\"}]}");
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
                "{\"moves\": [{\"issue\": \"B542\", \"to\":" + " \"Review\"}]}");
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
                        "{\"priority\": \"Lowest\", \"seen\": "
                                + seen(runtime, "/projects/any")
                                + "}");
        assertThat(priority.statusCode())
                .as("the status of a priority change: %s", priority.body())
                .isEqualTo(501);
        assertThat(JSON.readTree(priority.body()).path("type").asString())
                .as("the refusal of a priority change")
                .isEqualTo("urn:porelo-planner:NotOffered");
    }

    @Test
    void theMockAnswersAnAddOfAnIssueMadeOutsideTheProjectAsNotOffered() throws Exception {
        // Harm: the mock answered the add as saved or as no longer offered, so the window's tests
        // passed an add that never happened or reloaded a board that never changes.
        call(runtime, "PUT", "/projects/any", SOURCE);
        final HttpResponse<String> added =
                call(
                        runtime,
                        "POST",
                        "/projects/any/items",
                        "{\"issue\": \"porelo#7\", \"seen\": "
                                + seen(runtime, "/projects/any")
                                + "}");
        assertThat(added.statusCode()).as("the status of an add: %s", added.body()).isEqualTo(501);
        assertThat(JSON.readTree(added.body()).path("type").asString())
                .as("the refusal of an add")
                .isEqualTo("urn:porelo-planner:NotOffered");
    }

    @Test
    void aReviewerAskedForIsServedWithItsPlaceUntilTheNextReset() throws Exception {
        // The window's planning tests start a reviewer from the order the planner serves; a mock
        // that serves none, another role or no place leaves that start untested, and one that
        // keeps it past a reset makes the next test start from another board.
        assertThat(call(runtime, "POST", "/mock/projects/none/reviewer", null).statusCode())
                .as("a reviewer for a project never given")
                .isEqualTo(404);
        call(runtime, "PUT", "/projects/reviewed", SOURCE);

        assertThat(call(runtime, "POST", "/mock/projects/reviewed/reviewer", null).statusCode())
                .as("a reviewer asked for")
                .isEqualTo(204);
        final JsonNode starts =
                json(call(runtime, "GET", "/projects/reviewed/stories/ST14/work", null))
                        .at("/work/starts");
        assertThat(starts.size()).as("one start waits").isEqualTo(1);
        assertThat(
                        List.of(
                                starts.at("/0/role/name").asString(),
                                starts.at("/0/role/endKind").asString(),
                                starts.at("/0/role/workspace").asString(),
                                starts.at("/0/place/gatePush/name").asString(),
                                starts.at("/0/issues/0").asString()))
                .as("a reviewer reading at the gate push the issue's work waits at")
                .containsExactly("reviewer", "GIVES_POINTS", "foreign", "mock-author", "PJ58");

        call(runtime, "POST", "/mock/reset", null);
        call(runtime, "PUT", "/projects/reviewed", SOURCE);
        assertThat(
                        json(call(runtime, "GET", "/projects/reviewed/stories/ST14/work", null))
                                .get("work")
                                .isNull())
                .as("no run after the reset")
                .isTrue();
    }

    @Test
    void aHeldSaveAskedForIsOnTheBoardWithItsAnswerAndTriesUntilItIsResent() throws Exception {
        // The window's tests of a held save read it from the planner's mock; a mock that serves
        // none, or one without the forge's answer, its request id or its tries, leaves the
        // window's held-save row untested, and one kept past a resend or a reset leaks into the
        // next test.
        assertThat(call(runtime, "POST", "/mock/projects/none/held", null).statusCode())
                .as("a held save for a project never given")
                .isEqualTo(404);
        call(runtime, "PUT", "/projects/held", SOURCE);
        assertThat(call(runtime, "POST", "/mock/projects/held/held", null).statusCode())
                .as("a held save asked for")
                .isEqualTo(204);

        final JsonNode saves = heldSaves("held");
        assertThat(saves.size()).as("one line of held saves").isEqualTo(1);
        final JsonNode save = saves.path(0);
        assertThat(save.path("id").asString()).as("its id").isEqualTo(MockHeldSaves.SAVE);
        final List<String> folded = new ArrayList<>();
        save.path("saves").forEach(each -> folded.add(each.asString()));
        assertThat(folded)
                .as("two saves folded into it")
                .containsExactly(MockHeldSaves.FOLDED, MockHeldSaves.SAVE);
        assertThat(save.path("resendable").asBoolean()).as("a window's save").isTrue();
        assertThat(
                        List.of(
                                save.at("/changes/0/target").asString(),
                                save.at("/changes/0/field").asString(),
                                save.at("/changes/0/saved").asString()))
                .as("the one field it would change")
                .containsExactly(MockHeldSaves.ISSUE, "priority", MockHeldSaves.PRIORITY);
        assertThat(save.at("/answer/requestId").asString())
                .as("the forge's answer with its request id")
                .isEqualTo(MockHeldSaves.REQUEST_ID);
        final List<String> results = new ArrayList<>();
        save.path("tries").forEach(each -> results.add(each.path("result").asString()));
        assertThat(results)
                .as("three tries, of both saves")
                .containsExactly("REFUSED", "REFUSED", "HELD");

        assertThat(
                        call(
                                        runtime,
                                        "POST",
                                        "/projects/held/saves/" + MockHeldSaves.SAVE + "/resend",
                                        null)
                                .statusCode())
                .as("the save resent")
                .isEqualTo(200);
        assertThat(heldSaves("held").size()).as("none held once resent").isZero();

        call(runtime, "POST", "/mock/projects/held/held", null);
        call(runtime, "POST", "/mock/reset", null);
        call(runtime, "PUT", "/projects/held", SOURCE);
        assertThat(heldSaves("held").size()).as("none held after a reset").isZero();
    }

    /** The held saves of the first board a project's stream tells. */
    private static JsonNode heldSaves(final String project) throws Exception {
        final RuntimeDirectories.Address address = RuntimeDirectories.read(runtime);
        final HttpResponse<java.util.stream.Stream<String>> stream =
                Signing.stream(address, "/projects/" + project + "/events");
        try (java.util.stream.Stream<String> lines = stream.body()) {
            final String board =
                    CompletableFuture.supplyAsync(
                                    () ->
                                            lines.filter(
                                                            line ->
                                                                    line.startsWith("data:")
                                                                            && line.contains(
                                                                                    "\"heldSaves\""))
                                                    .findFirst()
                                                    .orElseThrow())
                            .get(20, TimeUnit.SECONDS);
            return JSON.readTree(board.substring("data:".length())).path("heldSaves");
        }
    }

    @Test
    void aResetForgetsTheProjectsGivenSoEachTestStartsAlike() throws Exception {
        // Projects kept across the window's tests would leak a test's project into the next, and
        // the projects of every test before would pile up for as long as the mock runs.
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
            assertThat(call(plain, "POST", "/mock/projects/any/reviewer", null).statusCode())
                    .as("a mock reviewer asked of a planner started with the profile alone")
                    .isEqualTo(404);
        } finally {
            RuntimeDirectories.delete(plain);
        }
    }

    @Test
    void withoutTheMockModeTheResetAndTheMockReviewerAreNotFound() throws Exception {
        // A person's planner must offer nothing that throws its boards away or plants a run.
        final Path plain = RuntimeDirectories.create();
        try (ConfigurableApplicationContext planner = start(plain)) {
            final HttpResponse<String> answer = call(plain, "POST", "/mock/reset", null);
            assertThat(answer.statusCode())
                    .as("the status of a reset without the mock mode")
                    .isEqualTo(404);
            assertThat(call(plain, "POST", "/mock/projects/any/reviewer", null).statusCode())
                    .as("the status of a mock reviewer asked for without the mock mode")
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
        return allStories(project).get("revision").asString();
    }

    /**
     * Reads every page of a project's stories and answers them as one list beside the first page's
     * revision.
     */
    private static JsonNode allStories(final String project)
            throws IOException, InterruptedException {
        final JsonNode first =
                json(call(runtime, "GET", "/projects/" + project + "/stories", null));
        final ArrayNode all = JSON.createArrayNode();
        JsonNode page = first;
        while (true) {
            all.addAll((ArrayNode) page.get("stories"));
            if (!page.hasNonNull("next")) {
                break;
            }
            page =
                    json(
                            call(
                                    runtime,
                                    "GET",
                                    "/projects/"
                                            + project
                                            + "/stories?after="
                                            + URLEncoder.encode(
                                                    page.get("next").asString(),
                                                    StandardCharsets.UTF_8),
                                    null));
        }
        final ObjectNode board = JSON.createObjectNode();
        board.put("revision", first.get("revision").asString());
        board.set("stories", all);
        return board;
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

    /** The versions of the project's items as its board answers them, as the window keeps them. */
    private static String seen(final Path runtime, final String project)
            throws IOException, InterruptedException {
        return Seens.ofAnswer(allStories(project.substring("/projects/".length())).toString());
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
