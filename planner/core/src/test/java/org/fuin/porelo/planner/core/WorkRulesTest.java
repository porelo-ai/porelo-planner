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
                    List.of());

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
                .as("the fault named")
                .containsExactly("stories/work.yml: Reworking: done 'Reading' is a working state");
        assertThat(checked.rules()).as("the rule dropped").doesNotContainKey("Reworking");
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
                .as("the fault named")
                .containsExactly(
                        "stories/work.yml: Reading: blocked 'Reworking' is a working state without"
                                + " a rule");
        assertThat(checked.rules().keySet())
                .as("only the rule that leads nowhere dropped")
                .containsExactly("Implementing");
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
