package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A change of a title and named parts of a text. */
class TextEditTest {

    @Test
    void whatAnIssueCoversOrWhatMustBeTrueIsNeverRemovedAndAPartIsOfItsOwnKind() {
        // An issue without what must be true has no goal left; a story's part written into an
        // issue's body would open a paragraph nobody reads.
        for (final TextEdit.Part kept :
                Set.of(TextEdit.Part.COVERS, TextEdit.Part.WHAT_MUST_BE_TRUE)) {
            assertThatThrownBy(
                            () -> new TextEdit(TextEdit.Kind.ISSUE, null, Map.of(), Set.of(kept)))
                    .as("the removal of %s", kept)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () ->
                                new TextEdit(
                                        TextEdit.Kind.ISSUE,
                                        null,
                                        Map.of(TextEdit.Part.GAIN, "More."),
                                        Set.of()))
                .as("a story's part in an issue's edit")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new TextEdit(
                                        TextEdit.Kind.ISSUE,
                                        null,
                                        Map.of(TextEdit.Part.PLAN, "A plan."),
                                        Set.of(TextEdit.Part.PLAN)))
                .as("a part written and removed at once")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
