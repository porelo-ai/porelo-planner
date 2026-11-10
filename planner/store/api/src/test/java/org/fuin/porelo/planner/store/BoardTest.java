package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoardTest {

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Finished"),
                    List.of(new Workflow.Phase("Open", List.of("Idea", "Backlog"))),
                    List.of("Finished"));

    private static Story story(final String id, final String state) {
        return new Story(id, "A story", state, true, "High", List.of("XY03"), "Text", null);
    }

    private static Issue issue(final String id, final String state) {
        return new Issue(
                id, "porelo-planner", "An issue", state, "S", List.of(), null, null, "Text");
    }

    @Test
    void repositoryIdentityIsExplicitOrderedAndImmutable() {
        // Story-wide work must start in the own repository even when all issues live elsewhere.
        final List<String> repositories = new ArrayList<>(List.of("project", "porelo-planner"));
        final Board board =
                new Board(
                        new Revision("r"),
                        WORKFLOW,
                        List.of(),
                        List.of(issue("XY03", "Idea")),
                        new Work(Map.of(), List.of()),
                        repositories);
        repositories.clear();
        assertThat(board.repositories())
                .as("own first and copied")
                .containsExactly("project", "porelo-planner");
        assertThatThrownBy(() -> board.repositories().clear())
                .as("repository identity cannot be changed")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(
                        () ->
                                new Board(
                                        new Revision("r"),
                                        WORKFLOW,
                                        List.of(),
                                        List.of(issue("XY03", "Idea")),
                                        new Work(Map.of(), List.of()),
                                        List.of("project")))
                .as("issues cannot escape the project's repositories")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Board(
                                        new Revision("r"),
                                        WORKFLOW,
                                        List.of(),
                                        List.of(),
                                        new Work(Map.of(), List.of()),
                                        List.of("project", "project")))
                .as("duplicate repositories cannot create duplicate work")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Board(
                                        new Revision("r"),
                                        WORKFLOW,
                                        List.of(),
                                        List.of(),
                                        new Work(Map.of(), List.of()),
                                        List.of("../other")))
                .as("repository names are checked before they reach tasks")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCompatibilityConstructorKeepsIssueRepositoriesInOrder() {
        // Existing fake boards need stable repository identity without a project definition.
        final Board board =
                new Board(
                        new Revision("r"),
                        WORKFLOW,
                        List.of(),
                        List.of(
                                issue("XY03", "Idea"),
                                new Issue(
                                        "AB1", "other", "Other", "Idea", null, List.of(), null,
                                        null, ""),
                                issue("XY04", "Idea")),
                        new Work(Map.of(), List.of()));
        assertThat(board.repositories())
                .as("first occurrence of each issue repository")
                .containsExactly("porelo-planner", "other");
    }

    @Test
    void aBoardCannotBeChangedThroughTheListsItWasMadeFrom() {
        // One board is the snapshot every request answers from; a caller's list must not change it.
        final List<Story> stories = new ArrayList<>(List.of(story("ST1", "Idea")));
        final List<Issue> issues = new ArrayList<>(List.of(issue("XY03", "Backlog")));
        final Board board =
                new Board(
                        new Revision("r"),
                        WORKFLOW,
                        stories,
                        issues,
                        new Work(Map.of(), List.of()));

        stories.clear();
        issues.add(issue("XY04", "Idea"));

        assertThat(board.stories()).as("the board keeps its own stories").hasSize(1);
        assertThat(board.issues()).as("the board keeps its own issues").hasSize(1);
        assertThatThrownBy(() -> board.issues().clear())
                .as("nobody changes the board's issues through it")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anIdOnTheBoardTwiceIsRefused() {
        // With an id twice, a move would land on whichever copy is found first.
        assertThatThrownBy(
                        () ->
                                new Board(
                                        new Revision("r"),
                                        WORKFLOW,
                                        List.of(story("ST1", "Idea"), story("ST1", "Backlog")),
                                        List.of(),
                                        new Work(Map.of(), List.of())))
                .as("a story twice")
                .hasMessage("Story ST1 is on the board twice");
        assertThatThrownBy(
                        () ->
                                new Board(
                                        new Revision("r"),
                                        WORKFLOW,
                                        List.of(),
                                        List.of(issue("XY03", "Idea"), issue("XY03", "Idea")),
                                        new Work(Map.of(), List.of())))
                .as("an issue twice")
                .hasMessage("Issue XY03 is on the board twice");
    }

    @Test
    void aStateTheWorkflowDoesNotKnowIsRefused() {
        // A state outside the workflow has no place on the board and no move out of it.
        assertThatThrownBy(
                        () ->
                                new Board(
                                        new Revision("r"),
                                        WORKFLOW,
                                        List.of(),
                                        List.of(issue("XY03", "Done")),
                                        new Work(Map.of(), List.of())))
                .as("an issue in an unknown state")
                .hasMessage("XY03 has the unknown state 'Done'");
    }

    @Test
    void aWorkflowRefusesAStateTwiceAndAPhaseOfAnUnknownState() {
        // Duplicate or unknown states would make a move's destination ambiguous.
        assertThatThrownBy(() -> new Workflow(List.of("Idea", "Idea"), List.of(), List.of()))
                .as("a state twice")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Workflow(
                                        List.of("Idea"),
                                        List.of(new Workflow.Phase("Open", List.of("Done"))),
                                        List.of()))
                .as("a phase naming an unknown state")
                .hasMessage("Phase 'Open' names an unknown state 'Done'");
    }

    @Test
    void aSubIssueMustCarryItsParentsId() {
        // A sub-issue's file sits beside its parent's; a mismatched parent reads someone else's.
        assertThatThrownBy(
                        () ->
                                new Issue(
                                        "XY03.1",
                                        "porelo-planner",
                                        "Sub",
                                        "Idea",
                                        null,
                                        List.of(),
                                        "XY04",
                                        null,
                                        ""))
                .as("XY03.1 under XY04")
                .hasMessage("Sub-issue XY03.1 does not carry its parent's id XY04");
    }
}
