package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.core.TaskRecord;
import org.fuin.porelo.planner.core.WorkRun;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TaskHeldsTest {

    @Test
    void theNewestRecordOfATaskStands() {
        // Harm: the card shows a count an older record gave, after Porelo counted anew.
        final TaskHelds helds = new TaskHelds();

        assertThat(
                        helds.read(
                                "project",
                                List.of(record("box", 3), record("box", 4)),
                                working("box")))
                .as("a count read is a change")
                .isTrue();

        assertThat(helds.of("project", "box")).isEqualTo(4);
        assertThat(helds.read("project", List.of(record("box", 4)), working("box")))
                .as("the same count again is none")
                .isFalse();
        helds.read("project", List.of(record("box", null)), working("box"));
        assertThat(helds.of("project", "box"))
                .as("a record without a count makes it unknown, never 0")
                .isNull();
    }

    @Test
    void oneTaskNameInTwoProjectsIsTwoTasks() {
        // Harm: one project's card shows the held items of another project's task of that name.
        final TaskHelds helds = new TaskHelds();
        helds.read("project", List.of(record("box", 2)), working("box"));
        helds.read("other", List.of(record("box", 5)), working("box"));

        assertThat(List.of(helds.of("project", "box"), helds.of("other", "box")))
                .containsExactly(2, 5);
        assertThat(helds.in("other").of("box")).as("a project's counts").isEqualTo(5);
    }

    @Test
    void theBoundForgetsTheTaskReadFirst() {
        // Harm: the counts of every task ever read are kept while the planner runs.
        final TaskHelds helds = new TaskHelds();
        final List<TaskRecord> records = new ArrayList<>();
        final List<WorkRun.Started> asked = new ArrayList<>();
        for (int i = 0; i <= TaskHelds.MOST; i++) {
            records.add(record("box-" + i, 1));
            asked.add(new WorkRun.Started("box-" + i, null, true));
        }
        helds.read("project", records, asked);

        assertThat(helds.known("project", "box-0")).as("the first read, forgotten").isFalse();
        assertThat(helds.of("project", "box-1")).as("the next, kept").isEqualTo(1);
        assertThat(helds.of("project", "box-" + TaskHelds.MOST)).isEqualTo(1);
    }

    @Test
    void aTaskNeverReadAnswersNothingAndOneAskedForWithoutARecordIsKnownWithoutACount() {
        // Harm: a task never read shows 0 held items; or one Porelo has no record of is asked for
        // every minute.
        final TaskHelds helds = new TaskHelds();

        assertThat(helds.of("project", "box")).isNull();
        assertThat(helds.known("project", "box")).isFalse();

        assertThat(helds.read("project", List.of(), working("box")))
                .as("no count was read")
                .isFalse();
        assertThat(helds.known("project", "box")).as("asked for once").isTrue();
        assertThat(helds.of("project", "box")).isNull();

        helds.read("project", List.of(record("box", 2)), working("box"));
        helds.read("project", List.of(), working("box"));
        assertThat(helds.of("project", "box")).as("asking again keeps a count read").isEqualTo(2);
    }

    @Test
    void twoComponentsShareNothing() {
        // Harm: the counts live beside the application, so a second one, or a test, sees them.
        final TaskHelds one = new TaskHelds();
        final TaskHelds other = new TaskHelds();
        one.read("project", List.of(record("box", 2)), working("box"));

        assertThat(other.of("project", "box")).isNull();
        assertThat(other.known("project", "box")).isFalse();
    }

    @Test
    void onlyTheTasksAskedForAreKeptAndEachProjectHasItsOwnBound() {
        // Harm: records of tasks nobody asked for, or another project's many tasks, push out the
        // count a card shows.
        final TaskHelds helds = new TaskHelds();
        final List<TaskRecord> others = new ArrayList<>();
        for (int i = 0; i <= TaskHelds.MOST; i++) {
            others.add(record("other-" + i, 1));
        }
        helds.read("project", List.of(record("box", 2)), working("box"));
        helds.read("project", others, working("box"));
        assertThat(helds.of("project", "box")).as("an asked task stays").isEqualTo(2);
        assertThat(helds.known("project", "other-0")).as("no other is kept").isFalse();

        for (final String project : List.of("one", "two")) {
            final List<TaskRecord> records = new ArrayList<>();
            final List<WorkRun.Started> asked = new ArrayList<>();
            for (int i = 0; i < 600; i++) {
                records.add(record("box-" + i, 1));
                asked.add(new WorkRun.Started("box-" + i, null, true));
            }
            helds.read(project, records, asked);
        }
        assertThat(List.of(helds.known("one", "box-0"), helds.known("two", "box-0")))
                .as("600 tasks in each of two projects, all kept")
                .containsExactly(true, true);
    }

    @Test
    void anEndedTaskLastReadAtWorkIsDueOnceMore() {
        // Harm: a task's count read while it worked stays on its card after it ended, though
        // Porelo counted anew at its end.
        final TaskHelds helds = new TaskHelds();
        final WorkRun.Started ended = new WorkRun.Started("box", null, false);

        assertThat(helds.due("project", ended)).as("never read").isTrue();
        helds.read("project", List.of(record("box", 1)), working("box"));
        assertThat(helds.due("project", new WorkRun.Started("box", null, true)))
                .as("at work")
                .isTrue();
        assertThat(helds.due("project", ended)).as("ended, last read at work").isTrue();
        helds.read("project", List.of(record("box", 3)), List.of(ended));
        assertThat(helds.of("project", "box")).isEqualTo(3);
        assertThat(helds.due("project", ended)).as("read once after its end").isFalse();
    }

    private static List<WorkRun.Started> working(final String task) {
        return List.of(new WorkRun.Started(task, null, true));
    }

    private static TaskRecord record(final String task, final @Nullable Integer held) {
        return new TaskRecord(
                task,
                "project",
                "alpha",
                List.of("AA1"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                List.of(),
                org.fuin.porelo.planner.store.Role.Workspace.OWN,
                null,
                held);
    }
}
