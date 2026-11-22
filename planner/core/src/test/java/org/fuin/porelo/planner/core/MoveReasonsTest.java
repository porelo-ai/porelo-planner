package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The person's one sentence why an item is closed, kept with each item a move closes. */
class MoveReasonsTest {

    private static final List<String> STATES =
            List.of("Idea", "Backlog", "Building", "Reading", "Review", "Finished", "Rejected");

    private static final List<String> CLOSED = List.of("Finished", "Rejected");

    private static final Work WORK =
            new Work(Map.of("Building", rule("Reading"), "Reading", rule(null)), List.of());

    private static final Moves.Runs NO_RUNS = (issue, stories) -> false;

    private static final String WHY = "Built as the story asked.";

    /** ST1 waits for its test over PL1 and PL1.1, while PL2 is rejected and PL5 finished. */
    private static final Board READY =
            new Board(
                    new Revision("r1"),
                    new Workflow(STATES, List.of(), CLOSED),
                    List.of(
                            story("ST1", "Review", "PL1", "PL2", "PL5"),
                            story("ST2", "Backlog", "PL3", "PL4"),
                            story("ST3", "Finished", "PL6"),
                            story("ST4", "Rejected", "PL7")),
                    List.of(
                            issue("PL1", "Review", null),
                            issue("PL1.1", "Review", "PL1"),
                            issue("PL2", "Rejected", null),
                            issue("PL5", "Finished", null),
                            issue("PL3", "Backlog", null),
                            issue("PL4", "Review", null),
                            issue("PL6", "Finished", null),
                            issue("PL7", "Rejected", null)),
                    WORK);

    @Test
    void aStorysReasonGoesToTheStoryAndToEachIssueItsMoveCloses() {
        // Harm: the issues a story's move closes lose why, or one closed long before gets a
        // sentence about other work.
        final Moves.Planned planned =
                Moves.check(
                        READY,
                        List.of(reason(story("ST1", "Finished"), WHY)),
                        false,
                        DERIVE,
                        NO_RUNS);
        final Writes.Reason kept = new Writes.Reason("Finished", WHY);
        assertThat(planned.storyReasons())
                .as("the story, whose shown state the move changes")
                .containsExactly(Map.entry("ST1", kept));
        assertThat(planned.issueReasons())
                .as("each issue it closes, its sub-issue too; never PL2 or PL5, closed already")
                .containsExactly(Map.entry("PL1", kept), Map.entry("PL1.1", kept));
        assertThat(planned.writes().storyReasons()).as("handed to the store").hasSize(1);
        assertThat(planned.writes().issueReasons()).as("handed to the store").hasSize(2);
    }

    @Test
    void aRejectedStoryGivesItsReasonToTheIssuesItCarries() {
        // Harm: a rejection's why is kept on the story but lost on the issues it closed.
        final Moves.Planned planned =
                Moves.check(
                        READY,
                        List.of(reason(story("ST2", "Rejected"), "Not wanted any more.")),
                        true,
                        DERIVE,
                        NO_RUNS);
        assertThat(planned.issueReasons())
                .as("the least advanced issue the move carries")
                .containsOnlyKeys("PL3");
        assertThat(planned.storyReasons()).containsOnlyKeys("ST2");
    }

    @Test
    void anIssuesOwnMoveInTheBatchWinsEntirelyItsReasonOrNone() {
        // Harm: a sentence the person wrote for the story lands on an issue they gave their own,
        // or none, in the same batch.
        final Moves.Planned none =
                Moves.check(
                        READY,
                        List.of(reason(story("ST1", "Finished"), WHY), issue("PL1.1", "Finished")),
                        false,
                        DERIVE,
                        NO_RUNS);
        assertThat(none.issueReasons())
                .as("PL1.1 moved by itself without one")
                .containsOnlyKeys("PL1");
        final Moves.Planned own =
                Moves.check(
                        READY,
                        List.of(
                                reason(story("ST1", "Finished"), WHY),
                                reason(issue("PL1.1", "Finished"), "Its own.")),
                        false,
                        DERIVE,
                        NO_RUNS);
        assertThat(own.issueReasons().get("PL1.1"))
                .as("its own sentence")
                .isEqualTo(new Writes.Reason("Finished", "Its own."));
    }

    @Test
    void aReasonIsTrimmedAndABlankOneIsNone() {
        // Harm: a stray space or an empty field written as a comment nobody meant.
        final Moves.Planned trimmed =
                Moves.check(
                        READY,
                        List.of(reason(issue("PL4", "Finished"), "  Done.\t")),
                        false,
                        DERIVE,
                        NO_RUNS);
        assertThat(trimmed.issueReasons().get("PL4"))
                .isEqualTo(new Writes.Reason("Finished", "Done."));
        final Moves.Planned blank =
                Moves.check(
                        READY,
                        List.of(reason(issue("PL4", "Finished"), " \t ")),
                        false,
                        DERIVE,
                        NO_RUNS);
        assertThat(blank.issueReasons()).as("a blank reason is none").isEmpty();
        assertThat(blank.issues()).as("and the move is saved as without one").hasSize(1);
    }

