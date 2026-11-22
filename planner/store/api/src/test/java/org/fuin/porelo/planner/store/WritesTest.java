package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What one save writes, the reasons kept with the items it closes included. */
class WritesTest {

    private static final Writes.Reason WHY = new Writes.Reason("Finished", "Built.");

    @Test
    void anIssuesReasonGoesOnlyWithAStateTheSameSaveWrites() {
        // Harm: a comment written to an issue the save does not close.
        assertThatThrownBy(
                        () ->
                                new Writes(
                                        Map.of("PL1", "Finished"),
                                        Map.of(),
                                        List.of(),
                                        Map.of(),
                                        Map.of(),
                                        Map.of("PL2", WHY),
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PL2");
        assertThatThrownBy(
                        () ->
                                new Writes(
                                        Map.of(),
                                        Map.of(),
                                        List.of(),
                                        Map.of(),
                                        Map.of(),
                                        Map.of(),
                                        Map.of("not a story", WHY)))
                .as("a story's reason names a story")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesThatKeepOnlyAReasonAreSomethingToSave() {
        // Harm: a reason whose comment failed after the close is dropped by the early return.
        final Writes only =
                new Writes(
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of("ST1", WHY));
        assertThat(only.none()).isFalse();
        assertThat(only.reasons()).isTrue();
        assertThat(new Writes(Map.of(), Map.of(), List.of()).none()).isTrue();
    }
}
