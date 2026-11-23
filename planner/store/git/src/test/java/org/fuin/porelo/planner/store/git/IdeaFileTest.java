package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.git.stories.IssueHead;
import org.fuin.porelo.planner.store.git.stories.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A new issue's file as the Git store writes and reads it. */
class IdeaFileTest {

    /** An issue file the store holds already, made up for the test. */
    private static final String EXISTING = "issues/XY01-Existing.md"; // not-a-citation

    /** The file the store writes next for a new issue, made up for the test. */
    private static final String NEXT = "issues/XY02-A-new-issue.md"; // not-a-citation

    @TempDir Path dir;

    @Test
    void aNewIssuesFileReadsBackInNew() throws Exception {
        // Harm: a new issue the store refuses to read back.
        final Workflow workflow =
                Workflow.read(
                        """
                        states:
                          - {name: New, closed: false}
                          - {name: Finished, closed: true}
                        phases:
                          - {name: Open, states: [New]}
                          - {name: Done, states: [Finished]}
                        """);
        Files.createDirectories(dir.resolve("issues"));
        Files.writeString(dir.resolve(EXISTING), "# Existing issue\n");

        assertThatCode(
                        () ->
                                IdeaFile.write(
                                        dir,
                                        dir,
                                        true,
                                        new NewIssue("store", "A new issue", "It reads back.", "S"),
                                        workflow))
                .as("the store can create an issue in the workflow's first state")
                .doesNotThrowAnyException();

        final IssueHead head = IssueHead.read(Files.readString(dir.resolve(NEXT)), workflow);
        assertThat(head).as("the new issue's file has a readable head").isNotNull();
        assertThat(head.state()).as("the new issue starts in New").isEqualTo("New");
    }

    @Test
    void aNewIssueStartsInTheFirstStateItsProjectsWorkflowNames() throws Exception {
        // Harm: every new issue refused in a project whose files still name the first state Idea.
        final Workflow workflow =
                Workflow.read(
                        """
                        states:
                          - {name: Idea, closed: false}
                          - {name: Finished, closed: true}
                        phases:
                          - {name: Open, states: [Idea]}
                          - {name: Done, states: [Finished]}
                        """);
        Files.createDirectories(dir.resolve("issues"));
        Files.writeString(dir.resolve(EXISTING), "# Existing issue\n");

        final IdeaFile.Written written =
                IdeaFile.write(
                        dir,
                        dir,
                        true,
                        new NewIssue("store", "A new issue", "It reads back.", "S"),
                        workflow);

        assertThat(written.state()).as("the workflow's first state").isEqualTo("Idea");
        assertThat(IssueHead.read(Files.readString(dir.resolve(written.path())), workflow).state())
                .as("the file's head")
                .isEqualTo("Idea");
    }
}