    @Test
    void aReasonHoldsAtMost280CodePointsCountedAsCharactersNotAsUnits() {
        // Harm: a long text written in full, or a short one in another script refused by the
        // length of its encoding.
        final String wide = "\uD83D\uDE00".repeat(Moves.MOST_REASON);
        final Moves.Planned planned =
                Moves.check(
                        READY,
                        List.of(reason(issue("PL4", "Finished"), wide)),
                        false,
                        DERIVE,
                        NO_RUNS);
        assertThat(planned.issueReasons().get("PL4").text()).isEqualTo(wide);
        assertThat(refused(reason(issue("PL4", "Finished"), "a".repeat(Moves.MOST_REASON + 1))))
                .as("281 characters")
                .contains("at most 280");
    }

    @Test
    void aReasonWithACharacterThatHidesOrTurnsTextIsRefused() {
        // Harm: a comment whose text reads otherwise than it was typed, or breaks into lines.
        for (final String bad :
                List.of(
                        "one\ntwo",
                        "bell\u0007",
                        "line\u2028break",
                        "para\u2029graph",
                        "turn\u202Eit",
                        "iso\u2066late",
                        "zero\u200Bwidth",
                        "joiner\u200Dx",
                        "mark\uFEFFx")) {
            assertThat(refused(reason(issue("PL4", "Finished"), bad)))
                    .as("refused: " + bad.codePoints().boxed().toList())
                    .contains("no control");
        }
    }

    @Test
    void aReasonGoesOnlyWithAMoveThatClosesItsItem() {
        // Harm: a sentence typed for a close that is written to nothing, or to an open item.
        assertThat(refused(reason(issue("PL3", "Review"), WHY)))
                .as("an open target")
                .contains("only with a move into a closed state");
        assertThat(refused(reason(story("ST2", "Review"), WHY)))
                .as("a story's open target")
                .contains("only with a move into a closed state");
        assertThat(refused(reason(issue("PL5", "Finished"), WHY)))
                .as("an issue that stands there already")
                .contains("in that state already");
        assertThat(refused(new Moves.Move(true, "ST3", "Finished", true, WHY)))
                .as("a story that follows its issues")
                .contains("closes nothing");
    }

    @Test
    void aStoryShownInItsTargetAlreadyStillKeepsItsReasonSoAFailedCommentIsWrittenAgain() {
        // Harm: a save whose comment failed after the close can never write it.
        final Moves.Planned planned =
                Moves.check(
                        READY,
                        List.of(reason(story("ST3", "Finished"), WHY)),
                        false,
                        DERIVE,
                        NO_RUNS);
        assertThat(planned.issues()).as("no state changes").isEmpty();
        assertThat(planned.storyReasons()).containsOnlyKeys("ST3");
        assertThat(planned.none()).as("still saved, for the reason").isFalse();
        final Moves.Planned rejected =
                Moves.check(
                        READY,
                        List.of(reason(story("ST4", "Rejected"), "Not wanted.")),
                        false,
                        DERIVE,
                        NO_RUNS);
        assertThat(rejected.storyReasons()).containsOnlyKeys("ST4");
        assertThat(rejected.issueReasons())
                .as("its issue stood rejected already, so the move closes nothing of it")
                .isEmpty();
    }

    @Test
    void theReasonIsNoPartOfThePreviewsDigestAndAPreviewRefusesABadOne() {
        // Harm: a reason typed after the preview makes the save refused as changed, or a bad one
        // passes the preview to be refused only on save.
        final MovePreview without =
                Moves.preview(
                        READY,
                        List.of(story("ST1", "Finished")),
                        DERIVE,
                        (issues, stories) -> List.of(),
                        NO_RUNS);
        final MovePreview with =
                Moves.preview(
                        READY,
                        List.of(reason(story("ST1", "Finished"), WHY)),
                        DERIVE,
                        (issues, stories) -> List.of(),
                        NO_RUNS);
        assertThat(with.digest("https://example.org/a.git", null))
                .isEqualTo(without.digest("https://example.org/a.git", null));
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () ->
                                Moves.preview(
                                        READY,
                                        List.of(reason(story("ST1", "Review"), WHY)),
                                        DERIVE,
                                        (issues, stories) -> List.of(),
                                        NO_RUNS));
        assertThat(refused).as("the preview refuses it too").isNotNull();
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
    }

    private static String refused(final Moves.Move move) {
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () -> Moves.check(READY, List.of(move), true, DERIVE, NO_RUNS));
        assertThat(refused).as("refused: " + move).isNotNull();
        assertThat(refused.name()).isEqualTo("MoveNotAllowed");
        assertThat(refused.parameters()).containsEntry("item", move.id());
        return refused.getMessage();
    }

    private static final Moves.StoryState DERIVE = MoveReasonsTest::derive;

    private static @Nullable String derive(final List<String> states) {
        final List<String> open = states.stream().filter(state -> !CLOSED.contains(state)).toList();
        final List<String> candidates = open.isEmpty() ? states : open;
        return candidates.stream()
                .min((a, b) -> STATES.indexOf(a) - STATES.indexOf(b))
                .orElse(null);
    }

    private static Moves.Move reason(final Moves.Move move, final String reason) {
        return new Moves.Move(move.story(), move.id(), move.to(), move.follow(), reason);
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

    private static Issue issue(final String id, final String state, final @Nullable String parent) {
        return new Issue(id, "porelo-planner", "An issue", state, "S", List.of(), parent, null, "");
    }
}
