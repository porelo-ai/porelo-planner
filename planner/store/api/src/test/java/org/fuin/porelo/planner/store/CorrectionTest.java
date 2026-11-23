package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorrectionTest {

    private static final Work NO_WORK = new Work(Map.of(), List.of());

    @Test
    void aMoveOffersNoStateTheWindowCouldNotSendBackAsAChoice() {
        // Harm: a choice offered that every preview of it refuses, or one the answer's bounds
        // break, so the window reads the whole board as malformed.
        final Workflow workflow =
                new Workflow(List.of("Backlog", "Ready.1", "Done"), List.of(), List.of("Done"));

        final Correction move = Correction.ofState(workflow, NO_WORK);

        assertThat(move).as("a Move is still offered").isNotNull();
        assertThat(move.choices())
                .as("only the states a choice can name")
                .containsExactly("Backlog");
    }

    @Test
    void noMoveIsOfferedWhenNoOpenStateCanBeSentBack() {
        // Harm: a correction offered with no choice the person could send.
        final Workflow workflow =
                new Workflow(List.of("Ready.1", "Done"), List.of(), List.of("Done"));

        assertThat(Correction.ofState(workflow, NO_WORK)).as("no correction").isNull();
    }

    @Test
    void aCorrectionRefusesAChoiceOutsideTheBoundsAndTooManyChoices() {
        // Harm: an answer the description bounds, broken by the planner itself.
        assertThat(catchThrowable(() -> new Correction(Correction.MOVE, List.of("Ready.1"))))
                .as("a choice with a dot")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new Correction(Correction.MOVE, List.of("x".repeat(65)))))
                .as("a choice past 64 characters")
                .isInstanceOf(IllegalArgumentException.class);
        final List<String> many =
                java.util.stream.IntStream.rangeClosed(0, Correction.MOST_CHOICES)
                        .mapToObj(n -> "S" + n)
                        .toList();
        assertThat(catchThrowable(() -> new Correction(Correction.MOVE, many)))
                .as("more choices than the description allows")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new Correction(Correction.MOVE, many.subList(0, Correction.MOST_CHOICES))
                                .choices())
                .as("as many as allowed stand")
                .hasSize(Correction.MOST_CHOICES);
    }
}
