package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.Tracker;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.planner.store.local.ItemsDirectories;
import org.fuin.porelo.planner.store.local.LocalDirectoryStores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Where a local project's items are kept, as the window shows and chooses it: over a local store in
 * a directory of the test's own, beside a Git and a GitHub project.
 */
@Tag("linux")
@EnabledOnOs(OS.LINUX)
class TrackerItemsEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final String NO_SEEN = "{}";

    private static final TrackedProject PROJECT =
            new TrackedProject(
                    null,
                    "",
                    LocalDirectoryStores.KIND,
                    "",
                    org.fuin.porelo.planner.store.ProjectRepository.named(
                            List.of("plans", "engine")),
                    new Workflow(List.of("Idea", "Backlog", "Done"), List.of(), List.of("Done")),
                    new Work(Map.of(), List.of()),
                    "files",
                    states -> states.isEmpty() ? "Idea" : states.getFirst());

    @TempDir Path temp;

    private final List<String> opened = new ArrayList<>();

    private final List<PlanningStore> stores = new ArrayList<>();

    private final List<AutoCloseable> closing = new ArrayList<>();

    private Path data;

    private MockMvc mvc;

    @BeforeEach
    void serve() throws Exception {
        data =
                Files.createDirectory(
                        temp.resolve("data"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwxr-xr-x")));
        mvc = restart();
    }

    @AfterEach
    void close() throws Exception {
        for (final AutoCloseable each : closing) {
            each.close();
        }
    }

    /** A planner as it starts: nothing held but what its state directory keeps. */
    private MockMvc restart() {
        final Map<String, String> environment =
                Map.of(
                        "HOME",
                        temp.toString(),
                        "XDG_DATA_HOME",
                        data.toString(),
                        "XDG_STATE_HOME",
                        temp.resolve("state").toString());
        final ItemsDirectories directories =
                new ItemsDirectories(PrivateFiles.of(environment), environment);
        final LocalDirectoryStores local =
                new LocalDirectoryStores(
                        PrivateFiles.of(environment), environment, Clock.systemUTC(), directories);
        final Projects projects =
                new Projects(
                        (project, repository, branch) -> {
                            opened.add(project);
                            return switch (project) {
                                case "git" -> fake(Tracker.GIT, null);
                                case "github" -> fake(Tracker.GITHUB, null);
                                case "slow" ->
                                        fake(
                                                Tracker.local("/kept/items", "/kept/default"),
                                                new StoreException.ItemsDirectoryRefused(
                                                        "/kept/items", ItemsDirectories.MISSING));
                                case "unruly" ->
                                        fake(
                                                Tracker.local("/kept/items", "/kept/default"),
                                                new StoreException.ItemsDirectoryRefused(
                                                        "/kept/\u202eitems",
                                                        ItemsDirectories.MISSING));
                                default -> {
                                    final PlanningStore store =
                                            local.open(project, PROJECT, () -> PROJECT);
                                    stores.add(store);
                                    yield store;
                                }
                            };
                        });
        final ChangesRegistry changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        closing.add(changes);
        return MockMvcBuilders.standaloneSetup(
                        new BoardController(projects, changes),
                        new EventsController(changes, Duration.ofSeconds(15)),
                        new TrackerController(projects, directories),
                        new EditsController(new BoardEdits(projects, changes)))
                .setControllerAdvice(new Problems(projects))
                .build();
    }

    /**
     * A store of another kind; one given a refusal answers it after a read longer than a glance.
     */
    private static PlanningStore fake(
            final Tracker tracker, final StoreException.@Nullable ItemsDirectoryRefused refusal) {
        return new PlanningStore() {
            @Override
            public Board board() {
                if (refusal == null) {
                    throw new StoreException.Unreadable("porelo-store", "not read in this test");
                }
                try {
                    Thread.sleep(Duration.ofSeconds(3));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw refusal;
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

            @Override
            public Tracker tracker() {
                return tracker;
            }
        };
    }

    private void given(final MockMvc on, final String project) throws Exception {
        on.perform(
                        put("/projects/" + project)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(SOURCE))
                .andExpect(status().isNoContent());
    }

    private ResultActions choose(final String project, final String body) throws Exception {
        return mvc.perform(
                put("/projects/" + project + "/tracker/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static String body(final @Nullable Path directory) {
        return "{\"directory\": "
                + (directory == null ? "null" : "\"" + directory + "\"")
                + ", \"seen\": "
                + NO_SEEN
                + "}";
    }

    private Path own(final String name) throws Exception {
        return Files.createDirectory(
                temp.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private Path kept() {
        return temp.resolve("state/porelo-planner/alpha/items.json");
    }

    @Test
    void aChosenDirectoryIsShownOpenedAndKeptAcrossARestartAndTheDefaultComesBack()
            throws Exception {
        // Harm: the person's choice is lost at a restart, read from the old directory, or shown
        // as another than the planner uses.
        final Path own = own("own");
        final String standard = data.resolve("porelo-planner/items/alpha").toString();
        given(mvc, "alpha");
        mvc.perform(get("/projects/alpha/workflow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracker.kind").value("local"))
                .andExpect(jsonPath("$.tracker.items").value(standard))
                .andExpect(jsonPath("$.tracker.default").value(standard));

        choose("alpha", body(own))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("local"))
                .andExpect(jsonPath("$.items").value(own.toString()))
                .andExpect(jsonPath("$.default").value(standard));

        assertThat(opened).as("the store is opened anew on the choice").hasSize(2);
        mvc.perform(get("/projects/alpha/stories")).andExpect(status().isOk());
        mvc.perform(get("/projects/alpha/workflow"))
                .andExpect(jsonPath("$.tracker.items").value(own.toString()));

        mvc = restart();
        given(mvc, "alpha");
        mvc.perform(get("/projects/alpha/workflow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracker.items").value(own.toString()));

        choose("alpha", body(null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").value(standard));
        assertThat(kept()).as("the default keeps no file").doesNotExist();
    }

    @Test
    void aProjectNotKeptLocallyIsNotOfferedAndAnswersItsKind() throws Exception {
        // Harm: a Git or GitHub project shows a directory, or one is kept that nothing reads.
        for (final String project : List.of("git", "github")) {
            given(mvc, project);
            choose(project, body(own("own-" + project)))
                    .andExpect(status().isNotImplemented())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"))
                    .andExpect(jsonPath("$.parameters.project").value(project));
        }
        choose("missing", body(temp))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"));
        assertThat(temp.resolve("state")).as("nothing is kept").doesNotExist();
    }

    @Test
    void aDirectoryThatCannotBeUsedIsRefusedNamingItAndNothingIsKept() throws Exception {
        // Harm: items are kept through a link or in a directory made by a typo, or the window
        // cannot tell the person which directory was refused.
        given(mvc, "alpha");
        final Path missing = temp.resolve("missing");
        final Path link = Files.createSymbolicLink(temp.resolve("link"), own("own"));
        for (final Path refused : List.of(missing, link)) {
            choose("alpha", body(refused))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:ItemsDirectoryRefused"))
                    .andExpect(jsonPath("$.parameters.project").value("alpha"))
                    .andExpect(jsonPath("$.parameters.directory").value(refused.toString()));
        }
        assertThat(missing).as("never made").doesNotExist();
        assertThat(kept()).as("nothing is kept").doesNotExist();
        assertThat(opened).as("the store stays as it was").hasSize(1);
    }

    /** Each body refused as it is read, with why: the reading's table of bodies. */
    private static final Map<String, String> REFUSED_BODIES =
            java.util.Map.ofEntries(
                    Map.entry("not json", "no JSON"),
                    Map.entry("[]", "no object"),
                    Map.entry("{\"directory\": \"/a\", \"seen\": {}} {}", "a second value"),
                    Map.entry("{\"directory\": \"/a\"}", "seen missing"),
                    Map.entry("{\"seen\": {}}", "directory missing, not null"),
                    Map.entry(
                            "{\"directory\": \"/a\", \"seen\": {}, \"more\": 1}", "a member more"),
                    Map.entry(
                            "{\"directory\": \"/a\", \"directory\": \"/b\", \"seen\": {}}",
                            "a member twice"),
                    Map.entry("{\"directory\": 7, \"seen\": {}}", "directory a number"),
                    Map.entry("{\"directory\": true, \"seen\": {}}", "directory a boolean"),
                    Map.entry("{\"directory\": {}, \"seen\": {}}", "directory an object"),
                    Map.entry("{\"directory\": \"/a\", \"seen\": []}", "seen an array"),
                    Map.entry("{\"directory\": \"/a\", \"seen\": null}", "seen null"),
                    Map.entry(
                            "{\"directory\": \"/a\", \"seen\": {\"x y\": \"1\"}}",
                            "seen naming no item"),
                    Map.entry(
                            "{\"directory\": \"/" + "a".repeat(9000) + "\", \"seen\": {}}",
                            "more than 8192 bytes"));

    /** Each directory outside the rule, refused naming none. */
    private static final Map<String, String> REFUSED_TEXTS =
            Map.of(
                    "relative",
                    ItemsDirectories.NOT_NORMAL,
                    "/a/../b",
                    ItemsDirectories.NOT_NORMAL,
                    "/a//b",
                    ItemsDirectories.NOT_NORMAL,
                    "/a/",
                    ItemsDirectories.NOT_NORMAL,
                    " /a",
                    ItemsDirectories.NOT_NORMAL,
                    "/a\\u202eb",
                    ItemsDirectories.NOT_NORMAL,
                    "",
                    ItemsDirectories.NOT_NORMAL,
                    "/" + "a".repeat(5000),
                    ItemsDirectories.TOO_LONG);

    @Test
    void aMalformedBodyOrATextOutsideTheRuleIsRefusedNamingNoDirectory() throws Exception {
        // Harm: text that is no directory is echoed back to the window, or taken in part.
        given(mvc, "alpha");
        for (final Map.Entry<String, String> body : REFUSED_BODIES.entrySet()) {
            choose("alpha", body.getKey())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:ItemsDirectoryRefused"))
                    .andExpect(jsonPath("$.detail").value(TrackerController.BODY_REFUSED))
                    .andExpect(jsonPath("$.parameters.project").value("alpha"))
                    .andExpect(jsonPath("$.parameters.directory").doesNotExist());
        }
        for (final Map.Entry<String, String> text : REFUSED_TEXTS.entrySet()) {
            choose("alpha", "{\"directory\": \"" + text.getKey() + "\", \"seen\": {}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:ItemsDirectoryRefused"))
                    .andExpect(jsonPath("$.detail").value(text.getValue()))
                    .andExpect(jsonPath("$.parameters.directory").doesNotExist());
        }
        assertThat(kept()).as("nothing is kept").doesNotExist();
        assertThat(opened).as("nothing is reopened").hasSize(1);
    }

    @Test
    void theCallIsCheckedByNameThenProjectThenKindBeforeItsBody() throws Exception {
        // Harm: a window is told its path is wrong for a project that keeps no directory at all.
        given(mvc, "git");
        choose("git", "not json")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
        choose("unknown", "not json")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"));
        choose("in valid", "not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
    }

    @Test
    void theDirectoryInUseAgainChangesNothingAndAWriteOnTheOldStoreIsRefused() throws Exception {
        // Harm: choosing the same directory again ends every stream for nothing; or a write the
        // window based on the old directory's board lands there after the swap.
        final Path own = own("own");
        given(mvc, "alpha");
        choose("alpha", body(null)).andExpect(status().isOk());
        assertThat(opened).as("the default again reopens nothing").hasSize(1);
        final PlanningStore old = stores.getFirst();
        final org.fuin.porelo.planner.store.Revision before = old.board().revision();

        choose("alpha", body(own)).andExpect(status().isOk());
        choose("alpha", body(own)).andExpect(status().isOk());

        assertThat(opened).as("the same directory again reopens nothing").hasSize(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                old.createIssue(
                                        before,
                                        new org.fuin.porelo.planner.store.NewIssue(
                                                "engine", "Late", "Late", "S")))
                .as("a write on the old store after the swap")
                .isInstanceOf(StoreException.StaleRevision.class);
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories.length()").value(0));
    }

    @Test
    void aKeptDirectoryMovedAwayIsRefusedOnEveryReadNamingIt() throws Exception {
        // Harm: a moved directory silently falls back to the default and the items seem lost.
        final Path own = own("own");
        given(mvc, "alpha");
        choose("alpha", body(own)).andExpect(status().isOk());
        Files.move(own, temp.resolve("moved"));

        for (final String read : List.of("workflow", "stories", "events")) {
            mvc.perform(get("/projects/alpha/" + read))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:ItemsDirectoryRefused"))
                    .andExpect(jsonPath("$.parameters.directory").value(own.toString()));
        }
        try (var listed = Files.list(data.resolve("porelo-planner/items/alpha"))) {
            assertThat(listed.map(each -> each.getFileName().toString()).toList())
                    .as("the default is not written in its place: its lock alone is there")
                    .containsAnyOf("lock")
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    /** Every call that reads or writes a project's items, with a body it takes in its form. */
    private static final List<List<String>> ITEM_CALLS =
            List.of(
                    List.of("GET", "/workflow", ""),
                    List.of("GET", "/events", ""),
                    List.of("POST", "/refresh", ""),
                    List.of("GET", "/stories", ""),
                    List.of(
                            "POST",
                            "/stories",
                            "{\"idea\": {\"story\": {\"title\": \"T\", \"gain\": \"G\","
                                    + " \"test\": \"X\", \"priority\": \"High\"}, \"issues\":"
                                    + " [{\"repository\": \"engine\", \"title\": \"T\","
                                    + " \"mustBeTrue\": \"M\", \"worker\": \"AGENT\"}]},"
                                    + " \"seen\": {}}"),
                    List.of("GET", "/stories/plans%231", ""),
                    List.of("GET", "/stories/plans%231/issues", ""),
                    List.of(
                            "PUT",
                            "/stories/plans%231/issues",
                            "{\"issues\": [\"engine#1\"], \"seen\": {}}"),
                    List.of("PUT", "/stories/plans%231/text", "{\"title\": \"T\", \"seen\": {}}"),
                    List.of(
                            "PUT",
                            "/stories/plans%231/priority",
                            "{\"priority\": \"High\", \"seen\": {}}"),
                    List.of("POST", "/stories/plans%231/proposal", "{\"to\": \"Backlog\"}"),
                    List.of("GET", "/stories/plans%231/work", ""),
                    List.of(
                            "POST",
                            "/stories/plans%231/work",
                            "{\"project\": \"alpha\", \"story\": \"plans#1\", \"to\": \"Backlog\","
                                    + " \"mode\": \"UNATTENDED\", \"seen\": {}}"),
                    List.of("POST", "/stories/plans%231/work/stop", "{}"),
                    List.of("POST", "/ideas/discussion", "{\"idea\": \"An idea\"}"),
                    List.of("GET", "/issues?story=none", ""),
                    List.of(
                            "POST",
                            "/issues",
                            "{\"repository\": \"engine\", \"title\": \"T\", \"mustBeTrue\": \"M\","
                                    + " \"seen\": {}}"),
                    List.of("GET", "/issues/engine%231", ""),
                    List.of(
                            "PUT",
                            "/issues/engine%231/worker",
                            "{\"worker\": \"AGENT\", \"seen\": {}}"),
                    List.of("PUT", "/issues/engine%231/fields", "{\"size\": \"S\", \"seen\": {}}"),
                    List.of(
                            "PUT",
                            "/issues/engine%231/blockedBy",
                            "{\"blockedBy\": [], \"seen\": {}}"),
                    List.of("PUT", "/issues/engine%231/text", "{\"title\": \"T\", \"seen\": {}}"),
                    List.of("POST", "/issues/engine%231/comments", "{\"text\": \"A comment\"}"),
                    List.of(
                            "POST",
                            "/moves",
                            "{\"moves\": [{\"issue\": \"engine#1\", \"to\": \"Backlog\"}],"
                                    + " \"digest\": \""
                                    + "a".repeat(64)
                                    + "\", \"seen\": {}}"),
                    List.of(
                            "POST",
                            "/moves/preview",
                            "{\"moves\": [{\"issue\": \"engine#1\", \"to\": \"Backlog\"}]}"),
                    List.of(
                            "POST",
                            "/corrections/preview",
                            "{\"issue\": \"engine#1\", \"warning\": \"W\", \"choice\": \"Backlog\"}"),
                    List.of(
                            "POST",
                            "/corrections",
                            "{\"issue\": \"engine#1\", \"warning\": \"W\", \"choice\": \"Backlog\","
                                    + " \"digest\": \""
                                    + "a".repeat(64)
                                    + "\", \"seen\": {}}"));

    // POST /items is left out: a local store adds no issue made outside the project, so the call is
    // NotOffered before anything is read.

    @Test
    void everyCallOnTheItemsIsRefusedWhileTheKeptDirectoryIsAndTheDefaultIsNeverUsed()
            throws Exception {
        // Harm: one call reads or writes the default directory while the chosen one is gone, so
        // the person's items seem lost or are split over two directories.
        final Path own = own("own");
        given(mvc, "alpha");
        choose("alpha", body(own)).andExpect(status().isOk());
        Files.move(own, temp.resolve("moved"));

        final List<String> answered = new ArrayList<>();
        for (final List<String> call : ITEM_CALLS) {
            final var request =
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                                    org.springframework.http.HttpMethod.valueOf(call.get(0)),
                                    java.net.URI.create("/projects/alpha" + call.get(1)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(call.get(2));
            final MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            final String text = response.getContentAsString();
            if (response.getStatus() != 409
                    || !text.contains("urn:porelo-planner:ItemsDirectoryRefused")
                    || !text.contains("\"directory\":\"" + own + "\"")) {
                answered.add(
                        call.get(0) + " " + call.get(1) + " " + response.getStatus() + " " + text);
            }
        }

        assertThat(answered).as("the calls not refused for the directory").isEmpty();
        try (var listed = Files.list(data.resolve("porelo-planner/items/alpha"))) {
            assertThat(listed.map(each -> each.getFileName().toString()).toList())
                    .as("nothing is read or written in the default directory: its lock alone")
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    @Test
    void aReadLongerThanAGlanceThatIsRefusedEndsTheStreamNamingTheDirectoryInTheRuleAlone()
            throws Exception {
        // Harm: the stream's failed load leaves out the directory the window offers to change, or
        // hands on a text outside the rule that hides what it names.
        assertThat(failedLoad("slow"))
                .contains(
                        "\"problem\":\"ItemsDirectoryRefused\"",
                        "\"failure\":\"" + ItemsDirectories.MISSING + "\"",
                        "\"parameters\":{\"directory\":\"/kept/items\"}");
        assertThat(failedLoad("unruly"))
                .contains("\"problem\":\"ItemsDirectoryRefused\"", "\"parameters\":{}")
                .doesNotContain("kept/");
    }

    private String failedLoad(final String project) throws Exception {
        given(mvc, project);
        final MockHttpServletResponse events =
                mvc.perform(get("/projects/" + project + "/events")).andReturn().getResponse();
        final long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!events.getContentAsString().contains("\"state\":\"failed\"")
                && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        return events.getContentAsString();
    }
}
