package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** A followed project is polled at the wait its store names, and a person's reload still asks. */
class ChangesRegistryPaceTest {

    private final AtomicInteger polls = new AtomicInteger();

    private final AtomicInteger refreshes = new AtomicInteger();

    @Test
    void aProbedStoreIsPolledAtItsOwnWaitThroughItsPollAndAReloadStillRefreshes() throws Exception {
        // Harm: a GitHub project's changes reach the window only after the long Git interval, a
        // store holding off GitHub is asked at every poll anyway, or the person's reload is
        // answered from the board kept without asking.
        final Projects projects = new Projects((project, repository, branch) -> store(true), 8);
        try (ChangesRegistry registry =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Duration.ofMillis(20),
                        Executors.newSingleThreadScheduledExecutor())) {
            projects.put("alpha", "one", null);
            final Changes.Subscription one = registry.subscribe("alpha");
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (polls.get() < 3 && System.nanoTime() < end) {
                Thread.sleep(10);
            }
            assertThat(polls.get()).as("polls at the store's wait").isGreaterThanOrEqualTo(3);
            assertThat(refreshes.get()).as("refreshes on the schedule").isZero();

            registry.refresh("alpha");

            assertThat(refreshes.get()).as("refreshes of the person's reload").isEqualTo(1);
            one.close();
        }
    }

    @Test
    void aFetchedStoreKeepsTheUsualInterval() throws Exception {
        // Harm: every Git project fetched every few seconds, as often as a GitHub one is probed.
        final Projects projects = new Projects((project, repository, branch) -> store(false), 8);
        try (ChangesRegistry registry =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Duration.ofMillis(20),
                        Executors.newSingleThreadScheduledExecutor())) {
            projects.put("alpha", "one", null);
            final Changes.Subscription one = registry.subscribe("alpha");
            Thread.sleep(300);

            assertThat(polls.get()).as("polls of a store that fetches, in 300 ms").isEqualTo(1);
            one.close();
        }
    }

    private PlanningStore store(final boolean probed) {
        return new PlanningStore() {
            @Override
            public Board board() {
                return boardAt("one");
            }

            @Override
            public Board refresh() {
                refreshes.incrementAndGet();
                return boardAt("one");
            }

            @Override
            public Board poll() {
                polls.incrementAndGet();
                return boardAt("one");
            }

            @Override
            public Duration pollDelay(final Duration fetched, final Duration probedInterval) {
                return probed ? probedInterval : fetched;
            }
        };
    }

    private static Board boardAt(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea"), List.of(), List.of()),
                List.of(),
                List.of(),
                new Work(Map.of(), List.of()),
                List.of());
    }
}
