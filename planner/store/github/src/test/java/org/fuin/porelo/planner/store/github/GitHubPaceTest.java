package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The pace of a person's token: how long a spent rate limit holds, and what the board says. */
class GitHubPaceTest {

    private static final Duration PROBED = Duration.ofSeconds(30);

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-11-20T10:00:00Z"));

    private final GitHubPace pace = new GitHubPace(now::get);

    @Test
    void aWaitOfNothingHoldsAsLongAsAWaitGitHubDidNotName() {
        // Harm: GitHub's refusal with a reset already passed holds nothing, so the refusal is
        // answered without words and the planner asks again at once.
        pace.spent(Duration.ZERO);

        assertThat(pace.hold())
                .as("the hold after a wait of nothing")
                .isEqualTo("GitHub's rate limit is spent; it reads again in 60 s");
    }

    @Test
    void aWaitLongerThanAnHourHoldsAnHourAndNeverOverflows() {
        // Harm: a malformed wait holds every project of the person for days, or overflows and
        // stops the polls for good.
        pace.spent(Duration.ofSeconds(Long.MAX_VALUE));

        assertThat(pace.delay(PROBED)).as("the wait taken").isEqualTo(GitHubPace.MOST_WAIT);
    }

    @Test
    void aRefusalNotedIsAnsweredWithItsHoldThoughTheClockMovesOn() {
        // Harm: the hold ends between noting the refusal and reading its words, so the refusal
        // carries no words and reaches the window as "null".
        final AtomicReference<Instant> ticking =
                new AtomicReference<>(Instant.parse("2026-11-20T10:00:00Z"));
        final InstantSource jumping = () -> ticking.getAndUpdate(at -> at.plusSeconds(61));
        final GitHubPace jumpy = new GitHubPace(jumping);

        assertThat(jumpy.refused(Duration.ofSeconds(60)))
                .as("the words of the refusal")
                .isEqualTo("GitHub's rate limit is spent; it reads again in 60 s");
    }

    @Test
    void anAnswerWithRequestsLeftEndsTheHold() {
        // Harm: after a fresh token GitHub answers again, yet the board says the limit is spent
        // and the polls stay held until the old wait ends.
        pace.spent(Duration.ofMinutes(30));

        pace.seen(4999L, 5000L);

        assertThat(pace.hold()).as("the hold after an answer with requests left").isNull();
        assertThat(pace.faults()).as("the board's faults").isEmpty();
    }

    @Test
    void aSpentLimitIsNotAlsoToldAsLow() {
        // Harm: the board says the limit is spent and, at once, that it is only low.
        pace.seen(1L, 5000L);
        pace.spent(Duration.ofMinutes(2));

        assertThat(pace.faults())
                .as("the board's faults while spent")
                .singleElement()
                .satisfies(fault -> assertThat(fault).contains("rate limit is spent"));
    }
}
