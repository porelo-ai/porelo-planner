package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** What a batch of moves would do and what it can do harm by, shown before anything is saved. */
class MovePreviewTest {

    private static final List<String> STATES =
            List.of(
                    "Idea",
                    "Backlog",
                    "Implementing",
                    "Reading",
                    "Review",
                    "Finishing",
                    "Finished",
                    "Rejected");

    private static final List<String> CLOSED = List.of("Finished", "Rejected");

    private static final Work WORK =
            new Work(Map.of("Implementing", rule("Reading"), "Reading", rule(null)), List.of());

    private static final MovePreview.Running NOTHING_RUNS = (issues, stories) -> List.of();

    /** The work record holds no run of this planner. */
    private static final Moves.Runs NO_RUNS = (issue, stories) -> false;

    private static final String OWN = "porelo-planner";

    private static final String OTHER = "porelo-frontend";

    private final Board board =
            new Board(
                    new Revision("r1"),
                    new Workflow(STATES, List.of(), CLOSED),
                    List.of(
                            story("ST1", "Implementing", "PL1", "F1"),
                            story("ST2", "Backlog", "PL2"),
                            story("ST3", "Review", "PL3", "PL4")),
                    List.of(
                            issue("PL1", OWN, "Review"),
                            issue("F1", OTHER, "Implementing"),
                            issue("PL2", OWN, "Backlog"),
                            issue("PL3", OWN, "Review"),
                            issue("PL4", OWN, "Backlog")),
                    WORK,
                    org.fuin.porelo.planner.store.ProjectRepository.named(List.of(OWN, OTHER)));

    @Test
    void aStoryRejectedShowsTheIssueOfAnotherRepositoryItClosesAlongWithItsStates() {
        // Harm: an issue of another repository still at work closed unasked.
        final MovePreview preview = preview(List.of(story("ST1", "Rejected")), NOTHING_RUNS);

        assertThat(preview.issues())
                .as("the issues that close with it, with their repository, old and new state")
                .containsExactly(
                        new MovePreview.MovedIssue("PL1", OWN, "Review", "Rejected"),
                        new MovePreview.MovedIssue("F1", OTHER, "Implementing", "Rejected"));
        assertThat(kinds(preview)).as("the warning").containsExactly(MovePreview.ISSUES_ALONG);
        assertThat(preview.warnings().getFirst().issues())
                .as("the warning names the issue carried along")
                .containsExactly(
                        new MovePreview.MovedIssue("F1", OTHER, "Implementing", "Rejected"));
    }

    @Test
    void aStoryRejectedThatClosesOnlyItsOwnWaitingIssueAsksNothing() {
        // Harm: a warning on every move, which teaches the person to confirm without reading.
        final MovePreview preview = preview(List.of(story("ST2", "Rejected")), NOTHING_RUNS);

        assertThat(preview.warnings()).as("nothing to ask").isEmpty();
        assertThat(preview.issues())
                .as("the story's own issue closes with it")
                .containsExactly(new MovePreview.MovedIssue("PL2", OWN, "Backlog", "Rejected"));
    }

    @Test
    void aMoveForwardPastAWaitingStateWarnsNamingTheStatesPassed() {
        // Harm: a story set past Review straight to Finishing, its review skipped unasked.
        final MovePreview preview = preview(List.of(issue("PL2", "Finishing")), NOTHING_RUNS);

        assertThat(kinds(preview)).containsExactly(MovePreview.SKIPS_STATES);
        assertThat(preview.warnings().getFirst().skipped())
                .as("only the waiting state passed, not the ones only work enters")
                .containsExactly("Review");
    }

    @Test
    void aMoveIntoAClosedStateIsNotAskedAboutAsSuch() {
        // Harm: a warning on every close, which teaches the person to confirm without reading.
        final MovePreview preview = preview(List.of(issue("PL2", "Finished")), NOTHING_RUNS);

        assertThat(preview.warnings()).as("a close can be moved back").isEmpty();
    }

