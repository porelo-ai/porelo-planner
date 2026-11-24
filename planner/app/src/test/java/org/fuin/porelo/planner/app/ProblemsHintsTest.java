package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.planner.app.answers.ChangedItem;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class ProblemsHintsTest {

    @Test
    void theNativeImageKeepsTheItemsAnItemChangedRefusalNames() {
        // Harm: the native executable cannot write the items an ItemChanged refusal names, which
        // no handler's return type names, so every such refusal reaches the window as a 500.
        final RuntimeHints hints = new RuntimeHints();
        new ReflectiveRuntimeHintsRegistrar().registerRuntimeHints(hints, Problems.class);

        assertThat(RuntimeHintsPredicates.reflection().onType(ChangedItem.class).test(hints))
                .as("ChangedItem kept for Jackson")
                .isTrue();
    }
}
