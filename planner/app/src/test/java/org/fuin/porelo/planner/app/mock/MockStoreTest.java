package org.fuin.porelo.planner.app.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.fuin.porelo.planner.core.MovePreview;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

/** The mock board's saves, which the window's planning tests read back. */
class MockStoreTest {

    private static final Revision FIRST = new Revision("mock@1");

    private final MockStore store = MockStore.fromClassPath().copy();

    // Prevents the window's planning tests from starting the mock board's analysis as work that
    // pushes, with no issue text and no result to hand in.
    @Test
    void anAnalysisStartedOnTheMockBoardIsGivenItsIssueAndAskedForItsResult() {
        final Board board = store.board();
        final org.fuin.porelo.planner.core.WorkOrder order =
                org.fuin.porelo.planner.core.WorkOrder.accept(
                        board,
                        "ST14",
                        "In analysis",
                        new org.fuin.porelo.planner.core.WorkOrder.Decision(
                                "AGENT",
                                org.fuin.porelo.planner.store.Basis.of(board),
                                null,
                                null,
                                null,
                                null));
        final var main = order.tasks().getFirst();
        assertThat(main.result()).as("the analysis's result file").isEqualTo("analysis.json");
        assertThat(main.given())
                .as("the issue's text as the mock board holds it")
                .extracting(org.fuin.porelo.planner.core.WorkOrder.Given::name)
                .containsExactly("PJ58.md");
        assertThat(main.prompt())
                .as("the stage's instructions")
                .contains("analysis.json", "push nothing");
        assertThat(board.work().rules().get("Under estimation").result())
                .as("the estimation hands back its result too")
                .isEqualTo(WorkRule.Result.ESTIMATE);
    }

    @Test
    void theMockBoardDefinesTheDocumentationsRolesAndItsRulesNameThem() {
        // Harm: the window's planning tests never see a role the project adds, or a judgment
        // among its rights, so the screen is held to role words the planner no longer serves.
        final Board board = MockStore.fromClassPath().board();
        final Map<String, Role> roles = board.work().roles();

        assertThat(roles.keySet())
                .as("the roles the mock board defines")
                .containsExactly("author", "reviewer", "tester", "screener", "analyst", "reader");
        assertThat(roles.get("tester"))
                .as("the tester by its name and parts")
                .isEqualTo(
                        new Role(
                                "tester",
                                Role.End.REPORTS_FINDINGS,
                                Role.Workspace.FOREIGN,
                                false,
                                false,
                                Role.WriteAs.REVIEW,
                                List.of(Role.Right.OPEN_THREADS, Role.Right.REPORT)));
        assertThat(roles.get("screener").rights())
                .as("the screener's rights, its judgment among them")
                .containsExactly(Role.Right.REPLY, Role.Right.OPEN_THREADS, Role.Right.REJECT);
        assertThat(roles.get("screener").end()).isEqualTo(Role.End.GIVES_POINTS);
        assertThat(roles.values())
                .as("every role sound, as Start would take it")
                .allSatisfy(role -> assertThat(role.broken()).as(role.name()).isEmpty());
        assertThat(
                        board.work().rules().entrySet().stream()
                                .map(rule -> rule.getKey() + "=" + rule.getValue().role().name())
                                .toList())
                .as("the role each rule names")
                .containsExactly(
                        "In analysis=analyst",
                        "Under estimation=analyst",
                        "Implementing=author",
                        "Reading=reader",
                        "Reworking=author",
                        "Finishing=author");
    }

    @Test
    void aClosedStoryKeepsItsVersionThroughASaveThatLeavesItBe() {
        // Harm: saving an unrelated issue changes every closed story's version, so the window's
        // edit of one is refused as stale.
        final Board before = store.board();

        final Board after = store.save(FIRST, moves(Map.of("B542", "Review")));

        for (final String id : List.of("ST264", "ST268", "ST243")) {
            assertThat(after.story(id).orElseThrow().version())
                    .as("the version of %s", id)
                    .isEqualTo(before.story(id).orElseThrow().version());
        }
    }

    @Test
    void aSaveAnswersANewRevisionWithTheMoveAndLeavesTheBoardBeforeItAsItWas() {
        // A mock that never saved would let the window's tests of a save pass.
        final Board before = store.board();

        final Board after = store.save(FIRST, moves(Map.of("B542", "Review")));

        assertThat(after.revision().value()).as("the revision after the save").isEqualTo("mock@2");
        assertThat(state(after, "B542")).as("the moved issue in the answer").isEqualTo("Review");
        assertThat(store.board()).as("the board read after the save").isEqualTo(after);
        assertThat(state(before, "B542")).as("the board before the save").isEqualTo("Backlog");
        assertThat(before.revision()).as("the revision before the save").isEqualTo(FIRST);
    }