    @Test
    void anOpenStoryMovedToAnotherOpenStateIsRefusedByItsPreview() {
        // Harm: an open story shown in a state its issues do not give, which no read keeps.
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () -> preview(List.of(story("ST3", "Backlog")), NOTHING_RUNS));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
    }

    @Test
    void aMoveOverRunningWorkNamesTheRunItsTasksAndItsOffersNotYetStarted() {
        // Harm: agents left working on a story that moved on, or offers started after the move.
        final List<Set<String>> asked = new ArrayList<>();
        final MovePreview preview =
                preview(
                        List.of(story("ST1", "Rejected")),
                        (issues, stories) -> {
                            asked.add(issues);
                            asked.add(stories);
                            return List.of(
                                    new MovePreview.Owned(
                                            "ST1", "run-1", List.of("build"), List.of("o-2")));
                        });

        assertThat(asked)
                .as("the issues and the story the batch touches")
                .containsExactly(Set.of("PL1", "F1"), Set.of("ST1"));
        final MovePreview.Warning running = preview.warnings().getFirst();
        assertThat(running.kind()).isEqualTo(MovePreview.RUNNING_WORK);
        assertThat(List.of(running.item(), running.run(), running.tasks(), running.offers()))
                .as("the story, its run, the task its stop ends and the offer it voids")
                .containsExactly("ST1", "run-1", List.of("build"), List.of("o-2"));
    }

    @Test
    void aBatchTheSaveWouldRefuseIsRefusedByItsPreviewToo() {
        // Harm: a preview that asks about a move the save then refuses, or the reverse.
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () -> preview(List.of(issue("PL9", "Review")), NOTHING_RUNS));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.name()).isEqualTo("NoSuchIssue");
    }

    @Test
    void anAgentsIssueIsPreviewedIntoAWorkingStateOnlyWhileNoRunWorksItsStory() {
        // Harm: a hand move confirmed beside a running task, which the save then stops.
        final List<Moves.Move> moves = List.of(issue("PL2", "Implementing"));
        final List<Set<String>> asked = new ArrayList<>();

        assertThat(
                        Moves.preview(
                                        board,
                                        moves,
                                        NOTHING_RUNS,
                                        (issue, stories) -> {
                                            asked.add(Set.of(issue));
                                            asked.add(stories);
                                            return false;
                                        })
                                .issues())
                .as("previewed into the working state with no run")
                .containsExactly(new MovePreview.MovedIssue("PL2", OWN, "Backlog", "Implementing"));
        assertThat(asked)
                .as("the issue and its story are asked about")
                .containsExactly(Set.of("PL2"), Set.of("ST2"));
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () ->
                                Moves.preview(
                                        board,
                                        moves,
                                        NOTHING_RUNS,
                                        (issue, stories) -> stories.contains("ST2")));
        assertThat(refused).as("refused while a run works its story").isNotNull();
        assertThat(refused.name()).as("the refusal").isEqualTo("EnteredOnlyByWork");
    }

    @Test
    void theDigestChangesWithEverythingThePreviewShowedAndWhereTheSaveGoes() {
        // Harm: a confirmation counted for another batch, repository, branch or work.
        final MovePreview preview = preview(List.of(story("ST1", "Rejected")), NOTHING_RUNS);
        final String digest = preview.digest("https://example.org/a.git", null);

        assertThat(digest).as("SHA-256 in hex").matches("[0-9a-f]{64}");
        assertThat(
                        preview(List.of(story("ST1", "Rejected")), NOTHING_RUNS)
                                .digest("https://example.org/a.git", null))
                .as("the same preview again")
                .isEqualTo(digest);
        assertThat(
                        List.of(
                                preview.digest("https://example.org/b.git", null),
                                preview.digest("https://example.org/a.git", "main"),
                                preview(List.of(story("ST2", "Rejected")), NOTHING_RUNS)
                                        .digest("https://example.org/a.git", null),
                                preview(
                                                List.of(story("ST1", "Rejected")),
                                                (issues, stories) ->
                                                        List.of(
                                                                new MovePreview.Owned(
                                                                        "ST1",
                                                                        "run-1",
                                                                        List.of("build"),
                                                                        List.of())))
                                        .digest("https://example.org/a.git", null)))
                .as("another repository, branch, batch and running work")
                .doesNotContain(digest)
                .doesNotHaveDuplicates();
    }

    /** Stories finished in odd shapes: over rejected issues only, over none, over work. */
    private final Board finishing =
            new Board(
                    new Revision("r1"),
                    new Workflow(STATES, List.of(), CLOSED),
                    List.of(
                            // Open, as a story over rejected issues only stands.
                            story("ST4", "Backlog", "PL5", "PL6"),
                            new Story("ST5", "A story", "Idea", "High", null, List.of(), "", null),
                            story("ST6", "Review", "PL7"),
                            story("ST7", "Reading", "PL8")),
                    List.of(
                            issue("PL5", OWN, "Rejected"),
                            issue("PL6", OWN, "Rejected"),
                            issue("PL7", OWN, "Review"),
                            issue("PL8", OWN, "Reading")),
                    WORK,
                    org.fuin.porelo.planner.store.ProjectRepository.named(List.of(OWN)));

    @Test
    void aStoryFinishedOverRejectedIssuesOnlyIsSavedWithItsIssuesLeftRejected() {
        // Harm: a Finished over rejected work refused, or reopening and finishing the rejected
        // work with it.
        final List<Moves.Move> moves = List.of(story("ST4", "Finished"));
        final MovePreview preview = Moves.preview(finishing, moves, NOTHING_RUNS, NO_RUNS);

        assertThat(preview.warnings()).as("nothing to ask").isEmpty();
        final Moves.Planned planned = Moves.check(finishing, moves, NO_RUNS);
        assertThat(planned.stories())
                .as("the save closes the story as Finished")
                .containsExactly(Map.entry("ST4", "Finished"));
        assertThat(planned.issues()).as("its rejected issues stay").isEmpty();
    }

    @Test
    void aStoryWithoutIssuesFinishedIsSavedUnasked() {
        // Harm: a Finished of a story without issues asked about, or refused by its save.
        final List<Moves.Move> moves = List.of(story("ST5", "Finished"));
        final MovePreview preview = Moves.preview(finishing, moves, NOTHING_RUNS, NO_RUNS);

        assertThat(preview.warnings()).as("nothing to ask").isEmpty();
        assertThat(Moves.check(finishing, moves, NO_RUNS).stories())
                .as("saved unasked")
                .containsExactly(Map.entry("ST5", "Finished"));
    }

    @Test
    void aStoryFinishedOverAnIssueWhoseStageRunsIsRefusedInOneLineByItsPreview() {
        // Harm: a Finished over an issue a stage still works passed by the preview and then
        // failing in the store.
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () ->
                                Moves.preview(
                                        finishing,
                                        List.of(story("ST7", "Finished")),
                                        NOTHING_RUNS,
                                        NO_RUNS));

        assertThat(refused).as("refused by the preview").isNotNull();
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
        assertThat(refused.getMessage())
                .as("one line naming the issue")
                .contains("PL8:Reading")
                .doesNotContain("\n");
        final Moves.Refused saved =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () -> Moves.check(finishing, List.of(story("ST7", "Finished")), NO_RUNS));
        assertThat(saved).as("refused by the save alike").isNotNull();
        assertThat(saved.getMessage()).as("in the same words").isEqualTo(refused.getMessage());
    }

    @Test
    void aStoryFinishedOverRunningWorkIsAskedAboutAndSavedAsConfirmed() {
        // Harm: work at a task over a finished issue ended unasked, or the confirmed save refused.
        final List<Moves.Move> moves = List.of(story("ST6", "Finished"));
        final MovePreview preview =
                Moves.preview(
                        finishing,
                        moves,
                        (issues, stories) ->
                                List.of(
                                        new MovePreview.Owned(
                                                "ST6", "run-1", List.of("read"), List.of())),
                        NO_RUNS);

        assertThat(kinds(preview)).as("asked about").contains(MovePreview.RUNNING_WORK);
        assertThat(Moves.check(finishing, moves, NO_RUNS).issues())
                .as("the issue finished with its story")
                .containsExactly(Map.entry("PL7", "Finished"));
    }

    private MovePreview preview(final List<Moves.Move> moves, final MovePreview.Running running) {
        return Moves.preview(board, moves, running, NO_RUNS);
    }

    private static List<String> kinds(final MovePreview preview) {
        return preview.warnings().stream().map(MovePreview.Warning::kind).toList();
    }

    private static Moves.Move story(final String id, final String to) {
        return new Moves.Move(true, id, to);
    }

    private static Moves.Move issue(final String id, final String to) {
        return new Moves.Move(false, id, to);
    }

    private static WorkRule rule(final @Nullable String then) {
        return new WorkRule(
                List.of("Backlog"),
                "Review",
                then,
                null,
                WorkRule.Per.STORY,
                List.of("UNATTENDED"),
                "Build {story}",
                Map.of("codex", "gpt"),
                Roles.forRule(null, null));
    }

    private static Story story(final String id, final String state, final String... issues) {
        return new Story(id, "A story", state, "High", null, List.of(issues), "", null);
    }

    private static Issue issue(final String id, final String repository, final String state) {
        return new Issue(id, repository, "An issue", state, "S", List.of(), null, null, "");
    }
}
