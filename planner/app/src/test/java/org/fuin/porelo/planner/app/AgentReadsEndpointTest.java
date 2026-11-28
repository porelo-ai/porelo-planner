package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    private Board current = board();

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
        return issue(id, "porelo-store", title, state, size, priority, parent, text);
    }

    private static Issue issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String size,
            final @Nullable String priority,
            final @Nullable String parent,
            final String text) {
        return new Issue(
                id,
                repository,
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

    /** Guards all four wire reads against lost items and oversized answers. */
    @Test
    void everyPagedReadReachesEveryItemWithinTheCompleteJsonBound() throws Exception {
        // Harm: a tool answer is truncated, or later matches silently disappear.
        current = largeBoard();
        for (final String read : List.of("stories", "outline", "issues", "search")) {
            final List<String> ids = new ArrayList<>();
            @Nullable String after = null;
            int pages = 0;
            do {
                final var request = get("/projects/alpha/" + read).param("limit", "200");
                if (read.equals("issues")) request.param("story", "none");
                if (read.equals("search")) request.param("words", "cache");
                if (after != null) request.param("after", after);
                final String body =
                        mvc.perform(request)
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                assertThat(body.length())
                        .as("%s page %d includes escaping, metadata and next", read, pages)
                        .isLessThanOrEqualTo(60000);
                final JsonNode answer = JsonMapper.builder().build().readTree(body);
                assertThat(answer.path("revision").asString())
                        .as("one board revision")
                        .isEqualTo("large");
                assertThat(answer.has("next")).as("the last page explicitly answers null").isTrue();
                for (final String member : List.of("stories", "issues", "matches")) {
                    for (final JsonNode item : answer.path(member))
                        ids.add(item.path("id").asString());
                }
                if (read.equals("stories")) {
                    assertThat(answer.path("open"))
                            .as("every page keeps whole-board counts")
                            .isEqualTo(json("/projects/alpha/stories?limit=1").path("open"));
                    for (final JsonNode story : answer.path("stories")) {
                        assertThat(story.path("issues").size())
                                .as("the whole story travels together")
                                .isEqualTo(1);
                    }
                }
                after = answer.path("next").isNull() ? null : answer.path("next").asString();
                if (read.equals("search"))
                    assertThat(answer.path("more").asBoolean())
                            .as("more agrees with continuation")
                            .isEqualTo(after != null);
                assertThat(++pages).as("paging makes progress").isLessThan(100);
            } while (after != null);
            final List<String> expected = new ArrayList<>();
            if (!read.equals("issues"))
                current.stories().forEach(story -> expected.add(story.id()));
            if (!read.equals("stories"))
                current.issues().stream()
                        .filter(issue -> read.equals("search") || issue.id().startsWith("XY"))
                        .forEach(issue -> expected.add(issue.id()));
            assertThat(ids)
                    .as("%s reaches its exact reference set", read)
                    .containsExactlyElementsOf(expected);
            assertThat(pages).as("the large board needs continuation").isGreaterThan(1);
        }
    }

    /** Guards the HTTP refusals for incompatible continuations and invalid limits. */
    @Test
    void allReadsRefuseStaleMalformedAndChangedSelectionCursors() throws Exception {
        // Harm: incompatible pages are combined into a plausible but incomplete board.
        current = largeBoard();
        for (final String read : List.of("stories", "outline", "issues", "search")) {
            final String path = "/projects/alpha/" + read;
            final var first =
                    get(path)
                            .param("limit", "1")
                            .param("state", "Backlog")
                            .param("story", "none")
                            .param("words", "cache");
            final JsonNode answer =
                    JsonMapper.builder()
                            .build()
                            .readTree(
                                    mvc.perform(first)
                                            .andExpect(status().isOk())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString());
            final String after = answer.path("next").asString();
            mvc.perform(
                            get(path)
                                    .param("after", "%%%")
                                    .param("story", "none")
                                    .param("words", "cache"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("InvalidName"))
                    .andExpect(jsonPath("$.parameters.project").value("alpha"));
            mvc.perform(
                            get(path)
                                    .param("after", after)
                                    .param("state", "Review")
                                    .param("story", "none")
                                    .param("words", read.equals("search") ? "other" : "cache"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("InvalidName"))
                    .andExpect(jsonPath("$.parameters.project").value("alpha"));
            final Board before = current;
            current =
                    new Board(
                            new Revision("changed"),
                            before.workflow(),
                            before.stories(),
                            before.issues(),
                            before.work());
            mvc.perform(
                            get(path)
                                    .param("after", after)
                                    .param("state", "Backlog")
                                    .param("story", "none")
                                    .param("words", "cache"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("PageChanged"));
            current = before;
            for (final String limit : List.of("0", "201", "bad", "")) {
                mvc.perform(
                                get(path)
                                        .param("limit", limit)
                                        .param("story", "none")
                                        .param("words", "cache"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.title").value("InvalidName"))
                        .andExpect(jsonPath("$.parameters.project").value("alpha"));
            }
        }
    }

    /** Guards selection semantics on the HTTP reads. */
    @Test
    void filtersApplyToStoriesAndLooseIssuesWithoutFilteringAStorysIssues() throws Exception {
        // Harm: a repository or state filter changes the work contained in a selected story.
        for (final String read : List.of("stories", "outline")) {
            final JsonNode answer =
                    json(
                            "/projects/alpha/"
                                    + read
                                    + "?state=Backlog&priority=Low&repository=porelo-store");
            assertThat(answer.path("stories").size()).as("the selected story").isEqualTo(1);
            assertThat(answer.path("stories").get(0).path("id").asString())
                    .as("its own priority")
                    .isEqualTo("ST1");
            assertThat(answer.path("stories").get(0).path("issues").size())
                    .as("all of its issues")
                    .isEqualTo(1);
            assertThat(json("/projects/alpha/" + read + "?repository=other").path("stories").size())
                    .as("no story has an issue of the other repository")
                    .isZero();
        }
        assertThat(json("/projects/alpha/issues?story=none&state=Review").path("issues").size())
                .as("no loose issue is in review")
                .isZero();
        assertThat(
                        json("/projects/alpha/issues?story=none&repository=porelo-store")
                                .path("issues")
                                .size())
                .as("the matching loose issue")
                .isEqualTo(1);
        current = filterBoard();
        final JsonNode stories =
                json("/projects/alpha/stories?state=Backlog&priority=Low&repository=porelo-store");
        assertThat(stories.path("stories").size()).as("the selected story").isEqualTo(1);
        assertThat(ids(stories.path("stories").get(0).path("issues")))
                .as("both issues of the story, though the second matches no filter")
                .containsExactly("AB1", "AB4");
        assertThat(
                        ids(
                                json("/projects/alpha/outline"
                                                + "?state=Backlog&priority=Low"
                                                + "&repository=porelo-store")
                                        .path("issues")))
                .as("the matching loose issue stays and the other one is left out")
                .containsExactly("LO1");
    }

    /** The board with a second issue in the first story and two more issues in no story. */
    private Board filterBoard() {
        final List<Story> stories = new ArrayList<>(current.stories());
        final Story first = stories.getFirst();
        stories.set(
                0,
                new Story(
                        first.id(),
                        first.title(),
                        first.state(),
                        first.priority(),
                        null,
                        List.of("AB1", "AB4"),
                        first.description(),
                        null));
        final List<Issue> issues = new ArrayList<>(current.issues());
        issues.add(issue("AB4", "other", "Second of one", "Review", "S", "High", null, ""));
        issues.add(issue("LO1", "porelo-store", "Matches", "Backlog", "S", "Low", null, ""));
        issues.add(issue("LO2", "other", "Matches nothing", "Review", "S", "High", null, ""));
        return new Board(current.revision(), current.workflow(), stories, issues, current.work());
    }

    private static List<String> ids(final JsonNode items) {
        final List<String> ids = new ArrayList<>();
        for (final JsonNode item : items) {
            ids.add(item.path("id").asString());
        }
        return ids;
    }

    /** Guards default pages and equivalent repeated filter selections. */
    @Test
    void omittedPageParametersAnswerFiftyItemsAndCanonicalFiltersKeepTheirCursor()
            throws Exception {
        // Harm: callers omitting pagination lose the continuation, or equivalent selections cannot
        // continue.
        current = largeBoard("Cache");
        for (final String read : List.of("stories", "outline", "issues", "search")) {
            final var first =
                    get("/projects/alpha/" + read).param("story", "none").param("words", "cache");
            final JsonNode answer =
                    JsonMapper.builder()
                            .build()
                            .readTree(
                                    mvc.perform(first)
                                            .andExpect(status().isOk())
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString());
            final String member =
                    read.equals("stories") || read.equals("outline")
                            ? "stories"
                            : read.equals("search") ? "matches" : "issues";
            assertThat(answer.path(member).size())
                    .as("%s defaults to fifty whole items", read)
                    .isEqualTo(50);
            assertThat(answer.path("next").isString())
                    .as("%s exposes the remaining board", read)
                    .isTrue();
        }
        final JsonNode first =
                JsonMapper.builder()
                        .build()
                        .readTree(
                                mvc.perform(
                                                get("/projects/alpha/stories")
                                                        .param("limit", "1")
                                                        .param(
                                                                "state", "Backlog", "Review",
                                                                "Backlog")
                                                        .param("priority", "High")
                                                        .param("repository", "porelo-store"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString());
        mvc.perform(
                        get("/projects/alpha/stories")
                                .param("limit", "2")
                                .param("state", "Review", "Backlog")
                                .param("priority", "High")
                                .param("repository", "porelo-store")
                                .param("after", first.path("next").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories[0].id").value("ST2"))
                .andExpect(jsonPath("$.stories.length()").value(2));
    }

    /** Guards the response bound when whole-board metadata cannot be paged. */
    @Test
    void oversizedWholeBoardCountsAreRefusedEvenWhenNoStoryMatches() throws Exception {
        // Harm: a caller's filter cannot bound the mandatory whole-board metadata.
        final List<String> states = new ArrayList<>(List.of("Backlog"));
        for (int n = 1; n <= 3000; n++) states.add("State" + n);
        current =
                new Board(
                        new Revision("metadata"),
                        new Workflow(states, List.of(), List.of()),
                        List.of(),
                        List.of(),
                        board().work());
        mvc.perform(get("/projects/alpha/stories").param("state", "Review"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("PageTooLarge"));
        mvc.perform(get("/projects/alpha/outline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories").isEmpty())
                .andExpect(jsonPath("$.next").value(org.hamcrest.Matchers.nullValue()));
    }

    private static Board largeBoard() {
        return largeBoard("Cache " + "\\\"\n雪".repeat(120));
    }

    private static Board largeBoard(final String title) {
        final List<Story> stories = new ArrayList<>();
        final List<Issue> issues = new ArrayList<>();
        for (int n = 1; n <= 205; n++) {
            stories.add(
                    new Story(
                            "ST" + n, title, "Backlog", "High", null, List.of("AB" + n), "", null));
            issues.add(issue("AB" + n, title, "Backlog", "S", "High", null, ""));
        }
        for (int n = 1; n <= 205; n++)
            issues.add(issue("XY" + n, title, "Backlog", "S", "High", null, ""));
        return new Board(
                new Revision("large"), board().workflow(), stories, issues, board().work());
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
