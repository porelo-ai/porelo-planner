package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Guards the one line a call to the planner logs: it must name where a slow write spent its time,
 * and a read that reached nothing must not fill the log.
 */
class CallTimesTest {

    @Test
    void aWriteNamesItsLockWaitsPhasesAndForgeCallsInOneLine() {
        final CallTimes times = Objects.requireNonNull(CallTimes.open("moves"));
        CallTimes.waited("the work record", 7_000_000_000L);
        CallTimes.phase(
                "recheck",
                () -> {
                    CallTimes.forge("GitHubGraphQl.issueBoard", 200, 2_500_000_000L);
                    return null;
                });
        CallTimes.forge("GitHubGraphQl.setFieldValues", 502, 1_000_000L);
        CallTimes.forge("GitHubClient.updateIssue", 0, 2_000_000L);

        final String line = times.end();

        assertThat(line)
                .as(
                        "the call, each lock's wait, each phase and each forge call with status and time")
                .startsWith("moves took ")
                .contains("; waited for the work record 7000 ms")
                .contains("; recheck ")
                .contains("; GitHub 3 calls: GitHubGraphQl.issueBoard 200 2500 ms")
                .contains("GitHubGraphQl.setFieldValues 502 1 ms")
                .contains("GitHubClient.updateIssue no answer 2 ms");
    }

    @Test
    void aCallThatNeitherWroteNorReachedTheForgeSaysNothing() {
        final CallTimes times = Objects.requireNonNull(CallTimes.open("lockedView"));
        CallTimes.waited("the work record", 1_000_000L);

        assertThat(times.end())
                .as(
                        "a read of the board held, logged at every poll, would push the log's history out")
                .isNull();
    }

    @Test
    void aCallInsideAnotherAddsToTheOuterOne() {
        final CallTimes outer = Objects.requireNonNull(CallTimes.open("moves"));

        assertThat(CallTimes.open("inner")).as("no second times while one is open").isNull();
        CallTimes.forge("GitHubGraphQl.issueBoard", 200, 1_000_000L);

        assertThat(outer.end()).as("the inner call's forge call").contains("GitHub 1 calls");
    }

    @Test
    void nothingIsNotedOutsideACall() {
        CallTimes.forge("GitHubGraphQl.issueBoard", 200, 1_000_000L);
        final CallTimes times = Objects.requireNonNull(CallTimes.open("moves"));

        assertThat(times.end())
                .as("a forge call made before the call opened belongs to no call")
                .isNull();
    }

    @Test
    void aFailedPhaseIsNamedAndItsFailurePassesOn() {
        final CallTimes times = Objects.requireNonNull(CallTimes.open("moves"));

        assertThatThrownBy(
                        () ->
                                CallTimes.phase(
                                        "write",
                                        () -> {
                                            throw new IllegalStateException("GitHub refused");
                                        }))
                .as("the phase's own failure")
                .hasMessage("GitHub refused");
        assertThat(times.end()).as("the phase marked failed").contains("write ").contains("failed");
    }

    @Test
    void forgeCallsBeyondTheBoundAreCounted() {
        final CallTimes times = Objects.requireNonNull(CallTimes.open("moves"));
        for (int call = 0; call < CallTimes.MOST_CALLS + 3; call++) {
            CallTimes.forge("GitHubGraphQl.issueBoard", 200, 1_000_000L);
        }

        assertThat(times.end())
                .as(
                        "a save of many items keeps its line bounded and still says how many calls it made")
                .contains("GitHub " + (CallTimes.MOST_CALLS + 3) + " calls")
                .endsWith(", 3 more");
    }
}
