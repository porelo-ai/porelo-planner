package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import org.junit.jupiter.api.Test;

class StoryHeadTest {

    private static final Workflow WORKFLOW =
            Workflow.read(
                    """
                    states:
                      - {name: Idea, closed: false}
                      - {name: Finished, closed: true}
                    phases:
                      - {name: Open, states: [Idea]}
                      - {name: Done, states: [Finished]}
                    """);

    // Prevents a person's words changed on their way into a head, or a field forged by them.
    @Test
    void aHeadWithQuotesColonsRulesAndBackslashesReadsBackAsItWasWritten() {
        final StoryHead written =
                new StoryHead(
                        "ST12",
                        "A \"quoted\": idea, \"state\": \"Finished\"",
                        null,
                        "--- a rule\\n and \\\"more\\\"",
                        "Run: \"it\"",
                        "Lowest",
                        List.of("porelo-other#CD02", "porelo-store#AB03"));

        final StoryHead read = StoryHead.read(written.file(), WORKFLOW);

        assertThat(read).as("every field as written, no state added").isEqualTo(written);
    }

    // Prevents a story head naming an issue the stories index cannot resolve.
    @Test
    void aReferenceTheSchemaRefusesIsRefusedBeforeItIsWritten() {
        final IllegalArgumentException refused =
                catchThrowableOfType(
                        IllegalArgumentException.class,
                        () -> StoryHead.reference("other-repository", "CD02"));

        assertThat(refused).as("a repository outside the schema's names refused").isNotNull();
        assertThat(StoryHead.reference("porelo-other", "CD02"))
                .as("a porelo repository taken")
                .isEqualTo("porelo-other#CD02");
    }
}
