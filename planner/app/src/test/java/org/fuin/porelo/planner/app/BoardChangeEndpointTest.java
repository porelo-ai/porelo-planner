package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BoardChangeEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    /** The issues the store holds, by id, in the order made. */
    private final Map<String, Issue> issues = new LinkedHashMap<>();

    private final List<NewIssue> made = new ArrayList<>();

    private final List<Story> stories = new ArrayList<>();

    /** What the store throws for a write of an issue, by the issue's id. */
    private final Map<String, RuntimeException> fieldsFailures = new LinkedHashMap<>();

    private final Map<String, RuntimeException> blockersFailures = new LinkedHashMap<>();

    /** The lists a story was written with, by its id. */
    private final Map<String, List<String>> storyWrites = new LinkedHashMap<>();

    private int revision = 1;

    /** Whether every read of the board finds it moved, as another call's saves would leave it. */
    private boolean moving;

    /** Whether every read of the board finds issue AB1 rewritten, as another writer's saves do. */
    private boolean rewriting;

    private int rewrites;

    /** A per-item read-back may retain older cached values for the other items. */
    private @org.jspecify.annotations.Nullable Board snapshotBase;

    private MockMvc mvc;

    private ChangesRegistry changes;

    private Board board() {
        return new Board(
                new Revision("r" + revision),
                new Workflow(
                        List.of("Idea", "Backlog", "Finished"), List.of(), List.of("Finished")),
                List.copyOf(stories),
                List.copyOf(issues.values()),
                new Work(Map.of(), List.of()),
                ProjectRepository.named(List.of("porelo-store")));
    }

    /** The board as the change reads it, moved by each read while another call saves. */
    private Board read() {
        if (moving) {
            revision++;
        }
        if (rewriting) {
            rewrites++;
            issues.put("AB1", stored("AB1", "Rewritten " + rewrites, "M", List.of()));
        }
        return board();
    }

    private static Issue stored(
            final String id,
            final String title,
            final @org.jspecify.annotations.Nullable String size,
            final List<String> blockedBy) {
        return new Issue(
                id,
                "porelo-store",
                title,
                "Idea",
                size,
                blockedBy,
                null,
                null,
                "Text.",
                Worker.AGENT,
                Risk.ORDINARY);
    }

    @BeforeEach
    void serve() throws Exception {
        issues.put("AB1", stored("AB1", "Existing", null, List.of()));
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return read();
                    }

                    @Override
                    public Board refresh() {
                        return board();
                    }

                    @Override
                    public NewIssue.Added createIssue(final Revision seen, final NewIssue issue) {
                        made.add(issue);
                        final String id = "AB" + (issues.size() + 1);
                        issues.put(id, stored(id, issue.title(), issue.size(), List.of()));
                        revision++;
                        return new NewIssue.Added(id, board());
                    }

                    @Override
                    public Board setFields(
                            final Revision seen, final String id, final Fields fields) {
                        if (fieldsFailures.containsKey(id)) {
                            throw fieldsFailures.get(id);
                        }
                        final Issue now = issues.get(id);
                        issues.put(id, stored(id, now.title(), fields.size(), now.blockedBy()));
                        revision++;
                        final Board base = snapshotBase;
                        if (base != null) {
                            final Board after = board();
                            return new Board(
                                    after.revision(),
                                    after.workflow(),
                                    after.stories(),
                                    base.issues().stream()
                                            .map(
                                                    issue ->
                                                            issue.id().equals(id)
                                                                    ? issues.get(id)
                                                                    : issue)
                                            .toList(),
                                    after.work(),
                                    after.repositories());
                        }
                        return board();
                    }

                    @Override
                    public Board setStoryIssues(
                            final Revision seen, final String id, final List<String> list) {
                        storyWrites.put(id, list);
                        revision++;
                        return board();
                    }

                    @Override
                    public Board setBlockedBy(
                            final Revision seen, final String id, final List<String> blockedBy) {
                        if (blockersFailures.containsKey(id)) {
                            throw blockersFailures.get(id);
                        }
                        final Issue now = issues.get(id);
                        issues.put(id, stored(id, now.title(), now.size(), blockedBy));
                        revision++;
                        return board();
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        final BoardController window = new BoardController(projects, changes);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                window,
                                new BoardChangeController(
                                        projects,
                                        changes,
                                        window,
                                        new BoardEdits(projects, changes)))
                        .setControllerAdvice(new Problems(projects))
                        .build();
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    @AfterEach
    void close() {
        changes.close();
    }

    private ResultActions change(final String body) throws Exception {
        return mvc.perform(
                post("/projects/alpha/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static String create(final String key, final String blockedBy) {
        return "{\"key\": \""
                + key
                + "\", \"repository\": \"porelo-store\", \"title\": \"Title "
                + key
                + "\", \"parts\": {\"whatMustBeTrue\": \"It works.\"}"
                + (blockedBy.isEmpty() ? "" : ", \"blockedBy\": [" + blockedBy + "]")
                + "}";
    }

    @Test
    void completedItemsKeepTheirVersionsWhenALaterItemIsPartlySaved() throws Exception {
        // Harm: the last step's cached board replaces versions known from earlier read-backs, or
        // the partly saved item's version is answered although a step of it failed after writing.
        issues.put("AB2", stored("AB2", "Second", null, List.of()));
        snapshotBase = board();
        blockersFailures.put("AB2", new StoreException.Unreadable("porelo-store", "gone"));
        change(
                        "{\"updates\":[{\"issue\":\"AB1\",\"size\":\"M\"},"
                                + "{\"issue\":\"AB2\",\"size\":\"S\",\"blockedBy\":[\"AB1\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[1].refusal.title").value("PartlySaved"))
                .andExpect(jsonPath("$.versions.AB1").value(issues.get("AB1").version().value()))
                .andExpect(jsonPath("$.versions.AB2").doesNotExist())
                .andExpect(
                        jsonPath("$.results[0].version").value(issues.get("AB1").version().value()))
                .andExpect(jsonPath("$.results[1].version").doesNotExist());
    }

    @Test
    void aRefusedBoardChangeItemValidatesAgainstThePublishedSchema() throws Exception {
        // Harm: a schema error conceals the rule that refused an item of an agent's change.
        final String answer =
                change("{\"updates\":[{\"issue\":\"AB1\",\"state\":\"Nowhere\"}]}")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        ItemResultSchema.matches(answer, 0);
    }

    @Test
    void threeNewIssuesOneBlockingAnotherByKeyAndOneUpdateApplyInOneCallWithIdsAnsweredByKey()
            throws Exception {
        // Harm: an agent's plan of new issues written without the blocker it named by key, or its
        // ids lost, so it cannot tell which new issue is which.
        change(
                        "{\"creates\": ["
                                + create("first", "")
                                + ","
                                + create("second", "\"first\"")
                                + ","
                                + create("third", "")
                                + "], \"updates\": [{\"issue\": \"AB1\", \"size\": \"M\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(4))
                .andExpect(jsonPath("$.results[0].key").value("first"))
                .andExpect(jsonPath("$.results[0].item").value("AB2"))
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[1].key").value("second"))
                .andExpect(jsonPath("$.results[1].item").value("AB3"))
                .andExpect(jsonPath("$.results[1].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[2].item").value("AB4"))
                .andExpect(jsonPath("$.results[3].item").value("AB1"))
                .andExpect(jsonPath("$.results[3].key").doesNotExist())
                .andExpect(jsonPath("$.results[3].outcome").value("SAVED"))
                .andExpect(jsonPath("$.revision").value("r" + revision));

        assertThat(made)
                .as("the new issues, made in the order sent")
                .extracting(NewIssue::title)
                .containsExactly("Title first", "Title second", "Title third");
        assertThat(issues.get("AB3").blockedBy())
                .as("the blocker named by key, written as the id made for it")
                .containsExactly("AB2");
        assertThat(issues.get("AB1").size()).as("the existing issue's new size").isEqualTo("M");
    }

    @Test
    void aBlockerKeyNamingNothingIsRefusedNamingTheKeyWhileTheOthersStand() throws Exception {
        // Harm: a mistyped key failing the whole plan, or a new issue made without the blocker
        // the agent meant and no word of it.
        change("{\"creates\": [" + create("first", "\"frist\"") + "," + create("second", "") + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].key").value("first"))
                .andExpect(jsonPath("$.results[0].item").doesNotExist())
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].refusal.title").value("NoSuchKey"))
                .andExpect(jsonPath("$.results[0].refusal.parameters.key").value("frist"))
                .andExpect(jsonPath("$.results[1].outcome").value("SAVED"));

        assertThat(made)
                .as("only the new issue whose references all name something")
                .extracting(NewIssue::title)
                .containsExactly("Title second");
    }

    @Test
    void aNewIssueWhoseBlockerWasRefusedIsRefusedNamingThatKeyAndIsNotMade() throws Exception {
        // Harm: a new issue made without the blocker the agent planned for it.
        change(
                        "{\"creates\": [{\"key\": \"first\", \"repository\": \"porelo-elsewhere\","
                                + " \"title\": \"Nowhere\", \"parts\": {\"whatMustBeTrue\": \"X.\"}},"
                                + create("second", "\"first\"")
                                + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].refusal.title").value("IssueNotAllowed"))
                .andExpect(jsonPath("$.results[1].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[1].refusal.title").value("KeyRefused"))
                .andExpect(jsonPath("$.results[1].refusal.parameters.key").value("first"));

        assertThat(made).as("nothing made").isEmpty();
    }

    @Test
    void anUpdateARuleRefusesIsNamedWithItsReasonWhileTheOthersAreSaved() throws Exception {
        // Harm: one bad update holding back the agent's whole change, or dropped without a word.
        change(
                        "{\"updates\": [{\"issue\": \"AB9\", \"size\": \"S\"},"
                                + " {\"issue\": \"AB1\", \"size\": \"S\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].item").value("AB9"))
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].refusal.detail").isNotEmpty())
                .andExpect(jsonPath("$.results[1].item").value("AB1"))
                .andExpect(jsonPath("$.results[1].outcome").value("SAVED"));

        assertThat(issues.get("AB1").size()).as("the other update saved").isEqualTo("S");
    }

    @Test
    void anUpdateOfWhatTheIssueHoldsAlreadyIsUnchanged() throws Exception {
        // Harm: an agent told it changed what it did not, and a save made for nothing.
        issues.put("AB1", stored("AB1", "Existing", "M", List.of()));
        final int before = revision;

        change("{\"updates\": [{\"issue\": \"AB1\", \"size\": \"M\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("UNCHANGED"));

        assertThat(revision).as("no save").isEqualTo(before);
    }

    @Test
    void aMalformedChangeIsRefusedWholeBeforeAnythingIsMade() throws Exception {
        // Harm: half of a malformed change written before its fault is found.
        for (final String body :
                List.of(
                        "{\"creates\": [" + create("same", "") + "," + create("same", "") + "]}",
                        "{\"creates\": [" + create("first", "") + "], \"deletes\": []}",
                        "{\"creates\": [{\"key\": \"no spaces\", \"repository\": \"porelo-store\","
                                + " \"title\": \"T\", \"parts\": {\"whatMustBeTrue\": \"X.\"}}]}",
                        "{\"creates\": [{\"key\": \"first\", \"repository\": \"porelo-store\","
                                + " \"title\": \"T\", \"parts\": {\"plan\": \"X.\"}}]}",
                        "{}")) {
            change(body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("EditNotAllowed"));
        }

        assertThat(made).as("nothing made").isEmpty();
    }

    @Test
    void aBadTextPartOrAForwardKeyOfARefusedNewIssueRefusesTheItemBeforeAnyNewIssueIsMade()
            throws Exception {
        // Harm: a new issue made and left without the text part or blocker the agent planned.
        change(
                        "{\"creates\": [{\"key\": \"first\", \"repository\": \"porelo-store\","
                                + " \"title\": \"T\", \"parts\": {\"whatMustBeTrue\": \"X.\","
                                + " \"plan\": \" \"}},"
                                + create("second", "\"third\"")
                                + ", {\"key\": \"third\", \"repository\": \"porelo-store\","
                                + " \"title\": \"T\", \"state\": \"Nowhere\","
                                + " \"parts\": {\"whatMustBeTrue\": \"X.\"}}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].item").doesNotExist())
                .andExpect(jsonPath("$.results[0].refusal.title").value("EditNotAllowed"))
                .andExpect(jsonPath("$.results[0].refusal.parameters.part").value("Plan"))
                .andExpect(jsonPath("$.results[1].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[1].refusal.title").value("KeyRefused"))
                .andExpect(jsonPath("$.results[1].refusal.parameters.key").value("third"))
                .andExpect(jsonPath("$.results[2].refusal.title").value("StateNotAllowed"));

        assertThat(made).as("nothing made, the forward key included").isEmpty();
    }

    @Test
    void anUpdateTheJudgmentRefusesWritesNoneOfItsSteps() throws Exception {
        // Harm: a size written and then the blocker, story or state refused, so the agent's
        // change stands half done.
        issues.put("AB2", stored("AB2", "Second", null, List.of()));
        issues.put("AB3", stored("AB3", "Third", null, List.of()));

        change(
                        "{\"updates\": ["
                                + "{\"issue\": \"AB1\", \"size\": \"S\", \"blockedBy\": [\"AB1\"]},"
                                + " {\"issue\": \"AB2\", \"size\": \"S\", \"story\": \"AB1\"},"
                                + " {\"issue\": \"AB3\", \"size\": \"S\","
                                + " \"state\": \"Nowhere\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].refusal.title").value("EditNotAllowed"))
                .andExpect(jsonPath("$.results[1].refusal.title").value("NoSuchStory"))
                .andExpect(jsonPath("$.results[2].refusal.title").value("StateNotAllowed"));

        assertThat(issues.values())
                .as("no size written for an item refused")
                .extracting(Issue::size)
                .containsOnlyNulls();
    }

    @Test
    void aKeyThatIsAnIdOfTheBoardOrAnIssueChangedTwiceRefusesTheWholeBody() throws Exception {
        // Harm: a key that shadows an id, so a blocker meant for the issue lands on the new one,
        // and an answer that names one item twice.
        stories.add(new Story("ST1", "A story", "Idea", "High", null, List.of(), "", null));
        for (final String body :
                List.of(
                        "{\"creates\": [" + create("AB1", "") + "]}",
                        "{\"creates\": [" + create("ST1", "") + "]}",
                        "{\"updates\": [{\"issue\": \"AB1\", \"size\": \"S\"},"
                                + " {\"issue\": \"AB1\", \"size\": \"M\"}]}")) {
            change(body)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("EditNotAllowed"));
        }

        assertThat(made).as("nothing made").isEmpty();
        assertThat(issues.get("AB1").size()).as("nothing written").isNull();
    }

    @Test
    void aStoreFailureAnswersItsItemAndEveryOtherItemWhileAnUnreachableStoreIsNotAskedAgain()
            throws Exception {
        // Harm: the ids of the issues already made lost in an error answer, and later items
        // written against a store that cannot be reached.
        blockersFailures.put("AB3", new StoreException.Unreachable("porelo-store", "timed out"));

        change(
                        "{\"creates\": ["
                                + create("first", "")
                                + ","
                                + create("second", "\"first\"")
                                + ","
                                + create("third", "")
                                + "], \"updates\": [{\"issue\": \"AB1\", \"size\": \"S\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(4))
                .andExpect(jsonPath("$.results[0].item").value("AB2"))
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[1].item").value("AB3"))
                .andExpect(jsonPath("$.results[1].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[1].refusal.title").value("Unreachable"))
                .andExpect(jsonPath("$.results[2].item").value("AB4"))
                .andExpect(jsonPath("$.results[2].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[3].item").value("AB1"))
                .andExpect(jsonPath("$.results[3].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[3].refusal.title").value("Unreachable"));

        assertThat(issues.get("AB1").size()).as("the item after it was not tried").isNull();
    }

    @Test
    void aRefusalOfTheStoreForOneItemNeverStopsTheOthers() throws Exception {
        // Harm: one item whose issue changed under it holding back the agent's whole change.
        issues.put("AB2", stored("AB2", "Second", null, List.of()));
        fieldsFailures.put("AB1", new StoreException.Changed(List.of("AB1")));

        change(
                        "{\"updates\": [{\"issue\": \"AB1\", \"size\": \"S\"},"
                                + " {\"issue\": \"AB2\", \"size\": \"S\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].refusal.title").value("ItemChanged"))
                .andExpect(jsonPath("$.results[1].outcome").value("SAVED"));

        assertThat(issues.get("AB2").size()).as("the other update saved").isEqualTo("S");
    }

    @Test
    void aStoreThatOffersNoTextWriteRefusesTheItemAsNotOfferedAndAnswersTheIdAlreadyMade()
            throws Exception {
        // Harm: the ids of the issues made lost in an error, and the agent writing them twice.
        change(
                        "{\"creates\": [{\"key\": \"first\", \"repository\": \"porelo-store\","
                                + " \"title\": \"T\", \"parts\": {\"whatMustBeTrue\": \"X.\","
                                + " \"plan\": \"Do it.\"}},"
                                + create("second", "")
                                + "], \"updates\": [{\"issue\": \"AB1\", \"size\": \"S\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].item").value("AB2"))
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].refusal.title").value("NotOffered"))
                .andExpect(jsonPath("$.results[1].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[2].outcome").value("SAVED"));
    }

    @Test
    void anUpdateWhoseLaterStepFailsAfterAnEarlierOneWroteIsRefusedAsPartlySavedNamingIt()
            throws Exception {
        // Harm: a size written and the blockers lost, with the item answered as a plain refusal,
        // or its targets answered with the version of the step that wrote before the failure.
        issues.put("AB2", stored("AB2", "Second", null, List.of()));
        blockersFailures.put("AB1", new StoreException.Unreadable("porelo-store", "gone"));

        change("{\"updates\": [{\"issue\": \"AB1\", \"size\": \"S\", \"blockedBy\": [\"AB2\"]}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].refusal.title").value("PartlySaved"))
                .andExpect(jsonPath("$.results[0].refusal.parameters.issues").value("AB1"))
                .andExpect(jsonPath("$.results[0].version").doesNotExist())
                .andExpect(jsonPath("$.versions.AB1").doesNotExist())
                .andExpect(jsonPath("$.results[0].refusal.detail").value(containsString("fields")))
                .andExpect(
                        jsonPath("$.results[0].refusal.detail")
                                .value(containsString("Unreadable")));

        assertThat(issues.get("AB1").size()).as("the step that wrote stands").isEqualTo("S");
    }

    @Test
    void aChangeAnswersNewItemsWithTheirFinalVersionsAndAnUnchangedOneWithNone() throws Exception {
        // Harm: the ids made by a change cannot be edited using that change's answer, or an item
        // no step wrote is answered with a version read before any lock.
        change("{\"creates\":[" + create("new", "") + "],\"updates\":[{\"issue\":\"AB1\"}]}")
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.results[0].version").value(issues.get("AB2").version().value()))
                .andExpect(jsonPath("$.results[1].outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.results[1].version").doesNotExist())
                .andExpect(jsonPath("$.versions.AB1").doesNotExist())
                .andExpect(jsonPath("$.versions.AB2").value(issues.get("AB2").version().value()));
    }

    @Test
    void anUnchangedItemAnotherWriterChangedMeanwhileIsNotAnsweredWithAVersion() throws Exception {
        // Harm: an agent sends its next write's versions seen with a version of content another
        // writer saved after the change read the board, and the write passes over it unread.
        issues.put("AB1", stored("AB1", "Existing", "M", List.of()));
        rewriting = true;

        change("{\"updates\": [{\"issue\": \"AB1\", \"size\": \"M\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.results[0].version").doesNotExist())
                .andExpect(jsonPath("$.versions.AB1").doesNotExist());
    }

    @Test
    void aMovedStateTheRulesRefuseKeepsTheVersionAnEarlierStepOfTheUpdateGave() throws Exception {
        // Harm: the refused move answers no version of an issue whose size it did write, so the
        // agent reads it again, or the move's empty answer erases what the fields step gave.
        issues.put(
                "AB1.1",
                new Issue(
                        "AB1.1",
                        "porelo-store",
                        "Sub",
                        "Idea",
                        null,
                        List.of(),
                        "AB1",
                        null,
                        "Text.",
                        Worker.AGENT,
                        Risk.ORDINARY));

        change(
                        "{\"updates\": [{\"issue\": \"AB1\", \"size\": \"S\","
                                + " \"state\": \"Finished\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].refusal.title").value("PartlySaved"))
                .andExpect(jsonPath("$.versions.AB1").value(issues.get("AB1").version().value()));
    }

    @Test
    void aStoryHoldingAChangedIssueIsAnsweredOnlyWhenItIsATarget() throws Exception {
        // Harm: the answer carries the story's version of a change the agent never saw, and its
        // next write passes the story's check against content it never read.
        stories.add(new Story("ST1", "A story", "Idea", "High", null, List.of("AB1"), "", null));

        change("{\"updates\": [{\"issue\": \"AB1\", \"size\": \"S\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.AB1").value(issues.get("AB1").version().value()))
                .andExpect(jsonPath("$.versions.ST1").doesNotExist());
    }

    @Test
    void anUpdateThatWritesNothingIsUnchangedWhateverOtherCallsSaveMeanwhile() throws Exception {
        // Harm: an agent told it changed what it did not, as another call moved the revision.
        issues.put("AB1", stored("AB1", "Existing", "M", List.of()));
        moving = true;

        change("{\"updates\": [{\"issue\": \"AB1\", \"size\": \"M\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("UNCHANGED"));
    }
}
