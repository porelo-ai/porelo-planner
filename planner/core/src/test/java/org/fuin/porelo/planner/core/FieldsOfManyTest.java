package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Fields of many issues and priorities of stories set in one call, each item judged alone. */
class FieldsOfManyTest {

    private static final MovePreview.Running NOTHING_RUNS = (issues, stories) -> List.of();

    private static final Fields NONE =
            new Fields(EnumSet.noneOf(Fields.Field.class), null, null, null, null);

    private static Board board(final List<Story> stories, final Issue... issues) {
        return new Board(
                new Revision("r1"),
                new Workflow(
                        List.of("Idea", "Backlog", "Building", "Review", "Finished"),
                        List.of(),
                        List.of("Finished")),
                stories,
                List.of(issues),
                new Work(Map.of(), List.of()));
    }

    private static Issue issue(final String id, final String priority) {
        return new Issue(
                        id, "porelo-planner", "An issue", "Backlog", "S", List.of(), null, null, "")
                .withPriority(priority);
    }

    private static Story story(final String id, final String priority, final String... issues) {
        return new Story(id, "A story", "Backlog", priority, null, List.of(issues), "", null);
    }

    private static FieldsOfMany.IssueAsk size(final String issue, final String size) {
        return new FieldsOfMany.IssueAsk(
                issue, new Fields(Set.of(Fields.Field.SIZE), size, null, null, null), null);
    }

    private static FieldsOfMany.IssueAsk priority(final String issue, final String priority) {
        return new FieldsOfMany.IssueAsk(
                issue, new Fields(Set.of(Fields.Field.PRIORITY), null, null, priority, null), null);
    }

    private static Fields medium() {
        return new Fields(Set.of(Fields.Field.PRIORITY), null, null, "Medium", null);
    }

    private static FieldsOfMany.IssueAsk joining(final String issue, final String story) {
        return new FieldsOfMany.IssueAsk(issue, NONE, story);
    }

    private static FieldsOfMany.Judged judge(
            final Board board,
            final List<FieldsOfMany.IssueAsk> issues,
            final List<FieldsOfMany.StoryAsk> stories) {
        return FieldsOfMany.judge(board, issues, stories, NOTHING_RUNS);
    }

    /** The name of the item's refusal, or empty for an item not refused. */
    private static String refusalName(final FieldsOfMany.Item item) {
        final @Nullable RuntimeException refusal = item.refusal();
        if (refusal instanceof Moves.Refused refused) {
            return refused.name();
        }
        return refusal instanceof WorkRefusal work ? work.name() : "";
    }

    @Test
    void tenSizesAreSetInOneSaveAndEachIsAnsweredSaved() {
        // Harm: a call that saves some of the sizes, or answers them in another order.
        final List<Issue> issues = new ArrayList<>();
        final List<FieldsOfMany.IssueAsk> asks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            issues.add(issue("PL" + i, "Medium"));
            asks.add(size("PL" + i, "M"));
        }

        final FieldsOfMany.Judged judged =
                judge(board(List.of(), issues.toArray(new Issue[0])), asks, List.of());

