package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An issue's head as the store writes and reads it. */
class IssueHeadTest {

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

    @Test
    void theHeadWritesTheWorkerAfterEveryOtherOptionalFieldButTheRiskAndReadsItBack() {
        // A worker written elsewhere would differ from the head the build tools write, and their
        // index check would rewrite every issue a person set.
        final IssueHead head =
                new IssueHead(
                        "XY07.1",
                        "Served",
                        "Idea",
                        "S",
                        "Medium",
                        1,
                        List.of(),
                        "Something.",
                        "XY07",
                        "builder",
                        "Major",
                        "Bug",
                        2,
                        "Manual",
                        null);

        final String json = head.json();

        assertThat(json)
                .as("the worker follows openQuestions and closes the head")
                .endsWith("  \"openQuestions\": 2,\n  \"worker\": \"Manual\"\n}");
        assertThat(IssueHead.read(head.file("Body.\n"), WORKFLOW))
                .as("the written head reads back as itself")
                .isEqualTo(head);
    }

    @Test
    void theHeadWritesTheRiskLastAfterTheWorkerAndReadsItBack() {
        // A risk dropped where the planner writes a head would work an issue that can do harm as an
        // ordinary one; written elsewhere, it would differ from the build tools' head.
        final IssueHead head =
                new IssueHead(
                        "XY07",
                        "Served",
                        "Idea",
                        "S",
                        "Medium",
                        1,
                        List.of(),
                        "Something.",
                        null,
                        null,
                        null,
                        null,
                        2,
                        "Manual",
                        "harm");

        assertThat(head.json())
                .as("the worker follows openQuestions and the risk closes the head")
                .endsWith(
                        "  \"openQuestions\": 2,\n  \"worker\": \"Manual\",\n  \"risk\": \"harm\"\n}");
        assertThat(IssueHead.read(head.file("Body.\n"), WORKFLOW))
                .as("the written head reads back as itself")
                .isEqualTo(head);
    }
}
