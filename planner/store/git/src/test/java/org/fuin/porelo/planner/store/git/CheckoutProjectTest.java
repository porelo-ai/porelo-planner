package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.porelo.planner.store.Story;
import org.junit.jupiter.api.Test;

/** What a person reads of a Markdown story, as a carry-over takes it without its generated part. */
class CheckoutProjectTest {

    private static Story story(final String description) {
        return new Story(
                "ST1", "The Release", "Backlog", true, "High", "M", List.of(), description, null);
    }

    @Test
    void theGeneratedListOfIssuesIsLeftOutOfAStoryWrittenWithCarriageReturns() {
        // Harm: a story carried with its old list of issues shows links to Markdown files that are
        // gone.
        final String text =
                CheckoutProject.storyText(
                        story(
                                "**Priority:** High · **State:** Backlog\r\n\r\n"
                                        + "**What a user gains.** A gain.\r\n\r\n"
                                        + "**Issues:**\r\n\r\n"
                                        + "- [porelo-alpha#second-step](x) (Backlog)\r\n\r\n"
                                        + "Words of a person."));
        assertThat(text)
                .as("the story's text")
                .doesNotContain("second-step")
                .contains("Words of a person.");
    }

    @Test
    void aLineOfAPersonBelowTheGeneratedPartIsKeptWhateverItStartsWith() {
        // Harm: a person's own words below the generated part are taken out of the body carried.
        final String text =
                CheckoutProject.storyText(
                        story(
                                "**Priority:** High · **State:** Backlog\n\n"
                                        + "**Issues:** none yet\n\n"
                                        + "**Issues:** of my own\n"
                                        + "- the first\n"));
        assertThat(text).as("the story's text").isEqualTo("**Issues:** of my own\n- the first");
    }
}
