package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.github.BoardReader;
import org.fuin.porelo.planner.store.github.BoardWriter;
import org.fuin.porelo.planner.store.github.GitHubStore;
import org.fuin.porelo.planner.store.github.ProjectConfig;
import org.fuin.porelo.planner.store.github.ProjectRef;
import org.fuin.porelo.planner.store.github.Snapshot;
import org.fuin.porelo.planner.store.github.Touched;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A move on a GitHub board of a large project's size while a whole read of it runs, measured
 * through the planner's HTTP answers and its stream: the move is answered and shown at once.
 */
class GitHubMoveDuringReadHttpTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    /** As many issues as the large board holds, stories counted as issues. */
    private static final int ITEMS = 1_388;

    /** How long a whole read takes here; the large board's took about 50 s. */
    private static final Duration WHOLE_READ = Duration.ofSeconds(8);

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

    private final AtomicInteger wholeReads = new AtomicInteger();

    private final AtomicInteger revisions = new AtomicInteger();

    private final CountDownLatch reading = new CountDownLatch(2);

    private final List<String> saved = new ArrayList<>();

    private ChangesRegistry changes;

    private static Board board(final String revision, final String movedTo) {
        final List<Issue> issues = new ArrayList<>(ITEMS);
        for (int n = 1; n <= ITEMS; n++) {
            issues.add(
                    new Issue(
                            "porelo-store#" + n,
                            "porelo-store",
                            "Issue " + n,
                            n == 1 ? movedTo : "Idea",
                            "S",
                            List.of(),
                            null,
                            null,
                            "# Issue " + n + "\n\n" + "What must be true. ".repeat(40)));
        }
        return new Board(
                new Revision(revision),
                WORKFLOW,
                List.of(),
                issues,
                new Work(Map.of(), List.of()),
                List.of("porelo-store"));
    }

    private Snapshot snapshot(final String movedTo) {
        return new Snapshot(board("r" + revisions.incrementAndGet(), movedTo), Map.of(), CONFIG);
    }

    private MockMvc serve() throws Exception {
        final GitHubStore store =
                new GitHubStore(
                        CONFIG,
                        () -> CONFIG,
                        GitHubStore.Parts.reading(
                                        new BoardReader() {
                                            @Override
                                            public Snapshot read(final ProjectConfig config) {
                                                wholeReads.incrementAndGet();
                                                reading.countDown();
                                                try {
                                                    Thread.sleep(WHOLE_READ);
                                                } catch (final InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return snapshot(
                                                        saved.isEmpty() ? "Idea" : "Backlog");
                                            }

                                            @Override
                                            public Snapshot reread(
                                                    final ProjectConfig config,
                                                    final Snapshot held,
                                                    final Touched.Ids touched) {
                                                return snapshot("Backlog");
                                            }
                                        },
                                        states -> null)
                                .writer(
                                        new BoardWriter() {
                                            @Override
                                            public void save(
                                                    final Snapshot current,
                                                    final org.fuin.porelo.planner.store.Basis basis,
                                                    final Writes writes) {
                                                saved.addAll(writes.issues().keySet());
                                            }
                                        }),
                        origin -> "github_pat_measure_0123456789",
                        Duration.ofMinutes(2));
        final Projects projects = new Projects((project, repository, branch) -> store);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        final MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects, changes))
                        .setControllerAdvice(new Problems(projects))
                        .build();
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
        return mvc;
    }

    @AfterEach
    void close() {
        if (changes != null) {
            changes.close();
        }
    }

    @Test
    void aMoveDuringAWholeReadIsAnsweredAndShownAtOnceAndStartsNoWholeRead() throws Exception {
        // Harm: every move on a large board waits for a whole read of it, so its card stays
        // "Saving..." for most of a minute and the board cannot be worked on.
        final MockMvc mvc = serve();
        final Changes.Subscription stream = changes.subscribe("alpha");
        // The subscription's first poll reads whole: the stand-in for a read under way.
        final Thread reload = Thread.ofVirtual().start(() -> changes.refresh("alpha"));
        assertThat(reading.await(30, TimeUnit.SECONDS)).as("a whole read under way").isTrue();
        final int readsBefore = wholeReads.get();
        final String revision = stream.firstFrame().board().revision().value();
        final String body = "{\"moves\": [{\"issue\": \"porelo-store#1\", \"to\": \"Backlog\"}]}";

        final long start = System.nanoTime();
        final String preview =
                mvc.perform(
                                post("/projects/alpha/moves/preview")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        mvc.perform(
                        post("/projects/alpha/moves")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(Previewed.moves(body, preview)))
                .andExpect(status().isOk());
        final Duration answered = Duration.ofNanos(System.nanoTime() - start);
        Optional<Board> shown = Optional.empty();
        while (shown.isEmpty() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) {
            shown =
                    stream.next(Duration.ofMillis(200))
                            .filter(
                                    b ->
                                            b.issue("porelo-store#1")
                                                    .orElseThrow()
                                                    .state()
                                                    .equals("Backlog"));
        }
        final Duration toStream = Duration.ofNanos(System.nanoTime() - start);
        reload.join(WHOLE_READ.multipliedBy(3));
        System.out.println(
                "MEASURED move answered in "
                        + answered.toMillis()
                        + " ms, shown on the stream in "
                        + toStream.toMillis()
                        + " ms, a whole read taking "
                        + WHOLE_READ.toMillis()
                        + " ms of "
                        + ITEMS
                        + " issues; whole reads after the move: "
                        + (wholeReads.get() - readsBefore)
                        + "; first revision "
                        + revision);

        assertThat(saved).as("the move saved").containsExactly("porelo-store#1");
        assertThat(answered).as("the move's answer").isLessThan(Duration.ofSeconds(2));
        assertThat(shown).as("the move on the stream within 5 s").isPresent();
        assertThat(wholeReads.get() - readsBefore).as("whole reads after the move").isZero();
    }
}
