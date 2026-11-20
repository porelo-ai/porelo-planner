package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** A board a tracker keeps, read and moved by its references as the window calls it. */
class TrackerIdsHttpTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<Writes> saved = new ArrayList<>();

    private MockMvc mvc;

    private ChangesRegistry changes;

    private static Board board(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(
                        List.of("Backlog", "Review", "Finished"), List.of(), List.of("Finished")),
                List.of(
                        new Story(
                                "porelo-store#7",
                                "A carried story",
                                "Backlog",
                                false,
                                "High",
                                null,
                                List.of("porelo-store#8", "porelo-store#9"),
                                "",
                                null,
                                true,
                                "ST7")),
                List.of(
                        new Issue(
                                "porelo-store#8",
                                "porelo-store",
                                "A carried issue",
                                "Backlog",
                                "S",
                                List.of(),
                                null,
                                null,
                                "",
                                Worker.AGENT,
                                Risk.ORDINARY,
                                "AB08"),
                        new Issue(
                                "porelo-store#9",
                                "porelo-store",
                                "An issue made in the tracker",
                                "Backlog",
                                null,
                                List.of(),
                                null,
                                null,
                                "")),
                new Work(Map.of(), List.of()));
    }

    @BeforeEach
    void serve() throws Exception {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return TrackerIdsHttpTest.board(saved.isEmpty() ? "r1" : "r2");
                    }

                    @Override
                    public Board refresh() {
                        return board();
                    }

                    @Override
                    public Board save(final Revision revision, final Writes writes) {
                        saved.add(writes);
                        return board();
                    }

                    @Override
                    public @Nullable String storyState(final List<String> states) {
                        return states.isEmpty() ? null : states.get(0);
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store, 8);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects, changes))
                        .setControllerAdvice(new Problems())
                        .build();
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    @AfterEach
    void close() {
        changes.close();
    }

    @Test
    void aStoryAndAnIssueAreReadByTheirPercentEncodedReferenceWithTheirAlias() throws Exception {
        // Harm: a board the tracker keeps could not be opened item by item in the window.
        mvc.perform(get(URI.create("/projects/alpha/stories/porelo-store%237")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.story.id").value("porelo-store#7"))
                .andExpect(jsonPath("$.story.alias").value("ST7"))
                .andExpect(jsonPath("$.story.issues[0].alias").value("AB08"))
                .andExpect(jsonPath("$.story.issues[1].alias").doesNotExist());
        mvc.perform(get(URI.create("/projects/alpha/issues/porelo-store%238")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.id").value("porelo-store#8"))
                .andExpect(jsonPath("$.issue.alias").value("AB08"));
        mvc.perform(get(URI.create("/projects/alpha/stories/porelo-store%237/issues")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[1].id").value("porelo-store#9"));
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories[0].id").value("porelo-store#7"))
                .andExpect(jsonPath("$.stories[0].alias").value("ST7"));
    }

    @Test
    void aReferenceThatIsNoIdIsRefusedAsInvalidAndOneNotOnTheBoardAsUnknown() throws Exception {
        // Harm: a reference with a path or a second item in it reaching the store and the tracker.
        for (final String path :
                List.of(
                        "/projects/alpha/issues/porelo-store%2308",
                        "/projects/alpha/issues/porelo-store%238%231",
                        "/projects/alpha/issues/porelo-store%23%2E%2E",
                        "/projects/alpha/stories/a%2E%2Eb%231",
                        "/projects/alpha/stories/porelo-store%23%201")) {
            mvc.perform(get(URI.create(path)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
        }
        mvc.perform(get(URI.create("/projects/alpha/issues/porelo-store%2399")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchIssue"));
        mvc.perform(get(URI.create("/projects/alpha/stories/porelo-store%2399")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchStory"));
    }

    @Test
    void anIssueIsMovedByItsReference() throws Exception {
        // Harm: an item made in the tracker could not be moved on the board.
        final String body =
                "{\"revision\": \"r1\", \"moves\": [{\"issue\": \"porelo-store#9\","
                        + " \"to\": \"Review\"}]}";
        final String preview =
                mvc.perform(
                                post("/projects/alpha/moves/preview")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.issues[0].id").value("porelo-store#9"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        mvc.perform(
                        post("/projects/alpha/moves")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(Previewed.moves(body, preview)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved[0]").value("porelo-store#9"));
        assertThat(saved).as("one save").hasSize(1);
        assertThat(saved.get(0).issues())
                .as("the issue's new state, under its reference")
                .containsExactly(Map.entry("porelo-store#9", "Review"));
    }
}
