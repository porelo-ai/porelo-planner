package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.core.ReportTaking;
import org.fuin.porelo.planner.core.TaskRecords;
import org.fuin.porelo.planner.core.TaskReports;
import org.fuin.porelo.planner.store.PlanningStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The reading of a project's reports, off the request threads and one at a time per project. */
class ReportReadingTest {

    private static final ReportTaking.Outcome NOTHING =
            new ReportTaking.Outcome(List.of(), List.of(), List.of(), List.of());

    private final ExecutorService reads = Executors.newVirtualThreadPerTaskExecutor();

    private final List<String> saved = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        reads.shutdownNow();
    }

    @Test
    void aReadAskedWhileOneRunsRunsOnceAfterItNeverBeside() throws Exception {
        // Harm: two reads of one project take the same report side by side, or a task's event
        // during a read is lost until the next minute.
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final AtomicInteger runs = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(2);
        final ReportReading reading =
                new ReportReading(
                        project -> {
                            most.accumulateAndGet(running.incrementAndGet(), Math::max);
                            runs.incrementAndGet();
                            entered.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            running.decrementAndGet();
                            done.countDown();
                            return NOTHING;
                        },
                        Set::of,
                        saved::add,
                        reads,
                        null);

        reading.soon("alpha");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        reading.soon("alpha");
        reading.soon("alpha");
        reading.soon("alpha");
        release.countDown();

        assertThat(done.await(5, TimeUnit.SECONDS)).as("a second read").isTrue();
        reads.shutdown();
        assertThat(reads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runs.get()).as("the asks while one ran made one more").isEqualTo(2);
        assertThat(most.get()).as("never two at once").isEqualTo(1);
    }

    @Test
    void aSavedStoryIsToldToTheProjectsWindowsAndAReadWithoutNoneIsNot() throws Exception {
        // Harm: a story saved from a report the window never shows until its next poll, or every
        // read announcing a board that did not change.
        final ReportReading reading =
                new ReportReading(
                        project ->
                                project.equals("alpha")
                                        ? new ReportTaking.Outcome(
                                                List.of("porelo-alpha_t#1 ST1"),
                                                List.of(),
                                                List.of(),
                                                List.of())
                                        : NOTHING,
                        () -> Set.of("alpha", "beta"),
                        saved::add,
                        reads,
                        null);

        reading.tick();
        reads.shutdown();
        assertThat(reads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(saved).containsExactly("alpha");
    }

    @Test
    void aFailedReadIsCaughtAndTheNextReadRuns() throws Exception {
        // Harm: one failure of Porelo ends the reading of the project for the planner's life.
        final AtomicInteger runs = new AtomicInteger();
        final ReportReading reading =
                new ReportReading(
                        project -> {
                            if (runs.incrementAndGet() == 1) {
                                throw new TaskRecords.Unanswered("Porelo gave no answer");
                            }
                            return NOTHING;
                        },
                        Set::of,
                        saved::add,
                        reads,
                        null);

        reading.soon("alpha");
        awaitIdle(reading, "alpha");
        reading.soon("alpha");
        reads.shutdown();
        assertThat(reads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void aProjectThePlannerWasNotGivenIsNotAFailureAndAGivenUpOneIsForgotten() throws Exception {
        // Harm: a retired project logged as a failed read and kept as failing for the planner's
        // life.
        final Projects projects =
                new Projects(
                        (p, r, b) ->
                                (PlanningStore)
                                        java.lang.reflect.Proxy.newProxyInstance(
                                                PlanningStore.class.getClassLoader(),
                                                new Class<?>[] {PlanningStore.class},
                                                (proxy, method, args) -> null));
        final ChangesRegistry changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        final TaskRecords records =
                (project, since) -> {
                    throw new TaskRecords.Unanswered("Porelo gave no answer");
                };
        final TaskReports taskReports =
                (container, after) -> {
                    throw new TaskRecords.Unanswered("Porelo gave no answer");
                };
        try (ReportReading reading =
                new ReportReading(projects, changes, records, taskReports, Duration.ofHours(1))) {
            reading.soon("ghost");
            awaitIdle(reading, "ghost");
            assertThat(reading.failing("ghost")).as("no failure of an unknown project").isFalse();

            reading.failed("alpha", "Unanswered: x");
            projects.put("alpha", "https://example.org/store.git", null);
            projects.forgetAll();

            assertThat(reading.failing("alpha")).as("forgotten with its project").isFalse();
        } finally {
            changes.close();
        }
    }

    private static void awaitIdle(final ReportReading reading, final String project)
            throws InterruptedException {
        for (int i = 0; i < 500 && reading.busy(project); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(reading.busy(project)).as("the first read ended").isFalse();
    }
}
