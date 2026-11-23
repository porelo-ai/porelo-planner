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
    void anIssueStandingClosedAlreadyKeepsAReasonAndABadIdIsRefused() {
        // Harm: a reason whose comment failed after the close is refused on the retry, or a
        // comment goes to an item no id names.
        final Writes retry =
                new Writes(Map.of(), Map.of(), Map.of(), Map.of(), Map.of("PL1", WHY), Map.of());
        assertThat(retry.issueReasons()).containsOnlyKeys("PL1");
        assertThatThrownBy(
                        () ->
                                new Writes(
                                        Map.of(),
                                        Map.of(),
                                        Map.of(),
                                        Map.of(),
                                        Map.of(),
                                        Map.of("not a story", WHY)))
                .as("a story's reason names a story")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReasonChecksItsTextAgainWhereItIsKept() {
        // Harm: a caller that skipped the move's check writes a broken or misleading comment.
        for (final String bad :
                List.of(
                        "",
                        "  ",
                        " padded",
                        "one\ntwo",
                        "bell\u0007",
                        "soft\u00ADhyphen",
                        "a".repeat(Writes.Reason.MOST + 1))) {
            assertThatThrownBy(() -> new Writes.Reason("Finished", bad))
                    .as("refused: " + bad.codePoints().boxed().toList())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Writes.Reason(" ", "Built."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Writes.Reason("Finished", "a".repeat(Writes.Reason.MOST)).text())
                .hasSize(Writes.Reason.MOST);
    }

    @Test
    void writesThatKeepOnlyAReasonAreSomethingToSave() {
        // Harm: a reason whose comment failed after the close is dropped by the early return.
        final Writes only =
                new Writes(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of("ST1", WHY));
        assertThat(only.none()).isFalse();
        assertThat(only.reasons()).isTrue();
        assertThat(new Writes(Map.of(), Map.of()).none()).isTrue();
    }
}
