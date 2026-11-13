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

    private static final Moves.StoryState DERIVE = MovePreviewTest::derive;

    private static final MovePreview.Running NOTHING_RUNS = (issues, stories) -> List.of();

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
                    List.of(OWN, OTHER));

    @Test
    void aStoryMovedOnShowsTheIssueOfAnotherRepositoryItCarriesAlongWithItsStates() {
        // Harm: an issue of another repository still at work set to Review unasked.
        final MovePreview preview = preview(List.of(story("ST1", "Review")), NOTHING_RUNS);

        assertThat(preview.issues())
                .as("the issue that moves, with its repository, old and new state")
                .containsExactly(new MovePreview.MovedIssue("F1", OTHER, "Implementing", "Review"));
        assertThat(kinds(preview)).as("the warning").containsExactly(MovePreview.ISSUES_ALONG);
        assertThat(preview.warnings().getFirst().issues())
                .as("the warning names the issue carried along")
                .containsExactly(new MovePreview.MovedIssue("F1", OTHER, "Implementing", "Review"));
    }

    @Test
    void aStoryMovedThatCarriesOnlyItsOwnWaitingIssueAsksNothing() {
        // Harm: a warning on every move, which teaches the person to confirm without reading.
        final MovePreview preview = preview(List.of(story("ST2", "Review")), NOTHING_RUNS);

        assertThat(preview.warnings()).as("nothing to ask").isEmpty();
        assertThat(preview.issues())
                .as("the story's own issue moves with it")
                .containsExactly(new MovePreview.MovedIssue("PL2", OWN, "Backlog", "Review"));
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
    void aStoryThatWouldNotFitItsIssuesWarnsInsteadOfBeingRefused() {
        // Harm: a story shown Rejected over an issue still in Review, confirmed without seeing it.
        final MovePreview preview = preview(List.of(story("ST3", "Rejected")), NOTHING_RUNS);

        assertThat(kinds(preview)).containsExactly(MovePreview.STORY_MISMATCH);
        assertThat(preview.warnings().getFirst().issues())
                .as("the issue that does not fit, with its state after the batch")
                .containsExactly(new MovePreview.MovedIssue("PL3", OWN, "Review", "Review"));
    }

    @Test
    void aMoveOverRunningWorkNamesTheRunItsTasksAndItsOffersNotYetStarted() {
        // Harm: agents left working on a story that moved on, or offers started after the move.
        final List<Set<String>> asked = new ArrayList<>();
        final MovePreview preview =
                preview(
                        List.of(story("ST1", "Review")),
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
                        () -> preview(List.of(story("ST2", "Reading")), NOTHING_RUNS));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.name()).isEqualTo("EnteredOnlyByWork");
    }

    @Test
    void theDigestChangesWithEverythingThePreviewShowedAndWhereTheSaveGoes() {
        // Harm: a confirmation counted for another batch, repository, branch or work.
        final MovePreview preview = preview(List.of(story("ST1", "Review")), NOTHING_RUNS);
        final String digest = preview.digest("https://example.org/a.git", null);

        assertThat(digest).as("SHA-256 in hex").matches("[0-9a-f]{64}");
        assertThat(
                        preview(List.of(story("ST1", "Review")), NOTHING_RUNS)
                                .digest("https://example.org/a.git", null))
                .as("the same preview again")
                .isEqualTo(digest);
        assertThat(
                        List.of(
                                preview.digest("https://example.org/b.git", null),
                                preview.digest("https://example.org/a.git", "main"),
                                preview(List.of(story("ST1", "Finishing")), NOTHING_RUNS)
                                        .digest("https://example.org/a.git", null),
                                preview(
                                                List.of(story("ST1", "Review")),
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

    private MovePreview preview(final List<Moves.Move> moves, final MovePreview.Running running) {
        return Moves.preview(board, moves, DERIVE, running);
    }

    private static List<String> kinds(final MovePreview preview) {
        return preview.warnings().stream().map(MovePreview.Warning::kind).toList();
    }

    private static @Nullable String derive(final List<String> states) {
        final List<String> open = states.stream().filter(state -> !CLOSED.contains(state)).toList();
        final List<String> candidates = open.isEmpty() ? states : open;
        return candidates.stream()
                .min((a, b) -> STATES.indexOf(a) - STATES.indexOf(b))
                .orElse(null);
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
                Map.of("codex", "gpt"));
    }

    private static Story story(final String id, final String state, final String... issues) {
        return new Story(id, "A story", state, false, "High", null, List.of(issues), "", null);
    }

    private static Issue issue(final String id, final String repository, final String state) {
        return new Issue(id, repository, "An issue", state, "S", List.of(), null, null, "");
    }
}
