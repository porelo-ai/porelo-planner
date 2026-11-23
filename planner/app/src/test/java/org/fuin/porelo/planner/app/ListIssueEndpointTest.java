package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The add of an issue made outside the project as the window sends it, mapped to HTTP. */
class ListIssueEndpointTest {

    /** The link of the issue made outside the project, a fixture of an invented repository. */
    private static final String MADE_LINK =
            "https://github.com/porelo-ai/porelo-lib/issues/7"; // not-a-citation

    private static final String MADE = "porelo-lib#7";

    /** What the store was asked to add, with the revision it was asked against. */
    private final List<String> written = new ArrayList<>();

    /** Whether the store adds issues made outside the project, as the GitHub store does. */
    private boolean lists = true;

    private @Nullable RuntimeException failure;

    private Board current = board("r1", true);

    private MockMvc mvc;

    private ChangesRegistry changes;

    /** A board holding porelo-lib#3, offering porelo-lib#7 when asked to. */
    private static Board board(final String revision, final boolean offering) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                List.of(),
                List.of(
                        new Issue(
                                "porelo-lib#3",
                                "porelo-lib",
                                "On the board",
                                "Idea",
                                null,
                                List.of(),
                                null,
                                null,
                                "")),
                new Work(Map.of(), List.of()),
                List.of("porelo-lib"),
                List.of(),
                "porelo-ai",
                offering
                        ? List.of(
                                new UnlistedIssue(
                                        MADE,
                                        "porelo-lib",
                                        "A secret title",
                                        UnlistedIssue.Kind.ISSUE,
                                        URI.create(MADE_LINK)))
                        : List.of());
    }

    @BeforeEach
    void serve() {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return current;
                    }

                    @Override
                    public Board refresh() {
                        return current;
                    }

                    @Override
                    public boolean addsUnlistedIssues() {
                        return lists;
                    }

                    @Override
                    public Board addUnlistedIssue(final Revision revision, final String issue) {
                        if (failure != null) {
                            throw failure;
                        }
                        written.add(issue + " at " + revision.value());
                        current = ListIssueEndpointTest.board("r2", false);
                        return current;
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        projects.put("alpha", "git@example.org:a/porelo-lib.git", null);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new EditsController(new BoardEdits(projects, changes)))
                        .setControllerAdvice(new Problems(projects))
                        .build();
    }

    @AfterEach
    void close() {
        changes.close();
    }

    private ResultActions add(final String body) throws Exception {
        return mvc.perform(
                post("/projects/alpha/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    void anOfferedIssueIsAddedAndAnsweredWithTheNewRevision() throws Exception {
        // Harm: an add the store saved answered as refused or without its revision leaves the
        // window offering the issue again.
        add("{\"issue\": \"porelo-lib#7\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(written).as("the add").containsExactly("porelo-lib#7 at r1");
    }

    @Test
    void anIssueTheBoardDoesNotOfferIsNotUnlistedAndNothingIsWritten() throws Exception {
        // Harm: the add took any issue the window named, one on the board or one never offered,
        // and the window could not tell an unknown project from an offer to read again.
        for (final String id : List.of("porelo-lib#3", "porelo-lib#8")) {
            add("{\"issue\": \"" + id + "\", \"seen\": " + Seens.of(current) + "}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("NotUnlisted"))
                    .andExpect(jsonPath("$.parameters.issue").value(id));
        }
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aStoreThatAddsNoneIsNotOfferedForAnIssueTheBoardDoesNotHold() throws Exception {
        // Harm: the Git store answered every add as no longer offered, so the window read the
        // board again and again instead of hiding an add the store cannot serve.
        lists = false;
        current = board("r1", false);

        add("{\"issue\": \"porelo-lib#8\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aBodyThatIsNoAddIsRefusedBeforeTheBoardIsRead() throws Exception {
        // Harm: a fixed alias, an id of the Git store's form or a member nobody named was taken as
        // an issue to add, and a refusal repeated what was sent.
        for (final String body :
                List.of(
                        "{\"issue\": \"PL4\", \"seen\": " + Seens.of(current) + "}",
                        "{\"issue\": 7, \"seen\": " + Seens.of(current) + "}",
                        "{\"issue\": \"porelo-lib#7\"}",
                        "{\"issue\": \"porelo-lib#7\", \"seen\": "
                                + Seens.of(current)
                                + ", \"story\": \"x\"}",
                        "[]")) {
            add(body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("EditNotAllowed"));
        }
        add("{\"issue\": \"PL4\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(jsonPath("$.parameters.field").value("issue"));
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void anAddOfAnIssueChangedSinceItWasSeenIsAConflict() throws Exception {
        // Harm: an add planned on an old board went ahead on an issue a person changed since.
        add("{\"issue\": \"porelo-lib#7\", \"seen\": " + Seens.stale(current) + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("ItemChanged"))
                .andExpect(jsonPath("$.parameters.changed[0]").value("porelo-lib#7"));
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void theStoresRefusalsReachTheWindowAsTheContractNamesThem() throws Exception {
        // Harm: an issue gone from the tracker read as a fault of the planner, one the board could
        // not hold as a refused move, or half a save as none.
        failure = new StoreException.NoSuchIssue(MADE);
        add("{\"issue\": \"porelo-lib#7\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("NotUnlisted"));

        failure =
                new StoreException.NotSavable(
                        MADE,
                        "Add its parent porelo-lib#9 to the project first; it is no item of it.");
        add("{\"issue\": \"porelo-lib#7\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("EditNotAllowed"))
                .andExpect(jsonPath("$.parameters.field").value("issue"))
                .andExpect(
                        jsonPath("$.parameters.reason")
                                .value(
                                        "Add its parent porelo-lib#9 to the project first; it is"
                                                + " no item of it."));

        failure =
                new StoreException.PartlySaved(
                        "porelo-lib", List.of("porelo-lib"), "GitHub refused a field value");
        add("{\"issue\": \"porelo-lib#7\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("PartlySaved"));
    }
}
