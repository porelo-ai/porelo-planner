package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The reads an agent asks of the board, over a store that answers one board. */
class AgentReadsEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final String PARENT_TEXT =
            "The parent covers it.\n\n**What must be true.** The cache is warm.\n\n"
                    + "## Plan\n\nRefill nightly.\n";

    private static final String TWICE_TEXT = "Covers.\n\n## Plan\n\none\n\n## Plan\n\ntwo\n";

    private MockMvc mvc;

    @BeforeEach
    void serve() throws Exception {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return AgentReadsEndpointTest.board();
                    }

                    @Override
                    public Board refresh() {
                        return board();
                    }

                    @Override
                    public @Nullable String storyState(final List<String> issueStates) {
                        return null;
                    }

                    @Override
                    public TextFormat textFormat() {
                        return TextFormat.MARKDOWN;
                    }
                };
        final Projects projects =
                new Projects(
                        (project, repository, branch) -> store, new WorkRecord(Map.of(), true));
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new BoardController(
                                        projects,
                                        new ChangesRegistry(
                                                projects,
                                                Duration.ofHours(1),
                                                Executors.newSingleThreadScheduledExecutor())),
                                new AgentReadsController(projects))
                        .setControllerAdvice(new Problems(projects))
                        .build();
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    private static Board board() {
        return new Board(
                new Revision("porelo-store@c1"),
                new Workflow(
                        List.of("Backlog", "Implementing", "Review", "Finished"),
                        List.of(),
                        List.of("Finished")),
                List.of(
                        new Story(
                                "ST1",
                                "Cache story",
                                "Backlog",
                                "Low",
                                null,
                                List.of("AB1"),
                                "The story mentions latency.",
                                null),
                        new Story(
                                "ST2",
                                "Other story",
                                "Backlog",
                                "High",
                                null,
                                List.of("AB2"),
                                "",
                                null)),
                List.of(
                        issue("AB1", "Parent", "Backlog", "S", "High", null, PARENT_TEXT),
                        issue("AB1.1", "Child", "Review", null, null, "AB1", "Plain child."),
                        issue("AB2", "Second", "Backlog", "M", "Medium", null, ""),
                        issue("AB3", "Loose", "Implementing", "S", "Low", null, TWICE_TEXT)),
                new Work(Map.of(), List.of()));
    }

    private static Issue issue(
            final String id,
            final String title,
            final String state,
            final @Nullable String size,
            final @Nullable String priority,
            final @Nullable String parent,
            final String text) {
        return new Issue(
                id,
                "porelo-store",
                title,
                state,
                size,
                List.of(),
                parent,
                null,
                text,
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                priority,
                List.of(),
                List.of());
    }

    private JsonNode json(final String path, final String... words) throws Exception {
        final var request = get(path);
        for (final String word : words) {
            request.param("words", word);
        }
        return JsonMapper.builder()
                .build()
                .readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }

    @Test
    void theOutlineSaysOfEachItemWhatTheFullBoardSays() throws Exception {
        // Harm: an agent acts on a view that disagrees with the window's board.
        final JsonNode outline = json("/projects/alpha/outline");
        final JsonNode stories = json("/projects/alpha/stories");
        assertThat(outline.path("revision").asString())
                .as("the revision")
                .isEqualTo(stories.path("revision").asString());
        assertThat(outline.path("stories").size())
                .as("every story")
                .isEqualTo(stories.path("stories").size());
        for (int s = 0; s < stories.path("stories").size(); s++) {
            final JsonNode full = stories.path("stories").get(s);
            final JsonNode brief = outline.path("stories").get(s);
            for (final String member : List.of("id", "title", "state", "priority")) {
                assertThat(brief.path(member))
                        .as("story %d %s", s, member)
                        .isEqualTo(full.path(member));
            }
            assertThat(brief.path("issues").size())
                    .as("the issues of story %d", s)
                    .isEqualTo(full.path("issues").size());
            for (int i = 0; i < full.path("issues").size(); i++) {
                for (final String member : List.of("id", "title", "state", "size", "priority")) {
                    assertThat(brief.path("issues").get(i).path(member))
                            .as("story %d issue %d %s", s, i, member)
                            .isEqualTo(full.path("issues").get(i).path(member));
                }
            }
        }
        final JsonNode loose = json("/projects/alpha/issues?story=none").path("issues");
        assertThat(outline.path("issues").size())
                .as("the issues in no story")
                .isEqualTo(loose.size());
        assertThat(outline.path("issues").get(0).path("id").asString())
                .as("AB3 is named by no story")
                .isEqualTo(loose.get(0).path("id").asString())
                .isEqualTo("AB3");
    }

    @Test
    void anIssuesPartsAreItsTextByTheNamesSetIssueTextWrites() throws Exception {
        final JsonNode issue = json("/projects/alpha/issues/AB1/parts");
        final JsonNode full = json("/projects/alpha/issues/AB1").path("issue");
        assertThat(issue.path("version")).as("the version").isEqualTo(full.path("version"));
        assertThat(issue.path("story").asString()).as("the story that names it").isEqualTo("ST1");
        assertThat(issue.path("parts").path("covers").asString())
                .as("the lead paragraph")
                .isEqualTo("The parent covers it.");
        assertThat(issue.path("parts").path("whatMustBeTrue").asString())
                .as("what must be true")
                .isEqualTo("The cache is warm.");
        assertThat(issue.path("parts").path("plan").asString())
                .as("the plan")
                .isEqualTo("Refill nightly.");
        assertThat(issue.path("parts").has("findings")).as("an absent part is left out").isFalse();
        assertThat(issue.has("text")).as("no whole text beside parts").isFalse();
        assertThat(json("/projects/alpha/issues/AB2/parts").path("story").asString())
                .as("another story's issue")
                .isEqualTo("ST2");
    }

    @Test
    void aTextWhoseSectionsCannotBeToldApartAnswersNoPartsTheTextAndTheFault() throws Exception {
        final JsonNode issue = json("/projects/alpha/issues/AB3/parts");
        assertThat(issue.path("parts").isNull()).as("parts are null").isTrue();
        assertThat(issue.path("text").asString()).as("the whole text").isEqualTo(TWICE_TEXT);
        assertThat(issue.path("fault").asString()).as("what to repair").isNotBlank();
        assertThat(issue.path("story").isNull()).as("no story names it").isTrue();
    }

    @Test
    void anUnknownIssueIsNoSuchIssueAndABadIdIsInvalid() throws Exception {
        mvc.perform(get("/projects/alpha/issues/ZZ9/parts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchIssue"));
        mvc.perform(get("/projects/alpha/issues/bad/parts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
        mvc.perform(get("/projects/none/outline"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"));
    }

    @Test
    void aBoardWithBreachesListsThemWithTheirIssuesAsItsOwnRulesGaveThem() throws Exception {
        // Harm: a rule shown that the board does not break, or one lost.
        final JsonNode breaches = json("/projects/alpha/breaches").path("breaches");
        assertThat(breaches.size()).as("one group for each rule and story").isEqualTo(2);
        assertThat(breaches.get(0).path("rule").asString())
                .as("the story below its issue")
                .isEqualTo("StoryPriorityBelowIssue");
        assertThat(breaches.get(0).path("story").asString()).as("its story").isEqualTo("ST1");
        assertThat(breaches.get(0).path("issues").get(0).asString())
                .as("its issue")
                .isEqualTo("AB1");
        assertThat(breaches.get(1).path("rule").asString())
                .as("an open issue past the first state in no story")
                .isEqualTo("IssueInNoStory");
        assertThat(breaches.get(1).path("story").isNull()).as("no story").isTrue();
        assertThat(breaches.get(1).path("issues").get(0).asString())
                .as("the loose issue")
                .isEqualTo("AB3");
    }

    @Test
    void searchFindsAWordInATitleAndInATextPartAndMissesOtherwise() throws Exception {
        final JsonNode cache = json("/projects/alpha/search", "CACHE");
        assertThat(cache.path("more").asBoolean()).as("nothing more").isFalse();
        assertThat(cache.path("matches").size())
                .as("the story title and the text part")
                .isEqualTo(2);
        assertThat(cache.path("matches").get(0).path("id").asString())
                .as("a story first")
                .isEqualTo("ST1");
        assertThat(cache.path("matches").get(0).path("kind").asString())
                .as("its kind")
                .isEqualTo("STORY");
        assertThat(cache.path("matches").get(0).path("found").get(0).asString())
                .as("in its title")
                .isEqualTo("title");
        assertThat(cache.path("matches").get(1).path("id").asString())
                .as("then the issue whose what-must-be-true holds the word")
                .isEqualTo("AB1");
        assertThat(cache.path("matches").get(1).path("found").get(0).asString())
                .as("in the part the text names it")
                .isEqualTo("whatMustBeTrue");
        assertThat(
                        json("/projects/alpha/search", "latency")
                                .path("matches")
                                .get(0)
                                .path("found")
                                .get(0)
                                .asString())
                .as("a story's text")
                .isEqualTo("text");
        assertThat(json("/projects/alpha/search", "warm", "nonesuch").path("matches").size())
                .as("a word nothing holds")
                .isZero();
    }

    @Test
    void searchRefusesNoWordsTooManyAndAWordOutOfBounds() throws Exception {
        mvc.perform(get("/projects/alpha/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
        mvc.perform(get("/projects/alpha/search").param("words", " "))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/projects/alpha/search")
                                .param(
                                        "words", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j",
                                        "k"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/projects/alpha/search").param("words", "x".repeat(101)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/projects/alpha/search").param("words", "a,b")).andExpect(status().isOk());
    }
}
