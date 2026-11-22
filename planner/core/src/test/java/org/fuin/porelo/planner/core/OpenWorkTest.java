package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** What is still open of a story and a board, and the minutes left of each issue. */
class OpenWorkTest {

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Implementing", "Review", "Finished", "Rejected"),
                    List.of(),
                    List.of("Finished", "Rejected"));

    private final List<Issue> issues = new ArrayList<>();

    // A blocked issue counted under its plain state would show a story as ready to build on.
    @Test
    void anIssueBlockedByAnOpenIssueCountsAsBlockedUntilItsBlockerCloses() {
        issue("own#1", "Backlog", "S", null, null, "own#2");
        issue("own#2", "Implementing", "S", null, null);

        final OpenWork blocked = story();

        assertThat(blocked.blocked()).as("the issue waiting on an open one").isEqualTo(1);
        assertThat(count(blocked.states(), "Backlog"))
                .as("the blocked issue is not counted under its state")
                .isZero();
        assertThat(blocked.left()).as("both are left to build").isEqualTo(2);

        issues.set(1, issue("own#2", "Finished", "S", null, null));
        issues.remove(2);
        final OpenWork free = story();

        assertThat(free.blocked()).as("its blocker closed").isZero();
        assertThat(count(free.states(), "Backlog")).as("counted under its state").isEqualTo(1);
        assertThat(free.left()).as("only the free one is left").isEqualTo(1);
    }

    // An issue waiting on another repository's counted under its plain state would show a story
    // as ready to build on while nothing of it may start.
    @Test
    void anIssueWaitingOutsideTheBoardCountsAsBlockedUntilItsWaitIsDone() {
        for (final Boolean done : new Boolean[] {false, null}) {
            issues.clear();
            issues.add(waitingOn("own#1", done));
            assertThat(story().blocked())
                    .as("an issue whose wait is %s", done == null ? "not read" : "open")
                    .isEqualTo(1);
        }
        issues.clear();
        issues.add(waitingOn("own#1", true));
        final OpenWork free = story();

        assertThat(free.blocked()).as("its wait done").isZero();
        assertThat(count(free.states(), "Backlog")).as("counted under its state").isEqualTo(1);
    }

    private static Issue waitingOn(final String id, final @Nullable Boolean done) {
        return new Issue(
                id,
                "own",
                "An issue",
                "Backlog",
                "S",
                List.of(),
                null,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                null,
                List.of(),
                List.of(new Wait("other-org/elsewhere#7", done, null)));
    }

    // A review issue counted as work to build would hide what waits for the person.
    @Test
    void anIssueInTheStateAWorkRuleEndsInWaitsForReviewAndIsNotLeftToBuild() {
        issue("own#1", "Review", "M", null, null);
        issue("own#2", "Backlog", "M", null, null);
        issue("own#3", "Finished", "M", null, null);

        final OpenWork open = story();

        assertThat(open.review()).as("the issue in Review waits for the person").isEqualTo(1);
        assertThat(open.left()).as("only the Backlog issue is left to build").isEqualTo(1);
        assertThat(open.sizes())
                .as("the sizes of what is left")
                .containsExactly(new OpenWork.Count("M", 1));
    }

    // Minutes from the wrong source would promise a story sooner or later than its record says.
    @Test
    void minutesComeFromTheEstimateThenTheRepositoryMedianThenTheMedianOfEveryRepository() {
        issue("own#1", "Backlog", "S", 20.0, null);
        issue("own#2", "Backlog", "S", null, null);
        issue("other#3", "Backlog", "M", null, null);
        issue("own#4", "Backlog", "L", null, null);
        issue("own#10", "Finished", "S", null, 10.0);
        issue("own#11", "Finished", "S", null, 30.0);
        issue("other#12", "Finished", "S", null, 100.0);
        issue("own#13", "Finished", "M", null, 40.0);
        issue("own#14", "Rejected", "M", null, 400.0);

        final Map<String, Double> minutes = OpenWork.minutesLeft(board());

        assertThat(minutes)
                .as("own#1 its estimate; own#2 the S median of own; other#3 the M median of all")
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("own#1", 20.0, "own#2", 20.0, "other#3", 40.0));
        final OpenWork open = new OpenWork.Counter(board()).board();
        assertThat(open.minutes()).as("the sum of the three with minutes").isEqualTo(80.0);
        assertThat(open.unestimated()).as("the L issue has no expectation").isEqualTo(1);
    }

    // Work that took longer than expected must not count as negative time left.
    @Test
    void theMinutesAnIssuesWorkTookAreTakenOffItsExpectationNeverBelowZero() {
        issue("own#1", "Implementing", "S", 30.0, 12.0);
        issue("own#2", "Implementing", "S", 30.0, 45.0);

        assertThat(OpenWork.minutesLeft(board()))
                .as("18 minutes left of 30, and none of an overrun")
                .containsExactlyInAnyOrderEntriesOf(Map.of("own#1", 18.0, "own#2", 0.0));
    }

    // A story told done while its sub-issues are open, or one counted whole while the board lacks
    // some of its issues, reads as finished with work left.
    @Test
    void aStoryCountsItsSubIssuesOnceAndSaysNothingWhileTheBoardLacksSomeOfItsIssues() {
        issue("own#1", "Review", "M", null, null);
        issues.add(sub("own#2", "own#1", "Backlog"));
        issues.add(sub("own#3", "own#1", "Implementing"));

        final OpenWork open = java.util.Objects.requireNonNull(story(List.of("own#1"), true));

        assertThat(open.left()).as("both open sub-issues are left to build").isEqualTo(2);
        assertThat(open.review()).as("the parent waits for review").isEqualTo(1);
        assertThat(java.util.Objects.requireNonNull(story(List.of("own#1", "own#2"), true)).left())
                .as("a sub-issue the story also names counts once")
                .isEqualTo(2);
        assertThat(story(List.of("own#1"), false))
                .as("nothing told for a story the board does not hold whole")
                .isNull();
    }

    // Two of the largest figures summed as one double would turn the median into infinity.
    @Test
    void theMedianOfTheLargestFiguresStaysFinite() {
        issue("own#1", "Backlog", "S", null, null);
        issue("own#10", "Finished", "S", null, Double.MAX_VALUE);
        issue("own#11", "Finished", "S", null, Double.MAX_VALUE);

        assertThat(OpenWork.minutesLeft(board()).get("own#1"))
                .as("the median of two of the largest doubles")
                .isEqualTo(Double.MAX_VALUE);
    }

    private OpenWork story() {
        return java.util.Objects.requireNonNull(
                story(issues.stream().map(Issue::id).toList(), true));
    }

    private @Nullable OpenWork story(final List<String> ids, final boolean whole) {
        final Board board = board(ids, whole);
        return new OpenWork.Counter(board).story(board.stories().get(0));
    }

    private Board board() {
        return board(issues.stream().map(Issue::id).toList(), true);
    }

    private Board board(final List<String> ids, final boolean whole) {
        return new Board(
                new Revision("r1"),
                WORKFLOW,
                List.of(
                        new Story(
                                "own#100", "A story", "Backlog", false, "High", null, ids, "", null,
                                whole)),
                List.copyOf(issues),
                new Work(Map.of("Implementing", rule()), List.of()),
                List.of("own", "other"),
                List.of(),
                null);
    }

    private static WorkRule rule() {
        return new WorkRule(
                List.of("Backlog"),
                "Review",
                null,
                null,
                WorkRule.Per.STORY,
                List.of("UNATTENDED"),
                "Build {story}",
                Map.of("codex", "gpt"));
    }

    private Issue issue(
            final String id,
            final String state,
            final String size,
            final @Nullable Double estimate,
            final @Nullable Double minutes,
            final String... blockedBy) {
        final Issue issue =
                new Issue(
                        id,
                        id.substring(0, id.indexOf('#')),
                        "An issue",
                        state,
                        size,
                        List.of(blockedBy),
                        null,
                        null,
                        "",
                        Worker.AGENT,
                        Risk.ORDINARY,
                        estimate,
                        minutes);
        issues.add(issue);
        return issue;
    }

    private static Issue sub(final String id, final String parent, final String state) {
        return new Issue(
                id,
                "own",
                "A sub-issue",
                state,
                "S",
                List.of(),
                parent,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null);
    }

    private static int count(final List<OpenWork.Count> counts, final String name) {
        return counts.stream()
                .filter(count -> count.name().equals(name))
                .mapToInt(OpenWork.Count::issues)
                .findFirst()
                .orElseThrow();
    }
}
