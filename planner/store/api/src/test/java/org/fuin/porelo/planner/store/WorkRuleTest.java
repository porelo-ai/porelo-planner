package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkRuleTest {

    private static final Role AUTHOR =
            new Role(
                    "author",
                    Role.End.PUSHES_WORK,
                    Role.Workspace.OWN,
                    false,
                    true,
                    Role.WriteAs.AUTHOR,
                    List.of(Role.Right.PUSH, Role.Right.REPLY));

    private static final Role ANALYST =
            new Role(
                    "analyst",
                    Role.End.HANDS_BACK_RESULT,
                    Role.Workspace.READ_ONLY,
                    false,
                    true,
                    Role.WriteAs.AUTHOR,
                    List.of(Role.Right.REPORT));

    private static WorkRule rule(final WorkRule.Result result, final Role role) {
        return new WorkRule(
                List.of("Backlog"),
                "Review",
                null,
                null,
                WorkRule.Per.STORY,
                List.of("AGENT"),
                "Build",
                Map.of("agent", "model"),
                Map.of(),
                result,
                role);
    }

    @Test
    void aResultIsNamedExactlyForARoleThatHandsOneBack() {
        // Harm: a task that pushes is asked for a result file, or a result is never taken.
        assertThat(rule(WorkRule.Result.ANALYSIS, ANALYST).role())
                .as("a role that hands back a result, with the result named")
                .isEqualTo(ANALYST);
        assertThatThrownBy(() -> rule(WorkRule.Result.ANALYSIS, AUTHOR))
                .as("a result named for a role that pushes work")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("author")
                .hasMessageContaining("pushes work");
        assertThatThrownBy(() -> rule(null, ANALYST))
                .as("a role that hands back a result, with none named")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("analyst");
    }

    @Test
    void theWorkHoldsTheRolesItsRulesOfferAndRefusesOneDefinedOtherwise() {
        // Harm: a rule starts a task by other parts than the role the project's file defines.
        final WorkRule build = rule(null, AUTHOR);
        assertThat(new Work(Map.of("Building", build), List.of()).roles())
                .as("the roles the rules offer")
                .containsExactly(Map.entry("author", AUTHOR));
        assertThat(
                        new Work(
                                        Map.of("Building", build),
                                        List.of(),
                                        Map.of(),
                                        Map.of("author", AUTHOR, "analyst", ANALYST))
                                .roles())
                .as("a role no rule offers is kept")
                .containsKeys("author", "analyst");
        final Role other =
                new Role(
                        "author",
                        Role.End.PUSHES_WORK,
                        Role.Workspace.OWN,
                        false,
                        false,
                        Role.WriteAs.AUTHOR,
                        List.of(Role.Right.PUSH));
        assertThatThrownBy(
                        () ->
                                new Work(
                                        Map.of("Building", build),
                                        List.of(),
                                        Map.of(),
                                        Map.of("author", other)))
                .as("the rule's role differs from the one defined under its name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Building");
        assertThatThrownBy(() -> new Work(Map.of(), List.of(), Map.of(), Map.of("writer", AUTHOR)))
                .as("a role kept under another name than its own")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankModesAgentsAndModelsCannotAuthorizeWork() {
        // Empty launch arguments can bypass the rule's intended agent, model or execution mode.
        for (final String blank : List.of("", " ", "\n")) {
            assertThatThrownBy(() -> rule(List.of("AGENT", blank), Map.of("agent", "model")))
                    .as("every offered mode must be nonblank")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> rule(List.of("AGENT"), Map.of(blank, "model")))
                    .as("every offered agent must be nonblank")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> rule(List.of("AGENT"), Map.of("agent", blank)))
                    .as("every offered model must be nonblank")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static WorkRule rule(final List<String> modes, final Map<String, String> models) {
        return new WorkRule(
                List.of("Backlog"),
                "Review",
                null,
                null,
                WorkRule.Per.STORY,
                modes,
                "Build",
                models,
                AUTHOR);
    }
}
