package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardTest {

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Finished"),
                    List.of(new Workflow.Phase("Open", List.of("Idea", "Backlog"))));

    private static Story story(final String id, final String state) {
        return new Story(id, "A story", state, "High", List.of("XY03"), "Text", null);
    }

    private static Issue issue(final String id, final String state) {
        return new Issue(
                id, "porelo-planner", "An issue", state, "S", List.of(), null, null, "Text");
    }

    @Test
    void aBoardCannotBeChangedThroughTheListsItWasMadeFrom() {
        // One board is the snapshot every request answers from; a caller's list must not change it.
        final List<Story> stories = new ArrayList<>(List.of(story("ST1", "Idea")));
        final List<Issue> issues = new ArrayList<>(List.of(issue("XY03", "Backlog")));
        final Board board = new Board(new Revision("r"), WORKFLOW, stories, issues);

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
                                        List.of()))
                .as("a story twice")
                .hasMessage("Story ST1 is on the board twice");
        assertThatThrownBy(
                        () ->
                                new Board(
                                        new Revision("r"),
                                        WORKFLOW,
                                        List.of(),
                                        List.of(issue("XY03", "Idea"), issue("XY03", "Idea"))))
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
                                        List.of(issue("XY03", "Done"))))
                .as("an issue in an unknown state")
                .hasMessage("XY03 has the unknown state 'Done'");
    }

    @Test
    void aWorkflowRefusesAStateTwiceAndAPhaseOfAnUnknownState() {
        assertThatThrownBy(() -> new Workflow(List.of("Idea", "Idea"), List.of()))
                .as("a state twice")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Workflow(
                                        List.of("Idea"),
                                        List.of(new Workflow.Phase("Open", List.of("Done")))))
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
