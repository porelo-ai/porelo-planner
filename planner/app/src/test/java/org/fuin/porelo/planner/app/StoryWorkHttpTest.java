package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StoryWorkHttpTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "/projects/project/stories/ST1";
    private static final String MINIMAL =
            """
            {"project":"project","story":"ST1","to":"Building","mode":"AGENT","revision":"board@1"}
            """;
    private static final String SELECTED =
            """
            {"project":"project","story":"ST1","to":"Building","mode":"AGENT","revision":"board@1",
             "repositories":[{"repository":"alpha","issues":["AA1","AA1.1"],
              "main":{"name":"main","role":"MAIN","agent":"codex","model":"model-a",
                      "brief":"Build {story}: {issues}; {tasks}"},
              "subAgents":[{"id":"alpha/AA1.1/BUILDER","issue":"AA1.1","name":"builder","kind":"BUILDER",
                            "agent":"codex","model":"model-a","workingState":"Building",
                            "brief":"AA1.1 — Child\\nKeep the data.","choice":"KEEP"}]}],
             "comment":"Keep the proposal.","readingRounds":2,"issueReadingRounds":[{"issue":"AA1.1","rounds":3}]}
            """;
    private final AccessToken token = AccessToken.random();
    private @Nullable RuntimeException failure;
    private MockMvc mvc;
    private Board board;

    @BeforeEach
    void serveAFakeBoardThroughTheRealControllerAndAdvice() {
        board =
                new Board(
                        new Revision("board@1"),
                        new Workflow(
                                List.of("Backlog", "Building", "Review"), List.of(), List.of()),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story",
                                        "Backlog",
                                        true,
                                        "High",
                                        List.of("AA1"),
                                        "Description",
                                        null)),
                        List.of(
                                new Issue(
                                        "AA1", "alpha", "Parent", "Backlog", null, List.of(), null,
                                        null, ""),
                                new Issue(
                                        "AA1.1",
                                        "alpha",
                                        "Child",
                                        "Backlog",
                                        null,
                                        List.of(),
                                        "AA1",
                                        null,
                                        "**What must be true.** Keep the data.\n\nNotes")),
                        new Work(
                                Map.of(
                                        "Building",
                                        new WorkRule(
                                                List.of("Backlog"),
                                                "Review",
                                                null,
                                                null,
                                                WorkRule.Per.REPOSITORY,
                                                List.of("AGENT"),
                                                "Build {story}: {issues}; {tasks}",
                                                Map.of("codex", "model-a"))),
                                List.of()),
                        List.of("project", "alpha"));
        final PlanningStore store =
                new PlanningStore() {
                    /** Supplies the immutable board or the scenario's refusal. */
                    @Override
                    public Board board() {
                        if (failure != null) {
                            throw failure;
                        }
                        return board;
                    }

                    /** Preserves the board because preparing work never saves it. */
                    @Override
                    public Board refresh() {
                        return board();
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store, 8);
        projects.put("project", "https://example.org/p/project.git", null);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new BoardController(
                                        projects,
                                        new ChangesRegistry(
                                                projects,
                                                java.time.Duration.ofHours(1),
                                                java.util.concurrent.Executors
                                                        .newSingleThreadScheduledExecutor())))
                        .setControllerAdvice(new Problems())
                        .addFilters(new AccessFilter(token))
                        .build();
    }

    @Test
    void theWindowsExactBodiesProduceAProposalAndSeparateStartArguments() throws Exception {
        // Misreading the window's body can launch the wrong participant or lose its work.
        final JsonNode proposal = json(call(BASE + "/proposal", "{\"to\":\"Building\"}"), 200);
        assertThat(proposal.propertyNames())
                .as("the window reads the proposal directly")
                .containsExactlyInAnyOrder(
                        "modes",
                        "repositories",
                        "coordinator",
                        "comment",
                        "readingRounds",
                        "issueReadingRounds",
                        "revision",
                        "manual");
        assertThat(
                        proposal.get("repositories")
                                .get(0)
                                .get("subAgents")
                                .get(0)
                                .get("kind")
                                .asString())
                .as("reading does not start with implementation")
                .isEqualTo("BUILDER");
        final JsonNode order = json(call(BASE + "/work", SELECTED), 200);
        assertThat(order.propertyNames())
                .as("the response is an order, not a fabricated running record")
                .containsExactlyInAnyOrder("revision", "tasks");
        assertThat(order.get("tasks").size()).as("one main and one builder").isEqualTo(2);
        assertThat(order.get("tasks").get(0).propertyNames())
                .as("each entry is the complete Start argument record")
                .containsExactlyInAnyOrder(
                        "offer",
                        "repository",
                        "issues",
                        "role",
                        "agent",
                        "model",
                        "mode",
                        "prompt");
        assertThat(order.get("tasks").get(1).get("prompt").asString())
                .as("the selected operator comment reaches helpers")
                .contains("Keep the proposal.", "Keep the data.")
                .doesNotContain("Notes");
        assertThat(order.get("revision").asString())
                .as("no store revision changes")
                .isEqualTo("board@1");
        assertThat(board.stories().getFirst().state()).as("no story moves").isEqualTo("Backlog");
        assertThat(json(call(BASE + "/work", MINIMAL), 200).get("tasks").size())
                .as("omitted repositories and limits use the recomputed proposal")
                .isEqualTo(2);
    }

    @Test
    void theServedProposalNamesTheIssuesAPersonWorksAndGivesThemNoTask() throws Exception {
        // A task served for a person's issue would put an agent on the work they are doing.
        final Story story = board.stories().getFirst();
        board =
                new Board(
                        board.revision(),
                        board.workflow(),
                        List.of(
                                new Story(
                                        story.id(),
                                        story.title(),
                                        story.state(),
                                        story.stateGiven(),
                                        story.priority(),
                                        List.of("AA1", "BB1"),
                                        story.description(),
                                        story.link())),
                        java.util.stream.Stream.concat(
                                        board.issues().stream(),
                                        java.util.stream.Stream.of(
                                                new Issue(
                                                        "BB1",
                                                        "alpha",
                                                        "A person's",
                                                        "Backlog",
                                                        null,
                                                        List.of(),
                                                        null,
                                                        null,
                                                        "",
                                                        Worker.MANUAL)))
                                .toList(),
                        board.work(),
                        board.repositories());

        final JsonNode proposal = json(call(BASE + "/proposal", "{\"to\":\"Building\"}"), 200);

        assertThat(proposal.get("manual").toString())
                .as("the person's issue is named")
                .isEqualTo("[\"BB1\"]");
        assertThat(proposal.at("/repositories/0/issues").toString())
                .as("no task names it")
                .isEqualTo("[\"AA1\",\"AA1.1\"]");
        final JsonNode order = json(call(BASE + "/work", MINIMAL), 200);
        for (final JsonNode task : order.get("tasks")) {
            assertThat(task.get("issues").toString())
                    .as("no task of the order names the person's issue")
                    .doesNotContain("BB1");
        }
    }

    @Test
    void aDiscussionIsOneTaskOverEveryRepositoryThatNamesNoneAndHandsBackTheIdea()
            throws Exception {
        // A discussion that named a repository would start in one checkout and miss the others.
        final JsonNode task =
                json(
                        call(
                                "/projects/project/ideas/discussion",
                                "{\"idea\":\"Let people tag stories\"}"),
                        200);

        assertThat(task.propertyNames())
                .as("a Start argument record without a repository, with the file it hands back")
                .containsExactlyInAnyOrder(
                        "offer", "issues", "role", "agent", "model", "mode", "prompt", "result");
        assertThat(
                        List.of(
                                task.get("role").asString(),
                                task.get("mode").asString(),
                                task.get("agent").asString(),
                                task.get("model").asString(),
                                task.get("result").asString()))
                .as("a conversation with the rule's agent, handing back the idea")
                .containsExactly("DISCUSSION", "AGENT", "codex", "model-a", "idea.json");
        assertThat(task.get("issues").size()).as("no issue is the discussion's").isZero();
        assertThat(task.get("prompt").asString())
                .as("the brief names both repositories and the person's words")
                .contains("/workspace/project/", "/workspace/alpha/", "Let people tag stories");
        assertThat(board.stories()).as("nothing on the board changes").hasSize(1);
    }

    @Test
    void aDiscussionsWordsAreCheckedAndAnUnknownProjectIsNotFound() throws Exception {
        // Unchecked words would start an agent with no topic, or a body of any size.
        for (final String body :
                List.of(
                        "{\"idea\":\"\"}",
                        "{\"idea\":\" \\n \"}",
                        "{\"idea\":\"" + "x".repeat(4001) + "\"}",
                        "{\"idea\":1}",
                        "{\"idea\":\"An idea\",\"repository\":\"alpha\"}",
                        "[]",
                        "{\"idea\":\"" + "x".repeat(70000) + "\"}")) {
            assertThat(
                            json(call("/projects/project/ideas/discussion", body), 400)
                                    .get("type")
                                    .asString())
                    .as("the refusal of %s", body.length() > 80 ? body.length() + " chars" : body)
                    .isEqualTo("urn:porelo-planner:InvalidSettings");
        }
        assertThat(
                        json(
                                        call(
                                                "/projects/other/ideas/discussion",
                                                "{\"idea\":\"An idea\"}"),
                                        404)
                                .get("type")
                                .asString())
                .as("a project the planner does not hold")
                .isEqualTo("urn:porelo-planner:NoSuchProject");
        assertThat(
                        json(call("/projects/BAD%20name/ideas/discussion", "{"), 400)
                                .get("type")
                                .asString())
                .as("the path is checked before the body, as every work call does")
                .isEqualTo("urn:porelo-planner:InvalidName");
    }

    @Test
    void forgedFactsChoicesAndLimitsAreRefusedAsTypedProblems() throws Exception {
        // A refused decision must not turn into successful or unreviewed work over HTTP.
        for (final String body :
                List.of(
                        SELECTED.replace("Keep the data.", "Forged criteria."),
                        SELECTED.replace("\"choice\":\"KEEP\"", "\"choice\":\"UNKNOWN\""),
                        SELECTED.replace("\"readingRounds\":2", "\"readingRounds\":1.5"),
                        SELECTED.replace("\"readingRounds\":2", "\"readingRounds\":11"),
                        SELECTED.replace("\"rounds\":3", "\"rounds\":0"),
                        SELECTED.replace(
                                "\"issue\":\"AA1.1\",\"rounds\":3",
                                "\"issue\":\"ZZ9\",\"rounds\":3"))) {
            final JsonNode problem = json(call(BASE + "/work", body), 400);
            assertThat(problem.get("type").asString())
                    .as("semantic refusal type")
                    .isEqualTo("urn:porelo-planner:WorkNotAllowed");
            assertThat(problem.get("parameters").propertyNames())
                    .as("workflow context and safe reason")
                    .containsExactlyInAnyOrder("from", "to", "reason");
        }
        final JsonNode stale = json(call(BASE + "/work", MINIMAL.replace("board@1", "old")), 409);
        assertThat(stale.at("/parameters/revision").asString())
                .as("the latest revision is returned")
                .isEqualTo("board@1");
        // A stale decision must be told as stale, whatever else it holds, so the window reloads.
        for (final String rounds :
                List.of(
                        "\"readingRounds\":1.5",
                        "\"issueReadingRounds\":[{\"issue\":\"AA1\",\"rounds\":0.5}]")) {
            final JsonNode staleAnyway =
                    json(
                            call(
                                    BASE + "/work",
                                    MINIMAL.replace("board@1", "old")
                                            .replace("}", "," + rounds + "}")),
                            409);
            assertThat(staleAnyway.get("title").asString())
                    .as("stale before a round count is judged: " + rounds)
                    .isEqualTo("StaleRevision");
        }
        failure = WorkRefusal.workRunning("ST1", List.of("main", "builder"));
        final JsonNode running = json(call(BASE + "/work", MINIMAL), 409);
        assertThat(running.at("/parameters/tasks").isArray())
                .as("task names keep their array type over HTTP")
                .isTrue();
        assertThat(running.at("/parameters/tasks/1").asString())
                .as("recorded task names survive the mapping")
                .isEqualTo("builder");
    }

    @Test
    void malformedOrOversizedBodiesAndPathDisagreementNeverEchoUncheckedText() throws Exception {
        // Parser messages and unchecked identifiers can expose private text in refusal parameters.
        for (final String malformed :
                List.of(
                        "{\"private-password\":",
                        "null",
                        "[]",
                        "{}",
                        "{} {}",
                        "{\"to\":false}",
                        "{\"to\":\"Building\",\"to\":\"Review\"}",
                        MINIMAL.replace(
                                "\"project\":\"project\"", "\"project\":\"private-password\""),
                        MINIMAL.replace("\"to\":\"Building\"", "\"to\":\"private-password\""),
                        MINIMAL.strip() + " ".repeat(65536))) {
            final MockHttpServletResponse response = call(BASE + "/work", malformed);
            final JsonNode problem = json(response, 400);
            assertThat(problem.get("type").asString())
                    .as("unreadable work uses its own refusal")
                    .isEqualTo("urn:porelo-planner:InvalidSettings");
            assertThat(response.getContentAsString())
                    .as("fixed details exclude the parser's input")
                    .doesNotContain("private-password", "JsonParse", "NotACloneUrl");
        }
        final JsonNode badPath = json(call("/projects/BAD/stories/ST1/work", "{"), 400);
        assertThat(badPath.get("type").asString())
                .as("path validation precedes body refusal")
                .isEqualTo("urn:porelo-planner:InvalidName");
        assertThat(badPath.get("parameters").isEmpty())
                .as("an invalid project is never echoed")
                .isTrue();
        final JsonNode badStory = json(call("/projects/project/stories/BAD/work", "{"), 400);
        assertThat(badStory.get("parameters").propertyNames())
                .as("only the checked project survives")
                .containsExactly("project");
        final MockHttpServletResponse privatePath =
                call("/projects/project/stories/private-password/work", "{");
        assertThat(json(privatePath, 400).get("title").asString())
                .as("an invalid story path remains InvalidName")
                .isEqualTo("InvalidName");
        assertThat(privatePath.getContentAsString())
                .as("invalid story text never enters the full problem")
                .doesNotContain("private-password");
        final MockHttpServletResponse unreadableSource =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .put("/projects/BAD")
                                        .header("Authorization", "Bearer " + token.text())
                                        .contentType("application/json")
                                        .content("{private-password"))
                        .andReturn()
                        .getResponse();
        assertThat(json(unreadableSource, 400).get("title").asString())
                .as("a project's unreadable source still checks the project's name first")
                .isEqualTo("InvalidName");
        assertThat(unreadableSource.getContentAsString())
                .as("neither the name nor the body is repeated")
                .doesNotContain("BAD", "private-password");
    }

    @Test
    void missingSubjectsAndStoreFailuresKeepTheirExistingProblems() throws Exception {
        // New endpoints must preserve failures without exposing store details.
        assertThat(
                        json(
                                        call(
                                                "/projects/unknown/stories/ST1/proposal",
                                                "{\"to\":\"Building\"}"),
                                        404)
                                .get("title")
                                .asString())
                .as("unknown project")
                .isEqualTo("NoSuchProject");
        assertThat(
                        json(
                                        call(
                                                "/projects/project/stories/ST9/proposal",
                                                "{\"to\":\"Building\"}"),
                                        404)
                                .at("/parameters/story")
                                .asString())
                .as("checked absent story")
                .isEqualTo("ST9");
        for (final RuntimeException refusal :
                List.of(
                        new StoreException.WorkflowMissing("alpha"),
                        new StoreException.CredentialRefused("alpha"),
                        new StoreException.Unreadable("alpha", "Unreadable"),
                        new StoreException.Unreachable("alpha", "Unreachable"))) {
            failure = refusal;
            final int status = refusal instanceof StoreException.Unreachable ? 504 : 502;
            assertThat(
                            json(call(BASE + "/proposal", "{\"to\":\"Building\"}"), status)
                                    .at("/parameters/repository")
                                    .asString())
                    .as("store failure names only its repository")
                    .isEqualTo("alpha");
        }
    }

    @Test
    void missingOrWrongBearerTokensRefuseBothOperationsBeforeReadingTheBody() throws Exception {
        // Unauthenticated work bodies must never reach the planning rules.
        for (final String operation : List.of("proposal", "work")) {
            for (final String bearer : List.of("", "Bearer wrong")) {
                final MockHttpServletRequestBuilder request =
                        post(BASE + "/" + operation)
                                .contentType("application/json")
                                .content("{private-password");
                if (!bearer.isEmpty()) {
                    request.header("Authorization", bearer);
                }
                final MockHttpServletResponse response =
                        mvc.perform(request).andReturn().getResponse();
                assertThat(response.getStatus()).as("authentication status").isEqualTo(401);
                assertThat(response.getContentAsByteArray())
                        .as("authentication refuses with an empty body")
                        .isEmpty();
            }
        }
    }

    @Test
    void mockModeServesTheOperationsThroughARealHttpServer() throws Exception {
        // Standalone controller tests cannot show that the executable wires the new operations.
        final Path runtime = RuntimeDirectories.create();
        try (ConfigurableApplicationContext context =
                PlannerApplication.run("--planner.runtime-directory=" + runtime, "--mock")) {
            final RuntimeDirectories.Address address = RuntimeDirectories.read(runtime);
            assertThat(
                            http(
                                            address,
                                            "PUT",
                                            "/projects/any",
                                            "{\"repository\":\"https://example.org/p/project.git\"}")
                                    .statusCode())
                    .as("the mock project is prepared")
                    .isEqualTo(204);
            final String path = "/projects/any/stories/ST14";
            final HttpResponse<String> proposal =
                    http(address, "POST", path + "/proposal", "{\"to\":\"Implementing\"}");
            assertThat(proposal.statusCode())
                    .as("mock proposal over real HTTP: %s", proposal.body())
                    .isEqualTo(200);
            final HttpResponse<String> order =
                    http(
                            address,
                            "POST",
                            path + "/work",
                            """
                            {"project":"any","story":"ST14","to":"Implementing","mode":"AGENT","revision":"mock@1"}
                            """);
            assertThat(order.statusCode()).as("mock work order over real HTTP").isEqualTo(200);
            assertThat(JSON.readTree(order.body()).get("revision").asString())
                    .as("the mock revision stays unchanged")
                    .isEqualTo("mock@1");
        } finally {
            RuntimeDirectories.delete(runtime);
        }
    }

    private MockHttpServletResponse call(final String path, final String body) throws Exception {
        return mvc.perform(
                        post(path)
                                .header("Authorization", "Bearer " + token.text())
                                .contentType("application/json")
                                .content(body))
                .andReturn()
                .getResponse();
    }

    private static JsonNode json(final MockHttpServletResponse response, final int status)
            throws Exception {
        assertThat(response.getStatus())
                .as("HTTP status with body %s", response.getContentAsString())
                .isEqualTo(status);
        assertThat(response.getContentType())
                .as("the documented content type")
                .startsWith(status == 200 ? "application/json" : "application/problem+json");
        return JSON.readTree(response.getContentAsString());
    }

    private static HttpResponse<String> http(
            final RuntimeDirectories.Address address,
            final String method,
            final String path,
            final String body)
            throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + address.port() + path))
                                .header("Authorization", "Bearer " + address.token())
                                .header("Content-Type", "application/json")
                                .method(method, HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
