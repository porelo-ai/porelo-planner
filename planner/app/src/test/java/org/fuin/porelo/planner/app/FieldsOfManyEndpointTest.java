package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The fields of many issues and the priorities of stories set in one call, as an agent puts it. */
class FieldsOfManyEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final Map<String, String> sizes = new LinkedHashMap<>();

    private final Map<String, String> priorities = new LinkedHashMap<>();

    private final Map<String, String> storyPriorities = new LinkedHashMap<>();

    private final Map<String, List<String>> storyIssues = new LinkedHashMap<>();

    private final List<String> written = new ArrayList<>();

    private int revision = 1;

    private int writeCalls;

    private int changedWrites;

    private Board current;

    private MockMvc mvc;

    private ChangesRegistry changes;

    FieldsOfManyEndpointTest() {
        for (int i = 1; i <= 10; i++) {
            sizes.put("AB" + i, "S");
            priorities.put("AB" + i, "Low");
        }
        priorities.put("AB1", "Highest");
        priorities.put("AB2", "High");
        priorities.put("AB3", "Medium");
        storyPriorities.put("ST1", "Highest");
        storyIssues.put("ST1", List.of("AB1", "AB2", "AB3"));
        storyPriorities.put("ST2", "Low");
        storyIssues.put("ST2", List.of("AB4"));
        current = FieldsOfManyEndpointTest.this.board();
    }

    private Board board() {
        final List<Issue> issues = new ArrayList<>();
        sizes.forEach(
                (id, size) ->
                        issues.add(
                                new Issue(
                                                id,
                                                "porelo-store",
                                                "An issue",
                                                "Backlog",
                                                size,
                                                List.of(),
                                                null,
                                                null,
                                                "")
                                        .withPriority(priorities.getOrDefault(id, "Low"))));
        final List<Story> stories = new ArrayList<>();
        storyPriorities.forEach(
                (id, priority) ->
                        stories.add(
                                new Story(
                                        id,
                                        "A story",
                                        "Backlog",
                                        priority,
                                        null,
                                        storyIssues.getOrDefault(id, List.of()),
                                        "",
                                        null)));
        return new Board(
                new Revision("r" + revision),
                new Workflow(
                        List.of("Idea", "Backlog", "Finished"), List.of(), List.of("Finished")),
                stories,
                issues,
                new Work(Map.of(), List.of()));
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
                    public Board setFields(
                            final Revision revision, final String issue, final Fields fields) {
                        writeCalls++;
                        if (changedWrites > 0) {
                            // Another change reached the issue between the judging and the save.
                            changedWrites--;
                            sizes.put("AB1", "XL");
                            current = FieldsOfManyEndpointTest.this.board();
                            throw new StoreException.Changed(List.of(issue));
                        }
                        if (fields.named().contains(Fields.Field.SIZE)) {
                            sizes.put(issue, fields.size());
                            written.add(issue + " size " + fields.size());
                        }
                        if (fields.named().contains(Fields.Field.PRIORITY)) {
                            priorities.put(issue, fields.priority());
                            written.add(issue + " priority " + fields.priority());
                        }
                        return next();
                    }

                    @Override
                    public Board setStoryIssues(
                            final Revision revision,
                            final String story,
                            final List<String> issues) {
                        writeCalls++;
                        storyIssues.put(story, issues);
                        written.add(story + " issues " + issues);
                        return next();
                    }

                    @Override
                    public Board save(final Revision revision, final Writes writes) {
                        writeCalls++;
                        writes.priorities()
                                .forEach(
                                        (story, priority) -> {
                                            storyPriorities.put(story, priority);
                                            written.add(story + " priority " + priority);
                                        });
                        return next();
                    }

                    private Board next() {
                        FieldsOfManyEndpointTest.this.revision++;
                        current = FieldsOfManyEndpointTest.this.board();
                        return current;
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new BoardController(projects, changes),
                                new FieldsOfManyController(projects, changes))
                        .setControllerAdvice(new Problems(projects))
                        .build();
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    @AfterEach
    void close() {
        changes.close();
    }

    private ResultActions setFields(final String body) throws Exception {
        return mvc.perform(
                put("/projects/alpha/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    void aRefusedFieldsItemValidatesAgainstThePublishedSchema() throws Exception {
        // Harm: an invalid refusal hides why one item of a batch could not be saved.
        final String answer =
                setFields("{\"issues\":[{\"issue\":\"CD9\",\"size\":\"M\"}]}")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        ItemResultSchema.matches(answer, 0);
    }

    @Test
    void tenSizesAreSetInOneCallAndEachIsAnsweredSaved() throws Exception {
        // Harm: a call that saves only some of the sizes, or answers them out of order.
        final StringBuilder body = new StringBuilder("{\"issues\": [");
        for (int i = 1; i <= 10; i++) {
            body.append(i > 1 ? "," : "").append("{\"issue\": \"AB" + i + "\", \"size\": \"M\"}");
        }

        setFields(body.append("]}").toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r11"))
                .andExpect(jsonPath("$.results.length()").value(10))
                .andExpect(jsonPath("$.results[0].item").value("AB1"))
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[9].item").value("AB10")) // not-a-citation
                .andExpect(jsonPath("$.results[9].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[9].refusal").doesNotExist())
                .andExpect(jsonPath("$.results[9].raised").doesNotExist());

        assertThat(sizes.values()).as("every issue's size is written").containsOnly("M");
        assertThat(writeCalls).as("one write per issue").isEqualTo(10);
    }

    @Test
    void membershipChangesAnswerTheIssueAndBothParentsAndEachItemsFinalVersion() throws Exception {
        // Harm: a follow-up membership edit uses a stale version of the story the issue left.
        setFields("{\"issues\":[{\"issue\":\"AB1\",\"story\":\"ST2\",\"size\":\"M\"}]}")
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.versions.AB1")
                                .value(current.issue("AB1").orElseThrow().version().value()))
                .andExpect(
                        jsonPath("$.versions.ST1")
                                .value(current.story("ST1").orElseThrow().version().value()))
                .andExpect(
                        jsonPath("$.versions.ST2")
                                .value(current.story("ST2").orElseThrow().version().value()))
                .andExpect(
                        jsonPath("$.results[0].version")
                                .value(current.issue("AB1").orElseThrow().version().value()));
        setFields("{\"issues\":[{\"issue\":\"AB1\",\"size\":\"M\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("UNCHANGED"))
                .andExpect(
                        jsonPath("$.results[0].version")
                                .value(current.issue("AB1").orElseThrow().version().value()));
    }

    @Test
    void aStoryGivenALowerPriorityRaisesTheIssuesTheRuleNeedsAndNamesThem() throws Exception {
        // Harm: a story saved over issues that break the stories' priority rule, unnamed.
        setFields("{\"stories\": [{\"story\": \"ST1\", \"priority\": \"Medium\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].item").value("ST1"))
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[0].raised.length()").value(2))
                .andExpect(jsonPath("$.results[0].raised[0]").value("AB1"))
                .andExpect(jsonPath("$.results[0].raised[1]").value("AB2"))
                .andExpect(jsonPath("$.versions.ST1").exists())
                .andExpect(jsonPath("$.versions.AB1").exists())
                .andExpect(jsonPath("$.versions.AB2").exists());

        assertThat(written)
                .as("the story's priority and each raised issue's, and no other issue's")
                .containsExactly(
                        "ST1 priority Medium", "AB1 priority Medium", "AB2 priority Medium");
    }

    @Test
    void aStoryLoweredBesideAHigherPriorityRefusedForItsIssueRaisesThatIssueAndNamesIt()
            throws Exception {
        // Harm: a story saved below an issue whose sent priority was refused, with no word of it.
        setFields(
                        "{\"issues\": [{\"issue\": \"AB4\", \"priority\": \"Medium\"}],"
                                + " \"stories\": ["
                                + "{\"story\": \"ST2\", \"priority\": \"Lowest\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].item").value("AB4"))
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[1].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[1].raised[0]").value("AB4"));

        assertThat(written)
                .as("the story's priority, then the issue raised to it, not to what was sent")
                .containsExactly("ST2 priority Lowest", "AB4 priority Lowest");
    }

    @Test
    void anIssueTheRulesRefuseIsNamedWithItsRefusalWhileTheOthersAreSaved() throws Exception {
        // Harm: one refused issue holding back the others, or dropped without its refusal.
        setFields(
                        "{\"issues\": ["
                                + "{\"issue\": \"AB1\", \"size\": \"M\"},"
                                + "{\"issue\": \"CD9\", \"size\": \"M\"},"
                                + "{\"issue\": \"AB2\", \"size\": \"L\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[1].item").value("CD9"))
                .andExpect(jsonPath("$.results[1].outcome").value("REFUSED"))
                .andExpect(
                        jsonPath("$.results[1].refusal.type")
                                .value("urn:porelo-planner:NoSuchIssue"))
                .andExpect(jsonPath("$.results[1].refusal.status").value(404))
                .andExpect(jsonPath("$.results[2].outcome").value("SAVED"));

        assertThat(written)
                .as("the two allowed issues, and no other")
                .containsExactly("AB1 size M", "AB2 size L");
    }

    @Test
    void anIssueGivenAStoryJoinsItAndTheStoriesListIsWrittenWhole() throws Exception {
        // Harm: an issue left in the story that held it.
        setFields("{\"issues\": [{\"issue\": \"AB4\", \"story\": \"ST1\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"));

        assertThat(storyIssues.get("ST1"))
                .as("the story's list with the issue after its own")
                .containsExactly("AB1", "AB2", "AB3", "AB4");
    }

    @Test
    void aValueAnIssueHoldsAlreadyIsUnchangedAndNothingIsWritten() throws Exception {
        // Harm: a commit for a change that changes nothing.
        setFields(
                        "{\"issues\": [{\"issue\": \"AB1\", \"size\": \"S\"}],"
                                + " \"stories\": ["
                                + "{\"story\": \"ST1\", \"priority\": \"Highest\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r1"))
                .andExpect(jsonPath("$.results[0].outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.results[1].outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.results[1].raised.length()").value(0));

        assertThat(writeCalls).as("the store is not asked").isZero();
    }

    @Test
    void aBoardChangedBetweenTheJudgingAndTheSaveIsJudgedAgainOnceAndSaved() throws Exception {
        // Harm: a change saved over an issue as it no longer stands, or refused for a change a
        // second look finds harmless.
        changedWrites = 1;

        setFields("{\"issues\": [{\"issue\": \"AB1\", \"size\": \"M\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"));

        assertThat(writeCalls).as("one write refused as changed, one that stands").isEqualTo(2);
        assertThat(written).as("the size written once").containsExactly("AB1 size M");
    }

    @Test
    void aBoardChangedAgainWritesNothingAndAnswersItemChanged() throws Exception {
        // Harm: a call that keeps trying against a board that keeps moving.
        changedWrites = 2;

        setFields("{\"issues\": [{\"issue\": \"AB1\", \"size\": \"M\"}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:ItemChanged"));

        assertThat(writeCalls).as("the write tried twice and no more").isEqualTo(2);
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aBodyThatIsNoSuchCallIsRefusedBeforeAnythingIsJudged() throws Exception {
        // Harm: a member the planner does not take, or too many items, read as a smaller call.
        setFields("{}").andExpect(status().isUnprocessableContent());
        setFields("{\"issues\": [], \"stories\": []}").andExpect(status().isUnprocessableContent());
        setFields("{\"issues\": [{\"issue\": \"AB1\", \"size\": \"M\"}], \"other\": 1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:EditNotAllowed"));
        setFields("{\"issues\": [{\"issue\": \"AB1\", \"size\": \"M\", \"other\": 1}]}")
                .andExpect(status().isUnprocessableContent());
        setFields("{\"issues\": [{\"issue\": \"AB1\", \"size\": \"XXL\"}]}")
                .andExpect(status().isUnprocessableContent());
        setFields("{\"issues\": [{\"issue\": \"AB1\", \"priority\": \"Urgent\"}]}")
                .andExpect(status().isUnprocessableContent());
        setFields("{\"stories\": [{\"story\": \"ST1\"}]}")
                .andExpect(status().isUnprocessableContent());
        setFields(
                        "{\"issues\": ["
                                + "{\"issue\": \"AB1\", \"size\": \"M\"},".repeat(200)
                                + "{\"issue\": \"AB1\", \"size\": \"M\"}]}")
                .andExpect(status().isUnprocessableContent());
        setFields("{\"issues\": [{\"size\": \"M\"}]}").andExpect(status().isUnprocessableContent());
        setFields("{\"issues\": [{\"issue\": \"../x\", \"size\": \"M\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
        setFields("{\"issues\": [{\"issue\": \"AB1\", \"story\": \"../x\"}]}")
                .andExpect(status().isBadRequest());

        assertThat(writeCalls).as("the store is not asked").isZero();
    }

    @Test
    void aSizeSentAsNullClearsTheSizeOfTheIssue() throws Exception {
        // Harm: a null size read as a size left out, so a clear never happens.
        setFields("{\"issues\": [{\"issue\": \"AB1\", \"size\": null}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"));

        assertThat(sizes).as("the size is cleared").containsEntry("AB1", null);
    }
}