    @Test
    void aSaveAgainstAnOlderRevisionIsRefusedAndChangesNothing() {
        // A save over a board the window never saw would overwrite another window's move.
        final Board saved = store.save(FIRST, moves(Map.of("B542", "Review")));

        assertThatThrownBy(() -> store.save(FIRST, moves(Map.of("B543", "Review"))))
                .as("a second save against the first revision")
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale ->
                                assertThat(stale.current())
                                        .as("the revision it names")
                                        .isEqualTo("mock@2"));
        assertThat(store.board()).as("the board after the refusal").isEqualTo(saved);
    }

    @Test
    void anOpenStoryFollowsItsIssuesAndAClosedOneStaysClosedUntilReopened() {
        // A story left in its old column after its issues moved shows the window a wrong board;
        // one closed that its issues reopen undoes the person's close.
        final Board after =
                store.save(
                        FIRST,
                        new Writes(
                                Map.of("PJ58", "Estimated", "SI05", "Backlog"),
                                Map.of("ST15", "Finished")));

        assertThat(story(after, "ST14").state())
                .as("the story of the moved issue, open")
                .isEqualTo("Estimated");
        assertThat(story(after, "ST15").state())
                .as("a story closed, whatever its issue stands in")
                .isEqualTo("Finished");

        final Board reopened =
                store.save(after.revision(), new Writes(Map.of(), Map.of("ST15", "Backlog")));
        assertThat(story(reopened, "ST15").state())
                .as("the story reopened stands where its issue gives")
                .isEqualTo("Backlog");
    }

    @Test
    void aStorysStateIsDerivedAsTheStoriesIndexDerivesIt() {
        // A wrong derivation lets the moves check refuse or confirm the wrong story moves.
        assertThat(store.storyState(List.of("Review", "Backlog", "Finished")))
                .as("none being built: those in Review or later count as Backlog")
                .isEqualTo("Backlog");
        assertThat(store.storyState(List.of("Idea", "Reading", "Implementing", "Review")))
                .as("the furthest state of its issues being built")
                .isEqualTo("Reading");
        assertThat(store.storyState(List.of("Rejected", "Finished")))
                .as("the rejected left out, the rest closed: Review")
                .isEqualTo("Review");
        assertThat(store.storyState(List.of("Rejected", "Rejected")))
                .as("every issue rejected: none left")
                .isNull();
        assertThat(store.storyState(List.of())).as("no issue").isNull();
    }

    @Test
    void aBatchOverTheFixedStorysRepositoriesSavesTheFirstAndNamesTheNext() {
        // The window's handling of a partly saved batch could not be tested against the mock.
        assertThatThrownBy(
                        () -> store.save(FIRST, moves(Map.of("HO158", "Review", "B553", "Review"))))
                .as("a batch over the issues of " + MockStore.PARTLY_SAVED)
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> {
                            assertThat(partly.pushed())
                                    .as("the repositories pushed")
                                    .containsExactly("porelo");
                            assertThat(partly.subject())
                                    .as("the repository that failed")
                                    .isEqualTo("porelo-host");
                        });
        final Board after = store.board();
        assertThat(after.revision().value())
                .as("the revision holding what was pushed")
                .isEqualTo("mock@2");
        assertThat(state(after, "B553"))
                .as("the issue of the pushed repository")
                .isEqualTo("Review");
        assertThat(state(after, "HO158"))
                .as("the issue of the repository that failed")
                .isEqualTo("Backlog");
    }

    @Test
    void aBatchOverOneRepositoryOfTheFixedStoryIsSavedWhole() {
        // A partly saved answer for every move of the story would leave it no plain save to test.
        final Board after = store.save(FIRST, moves(Map.of("B553", "Review", "B554", "Review")));

        assertThat(List.of(state(after, "B553"), state(after, "B554")))
                .as("both moved issues of one repository")
                .containsExactly("Review", "Review");
    }

    @Test
    void aMoveInOneCopyLeavesAnotherCopysBoardAsItWas() {
        // One project's moves seen in another project would mix the window's tests.
        final MockStore other = store.copy();

        store.save(FIRST, moves(Map.of("B542", "Review")));

        assertThat(other.board().revision()).as("the other copy's revision").isEqualTo(FIRST);
        assertThat(state(other.board(), "B542"))
                .as("the issue in the other copy")
                .isEqualTo("Backlog");
    }

    @Test
    void aReasonIsKeptOncePerTextAndAResetDropsIt() {
        // Harm: the window's tests of a reason pass against a mock that drops it, or keeps it
        // twice for the same close.
        final Writes.Reason why = new Writes.Reason("Finished", "Built.");
        final Board after =
                store.save(
                        FIRST,
                        new Writes(
                                Map.of("B542", "Finished"),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of("B542", why),
                                Map.of()));
        store.save(
                after.revision(),
                new Writes(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of("ST193", why)));
        store.save(
                store.board().revision(),
                new Writes(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of("ST193", why)));
        final Writes.Reason anew = new Writes.Reason("Finished", "Built anew.");
        store.save(
                store.board().revision(),
                new Writes(
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of("ST193", anew)));

        assertThat(store.keepsReasons()).isTrue();
        assertThat(store.reasons("B542")).containsExactly(why);
        assertThat(store.reasons("ST193"))
                .as("the same text once, a new one after a reopen again")
                .containsExactly(why, anew);
        store.reset();
        assertThat(store.reasons("B542")).isEmpty();
    }

    @Test
    void aResetRestoresTheFirstBoard() {
        // A test's moves left for the next would make the window's tests depend on their order.
        final Board first = store.board();
        store.save(FIRST, moves(Map.of("B542", "Review")));

        store.reset();

        assertThat(store.board()).as("the board after the reset").isEqualTo(first);
        assertThat(store.save(FIRST, moves(Map.of("B543", "Review"))).revision().value())
                .as("the revision of the first save after the reset")
                .isEqualTo("mock@2");
    }

    @Test
    void concurrentSavesNeitherLoseNorDoubleAMove() throws Exception {
        // The mock board is shared by every request; a lost or doubled save hides a race.
        final List<String> ids =
                List.of("B542", "B543", "B544", "B545", "B546", "B547", "B548", "B549");
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<Integer>> tries = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(ids.size())) {
            for (final String id : ids) {
                tries.add(pool.submit(() -> saveUntilTaken(start, id)));
            }
            start.countDown();
            for (final Future<Integer> attempt : tries) {
                attempt.get();
            }
        }
        final Board after = store.board();
        assertThat(after.revision().value())
                .as("the revision after one save per move")
                .isEqualTo("mock@" + (ids.size() + 1));
        assertThat(ids.stream().map(id -> state(after, id)).toList())
                .as("every moved issue")
                .containsOnly("Review");
    }

    @Test
    void aPriorityIsNotSaved() {
        // The mock offers no priority change; one answered as saved would hide that.
        assertThatThrownBy(
                        () ->
                                store.save(
                                        FIRST,
                                        new Writes(
                                                Map.of(),
                                                Map.of(),
                                                Map.of(),
                                                Map.of("ST294", "Lowest"))))
                .as("a priority saved into the mock")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.board().revision()).as("the revision after it").isEqualTo(FIRST);
    }

    private int saveUntilTaken(final CountDownLatch start, final String id) throws Exception {
        start.await();
        int tries = 0;
        while (true) {
            tries++;
            try {
                store.save(store.board().revision(), moves(Map.of(id, "Review")));
                return tries;
            } catch (final StoreException.StaleRevision e) {
                Thread.onSpinWait();
            }
        }
    }

    @Test
    void aStageResultMovesTheIssueAndKeepsItsSizeAndTextAndOneFromAnotherStateIsRefused() {
        // Prevents the window's tests of a stage's result from passing against a mock that drops
        // it.
        final Board before = store.board();

        assertThatThrownBy(() -> store.saveResult(result("Idea")))
                .as("a result for an issue no longer in the stage's state")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(store.board()).as("the board after the refusal").isEqualTo(before);

        final Board after = store.saveResult(result("Backlog"));

        assertThat(after.issue("B542").orElseThrow())
                .as("the issue after the result")
                .satisfies(
                        issue -> {
                            assertThat(issue.state()).as("state").isEqualTo("Review");
                            assertThat(issue.size()).as("size").isEqualTo("M");
                            assertThat(issue.text())
                                    .as("text")
                                    .contains("**Size:** M - one module")
                                    .contains("- Read it.")
                                    .contains("- Measure it.");
                        });
        assertThat(after.revision().value()).as("the next revision").isEqualTo("mock@2");
    }

    // Harm: the window's tests of a readiness check pass against a mock that sizes the issue,
    // drops why it went back, keeps that reason twice when the save is sent again, or keeps a
    // reason for the ready one.
    @Test
    void aReadinessCheckMovesTheIssueAloneAndKeepsWhyItIsNotReadyOnce() {
        final String why = "Not ready: it lacks a plan. None.";
        final StageResult back =
                new StageResult(
                        WorkRule.Result.READINESS,
                        List.of(),
                        List.of(
                                new StageResult.Verdict("B542", "Backlog", "Estimated", why),
                                new StageResult.Verdict("B543", "Backlog", "Implementing", null)));
        final Issue before = store.board().issue("B542").orElseThrow();
        final Issue readyBefore = store.board().issue("B543").orElseThrow();

        final Board after = store.saveResult(back);
        store.save(after.revision(), moves(Map.of("B542", "Backlog", "B543", "Backlog")));
        store.saveResult(back);

        assertThat(store.board().issue("B542").orElseThrow())
                .as("moved back, its size and text as they were")
                .satisfies(
                        issue -> {
                            assertThat(issue.state()).as("state").isEqualTo("Estimated");
                            assertThat(issue.size()).as("size").isEqualTo(before.size());
                            assertThat(issue.text()).as("text").isEqualTo(before.text());
                        });
        assertThat(store.reasons("B542"))
                .as("the reason kept once")
                .containsExactly(new Writes.Reason("Estimated", why));
        assertThat(store.board().issue("B543").orElseThrow())
                .as("the ready one moved on, its size and text as they were")
                .satisfies(
                        issue -> {
                            assertThat(issue.state()).as("state").isEqualTo("Implementing");
                            assertThat(issue.size()).as("size").isEqualTo(readyBefore.size());
                            assertThat(issue.text()).as("text").isEqualTo(readyBefore.text());
                        });
        assertThat(store.reasons("B543")).as("no reason kept for the ready one").isEmpty();
    }

    // Prevents the window's tests from reading a mock issue holding two plans or sizes after an
    // estimate follows an analysis, which the Git store would have replaced.
    @Test
    void anEstimateAfterAnAnalysisReplacesTheSizeAndPlanAndDropsWhatIsNoLongerToBeChecked() {
        store.saveResult(result("Backlog"));
        final Board after =
                store.saveResult(
                        new StageResult(
                                WorkRule.Result.ESTIMATE,
                                List.of(
                                        new StageResult.ForIssue(
                                                "B542",
                                                "Review",
                                                "Review",
                                                "- Build it.",
                                                "L",
                                                "two modules",
                                                90,
                                                List.of(),
                                                List.of()))));
        final String text = after.issue("B542").orElseThrow().text();
        assertThat(text.split("\\*\\*Size:\\*\\*", -1)).as("one size line in %s", text).hasSize(2);
        assertThat(text.split("## Plan", -1)).as("one plan in %s", text).hasSize(2);
        assertThat(text)
                .as("the estimate's parts and not the analysis's")
                .contains("**Size:** L - two modules", "**Estimate:** 90 min", "- Build it.")
                .doesNotContain("- Read it.", "## To be checked");
    }

    private static StageResult result(final String from) {
        return new StageResult(
                WorkRule.Result.ANALYSIS,
                List.of(
                        new StageResult.ForIssue(
                                "B542",
                                from,
                                "Review",
                                "- Read it.",
                                "M",
                                "one module",
                                null,
                                List.of("Measure it."),
                                List.of())));
    }

    @Test
    void aStoryFinishedOverRejectedIssuesOnlyOrNoneOrItsDoneIssueIsSavedAsItsPreviewShowed() {
        // Harm: a window test of a Finished the preview passed seeing the mock's save fail, or
        // the board after it differ from what the preview showed.
        final List<String> states =
                List.of("Idea", "Backlog", "Building", "Review", "Finished", "Rejected");
        final Board board =
                new Board(
                        FIRST,
                        new org.fuin.porelo.planner.store.Workflow(
                                states, List.of(), List.of("Finished", "Rejected")),
                        List.of(
                                // Open, as a story over rejected issues only stands.
                                new Story(
                                        "ST4",
                                        "A story",
                                        "Backlog",
                                        "High",
                                        null,
                                        List.of("PL5", "PL6"),
                                        "",
                                        null),
                                new Story(
                                        "ST5", "A story", "Idea", "High", null, List.of(), "",
                                        null),
                                new Story(
                                        "ST6",
                                        "A story",
                                        "Review",
                                        "High",
                                        null,
                                        List.of("PL7"),
                                        "",
                                        null)),
                        List.of(
                                mockIssue("PL5", "Rejected"),
                                mockIssue("PL6", "Rejected"),
                                mockIssue("PL7", "Review")),
                        new org.fuin.porelo.planner.store.Work(
                                Map.of(
                                        "Building",
                                        new WorkRule(
                                                List.of("Backlog"),
                                                "Review",
                                                null,
                                                null,
                                                WorkRule.Per.STORY,
                                                List.of("UNATTENDED"),
                                                "Build {story}",
                                                Map.of("codex", "gpt"),
                                                MockStore.fromClassPath()
                                                        .board()
                                                        .work()
                                                        .roles()
                                                        .get("author"))),
                                List.of()));
        final MockStore mock = new MockStore(board);
        final List<Moves.Move> moves =
                List.of(
                        new Moves.Move(true, "ST4", "Finished"),
                        new Moves.Move(true, "ST5", "Finished"),
                        new Moves.Move(true, "ST6", "Finished"));

        final MovePreview preview =
                Moves.preview(board, moves, (issues, stories) -> List.of(), NO_RUNS);
        final Moves.Planned planned = Moves.check(board, moves, NO_RUNS);
        final Board after = mock.save(FIRST, new Writes(planned.issues(), planned.stories()));

        assertThat(preview.warnings())
                .as("the done issue taken along")
                .extracting(MovePreview.Warning::kind)
                .containsExactly(MovePreview.ISSUES_ALONG);
        assertThat(preview.issues())
                .as("the preview shows the issue finished")
                .extracting(MovePreview.MovedIssue::id, MovePreview.MovedIssue::to)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PL7", "Finished"));
        assertThat(List.of(story(after, "ST4"), story(after, "ST5"), story(after, "ST6")))
                .as("each story Finished after the save")
                .extracting(Story::state)
                .containsExactly("Finished", "Finished", "Finished");
        assertThat(List.of(state(after, "PL5"), state(after, "PL6"), state(after, "PL7")))
                .as("the rejected issues kept, the done one finished")
                .containsExactly("Rejected", "Rejected", "Finished");
    }

    private static final Moves.Runs NO_RUNS = (issue, stories) -> false;

    private static Issue mockIssue(final String id, final String state) {
        return new Issue(id, "porelo-planner", "An issue", state, "S", List.of(), null, null, "");
    }

    private static Writes moves(final Map<String, String> issues) {
        return new Writes(issues, Map.of());
    }

    private static String state(final Board board, final String issue) {
        return board.issue(issue).map(Issue::state).orElseThrow();
    }

    private static Story story(final Board board, final String id) {
        return board.story(id).orElseThrow();
    }

    @Test
    void anIssuesPrioritySetByHandStaysThroughASizeWriteAndAMove() {
        // Harm: the window's mock shows a story's rank against a priority a later edit dropped.
        final Board prioritized =
                store.setFields(
                        FIRST,
                        "B542",
                        new org.fuin.porelo.planner.store.Fields(
                                java.util.Set.of(
                                        org.fuin.porelo.planner.store.Fields.Field.PRIORITY),
                                null,
                                null,
                                "Low",
                                null));
        assertThat(prioritized.issue("B542").orElseThrow().priority())
                .as("the priority set on the board")
                .isEqualTo("Low");

        final Board sized =
                store.setFields(
                        prioritized.revision(),
                        "B542",
                        new org.fuin.porelo.planner.store.Fields(
                                java.util.Set.of(org.fuin.porelo.planner.store.Fields.Field.SIZE),
                                "XL",
                                null,
                                null,
                                null));
        final Board moved = store.save(sized.revision(), moves(Map.of("B542", "Review")));

        assertThat(moved.issue("B542").orElseThrow())
                .as("moved and sized, its priority kept")
                .extracting(Issue::state, Issue::size, Issue::priority)
                .containsExactly("Review", "XL", "Low");
    }

    @Test
    void eachEditByHandIsAnsweredWithANewRevisionHoldingItAndAStaleOneChangesNothing() {
        // A mock that answered an edit without holding it would let the window's tests of a kept
        // board pass, and one that took an old revision would let a lost edit pass.
        final Board board = store.board();
        final Story story = board.story("ST72").orElseThrow();
        final Issue issue = board.issue("PJ74").orElseThrow();
        final Issue other = board.issue("B542").orElseThrow();

        final Board sized =
                store.setFields(
                        FIRST,
                        issue.id(),
                        new org.fuin.porelo.planner.store.Fields(
                                java.util.Set.of(
                                        org.fuin.porelo.planner.store.Fields.Field.SIZE,
                                        org.fuin.porelo.planner.store.Fields.Field.ESTIMATE,
                                        org.fuin.porelo.planner.store.Fields.Field.RANK),
                                "XL",
                                40,
                                null,
                                7));
        assertThat(sized.issue(issue.id()).orElseThrow().size()).as("the size set").isEqualTo("XL");
        // The window's planning tests read the minutes left from the estimate the mock holds.
        assertThat(sized.issue(issue.id()).orElseThrow().estimate())
                .as("the estimate set")
                .isEqualTo(40.0);
        assertThat(store.kept(issue.id()))
                .as("the rank kept beside the board")
                .containsEntry(org.fuin.porelo.planner.store.Fields.Field.RANK, 7);

        final Board listed =
                store.setStoryIssues(sized.revision(), story.id(), List.of(issue.id()));
        assertThat(listed.story(story.id()).orElseThrow().issues())
                .as("the story's new list")
                .containsExactly(issue.id());

        final Board blocked =
                store.setBlockedBy(listed.revision(), issue.id(), List.of(other.id()));
        assertThat(blocked.issue(issue.id()).orElseThrow().blockedBy())
                .as("the issue's new blockers")
                .containsExactly(other.id());
        assertThat(blocked.revision())
                .as("each edit a new revision")
                .isNotEqualTo(listed.revision());

        store.comment(issue.id(), "A comment.");
        assertThat(store.board()).as("a comment leaves the board").isEqualTo(blocked);

        assertThatThrownBy(() -> store.setBlockedBy(FIRST, issue.id(), List.of()))
                .as("an edit against the first revision")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(store.board()).as("the board after the stale edit").isEqualTo(blocked);
    }

    @Test
    void anEditEqualToTheBoardSavesNothingAndAnswersTheCurrentRevision() {
        // A save of nothing answered with a new revision tells the window's tests of an unchanged
        // edit that the board moved, against what the description says.
        final Board board = store.board();
        final Issue issue = board.issue("PJ74").orElseThrow();

        assertThat(
                        store.setFields(
                                        FIRST,
                                        issue.id(),
                                        new org.fuin.porelo.planner.store.Fields(
                                                java.util.Set.of(
                                                        org.fuin.porelo.planner.store.Fields.Field
                                                                .SIZE),
                                                issue.size(),
                                                null,
                                                null,
                                                null))
                                .revision())
                .as("the size the issue has")
                .isEqualTo(FIRST);
        assertThat(
                        store.setStoryIssues(
                                        FIRST, "ST72", board.story("ST72").orElseThrow().issues())
                                .revision())
                .as("the list the story holds")
                .isEqualTo(FIRST);
        assertThat(store.setBlockedBy(FIRST, issue.id(), issue.blockedBy()).revision())
                .as("the blockers the issue has")
                .isEqualTo(FIRST);
        assertThat(
                        store.setText(
                                        FIRST,
                                        issue.id(),
                                        new TextEdit(
                                                TextEdit.Kind.ISSUE,
                                                issue.title(),
                                                Map.of(),
                                                java.util.Set.of()))
                                .revision())
                .as("the title the issue has")
                .isEqualTo(FIRST);
        assertThat(store.board()).as("the board after edits of nothing").isEqualTo(board);
    }

    @Test
    void theMockBoardSetsTopicLabelsKeepsThePersonsOwnAndKeepsThemThroughAMove() {
        // Harm: the window's planning tests read no labels, or a labels edit on the mock takes the
        // person's own label off, saves nothing new, or a later save loses the labels.
        final Board board = store.board();
        final Issue issue = board.issue("PJ74").orElseThrow();
        assertThat(issue.labels())
                .as("the mock issue's labels, a label of the person's own among them")
                .containsExactly("documentation", "needs-design");
        assertThat(board.story("ST294").orElseThrow().labels())
                .as("the mock story's labels")
                .containsExactly("usability");

        final Board labelled =
                store.setText(
                        FIRST,
                        issue.id(),
                        new TextEdit(
                                TextEdit.Kind.ISSUE,
                                null,
                                Map.of(),
                                java.util.Set.of(),
                                List.of("tests")));
        final Issue after = labelled.issue("PJ74").orElseThrow();
        assertThat(after.labels())
                .as("the topic label set, the person's own kept")
                .containsExactly("needs-design", "tests");
        assertThat(after.version()).as("a new version").isNotEqualTo(issue.version());
        assertThat(labelled.revision()).as("a new revision").isNotEqualTo(FIRST);
        assertThat(
                        store.setText(
                                        labelled.revision(),
                                        issue.id(),
                                        new TextEdit(
                                                TextEdit.Kind.ISSUE,
                                                null,
                                                Map.of(),
                                                java.util.Set.of(),
                                                List.of("tests")))
                                .revision())
                .as("the labels it carries save nothing")
                .isEqualTo(labelled.revision());

        final Board story =
                store.setText(
                        labelled.revision(),
                        "ST294",
                        new TextEdit(
                                TextEdit.Kind.STORY,
                                null,
                                Map.of(),
                                java.util.Set.of(),
                                List.of()));
        assertThat(story.story("ST294").orElseThrow().labels())
                .as("every topic label taken off the story")
                .isEmpty();

        final Board moved =
                store.save(story.revision(), new Writes(Map.of("PJ74", "Implementing"), Map.of()));
        assertThat(moved.issue("PJ74").orElseThrow().labels())
                .as("a move keeps the labels")
                .containsExactly("needs-design", "tests");
    }

    @Test
    void theTopicLabelsAnItemCarriesAskedAgainInAnotherOrderSaveNothing() {
        // Harm: the mock answered a new revision where GitHub and a local project save nothing,
        // so the window's tests pass on a change the planner never makes.
        final Issue issue = store.board().issue("PJ74").orElseThrow();
        assertThat(issue.labels()).containsExactly("documentation", "needs-design");

        assertThat(
                        store.setText(
                                        FIRST,
                                        issue.id(),
                                        new TextEdit(
                                                TextEdit.Kind.ISSUE,
                                                null,
                                                Map.of(),
                                                java.util.Set.of(),
                                                List.of("documentation")))
                                .revision())
                .as("the topic label it carries, its own label first in the write")
                .isEqualTo(FIRST);
        assertThat(store.board().issue("PJ74").orElseThrow().labels())
                .containsExactly("documentation", "needs-design");
    }

    @Test
    void aStoryMadeFromAnIdeaHasItsGainReplacedByATextEdit() {
        // A gain added beside the old one, which stays as what the story covers, would show a
        // window's tests two gains where the real store holds one.
        final Board board = store.board();
        final NewIdea.Added added =
                store.createIdea(
                        board.revision(),
                        new NewIdea(
                                "A story",
                                "An old gain.",
                                "A test.",
                                "High",
                                List.of(
                                        new NewIssue(
                                                "porelo-project",
                                                "An issue",
                                                "It works.",
                                                null,
                                                Worker.AGENT,
                                                null))));

        final Board edited =
                store.setText(
                        added.board().revision(),
                        added.story(),
                        new TextEdit(
                                TextEdit.Kind.STORY,
                                null,
                                Map.of(TextEdit.Part.GAIN, "A new gain."),
                                java.util.Set.of()));

        assertThat(edited.story(added.story()).orElseThrow().description())
                .as("the gain replaced, the test as it was")
                .isEqualTo("**What a user gains.** A new gain.\n\n**The test.** A test.\n");
    }

    @Test
    void aPartGrownWhileAnotherIsRemovedIsTakenWhenTheBodyEndsWithinTheLimit() {
        // The mock refusing an edit the real store takes would fail the window's tests of it.
        final Issue issue = store.board().issue("PJ74").orElseThrow();
        final Board grown =
                store.setText(
                        FIRST,
                        issue.id(),
                        new TextEdit(
                                TextEdit.Kind.ISSUE,
                                null,
                                Map.of(TextEdit.Part.FINDINGS, "x".repeat(40_000)),
                                java.util.Set.of()));

        final Board edited =
                store.setText(
                        grown.revision(),
                        issue.id(),
                        new TextEdit(
                                TextEdit.Kind.ISSUE,
                                null,
                                Map.of(TextEdit.Part.PLAN, "y".repeat(40_000)),
                                java.util.Set.of(TextEdit.Part.FINDINGS)));

        assertThat(edited.issue(issue.id()).orElseThrow().text())
                .as("the plan grown, the findings gone")
                .contains("y".repeat(40_000))
                .doesNotContain("x".repeat(100));
    }

    @Test
    void aTextEditIsHeldWithItsTitleAndTheRestOfTheTextAndAStaleOrInjectedOneChangesNothing() {
        // A mock that answered a text edit without holding it, or took one the real store refuses,
        // would let the window's tests of a kept text pass where the real store fails.
        final Issue issue = store.board().issue("PJ74").orElseThrow();

        final Board edited =
                store.setText(
                        FIRST,
                        issue.id(),
                        new TextEdit(
                                TextEdit.Kind.ISSUE,
                                "A new title",
                                Map.of(TextEdit.Part.PLAN, "- A plan."),
                                java.util.Set.of()));
        final Issue after = edited.issue(issue.id()).orElseThrow();
        assertThat(after.title()).as("the title set").isEqualTo("A new title");
        assertThat(after.text())
                .as("the plan added, what must be true as it was")
                .isEqualTo(issue.text() + "\n## Plan\n\n- A plan.\n");
        assertThat(edited.revision()).as("a new revision").isNotEqualTo(FIRST);

        assertThatThrownBy(
                        () ->
                                store.setText(
                                        edited.revision(),
                                        issue.id(),
                                        new TextEdit(
                                                TextEdit.Kind.ISSUE,
                                                null,
                                                Map.of(TextEdit.Part.PLAN, "- a\n## Findings"),
                                                java.util.Set.of())))
                .as("a heading injected through the plan")
                .isInstanceOf(TextEdit.Refused.class);
        assertThatThrownBy(
                        () ->
                                store.setText(
                                        FIRST,
                                        issue.id(),
                                        new TextEdit(
                                                TextEdit.Kind.ISSUE,
                                                "Another",
                                                Map.of(),
                                                java.util.Set.of())))
                .as("a text edit against the first revision")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(store.board()).as("the board after both refusals").isEqualTo(edited);
    }

    @Test
    void theMockBoardCarriesOneItemOfEachWarningTheStoreApiNames() {
        // The window's planning tests show what a warning looks like only for kinds the mock has.
        final Board board = store.board();
        final java.util.Map<String, Long> kinds =
                java.util.stream.Stream.concat(
                                board.stories().stream()
                                        .flatMap(story -> story.warnings().stream()),
                                board.issues().stream().flatMap(issue -> issue.warnings().stream()))
                        .collect(
                                java.util.stream.Collectors.groupingBy(
                                        org.fuin.porelo.planner.store.Warning::kind,
                                        java.util.stream.Collectors.counting()));
        // Read from the API itself, so a kind added there fails here until the mock has it.
        final List<org.fuin.porelo.planner.store.Warning> factories = factories();
        final java.util.Map<String, Long> expected = new java.util.HashMap<>();
        for (final org.fuin.porelo.planner.store.Warning warning : factories) {
            // Both copies of an id on two issues carry it, and both stories the rule puts ahead
            // of issues, ST72 and ST291.
            expected.put(
                    warning.kind(),
                    warning.kind().equals(org.fuin.porelo.planner.store.Warning.ID_TWICE)
                                    || warning.kind()
                                            .equals(
                                                    org.fuin.porelo.planner.store.Warning
                                                            .ISSUE_BEHIND_STORY)
                            ? 2L
                            : 1L);
        }
        assertThat(kinds)
                .as(
                        "every kind the API makes, %d of them, once, an id on two issues and a"
                                + " story ahead of its issues twice",
                        expected.size())
                .isEqualTo(expected);
        assertThat(
                        java.util.stream.Stream.concat(
                                        board.stories().stream()
                                                .flatMap(story -> story.warnings().stream()),
                                        board.issues().stream()
                                                .flatMap(issue -> issue.warnings().stream()))
                                .map(warning -> warning.offering(null))
                                .distinct()
                                .toList())
                .as("each as the store API words it, naming places as the Git store does")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(
                                        factories.stream().map(MockStoreTest::placed),
                                        java.util.stream.Stream.of(
                                                org.fuin.porelo.planner.store.Warning
                                                        .issueBehindStory(
                                                                List.of(
                                                                        "B542", "B543", "B544",
                                                                        "B545", "B546", "B547",
                                                                        "B548", "B549", "B550",
                                                                        "B551", "B552", "HO156",
                                                                        "HO157", "B540.1"),
                                                                "Implementing")))
                                .toList());
    }

    /** A warning that names where an item is kept, worded with the mock's places. */
    private static org.fuin.porelo.planner.store.Warning placed(
            final org.fuin.porelo.planner.store.Warning warning) {
        return switch (warning.kind()) {
            case org.fuin.porelo.planner.store.Warning.ID_TWICE ->
                    org.fuin.porelo.planner.store.Warning.idTwice(
                            List.of(
                                    org.fuin.porelo.planner.store.Warning.place(
                                            "issues/B872-An-issue-whose-id-stands-twice.md",
                                            "porelo"),
                                    org.fuin.porelo.planner.store.Warning.place(
                                            "issues/B872-The-other-issue-whose-id-stands-twice.md",
                                            "porelo-host")));
            case org.fuin.porelo.planner.store.Warning.ISSUE_OUTSIDE_PROJECT ->
                    org.fuin.porelo.planner.store.Warning.issueOutsideProject(
                            List.of(
                                    org.fuin.porelo.planner.store.Warning.place(
                                            "stories/ST298-A-story-naming-an-issue-outside-the"
                                                    + "-project.md",
                                            "porelo-project")));
            default -> warning;
        };
    }

    @Test
    void whileItsWorkRulesHaveFaultsTheMockOffersNoCorrection() throws Exception {
        // Harm: the window's planning tests offer a correction the Git store would withhold, into
        // a working state whose rule could not be read.
        final String json;
        try (java.io.InputStream in =
                MockStore.class.getClassLoader().getResourceAsStream(MockStore.BOARD)) {
            json =
                    new String(
                                    java.util.Objects.requireNonNull(in).readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .replace(
                                    "\"faults\": [],",
                                    "\"faults\": [\"stories/work.yml: Building is no rule\"],");
        }
        final Board faulty =
                MockStore.read(
                        new java.io.ByteArrayInputStream(
                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(faulty.work().faults()).as("the fault read").hasSize(1);
        assertThat(
                        java.util.stream.Stream.concat(
                                        faulty.stories().stream()
                                                .flatMap(s -> s.warnings().stream()),
                                        faulty.issues().stream()
                                                .flatMap(i -> i.warnings().stream()))
                                .map(org.fuin.porelo.planner.store.Warning::correction)
                                .toList())
                .as("no correction offered")
                .isNotEmpty()
                .containsOnlyNulls();
    }

    @Test
    void theMockBoardOffersACorrectionForAStateAloneAsTheGitStoreDoes() {
        // Harm: the window's planning tests offer a correction the Git store never offers, or
        // miss the one it does.
        final Board board = store.board();
        final List<String> open =
                board.workflow().states().stream()
                        .filter(
                                state ->
                                        !board.workflow().closed().contains(state)
                                                && !board.work().rules().containsKey(state))
                        .toList();
        final java.util.Map<String, org.fuin.porelo.planner.store.Correction> offered =
                new java.util.TreeMap<>();
        board.stories()
                .forEach(
                        story ->
                                story.warnings().stream()
                                        .filter(warning -> warning.correction() != null)
                                        .forEach(
                                                warning ->
                                                        offered.put(
                                                                "story " + warning.kind(),
                                                                warning.correction())));
        board.issues()
                .forEach(
                        issue ->
                                issue.warnings().stream()
                                        .filter(warning -> warning.correction() != null)
                                        .forEach(
                                                warning ->
                                                        offered.put(
                                                                "issue " + warning.kind(),
                                                                warning.correction())));

        assertThat(open).as("the open states no work alone enters").isNotEmpty();
        assertThat(offered)
                .as("an issue's state set right by a move, nothing else offered")
                .containsExactly(
                        java.util.Map.entry(
                                "issue StateOutsideWorkflow",
                                new org.fuin.porelo.planner.store.Correction(
                                        org.fuin.porelo.planner.store.Correction.MOVE, open)));
    }

    /**
     * Every warning the store API's factories make, each checked against a kind constant, so a kind
     * named without a factory fails too.
     */
    private static List<org.fuin.porelo.planner.store.Warning> factories() {
        final List<org.fuin.porelo.planner.store.Warning> made = new java.util.ArrayList<>();
        final java.util.Set<String> constants = new java.util.TreeSet<>();
        try {
            for (final java.lang.reflect.Method method :
                    org.fuin.porelo.planner.store.Warning.class.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && method.getReturnType()
                                .equals(org.fuin.porelo.planner.store.Warning.class)
                        && (method.getParameterCount() == 0
                                || List.of(method.getParameterTypes())
                                        .equals(List.of(String.class)))) {
                    // A warning naming a field is made as the mock names it, for Minutes; one
                    // naming places is the same kind as its factory without them.
                    made.add(
                            (org.fuin.porelo.planner.store.Warning)
                                    (method.getParameterCount() == 0
                                            ? method.invoke(null)
                                            : method.invoke(null, "Minutes")));
                }
            }
            // A story ahead of its issues names them and its state, as the mock's ST72 is.
            made.add(
                    (org.fuin.porelo.planner.store.Warning)
                            org.fuin.porelo.planner.store.Warning.class
                                    .getMethod("issueBehindStory", List.class, String.class)
                                    .invoke(
                                            null,
                                            List.of("FC03", "FC09", "FC10", "FC11", "FC13"),
                                            "Backlog"));
            for (final java.lang.reflect.Field field :
                    org.fuin.porelo.planner.store.Warning.class.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && field.getType().equals(String.class)) {
                    constants.add((String) field.get(null));
                }
            }
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        assertThat(made.stream().map(org.fuin.porelo.planner.store.Warning::kind).sorted().toList())
                .as("one factory for each kind constant")
                .containsExactlyElementsOf(constants);
        return made;
    }

    @Test
    void aStoryMadeFromAnIssueOfTheMockBoardHoldsItAndAStaleOneChangesNothing() {
        // Harm: the window's planning tests of a story made from issues that stand pass against
        // a mock that holds no such story, or that took an old revision.
        final Board board = store.board();
        final java.util.Set<String> held = new java.util.HashSet<>();
        board.stories().forEach(story -> held.addAll(story.issues()));
        final List<Issue> loose =
                board.issues().stream()
                        .filter(
                                issue ->
                                        !held.contains(issue.id())
                                                && issue.parent() == null
                                                && board.holdsWork(issue.id()).isEmpty()
                                                && board.workflow().states().contains(issue.state())
                                                && !board.workflow()
                                                        .closed()
                                                        .contains(issue.state()))
                        .limit(1)
                        .toList();
        assertThat(loose).as("the mock board's loose open issue").hasSize(1);
        final List<String> ids = List.of(loose.get(0).id());

        final NewIdea.Added added =
                store.createStoryFromIssues(
                        board.revision(),
                        new StoryFromIssues("A story", "A gain.", "A test.", "High", ids));

        final Story made = added.board().story(added.story()).orElseThrow();
        assertThat(made.issues()).as("the story's issues").isEqualTo(ids);
        assertThat(added.issues()).as("the answer's issues").isEqualTo(ids);
        assertThat(made.state())
                .as("its state following its issues'")
                .isEqualTo(store.storyState(List.of(loose.get(0).state())));
        assertThat(added.board().revision()).as("a new revision").isNotEqualTo(board.revision());

        assertThatThrownBy(
                        () ->
                                store.createStoryFromIssues(
                                        board.revision(),
                                        new StoryFromIssues(
                                                "Another", "A gain.", "A test.", "High", ids)))
                .as("a story from issues against an older revision")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(store.board()).as("the board after the stale call").isEqualTo(added.board());
    }

    @Test
    void theSameStoryFromIssuesSentAgainTakesItsStoryAndAnIssueOfAnotherStoryMovesIntoTheNewOne() {
        // Harm: the window's planning tests run against a mock that makes a second story over
        // the same issue when a request is sent again, or leaves an issue under two stories,
        // which no real store does.
        final Board board = store.board();
        final java.util.Set<String> held = new java.util.HashSet<>();
        board.stories().forEach(story -> held.addAll(story.issues()));
        final String loose =
                board.issues().stream()
                        .filter(
                                issue ->
                                        !held.contains(issue.id())
                                                && issue.parent() == null
                                                && board.holdsWork(issue.id()).isEmpty()
                                                && board.workflow().states().contains(issue.state())
                                                && !board.workflow()
                                                        .closed()
                                                        .contains(issue.state()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        final StoryFromIssues request =
                new StoryFromIssues("A story", "A gain.", "A test.", "High", List.of(loose));
        final NewIdea.Added first = store.createStoryFromIssues(board.revision(), request);

        final NewIdea.Added again = store.createStoryFromIssues(first.board().revision(), request);

        assertThat(again.story()).as("the story the first call made").isEqualTo(first.story());
        assertThat(again.board().stories())
                .as("one story holding the issue")
                .filteredOn(story -> story.issues().contains(loose))
                .hasSize(1);
        assertThat(again.board()).as("the board, unchanged").isEqualTo(first.board());

        final Story other =
                board.stories().stream()
                        .filter(story -> story.issues().stream().anyMatch(id -> open(board, id)))
                        .findFirst()
                        .orElseThrow();
        final String taken =
                other.issues().stream().filter(id -> open(board, id)).findFirst().orElseThrow();
        final NewIdea.Added moved =
                store.createStoryFromIssues(
                        again.board().revision(),
                        new StoryFromIssues(
                                "Another story", "A gain.", "A test.", "High", List.of(taken)));
        assertThat(moved.board().stories())
                .as("one story holding the issue taken from another, the new one")
                .filteredOn(story -> story.issues().contains(taken))
                .extracting(Story::id)
                .containsExactly(moved.story());
    }

    @Test
    void anIssueOutsideTheWorkflowIsTakenIntoNoStoryOnTheMock() {
        // Harm: the mock hangs an issue whose state the workflow lacks into a new story, which
        // no real store does, so the window's tests pass against a board no planner serves.
        final Board board = store.board();
        final Issue outside =
                board.issues().stream()
                        .filter(issue -> !board.workflow().states().contains(issue.state()))
                        .findFirst()
                        .orElseThrow();

        assertThatThrownBy(
                        () ->
                                store.createStoryFromIssues(
                                        board.revision(),
                                        new StoryFromIssues(
                                                "A story",
                                                "A gain.",
                                                "A test.",
                                                "High",
                                                List.of(outside.id()))))
                .as("an issue in a state the workflow lacks")
                .isInstanceOfSatisfying(
                        StoreException.NotSavable.class,
                        e -> assertThat(e.subject()).as("the issue").isEqualTo(outside.id()));
        assertThat(store.board()).as("the board after the refusal").isEqualTo(board);
    }

    /** Whether an issue stands open on the board and under no other issue. */
    private static boolean open(final Board board, final String id) {
        return board.issue(id)
                .filter(
                        i ->
                                i.parent() == null
                                        && board.holdsWork(i.id()).isEmpty()
                                        && board.workflow().states().contains(i.state())
                                        && !board.workflow().closed().contains(i.state()))
                .isPresent();
    }
}
