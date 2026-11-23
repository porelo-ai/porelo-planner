package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.ReadProgress;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
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

/** A whole read of a GitHub board that fails after the window got its first board. */
class GitHubFailedReadStreamTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Backlog", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef("example-org", 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of("porelo-store"),
                    Map.of(),
                    Map.of(),
                    "PVT_7");

    private final AtomicInteger reads = new AtomicInteger();

    private volatile ReadProgress progress;

    private static Snapshot snapshot() {
        return new Snapshot(
                new Board(
                        new Revision("r1"),
                        WORKFLOW,
                        List.of(),
                        List.of(
                                new Issue(
                                        "porelo-store#1",
                                        "porelo-store",
                                        "One",
                                        "Idea",
                                        "S",
                                        List.of(),
                                        null,
                                        null,
                                        "# One")),
                        new Work(Map.of(), List.of()),
                        List.of("porelo-store")),
                Map.of(),
                CONFIG);
    }

    @Test
    void aWholeReadThatFailsAfterTheFirstBoardIsToldAsFailedAndTheStreamStays() throws Exception {
        // Harm: a read that fails in the background ends without a word, so the window's bar
        // and its held Reload stay until the person restarts it.
        final GitHubStore store =
                new GitHubStore(
                        CONFIG,
                        () -> CONFIG,
                        GitHubStore.Parts.reading(
                                new BoardReader() {
                                    @Override
                                    public Snapshot read(final ProjectConfig config) {
                                        if (reads.incrementAndGet() == 1) {
                                            return snapshot();
                                        }
                                        progress =
                                                new ReadProgress(
                                                        Instant.now(), 0, null, null, "reload");
                                        try {
                                            Thread.sleep(Duration.ofSeconds(7));
                                        } catch (final InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        progress = null;
                                        throw new StoreException.Unreachable(
                                                "porelo-store", "GitHub answered 502: bad gateway");
                                    }

                                    @Override
                                    public Optional<ReadProgress> progress() {
                                        return Optional.ofNullable(progress);
                                    }
                                },
                                states -> null),
                        origin -> "github_pat_failed_0123456789",
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
                                    new EventsController(changes, Duration.ofSeconds(15)))
                            .setControllerAdvice(new Problems())
                            .build();
            mvc.perform(
                            put("/projects/alpha")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(SOURCE))
                    .andExpect(status().isNoContent());
            final MockHttpServletResponse events =
                    mvc.perform(get("/projects/alpha/events")).andReturn().getResponse();
            within(() -> text(events).contains("event:board"), "the first board", events);

            mvc.perform(post("/projects/alpha/refresh")).andExpect(status().isOk());
            within(
                    () -> text(events).contains("\"state\":\"failed\""),
                    "the failed read on the stream",
                    events);

            assertThat(text(events))
                    .as("the read's problem")
                    .contains("\"problem\":\"Unreachable\"", "GitHub answered 502: bad gateway")
                    .doesNotContain("\"state\":\"done\"");
            assertThat(changes.followedProjects()).as("the stream still followed").isEqualTo(1);
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
            final String what,
            final MockHttpServletResponse events)
            throws InterruptedException {
        final long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < end)
                    .as("%s within 30 s; the stream so far:%n%s", what, text(events))
                    .isTrue();
            Thread.sleep(50);
        }
    }
}
