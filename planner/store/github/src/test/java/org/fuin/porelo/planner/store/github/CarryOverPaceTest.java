package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.junit.jupiter.api.Test;

class CarryOverPaceTest {

    private static final Instant START = Instant.parse("2026-11-21T07:00:00Z");

    /** A clock that moves only when the pace sleeps, or the test moves it. */
    private static final class Clock implements InstantSource {
        Instant now = START;

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Clock clock = new Clock();
    private final List<Duration> slept = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private final CarryOverPace pace =
            new CarryOverPace(
                    clock,
                    wait -> {
                        slept.add(wait);
                        clock.now = clock.now.plus(wait);
                    },
                    lines::add);

    private void issue() {
        pace.call("making an issue in porelo", CarryOverPace.Kind.CREATE, () -> "made");
    }

    private static Duration total(final List<Duration> waits) {
        return waits.stream().reduce(Duration.ZERO, Duration::plus);
    }

    @Test
    void theIssueOverTheHoursShareWaitsUntilTheFirstIsAnHourOldAndSaysSo() {
        // Harm: a run of a real project refused by GitHub's hourly limit and stopped halfway.
        for (int i = 0; i < CarryOverPace.PER_HOUR; i++) {
            issue();
        }
        assertThat(lines).as("lines before the hour's share is spent").isEmpty();
        final Instant last = clock.now;
        issue();
        assertThat(clock.now)
                .as("when the issue over the hour's share is made")
                .isEqualTo(START.plus(Duration.ofHours(1)));
        assertThat(Duration.between(last, clock.now))
                .as("the wait before it")
                .isGreaterThan(Duration.ofMinutes(50));
        assertThat(lines).as("the lines said").hasSize(1);
        assertThat(lines.get(0))
                .as("the line")
                .contains("making an issue in porelo")
                .contains(String.valueOf(CarryOverPace.PER_HOUR))
                .contains("08:00:00Z");
    }

    @Test
    void twoIssuesOneAfterTheOtherStandASecondApart() {
        // Harm: a burst over GitHub's limit a minute, refused as a secondary rate limit.
        issue();
        final Instant first = clock.now;
        issue();
        assertThat(Duration.between(first, clock.now))
                .as("the time between two issues")
                .isEqualTo(CarryOverPace.SPACING);
        assertThat(lines).as("a short wait is not said").isEmpty();
    }

    @Test
    void aCallRefusedAsRateLimitedIsMadeAgainAfterAMinuteAndSaysSo() {
        // Harm: an unattended run stopped by one refusal it could have waited out.
        final AtomicInteger calls = new AtomicInteger();
        final String answer =
                pace.call(
                        "adding a sub-issue to porelo#3",
                        CarryOverPace.Kind.WRITE,
                        () -> {
                            if (calls.incrementAndGet() == 1) {
                                throw GitHubExceptions.rateLimited(
                                        403, "secondary rate limit", Duration.ofSeconds(30));
                            }
                            return "added";
                        });
        assertThat(answer).as("the answer of the call made again").isEqualTo("added");
        assertThat(calls).as("the calls made").hasValue(2);
        assertThat(slept).as("the waits").containsExactly(Duration.ofMinutes(1));
        assertThat(lines).as("the lines said").hasSize(1);
        assertThat(lines.get(0))
                .as("the line")
                .contains("adding a sub-issue to porelo#3")
                .contains("try 2")
                .contains("07:01:00Z")
                .doesNotContain("secondary rate limit");
    }

    @Test
    void aRefusalThatIsNoRateLimitIsThrownAtOnce() {
        // Harm: a missing right waited on for hours instead of named.
        final GitHubException refused = GitHubExceptions.refused(403, "Resource not accessible");
        assertThatThrownBy(
                        () ->
                                pace.call(
                                        "making an issue in porelo",
                                        CarryOverPace.Kind.CREATE,
                                        () -> {
                                            throw refused;
                                        }))
                .as("the refusal")
                .isSameAs(refused);
        assertThat(total(slept)).as("the time waited").isEqualTo(Duration.ZERO);
        assertThat(lines).as("the lines said").isEmpty();
    }

    @Test
    void refusalsInARowAreThrownAfterTheLastTry() {
        // Harm: a run that never ends while GitHub keeps refusing it.
        final AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                pace.call(
                                        "writing the body of porelo#3",
                                        CarryOverPace.Kind.WRITE,
                                        () -> {
                                            calls.incrementAndGet();
                                            throw GitHubExceptions.rateLimited(
                                                    429, "slow down", Duration.ofSeconds(1));
                                        }))
                .as("the last refusal")
                .isInstanceOf(GitHubException.class);
        assertThat(calls).as("the calls made").hasValue(CarryOverPace.TRIES);
        assertThat(slept).as("the waits").hasSize(CarryOverPace.TRIES - 1);
        assertThat(slept.get(1)).as("the second wait, doubled").isEqualTo(Duration.ofMinutes(2));
        assertThat(slept)
                .as("every wait")
                .allMatch(wait -> wait.compareTo(Duration.ofHours(1)) <= 0);
    }

