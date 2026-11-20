package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** A Markdown project's carry-over to GitHub Issues as planned, before anything is written. */
class CarryOverPlanTest {

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Backlog", "Implementing", "Finished", "Rejected"),
                    List.of(),
                    List.of("Finished", "Rejected"));

    private static final String OWN = "porelo-project";

    private static final String PLANNER = "porelo-planner";

    private static Issue issue(
            final String id, final String state, final List<String> blockedBy, final String text) {
        final int dot = id.indexOf('.');
        return new Issue(
                id,
                PLANNER,
                "Title of " + id,
                state,
                "S",
                blockedBy,
                dot < 0 ? null : id.substring(0, dot),
                null,
                text);
    }

    private static Story story(final String id, final String state, final List<String> issues) {
        return new Story(id, "Title of " + id, state, true, "High", "M", issues, "", null);
    }

    private static final Map<String, String> WEBS =
            Map.of(
                    OWN, "https://github.com/porelo-ai/porelo-project",
                    PLANNER, "https://github.com/porelo-ai/porelo-planner");

    private static List<Issue> issues() {
        return List.of(
                issue("PL9", "Finished", List.of(), ""),
                issue("PL10", "Backlog", List.of("PL9", "PL11"), "# PL10"),
                issue("PL11", "Implementing", List.of(), "# PL11"),
                issue("PL11.1", "Backlog", List.of(), "# PL11.1"),
                issue("PL12", "Rejected", List.of(), "# PL12"));
    }

    private static Board board(final List<String> faults) {
        return board(issues(), faults);
    }

    private static Board board(final List<Issue> issues, final List<String> faults) {
        return new Board(
                new Revision("one"),
                WORKFLOW,
                List.of(
                        story("ST1", "Backlog", List.of("PL10", "PL11.1", "PL9")),
                        story("ST2", "Backlog", List.of("PL10")),
                        story("ST4", "Finished", List.of("PL9"))),
                issues,
                new Work(Map.of(), List.of()),
                List.of(OWN, PLANNER),
                faults);
    }

    private static StageEntry stage(
            final int n, final String kind, final String actor, final double minutes) {
        return new StageEntry(
                "markdown:PL11:" + kind + ":" + n,
                kind,
                "done",
                actor,
                "opus",
                "high",
                minutes,
                1,
                Map.of("claude_input_tokens", 100L, "claude_cache_read_tokens", 5000L));
    }

    private static CarryOverPlan plan(final Board board, final String below) {
        return plan(
                board,
                below,
                List.of(
                        stage(1, "implement", "agent", 10),
                        stage(1, "wait", "agent", 30),
                        stage(1, "review", "agent", 5)),
                Map.of("ST1", "**What a user gains.** Gain.", "ST2", ""));
    }

    private static CarryOverPlan plan(
            final Board board,
            final String below,
            final List<StageEntry> stages,
            final Map<String, String> texts) {
        final Map<String, CarryOverPlan.IssueSource> sources = new LinkedHashMap<>();
        for (final Issue issue : CarryOverPlan.carried(board)) {
            sources.put(
                    issue.id(),
                    new CarryOverPlan.IssueSource(
                            issue,
                            "issues/" + issue.id() + "-X.md",
                            "Medium",
                            3,
                            "It covers " + issue.id() + ".",
                            issue.id().equals("PL10")
                                    ? below
                                    : "## Plan\n\nBuilt on [PL9](README.md#pl9) and"
                                            + " [PL10](README.md#pl10).",
                            issue.id().equals("PL11") ? stages : List.of(),
                            issue.id().equals("PL11") ? 2 : 0,
                            issue.id().equals("PL11") ? 25.0 : null));
        }
        return CarryOverPlan.of(board, sources, texts, WEBS, CarryOverPlan.References.BY_ALIAS);
    }

    private static CarryOverPlan.Item item(final CarryOverPlan plan, final String id) {
        return plan.items().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void onlyOpenStoriesAndIssuesWithAFileAreCarried() {
        // Harm: a finished or rejected item carried over reopens work that is done.
        final CarryOverPlan plan = plan(board(List.of()), "Text.");
        assertThat(plan.items())
                .as("the items carried")
                .extracting(CarryOverPlan.Item::id)
                .containsExactly("ST1", "ST2", "PL10", "PL11", "PL11.1");
        assertThat(plan.whole()).as("whether the plan is whole").isTrue();
    }

    @Test
    void eachCarriedItemKeepsItsOldIdAsItsAliasAndNoOtherId() {
        final CarryOverPlan plan = plan(board(List.of()), "Text.");
        plan.items()
                .forEach(
                        item ->
                                assertThat(item.fields().get(FieldNames.ID))
                                        .as("the Id of %s", item.id())
                                        .isEqualTo(item.id()));
    }

    @Test
    void anIssueHasOneParentAndASecondStoryListsItUnderAlsoNeeds() {
        // Harm: an issue given two parents is refused by GitHub, or a story loses an issue it
        // needs.
        final CarryOverPlan plan = plan(board(List.of()), "Text.");
        assertThat(item(plan, "PL10").parent()).as("PL10's parent").isEqualTo("porelo-project#ST1");
        assertThat(item(plan, "ST1").subIssues())
                .as("ST1's sub-issues")
                .containsExactly("porelo-planner#PL10");
        assertThat(item(plan, "ST1").alsoNeeds())
                .as("ST1's issues under another parent")
                .containsExactly("porelo-planner#PL11.1");
        assertThat(item(plan, "ST1").notCarried())
                .as("ST1's issues not carried")
                .containsExactly("PL9");
        assertThat(item(plan, "ST2").alsoNeeds())
                .as("ST2's issues under another parent")
                .containsExactly("porelo-planner#PL10");
        assertThat(item(plan, "ST2").body())
                .as("ST2's body")
                .contains("## Also needs\n\n- porelo-planner#PL10\n");
        assertThat(item(plan, "PL11.1").parent())
                .as("PL11.1's parent")
                .isEqualTo("porelo-planner#PL11");
        assertThat(item(plan, "PL11").subIssues())
                .as("PL11's sub-issues")
                .containsExactly("porelo-planner#PL11.1");
    }

    @Test
    void aBlockerNotCarriedIsNamedAndNoDependency() {
        final CarryOverPlan.Item item = item(plan(board(List.of()), "Text."), "PL10");
        assertThat(item.blockedBy()).as("PL10's blockers").containsExactly("porelo-planner#PL11");
        assertThat(item.notCarried()).as("PL10's blockers not carried").containsExactly("PL9");
    }

    @Test
    void theStageRecordsBecomeCommentsOldestFirstAndTheNumberFieldsTheirSums() {
        // Harm: a sum other than its comments' is named as a fault on the first read after the run.
        final CarryOverPlan.Item item = item(plan(board(List.of()), "Text."), "PL11");
        assertThat(item.comments()).as("the stage comments").hasSize(3);
        assertThat(item.comments().get(0)).as("the first comment").contains("implement: done");
        assertThat(item.comments().get(2)).as("the last comment").contains("review: done");
        assertThat(item.fields())
                .as("the number fields")
                .containsEntry(FieldNames.MINUTES, "15")
                .containsEntry(FieldNames.VM_MINUTES, "2")
                .containsEntry(FieldNames.TOKENS, "200")
                .containsEntry(FieldNames.STAGES, "2")
                .containsEntry(FieldNames.ESTIMATE, "25");
        assertThat(item.moves()).as("the moves left out").isEqualTo(2);
    }

    @Test
    void theBodyHoldsWhatItCoversAndItsReferencesRewritten() {
        final CarryOverPlan.Item item = item(plan(board(List.of()), "Text."), "PL11");
        assertThat(item.body())
                .as("PL11's body")
                .isEqualTo("It covers PL11.\n\n## Plan\n\nBuilt on PL9 and porelo-planner#PL10.\n");
        assertThat(item.leftAsText()).as("left as text").containsExactly("PL9");
        assertThat(item.rewritten()).as("rewritten").containsExactly("PL10 -> porelo-planner#PL10");
    }

    @Test
    void theLineHoldsNoTextAPersonWroteButTheTitle() {
        // Harm: a dry run's line repeats a body, which may hold what was never meant for a log.
        final CarryOverPlan.Item item =
                item(plan(board(List.of()), "Words of the body only."), "PL10");
        final String line = item.line(true);
        assertThat(line).as("the line").contains("\"Title of PL10\"").contains("by alias");
        assertThat(line)
                .as("the line")
                .doesNotContain("Words of the body")
                .doesNotContain("It covers");
    }

    @Test
    void anItemWhoseBodyGitHubWouldRefuseLeavesThePlanNotWhole() {
        // Harm: a plan said whole whose real run then fails on an item halfway.
        final CarryOverPlan plan = plan(board(List.of()), "x".repeat(BodySections.LONGEST + 1));
        assertThat(plan.whole()).as("whether the plan is whole").isFalse();
        assertThat(item(plan, "PL10").line(true)).as("the line").contains("CANNOT BE CARRIED");
    }

    @Test
    void aBoardThatLeftSomethingOutIsNoPlan() {
        // Harm: a plan made over a board that left an issue out reads as whole and loses it.
        assertThatThrownBy(
                        () ->
                                plan(
                                        board(List.of("porelo-planner: issues/PL13-X.md: broken")),
                                        "Text."))
                .as("the plan over a board with a fault")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOpenIssueOnlyAnIndexRowNamesLeavesNoPlan() {
        // Harm: an open issue whose file is gone is left out of the carry-over and nobody is told.
        final List<Issue> issues = new java.util.ArrayList<>(issues());
        issues.add(issue("PL13", "Backlog", List.of(), ""));
        assertThatThrownBy(() -> plan(board(issues, List.of()), "Text."))
                .as("the plan over an open issue without a file")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PL13 stands in Backlog but has no file");
    }

    @Test
    void aSubIssueWhoseParentIsNotCarriedLeavesThePlanNotWhole() {
        // Harm: a sub-issue carried without its parent is left out by the first read from GitHub.
        final List<Issue> issues = new java.util.ArrayList<>(issues());
        issues.set(2, issue("PL11", "Finished", List.of(), "# PL11"));
        final CarryOverPlan plan = plan(board(issues, List.of()), "Text.");
        assertThat(plan.whole()).as("whether the plan is whole").isFalse();
        assertThat(item(plan, "PL11.1").problem())
                .as("why PL11.1 cannot be carried")
                .isEqualTo("its parent PL11 is not carried");
    }

    @Test
    void aStoryWhoseTextWasNotReadLeavesNoPlan() {
        // Harm: an open story is planned with an empty body, its text lost.
        assertThatThrownBy(() -> plan(board(List.of()), "Text.", List.of(), Map.of("ST1", "Gain.")))
                .as("the plan without ST2's text")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ST2 was not read");
    }

    @Test
    void aStoryWhoseTextHoldsTheSectionOfItsOtherIssuesCannotBeCarried() {
        // Harm: read back, the person's section is taken for the list of references and the
        // carried ones are lost.
        final CarryOverPlan plan =
                plan(
                        board(List.of()),
                        "Text.",
                        List.of(),
                        Map.of("ST1", "Gain.\n\n## Also needs\n\nWords of a person.", "ST2", ""));
        assertThat(item(plan, "ST1").problem())
                .as("why ST1 cannot be carried")
                .isEqualTo("its body already has a section Also needs");
        assertThat(plan.whole()).as("whether the plan is whole").isFalse();
    }

    @Test
    void stageCommentsWhoseSumsCannotBeReadBackLeaveThePlanNotWhole() {
        // Harm: a plan said whole whose sums are named as a fault on every read after the run.
        final Map<String, Long> most = new LinkedHashMap<>();
        for (final String kind :
                List.of(
                        "claude_input_tokens",
                        "claude_output_tokens",
                        "claude_cache_write_tokens",
                        "codex_input_tokens",
                        "codex_output_tokens",
                        "agy_input_tokens",
                        "agy_output_tokens")) {
            // The most a stage comment carries of one kind, as JSON numbers read back exactly.
            most.put(kind, 9_007_199_254_740_991L);
        }
        final List<StageEntry> stages = new java.util.ArrayList<>();
        for (int n = 1; n <= 150; n++) {
            stages.add(
                    new StageEntry(
                            "markdown:PL11:implement:" + n,
                            "implement",
                            "done",
                            "agent",
                            "opus",
                            "high",
                            1,
                            0,
                            most));
        }
        final CarryOverPlan plan =
                plan(board(List.of()), "Text.", stages, Map.of("ST1", "Gain.", "ST2", ""));
        assertThat(item(plan, "PL11").problem())
                .as("why PL11 cannot be carried")
                .startsWith("its stage comments do not sum");
    }
}
