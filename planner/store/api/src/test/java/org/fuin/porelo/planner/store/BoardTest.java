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
        return new Story(id, "A story", state, true, "High", null, List.of("XY03"), "Text", null);
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
    void anIssueOutsideTheProjectNamesOneIssueWithinItsRepositories() {
        // Harm: an add that names by one id two issues, or one in a repository outside the
        // project.
        final Issue onBoard =
                new Issue(
                        "porelo-planner#3",
                        "porelo-planner",
                        "An issue",
                        "Idea",
                        "S",
                        List.of(),
                        null,
                        null,
                        "Text");
        final UnlistedIssue made = unlisted("porelo-planner", 4);

        assertThat(withUnlisted(List.of(onBoard), List.of(made)).unlisted())
                .as("an issue outside the project")
                .containsExactly(made);
        assertThatThrownBy(
                        () ->
                                withUnlisted(
                                        List.of(onBoard), List.of(unlisted("porelo-planner", 3))))
                .as("an id on the board")
                .hasMessage("Issue porelo-planner#3 is on the board twice");
        assertThatThrownBy(() -> withUnlisted(List.of(), List.of(made, made)))
                .as("an id twice outside the project")
                .hasMessage("Issue porelo-planner#4 is on the board twice");
        assertThatThrownBy(() -> withUnlisted(List.of(), List.of(unlisted("elsewhere", 4))))
                .as("a repository outside the project")
                .hasMessage("An issue names a repository outside the project");
    }

    private static UnlistedIssue unlisted(final String repository, final int number) {
        return new UnlistedIssue(
                repository + "#" + number,
                repository,
                "Made by hand",
                UnlistedIssue.Kind.ISSUE,
                java.net.URI.create(
                        "https://github.com/porelo-ai/" + repository + "/issues/" + number));
    }

    private static Board withUnlisted(
            final List<Issue> issues, final List<UnlistedIssue> unlisted) {
        return new Board(
                new Revision("r"),
                WORKFLOW,
                List.of(),
                issues,
                new Work(Map.of(), List.of()),
                List.of("porelo-planner"),
                List.of(),
                "porelo-ai",
                List.of(),
                unlisted);
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

    private static Board board(final List<Story> stories, final List<Issue> issues) {
        return new Board(
                new Revision("r"),
                WORKFLOW,
                stories,
                issues,
                new Work(Map.of(), List.of()),
                List.of("porelo-planner", "porelo"));
    }

    private static Issue in(final String repository, final String id, final Warning... warnings) {
        return new Issue(id, repository, "An issue", "Idea", "S", List.of(), null, null, "Text")
                .withWarnings(List.of(warnings));
    }

    @Test
    void aStateOutsideTheWorkflowStandsOnlyOnAnItemWarnedAboutIt() {
        // Without the warning a store could slip an item past every rule that reads the workflow.
        assertThatThrownBy(() -> board(List.of(), List.of(issue("XY03", "Parked"))))
                .as("an unknown state without a warning")
                .hasMessage("XY03 has the unknown state 'Parked'");
        assertThatThrownBy(
                        () ->
                                board(
                                        List.of(),
                                        List.of(
                                                issue("XY03", "Parked")
                                                        .withWarnings(
                                                                List.of(Warning.noPriority())))))
                .as("a warning about something else does not let the state in")
                .isInstanceOf(IllegalArgumentException.class);
        final Board board =
                board(
                        List.of(),
                        List.of(
                                issue("XY03", "Parked")
                                        .withWarnings(List.of(Warning.stateOutsideWorkflow()))));
        assertThat(board.issue("XY03").map(Issue::state))
                .as("the warned issue is on the board in its own state")
                .contains("Parked");
    }

    @Test
    void anEmptyStateStandsOnlyOnAStoryWarnedThatNoneCanBeTold() {
        // An empty state is what a save writes to have a story follow its issues again.
        assertThatThrownBy(
                        () ->
                                board(
                                        List.of(
                                                story("ST1", "")
                                                        .withWarnings(
                                                                List.of(
                                                                        Warning
                                                                                .stateOutsideWorkflow()))),
                                        List.of(issue("XY03", "Idea"))))
                .as("an empty state needs the warning that no state can be told")
                .hasMessage("ST1 has the unknown state ''");
        assertThatThrownBy(
                        () ->
                                board(
                                        List.of(
                                                story("ST1", "")
                                                        .withWarnings(List.of(Warning.noState()))),
                                        List.of(issue("XY03", "Idea"))))
                .as("a story without a state never counts as stating one")
                .hasMessage("Story ST1 states no state but is marked as stating one");
        final Board board =
                board(
                        List.of(
                                new Story(
                                        "ST1",
                                        "A story",
                                        "",
                                        false,
                                        "High",
                                        null,
                                        List.of("XY03"),
                                        "Text",
                                        null,
                                        true,
                                        List.of(Warning.noState()))),
                        List.of(issue("XY03", "Idea")));
        assertThat(board.holdsWork("ST1"))
                .as("no work starts on a story whose state cannot be told")
                .contains("ST1: " + Warning.noState().text());
    }

    @Test
    void anIdOnTwoIssuesStandsOnlyWhenEachCopyIsWarnedAndNamesNoOneIssue() {
        // One id naming two issues would let a move or a work write whichever it found first.
        assertThatThrownBy(
                        () ->
                                board(
                                        List.of(),
                                        List.of(
                                                in("porelo-planner", "XY03", Warning.idTwice()),
                                                in("porelo", "XY03"))))
                .as("a copy without the warning")
                .hasMessage("Issue XY03 is on the board twice");
        final Board board =
                board(
                        List.of(),
                        List.of(
                                in("porelo-planner", "XY03", Warning.idTwice()),
                                in("porelo", "XY03", Warning.idTwice())));
        assertThat(board.issues()).as("both copies are shown").hasSize(2);
        assertThat(board.issue("XY03")).as("the id names no one issue").isEmpty();
        final String words =
                "XY03 names an issue in porelo-planner and porelo; it can be neither worked nor"
                        + " moved until one id is changed.";
        assertThat(board.twice("XY03")).as("the refusal names both repositories").contains(words);
        assertThat(board.holdsWork("XY03")).as("it holds back work").contains(words);
        assertThat(board.holdsMove("XY03")).as("and moves").contains(words);
    }

    @Test
    void onlyAWarningAboutStateParentBlockersOrIdHoldsAnItemBack() {
        // A warning that changes nothing a rule reads must not stop the person's work.
        final Board board =
                board(
                        List.of(),
                        List.of(
                                in("porelo-planner", "XY01", Warning.noPriority()),
                                in("porelo-planner", "XY02", Warning.bodyNotSectioned()),
                                in("porelo-planner", "XY03", Warning.parentOutsideProject()),
                                issue("XY04", "Parked")
                                        .withWarnings(List.of(Warning.stateOutsideWorkflow()))));
        assertThat(board.holdsWork("XY01")).as("no priority").isEmpty();
        assertThat(board.holdsWork("XY02")).as("no sections").isEmpty();
        assertThat(board.holdsWork("XY03"))
                .as("a parent outside the project")
                .contains("XY03: " + Warning.parentOutsideProject().text());
        assertThat(board.holdsMove("XY03")).as("which no move sets right").isPresent();
        assertThat(board.holdsWork("XY04"))
                .as("a state outside the workflow")
                .contains("XY04: " + Warning.stateOutsideWorkflow().text());
        assertThat(board.holdsMove("XY04"))
                .as("a move into the workflow sets the state right")
                .isEmpty();
    }
}
