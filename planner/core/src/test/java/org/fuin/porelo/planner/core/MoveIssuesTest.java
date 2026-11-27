package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Many issues moved in one call: each move judged, the allowed ones saved together. */
class MoveIssuesTest {

    private static final List<String> STATES =
            List.of("Idea", "Backlog", "Building", "Review", "Finished", "Rejected");

    private static final Work WORK =
            new Work(
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
                                    Roles.forRule(null, null))),
                    List.of());

    private static final Moves.Runs NO_RUNS = (issue, stories) -> false;

    private static final MovePreview.Running NOTHING_RUNS = (issues, stories) -> List.of();

    private final Board board =
            board(
                    WORK,
                    issue("PL1", "Backlog", null),
                    issue("PL2", "Review", null),
                    issue("PL3", "Review", null),
                    issue("PL4", "Backlog", null),
                    issue("PL5", "Idea", null),
                    issue("PL6", "Review", null),
                    issue("PL6.1", "Backlog", "PL6"));

    private static Board board(final Work work, final Issue... issues) {
        return new Board(
                new Revision("r1"),
                new Workflow(STATES, List.of(), List.of("Finished", "Rejected")),
                List.of(),
                List.of(issues),
                work);
    }

    private static Issue issue(final String id, final String state, final @Nullable String parent) {
        return new Issue(id, "porelo-planner", "An issue", state, "S", List.of(), parent, null, "");
    }

    private static MoveIssues.Ask ask(final String issue, final String to) {
        return new MoveIssues.Ask(issue, to, null);
    }

    private MoveIssues.Judged judge(final boolean keepsReasons, final MoveIssues.Ask... asks) {
        return MoveIssues.judge(board, List.of(asks), keepsReasons, NOTHING_RUNS, NO_RUNS);
    }

    /** The name of the item's refusal, or empty for an item not refused. */
    private static String refusalName(final MoveIssues.Item item) {
        final @Nullable RuntimeException refusal = item.refusal();
        if (refusal instanceof Moves.Refused refused) {
            return refused.name();
        }
        return refusal instanceof WorkRefusal work ? work.name() : "";
    }

    @Test
    void fiveMovesOfWhichTwoBreakARuleSaveThreeInOneSaveAndNameTheTwoWithTheirReasons() {
        // Harm: one broken move holding back the others, or dropped without its reason.
        final MoveIssues.Judged judged =
                judge(
                        true,
                        ask("PL1", "Review"),
                        ask("PL2", "Finished"),
                        ask("PL3", "Rejected"),
                        ask("PL4", "Nowhere"),
                        new MoveIssues.Ask("PL5", "Backlog", "Why not."));

        assertThat(judged.items())
                .as("every move answered once, in the order sent")
                .extracting(MoveIssues.Item::issue)
                .containsExactly("PL1", "PL2", "PL3", "PL4", "PL5");
        assertThat(judged.items())
                .as("what became of each move")
                .extracting(MoveIssues.Item::outcome)
                .containsExactly(
                        MoveIssues.Outcome.SAVED,
                        MoveIssues.Outcome.SAVED,
                        MoveIssues.Outcome.SAVED,
                        MoveIssues.Outcome.REFUSED,
                        MoveIssues.Outcome.REFUSED);
        assertThat(judged.items().get(3).refusal())
                .as("the unknown state's refusal, as the call alone words it")
                .isInstanceOfSatisfying(
                        Moves.Refused.class,
                        refused -> {
                            assertThat(refused.name()).as("name").isEqualTo("StateNotAllowed");
                            assertThat(refused.parameters())
                                    .as("the issue it is about")
                                    .containsEntry("item", "PL4");
                        });
        assertThat(refusalName(judged.items().get(4)))
                .as("a reason on a move into an open state")
                .isEqualTo("MoveNotAllowed");
        assertThat(judged.planned().issues())
                .as("the three moves allowed, written together")
                .containsOnly(
                        Map.entry("PL1", "Review"),
                        Map.entry("PL2", "Finished"),
                        Map.entry("PL3", "Rejected"));
        assertThat(judged.moves()).as("only the allowed moves are carried").hasSize(3);
        assertThat(judged.saves()).as("a save is needed").isTrue();
        assertThat(judged.preview().targets().keySet())
                .as("the save checks exactly the issues it changes")
                .containsExactly("PL1", "PL2", "PL3");
    }

    @Test
    void aMoveToTheStateAnIssueStandsInIsUnchangedAndWritesNothing() {
        // Harm: a save and a commit for a move that changes nothing.
        final MoveIssues.Judged judged = judge(false, ask("PL1", "Backlog"));

        assertThat(judged.items())
                .as("the one move")
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.outcome())
                                    .as("outcome")
                                    .isEqualTo(MoveIssues.Outcome.UNCHANGED);
                            assertThat(item.state())
                                    .as("the state it stands in")
                                    .isEqualTo("Backlog");
                        });
        assertThat(judged.saves()).as("nothing to save").isFalse();
        assertThat(judged.preview()).as("nothing to check the targets of").isNull();
    }

    @Test
    void aMoveOverRunningWorkIsRefusedAsWorkRunningAndTheOthersAreSaved() {
        // Harm: work stopped under a move that no person confirmed.
        final MovePreview.Running running =
                (issues, stories) ->
                        issues.contains("PL1")
                                ? List.of(
                                        new MovePreview.Owned(
                                                "ST1", "run-1", List.of("task-1"), List.of()))
                                : List.of();

        final MoveIssues.Judged judged =
                MoveIssues.judge(
                        board,
                        List.of(ask("PL1", "Review"), ask("PL2", "Finished")),
                        false,
                        running,
                        NO_RUNS);

        assertThat(judged.items().get(0).refusal())
                .as("the move over running work")
                .isInstanceOfSatisfying(
                        WorkRefusal.class,
                        refusal -> {
                            assertThat(refusal.name()).as("name").isEqualTo("WorkRunning");
                            assertThat(refusal.parameters())
                                    .as("the story and its tasks")
                                    .containsEntry("story", "ST1")
                                    .containsEntry("tasks", List.of("task-1"));
                        });
        assertThat(judged.planned().issues())
                .as("the other move is saved")
                .containsOnly(Map.entry("PL2", "Finished"));
    }

    @Test
    void anIssueNamedTwiceIsRefusedEachTimeAndTheOthersAreSaved() {
        // Harm: whichever of two moves of one issue came last winning unseen.
        final MoveIssues.Judged judged =
                judge(false, ask("PL1", "Review"), ask("PL1", "Finished"), ask("PL2", "Finished"));

        assertThat(judged.items())
                .as("what became of each move")
                .extracting(MoveIssuesTest::refusalName, MoveIssues.Item::outcome)
                .containsExactly(
                        tuple("MoveNotAllowed", MoveIssues.Outcome.REFUSED),
                        tuple("MoveNotAllowed", MoveIssues.Outcome.REFUSED),
                        tuple("", MoveIssues.Outcome.SAVED));
        assertThat(judged.planned().issues())
                .as("only the issue named once is written")
                .containsOnly(Map.entry("PL2", "Finished"));
    }

    @Test
    void aParentAndItsSubIssueClosedTogetherAreSavedWhereTheParentAloneIsRefused() {
        // Harm: a move the window could make as one batch refused because its parts were judged
        // one by one.
        final MoveIssues.Judged together =
                judge(false, ask("PL6", "Finished"), ask("PL6.1", "Finished"));
        assertThat(together.planned().issues())
                .as("both closed in one save")
                .containsOnly(Map.entry("PL6", "Finished"), Map.entry("PL6.1", "Finished"));

        final MoveIssues.Judged alone =
                judge(false, ask("PL6", "Finished"), ask("PL2", "Finished"));
        assertThat(alone.items())
                .as("the parent closed over its open sub-issue, and the other move")
                .extracting(MoveIssuesTest::refusalName, MoveIssues.Item::outcome)
                .containsExactly(
                        tuple("MoveNotAllowed", MoveIssues.Outcome.REFUSED),
                        tuple("", MoveIssues.Outcome.SAVED));
        assertThat(alone.planned().issues())
                .as("the other move is saved")
                .containsOnly(Map.entry("PL2", "Finished"));
    }

    @Test
    void aStoreThatKeepsNoReasonRefusesOnlyTheMoveThatCarriesOne() {
        // Harm: a sentence dropped unsaid, or the whole batch refused over one sentence.
        final MoveIssues.Judged judged =
                judge(
                        false,
                        new MoveIssues.Ask("PL1", "Finished", "Done."),
                        ask("PL2", "Finished"));

        assertThat(judged.items())
                .as("what became of each move")
                .extracting(MoveIssuesTest::refusalName, MoveIssues.Item::outcome)
                .containsExactly(
                        tuple("MoveNotAllowed", MoveIssues.Outcome.REFUSED),
                        tuple("", MoveIssues.Outcome.SAVED));
    }

    @Test
    void anIssueTheBoardLacksIsRefusedAsNoSuchIssueAndTheOthersAreSaved() {
        // Harm: a missing issue turning a whole batch into an error nothing is saved under.
        final MoveIssues.Judged judged = judge(false, ask("PL9", "Review"), ask("PL2", "Finished"));

        assertThat(judged.items())
                .as("what became of each move")
                .extracting(MoveIssuesTest::refusalName, MoveIssues.Item::outcome)
                .containsExactly(
                        tuple("NoSuchIssue", MoveIssues.Outcome.REFUSED),
                        tuple("", MoveIssues.Outcome.SAVED));
    }

    @Test
    void aRefusalNamingNoMoveOfTheBatchRefusesEveryMoveAndSavesNothing() {
        // Harm: a board whose work rules cannot be read taking moves into a working state.
        final Board faulty =
                board(
                        new Work(Map.of(), List.of("not YAML")),
                        issue("PL1", "Backlog", null),
                        issue("PL2", "Backlog", null));

        final MoveIssues.Judged judged =
                MoveIssues.judge(
                        faulty,
                        List.of(ask("PL1", "Review"), ask("PL2", "Review")),
                        false,
                        NOTHING_RUNS,
                        NO_RUNS);

        assertThat(judged.items())
                .as("each move, refused whole")
                .extracting(MoveIssuesTest::refusalName, MoveIssues.Item::outcome)
                .containsExactly(
                        tuple("MoveNotAllowed", MoveIssues.Outcome.REFUSED),
                        tuple("MoveNotAllowed", MoveIssues.Outcome.REFUSED));
        assertThat(judged.saves()).as("nothing to save").isFalse();
    }
}
