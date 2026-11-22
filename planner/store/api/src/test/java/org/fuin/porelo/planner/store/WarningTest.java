package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WarningTest {

    @Test
    void aPlaceIsNamedWithoutControlBidiOrZeroWidthCharactersAndCappedWithAnEllipsis() {
        // Harm: a file name with a line break or a bidi override breaks or reorders the sentence
        // the person reads, and thirty long names make a warning no tooltip holds.
        assertThat(Warning.place("issues/note-\u202Edm.txt.md", "store"))
                .as("a bidi override left out")
                .isEqualTo("issues/note-dm.txt.md of store");
        assertThat(Warning.place("issues/note-a\nb\u0000\u200B\u2028.md", "store"))
                .as("a line break, a control, a zero-width and a line separator left out")
                .isEqualTo("issues/note-ab.md of store");
        final String named = Warning.place("issues/" + "x".repeat(300) + ".md", "store");
        assertThat(named.substring(0, named.length() - " of store".length()))
                .as("capped at the most a place names, the ellipsis counted")
                .hasSize(Warning.MOST_PATH)
                .endsWith("\u2026");
        assertThat(
                        Warning.idTwice(List.of(Warning.place("issues/note-\u202Ex.md", "store")))
                                .text())
                .as("so the warning's words hold none of them")
                .doesNotContain("\u202E");
    }
}
