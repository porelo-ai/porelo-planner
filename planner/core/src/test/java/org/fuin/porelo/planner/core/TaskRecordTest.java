package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.fuin.porelo.planner.core.TaskRecord.Call;
import org.fuin.porelo.planner.core.TaskRecord.End;
import org.junit.jupiter.api.Test;

/** What a task record refuses before any time is read from it. */
class TaskRecordTest {

    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");

    // A negative duration would take minutes off an issue's sample.
    @Test
    void aTaskThatEndsBeforeItStartsIsRefused() {
        assertThatThrownBy(
                        () ->
                                new TaskRecord(
                                        "t1",
                                        "example",
                                        null,
                                        null,
                                        T0,
                                        T0.minusSeconds(1),
                                        End.DONE,
                                        List.of()))
                .as("refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("t1");
    }

    // A record that says it ended but not how, or how but not when, cannot say whether it runs.
    @Test
    void anEndTimeWithoutHowItEndedIsRefusedAndTheOtherWayRound() {
        assertThatThrownBy(
                        () -> new TaskRecord("t1", "example", null, null, T0, T0, null, List.of()))
                .as("an end time alone refused")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new TaskRecord(
                                        "t1", "example", null, null, T0, null, End.DONE, List.of()))
                .as("how it ended alone refused")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // A call with a negative duration would take time off its issue.
    @Test
    void aCallThatEndsBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> new Call("EX1", T0, T0.minusSeconds(1)))
                .as("refused")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
