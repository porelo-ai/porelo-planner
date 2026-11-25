package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IssuePlaceTest {

    private static final String OFFER = "00000000-0000-0000-0000-000000000002";

    private static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void anAnswerKeptCutsItsPointsToAHundredAndCountsThemAll() {
        // Harm: a kept answer that says fewer points are open than were, so the issue is taken
        // for answered while points stand open.
        final IssuePlace kept = IssuePlace.atGate("task", OFFER).read(answer(101));

        assertThat(
                        List.of(
                                kept.last().points().size(),
                                kept.last().pointsTotal(),
                                kept.last().points().getLast().thread()))
                .as("the first hundred in Porelo's order, and the count of all")
                .containsExactly(100, 101, "t99");
    }

    @Test
    void aKeptAnswerWhoseCountDoesNotFitItsPointsOrThatWasIncompleteIsRefused() {
        // Harm: a record changed on disk that claims fewer open points than it keeps, more than a
        // hundred, or a partial read standing as what is open.
        final IssuePlace.Review two = IssuePlace.Review.of(answer(2));
        for (final int total : new int[] {1, 3}) {
            assertThatThrownBy(
                            () ->
                                    new IssuePlace.Review(
                                            two.head(),
                                            two.decision(),
                                            null,
                                            null,
                                            SINCE,
                                            two.points(),
                                            total,
                                            0,
                                            0,
                                            "v"))
                    .as("two points kept with a count of " + total)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        final IssuePlace.Review hundred = IssuePlace.Review.of(answer(100));
        assertThatThrownBy(
                        () ->
                                new IssuePlace.Review(
                                        hundred.head(),
                                        hundred.decision(),
                                        null,
                                        null,
                                        SINCE,
                                        answer(101).points(),
                                        101,
                                        0,
                                        0,
                                        "v"))
                .as("more than a hundred points kept")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                IssuePlace.Review.of(
                                        new ReviewOpen(
                                                null,
                                                ReviewOpen.Decision.OPEN,
                                                null,
                                                null,
                                                null,
                                                List.of(),
                                                0,
                                                0,
                                                false,
                                                "the forge did not answer",
                                                "v")))
                .as("an incomplete answer")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAnswerKeptAfterItsPushWasForwardedNamesTheBranchItsCommitLandedOn() {
        // Harm: an answer of a forwarded push, as Porelo gives it with the branch its commit
        // landed on, is refused, so the place is never read as forwarded; or a merged commit that
        // is no commit, or a head no branch can be, is kept.
        final ReviewOpen forwarded =
                new ReviewOpen(
                        "a".repeat(40),
                        ReviewOpen.Decision.APPROVED,
                        "f".repeat(40),
                        "main",
                        SINCE,
                        List.of(),
                        0,
                        0,
                        true,
                        null,
                        "v");

        assertThat(IssuePlace.atGate("task", OFFER).read(forwarded).last().mergedHead())
                .as("the branch kept")
                .isEqualTo("main");
        for (final String[] merged :
                new String[][] {{"main", "main"}, {"f".repeat(40), "main..next"}}) {
            assertThatThrownBy(
                            () ->
                                    new IssuePlace.Review(
                                            "a".repeat(40),
                                            ReviewOpen.Decision.APPROVED,
                                            merged[0],
                                            merged[1],
                                            SINCE,
                                            List.of(),
                                            0,
                                            0,
                                            0,
                                            "v"))
                    .as("merged commit " + merged[0] + " on " + merged[1])
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anOpenOrApprovedAnswerWithoutItsDecisionsTimeIsKeptAndNoOtherIs() {
        // Harm: an open or approved pull request, whose forge says no time, is refused as no
        // answer, so its issue stops under Needs you; or an ended decision without its time is
        // trusted.
        for (final ReviewOpen.Decision decision : ReviewOpen.Decision.values()) {
            final boolean undated =
                    decision == ReviewOpen.Decision.OPEN
                            || decision == ReviewOpen.Decision.APPROVED;
            final boolean merged =
                    decision == ReviewOpen.Decision.MERGED
                            || decision == ReviewOpen.Decision.LANDED;
            final java.util.function.Supplier<ReviewOpen> untimed =
                    () ->
                            new ReviewOpen(
                                    "a".repeat(40),
                                    decision,
                                    merged ? "e".repeat(40) : null,
                                    merged ? "main" : null,
                                    null,
                                    List.of(),
                                    0,
                                    0,
                                    true,
                                    null,
                                    "v");
            if (undated) {
                assertThat(IssuePlace.Review.of(untimed.get()).decisionSince())
                        .as("kept without a time: " + decision)
                        .isNull();
            } else {
                assertThatThrownBy(untimed::get)
                        .as("refused without its time: " + decision)
                        .hasMessageContaining("says when its decision began");
            }
        }
    }

    @Test
    void aPlaceCountsEachAuthorRunOnceAndRefusesAnyOtherList() {
        // Harm: a resent end or a record changed on disk spending an author's rounds twice, or
        // naming a run that is no offer.
        final IssuePlace place = IssuePlace.atGate("task", OFFER);
        assertThat(place.usedBy(OFFER)).as("the same run counted once").isEqualTo(place);
        for (final List<String> runs :
                List.of(
                        List.of(OFFER, OFFER),
                        List.of("not-an-offer"),
                        List.<String>of(),
                        IntStream.range(0, IssuePlace.MOST_RUNS + 1)
                                .mapToObj(i -> String.format("00000000-0000-0000-0000-%012d", i))
                                .toList())) {
            assertThatThrownBy(
                            () ->
                                    new IssuePlace(
                                            RunPlace.gate(null, "task"),
                                            RunRole.AUTHOR,
                                            "task",
                                            runs,
                                            null))
                    .as("author runs " + runs.size())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aGatePushNamingTheTasksOwnRepositoryIsItsOwnPushAndAnyOtherIsNot() {
        // Harm: Porelo's default place taken for another, stopping the issue for nothing; or a
        // push of another repository taken for the issue's own.
        final IssuePlace place = IssuePlace.atGate("task", OFFER);
        assertThat(place.same(RunPlace.gate("alpha", "task"), "alpha"))
                .as("its own repository named")
                .isTrue();
        assertThat(place.same(RunPlace.gate("beta", "task"), "alpha"))
                .as("another repository")
                .isFalse();
        assertThat(place.same(RunPlace.gate(null, "task-2"), "alpha")).as("another task").isFalse();
    }

    @Test
    void aPlaceWaitsUntilAnAnswerTakesItsWorkAway() {
        // Harm: an issue held at a push the gate forwarded, or let go while its push still waits.
        final IssuePlace place = IssuePlace.atGate("task", OFFER);
        assertThat(place.open()).as("no answer read yet").isTrue();
        assertThat(place.read(answer(1)).open()).as("returned with points").isTrue();
        for (final ReviewOpen.Decision gone :
                List.of(
                        ReviewOpen.Decision.APPROVED,
                        ReviewOpen.Decision.LANDED,
                        ReviewOpen.Decision.MERGED,
                        ReviewOpen.Decision.CLOSED)) {
            assertThat(
                            place.read(
                                            new ReviewOpen(
                                                    null, gone, null, null, SINCE, List.of(), 0, 0,
                                                    true, null, "v"))
                                    .open())
                    .as("the work no longer waits after " + gone)
                    .isFalse();
        }
    }

    private static ReviewOpen answer(final int points) {
        return new ReviewOpen(
                "0123456789abcdef0123456789abcdef01234567",
                ReviewOpen.Decision.RETURNED,
                null,
                null,
                SINCE,
                IntStream.range(0, points)
                        .mapToObj(
                                i ->
                                        new ReviewOpen.Point(
                                                "t" + i, null, null, "owner", SINCE.plusSeconds(i)))
                        .toList(),
                0,
                0,
                true,
                null,
                "v" + points);
    }
}
