package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkRuleTest {

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
                models);
    }
}
