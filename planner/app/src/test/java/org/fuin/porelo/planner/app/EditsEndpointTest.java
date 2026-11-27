package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.github.BodySections;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The edits by hand as the window sends them: each answer and refusal mapped to HTTP. */
class EditsEndpointTest {

    private final List<Object> written = new ArrayList<>();

    private boolean offered = true;

    private @Nullable RuntimeException failure;

    private Board current =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                    List.of(
                            new Story(
                                    "ST1", "One", "Idea", "High", null, List.of("AB1"), "", null)),
                    List.of(
                            new Issue(
                                    "AB1",
                                    "porelo-store",
                                    "One",
                                    "Idea",
                                    null,
                                    List.of(),
                                    null,
                                    null,
                                    ""),
                            new Issue(
                                    "AB2",
                                    "porelo-store",
                                    "Two",
                                    "Idea",
                                    null,
                                    List.of(),
                                    null,
                                    null,
                                    "")),
                    new Work(Map.of(), List.of()),
                    org.fuin.porelo.planner.store.ProjectRepository.named(List.of("porelo-store")));

    private MockMvc mvc;

    private ChangesRegistry changes;

    private PlanningStore store;

    /** The records a test serves through a project's outbox, closed after it. */
    private final List<WorkRecord> durable = new ArrayList<>();

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path state;

    @BeforeEach
    void serve() {
        store =
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
                        return write(fields);
                    }

                    @Override
                    public Board setBlockedBy(
                            final Revision revision,
                            final String issue,
                            final List<String> blockedBy) {
                        return write(blockedBy);
                    }

                    @Override
                    public Board setStoryIssues(
                            final Revision revision,
                            final String story,
                            final List<String> issues) {
                        return write(issues);
                    }

                    @Override
                    public Board setText(
                            final Revision revision, final String id, final TextEdit edit) {
                        return write(edit);
                    }

                    @Override
                    public void comment(final String issue, final String text) {
                        refuse();
                        // The board shows no comment, so its revision stays.
                        written.add(text);
                    }

                    @Override
                    public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
                        return new NewIdea.Added("ST2", List.of("AB3"), write(idea.title()));
                    }

                    @Override
                    public boolean makesStoriesFromIssues() {
                        return offered;
                    }

                    @Override
                    public NewIdea.Added createStoryFromIssues(
                            final Revision revision, final StoryFromIssues story) {
                        return new NewIdea.Added(
                                "ST2",
                                story.existing(),
                                write(story.title() + " " + story.existing()));
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        projects.put("alpha", "git@example.org:a/porelo-store.git", null);
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
        durable.forEach(WorkRecord::close);
    }

    /**
     * Serves the project from a durable record, its saves handed to its queue and written through
     * its outbox, as the planner's configuration wires them.
     */
    private void throughTheOutbox() {
        changes.close();
        final WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", state.toString()), false);
        durable.add(records);
        final Projects projects = new Projects((project, repository, branch) -> store, records);
        projects.put("alpha", "git@example.org:a/porelo-store.git", null);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new EditsController(
                                        new BoardEdits(projects, changes, projects::queue)))
                        .setControllerAdvice(new Problems(projects))
                        .build();
    }

    private void refuse() {
        if (!offered) {
            throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Board write(final Object what) {
        refuse();
        written.add(what);
        current =
                new Board(
                        new Revision("r2"),
                        current.workflow(),
                        current.stories(),
                        current.issues(),
                        current.work(),
                        current.repositories());
        return current;
    }

    private ResultActions send(final String method, final String path, final String body)
            throws Exception {
        return mvc.perform(
                ("POST".equals(method)
                                ? post("/projects/alpha" + path)
                                : put("/projects/alpha" + path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    void fieldsAreReadIntoTheirRecordAndAnsweredWithTheNewRevision() throws Exception {
        // A member read into the wrong field would set a value the person never chose.
        send(
                        "PUT",
                        "/issues/AB1/fields",
                        "{\"estimateMinutes\": 90, \"rank\": null, \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(written)
                .as("the fields written")
                .containsExactly(
                        new Fields(
                                java.util.Set.of(Fields.Field.ESTIMATE, Fields.Field.RANK),
                                null,
                                90,
                                null,
                                null));
    }

    @Test
    void aStorysListIsAnsweredWithTheNewRevision() throws Exception {
        // A list the store saved answered as refused or without its revision leaves the window
        // offering the edit again.
        send(
                        "PUT",
                        "/stories/ST1/issues",
                        "{\"issues\": [\"AB1\", \"AB2\"], \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(written).as("the list written").containsExactly(List.of("AB1", "AB2"));
    }

    @Test
    void aMalformedBodyOrAnInvalidIdIsRefusedBeforeTheStore() throws Exception {
        // A body read loosely, or a path id taken as it came, would write what nobody meant.
        send("PUT", "/issues/AB1/fields", "{\"size\": 3, \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("EditNotAllowed"));
        send(
                        "PUT",
                        "/issues/AB1/blockedBy",
                        "{\"blockedBy\": [\"AB2\"], \"extra\": 1, \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("EditNotAllowed"));
        send("PUT", "/issues/AB1/blockedBy", "{\"blockedBy\": [], \"seen\": {\"AB1\": \" \"}}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("EditNotAllowed"));
        send("POST", "/stories", "{\"idea\": {}, \"seen\": \"\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("IdeaRefused"));
        send(
                        "PUT",
                        "/issues/ab-1/blockedBy",
                        "{\"blockedBy\": [], \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isBadRequest());
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aChangedItemIsAConflictAndAnEditTheStoreDoesNotOfferIsNotOffered() throws Exception {
        // A missing feature told as a refused write, or a lost edit as a success, misleads the
        // window about what to do next.
        send(
                        "PUT",
                        "/issues/AB1/blockedBy",
                        "{\"blockedBy\": [\"AB2\"], \"seen\": " + Seens.stale(current) + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("ItemChanged"));

        offered = false;
        send(
                        "PUT",
                        "/issues/AB1/blockedBy",
                        "{\"blockedBy\": [\"AB2\"], \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
        send(
                        "PUT",
                        "/stories/ST1/issues",
                        "{\"issues\": [\"AB1\", \"AB2\"], \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
        send("POST", "/issues/AB1/comments", "{\"text\": \"A note.\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aNewStoryWithItsIdeaTakesOneMiBAndRefusesOneByteMore() throws Exception {
        // Harm: a new story's idea is refused although a forge accepts its size.
        final String body =
                "{\"idea\":{\"story\":{\"title\":\""
                        + "T".repeat(256)
                        + "\",\"gain\":\"G\",\"test\":\"T\",\"priority\":\"High\"},"
                        + "\"issues\":[{\"repository\":\"porelo-store\",\"title\":\"I\","
                        + "\"mustBeTrue\":\"W\",\"worker\":\"Agent\"}]},\"seen\": "
                        + Seens.of(current)
                        + "}";
        final String bounded = body + " ".repeat(1048576 - body.length());
        send("POST", "/stories", bounded + " ")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("IdeaRefused"));
        assertThat(written).as("the oversized request writes nothing").isEmpty();
        send("POST", "/stories", bounded).andExpect(status().isCreated());
        assertThat(written)
                .as("the full story title reaches the store")
                .containsExactly("T".repeat(256));
    }

    @Test
    void aStoryFromExistingIssuesKeepsItsBodyBound() throws Exception {
        // Harm: raising the idea limit also admits larger bodies to another kind of request.
        final String body =
                "{" + HEAD + ",\"existing\":[\"AB2\"],\"seen\": " + Seens.of(current) + "}";
        final String bounded = body + " ".repeat(65536 - body.length());
        send("POST", "/stories", bounded + " ").andExpect(status().isUnprocessableContent());
        assertThat(written).as("the oversized request writes nothing").isEmpty();
        send("POST", "/stories", bounded).andExpect(status().isCreated());
        assertThat(written).as("the original bound still saves").containsExactly("A story [AB2]");
    }

    @Test
    void aCommentIsAnsweredWithNoContentAndAStoryAsCreated() throws Exception {
        // A comment answered with a revision, or a story without its ids, leaves the window
        // waiting for a board change that never comes or unable to show what it made.
        send("POST", "/issues/AB1/comments", "{\"text\": \"A note.\"}")
                .andExpect(status().isNoContent());
        send(
                        "POST",
                        "/stories",
                        "{\"idea\": {\"story\": {\"title\": \"A story\", \"gain\": \"A gain.\","
                                + " \"test\": \"A test.\", \"priority\": \"High\"}, \"issues\":"
                                + " [{\"repository\": \"porelo-store\", \"title\": \"An issue\","
                                + " \"mustBeTrue\": \"It works.\", \"worker\": \"Agent\"}]},"
                                + " \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ST2"))
                .andExpect(jsonPath("$.issues[0]").value("AB3"))
                .andExpect(jsonPath("$.revision").value("r2"));
        assertThat(written).as("the writes").containsExactly("A note.", "A story");
    }

    @Test
    void aTextIsReadIntoItsPartsANullRemovingOneAndAMemberLeftOutLeavingIt() throws Exception {
        // A member read into the wrong part would overwrite a section the person did not name; a
        // null read as a text would leave a section the person removed.
        send(
                        "PUT",
                        "/issues/AB1/text",
                        "{\"title\": \"A title\", \"plan\": \"- step\", \"findings\": null,"
                                + " \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));
        send(
                        "PUT",
                        "/stories/ST1/text",
                        "{\"gain\": \"More.\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isOk());

        assertThat(written)
                .as("the texts written")
                .containsExactly(
                        new TextEdit(
                                TextEdit.Kind.ISSUE,
                                "A title",
                                Map.of(TextEdit.Part.PLAN, "- step"),
                                java.util.Set.of(TextEdit.Part.FINDINGS)),
                        new TextEdit(
                                TextEdit.Kind.STORY,
                                null,
                                Map.of(TextEdit.Part.GAIN, "More."),
                                java.util.Set.of()));
    }

    @Test
    void aTitleAloneIsSavedThroughTheOutbox() throws Exception {
        // Harm: a rename that can never be saved, answered as a fault of the planner.
        throughTheOutbox();

        send(
                        "PUT",
                        "/issues/AB1/text",
                        "{\"title\": \"A title\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(written)
                .as("the title written alone")
                .containsExactly(
                        new TextEdit(TextEdit.Kind.ISSUE, "A title", Map.of(), java.util.Set.of()));
    }

    @Test
    void aTextWhoseSeenNamesMoreThan2000ItemsIsRefusedWithFixedWordsAndWritesNothing()
            throws Exception {
        // Harm: a caller makes the planner hold and walk an unbounded map of versions per write.
        send(
                        "PUT",
                        "/issues/AB1/text",
                        "{\"title\": \"A title\", \"seen\": " + Seens.padded(current, 2001) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("EditNotAllowed"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "The body names no versions seen by item id, or more"
                                                + " than 2000."));
        assertThat(written).as("nothing written").isEmpty();

        send(
                        "PUT",
                        "/issues/AB1/text",
                        "{\"title\": \"A title\", \"seen\": " + Seens.padded(current, 2000) + "}")
                .andExpect(status().isOk());
        assertThat(written).as("2000 versions are taken").hasSize(1);
    }

    @Test
    void aTextNamingWhatItMayNotWriteOrRemoveIsRefusedBeforeTheStore() throws Exception {
        // Also needs written through the text would change a story's issues unchecked; a removed
        // goal or a title broken over lines would leave an item nobody can read.
        for (final String[] call :
                List.of(
                        new String[] {
                            "/issues/AB1/text", "{\"title\": \"A title\", \"alsoNeeds\": \"- AB2\""
                        },
                        new String[] {
                            "/issues/AB1/text", "{\"title\": \"A title\", \"whatMustBeTrue\": null"
                        },
                        new String[] {
                            "/issues/AB1/text", "{\"title\": \"A title\", \"covers\": null"
                        },
                        new String[] {"/issues/AB1/text", "{\"title\": null"},
                        new String[] {"/issues/AB1/text", "{\"title\": \"One\\nTwo\""},
                        new String[] {"/issues/AB1/text", "{\"title\": \"A title\", \"plan\": 3"},
                        new String[] {
                            "/issues/AB1/text", "{\"title\": \"A title\", \"gain\": \"More.\""
                        },
                        new String[] {
                            "/stories/ST1/text", "{\"title\": \"A title\", \"plan\": \"A plan.\""
                        },
                        new String[] {"/issues/AB1/text", "{"})) {
            send(
                            "PUT",
                            call[0],
                            call[1]
                                    + (call[1].equals("{") ? "" : ",")
                                    + " \"seen\": "
                                    + Seens.of(current)
                                    + "}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("EditNotAllowed"));
        }
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void labelsAreReadAsTopicLabelsAndOneOutsideThemIsRefusedNamingItOnlyWhenWellFormed()
            throws Exception {
        // Harm: a label the planner never sets written to the tracker, a refusal that repeats a
        // malformed name back, or labels GitHub dropped answered as saved.
        send(
                        "PUT",
                        "/issues/AB1/text",
                        "{\"labels\": [\"usability\", \"tests\"], \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isOk());
        assertThat(written)
                .as("the topic labels in their fixed order, nothing else of the text")
                .containsExactly(
                        new TextEdit(
                                TextEdit.Kind.ISSUE,
                                null,
                                Map.of(),
                                java.util.Set.of(),
                                List.of("tests", "usability")));
        written.clear();
        send(
                        "PUT",
                        "/stories/ST1/text",
                        "{\"labels\": [\"bug\"], \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("LabelNotAllowed"))
                .andExpect(jsonPath("$.parameters.item").value("ST1"))
                .andExpect(jsonPath("$.parameters.label").value("bug"));
        for (final String hidden : List.of("a\\u0007b", "a\\u0085b", "a\\u202Eb", "a\\u2028b")) {
            send(
                            "PUT",
                            "/issues/AB1/text",
                            "{\"labels\": [\""
                                    + hidden
                                    + "\"], \"seen\": "
                                    + Seens.of(current)
                                    + "}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("LabelNotAllowed"))
                    .andExpect(jsonPath("$.parameters.label").value(""));
        }
        for (final String labels : List.of("[\"tests\", \"tests\"]", "\"tests\"", "[3]")) {
            send(
                            "PUT",
                            "/issues/AB1/text",
                            "{\"labels\": " + labels + ", \"seen\": " + Seens.of(current) + "}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("EditNotAllowed"))
                    .andExpect(jsonPath("$.parameters.field").value("labels"));
        }
        assertThat(written).as("nothing refused is written").isEmpty();

        failure = new StoreException.LabelsNotKept("porelo-store", "AB1");
        send(
                        "PUT",
                        "/issues/AB1/text",
                        "{\"labels\": [\"tests\"], \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("LabelsNotKept"))
                .andExpect(jsonPath("$.parameters.repository").value("porelo-store"))
                .andExpect(jsonPath("$.parameters.item").value("AB1"));
    }

    @Test
    void bothTextEndpointsAnswerThePartFaultAndOffendingLine() throws Exception {
        // Harm: the window cannot direct a correction when HTTP drops the store's fault.
        for (final boolean story : List.of(false, true)) {
            final String id = story ? "ST1" : "AB1";
            final TextEdit.Part part = story ? TextEdit.Part.GAIN : TextEdit.Part.PLAN;
            failure =
                    catchThrowableOfType(
                            TextEdit.Refused.class,
                            () ->
                                    BodySections.edited(
                                            id,
                                            "",
                                            new TextEdit(
                                                    part.of(),
                                                    null,
                                                    Map.of(part, "First line.\n## Unexpected"),
                                                    Set.of())));
            assertThat(failure).as("the real store parser refuses the heading").isNotNull();
            final String answer =
                    send(
                                    "PUT",
                                    (story ? "/stories/" : "/issues/") + id + "/text",
                                    "{\""
                                            + (story ? "gain" : "plan")
                                            + "\": \"First line.\\n## Unexpected\", \"seen\": "
                                            + Seens.of(current)
                                            + "}")
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            final JsonNode problem = JsonMapper.builder().build().readTree(answer);
            assertThat(problem.path("status").asInt()).as("the HTTP refusal status").isEqualTo(422);
            assertThat(problem.path("title").asText())
                    .as("the contract refusal name")
                    .isEqualTo("EditNotAllowed");
            final JsonNode parameters = problem.path("parameters");
            final String heading = story ? "What a user gains" : "Plan";
            assertThat(parameters.path("part").asText())
                    .as("the affected heading")
                    .isEqualTo(heading);
            assertThat(parameters.path("fault").asText())
                    .as("the machine-readable fault")
                    .isEqualTo("HEADING");
            assertThat(parameters.path("found").asText())
                    .as("the offending line")
                    .isEqualTo("## Unexpected");
            assertThat(problem.path("detail").asText())
                    .as("the refusal's repair words")
                    .contains(heading, "heading", "## Unexpected");
        }
        assertThat(written).as("neither refused edit is saved").isEmpty();
    }

    @Test
    void aTextOnAChangedItemIsAConflictNamingItAndOneNotOfferedIsNotOffered() throws Exception {
        // A person's change on the tracker answered as a success would be lost unseen; a missing
        // feature told as a refused write misleads the window.
        failure = new StoreException.Changed(List.of("AB1"));
        send(
                        "PUT",
                        "/issues/AB1/text",
                        "{\"title\": \"A title\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("ItemChanged"))
                .andExpect(jsonPath("$.parameters.changed[0]").value("AB1"));
        failure = new TextEdit.Refused("AB1", "A part of the body holds a heading.");
        send(
                        "PUT",
                        "/issues/AB1/text",
                        "{\"plan\": \"## Plan\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("EditNotAllowed"))
                .andExpect(jsonPath("$.parameters.item").value("AB1"));
        failure = null;
        offered = false;
        send(
                        "PUT",
                        "/stories/ST1/text",
                        "{\"title\": \"A title\", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aStoryTheStoreCannotWriteIsAnIdeaRefused() throws Exception {
        // A refused story answered as a server fault gives the window no reason to show.
        failure = new StoreException.NotSavable("ST2", "the repository is archived");
        send(
                        "POST",
                        "/stories",
                        "{\"idea\": {\"story\": {\"title\": \"A story\", \"gain\": \"A gain.\","
                                + " \"test\": \"A test.\", \"priority\": \"High\"}, \"issues\":"
                                + " [{\"repository\": \"porelo-store\", \"title\": \"An issue\","
                                + " \"mustBeTrue\": \"It works.\", \"worker\": \"Agent\"}]},"
                                + " \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("IdeaRefused"));
    }

    private static final String HEAD =
            "\"story\": {\"title\": \"A story\", \"gain\": \"A gain.\", \"test\": \"A test.\","
                    + " \"priority\": \"High\"}";

    @Test
    void aStoryFromIssuesIsAnsweredAsCreatedWithTheIssuesInTheOrderNamed() throws Exception {
        // Harm: the second body the description names served unlike it, or answered without the
        // ids the window shows the new story by.
        send(
                        "POST",
                        "/stories",
                        "{"
                                + HEAD
                                + ", \"existing\": [\"AB2\"], \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ST2"))
                .andExpect(jsonPath("$.issues[0]").value("AB2"))
                .andExpect(jsonPath("$.revision").value("r2"));
        assertThat(written).as("the story the store was given").containsExactly("A story [AB2]");
    }

    @Test
    void aStoryFromIssuesWhoseBodyBreaksItsShapeOrNamesATakenIssueIsRefusedBeforeTheStore()
            throws Exception {
        // Harm: a body mixing new and existing issues, an unknown member, a list out of bounds or
        // an issue another story holds that makes a story anyway, or a refusal that repeats a
        // text sent as an id.
        final String idea =
                "\"idea\": {"
                        + HEAD
                        + ", \"issues\": [{\"repository\": \"porelo-store\","
                        + " \"title\": \"An issue\", \"mustBeTrue\": \"It works.\","
                        + " \"worker\": \"Agent\"}]}";
        final String ids101 = String.join(", ", java.util.Collections.nCopies(101, "\"AB2\""));
        final Map<String, String> bodies = new java.util.LinkedHashMap<>();
        bodies.put(
                "{" + idea + ", \"existing\": [\"AB2\"], \"seen\": " + Seens.of(current) + "}",
                "body: ");
        bodies.put(
                "{"
                        + HEAD
                        + ", \"existing\": [\"AB2\"], \"seen\": "
                        + Seens.of(current)
                        + ", \"more\": 1}",
                "body: ");
        bodies.put(
                "{" + HEAD + ", \"existing\": [], \"seen\": " + Seens.of(current) + "}",
                "existing: ");
        bodies.put(
                "{"
                        + HEAD
                        + ", \"existing\": ["
                        + ids101
                        + "], \"seen\": "
                        + Seens.of(current)
                        + "}",
                "existing: ");
        bodies.put(
                "{"
                        + HEAD
                        + ", \"existing\": [\"AB2\", \"<b>x</b>\"], \"seen\": "
                        + Seens.of(current)
                        + "}",
                "existing[1]: The id must be an issue's id.");
        for (final Map.Entry<String, String> body : bodies.entrySet()) {
            final String answer =
                    send("POST", "/stories", body.getKey())
                            .andExpect(status().isUnprocessableContent())
                            .andExpect(jsonPath("$.title").value("IdeaRefused"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(answer).as("the refusal of %s", body.getKey()).contains(body.getValue());
            assertThat(answer).as("nothing sent repeated").doesNotContain("<b>x</b>", "more");
        }
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aStoryFromIssuesOnAMovedBoardIsAConflictAndOneTheStoreDoesNotMakeIsNotOffered()
            throws Exception {
        // Harm: a story made over a board the caller never saw, or a store that makes none, such
        // as the Git store, answered as a refused write the window would show as the person's.
        send(
                        "POST",
                        "/stories",
                        "{"
                                + HEAD
                                + ", \"existing\": [\"AB2\"], \"seen\": "
                                + Seens.stale(current)
                                + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("ItemChanged"));
        offered = false;
        send(
                        "POST",
                        "/stories",
                        "{"
                                + HEAD
                                + ", \"existing\": [\"AB2\"], \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
        send("POST", "/stories", "{" + HEAD + ", \"existing\": [], \"more\": 1}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void anIssueTheStoreFindsUnderAnotherParentIsAConflictNamingTheIssueTheParentAndItsField()
            throws Exception {
        // Harm: an issue hung under an issue on the tracker answered as a malformed request, so
        // the window's conflict path never runs; one another story holds moves instead.
        failure = new StoreException.InStory("AB2", "porelo-store#9", "existing[0]");
        send(
                        "POST",
                        "/stories",
                        "{"
                                + HEAD
                                + ", \"existing\": [\"AB2\"], \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("IssueInStory"))
                .andExpect(jsonPath("$.parameters.issue").value("AB2"))
                .andExpect(jsonPath("$.parameters.story").value("porelo-store#9"))
                .andExpect(jsonPath("$.parameters.field").value("existing[0]"));
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aStoryFromIssuesTheStoreCannotWriteOrOnlyPartlyWroteIsAnsweredAsTheContractSays()
            throws Exception {
        // Harm: a store's refusal of one issue answered without the field naming it, or a save
        // cut off midway answered as a success or a server fault.
        failure = new StoreException.NotSavable("AB2", "the issue AB2 is closed");
        send(
                        "POST",
                        "/stories",
                        "{"
                                + HEAD
                                + ", \"existing\": [\"AB2\"], \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("IdeaRefused"))
                .andExpect(
                        jsonPath("$.parameters.faults[0]")
                                .value("existing[0]: the issue AB2 is closed"));
        failure =
                new StoreException.PartlySaved(
                        "porelo-store", List.of("porelo-store"), "GitHub refused a link");
        send(
                        "POST",
                        "/stories",
                        "{"
                                + HEAD
                                + ", \"existing\": [\"AB2\"], \"seen\": "
                                + Seens.of(current)
                                + "}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("PartlySaved"))
                .andExpect(jsonPath("$.parameters.failed").value("porelo-store"))
                .andExpect(jsonPath("$.parameters.pushed").value("porelo-store"));
        assertThat(written).as("nothing written").isEmpty();
    }
}
