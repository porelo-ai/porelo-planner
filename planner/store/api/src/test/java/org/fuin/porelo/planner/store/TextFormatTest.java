package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The name a store gives the format of its texts, as the window's contract carries it. */
class TextFormatTest {

    @Test
    void aStoreThatNamesNoFormatGivesPlainText() {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Board refresh() {
                        throw new UnsupportedOperationException();
                    }
                };
        assertThat(store.textFormat())
                .as("a store that names no format is shown as today, plain")
                .isEqualTo(TextFormat.PLAIN);
    }

    @Test
    void anAdaptersOwnFormatPassesInTheContractsShape() {
        assertThat(new TextFormat("JIRA_WIKI").name())
                .as("an open name in the contract's shape")
                .isEqualTo("JIRA_WIKI");
    }

    @Test
    void aNameOutsideTheContractsShapeIsRefusedWithoutBeingEchoed() {
        // A name the contract does not allow would break the window, and a line break in it
        // would forge a log line.
        for (final String name :
                new String[] {
                    "", "markdown", "A".repeat(33), "PLAIN\n2026 INFO forged", "MD\u202e"
                }) {
            assertThatThrownBy(() -> new TextFormat(name))
                    .as("refused: %s", name.length())
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(
                            refused ->
                                    assertThat(refused.getMessage())
                                            .as("the refused name is not echoed")
                                            .doesNotContain("\n", "\u202e", "forged"));
        }
    }
}
