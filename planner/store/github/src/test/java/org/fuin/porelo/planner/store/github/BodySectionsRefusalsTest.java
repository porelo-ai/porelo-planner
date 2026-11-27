package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.TextEdit;
import org.junit.jupiter.api.Test;

/** Refused edits retain enough information for a caller to repair the affected part. */
class BodySectionsRefusalsTest {

    @Test
    void aHeadingNamesThePartAndTheHeadingFound() {
        assertFault(
                TextEdit.Part.WHAT_MUST_BE_TRUE,
                "It works.\n## Unexpected",
                "What must be true",
                "HEADING",
                "## Unexpected");
        assertFault(
                TextEdit.Part.PLAN, "A step.\n## Unexpected", "Plan", "HEADING", "## Unexpected");
    }

    @Test
    void aLeadNamesTheStoryPartAndTheLeadFound() {
        assertFault(
                TextEdit.Part.GAIN,
                "A gain.\n**The test.** Other",
                "What a user gains",
                "LEAD",
                "**The test.** Other");
    }

    @Test
    void aBlankLineNamesTheLeadingPart() {
        assertFault(TextEdit.Part.TEST, "A test.\n\nAnother.", "The test", "BLANK_LINE", "");
    }

    @Test
    void aBlankPartPreservesItsOffendingWhitespace() {
        assertFault(TextEdit.Part.PLAN, "   ", "Plan", "BLANK_LINE", "   ");
    }

    @Test
    void aMalformedLineEndingNamesTheLineThatBreaksOddly() {
        assertFault(TextEdit.Part.PLAN, "intro\nleft\rright", "Plan", "BLANK_LINE", "left");
    }

    @Test
    void anOpenCodeBlockNamesItsOpeningLine() {
        assertFault(TextEdit.Part.PLAN, "A step.\n```java\ncode", "Plan", "CODE_BLOCK", "```java");
    }

    @Test
    void anOpenHtmlCommentNamesItsOpeningLine() {
        assertFault(
                TextEdit.Part.PLAN,
                "A step.\n<!-- unfinished",
                "Plan",
                "HTML_COMMENT",
                "<!-- unfinished");
    }

    @Test
    void anOpenHtmlBlockNamesItsOpeningLine() {
        assertFault(
                TextEdit.Part.PLAN,
                "A step.\n<details>\nunfinished",
                "Plan",
                "OPEN_BLOCK",
                "<details>");
    }

    @Test
    void aBodyGrownTooLongNamesTheWrittenPartWithoutRepeatingItsText() {
        assertFault(TextEdit.Part.PLAN, "x".repeat(BodySections.LONGEST), "Plan", "LENGTH", "");
    }

    @Test
    void ambiguousHeldSectionsNameNoOnePartAndRepeatNoLine() {
        final TextEdit.Refused refused =
                refusal("## Plan\n\none\n\n## Plan\n\ntwo", TextEdit.Part.PLAN, "A step.");
        assertThat(refused.part()).as("no part can be selected in the held body").isEmpty();
        assertThat(refused.fault().name())
                .as("the held sections need repair")
                .isEqualTo("SECTIONS");
        assertThat(refused.found()).as("held text is not echoed").isEmpty();
    }

    @Test
    void theOffendingLineKeepsTheWhitespaceTheCallerWrote() {
        assertFault(
                TextEdit.Part.PLAN,
                "\n  ## Unexpected  \n",
                "Plan",
                "HEADING",
                "  ## Unexpected  ");
    }

    @Test
    void theExcerptDoesNotSplitASupplementaryCharacter() {
        final String heading = "## " + "x".repeat(196) + "😀" + "tail";
        assertFault(
                TextEdit.Part.PLAN,
                "A step.\n" + heading,
                "Plan",
                "HEADING",
                "## " + "x".repeat(196) + "😀");
    }

    @Test
    void anOffendingLineIsLimitedToItsFirstTwoHundredCharacters() {
        final String heading = "## " + "x".repeat(300);
        assertFault(
                TextEdit.Part.PLAN,
                "A step.\n" + heading,
                "Plan",
                "HEADING",
                heading.substring(0, 200));
    }

    private static void assertFault(
            final TextEdit.Part part,
            final String text,
            final String name,
            final String fault,
            final String found) {
        final TextEdit.Refused refused = refusal("Covers.\n", part, text);
        assertThat(refused.item()).as("the edited item").isEqualTo("item");
        assertThat(refused.part()).as("the part's heading").isEqualTo(name);
        assertThat(refused.fault().name()).as("the fault kind").isEqualTo(fault);
        assertThat(refused.found()).as("the offending line").isEqualTo(found);
        assertThat(refused).as("the refusal explains the affected part").hasMessageContaining(name);
        if (!found.isEmpty()) {
            assertThat(refused)
                    .as("the refusal identifies the offending line")
                    .hasMessageContaining(found);
        }
    }

    private static TextEdit.Refused refusal(
            final String held, final TextEdit.Part part, final String text) {
        // Harm: a caller cannot repair an edit when the store discards its fault and location.
        final TextEdit.Refused refused =
                catchThrowableOfType(
                        TextEdit.Refused.class,
                        () ->
                                BodySections.edited(
                                        "item",
                                        held,
                                        new TextEdit(
                                                part.of(), null, Map.of(part, text), Set.of())));
        assertThat(refused).as("the malformed part is refused").isNotNull();
        return refused;
    }
}