        assertThat(judged.items())
                .as("every issue answered once, in the order sent, saved")
                .extracting(FieldsOfMany.Item::item, FieldsOfMany.Item::outcome)
                .containsExactlyElementsOf(
                        asks.stream()
                                .map(ask -> tuple(ask.issue(), FieldsOfMany.Outcome.SAVED))
                                .toList());
        assertThat(judged.steps())
                .as("ten writes of the size, one per issue")
                .hasSize(10)
                .allSatisfy(
                        step ->
                                assertThat(((FieldsOfMany.SetIssue) step).fields().size())
                                        .as("the size written")
                                        .isEqualTo("M"));
    }

    @Test
    void anIssueTheRulesRefuseIsNamedWithItsRefusalWhileTheOthersAreSaved() {
        // Harm: one refused issue holding back the others, or dropped without its refusal.
        final FieldsOfMany.Judged judged =
                judge(
                        board(List.of(), issue("PL1", "Medium"), issue("PL2", "Medium")),
                        List.of(size("PL1", "M"), size("PL9", "M"), size("PL2", "L")),
                        List.of());

        assertThat(judged.items())
                .as("what became of each issue")
                .extracting(FieldsOfMany.Item::item, FieldsOfMany.Item::outcome)
                .containsExactly(
                        tuple("PL1", FieldsOfMany.Outcome.SAVED),
                        tuple("PL9", FieldsOfMany.Outcome.REFUSED),
                        tuple("PL2", FieldsOfMany.Outcome.SAVED));
        assertThat(refusalName(judged.items().get(1)))
                .as("the refusal the call alone would give for an issue the board lacks")
                .isEqualTo("NoSuchIssue");
        assertThat(judged.steps())
                .as("only the two allowed issues are written")
                .extracting(step -> ((FieldsOfMany.SetIssue) step).issue())
                .containsExactly("PL1", "PL2");
    }

    @Test
    void aValueAnIssueHoldsAlreadyIsUnchangedAndNothingIsWritten() {
        // Harm: a commit for a change that changes nothing.
        final FieldsOfMany.Judged judged =
                judge(
                        board(
                                List.of(story("ST1", "Medium", "PL1", "PL2", "PL3")),
                                issue("PL1", "Medium"),
                                issue("PL2", "Medium"),
                                issue("PL3", "Medium")),
                        List.of(size("PL1", "S"), priority("PL2", "Medium"), joining("PL3", "ST1")),
                        List.of(new FieldsOfMany.StoryAsk("ST1", "Medium")));

        assertThat(judged.items())
                .as("each item answered unchanged")
                .extracting(FieldsOfMany.Item::outcome)
                .containsOnly(FieldsOfMany.Outcome.UNCHANGED);
        assertThat(judged.items().getLast().raised())
                .as("a story left as it is raises nothing")
                .isEmpty();
        assertThat(judged.steps()).as("nothing is written").isEmpty();
    }

    @Test
    void aStoryGivenALowerPriorityRaisesTheIssuesTheRuleNeedsAndNamesThem() {
        // Harm: a story saved over issues that break the stories' priority rule, or issues
        // changed that the rule does not need changed.
        final FieldsOfMany.Judged judged =
                judge(
                        board(
                                List.of(story("ST1", "Highest", "PL1", "PL2", "PL3", "PL4")),
                                issue("PL1", "Highest"),
                                issue("PL2", "High"),
                                issue("PL3", "Medium"),
                                issue("PL4", "Low")),
                        List.of(),
                        List.of(new FieldsOfMany.StoryAsk("ST1", "Medium")));

        assertThat(judged.items())
                .as("the story is saved with the issues raised with it")
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.outcome())
                                    .as("outcome")
                                    .isEqualTo(FieldsOfMany.Outcome.SAVED);
                            assertThat(item.raised())
                                    .as("only the issues above the story's new priority")
                                    .containsExactly("PL1", "PL2");
                        });
        assertThat(judged.steps())
                .as("the story's priority, then each raised issue's")
                .containsExactly(
                        new FieldsOfMany.Priority("ST1", "Medium"),
                        new FieldsOfMany.SetIssue("PL1", medium()),
                        new FieldsOfMany.SetIssue("PL2", medium()));
    }

    @Test
    void aPriorityTheCallSendsForAnIssueIsKeptAndOneThatBreachesIsRefusedForThatIssue() {
        // Harm: the call overwriting a priority it was told to set, or saving a breach it names.
        final FieldsOfMany.Judged judged =
                judge(
                        board(
                                List.of(story("ST1", "Highest", "PL1", "PL2", "PL3")),
                                issue("PL1", "Highest"),
                                issue("PL2", "High"),
                                issue("PL3", "Highest")),
                        List.of(priority("PL1", "Highest"), priority("PL2", "Low")),
                        List.of(new FieldsOfMany.StoryAsk("ST1", "Medium")));

        assertThat(judged.items())
                .as("the breaching issue is refused, the other saved, the story saved")
                .extracting(FieldsOfMany.Item::item, FieldsOfMany.Item::outcome)
                .containsExactly(
                        tuple("PL1", FieldsOfMany.Outcome.REFUSED),
                        tuple("PL2", FieldsOfMany.Outcome.SAVED),
                        tuple("ST1", FieldsOfMany.Outcome.SAVED));
        assertThat(refusalName(judged.items().getFirst()))
                .as("the refusal of the breach")
                .isEqualTo("EditNotAllowed");
        assertThat(judged.items().getLast().raised())
                .as("the issue the call names is never raised; the one it does not name is")
                .containsExactly("PL3");
        assertThat(judged.steps())
                .as(
                        "the story, the raised issue and the issue the call set, nothing for the"
                                + " refused one")
                .extracting(FieldsOfMany.Step::targets)
                .containsExactly(Set.of("ST1"), Set.of("PL2"), Set.of("PL3"));
    }

    @Test
    void anIssueGivenAStoryJoinsItAndLeavesTheOneThatHeldIt() {
        // Harm: an issue in two stories, or a story list written without the issue it was given.
        final FieldsOfMany.Judged judged =
                judge(
                        board(
                                List.of(story("ST1", "Low", "PL1"), story("ST2", "Low", "PL2")),
                                issue("PL1", "Low"),
                                issue("PL2", "Low"),
                                issue("PL3", "Low")),
                        List.of(joining("PL2", "ST1"), joining("PL3", "ST1")),
                        List.of());

        assertThat(judged.items())
                .as("both joins saved")
                .extracting(FieldsOfMany.Item::outcome)
                .containsOnly(FieldsOfMany.Outcome.SAVED);
        assertThat(judged.steps())
                .as("one list of the story, with both issues after its own")
                .singleElement()
                .isInstanceOfSatisfying(
                        FieldsOfMany.Members.class,
                        members -> {
                            assertThat(members.story()).as("story").isEqualTo("ST1");
                            assertThat(members.issues())
                                    .as("the whole list")
                                    .containsExactly("PL1", "PL2", "PL3");
                            assertThat(members.targets())
                                    .as("the story, the issues it gains and the story one leaves")
                                    .containsExactlyInAnyOrder("ST1", "ST2", "PL2", "PL3");
                        });
    }

    @Test
    void anIssueAnotherStoryHoldsThatATaskWorksIsRefusedAndTheOthersAreSaved() {
        // Harm: a started task's result no longer belonging to the story it works for.
        final MovePreview.Running running =
                (issues, stories) ->
                        issues.contains("PL2")
                                ? List.of(
                                        new MovePreview.Owned(
                                                "ST2", "run", List.of("t1"), List.of()))
                                : List.of();

        final FieldsOfMany.Judged judged =
                FieldsOfMany.judge(
                        board(
                                List.of(story("ST1", "Low"), story("ST2", "Low", "PL2")),
                                issue("PL2", "Low"),
                                issue("PL3", "Low")),
                        List.of(joining("PL2", "ST1"), joining("PL3", "ST1")),
                        List.of(),
                        running);

        assertThat(judged.items())
                .as("the worked issue is refused, the free one saved")
                .extracting(FieldsOfMany.Item::item, FieldsOfMany.Item::outcome)
                .containsExactly(
                        tuple("PL2", FieldsOfMany.Outcome.REFUSED),
                        tuple("PL3", FieldsOfMany.Outcome.SAVED));
        assertThat(refusalName(judged.items().getFirst()))
                .as("the refusal naming the work")
                .isEqualTo("WorkRunning");
        assertThat(judged.steps())
                .as("the list holds the free issue alone")
                .singleElement()
                .isInstanceOfSatisfying(
                        FieldsOfMany.Members.class,
                        members ->
                                assertThat(members.issues()).as("the list").containsExactly("PL3"));
    }

    @Test
    void aStoryWrittenTwiceInOneCallIsRefusedTheSecondTime() {
        // Harm: a write judged on a version an earlier write of the same call already moved.
        final FieldsOfMany.Judged judged =
                judge(
                        board(
                                List.of(story("ST1", "Low"), story("ST2", "Low", "PL2")),
                                issue("PL2", "Low")),
                        List.of(joining("PL2", "ST1")),
                        List.of(new FieldsOfMany.StoryAsk("ST1", "High")));

        assertThat(judged.items())
                .as("the join first, the story's priority refused")
                .extracting(FieldsOfMany.Item::item, FieldsOfMany.Item::outcome)
                .containsExactly(
                        tuple("PL2", FieldsOfMany.Outcome.SAVED),
                        tuple("ST1", FieldsOfMany.Outcome.REFUSED));
        assertThat(refusalName(judged.items().getLast()))
                .as("the refusal")
                .isEqualTo("EditNotAllowed");
    }

    @Test
    void anItemNamedTwiceOrNamingNothingIsRefused() {
        // Harm: two asks for one item judged on the same version, or an empty ask saved.
        final FieldsOfMany.Judged judged =
                judge(
                        board(
                                List.of(story("ST1", "Low")),
                                issue("PL1", "Low"),
                                issue("PL2", "Low")),
                        List.of(
                                size("PL1", "M"),
                                size("PL1", "L"),
                                new FieldsOfMany.IssueAsk("PL2", NONE, null)),
                        List.of(
                                new FieldsOfMany.StoryAsk("ST1", "High"),
                                new FieldsOfMany.StoryAsk("ST1", "Low")));

        assertThat(judged.items())
                .as("every item refused")
                .extracting(FieldsOfMany.Item::outcome)
                .containsOnly(FieldsOfMany.Outcome.REFUSED);
        assertThat(judged.steps()).as("nothing is written").isEmpty();
    }
}
