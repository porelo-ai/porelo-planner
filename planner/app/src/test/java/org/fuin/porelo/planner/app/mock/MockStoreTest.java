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
import org.fuin.porelo.planner.core.Misfits;
import org.fuin.porelo.planner.core.MovePreview;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
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
                                "AGENT", FIRST.value(), null, null, null, null));
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
    void aStoryWithoutAStatedStateFollowsItsIssuesAndAStatedOneIsKept() {
        // A story left in its old column after its issues moved shows the window a wrong board.
        final Board after =
                store.save(
                        FIRST,
                        new Writes(
                                Map.of("PJ58", "Estimated", "SI05", "Backlog"),
                                Map.of("ST15", "Finished"),
                                List.of()));

        assertThat(story(after, "ST14").state())
                .as("the story of the moved issue, which states no state")
                .isEqualTo("Estimated");
        assertThat(story(after, "ST15"))
                .as("a story whose state was written")
                .extracting(Story::state, Story::stateGiven)
                .containsExactly("Finished", true);

        final Board unstated =
                store.save(after.revision(), new Writes(Map.of(), Map.of(), List.of("ST15")));
        assertThat(story(unstated, "ST15"))
                .as("the story whose stated state was removed")
                .extracting(Story::state, Story::stateGiven)
                .containsExactly("Backlog", false);
    }

    @Test
    void theMockBoardsGivenStoryKeepsItsStateAndItsMisfitThroughASave() {
        // Harm: the window's tests never seeing a given state that its issues do not fit.
        final Board after = store.save(FIRST, moves(Map.of("B542", "Review")));

        assertThat(story(after, "ST295"))
                .as("ST295 given Review over SL38 in Backlog, after another issue's save")
                .extracting(Story::state, Story::stateGiven)
                .containsExactly("Review", true);
        assertThat(Misfits.of(after, story(after, "ST295"), store::storyState))
                .as("SL38 stands outside the given Review, SL37 fits")
                .containsExactly("SL38");
    }

    @Test
    void aStorysStateIsDerivedAsTheStoriesIndexDerivesIt() {
        // A wrong derivation lets the moves check refuse or confirm the wrong story moves.
        assertThat(store.storyState(List.of("Review", "Backlog", "Finished")))
                .as("the earliest open state in the workflow's order")
                .isEqualTo("Backlog");
        assertThat(store.storyState(List.of("Rejected", "Finished")))
                .as("closed issues, not all rejected")
                .isEqualTo("Finished");
        assertThat(store.storyState(List.of("Rejected", "Rejected")))
                .as("every issue rejected")
                .isEqualTo("Rejected");
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
                                                List.of(),
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
                                new Story(
                                        "ST4",
                                        "A story",
                                        "Rejected",
                                        false,
                                        "High",
                                        null,
                                        List.of("PL5", "PL6"),
                                        "",
                                        null),
                                new Story(
                                        "ST5", "A story", "Idea", false, "High", null, List.of(),
                                        "", null),
                                new Story(
                                        "ST6",
                                        "A story",
                                        "Review",
                                        false,
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
                                                Map.of("codex", "gpt"))),
                                List.of()));
        final MockStore mock = new MockStore(board);
        final List<Moves.Move> moves =
                List.of(
                        new Moves.Move(true, "ST4", "Finished"),
                        new Moves.Move(true, "ST5", "Finished"),
                        new Moves.Move(true, "ST6", "Finished"));

        final MovePreview preview =
                Moves.preview(
                        board, moves, mock::storyState, (issues, stories) -> List.of(), NO_RUNS);
        final Moves.Planned planned = Moves.check(board, moves, true, mock::storyState, NO_RUNS);
        final Board after =
                mock.save(
                        FIRST, new Writes(planned.issues(), planned.stories(), planned.unstated()));

        assertThat(preview.warnings())
                .as("the done issue taken along, the rejected-only story asked about")
                .extracting(MovePreview.Warning::kind)
                .containsExactly(MovePreview.ISSUES_ALONG, MovePreview.STORY_MISMATCH);
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
        return new Writes(issues, Map.of(), List.of());
    }

    private static String state(final Board board, final String issue) {
        return board.issue(issue).map(Issue::state).orElseThrow();
    }

    private static Story story(final Board board, final String id) {
        return board.story(id).orElseThrow();
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
}
