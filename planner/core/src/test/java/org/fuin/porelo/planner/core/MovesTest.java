package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A batch of moves checked against the board a window shows, saved all or none. */
class MovesTest {

    private static final List<String> STATES =
            List.of("Idea", "Backlog", "Building", "Reading", "Review", "Finished", "Rejected");

    private static final List<String> CLOSED = List.of("Finished", "Rejected");

    /** Building and Reading are entered only by work, as the work rules name them. */
    private static final Work WORK =
            new Work(
                    Map.of(
                            "Building", rule("Reading"),
                            "Reading", rule(null)),
                    List.of());

    /** The work record holds no run of this planner. */
    private static final Moves.Runs NO_RUNS = (issue, stories) -> false;

    /** The work record holds a run of ST1, as it keeps one run per story. */
    private static final Moves.Runs ST1_RUNS = (issue, stories) -> stories.contains("ST1");

    private final Board board = board(WORK);

    private static Board board(final Work work) {
        return board(work, "Backlog");
    }

    private static Board board(final Work work, final String first) {
        return new Board(
                new Revision("r1"),
                new Workflow(STATES, List.of(), CLOSED),
                List.of(
                        story("ST1", "Backlog", "PL1", "PL2"),
                        storyOf("ST2", "Backlog"),
                        story("ST3", "Review", "PL3")),
                List.of(
                        issue("PL1", first, null),
                        issue("PL2", "Review", null),
                        issue("PL3", "Review", null),
                        issue("PL4", "Review", null),
                        issue("PL4.1", "Backlog", "PL4"),
                        issue("PL4.2", "Finished", "PL4"),
                        issue("PL5", "Finished", null),
                        issue("PL5.1", "Finished", "PL5")),
                work);
    }

    @Test
    void anOpenStoryMovedToAnotherOpenStateIsRefusedAndMovesNothing() {
        // Harm: an open story shown where its issues do not stand, or its issues moved by it.
        for (final String to : List.of("Review", "Idea")) {
            final Moves.Refused refused = refused(board, List.of(story("ST1", to)));
            assertThat(refused.name()).as("to %s", to).isEqualTo("MoveNotAllowed");
            assertThat(refused.parameters()).containsEntry("item", "ST1");
        }
        // A working state is entered only by the story's work, which the window offers on it.
        assertThat(refused(board, List.of(story("ST1", "Building"))).name())
                .as("to Building")
                .isEqualTo("EnteredOnlyByWork");
    }

    @Test
    void aClosedStoryMovedToAnotherClosedStateIsRefusedAndMovesNothing() {
        // Harm: a rejected story turned finished in one move, its rejected work taken for tested.
        for (final String[] pair :
                List.of(
                        new String[] {"Rejected", "Finished"},
                        new String[] {"Finished", "Rejected"})) {
            final Board closed =
                    new Board(
                            new Revision("r1"),
                            board.workflow(),
                            List.of(story("ST1", pair[0], "PL5")),
                            List.of(issue("PL5", pair[0], null)),
                            WORK);
            final Moves.Refused refused = refused(closed, List.of(story("ST1", pair[1])));
            assertThat(refused.name()).as("%s to %s", pair[0], pair[1]).isEqualTo("MoveNotAllowed");
        }
    }

    @Test
    void theFirstClosedStateCompletesWhateverTheLaterOnesAreNamed() {
        // Harm: a workflow naming its rejection Cancelled has it counted as completed work, while
        // the GitHub store closes it as not planned.
        final Workflow cancelling =
                new Workflow(
                        List.of("Idea", "Backlog", "Done", "Cancelled"),
                        List.of(),
                        List.of("Done", "Cancelled"));
        assertThat(Moves.completed(cancelling, "Done")).isTrue();
        assertThat(Moves.completed(cancelling, "Cancelled")).isFalse();
        assertThat(Moves.completed(cancelling, "Backlog")).isFalse();
    }

