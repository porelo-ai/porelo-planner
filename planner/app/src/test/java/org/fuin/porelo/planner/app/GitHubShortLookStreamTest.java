package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.ReadProgress;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.github.BoardReader;
import org.fuin.porelo.planner.store.github.GitHubStore;
import org.fuin.porelo.planner.store.github.ProjectConfig;
import org.fuin.porelo.planner.store.github.ProjectRef;
import org.fuin.porelo.planner.store.github.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** A short look for changes right after the window got its first board. */
class GitHubShortLookStreamTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef("example-org", 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    org.fuin.porelo.planner.store.ProjectRepository.named(List.of("porelo-store")),
                    Map.of(),
                    Map.of(),
                    "PVT_7");

    /** Until when the look after the first board runs, or null before the first board. */
    private volatile Instant lookUntil;

    @Test
    void aLookOfASecondRightAfterTheFirstBoardIsToldAtOnceAndEndsDone() throws Exception {
        // Harm: a look shorter than the stream's five-second step is never told, so the window's
        // bar shows no look; or one told late never ends with done; or a look waiting for a save
        // is told as a whole read that stands still.
        final GitHubStore store =
                new GitHubStore(
                        CONFIG,
                        () -> CONFIG,
                        GitHubStore.Parts.reading(
                                new BoardReader() {
                                    @Override
                                    public Snapshot read(final ProjectConfig config) {
                                        lookUntil = Instant.now().plusMillis(1500);
                                        return new Snapshot(
                                                new Board(
                                                        new Revision("r1"),
                                                        WORKFLOW,
                                                        List.of(),
                                                        List.of(),
                                                        new Work(Map.of(), List.of()),
                                                        org.fuin.porelo.planner.store
                                                                .ProjectRepository.named(
                                                                List.of("porelo-store"))),
                                                Map.of(),
                                                CONFIG);
                                    }

                                    @Override
                                    public Optional<ReadProgress> progress() {
                                        final Instant until = lookUntil;
                                        return until != null && Instant.now().isBefore(until)
                                                ? Optional.of(
                                                        new ReadProgress(
                                                                Instant.now(),
                                                                0,
                                                                1,
                                                                null,
                                                                "GitHub",
                                                                ReadProgress.Kind.LOOK,
                                                                ReadProgress.SAVE))
                                                : Optional.empty();
                                    }
                                },
                                states -> null),
                        origin -> "github_pat_look_0123456789",
                        Duration.ofMinutes(2));
        final Projects projects = new Projects((project, repository, branch) -> store);
        try (ChangesRegistry changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor())) {
            final MockMvc mvc =
                    MockMvcBuilders.standaloneSetup(
                                    new BoardController(projects, changes),
                                    new EventsController(
                                            changes, Duration.ofSeconds(15), new TaskHelds()))
                            .setControllerAdvice(new Problems(projects))
                            .build();
            mvc.perform(
                            put("/projects/alpha")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(SOURCE))
                    .andExpect(status().isNoContent());
            final MockHttpServletResponse events =
                    mvc.perform(get("/projects/alpha/events")).andReturn().getResponse();
            within(
                    () -> text(events).contains("event:board"),
                    Duration.ofSeconds(30),
                    "the first board",
                    events);

            within(
                    () -> text(events).contains("\"state\":\"reading\""),
                    Duration.ofSeconds(1),
                    "the look told at once",
                    events);
            assertThat(text(events))
                    .as("the look told as a look that waits for a save")
                    .contains("\"kind\":\"look\"", "\"waitsFor\":\"save\"");
            within(
                    () -> text(events).contains("\"state\":\"done\""),
                    Duration.ofSeconds(10),
                    "the look's end",
                    events);
            assertThat(text(events).indexOf("\"state\":\"reading\""))
                    .as("the look told after the first board")
                    .isGreaterThan(text(events).indexOf("event:board"));
        }
    }

    private static String text(final MockHttpServletResponse events) {
        try {
            return events.getContentAsString();
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void within(
            final java.util.function.BooleanSupplier condition,
            final Duration wait,
            final String what,
            final MockHttpServletResponse events)
            throws InterruptedException {
        final long end = System.nanoTime() + wait.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < end)
                    .as("%s within %s; the stream so far:%n%s", what, wait, text(events))
                    .isTrue();
            Thread.sleep(50);
        }
    }
}
