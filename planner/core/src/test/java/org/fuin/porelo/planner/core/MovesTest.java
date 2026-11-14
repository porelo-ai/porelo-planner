package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
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

    /** The rule the stories index applies: the earliest open state, else the earliest closed. */
    private static final Moves.StoryState DERIVE = MovesTest::derive;

    private final Board board = board(WORK);

    private static Board board(final Work work) {
        return board(work, "Backlog");
    }

    private static Board board(final Work work, final String first) {
        return new Board(
                new Revision("r1"),
                new Workflow(STATES, List.of(), CLOSED),
                List.of(
                        story("ST1", "Backlog", false, "PL1", "PL2"),
                        story("ST2", "Backlog", true),
                        story("ST3", "Review", true, "PL3")),
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
    void aStoryMovesItsLeastAdvancedIssuesAndLeavesThoseFurtherOn() {
        // A story move that moved every issue would undo work already in Review.
        final Moves.Planned planned =
                Moves.check(board, List.of(story("ST1", "Review")), false, DERIVE);
        assertThat(planned.issues())
                .as("only the issue in Backlog")
                .containsExactly(entry("PL1", "Review"));
        assertThat(planned.moved()).as("the window's expected issues").containsExactly("PL1");
        assertThat(planned.stories()).as("its state follows from its issues").isEmpty();
    }

    @Test
    void aStoryMovedWhereItsIssuesDoNotFitIsRefusedNamingThemUnlessConfirmed() {
        // A story shown Rejected while an issue of it is in Review hides open work.
        final Moves.Refused refused = refused(List.of(story("ST1", "Rejected")), false);
        assertThat(refused.name()).isEqualTo("StoryStateMismatch");
        assertThat(refused.parameters())
                .as("the story, its target and each issue that does not fit")
                .containsExactly(
                        entry("story", "ST1"),
                        entry("to", "Rejected"),
                        entry("issues", "PL2:Review"));

        final Moves.Planned planned =
                Moves.check(board, List.of(story("ST1", "Rejected")), true, DERIVE);
        assertThat(planned.issues())
                .as("the Backlog issue")
                .containsExactly(entry("PL1", "Rejected"));
        assertThat(planned.stories())
                .as("the story states its state")
                .containsExactly(entry("ST1", "Rejected"));
    }

    @Test
    void aConfirmedMoveLeavesTheStoryGivenWithTheIssuesItsRefusalNamed() {
        // Harm: the board's mark and the move's refusal naming different issues for one story.
        final Moves.Refused refused = refused(List.of(story("ST1", "Rejected")), false);
        final Moves.Planned planned =
                Moves.check(board, List.of(story("ST1", "Rejected")), true, DERIVE);
        final Story saved =
                new Story(
                        "ST1",
                        "A story",
                        planned.stories().get("ST1"),
                        true,
                        "High",
                        null,
                        List.of("PL1", "PL2"),
                        "",
                        null);
        final Board after =
                new Board(
                        new Revision("r2"),
                        board.workflow(),
                        List.of(saved),
                        List.of(issue("PL1", "Rejected", null), issue("PL2", "Review", null)),
                        WORK);
        assertThat(Misfits.of(after, saved, DERIVE))
                .as("the saved story is given and PL2 stands outside Rejected, as refused")
                .containsExactly("PL2");
        assertThat(refused.parameters().get("issues"))
                .as("the refusal names the same issue")
                .isEqualTo("PL2:Review");
    }

    @Test
    void aRefusalNamesNoIssueTheBoardDoesNotHold() {
        // Harm: a refusal naming an issue as "null", which the person cannot find or move.
        final Board missing =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Backlog", false, "PL1", "XX9", "PL2")),
                        List.of(issue("PL1", "Backlog", null), issue("PL2", "Review", null)),
                        WORK);
        final Moves.Refused refused = refused(missing, List.of(story("ST1", "Rejected")));
        assertThat(refused.parameters().get("issues"))
                .as("only the issue the board holds that stands elsewhere")
                .isEqualTo("PL2:Review");
    }

    @Test
    void aStoryNoneOfWhoseIssuesTheBoardHoldsTakesTheStateItself() {
        // Harm: a move refused as a mismatch that names no issue, which nobody can confirm against.
        final Board elsewhere =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Backlog", false, "XX9")),
                        List.of(issue("PL1", "Backlog", null)),
                        WORK);
        final Moves.Planned planned =
                Moves.check(elsewhere, List.of(story("ST1", "Review")), false, DERIVE);
        assertThat(planned.stories())
                .as("taken as for a story without issues")
                .containsExactly(entry("ST1", "Review"));
        assertThat(planned.issues()).as("no issue of the board moves").isEmpty();
    }

    @Test
    void aStoryWithoutIssuesTakesTheStateItself() {
        final Moves.Planned planned =
                Moves.check(board, List.of(story("ST2", "Idea")), false, DERIVE);
        assertThat(planned.stories()).containsExactly(entry("ST2", "Idea"));
        assertThat(planned.issues()).isEmpty();
    }

    @Test
    void aMoveIntoAWorkingStateIsRefusedSoTheWindowOffersTheWork() {
        // Building entered without its work would show a story in work that nobody does.
        final Moves.Refused refused = refused(List.of(issue("PL1", "Building")), false);
        assertThat(refused.name()).isEqualTo("EnteredOnlyByWork");
        assertThat(refused.parameters())
                .containsExactly(entry("item", "PL1"), entry("to", "Building"));
        assertThat(refused(List.of(story("ST2", "Reading")), false).name())
                .as("a story too")
                .isEqualTo("EnteredOnlyByWork");
    }

    @Test
    void aPersonMovesTheirIssueIntoAWorkingStateAndOnWithoutATask() {
        // A person's issue held out of the working states could never show its work on the board.
        final Board manual = ManualIssues.of(board, "PL1");

        assertThat(Moves.check(manual, List.of(issue("PL1", "Building")), false, DERIVE).issues())
                .as("into the working state")
                .containsExactly(entry("PL1", "Building"));
        final Board building = ManualIssues.of(board(WORK, "Building"), "PL1");
        assertThat(Moves.check(building, List.of(issue("PL1", "Reading")), false, DERIVE).issues())
                .as("on to the next working state")
                .containsExactly(entry("PL1", "Reading"));
        assertThat(Moves.check(building, List.of(issue("PL1", "Review")), false, DERIVE).issues())
                .as("and out of the work")
                .containsExactly(entry("PL1", "Review"));
    }

    @Test
    void anAgentsIssueAndAStoryStillEnterAWorkingStateOnlyByWork() {
        // A hand move into Building would show work begun that no agent's task does.
        final Board manual = ManualIssues.of(board, "PL1");

        final Moves.Refused agents = refused(manual, List.of(issue("PL2", "Building")));
        assertThat(agents.name()).as("an agent's issue").isEqualTo("EnteredOnlyByWork");
        assertThat(refused(manual, List.of(story("ST1", "Building"))).name())
                .as("a story, even one with a person's issue")
                .isEqualTo("EnteredOnlyByWork");
    }

    @Test
    void aMoveIntoAStateTheWorkflowDoesNotNameIsRefused() {
        final Moves.Refused refused = refused(List.of(issue("PL1", "Shipped")), false);
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
                refused(List.of(issue("PL1", "Review"), issue("PL1", "Finished")), false);
        assertThat(twice.name()).isEqualTo("MoveNotAllowed");
        assertThat(twice.parameters()).containsEntry("item", "PL1");
        assertThat(refused(List.of(story("ST9", "Review")), false).parameters())
                .as("an unknown story")
                .containsExactly(entry("story", "ST9"));
        assertThat(refused(List.of(story("ST9", "Review")), false).name()).isEqualTo("NoSuchStory");
        assertThat(refused(List.of(issue("PL9", "Review")), false).name()).isEqualTo("NoSuchIssue");
    }

    @Test
    void aParentIsClosedOnlyOnceEverySubIssueIsClosedAfterTheBatch() {
        // A parent Finished over an open sub-issue would hide that work from the board.
        final Moves.Refused refused = refused(List.of(issue("PL4", "Finished")), false);
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
                        false,
                        DERIVE);
        assertThat(planned.issues())
                .as("closed together in one batch")
                .containsExactly(entry("PL4", "Finished"), entry("PL4.1", "Rejected"));
    }

    @Test
    void storyMovesApplyBeforeIssueMovesAsTheWindowSendsThem() {
        // The window shows an explicit issue move made after a story move as the one that wins.
        final Moves.Planned planned =
                Moves.check(
                        board,
                        List.of(issue("PL1", "Rejected"), story("ST1", "Review")),
                        true,
                        DERIVE);
        assertThat(planned.issues())
                .as("the issue's own move wins")
                .containsEntry("PL1", "Rejected");
        assertThat(planned.moved()).containsExactlyInAnyOrder("PL1");
    }

    @Test
    void aRefusedBatchPlansNothing() {
        // One refused move must leave every other move of the batch unsaved.
        final Moves.Refused refused =
                refused(List.of(issue("PL1", "Review"), issue("PL2", "Building")), false);
        assertThat(refused.name()).isEqualTo("EnteredOnlyByWork");
    }

    @Test
    void aSubIssueReopenedUnderAClosedParentIsRefused() {
        // A parent left Finished over a sub-issue in Backlog again hides open work.
        final Moves.Refused refused = refused(List.of(issue("PL5.1", "Backlog")), false);
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
                        () -> Moves.check(faulty, List.of(issue("PL1", "Review")), false, DERIVE));
        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
        assertThat(refused.getMessage()).contains("stories/work.yml: not YAML");
    }

    @Test
    void aStoryWithIssuesThatFitsItsTargetAgainLosesItsStatedState() {
        // A stated state left behind would hold the story where its issues no longer are.
        final Moves.Planned planned =
                Moves.check(board, List.of(story("ST3", "Rejected")), false, DERIVE);
        assertThat(planned.issues()).containsExactly(entry("PL3", "Rejected"));
        assertThat(planned.stories()).as("no stated state").isEmpty();
        assertThat(planned.unstated()).containsExactly("ST3");
    }

    @Test
    void aStoryFinishedWhileAnIssueStandsBeforeReviewIsRefusedNamingItAndMovesNothing() {
        // Harm: a story closed over open work, with that work carried to Finished unseen.
        final Moves.Refused refused = refused(List.of(story("ST1", "Finished")), true);
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
    void aStoryFinishedOverIssuesInReviewOrClosedMovesAloneAndTakesNoIssueAlong() {
        // Harm: a story's Finished carrying its issues past the person's test in Review.
        final Board ready =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Review", false, "PL1", "PL2", "PL5")),
                        List.of(
                                issue("PL1", "Review", null),
                                issue("PL2", "Rejected", null),
                                issue("PL5", "Finished", null)),
                        WORK);
        final Moves.Planned planned =
                Moves.check(ready, List.of(story("ST1", "Finished")), true, DERIVE);
        assertThat(planned.issues()).as("no issue is moved along").isEmpty();
        assertThat(planned.moved()).as("nor named as moved to the window").isEmpty();
        assertThat(planned.stories())
                .as("the story alone stands Finished")
                .containsExactly(entry("ST1", "Finished"));
    }

    @Test
    void aStoryFinishedOverAnIssueInReviewWhoseSubIssueStandsBeforeReviewIsRefused() {
        // Harm: a story closed over open work hidden under an issue that stands in Review.
        final Board nested =
                new Board(
                        new Revision("r1"),
                        board.workflow(),
                        List.of(story("ST1", "Review", false, "PL1")),
                        List.of(issue("PL1", "Review", null), issue("PL1.1", "Backlog", "PL1")),
                        WORK);
        assertThatThrownBy(
                        () -> Moves.check(nested, List.of(story("ST1", "Finished")), true, DERIVE))
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
                        board,
                        List.of(issue("PL2", "Review"), story("ST1", "Review")),
                        false,
                        DERIVE);
        assertThat(planned.issues())
                .as("PL2 stays in Review")
                .containsExactly(entry("PL1", "Review"));
        assertThat(planned.moved()).as("as the window expects").containsExactly("PL1", "PL2");
    }

    /** Stories given a state of their own, for a story that follows its issues again. */
    private static Board given() {
        return new Board(
                new Revision("r1"),
                new Workflow(STATES, List.of(), CLOSED),
                List.of(
                        story("ST1", "Backlog", false, "PL1"),
                        story("ST2", "Idea", true),
                        story("ST3", "Review", true, "PL3"),
                        story("ST4", "Idea", true, "PL1", "PL6"),
                        story("ST5", "Idea", true, "PL1", "OT99")), // not-a-citation
                List.of(
                        issue("PL1", "Review", null),
                        issue("PL3", "Review", null),
                        issue("PL6", "Building", null)),
                WORK);
    }

    @Test
    void aStoryThatFollowsItsIssuesAgainLosesItsGivenStateAndCarriesNoIssue() {
        // Harm: a story handed back to its issues that moves an issue, or starts work, changes what
        // nobody asked.
        for (final Map.Entry<String, String> story :
                List.of(entry("ST4", "Building"), entry("ST3", "Review"))) {
            final Moves.Planned planned =
                    Moves.check(
                            given(),
                            List.of(new Moves.Move(true, story.getKey(), story.getValue(), true)),
                            false,
                            DERIVE);
            assertThat(planned.unstated())
                    .as("%s's given state goes, to a working state or its own", story.getKey())
                    .containsExactly(story.getKey());
            assertThat(planned.issues()).as("no issue moves").isEmpty();
            assertThat(planned.stories()).as("no state is given").isEmpty();
        }
        final MovePreview preview =
                Moves.preview(
                        given(),
                        List.of(new Moves.Move(true, "ST4", "Building", true)),
                        DERIVE,
                        (issues, stories) -> List.of());
        assertThat(preview.warnings()).as("nothing to confirm, so one click does it").isEmpty();
    }

    @Test
    void aStoryFollowsItsIssuesOnlyToTheStateTheyGiveNowWithAllOfThemHeld() {
        // Harm: a stale board, or one missing an issue, unstates a story into a state its issues do
        // not give.
        for (final Moves.Move move :
                List.of(
                        new Moves.Move(true, "ST4", "Backlog", true),
                        new Moves.Move(true, "ST5", "Review", true),
                        new Moves.Move(true, "ST2", "Idea", true),
                        new Moves.Move(true, "ST1", "Review", true),
                        new Moves.Move(false, "PL1", "Review", true))) {
            final Moves.Refused refused = refused(given(), List.of(move));
            assertThat(refused.name()).as("%s refused", move).isEqualTo("MoveNotAllowed");
        }
        assertThat(
                        refused(given(), List.of(new Moves.Move(true, "ST4", "Backlog", true)))
                                .getMessage())
                .as("the state its issues give now is named")
                .contains("Building");
    }

    @Test
    void aStoryThatFollowsItsIssuesTouchesNoneOfThemSoItStopsNoWork() {
        // Harm: handing a story back to its issues stops the work running on them.
        final List<Moves.Move> follow = List.of(new Moves.Move(true, "ST4", "Building", true));
        assertThat(Moves.touched(given(), follow)).as("no issue whose run it stops").isEmpty();
        assertThat(Moves.stories(given(), follow)).as("no story whose run it stops").isEmpty();
    }

    @Test
    void aStoryFollowsItsIssuesOnlyInABatchOfItsOwn() {
        // Harm: judged after the batch's own issue moves, a story follows into a state its issues
        // on the board do not give.
        final Moves.Refused refused =
                refused(
                        given(),
                        List.of(
                                new Moves.Move(false, "PL6", "Review"),
                                new Moves.Move(true, "ST4", "Review", true)));
        assertThat(refused.getMessage()).as("refused for the batch").contains("batch of its own");
    }

    @Test
    void aStoryFollowingIntoAClosedStateIsJudgedByWhatItsIssuesGive() {
        // Harm: a refusal that names the wrong cause sends the person after a fault that is not
        // there.
        assertThat(
                        refused(given(), List.of(new Moves.Move(true, "ST4", "Finished", true)))
                                .getMessage())
                .as("the state its issues give is named")
                .contains("Its issues give Building now");
    }

    @Test
    void aStoryWhoseHeadNamesAnIssueTheStoreLeftOutFollowsNothing() {
        // Harm: read from the issues the store kept, the story would follow into a state its
        // whole set of issues does not give.
        final Story partial =
                new Story(
                        "ST6",
                        "A story",
                        "Idea",
                        true,
                        "High",
                        null,
                        List.of("PL1"),
                        "",
                        null,
                        false);
        final Board board =
                new Board(
                        new Revision("r1"),
                        new Workflow(STATES, List.of(), CLOSED),
                        List.of(partial),
                        List.of(issue("PL1", "Review", null)),
                        WORK);
        assertThat(Misfits.derivedState(board, partial, DERIVE))
                .as("no state its issues give is known")
                .isNull();
        assertThat(
                        refused(board, List.of(new Moves.Move(true, "ST6", "Review", true)))
                                .getMessage())
                .as("the follow is refused")
                .contains("does not hold all of its issues");
    }

    private Moves.Refused refused(final List<Moves.Move> moves, final boolean confirm) {
        return refused(board, moves, confirm);
    }

    private static Moves.Refused refused(final Board board, final List<Moves.Move> moves) {
        return refused(board, moves, false);
    }

    private static Moves.Refused refused(
            final Board board, final List<Moves.Move> moves, final boolean confirm) {
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class, () -> Moves.check(board, moves, confirm, DERIVE));
        assertThat(refused).as("refused: " + moves).isNotNull();
        return refused;
    }

    private static @Nullable String derive(final List<String> states) {
        final List<String> open = states.stream().filter(state -> !CLOSED.contains(state)).toList();
        final List<String> candidates = open.isEmpty() ? states : open;
        return candidates.stream()
                .min((a, b) -> STATES.indexOf(a) - STATES.indexOf(b))
                .orElse(null);
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
                Map.of("codex", "gpt"));
    }

    private static Story story(
            final String id, final String state, final boolean given, final String... issues) {
        return new Story(id, "A story", state, given, "High", null, List.of(issues), "", null);
    }

    private static Issue issue(final String id, final String state, final @Nullable String parent) {
        return new Issue(id, "porelo-planner", "An issue", state, "S", List.of(), parent, null, "");
    }
}
