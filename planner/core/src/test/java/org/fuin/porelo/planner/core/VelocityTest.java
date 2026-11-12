package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.core.TaskRecord.Call;
import org.fuin.porelo.planner.core.TaskRecord.End;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The velocity read from Porelo's records of the tasks that worked a repository's issues. */
class VelocityTest {

    private static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Implementing", "Review", "Finished", "Rejected"),
                    List.of(),
                    List.of("Finished", "Rejected"));

    private final List<Issue> issues = new ArrayList<>();

    // An estimate given from a whole task's time must be that time, not less or more.
    @Test
    void aTwoHourTaskOnAFinishedIssueGivesItsHundredAndTwentyMinutes() {
        issue("EX1", "own", "M", "Finished");

        assertThat(velocity(List.of(task("t1", "EX1", 0, 120, List.of())), "own", "M"))
                .as("the task's two hours")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(120.0, within(1e-9)));
    }

    // Two issues worked by one task must each be given their own sub-agent's time, not half each.
    @Test
    void theCallsOfTwoIssuesGiveEachItsSpanAndAnEvenShareOfTheRest() {
        issue("EX1", "own", "M", "Finished");
        issue("EX2", "own", "S", "Finished");
        final TaskRecord task =
                task("t1", null, 0, 100, List.of(call("EX1", 10, 60), call("EX2", 60, 90)));

        assertThat(velocity(List.of(task), "own", "M"))
                .as("EX1's 50 minutes and half of the 20 outside every sub-agent")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(60.0, within(1e-9)));
        assertThat(velocity(List.of(task), "own", "S"))
                .as("EX2's 30 minutes and the other half")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(40.0, within(1e-9)));
    }

    // Idle time between an issue's sub-agent runs must not be counted to that issue alone.
    @Test
    void anIssuesCallsWithAGapBetweenThemCountOnlyTheCalls() {
        issue("EX1", "own", "M", "Finished");
        issue("EX2", "own", "M", "Review");
        final TaskRecord task =
                task(
                        "t1",
                        null,
                        0,
                        100,
                        List.of(call("EX1", 0, 10), call("EX2", 10, 90), call("EX1", 90, 100)));

        assertThat(velocity(List.of(task), "own", "M"))
                .as("EX1's two calls of ten minutes, nothing of EX2's eighty between them")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(20.0, within(1e-9)));
    }

    // Parallel sub-agents must not make a task give more minutes than it lasted.
    @Test
    void twoSubAgentsInParallelOverAWholeTaskShareItsTime() {
        issue("EX1", "own", "M", "Finished");
        issue("EX2", "own", "M", "Finished");
        final TaskRecord task =
                task("t1", null, 0, 120, List.of(call("EX1", 0, 120), call("EX2", 0, 120)));

        assertThat(velocity(List.of(task), "own", "M"))
                .as("each issue half of the task's 120 minutes")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(60.0, within(1e-9)));
    }

    // A call Porelo timed outside its task must not add time the task never had.
    @Test
    void aCallOutsideItsTaskIsCutToTheTask() {
        issue("EX1", "own", "M", "Finished");
        issue("EX2", "own", "S", "Finished");
        final TaskRecord task =
                task("t1", null, 10, 40, List.of(call("EX1", 0, 20), call("EX2", 20, 30)));

        assertThat(velocity(List.of(task), "own", "M"))
                .as("EX1's ten minutes inside the task and half of the ten outside every call")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(15.0, within(1e-9)));
    }

    // A call without an end must not count its issue any time, nor end the task's count.
    @Test
    void aCallWithoutAnEndGivesItsIssueOnlyItsShare() {
        issue("EX1", "own", "M", "Finished");
        issue("EX2", "own", "S", "Finished");
        final TaskRecord task =
                task(
                        "t1",
                        null,
                        0,
                        60,
                        List.of(
                                call("EX1", 0, 40),
                                new Call("EX2", T0.plusSeconds(40 * 60), null)));

        assertThat(velocity(List.of(task), "own", "M"))
                .as("EX1's 40 minutes and half of the 20 outside its call")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(50.0, within(1e-9)));
        assertThat(velocity(List.of(task), "own", "S"))
                .as("EX2's half of the 20 minutes alone")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(10.0, within(1e-9)));
    }

    // A task whose issue nobody can say must not be counted to some issue.
    @Test
    void aTaskWithNoWorkAndNoCallsIssueGivesNothing() {
        issue("EX1", "own", "M", "Finished");

        assertThat(
                        velocity(
                                List.of(task("t1", null, 0, 60, List.of(call(null, 0, 60)))),
                                "own",
                                "M"))
                .as("no sample")
                .isEmpty();
    }

    // Work that is not finished, rejected, another repository's or another size's must not move
    // an estimate.
    @Test
    void onlyAFinishedIssueOfTheRepositoryAndSizeGivesASample() {
        issue("EX1", "own", "M", "Review");
        issue("EX2", "own", "M", "Rejected");
        issue("EX3", "other", "M", "Finished");
        issue("EX4", "own", "S", "Finished");
        final List<TaskRecord> records =
                List.of(
                        task("t1", "EX1", 0, 10, List.of()),
                        task("t2", "EX2", 0, 10, List.of()),
                        task("t3", "EX3", 0, 10, List.of()),
                        task("t4", "EX4", 0, 10, List.of()));

        assertThat(velocity(records, "own", "M")).as("no sample").isEmpty();
    }

    // A task with no time between its start and end measured nothing, and a zero would pull every
    // estimate down.
    @Test
    void anIssueThatTookNoTimeGivesNoSample() {
        issue("EX1", "own", "M", "Finished");

        assertThat(velocity(List.of(task("t1", "EX1", 5, 5, List.of())), "own", "M"))
                .as("no sample")
                .isEmpty();
    }

    // A task still running has no time yet; counting part of it would understate the issue.
    @Test
    void aRunningTaskGivesNothing() {
        issue("EX1", "own", "M", "Finished");
        final TaskRecord running =
                new TaskRecord("t1", "example", "own", "EX1", T0, null, null, List.of());

        assertThat(velocity(List.of(running), "own", "M")).as("no sample").isEmpty();
    }

    // An issue worked in two tasks, a failed first attempt among them, cost both.
    @Test
    void twoTasksOnOneIssueAddUpHoweverTheFirstEnded() {
        issue("EX1", "own", "M", "Finished");
        final List<TaskRecord> records =
                List.of(
                        new TaskRecord(
                                "t1",
                                "example",
                                "own",
                                "EX1",
                                T0,
                                T0.plusSeconds(30 * 60),
                                End.FAILED,
                                List.of()),
                        task("t2", "EX1", 60, 100, List.of()));

        assertThat(velocity(records, "own", "M"))
                .as("30 and 40 minutes")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(70.0, within(1e-9)));
    }

    // One task given twice must not be counted twice.
    @Test
    void aTaskRecordedTwiceIsRefused() {
        issue("EX1", "own", "M", "Finished");
        final TaskRecord task = task("t1", "EX1", 0, 10, List.of());

        assertThatThrownBy(() -> velocity(List.of(task, task), "own", "M"))
                .as("refused, naming the task")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("t1");
    }

    // The median, not the mean, so one runaway task does not move every estimate.
    @Test
    void theMedianOfAnEvenNumberOfSamplesIsTheMeanOfTheMiddleTwo() {
        issue("EX1", "own", "M", "Finished");
        issue("EX2", "own", "M", "Finished");
        issue("EX3", "own", "M", "Finished");
        issue("EX4", "own", "M", "Finished");
        final List<TaskRecord> records =
                List.of(
                        task("t1", "EX1", 0, 10, List.of()),
                        task("t2", "EX2", 0, 20, List.of()),
                        task("t3", "EX3", 0, 40, List.of()),
                        task("t4", "EX4", 0, 900, List.of()));

        assertThat(velocity(records, "own", "M"))
                .as("between 20 and 40")
                .hasValueSatisfying(minutes -> assertThat(minutes).isCloseTo(30.0, within(1e-9)));
    }

    private void issue(
            final String id, final String repository, final String size, final String state) {
        issues.add(new Issue(id, repository, id, state, size, List.of(), null, null, ""));
    }

    private java.util.Optional<Double> velocity(
            final List<TaskRecord> records, final String repository, final String size) {
        final Board board =
                new Board(
                        new Revision("r1"),
                        WORKFLOW,
                        List.of(),
                        issues,
                        new Work(Map.of(), List.of()),
                        List.of("own", "other"));
        return Velocity.median(records, board, repository, size);
    }

    /** A task that ran from one minute to another after {@link #T0} and finished. */
    private static TaskRecord task(
            final String id,
            final @Nullable String issue,
            final int from,
            final int to,
            final List<Call> calls) {
        return new TaskRecord(
                id,
                "example",
                "own",
                issue,
                T0.plusSeconds(from * 60L),
                T0.plusSeconds(to * 60L),
                End.DONE,
                calls);
    }

    private static Call call(final @Nullable String issue, final int from, final int to) {
        return new Call(issue, T0.plusSeconds(from * 60L), T0.plusSeconds(to * 60L));
    }
}
