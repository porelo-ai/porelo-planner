package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An item's labels: the topic labels the planner sets and the person's own it keeps. */
class TopicLabelsTest {

    @Test
    void aLabelNameHoldsOneToFiftyCharactersAndNothingThatHidesOrTurnsText() {
        assertThat(TopicLabels.wellFormed("needs-design")).isTrue();
        assertThat(TopicLabels.wellFormed("Größe 🙂")).isTrue();
        assertThat(TopicLabels.wellFormed("🙂".repeat(50))).as("fifty characters").isTrue();
        assertThat(TopicLabels.wellFormed("a".repeat(51))).as("fifty-one characters").isFalse();
        assertThat(TopicLabels.wellFormed("")).isFalse();
        // Named by their code points, so no character that hides or turns text stands in the
        // source.
        for (final int hidden :
                List.of(
                        0x0000, 0x001F, 0x007F, 0x0085, 0x009F, 0x061C, 0x200B, 0x200F, 0x202A,
                        0x202E, 0x2060, 0x2064, 0x2066, 0x206F, 0xFEFF, 0x2028, 0x2029, 0x00AD,
                        0x034F, 0x180E, 0xE0000, 0xE0001, 0xE0041, 0xE007F)) {
            assertThat(TopicLabels.wellFormed("a" + Character.toString(hidden) + "b"))
                    .as("U+%04X", hidden)
                    .isFalse();
        }
        assertThat(TopicLabels.wellFormed("a" + Character.toString(0x2065) + "b"))
                .as("U+2065, unassigned")
                .isTrue();
        assertThat(TopicLabels.wellFormed("a" + Character.toString(0xE0080) + "b"))
                .as("U+E0080, past the tag characters")
                .isTrue();
    }

    @Test
    void anItemsLabelsAreCheckedAndAWriteSetsOnlyTopicLabelsInTheirOwnOrder() {
        assertThat(TopicLabels.checked("AB1", List.of("tests", "needs-design")))
                .containsExactly("tests", "needs-design");
        assertThatThrownBy(() -> TopicLabels.checked("AB1", List.of("tests", "tests")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                TopicLabels.checked(
                                        "AB1", List.of("a" + Character.toString(0x202E) + "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TopicLabels.topic(List.of("usability", "tests")))
                .containsExactly("tests", "usability");
        assertThatThrownBy(() -> TopicLabels.topic(List.of("bug")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TopicLabels.after(List.of("usability", "needs-design"), List.of("tests")))
                .as("the person's own label is kept, the topic labels are replaced")
                .containsExactly("needs-design", "tests");
    }

    @Test
    void aTopicLabelInAnotherCaseIsTheTopicLabelAndIsNeverWrittenBesideIt() {
        // Harm: a label GitHub matches as a topic label in any case was kept beside it, so a write
        // named one label twice, or could not take it off.
        assertThat(TopicLabels.topicOf(List.of("Tests", "needs-design", "USABILITY")))
                .containsExactly("tests", "usability");
        assertThat(TopicLabels.after(List.of("Tests", "needs-design"), List.of("tests")))
                .containsExactly("needs-design", "tests");
        assertThat(TopicLabels.after(List.of("Tests", "needs-design"), List.of()))
                .containsExactly("needs-design");
        assertThat(
                        TopicLabels.same(
                                List.of("needs-design", "Tests"), List.of("tests", "Needs-Design")))
                .as("the same labels in another case and order")
                .isTrue();
        assertThat(TopicLabels.same(List.of("tests"), List.of("tests", "needs-design")))
                .as("a label left out")
                .isFalse();
    }
}