    @Test
    void aWaitGitHubNamesAsFiveHoursIsHeldToOne() {
        // Harm: a run asleep for hours on one odd answer.
        final AtomicInteger calls = new AtomicInteger();
        pace.call(
                "making an issue in porelo",
                CarryOverPace.Kind.CREATE,
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw GitHubExceptions.rateLimited(403, "limit", Duration.ofHours(5));
                    }
                    return "made";
                });
        assertThat(slept).as("the waits").containsExactly(Duration.ofHours(1));
    }

    @Test
    void aWriteStandsASecondAfterTheLastWriteWithoutCountingAgainstTheHour() {
        // Harm: mutations in a burst GitHub refuses, or a run slowed for hours by writes that make
        // no content.
        for (int i = 0; i < CarryOverPace.PER_HOUR; i++) {
            pace.call("writing porelo#1", CarryOverPace.Kind.WRITE, () -> "set");
        }
        assertThat(clock.now)
                .as("the time after the writes, each a second after the last")
                .isEqualTo(START.plusSeconds(CarryOverPace.PER_HOUR - 1));
        final Instant after = clock.now;
        issue();
        assertThat(clock.now)
                .as("an issue after them, not held by the hour")
                .isEqualTo(after.plus(CarryOverPace.SPACING));
        final Instant read = clock.now;
        pace.call("reading porelo#1", CarryOverPace.Kind.READ, () -> "read");
        assertThat(clock.now).as("the time after a read").isEqualTo(read);
    }

    /** Answers every call; counts the issues and comments made. */
    private static final class Answers implements ProjectWrites {
        int made;

        @Override
        public org.fuin.porelo.forge.github.GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.fuin.porelo.forge.github.Batch<String> addProjectItems(
                final String token, final String projectId, final List<String> issueIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.fuin.porelo.forge.github.Batch<String> setFieldValues(
                final String token,
                final String projectId,
                final List<org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue> values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.fuin.porelo.forge.github.GitHubGraphQl.FieldOptions setFieldOptions(
                final String token,
                final String fieldId,
                final List<org.fuin.porelo.forge.github.GitHubGraphQl.NewOption> options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Created createIssue(
                final String token,
                final String owner,
                final String repository,
                final String title,
                final String body,
                final List<String> labels) {
            made++;
            return new Created("I_" + made, made, made, labels);
        }

        @Override
        public void addSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {}

        @Override
        public void removeSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {}

        @Override
        public void reprioritizeSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId,
                final Long afterId,
                final Long beforeId) {}

        @Override
        public void addBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {}

        @Override
        public void removeBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {}

        @Override
        public FoundPage issuesCreatedSince(
                final String token,
                final String owner,
                final String repository,
                final Instant since,
                final int page) {
            return new FoundPage(List.of(), false);
        }

        @Override
        public void updateIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final String title,
                final String body,
                final Opening opening) {}

        @Override
        public CommentsPage issueComments(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final int page) {
            return new CommentsPage(List.of(), false);
        }

        @Override
        public void createIssueComment(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final String body) {
            made++;
        }
    }

    @Test
    void theWrappedWritesMakeIssuesAndCommentsAtTheRunsPaceAndOtherWritesASecondApart() {
        // Harm: an issue or a comment left out of the pace, so a large run is refused by GitHub.
        final Answers answers = new Answers();
        final CarryOverPace slow =
                new CarryOverPace(
                        clock,
                        wait -> {
                            slept.add(wait);
                            clock.now = clock.now.plus(wait);
                        },
                        lines::add,
                        4);
        final ProjectWrites writes = slow.writes(answers);
        writes.createIssue("t", "o", "porelo", "A", "B", List.of());
        writes.createIssueComment("t", "o", "porelo", 1, "C");
        writes.updateIssue("t", "o", "porelo", 1, null, "D", null);
        writes.addSubIssue("t", "o", "porelo", 1, 2L);
        writes.addBlockedBy("t", "o", "porelo", 1, 3L);
        writes.issueComments("t", "o", "porelo", 1, 1);
        writes.issuesCreatedSince("t", "o", "porelo", START, 1);
        assertThat(answers.made).as("the issues and comments made").isEqualTo(2);
        assertThat(slept)
                .as("the waits: the comment at the pace, each other write a second after the last")
                .containsExactly(
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1));
    }

    @Test
    void aRefusalWhoseAnswerWasLostIsNeverMadeAgain() {
        // Harm: an issue made twice when GitHub took the call but its answer never came.
        final AtomicInteger calls = new AtomicInteger();
        final GitHubException lost = GitHubExceptions.unansweredRateLimited("timed out");
        assertThatThrownBy(
                        () ->
                                pace.call(
                                        "making an issue in porelo",
                                        CarryOverPace.Kind.CREATE,
                                        () -> {
                                            calls.incrementAndGet();
                                            throw lost;
                                        }))
                .as("the refusal")
                .isSameAs(lost);
        assertThat(calls).as("the calls made").hasValue(1);
    }

    @Test
    void aPaceOfFourAMinuteStandsIssuesFifteenSecondsApartAndSaysEachWait() {
        // Harm: a person who asked for a slower run getting GitHub's whole share.
        final CarryOverPace slow =
                new CarryOverPace(
                        clock,
                        wait -> {
                            slept.add(wait);
                            clock.now = clock.now.plus(wait);
                        },
                        lines::add,
                        4);
        slow.call("making an issue in porelo", CarryOverPace.Kind.CREATE, () -> "made");
        slow.call("making an issue in porelo", CarryOverPace.Kind.CREATE, () -> "made");
        assertThat(slept).as("the waits").containsExactly(Duration.ofSeconds(15));
        assertThat(lines)
                .as("the lines said")
                .singleElement()
                .asString()
                .contains("waiting 15 s before making an issue in porelo")
                .contains("07:00:15Z");
    }

    @Test
    void aPaceOutsideOneToSixtyAMinuteIsRefused() {
        // Harm: a pace over GitHub's limit taken.
        for (final int perMinute : new int[] {0, 61}) {
            assertThatThrownBy(() -> new CarryOverPace(clock, wait -> {}, lines::add, perMinute))
                    .as("a pace of %d a minute", perMinute)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
