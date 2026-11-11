package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerMapping;

/** The hand-in of a discussed idea as the window posts it: checked, saved once, refused by name. */
class IdeasEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final String OFFER = "4f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f";

    private static final String IDEA =
            "{\"story\": {\"title\": \" Tag stories \", \"gain\": \"People find stories\","
                    + " \"test\": \"Tag one\", \"priority\": \"High\"}, \"issues\":"
                    + " [{\"repository\": \"porelo-other\", \"title\": \"Tags\", \"mustBeTrue\":"
                    + " \"A story can be tagged.\", \"size\": \"S\", \"worker\": \"Manual\"}]}";

    private final List<NewIdea> added = new ArrayList<>();

    private @Nullable RuntimeException failure;

    private boolean offered = true;

    private Board current = board("r1");

    private MockMvc mvc;

    private ChangesRegistry changes;

    private static Board board(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                List.of(),
                List.of(),
                new Work(Map.of(), List.of()),
                List.of("porelo-store", "porelo-other"));
    }

    @BeforeEach
    void serve() throws Exception {
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
                    public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
                        if (!offered) {
                            return PlanningStore.super.createIdea(revision, idea);
                        }
                        final RuntimeException refused = failure;
                        if (refused != null) {
                            throw refused;
                        }
                        added.add(idea);
                        current = IdeasEndpointTest.board("r" + (added.size() + 1));
                        return new NewIdea.Added("ST" + added.size(), List.of("CD3"), current);
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
    void anIdeaIsSavedOnceAnsweredAsCreatedAndToldToEveryWindow() throws Exception {
        // A repeated hand-in that saved again would put a second story and its issues on the board.
        final Changes.Subscription window = changes.subscribe("alpha");

        handIn("{\"offer\": \"" + OFFER + "\", \"idea\": " + IDEA + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ST1"));
        handIn("{\"offer\": \"" + OFFER + "\", \"idea\": " + IDEA + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ST1"));

        assertThat(added).as("one save for the offer").hasSize(1);
        final NewIdea idea = added.getFirst();
        assertThat(idea.title()).as("the story's title, stripped").isEqualTo("Tag stories");
        assertThat(idea.issues())
                .as("its issue, worked as the person chose")
                .singleElement()
                .satisfies(
                        issue -> {
                            assertThat(issue.repository())
                                    .as("its repository")
                                    .isEqualTo("porelo-other");
                            assertThat(issue.worker()).as("its worker").isEqualTo(Worker.MANUAL);
                        });
        assertThat(window.next(Duration.ofSeconds(2)).map(board -> board.revision().value()))
                .as("the window is told the saved board")
                .contains("r2");
    }

    @Test
    void aBodyThatIsNoHandInIsRefusedAsAnIdeaWithoutRepeatingIt() throws Exception {
        // A body's fault answered by another name, or with its text, misleads the window or leaks.
        final String idea = "{\"offer\": \"" + OFFER + "\", \"idea\": " + IDEA;
        final List<String> bodies =
                List.of(
                        "{\"offer\": \""
                                + OFFER
                                + "\", \"idea\": "
                                + IDEA
                                + ", \"pad\": \""
                                + "s".repeat(65536)
                                + "\"}",
                        "secret not json",
                        "[\"secret\"]",
                        "{\"offer\": \"" + OFFER + "\"}",
                        idea + ", \"secret\": 1}",
                        "{\"offer\": \""
                                + OFFER.toUpperCase(java.util.Locale.ROOT)
                                + "\", \"idea\": "
                                + IDEA
                                + "}",
                        "{\"offer\": \"secret\", \"idea\": " + IDEA + "}",
                        "{\"offer\": 7, \"idea\": " + IDEA + "}",
                        "{\"offer\": \"" + OFFER + "\", \"idea\": \"secret\"}",
                        "{\"offer\": \""
                                + OFFER
                                + "\", \"idea\": "
                                + "[".repeat(30000)
                                + "]".repeat(30000)
                                + "}");
        for (final String body : bodies) {
            final MvcResult result =
                    handIn(body).andExpect(status().isUnprocessableContent()).andReturn();
            final String answer = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(answer)
                    .as("the answer to a body of %s bytes", body.length())
                    .contains("urn:porelo-planner:IdeaRefused", "\"faults\":[\"")
                    .doesNotContain("secret");
        }
        assertThat(added).as("nothing is saved").isEmpty();
    }

    @Test
    void aMalformedIdeaListsItsFaultsByFieldWithoutRepeatingWhatWasSent() throws Exception {
        // An idea let through half checked would write files nobody can read; an echo would leak.
        handIn(
                        "{\"offer\": \""
                                + OFFER
                                + "\", \"idea\": {\"story\": {\"title\": \"secret\\n\","
                                + " \"gain\": \"G\", \"test\": \"T\", \"priority\": \"High\","
                                + " \"secret\": 1}, \"issues\": [{\"repository\": \"secret\","
                                + " \"title\": \"T\", \"mustBeTrue\": \"W\", \"size\": 3,"
                                + " \"worker\": \"AGENT\"}, 5]}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("IdeaRefused"))
                .andExpect(
                        jsonPath("$.parameters.faults")
                                .value(
                                        org.hamcrest.Matchers.contains(
                                                org.hamcrest.Matchers.startsWith("story: "),
                                                org.hamcrest.Matchers.startsWith("story.title: "),
                                                org.hamcrest.Matchers.startsWith(
                                                        "issues[0].repository: "),
                                                org.hamcrest.Matchers.startsWith(
                                                        "issues[0].size: "),
                                                org.hamcrest.Matchers.startsWith(
                                                        "issues[0].worker: "),
                                                org.hamcrest.Matchers.startsWith("issues[1]: "))))
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .as("nothing sent is repeated")
                                        .doesNotContain("secret"));

        assertThat(added).as("nothing is saved").isEmpty();
    }

    @Test
    void aStoreThatCannotWriteTheIdeaAnswersItsWordsAsTheOneFault() throws Exception {
        // A store refusal answered as a move's refusal would show the person the wrong problem.
        failure = new StoreException.NotSavable("", "The repository's issues give no prefix.");

        handIn("{\"offer\": \"" + OFFER + "\", \"idea\": " + IDEA + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("IdeaRefused"))
                .andExpect(
                        jsonPath("$.parameters.faults[0]")
                                .value("The repository's issues give no prefix."));

        failure = null;
        handIn("{\"offer\": \"" + OFFER + "\", \"idea\": " + IDEA + "}")
                .andExpect(status().isCreated());
        assertThat(added).as("the retry saves").hasSize(1);
    }

    @Test
    void aStoresLongWordsAreCutToTheFaultsBound() throws Exception {
        // A fault longer than the description allows makes the window refuse the whole answer.
        failure = new StoreException.NotSavable("", "x".repeat(5000));

        final String fault =
                com.jayway.jsonpath.JsonPath.read(
                        handIn("{\"offer\": \"" + OFFER + "\", \"idea\": " + IDEA + "}")
                                .andExpect(status().isUnprocessableContent())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.parameters.faults[0]");

        assertThat(fault.codePointCount(0, fault.length()))
                .as("the fault within the description's bound of 1000 characters")
                .isBetween(1, 1000);
    }

    @Test
    void aStoreThatAddsNoIdeasAnswersNotOffered() throws Exception {
        // A store without the save answered as a crash would hide that this planner cannot do it.
        offered = false;

        handIn("{\"offer\": \"" + OFFER + "\", \"idea\": " + IDEA + "}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
    }

    @Test
    void aBodySpringCannotReadOnTheHandInPathIsRefusedAsAnIdea() {
        // A body refused under another name would be taken by the window as another call's fault.
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("project", "alpha"));
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/projects/{project}/ideas");

        final ResponseEntity<ProblemDetail> answer =
                new Problems()
                        .unreadable(
                                new HttpMessageNotReadableException(
                                        "secret", new MockHttpInputMessage(new byte[0])),
                                new ServletWebRequest(request));

        assertThat(answer.getStatusCode())
                .as("its status")
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(answer.getBody()).as("its problem").isNotNull();
        assertThat(answer.getBody().getTitle()).as("its name").isEqualTo("IdeaRefused");
        assertThat(answer.getBody().getDetail()).as("its words").doesNotContain("secret");
    }

    private ResultActions handIn(final String body) throws Exception {
        return mvc.perform(
                post("/projects/alpha/ideas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
