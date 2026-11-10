package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The board reads as the window calls them, over a store that answers what a test gives it. */
class BoardReadsTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<String> opened = new ArrayList<>();

    private @Nullable RuntimeException refusal;

    private MockMvc mvc;

    private static Board board() {
        final Map<String, WorkRule> rules = new LinkedHashMap<>();
        rules.put(
                "Implementing",
                new WorkRule(
                        List.of("Backlog"),
                        "Review",
                        null,
                        null,
                        WorkRule.Per.REPOSITORY,
                        List.of("UNATTENDED"),
                        "Build {issues}.",
                        Map.of("claude", "claude-opus-5-5")));
        rules.put(
                "Finished",
                new WorkRule(
                        List.of("Review"),
                        "Review",
                        null,
                        null,
                        WorkRule.Per.STORY,
                        List.of("AGENT"),
                        "Close it.",
                        Map.of("claude", "claude-opus-5-5")));
        return new Board(
                new Revision("porelo-store@c1"),
                new Workflow(
                        List.of("Backlog", "Implementing", "Review", "Finished"),
                        List.of(new Workflow.Phase("Open", List.of("Backlog", "Implementing"))),
                        List.of("Finished")),
                List.of(
                        new Story(
                                "ST1",
                                "Given",
                                "Review",
                                true,
                                "High",
                                List.of("AB01"),
                                "The story's text.",
                                URI.create("https://example.org/ST1")),
                        new Story(
                                "ST2",
                                "By issues",
                                "Backlog",
                                false,
                                "Low",
                                List.of("AB01.1", "AB01"),
                                "Another text.",
                                null)),
                List.of(
                        new Issue(
                                "AB01",
                                "porelo-store",
                                "Parent",
                                "Backlog",
                                "S",
                                List.of(),
                                null,
                                null,
                                "# AB01 — Parent\n\n**What must be true.** The parent holds,\nin two lines.\n\n## Plan\n"),
                        new Issue(
                                "AB01.1",
                                "porelo-store",
                                "Child",
                                "Review",
                                null,
                                List.of("AB01"),
                                "AB01",
                                null,
                                "No such paragraph.")),
                new Work(rules, List.of("stories/work.yml: Testing: no state of the workflow")));
    }

    @BeforeEach
    void serve() {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        if (refusal != null) {
                            throw refusal;
                        }
                        return BoardReadsTest.board();
                    }

                    @Override
                    public Board refresh() {
                        return board();
                    }
                };
        final Projects projects =
                new Projects(
                        (project, repository, branch) -> {
                            if (repository.startsWith("/")) {
                                throw new Refusal(
                                        "NotACloneUrl",
                                        HttpStatus.BAD_REQUEST,
                                        "Not a clone URL the planner takes",
                                        Map.of("project", project));
                            }
                            opened.add(project + " " + repository + " " + branch);
                            return store;
                        },
                        8);
        mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects))
                        .setControllerAdvice(new Problems())
                        .build();
    }

    private void given() throws Exception {
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    @Test
    void aProjectNotGivenIsNoSuchProjectAndOneGivenAnswers() throws Exception {
        // The window sends its PUT and retries once on exactly this answer.
        mvc.perform(get("/projects/alpha/workflow"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"))
                .andExpect(jsonPath("$.parameters.project").value("alpha"));
        given();
        given();
        assertThat(opened).as("the same source opened once").hasSize(1);
        mvc.perform(get("/projects/alpha/workflow")).andExpect(status().isOk());
    }

    @Test
    void theWorkflowGivesEachStateItsKindAndOnlyTheSoundRules() throws Exception {
        // A closed state shown as working would let the window start work on a finished story.
        given();
        mvc.perform(get("/projects/alpha/workflow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("porelo-store@c1"))
                .andExpect(jsonPath("$.states[0].name").value("Backlog"))
                .andExpect(jsonPath("$.states[0].kind").value("WAITING"))
                .andExpect(jsonPath("$.states[1].kind").value("WORKING"))
                .andExpect(jsonPath("$.states[2].kind").value("WAITING"))
                .andExpect(jsonPath("$.states[3].kind").value("CLOSED"))
                .andExpect(jsonPath("$.phases[0].states[1]").value("Implementing"))
                .andExpect(jsonPath("$.work.length()").value(1))
                .andExpect(jsonPath("$.work[0].state").value("Implementing"))
                .andExpect(jsonPath("$.work[0].per").value("repository"))
                .andExpect(jsonPath("$.work[0].then").isEmpty())
                .andExpect(jsonPath("$.work[0].models.claude").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.faults.length()").value(2))
                .andExpect(
                        jsonPath("$.faults[1]")
                                .value("stories/work.yml: Finished: a closed state"));
    }

    @Test
    void theStoriesCarryTheirIssuesAndWhetherTheirStateWasGivenButNoDescription() throws Exception {
        // A story whose state was given by hand must not be moved by its issues' states.
        given();
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories.length()").value(2))
                .andExpect(jsonPath("$.stories[0].stateGiven").value(true))
                .andExpect(jsonPath("$.stories[0].link").value("https://example.org/ST1"))
                .andExpect(jsonPath("$.stories[0].description").doesNotExist())
                .andExpect(jsonPath("$.stories[0].work").isEmpty())
                .andExpect(jsonPath("$.stories[0]", org.hamcrest.Matchers.hasKey("work")))
                .andExpect(jsonPath("$.stories[1].stateGiven").value(false))
                .andExpect(jsonPath("$.stories[1].issues[0].id").value("AB01.1"))
                .andExpect(jsonPath("$.stories[1].issues[1].id").value("AB01"));
        mvc.perform(get("/projects/alpha/stories").param("state", "Backlog"))
                .andExpect(jsonPath("$.stories.length()").value(1))
                .andExpect(jsonPath("$.stories[0].id").value("ST2"));
    }

    @Test
    void oneStoryCarriesItsDescriptionAndItsIssuesInItsOrder() throws Exception {
        given();
        mvc.perform(get("/projects/alpha/stories/ST1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("porelo-store@c1"))
                .andExpect(jsonPath("$.story.description").value("The story's text."))
                .andExpect(jsonPath("$.story.issues[0].id").value("AB01"));
        mvc.perform(get("/projects/alpha/stories/ST2/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[0].id").value("AB01.1"))
                .andExpect(jsonPath("$.issues[0].parent").value("AB01"))
                .andExpect(jsonPath("$.issues[0].size").isEmpty())
                .andExpect(jsonPath("$.issues[0].blockedBy[0]").value("AB01"));
    }

    @Test
    void oneIssueCarriesItsTextItsSubIssuesAndWhatMustBeTrueWhenItHasIt() throws Exception {
        given();
        mvc.perform(get("/projects/alpha/issues/AB01"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.issue.whatMustBeTrue")
                                .value("The parent holds,\nin two lines."))
                .andExpect(jsonPath("$.issue.subIssues[0]").value("AB01.1"))
                .andExpect(jsonPath("$.issue.parent").isEmpty())
                .andExpect(jsonPath("$.issue", org.hamcrest.Matchers.hasKey("parent")));
        mvc.perform(get("/projects/alpha/issues/AB01.1"))
                .andExpect(jsonPath("$.issue.whatMustBeTrue").doesNotExist())
                .andExpect(jsonPath("$.issue.text").value("No such paragraph."));
    }

    @Test
    void anUnknownStoryOrIssueAndABadIdAreRefusedByName() throws Exception {
        given();
        mvc.perform(get("/projects/alpha/stories/ST9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchStory"))
                .andExpect(jsonPath("$.parameters.story").value("ST9"));
        mvc.perform(get("/projects/alpha/issues/ZZ9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchIssue"))
                .andExpect(jsonPath("$.parameters.issue").value("ZZ9"));
        mvc.perform(get("/projects/alpha/stories/bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
        mvc.perform(get("/projects/Bad..Name/workflow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
    }

    @Test
    void aRefusedSourceIsNotACloneUrlAndLeavesNoProject() throws Exception {
        mvc.perform(
                        put("/projects/alpha")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"repository\": \"/home/person/git/p\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotACloneUrl"));
        mvc.perform(get("/projects/alpha/workflow")).andExpect(status().isNotFound());
    }

    @Test
    void eachStoreRefusalIsItsProblemNamingTheRepository() throws Exception {
        // The window offers Try again on these and shows the planner's words.
        given();
        refusal = new StoreException.CredentialRefused("porelo-store");
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CredentialRefused"))
                .andExpect(jsonPath("$.parameters.repository").value("porelo-store"));
        refusal = new StoreException.Unreachable("porelo-store", "timed out");
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Unreachable"));
        refusal = new StoreException.Unreadable("porelo-store", "issues/README.md is no index");
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Unreadable"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(org.hamcrest.Matchers.containsString("no index")));
        refusal = new StoreException.WorkflowMissing("porelo-store");
        mvc.perform(get("/projects/alpha/workflow"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:WorkflowMissing"));
    }

    @Test
    void aBodyThatIsNoSourceIsAProblemTheWindowCanRead() throws Exception {
        // The window shows the planner's words; an answer of another shape would show none.
        mvc.perform(
                        put("/projects/alpha")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"repository\": [1]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotACloneUrl"))
                .andExpect(jsonPath("$.parameters.project").value("alpha"));
        mvc.perform(
                        put("/projects/Bad..Name")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"))
                .andExpect(jsonPath("$.parameters.project").doesNotExist());
    }
}
