package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.planner.app.answers.ChangedItem;
import org.fuin.porelo.planner.app.answers.ProblemParameters;
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

    @Test
    void theNativeImageKeepsTheParametersOfTextAndBoardChangeRefusals() throws Exception {
        // Harm: a refusal loses the part to repair or new item's key in the native executable.
        final RuntimeHints hints = new RuntimeHints();
        new ReflectiveRuntimeHintsRegistrar().registerRuntimeHints(hints, Problems.class);

        assertThat(RuntimeHintsPredicates.reflection().onType(ProblemParameters.class).test(hints))
                .as("typed problem parameters kept for Jackson")
                .isTrue();
        for (final String field : java.util.List.of("part", "fault", "found", "key")) {
            assertThat(
                            RuntimeHintsPredicates.reflection()
                                    .onMethodInvocation(
                                            ProblemParameters.class.getDeclaredMethod(field))
                                    .test(hints))
                    .as("the %s parameter can be written", field)
                    .isTrue();
        }
        assertThat(
                        RuntimeHintsPredicates.reflection()
                                .onType(
                                        ProblemParameters.class
                                                .getDeclaredMethod("fault")
                                                .getReturnType())
                                .test(hints))
                .as("the fault's enum kept for Jackson")
                .isTrue();
    }
}
