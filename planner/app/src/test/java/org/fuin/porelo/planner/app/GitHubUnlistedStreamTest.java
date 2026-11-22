package org.fuin.porelo.planner.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.assertj.core.api.Assertions;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.github.ChangeProbe;
import org.fuin.porelo.planner.store.github.GitHubStore;
import org.fuin.porelo.planner.store.github.Pace;
import org.fuin.porelo.planner.store.github.ProjectConfig;
import org.fuin.porelo.planner.store.github.ProjectRef;
import org.fuin.porelo.planner.store.github.Snapshot;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The issues outside a GitHub project on the board the window is streamed. */
class GitHubUnlistedStreamTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final String LOW =
            "GitHub's rate limit is low; the board is looked at less often";

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
                    List.of("porelo-store", "porelo-other"),
                    Map.of(),
                    Map.of(),
                    "PVT_7");

    /** A pace that says the rate limit is low, so every board served gains its fault. */
    private static final Pace SLOW =
            new Pace() {
                @Override
                public @Nullable String hold() {
                    return null;
                }

                @Override
                public List<String> faults() {
                    return List.of(LOW);
                }

                @Override
                public Duration delay(final Duration probed) {
                    return probed;
                }
            };

    @Test
    void aBoardServedWithTheRateLimitsWordsKeepsTheIssuesOutsideTheProject() throws Exception {
        // Harm: while GitHub's rate limit is low, the board the window is sent loses every issue
        // made by hand on the tracker, so they vanish from the screen until the limit recovers.
        final GitHubStore store =
                new GitHubStore(
                        CONFIG,
                        () -> CONFIG,
                        GitHubStore.Parts.reading(
                                        config ->
                                                new Snapshot(
                                                        new Board(
                                                                new Revision("r1"),
                                                                WORKFLOW,
                                                                List.of(),
                                                                List.of(),
                                                                config.work(),
                                                                config.repositories(),
                                                                List.of(),
                                                                "example-org",
                                                                List.of(),
                                                                List.of(
                                                                        new UnlistedIssue(
                                                                                "porelo-other#3",
                                                                                "porelo-other",
                                                                                "Made by hand",
                                                                                UnlistedIssue.Kind
                                                                                        .ISSUE,
                                                                                URI.create(
                                                                                        "https://github.com/example-org/porelo-other/issues/3")))),
                                                        Map.of(),
                                                        config),
                                        states -> null)
                                .probe(
                                        new ChangeProbe() {
                                            @Override
                                            public boolean unchanged(final Snapshot current) {
                                                return true;
                                            }

                                            @Override
                                            public Pace pace() {
                                                return SLOW;
                                            }
                                        }),
                        origin -> "github_pat_unlisted_0123456789",
                        Duration.ofMinutes(2));
        final Projects projects = new Projects((project, repository, branch) -> store, 8);
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
            final long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!events.getContentAsString().contains("event:board")
                    && System.nanoTime() < end) {
                Thread.sleep(50);
            }

            final String board =
                    events.getContentAsString()
                            .lines()
                            .dropWhile(line -> !line.equals("event:board"))
                            .filter(line -> line.startsWith("data:"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("no board was streamed"));
            Assertions.assertThat(board)
                    .as("the board streamed")
                    .contains(LOW)
                    .contains(
                            "\"unlisted\":[{\"id\":\"porelo-other#3\",\"repository\":\"porelo-other\","
                                    + "\"title\":\"Made by hand\",\"kind\":\"issue\",\"link\":"
                                    + "\"https://github.com/example-org/porelo-other/issues/3\"}]");
        }
    }
}
