package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubExceptions;
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

    /** Reads that GitHub refuses on its rate limit, naming a wait of two minutes. */
    private static ProjectQueries rateLimited() {
        return (ProjectQueries)
                java.lang.reflect.Proxy.newProxyInstance(
                        ProjectQueries.class.getClassLoader(),
                        new Class<?>[] {ProjectQueries.class},
                        (proxy, method, args) -> {
                            throw GitHubExceptions.rateLimited(
                                    403, "API rate limit exceeded", Duration.ofMinutes(2));
                        });
    }

    @Test
    void aRateLimitTheLookForChangesMeetsHoldsEveryLook() {
        // Harm: the look for changes, which never waits for GitHub's rate limit, sent again and
        // again while it is spent, and every board not told it is the one read before.
        assertThatThrownBy(() -> pace.watching(rateLimited()).projectFields("t", "o", 1, null))
                .isInstanceOf(GitHubException.class);

        assertThat(pace.hold())
                .as("the hold after the look's refusal")
                .isEqualTo("GitHub's rate limit is spent; it reads again in 120 s");
    }

    @Test
    void aRateLimitABoardReaderMeetsHoldsNothingByItself() {
        // Harm: every other project held for GitHub's wait while the reader waits it out and goes
        // on answered; the reader holds the pace only when its read fails.
        assertThatThrownBy(() -> pace.answering(rateLimited()).projectFields("t", "o", 1, null))
                .isInstanceOf(GitHubException.class);

        assertThat(pace.hold()).as("the hold after the reader's refusal").isNull();
    }
}