    @Test
    void aClosedStoryReopenedWritesItsReopenAndMovesNoIssue() {
        // Harm: a reopen that drags closed work back open unasked.
        final Board closed =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Finished", "PL5")),
                        List.of(issue("PL5", "Finished", null)),
                        WORK);
        final Moves.Planned planned =
                Moves.check(closed, List.of(story("ST1", "Backlog")), NO_RUNS);
        assertThat(planned.stories()).containsExactly(entry("ST1", "Backlog"));
        assertThat(planned.issues()).as("its issues stay closed").isEmpty();
    }

    @Test
    void aStoryRejectedClosesItAndEachOfItsOpenIssuesAsRejected() {
        // Harm: a rejected story that leaves its open work open, or refused over it.
        final Moves.Planned planned =
                Moves.check(board, List.of(story("ST1", "Rejected")), NO_RUNS);
        assertThat(planned.issues())
                .as("the Backlog and the Review issue")
                .containsExactly(entry("PL1", "Rejected"), entry("PL2", "Rejected"));
        assertThat(planned.stories())
                .as("the story is closed")
                .containsExactly(entry("ST1", "Rejected"));
    }

    @Test
    void aRejectionClosesNoIssueTheBoardDoesNotHold() {
        // Harm: a write to an issue the board cannot name, which fails the whole save.
        final Board missing =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Backlog", "PL1", "XX9", "PL2")),
                        List.of(issue("PL1", "Backlog", null), issue("PL2", "Review", null)),
                        WORK);
        assertThat(Moves.check(missing, List.of(story("ST1", "Rejected")), NO_RUNS).issues())
                .as("only the issues the board holds")
                .containsOnlyKeys("PL1", "PL2");
    }

    @Test
    void aStoryNoneOfWhoseIssuesTheBoardHoldsIsClosedByItself() {
        // Harm: a close refused for issues nobody can see or move.
        final Board elsewhere =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Backlog", "XX9")),
                        List.of(issue("PL1", "Backlog", null)),
                        WORK);
        final Moves.Planned planned =
                Moves.check(elsewhere, List.of(story("ST1", "Rejected")), NO_RUNS);
        assertThat(planned.stories()).containsExactly(entry("ST1", "Rejected"));
        assertThat(planned.issues()).as("no issue of the board moves").isEmpty();
    }

    @Test
    void aStoryWithoutIssuesIsClosedByItself() {
        final Moves.Planned planned =
                Moves.check(board, List.of(story("ST2", "Rejected")), NO_RUNS);
        assertThat(planned.stories()).containsExactly(entry("ST2", "Rejected"));
        assertThat(planned.issues()).isEmpty();
    }

    @Test
    void anAgentsIssueNoRunWorksIsMovedIntoAWorkingStateByHandAsWorkStartedElsewhere() {
        // An agent's issue held out of the working states hides work begun outside this planner.
        assertThat(Moves.check(board, List.of(issue("PL1", "Building")), NO_RUNS).issues())
                .as("into the working state, with no run of its story")
                .containsExactly(entry("PL1", "Building"));
        final Board building = board(WORK, "Building");
        assertThat(Moves.check(building, List.of(issue("PL1", "Reading")), NO_RUNS).issues())
                .as("on to the next working state")
                .containsExactly(entry("PL1", "Reading"));
    }

    @Test
    void anAgentsIssueIsRefusedAWorkingStateWhileARunOfThisPlannerWorksItOrItsStory() {
        // Harm: a hand move beside a running task, two workers on one issue.
        final Moves.Refused refused = refused(board, List.of(issue("PL1", "Building")), ST1_RUNS);
        assertThat(refused.name())
                .as("refused while a run works its story")
                .isEqualTo("EnteredOnlyByWork");
        assertThat(refused.parameters())
                .as("the item and its target")
                .containsExactly(entry("item", "PL1"), entry("to", "Building"));
        assertThat(refused.getMessage())
                .as("the reason names the run")
                .contains("a run of this planner works the issue or its story");
        assertThat(
                        refused(
                                        board,
                                        List.of(issue("PL4.1", "Building")),
                                        (issue, stories) -> issue.equals("PL4.1"))
                                .name())
                .as("refused while a run works the issue itself")
                .isEqualTo("EnteredOnlyByWork");
        assertThat(refused(List.of(story("ST2", "Reading"))).name())
                .as("a story with no run is still refused")
                .isEqualTo("EnteredOnlyByWork");
    }

    @Test
    void aPersonMovesTheirIssueIntoAWorkingStateAndOnWithoutATask() {
        // A person's issue held out of the working states could never show its work on the board.
        final Board manual = ManualIssues.of(board, "PL1");

        assertThat(Moves.check(manual, List.of(issue("PL1", "Building")), NO_RUNS).issues())
                .as("into the working state")
                .containsExactly(entry("PL1", "Building"));
        final Board building = ManualIssues.of(board(WORK, "Building"), "PL1");
        assertThat(Moves.check(building, List.of(issue("PL1", "Reading")), NO_RUNS).issues())
                .as("on to the next working state")
                .containsExactly(entry("PL1", "Reading"));
        assertThat(Moves.check(building, List.of(issue("PL1", "Review")), NO_RUNS).issues())
                .as("and out of the work")
                .containsExactly(entry("PL1", "Review"));
    }

    @Test
    void anAgentsIssueBesideARunAndAStoryStillEnterAWorkingStateOnlyByWork() {
        // A hand move into Building would show work begun that no agent's task does.
        final Board manual = ManualIssues.of(board, "PL1");

        final Moves.Refused agents = refused(manual, List.of(issue("PL2", "Building")), ST1_RUNS);
        assertThat(agents.name()).as("an agent's issue").isEqualTo("EnteredOnlyByWork");
        assertThat(refused(manual, List.of(story("ST1", "Building"))).name())
                .as("a story, even one with a person's issue")
                .isEqualTo("EnteredOnlyByWork");
    }

    @Test
    void aMoveIntoAStateTheWorkflowDoesNotNameIsRefused() {
        final Moves.Refused refused = refused(List.of(issue("PL1", "Shipped")));
        assertThat(refused.name()).isEqualTo("StateNotAllowed");
        assertThat(refused.parameters())
                .containsEntry("item", "PL1")
                .containsEntry("from", "Backlog")
                .containsEntry("to", "Shipped");
    }

    @Test
    void anItemNamedTwiceOrNotOnTheBoardIsRefused() {
        // A batch naming an item twice would save whichever move came last.
        final Moves.Refused twice =
                refused(List.of(issue("PL1", "Review"), issue("PL1", "Finished")));
        assertThat(twice.name()).isEqualTo("MoveNotAllowed");
        assertThat(twice.parameters()).containsEntry("item", "PL1");
        assertThat(refused(List.of(story("ST9", "Review"))).parameters())
                .as("an unknown story")
                .containsExactly(entry("story", "ST9"));
        assertThat(refused(List.of(story("ST9", "Review"))).name()).isEqualTo("NoSuchStory");
        assertThat(refused(List.of(issue("PL9", "Review"))).name()).isEqualTo("NoSuchIssue");
    }

    @Test
    void aParentIsClosedOnlyOnceEverySubIssueIsClosedAfterTheBatch() {
        // A parent Finished over an open sub-issue would hide that work from the board.
        final Moves.Refused refused = refused(List.of(issue("PL4", "Finished")));
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
        assertThat(refused.parameters())
                .containsEntry("item", "PL4")
                .containsEntry("to", "Finished");
        assertThat(refused.getMessage())
                .as("names the open sub-issue")
                .contains("PL4.1", "Backlog");

        final Moves.Planned planned =
                Moves.check(
                        board,
                        List.of(issue("PL4", "Finished"), issue("PL4.1", "Rejected")),
                        NO_RUNS);
        assertThat(planned.issues())
                .as("closed together in one batch")
                .containsExactly(entry("PL4", "Finished"), entry("PL4.1", "Rejected"));
    }

    @Test
    void aClosedParentElsewhereWithAnOpenSubIssueRefusesNoUnrelatedMoveOrCreate() {
        // Harm: one closed parent over open work, left so on GitHub, refuses every move and every
        // create on the board until someone finds and mends it.
        final Board left = withClosedParentOverOpenWork();

        final Moves.Planned moved = Moves.check(left, List.of(issue("PL1", "Review")), NO_RUNS);
        assertThat(moved.issues()).as("an issue's move").containsExactly(entry("PL1", "Review"));

        final Moves.Planned finished =
                Moves.check(left, List.of(story("ST3", "Finished")), NO_RUNS);
        assertThat(finished.issues())
                .as("a story finished over its issue")
                .containsExactly(entry("PL3", "Finished"));

        // A create is checked as the move of its new issue into its state, as the app sends it.
        final Moves.Planned created =
                Moves.check(
                        withIssue(left, issue("porelo-planner#900", "Idea", null)),
                        List.of(issue("porelo-planner#900", "Backlog")),
                        NO_RUNS);
        assertThat(created.issues())
                .as("a new issue in its state")
                .containsExactly(entry("porelo-planner#900", "Backlog"));
    }

    @Test
    void aClosedParentOverOpenWorkStillRefusesAMoveThatConcernsIt() {
        // Harm: work hidden under a closed parent once the parent or its sub-issue is moved.
        final Board left = withClosedParentOverOpenWork();

        final Moves.Refused parent =
                refused(left, List.of(issue("PL6", "Rejected"), issue("PL1", "Review")));
        assertThat(parent.parameters()).as("the parent moved").containsEntry("item", "PL6");
        assertThat(parent.getMessage()).as("its open sub-issue").contains("PL6.1", "Backlog");

        final Moves.Refused sub = refused(left, List.of(issue("PL6.1", "Idea")));
        assertThat(sub.parameters())
                .as("the parent of the sub-issue moved")
                .containsEntry("item", "PL6");
        assertThat(sub.getMessage()).as("the sub-issue moved").contains("PL6.1", "Idea");
    }

    /** The board with PL6 Finished over PL6.1 in Backlog, as GitHub can hold it. */
    private Board withClosedParentOverOpenWork() {
        return withIssue(
                withIssue(board, issue("PL6", "Finished", null)), issue("PL6.1", "Backlog", "PL6"));
    }

    private static Board withIssue(final Board on, final Issue added) {
        final List<Issue> issues = new java.util.ArrayList<>(on.issues());
        issues.add(added);
        return new Board(on.revision(), on.workflow(), on.stories(), issues, on.work());
    }

    @Test
    void storyMovesApplyBeforeIssueMovesAsTheWindowSendsThem() {
        // The window shows an explicit issue move made after a story move as the one that wins.
        final Moves.Planned planned =
                Moves.check(
                        board,
                        List.of(issue("PL1", "Finished"), story("ST1", "Rejected")),
                        NO_RUNS);
        assertThat(planned.issues())
                .as("the issue's own move wins")
                .containsEntry("PL1", "Finished")
                .containsEntry("PL2", "Rejected");
        assertThat(planned.moved()).containsExactlyInAnyOrder("PL1", "PL2");
    }

    @Test
    void aRefusedBatchPlansNothing() {
        // One refused move must leave every other move of the batch unsaved.
        final Moves.Refused refused =
                refused(board, List.of(issue("PL1", "Review"), issue("PL2", "Building")), ST1_RUNS);
        assertThat(refused.name()).isEqualTo("EnteredOnlyByWork");
    }

    @Test
    void aSubIssueReopenedUnderAClosedParentIsRefused() {
        // A parent left Finished over a sub-issue in Backlog again hides open work.
        final Moves.Refused refused = refused(List.of(issue("PL5.1", "Backlog")));
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
        assertThat(refused.parameters())
                .containsEntry("item", "PL5")
                .containsEntry("to", "Finished");
        assertThat(refused.getMessage()).contains("PL5.1", "Backlog");
    }

    @Test
    void workRulesWithFaultsRefuseEveryMove() {
        // A working state whose rule could not be read would be entered without its work.
        final Board faulty = board(new Work(Map.of(), List.of("stories/work.yml: not YAML")));
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () -> Moves.check(faulty, List.of(issue("PL1", "Review")), NO_RUNS));
        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
        assertThat(refused.getMessage()).contains("stories/work.yml: not YAML");
    }

    @Test
    void aStoryAlreadyInItsTargetWritesNoCloseOfItsOwn() {
        // Harm: a close sent twice, which a cut-off between the two writes leaves half done.
        final Board closed =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST3", "Rejected", "PL3")),
                        List.of(issue("PL3", "Review", null)),
                        WORK);
        final Moves.Planned planned =
                Moves.check(closed, List.of(story("ST3", "Rejected")), NO_RUNS);
        assertThat(planned.issues()).containsExactly(entry("PL3", "Rejected"));
        assertThat(planned.stories()).as("the story stands closed already").isEmpty();
    }

    @Test
    void aStoryFinishedWhileAnIssueStandsBeforeReviewIsRefusedNamingItAndMovesNothing() {
        // Harm: a story closed over open work, with that work carried to Finished unseen.
        final Moves.Refused refused = refused(List.of(story("ST1", "Finished")));
        assertThat(refused.name()).as("refused even when confirmed").isEqualTo("MoveNotAllowed");
        assertThat(refused.parameters())
                .as("the story, its move and the issue that stands before Review")
                .containsEntry("item", "ST1")
                .containsEntry("from", "Backlog")
                .containsEntry("to", "Finished");
        assertThat(refused.parameters().get("reason"))
                .as("names the open issue and not the one in Review")
                .contains("PL1:Backlog")
                .doesNotContain("PL2");
    }

    @Test
    void aStoryFinishedOverIssuesInReviewOrClosedFinishesThoseInReviewAndFitsThem() {
        // Prevents a story finished while its issues in Review stay open for good.
        final Board ready =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Review", "PL1", "PL2", "PL5")),
                        List.of(
                                issue("PL1", "Review", null),
                                issue("PL1.1", "Review", "PL1"),
                                issue("PL2", "Rejected", null),
                                issue("PL5", "Finished", null)),
                        WORK);
        final Moves.Planned planned =
                Moves.check(ready, List.of(story("ST1", "Finished")), NO_RUNS);
        assertThat(planned.issues())
                .as("the issue in Review and its sub-issue in Review finish with the story")
                .containsExactly(entry("PL1", "Finished"), entry("PL1.1", "Finished"));
        assertThat(planned.moved())
                .as("named as moved to the window")
                .containsExactly("PL1", "PL1.1");
        assertThat(planned.stories())
                .as("the story is closed")
                .containsExactly(entry("ST1", "Finished"));

        final MovePreview preview =
                Moves.preview(
                        ready,
                        List.of(story("ST1", "Finished")),
                        (issues, stories) -> List.of(),
                        NO_RUNS);
        assertThat(preview.warnings())
                .as("only the issues carried along are asked about")
                .extracting(MovePreview.Warning::kind)
                .containsExactly(MovePreview.ISSUES_ALONG);
        assertThat(preview.warnings().getFirst().issues())
                .as(
                        "each issue that finishes, own repository too, so the person sees whose file goes")
                .extracting(MovePreview.MovedIssue::id)
                .containsExactly("PL1", "PL1.1");
    }

    @Test
    void aStoryFinishedNamesItsIssueThatAnotherOpenStoryHoldsTooInTheWarning() {
        // Prevents an issue finished under a second open story without the person seeing it.
        final Board shared =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(
                                story("ST1", "Review", "PL1"),
                                story("ST2", "Backlog", "PL1", "PL2")),
                        List.of(issue("PL1", "Review", null), issue("PL2", "Backlog", null)),
                        WORK);
        final MovePreview preview =
                Moves.preview(
                        shared,
                        List.of(story("ST1", "Finished")),
                        (issues, stories) -> List.of(),
                        NO_RUNS);
        assertThat(preview.warnings())
                .as("the carried issue is named for ST1's move")
                .filteredOn(warning -> MovePreview.ISSUES_ALONG.equals(warning.kind()))
                .singleElement()
                .satisfies(
                        warning ->
                                assertThat(warning.issues())
                                        .as("PL1 finishing")
                                        .containsExactly(
                                                new MovePreview.MovedIssue(
                                                        "PL1",
                                                        "porelo-planner",
                                                        "Review",
                                                        "Finished")));
    }

    @Test
    void aStoryFinishedIsJudgedOnTheBoardSoAnIssueMovedToReviewInTheSameBatchIsRefused() {
        // Prevents an issue finished that never reached the person's test in Review.
        final Board forged =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(
                                story("ST1", "Review", "PL1", "PL2"),
                                story("ST2", "Backlog", "PL1")),
                        List.of(issue("PL1", "Backlog", null), issue("PL2", "Review", null)),
                        WORK);
        for (final List<Moves.Move> batch :
                List.of(
                        List.of(issue("PL1", "Review"), story("ST1", "Finished")),
                        List.of(issue("PL1", "Finished"), story("ST1", "Finished")))) {
            final Moves.Refused refused = refused(forged, batch);
            assertThat(refused.parameters().get("reason"))
                    .as("%s refused naming the issue as the board holds it", batch)
                    .contains("PL1:Backlog");
        }
    }

    @Test
    void aStoryFinishedOverASubIssueInReviewUnderARejectedParentIsRefused() {
        // Prevents work under a rejected issue finished as if it were accepted.
        final Board rejected =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Review", "PL1", "PL2")),
                        List.of(
                                issue("PL1", "Rejected", null),
                                issue("PL1.1", "Review", "PL1"),
                                issue("PL2", "Review", null)),
                        WORK);
        final Moves.Refused refused = refused(rejected, List.of(story("ST1", "Finished")));
        assertThat(refused.parameters())
                .as("the story's move is refused")
                .containsEntry("item", "ST1")
                .containsEntry("to", "Finished");
        assertThat(refused.parameters().get("reason"))
                .as("names the sub-issue and its rejected parent")
                .contains("PL1.1", "PL1", "Rejected");
    }

    @Test
    void aStoryFinishedBesideTheRejectionOfTheParentOfAReviewSubIssueIsRefused() {
        // Prevents work under an issue rejected in the same batch finished as if it were accepted.
        final Board review =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Review", "PL1")),
                        List.of(issue("PL1", "Review", null), issue("PL1.1", "Review", "PL1")),
                        WORK);
        final Moves.Refused refused =
                refused(review, List.of(story("ST1", "Finished"), issue("PL1", "Rejected")));
        assertThat(refused.parameters().get("reason"))
                .as("names the sub-issue and its rejected parent")
                .contains("PL1.1", "PL1", "Rejected");
    }

    @Test
    void aStoryFinishedOverAnIssueItsOwnMoveTakesBackIsRefused() {
        // Prevents a story finished while the same batch reopens one of its issues.
        final Board ready =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Review", "PL1", "PL2")),
                        List.of(issue("PL1", "Review", null), issue("PL2", "Review", null)),
                        WORK);
        final Moves.Refused refused =
                refused(ready, List.of(story("ST1", "Finished"), issue("PL2", "Backlog")));
        assertThat(refused.parameters().get("reason"))
                .as("names the issue as the batch leaves it")
                .contains("PL2:Backlog");
    }

    @Test
    void aStoryFinishedOverAnIssueInReviewWhoseSubIssueStandsBeforeReviewIsRefused() {
        // Harm: a story closed over open work hidden under an issue that stands in Review.
        final Board nested =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Review", "PL1")),
                        List.of(issue("PL1", "Review", null), issue("PL1.1", "Backlog", "PL1")),
                        WORK);
        assertThatThrownBy(() -> Moves.check(nested, List.of(story("ST1", "Finished")), NO_RUNS))
                .as("the sub-issue keeps the story open")
                .isInstanceOf(Moves.Refused.class)
                .satisfies(
                        e ->
                                assertThat(((Moves.Refused) e).parameters().get("reason"))
                                        .as("names the sub-issue")
                                        .contains("PL1.1:Backlog"));
    }

    @Test
    void onlyIssuesWhoseStateChangesAreWrittenButEveryMovedIssueIsNamed() {
        // A write of an unchanged head would make a commit that changes nothing.
        final Moves.Planned planned =
                Moves.check(
                        board, List.of(issue("PL2", "Review"), story("ST1", "Rejected")), NO_RUNS);
        assertThat(planned.issues())
                .as("PL2 stays in Review")
                .containsExactly(entry("PL1", "Rejected"));
        assertThat(planned.moved())
                .as("as the window expects")
                .containsExactlyInAnyOrder("PL1", "PL2");
    }

    @Test
    void anIssueOutsideTheWorkflowMovesIntoItFromItsOwnStateUnderTheRulesOfABoardIssue() {
        // Harm: an issue whose Status a person set outside the workflow can never be moved back,
        // or comes back in a working state no task works.
        final Board outside =
                withOutside(
                        board,
                        outside("PL7", "ST1", null, Worker.AGENT),
                        outside("PL8", null, null, Worker.MANUAL));

        final Moves.Planned planned =
                Moves.check(outside, List.of(issue("PL7", "Backlog")), NO_RUNS);
        assertThat(planned.issues())
                .as("its state written")
                .containsExactly(entry("PL7", "Backlog"));
        assertThat(planned.moved()).as("the window's expected issues").containsExactly("PL7");
        final MovePreview preview =
                Moves.preview(
                        outside,
                        List.of(issue("PL7", "Backlog")),
                        (issues, stories) -> List.of(),
                        NO_RUNS);
        assertThat(preview.issues())
                .as("the preview moves it from the state it stands in")
                .containsExactly(
                        new MovePreview.MovedIssue("PL7", "porelo-planner", "Accepted", "Backlog"));
        assertThat(Moves.stories(outside, List.of(issue("PL7", "Backlog"))))
                .as("its story's running work is the work it moves under")
                .containsExactly("ST1");

        final Moves.Refused agents = refused(outside, List.of(issue("PL7", "Building")), ST1_RUNS);
        assertThat(agents.name())
                .as("an agent's issue beside its story's run is refused, as on the board")
                .isEqualTo("EnteredOnlyByWork");
        assertThat(agents.getMessage())
                .as("with the words a board issue is refused with")
                .isEqualTo(
                        refused(board, List.of(issue("PL1", "Building")), ST1_RUNS).getMessage());
        assertThat(Moves.check(outside, List.of(issue("PL8", "Building")), NO_RUNS).issues())
                .as("a person's issue moved into a working state by hand")
                .containsExactly(entry("PL8", "Building"));
        assertThat(refused(outside, List.of(issue("PL9", "Backlog"))).name())
                .as("an id not on the board")
                .isEqualTo("NoSuchIssue");
    }

    @Test
    void anIssueOutsideTheWorkflowIsNotMovedOpenUnderAClosedParent() {
        // Harm: a sub-issue brought back open under a parent Finished hides its work from the
        // board.
        final Board outside = withOutside(board, outside("PL5.2", null, "PL5", Worker.AGENT));

        final Moves.Refused refused = refused(outside, List.of(issue("PL5.2", "Backlog")));
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
        assertThat(refused.parameters()).containsEntry("item", "PL5");
        assertThat(Moves.check(outside, List.of(issue("PL5.2", "Finished")), NO_RUNS).issues())
                .as("closed beside its parent")
                .containsExactly(entry("PL5.2", "Finished"));
    }

    @Test
    void aSubIssueOutsideTheWorkflowIsNotMovedOpenUnderItsParentOutsideItClosedInTheBatch() {
        // Harm: two issues brought back at once leave a sub-issue open under a Finished parent.
        final Board outside =
                withOutside(
                        board,
                        outside("PL6", null, null, Worker.AGENT),
                        outside("PL6.1", null, "PL6", Worker.AGENT));

        final Moves.Refused refused =
                refused(outside, List.of(issue("PL6", "Finished"), issue("PL6.1", "Backlog")));
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
        assertThat(refused.parameters()).as("the parent named").containsEntry("item", "PL6");
    }

    @Test
    void aStoryMovedWithItsIssueOutsideTheWorkflowJudgesThatIssueInTheStateItIsMovedTo() {
        // Harm: a story Finished in the same batch that brings an issue of it back open, or one
        // stated where that issue does not fit, as a board issue would refuse it.
        final Board outside =
                withOutside(
                        board,
                        outside("PL7", "ST3", null, Worker.AGENT),
                        outside("PL3.1", null, "PL3", Worker.AGENT));

        final Moves.Refused finished =
                refused(
                        outside,
                        List.of(
                                story("ST3", "Finished"),
                                issue("PL7", "Idea"),
                                issue("PL3.1", "Finished")));
        assertThat(finished.name()).as("its issue brought back open").isEqualTo("MoveNotAllowed");
        assertThat(finished.getMessage()).as("the issue named").contains("PL7:Idea");
        // Its parent kept in Review by its own move, so only the story's finish can refuse it.
        final Moves.Refused sub =
                refused(
                        outside,
                        List.of(
                                story("ST3", "Finished"),
                                issue("PL7", "Finished"),
                                issue("PL3", "Review"),
                                issue("PL3.1", "Idea")));
        assertThat(sub.name()).as("a sub-issue brought back open").isEqualTo("MoveNotAllowed");
        assertThat(sub.getMessage()).as("the sub-issue named").contains("PL3.1:Idea");
        final Moves.Refused left = refused(outside, List.of(story("ST3", "Finished")));
        assertThat(left.name())
                .as("an issue left outside the workflow holds its story's finish back")
                .isEqualTo("MoveNotAllowed");
        assertThat(left.getMessage())
                .as("named with its warning")
                .isEqualTo("PL7: " + Warning.stateOutsideWorkflow().text());
        assertThat(
                        Moves.check(
                                        outside,
                                        List.of(
                                                story("ST3", "Finished"),
                                                issue("PL7", "Finished"),
                                                issue("PL3.1", "Finished")),
                                        NO_RUNS)
                                .issues())
                .as("finished with its issues the same batch moves into the workflow")
                .containsEntry("PL7", "Finished")
                .containsEntry("PL3.1", "Finished");
    }

    /** An issue a person set to a Status the workflow lacks, and the story it stands under. */
    private record Outside(Issue issue, @Nullable String story) {}

    /** The board with each issue outside the workflow on it, warned, under its story. */
    private static Board withOutside(final Board board, final Outside... outside) {
        final List<Issue> issues = new ArrayList<>(board.issues());
        final List<Story> stories = new ArrayList<>();
        for (final Outside each : outside) {
            issues.add(each.issue());
        }
        for (final Story story : board.stories()) {
            final List<String> under = new ArrayList<>(story.issues());
            for (final Outside each : outside) {
                if (story.id().equals(each.story())) {
                    under.add(each.issue().id());
                }
            }
            stories.add(
                    new Story(
                            story.id(),
                            story.title(),
                            story.state(),
                            story.priority(),
                            story.size(),
                            under,
                            story.description(),
                            story.link(),
                            story.issuesWhole(),
                            story.warnings()));
        }
        return new Board(
                board.revision(),
                board.workflow(),
                stories,
                issues,
                board.work(),
                board.repositories(),
                List.of(),
                null);
    }

    private static Outside outside(
            final String id,
            final @Nullable String story,
            final @Nullable String parent,
            final Worker worker) {
        return new Outside(
                new Issue(
                                id,
                                "porelo-planner",
                                "An issue",
                                "Accepted",
                                null,
                                List.of(),
                                parent,
                                null,
                                "",
                                worker,
                                Risk.ORDINARY)
                        .withWarnings(List.of(Warning.stateOutsideWorkflow())),
                story);
    }

    private Moves.Refused refused(final List<Moves.Move> moves) {
        return refused(board, moves, NO_RUNS);
    }

    /** A board of the given story and issues, over the project's one repository and another. */
    private static Board warnedBoard(final Story story, final Issue... issues) {
        return new Board(
                new Revision("r1"),
                new Workflow(STATES, List.of(), CLOSED),
                List.of(story),
                List.of(issues),
                WORK,
                org.fuin.porelo.planner.store.ProjectRepository.named(
                        List.of("porelo-planner", "porelo")));
    }

    @Test
    void anIssueInAStateOutsideTheWorkflowMovesIntoItByItsOwnMove() {
        // The move is how a person sets a hand-edited state right from the board.
        final Board board =
                warnedBoard(
                        story("ST1", "Backlog", "PL1"),
                        issue("PL1", "Parked", null)
                                .withWarnings(List.of(Warning.stateOutsideWorkflow())));
        final Moves.Planned planned = Moves.check(board, List.of(issue("PL1", "Backlog")), NO_RUNS);
        assertThat(planned.issues())
                .as("the issue is written into the state picked")
                .containsExactly(entry("PL1", "Backlog"));
    }

    @Test
    void aStoryIsNotFinishedOverAnIssueInAStateOutsideTheWorkflow() {
        // Finishing the story would close it while one of its issues is still open by hand.
        final Board board =
                warnedBoard(
                        story("ST1", "Review", "PL1", "PL2"),
                        issue("PL1", "Finished", null),
                        issue("PL2", "Parked", null)
                                .withWarnings(List.of(Warning.stateOutsideWorkflow())));
        assertThatThrownBy(() -> Moves.check(board, List.of(story("ST1", "Finished")), NO_RUNS))
                .as("the story stays open")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage("PL2: " + Warning.stateOutsideWorkflow().text())
                .satisfies(
                        error ->
                                assertThat(((Moves.Refused) error).name())
                                        .isEqualTo("MoveNotAllowed"));
    }

    @Test
    void aStoryWhoseIssuesTheStoreCouldNotAllNameIsNotFinishedButMayStillBeRejected() {
        // Harm: a story finished while an issue of it that the board does not show is still open,
        // its known issues all done.
        final Story partial =
                new Story("ST1", "A story", "Review", "High", null, List.of("PL1"), "", null, false)
                        .withWarnings(List.of(Warning.issueOutsideProject()));
        final Board board = warnedBoard(partial, issue("PL1", "Review", null));
        assertThatThrownBy(() -> Moves.check(board, List.of(story("ST1", "Finished")), NO_RUNS))
                .as("the story stays open")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage(
                        "Not every issue of it is on the board, so whether all are done cannot be"
                                + " told.")
                .satisfies(
                        error ->
                                assertThat(((Moves.Refused) error).name())
                                        .isEqualTo("MoveNotAllowed"));
        assertThat(Moves.check(board, List.of(story("ST1", "Rejected")), NO_RUNS).issues())
                .as("rejecting it finishes nothing and is still the person's to do")
                .containsExactly(entry("PL1", "Rejected"));
    }

    @Test
    void aStoryRejectedClosesItsIssueInAKnownStateAndLeavesTheOneInDoubt() {
        // A state the workflow does not know must never count as an open one to close.
        final Board board =
                warnedBoard(
                        story("ST1", "Backlog", "PL1", "PL2"),
                        issue("PL1", "Parked", null)
                                .withWarnings(List.of(Warning.stateOutsideWorkflow())),
                        issue("PL2", "Backlog", null));
        final Moves.Planned planned =
                Moves.check(board, List.of(story("ST1", "Rejected")), NO_RUNS);
        assertThat(planned.issues())
                .as("only the issue in a state of the workflow is carried")
                .containsExactly(entry("PL2", "Rejected"));
    }

    @Test
    void aMoveNamingAnIdOnTwoIssuesIsRefusedNamingBothRepositories() {
        // Saved by id, the move would write whichever issue the store found first.
        final Board board =
                warnedBoard(
                        story("ST1", "Backlog", "PL1"),
                        issue("PL1", "Backlog", null).withWarnings(List.of(Warning.idTwice())),
                        new Issue(
                                        "PL1",
                                        "porelo",
                                        "An issue",
                                        "Backlog",
                                        "S",
                                        List.of(),
                                        null,
                                        null,
                                        "")
                                .withWarnings(List.of(Warning.idTwice())));
        final Moves.Refused refused = refused(board, List.of(issue("PL1", "Review")));
        assertThat(refused.name()).as("the move is not allowed").isEqualTo("MoveNotAllowed");
        assertThat(refused.getMessage())
                .as("the words name both repositories")
                .isEqualTo(
                        "PL1 names an issue in porelo-planner and porelo; it can be neither"
                                + " worked nor moved until one id is changed.");
        assertThat(Moves.check(board, List.of(story("ST1", "Rejected")), NO_RUNS).issues())
                .as("its story's rejection closes neither copy")
                .isEmpty();
    }

    @Test
    void aMoveOfAnIssueCarryingAnyKindTheGitHubStoreWarnsWithIsRefusedWithTheWarning() {
        // Harm: a move closes or reopens an issue a person edited by hand into what the board
        // cannot hold, a story label in another repository or a cut link among them.
        for (final Warning warning : HeldBoards.HAND_EDITS) {
            final Board board =
                    warnedBoard(
                            story("ST1", "Backlog", "PL1"),
                            issue("PL1", "Backlog", null).withWarnings(List.of(warning)));
            assertThat(refused(board, List.of(issue("PL1", "Finished"))).getMessage())
                    .as("a move of an issue warned " + warning.kind())
                    .isEqualTo("PL1: " + warning.text());
        }
    }

    @Test
    void aMoveOfAnIssueWarnedAboutItsParentIsRefusedWithTheWarning() {
        // A move into a closed state would close an issue whose parent the planner cannot see.
        final Board board =
                warnedBoard(
                        story("ST1", "Backlog", "PL1"),
                        issue("PL1", "Backlog", null)
                                .withWarnings(List.of(Warning.parentOutsideProject())));
        assertThat(refused(board, List.of(issue("PL1", "Finished"))).getMessage())
                .as("the warning's words")
                .isEqualTo("PL1: " + Warning.parentOutsideProject().text());
    }

    private static Moves.Refused refused(final Board board, final List<Moves.Move> moves) {
        return refused(board, moves, NO_RUNS);
    }

    private static Moves.Refused refused(
            final Board board, final List<Moves.Move> moves, final Moves.Runs runs) {
        final Moves.Refused refused =
                catchThrowableOfType(Moves.Refused.class, () -> Moves.check(board, moves, runs));
        assertThat(refused).as("refused: " + moves).isNotNull();
        return refused;
    }

    private static Map.Entry<String, String> entry(final String key, final String value) {
        return Map.entry(key, value);
    }

    private static Moves.Move story(final String id, final String to) {
        return new Moves.Move(true, id, to);
    }

    private static Moves.Move issue(final String id, final String to) {
        return new Moves.Move(false, id, to);
    }

    private static WorkRule rule(final @Nullable String then) {
        return new WorkRule(
                List.of("Backlog"),
                "Review",
                then,
                null,
                WorkRule.Per.STORY,
                List.of("UNATTENDED"),
                "Build {story}",
                Map.of("codex", "gpt"),
                Roles.forRule(null, null));
    }

    private static Story story(final String id, final String state, final String... issues) {
        return new Story(id, "A story", state, "High", null, List.of(issues), "", null);
    }

    /** A story without issues, which a move's helper of the same name would shadow. */
    private static Story storyOf(final String id, final String state) {
        return new Story(id, "A story", state, "High", null, List.of(), "", null);
    }

    private static Issue issue(final String id, final String state, final @Nullable String parent) {
        return new Issue(id, "porelo-planner", "An issue", state, "S", List.of(), parent, null, "");
    }
}
