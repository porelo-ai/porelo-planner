package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WorkRulesTest {

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of(
                            "Backlog",
                            "Implementing",
                            "Reading",
                            "Reworking",
                            "Review",
                            "Rework",
                            "Finished"),
                    List.of(),
                    List.of("Finished"));

    private static WorkRule rule(
            final List<String> from,
            final String done,
            final @Nullable String then,
            final @Nullable String blocked) {
        return new WorkRule(
                from,
                done,
                then,
                blocked,
                WorkRule.Per.REPOSITORY,
                List.of("UNATTENDED"),
                "Build {issues} of {story}.",
                Map.of("claude-code", "claude-opus-5-5"));
    }

    private static Map<String, WorkRule> sound() {
        final Map<String, WorkRule> rules = new LinkedHashMap<>();
        rules.put("Implementing", rule(List.of("Backlog", "Rework"), "Review", "Reading", null));
        rules.put("Reading", rule(List.of("Implementing"), "Review", null, "Reworking"));
        rules.put("Reworking", rule(List.of("Reading"), "Review", "Reading", null));
        return rules;
    }

    @Test
    void soundRulesAreKeptInTheirOrderWithoutAFault() {
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(sound(), List.of()));
        assertThat(checked.faults()).as("no fault in sound rules").isEmpty();
        assertThat(checked.rules().keySet())
                .as("every rule kept, in the file's order")
                .containsExactly("Implementing", "Reading", "Reworking");
    }

    @Test
    void aDoneThatIsAWorkingStateIsNamedAndItsRuleDropped() {
        // A done state that is itself work would end the work in work nobody started.
        final Map<String, WorkRule> rules = sound();
        rules.put("Reworking", rule(List.of("Reading"), "Reading", null, null));
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.faults())
                .as("the fault named first")
                .startsWith("stories/work.yml: Reworking: done 'Reading' is a working state");
        assertThat(checked.rules())
                .as("the rule dropped, and with it those leading into it")
                .isEmpty();
    }

    @Test
    void aWorkingStateTheWorkflowLacksIsNamed() {
        // Work into a state the board does not have would move an issue off the board.
        final Map<String, WorkRule> rules = sound();
        rules.put("Testing", rule(List.of("Review"), "Finished", null, null));
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.faults())
                .as("the fault named")
                .containsExactly("stories/work.yml: Testing: no state of the workflow");
        assertThat(checked.rules()).as("the rule dropped").doesNotContainKey("Testing");
    }

    @Test
    void aStateTheWorkflowLacksInFromDoneThenOrBlockedIsNamed() {
        final Map<String, WorkRule> rules = new LinkedHashMap<>();
        rules.put("Implementing", rule(List.of("Ready"), "Closed", "Proving", "Fixing"));
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.faults())
                .as("each unknown state named")
                .containsExactly(
                        "stories/work.yml: Implementing: from 'Ready' is no state of the workflow",
                        "stories/work.yml: Implementing: done 'Closed' is no state of the workflow",
                        "stories/work.yml: Implementing: then 'Proving' is no state of the workflow",
                        "stories/work.yml: Implementing: blocked 'Fixing' is no state of the"
                                + " workflow");
        assertThat(checked.rules()).as("the rule dropped").isEmpty();
    }

    @Test
    void aWorkingStateWithoutARuleIsNamed() {
        // Success or findings would lead into work nobody knows how to start.
        final Map<String, WorkRule> rules = sound();
        rules.remove("Reworking");
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.faults())
                .as("the fault named, then the drop it causes")
                .containsExactly(
                        "stories/work.yml: Reading: blocked 'Reworking' is a working state without"
                                + " a rule",
                        "stories/work.yml: Implementing: then 'Reading' has no rule work can start"
                                + " by");
        assertThat(checked.rules())
                .as("the rule that leads nowhere dropped, and the one leading into it")
                .isEmpty();
    }

    @Test
    void aRuleOnAClosedStateIsNamedAndDropped() {
        // Work in a closed state would show a finished story as one still being worked on.
        final Map<String, WorkRule> rules = sound();
        rules.put("Finished", rule(List.of("Review"), "Review", null, null));
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.faults())
                .as("the fault named")
                .containsExactly("stories/work.yml: Finished: a closed state");
        assertThat(checked.rules()).as("the rule dropped").doesNotContainKey("Finished");
    }

    // Prevents a stage whose saved result moves its issues to done from also offering readers or
    // a blocked state, whose tasks would push or start without the issues' texts.
    @Test
    void aRuleThatHandsBackAResultAndLeadsOnIsNamedAndDropped() {
        final Map<String, WorkRule> rules = sound();
        rules.put("Implementing", resulting(rules.get("Implementing")));
        rules.put("Reading", resulting(rules.get("Reading")));
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.faults())
                .as("each fault named")
                .contains(
                        "stories/work.yml: Implementing: a rule with a result names no then and no"
                                + " blocked",
                        "stories/work.yml: Reading: a rule with a result names no then and no"
                                + " blocked");
        assertThat(checked.rules())
                .as("both rules dropped")
                .doesNotContainKeys("Implementing", "Reading");
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> readinessFrom() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of("Backlog", "Finished"),
                        "stories/work.yml: Rework: a readiness check names exactly one from state"),
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of("Finished"),
                        "stories/work.yml: Rework: a readiness check's from state is open and no"
                                + " working state"),
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of("Rework"),
                        "stories/work.yml: Rework: a readiness check's from state is open and no"
                                + " working state"),
                org.junit.jupiter.params.provider.Arguments.of(
                        List.of("Implementing"),
                        "stories/work.yml: Rework: from 'Implementing' is a working state"));
    }

    // Prevents an issue found not ready sent back to a state it never came from, to a closed one,
    // or to one where an agent would start on it again at once.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("readinessFrom")
    void aReadinessCheckWithoutOneOpenIdleFromStateIsNamedAndDropped(
            final List<String> from, final String fault) {
        final Map<String, WorkRule> rules = sound();
        rules.put("Rework", readiness(from));
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.faults()).as("the fault named").contains(fault);
        assertThat(checked.rules()).as("the check dropped").doesNotContainKey("Rework");
    }

    // Prevents a sound readiness check refused, so no issue reaches the backlog checked.
    @Test
    void aReadinessCheckFromOneOpenIdleStateIsKept() {
        final Map<String, WorkRule> rules = sound();
        rules.put("Rework", readiness(List.of("Backlog")));
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.faults()).as("no fault").isEmpty();
        assertThat(checked.rules()).as("the check kept").containsKey("Rework");
    }

    /** A readiness check that sends an issue not ready back to its one from state. */
    private static WorkRule readiness(final List<String> from) {
        return new WorkRule(
                from,
                "Review",
                null,
                null,
                WorkRule.Per.REPOSITORY,
                List.of("UNATTENDED"),
                "Check {issues} of {story}.",
                Map.of("claude-code", "claude-opus-5-5"),
                Map.of(),
                WorkRule.Result.READINESS);
    }

    /** The rule handing back an analysis instead of pushing. */
    private static WorkRule resulting(final WorkRule rule) {
        return new WorkRule(
                rule.from(),
                rule.done(),
                rule.then(),
                rule.blocked(),
                rule.per(),
                rule.modes(),
                rule.order(),
                rule.models(),
                rule.risks(),
                WorkRule.Result.ANALYSIS);
    }

    @Test
    void aRuleLeadingIntoADroppedRuleIsDroppedToo() {
        // Work would end in a state the board shows as waiting, which no one starts.
        final Map<String, WorkRule> rules = sound();
        rules.put("Reworking", rule(List.of("Reading"), "Review", "Testing", null));
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.rules())
                .as(
                        "Reworking dropped for its unknown state, Reading for leading into it,"
                                + " Implementing for leading into Reading")
                .isEmpty();
        assertThat(checked.faults())
                .as("each drop named")
                .contains(
                        "stories/work.yml: Reading: blocked 'Reworking' has no rule work can"
                                + " start by",
                        "stories/work.yml: Implementing: then 'Reading' has no rule work can"
                                + " start by");
    }

    @Test
    void aDoneInAClosedStateWithADroppedRuleIsNoWorkingState() {
        // The closed state's rule is dropped, so ending work there is sound.
        final Map<String, WorkRule> rules = sound();
        rules.put("Finished", rule(List.of("Review"), "Review", null, null));
        rules.put("Implementing", rule(List.of("Backlog"), "Finished", "Reading", null));
        final WorkRules.Checked checked = WorkRules.check(WORKFLOW, new Work(rules, List.of()));
        assertThat(checked.rules().keySet())
                .as("Implementing kept")
                .containsExactly("Implementing", "Reading", "Reworking");
    }

    @Test
    void whatTheStoreCouldNotReadComesFirst() {
        final WorkRules.Checked checked =
                WorkRules.check(
                        WORKFLOW, new Work(Map.of(), List.of("stories/work.yml is missing")));
        assertThat(checked.faults())
                .as("the store's fault kept")
                .containsExactly("stories/work.yml is missing");
    }
}
