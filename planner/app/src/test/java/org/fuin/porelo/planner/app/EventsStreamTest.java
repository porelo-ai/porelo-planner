package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.app.answers.Board;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.ReadProgress;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Tracker;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A project's boards streamed to a window as the project's remote changes. */
class EventsStreamTest {

    // Counted once its blank line is written: the emitter writes an event's fields one by one.
    private static final Pattern BOARD =
            Pattern.compile("event:(?:board|change)\\nid:([^\\n]+)\\ndata:[^\\n]+\\n\\n");

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path dir;

    /** The HTTP stream carries a whole baseline and a delta for a remote push. */
    @Test
    void aWindowGetsTheBoardAndThenAChangeForAPushMadeElsewhere() throws Exception {
        // A window that never hears of a push shows a board that is no longer true.
        try (FixtureProject project = FixtureProject.serve(dir);
                ChangesRegistry changes = registry(project)) {
            final MockMvc mvc = mvc(project, changes);
            final MvcResult stream = mvc.perform(get("/projects/alpha/events")).andReturn();
            final MockHttpServletResponse events = stream.getResponse();
            within(() -> boards(events) >= 1, "the first board", events);
            final String first = lastRevision(events);
            assertThat(events.getContentAsString())
                    .as("the first board's data")
                    .containsPattern(
                            "\"stories\":\\[\\{\"version\":\"[0-9a-f]{64}\",\"id\":\"ST7\"")
                    .contains("\"kind\":\"WORKING\"");
            assertThat(events.getContentAsString())
                    .as("the project's repositories, which the window offers a new issue in")
                    .contains("\"repositories\":[\"porelo-store\"]");

            project.pushElsewhere();
            final String refreshed =
                    mvc.perform(post("/projects/alpha/refresh"))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            within(() -> boards(events) >= 2, "a change after the push", events);
            assertThat(events.getContentAsString())
                    .as("only the first answer is a whole board")
                    .containsOnlyOnce("event:board")
                    .contains("event:change");
            assertThat(lastRevision(events)).as("the change's revision").isNotEqualTo(first);
            assertThat(refreshed).as("the refresh's revision").contains(lastRevision(events));
            assertThat(events.getContentAsString())
                    .as("the second board's story, now derived from the issue in Review")
                    .contains("\"state\":\"Review\"");
            within(
                    () -> content(events).contains(": keep-alive"),
                    "a keep-alive while nothing changes",
                    events);

            mvc.perform(post("/projects/alpha/refresh")).andExpect(status().isOk());
            assertThat(boards(events)).as("no event for a fetch with nothing new").isEqualTo(2);

            final MvcResult reconnected =
                    mvc.perform(
                                    get("/projects/alpha/events")
                                            .header("Last-Event-ID", lastRevision(events)))
                            .andReturn();
            within(
                    () -> boards(reconnected.getResponse()) >= 1,
                    "a whole board after reconnecting",
                    reconnected.getResponse());
            assertThat(reconnected.getResponse().getContentAsString())
                    .as("a reconnect ignores its last id and gets the current whole board")
                    .contains("event:board", "\"stories\"", "\"noStory\"")
                    .doesNotContain("event:change");
            reconnected.getRequest().getAsyncContext().complete();

            // Ended at once, not at the next keep-alive: a window gone must not hold its stream.
            stream.getRequest().getAsyncContext().complete();
            assertThat(changes.followedProjects())
                    .as("the project polled once its last window went")
                    .isZero();
        }
    }

    @Test
    void anUnknownProjectIsRefusedBeforeAnyStreamStarts() throws Exception {
        // A stream that starts and stays silent would look like a board that never changes.
        try (FixtureProject project = FixtureProject.serve(dir);
                ChangesRegistry changes = registry(project)) {
            final MockMvc mvc = mvc(project, changes);
            mvc.perform(get("/projects/beta/events").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isNotFound())
                    .andExpect(
                            MockMvcResultMatchers.content()
                                    .contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"));
            mvc.perform(post("/projects/beta/refresh"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"));
            assertThat(changes.followedProjects()).as("nothing followed").isZero();
        }
    }

    /** A connection always establishes its complete baseline. */
    @Test
    void theFirstFrameIsWholeAndAReconnectStartsWholeAgain() throws IOException {
        // Harm: a reconnect applies a partial answer to a board it no longer holds.
        final Board board =
                answer(
                        storeBoard(
                                "first",
                                List.of(story("ST1", "AA1")),
                                List.of(issue("AA1", "Ready"), issue("AB1", "Review"))));
        final CapturingEmitter first = new CapturingEmitter();
        final BoardChanges stream = new BoardChanges();
        assertThat(stream.send(first, board)).as("the initial board is sent").isTrue();
        assertThat(first.frames.getFirst())
                .as("the first event's name")
                .startsWith("event:board\n");
        assertThat(first.data(0))
                .as("the complete board including noStory")
                .isEqualTo(JSON.valueToTree(board));
        assertThat(stream.send(first, board)).as("an identical answer sends nothing").isFalse();
        final CapturingEmitter reconnected = new CapturingEmitter();
        new BoardChanges().send(reconnected, board);
        assertThat(reconnected.frames)
                .as("a reconnect starts from the whole board")
                .containsExactlyElementsOf(first.frames);
    }

    /** An issue move replaces its story and the board counts. */
    @Test
    void anIssuesMoveSendsOnlyItsStoryAndTheCountsThatChanged() throws IOException {
        // Harm: a partial update drops the moved issue or silently leaves stale board counts.
        final List<Story> stories = List.of(story("ST1", "AA1"), story("ST2", "AB1"));
        final Board before =
                answer(
                        storeBoard(
                                "first",
                                stories,
                                List.of(issue("AA1", "Ready"), issue("AB1", "Ready"))));
        final Board after =
                answer(
                        storeBoard(
                                "second",
                                stories,
                                List.of(issue("AA1", "Review"), issue("AB1", "Ready"))));
        final CapturingEmitter events = changes(before, after);
        final JsonNode delta = events.data(1);
        assertThat(events.frames.get(1))
                .as("a delta event follows the board")
                .startsWith("event:change\n");
        assertThat(delta.path("stories").size()).as("only the affected story").isEqualTo(1);
        assertThat(delta.at("/stories/0/id").asString()).as("the affected story").isEqualTo("ST1");
        assertThat(delta.at("/stories/0/issues/0/state").asString())
                .as("the moved issue")
                .isEqualTo("Review");
        assertThat(delta.path("open"))
                .as("the changed counts")
                .isEqualTo(JSON.valueToTree(after.open()));
        final Set<String> members = new java.util.TreeSet<>();
        delta.properties().forEach(field -> members.add(field.getKey()));
        assertThat(members)
                .as("only the changed story and counts beside the patch envelope")
                .containsExactlyInAnyOrder(
                        "frame",
                        "after",
                        "before",
                        "revision",
                        "stories",
                        "removedStories",
                        "noStory",
                        "removedIssues",
                        "open");
        assertThat(delta.has("states")).as("unchanged workflow omitted").isFalse();
        assertThat(delta.has("storyOrder")).as("unchanged story order omitted").isFalse();
        assertThat(apply(events.data(0), delta))
                .as("applying the delta recovers the whole board")
                .isEqualTo(JSON.valueToTree(after));
    }

    /** Story membership and order survive a patch. */
    @Test
    void newAndRemovedStoriesAndAnOrderOnlyChangeAreApplied() throws IOException {
        // Harm: an added or removed story is lost, or a changed order is never seen.
        final Board before =
                answer(storeBoard("first", List.of(story("ST1"), story("ST2")), List.of()));
        final Board after =
                answer(storeBoard("second", List.of(story("ST3"), story("ST2")), List.of()));
        final CapturingEmitter events = changes(before, after);
        assertThat(events.data(1).path("removedStories"))
                .as("the removed story")
                .isEqualTo(JSON.valueToTree(List.of("ST1")));
        assertThat(events.data(1).path("storyOrder"))
                .as("the complete new story order")
                .isEqualTo(JSON.valueToTree(List.of("ST3", "ST2")));
        assertThat(apply(events.data(0), events.data(1)))
                .as("added and removed stories applied")
                .isEqualTo(JSON.valueToTree(after));
        final Board reordered =
                answer(storeBoard("third", List.of(story("ST2"), story("ST3")), List.of()));
        final CapturingEmitter order = changes(after, reordered);
        assertThat(order.data(1).path("stories").isEmpty())
                .as("no story changed in an order-only update")
                .isTrue();
        assertThat(apply(order.data(0), order.data(1)))
                .as("the order-only delta applied")
                .isEqualTo(JSON.valueToTree(reordered));
    }

    /** Unassigned issue membership and content survive patches. */
    @Test
    void issuesEnterChangeAndLeaveNoStory() throws IOException {
        // Harm: an unassigned issue disappears or stays visible after joining a story.
        final List<Issue> issues = List.of(issue("AA1", "Ready"), issue("AB1", "Review"));
        final Board assigned = answer(storeBoard("first", List.of(story("ST1", "AA1")), issues));
        final Board unassigned = answer(storeBoard("second", List.of(story("ST1")), issues));
        final CapturingEmitter left = changes(assigned, unassigned);
        assertThat(left.data(1).at("/noStory/0/id").asString())
                .as("the issue entering no story")
                .isEqualTo("AA1");
        assertThat(apply(left.data(0), left.data(1)))
                .as("the issue leaving its story applied")
                .isEqualTo(JSON.valueToTree(unassigned));
        final Board moved =
                answer(
                        storeBoard(
                                "third",
                                List.of(story("ST1")),
                                List.of(issue("AA1", "Review"), issue("AB1", "Review"))));
        final CapturingEmitter changed = changes(unassigned, moved);
        assertThat(changed.data(1).path("noStory").size())
                .as("only the changed unassigned issue")
                .isEqualTo(1);
        assertThat(apply(changed.data(0), changed.data(1)))
                .as("the unassigned move applied")
                .isEqualTo(JSON.valueToTree(moved));
        final Board reordered =
                answer(
                        storeBoard(
                                "fourth",
                                List.of(story("ST1")),
                                List.of(issue("AB1", "Review"), issue("AA1", "Review"))));
        final CapturingEmitter order = changes(moved, reordered);
        assertThat(order.data(1).path("noStory").isEmpty())
                .as("unchanged rows need no replacement")
                .isTrue();
        assertThat(order.data(1).path("noStoryOrder"))
                .as("the new unassigned issue order")
                .isEqualTo(JSON.valueToTree(List.of("AB1", "AA1")));
        assertThat(apply(order.data(0), order.data(1)))
                .as("unassigned order-only delta applied")
                .isEqualTo(JSON.valueToTree(reordered));
        final CapturingEmitter joined = changes(unassigned, assigned);
        assertThat(joined.data(1).path("removedIssues"))
                .as("the issue leaving no story")
                .isEqualTo(JSON.valueToTree(List.of("AA1")));
        assertThat(apply(joined.data(0), joined.data(1)))
                .as("the issue joining its story applied")
                .isEqualTo(JSON.valueToTree(assigned));
    }

    /** The board includes the existing unassigned issue answer. */
    @Test
    void theBoardsNoStoryEqualsTheIssueListAtItsRevision() {
        // Harm: the window needs another read or gets a different set or order of unassigned
        // issues.
        final org.fuin.porelo.planner.store.Board board =
                storeBoard(
                        "first",
                        List.of(story("ST1", "AA1")),
                        List.of(
                                issue("AB1", "Review"),
                                issue("AA1", "Ready"),
                                issue("AC1", "Ready")));
        final PlanningStore store = store(new AtomicReference<>(board));
        final Projects projects = new Projects((project, repository, branch) -> store);
        try (ChangesRegistry registry =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor())) {
            projects.put("alpha", "source", null);
            final var listed = new BoardController(projects, registry)
                            .issues(
                                    "alpha",
                                    "none",
                                    new org.springframework.mock.web.MockHttpServletRequest());
            final Board whole = answer(board);
            assertThat(whole.revision()).as("the same revision").isEqualTo(listed.revision());
            assertThat(whole.noStory())
                    .as("the same unassigned issues in the same order")
                    .containsExactlyElementsOf(listed.issues());
            assertThat(whole.noStory()).as("unassigned rows are present").hasSize(2);
        }
    }

    /** A same-revision work update has a consecutive connection frame. */
    @Test
    void aWorkOnlyChangeKeepsTheRevisionAndCountsOnlySentFrames() throws IOException {
        // Harm: same-revision work is dropped or suppressed frames break the client's sequence.
        final Board before = answer(storeBoard("same", List.of(story("ST1")), List.of()));
        final ObjectNode changed = JSON.valueToTree(before);
        ((ObjectNode) changed.withArray("stories").get(0))
                .set(
                        "work",
                        JSON.valueToTree(
                                SerializationCheck.sample(
                                        org.fuin.porelo.planner.app.answers.PlanWork.class)));
        final Board after = JSON.treeToValue(changed, Board.class);
        final CapturingEmitter events = new CapturingEmitter();
        final BoardChanges stream = new BoardChanges();
        stream.send(events, before);
        stream.send(events, before);
        stream.send(events, after);
        stream.send(events, after);
        stream.send(events, before);
        assertThat(events.frames).as("only real answer changes").hasSize(3);
        for (int index = 1; index < 3; index++) {
            assertThat(events.data(index).path("frame").asLong())
                    .as("consecutive sent frame")
                    .isEqualTo(index);
            assertThat(events.data(index).path("after").asLong())
                    .as("the preceding sent frame")
                    .isEqualTo(index - 1);
            assertThat(events.data(index).path("before").asString())
                    .as("the preceding revision")
                    .isEqualTo("same");
            assertThat(events.data(index).path("revision").asString())
                    .as("unchanged store revision")
                    .isEqualTo("same");
        }
        assertThat(apply(events.data(0), events.data(1)))
                .as("work-only update applied")
                .isEqualTo(JSON.valueToTree(after));
    }

    /** A change that arrives as a background read fails is sent, and the failure is told too. */
    @Test
    void aChangeAndAFailedBackgroundReadInOneStepDeliverTheChangeAndTheFailure() throws Exception {
        // Harm: the window never learns the read failed, so its bar and held Reload stay.
        final AtomicReference<org.fuin.porelo.planner.store.Board> current =
                new AtomicReference<>(oneIssue("r1", "Ready"));
        final AtomicReference<ReadProgress> progress = new AtomicReference<>();
        final CountDownLatch release = new CountDownLatch(1);
        final PlanningStore store =
                new PlanningStore() {
                    /** Answers the board held by this in-memory store. */
                    @Override
                    public org.fuin.porelo.planner.store.Board board() {
                        return current.get();
                    }

                    /** Reads on the planner's schedule without a wait. */
                    @Override
                    public org.fuin.porelo.planner.store.Board poll() {
                        return board();
                    }

                    /** Reads for as long as the test holds it, then fails. */
                    @Override
                    public org.fuin.porelo.planner.store.Board refresh() {
                        progress.set(new ReadProgress(Instant.now(), 0, null, null, "reload"));
                        try {
                            release.await();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        progress.set(null);
                        throw new StoreException.Unreachable("porelo-store", "bad gateway");
                    }

                    /** Answers that its whole read goes on alone. */
                    @Override
                    public boolean reloadsAlone() {
                        return true;
                    }

                    /** Answers the read's progress while the read is held. */
                    @Override
                    public Optional<ReadProgress> reading() {
                        return Optional.ofNullable(progress.get());
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        try (ChangesRegistry changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor())) {
            final MockMvc mvc =
                    MockMvcBuilders.standaloneSetup(
                                    new BoardController(projects, changes),
                                    new EventsController(
                                            changes, Duration.ofHours(1), new TaskHelds()))
                            .setControllerAdvice(new Problems(projects))
                            .build();
            mvc.perform(
                            put("/projects/alpha")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(SOURCE))
                    .andExpect(status().isNoContent());
            final MockHttpServletResponse events =
                    mvc.perform(get("/projects/alpha/events")).andReturn().getResponse();
            within(() -> boards(events) >= 1, "the first board", events);

            // The stream sees the read under way when a change wakes it.
            mvc.perform(post("/projects/alpha/refresh")).andExpect(status().isOk());
            within(() -> progress.get() != null, "the background read", events);
            current.set(oneIssue("r2", "Review"));
            changes.saved("alpha");
            within(() -> boards(events) >= 2, "the change while the read runs", events);

            // The read fails and the next change follows it before the stream wakes again.
            release.countDown();
            within(() -> changes.lastFailure("alpha").isPresent(), "the failed read", events);
            current.set(oneIssue("r3", "Done"));
            changes.saved("alpha");
            within(
                    () -> boards(events) >= 3 && content(events).contains("\"state\":\"failed\""),
                    "the change and the failed read",
                    events);

            assertThat(content(events))
                    .as("the failure is told once, with its problem")
                    .containsOnlyOnce("\"state\":\"failed\"")
                    .contains("\"problem\":\"Unreachable\"", "bad gateway")
                    .doesNotContain("\"state\":\"done\"");
            assertThat(boards(events)).as("the first board and two changes").isEqualTo(3);
        }
    }

    /** A reader can skip intermediate store frames without losing changes. */
    @Test
    void aSlowReaderAppliesOneDeltaCoveringEverySkippedFrame() throws Exception {
        // Harm: changes in skipped frames are lost when the reader resumes from its older board.
        final List<Story> stories = List.of(story("ST1", "AA1"), story("ST2", "AB1"));
        final AtomicReference<org.fuin.porelo.planner.store.Board> current =
                new AtomicReference<>(
                        storeBoard(
                                "first",
                                stories,
                                List.of(issue("AA1", "Ready"), issue("AB1", "Ready"))));
        final Changes changes = new Changes(store(current), () -> {});
        try (Changes.Subscription reader = changes.subscribe()) {
            final CapturingEmitter events = new CapturingEmitter();
            final BoardChanges stream = new BoardChanges();
            stream.send(events, answer(reader.first()));
            current.set(
                    storeBoard(
                            "second",
                            stories,
                            List.of(issue("AA1", "Review"), issue("AB1", "Ready"))));
            changes.saved();
            current.set(
                    storeBoard(
                            "third",
                            stories,
                            List.of(issue("AA1", "Review"), issue("AB1", "Review"))));
            changes.saved();
            final Changes.Frame newest = reader.nextFrame(Duration.ZERO).orElseThrow();
            stream.send(events, answer(newest.board()));
            assertThat(events.frames).as("one whole board and one delta").hasSize(2);
            assertThat(events.data(1).path("stories").size())
                    .as("both skipped story changes")
                    .isEqualTo(2);
            assertThat(events.data(1).path("frame").asLong())
                    .as("no gap for a skipped frame")
                    .isEqualTo(1);
            assertThat(events.data(1).path("after").asLong()).as("the last sent frame").isZero();
            assertThat(apply(events.data(0), events.data(1)))
                    .as("all skipped changes recovered")
                    .isEqualTo(JSON.valueToTree(answer(current.get())));
        }
    }

    /** Warned duplicate ids retain every row through updates. */
    @Test
    void duplicateIdsKeepEveryRowWhenTheyAppearChangeReorderAndClear() throws IOException {
        // Harm: id-keyed replacement hides warned rows with duplicate ids.
        final Board first =
                answer(storeBoard("first", List.of(story("ST1")), List.of(issue("AA1", "Ready"))));
        final List<Warning> warnings = List.of(Warning.idTwice());
        final Story oneStory = story("ST1").withWarnings(warnings);
        final Story otherStory =
                new Story("ST1", "Another row", "Ready", "High", null, List.of(), "", null, true)
                        .withWarnings(warnings);
        final Issue oneIssue = issue("AA1", "Ready").withWarnings(warnings);
        final Issue otherIssue =
                new Issue(
                                "AA1",
                                "porelo-store",
                                "Another issue row",
                                "Ready",
                                "S",
                                List.of(),
                                null,
                                null,
                                "")
                        .withWarnings(warnings);
        final Board second =
                answer(
                        storeBoard(
                                "second",
                                List.of(oneStory, otherStory),
                                List.of(oneIssue, otherIssue)));
        final CapturingEmitter appeared = changes(first, second);
        assertThat(appeared.data(1).path("stories").size())
                .as("both story rows supplied")
                .isEqualTo(2);
        assertThat(appeared.data(1).path("noStory").size())
                .as("both issue rows supplied")
                .isEqualTo(2);
        assertThat(apply(appeared.data(0), appeared.data(1)))
                .as("duplicates appearing applied")
                .isEqualTo(JSON.valueToTree(second));
        final Board third =
                answer(
                        storeBoard(
                                "third",
                                List.of(otherStory, oneStory),
                                List.of(otherIssue, oneIssue)));
        final CapturingEmitter order = changes(second, third);
        assertThat(apply(order.data(0), order.data(1)))
                .as("duplicate rows reordered without loss")
                .isEqualTo(JSON.valueToTree(third));
        final CapturingEmitter cleared = changes(third, first);
        assertThat(apply(cleared.data(0), cleared.data(1)))
                .as("a collision clearing keeps its remaining row")
                .isEqualTo(JSON.valueToTree(first));
    }

    /** Whole replacement members distinguish clearing from retaining. */
    @Test
    void topLevelChangesIncludeEmptyListsAndFalseAndOmitUnchangedMembers() throws IOException {
        // Harm: cleared warnings, held saves or work counts stay visible because absence means
        // retain.
        final ObjectNode full = JSON.valueToTree(SerializationCheck.sample(Board.class));
        final ObjectNode cleared = full.deepCopy();
        cleared.put("revision", "next");
        for (final String field :
                List.of(
                        "states",
                        "phases",
                        "work",
                        "faults",
                        "repositories",
                        "repositoryWarnings",
                        "unlisted",
                        "rules",
                        "heldSaves")) {
            cleared.set(field, JSON.createArrayNode());
        }
        cleared.put("reasons", false);
        ((ObjectNode) cleared.get("open")).put("unsized", 123);
        ((ObjectNode) cleared.get("tracker")).put("kind", "git");
        final Board before = JSON.treeToValue(full, Board.class);
        final Board after = JSON.treeToValue(cleared, Board.class);
        final CapturingEmitter events = changes(before, after);
        for (final String field :
                List.of(
                        "states",
                        "phases",
                        "work",
                        "faults",
                        "repositories",
                        "repositoryWarnings",
                        "unlisted",
                        "rules",
                        "heldSaves")) {
            assertThat(events.data(1).has(field)).as("%s clearing is present", field).isTrue();
            assertThat(events.data(1).path(field).isEmpty()).as("%s clears", field).isTrue();
        }
        assertThat(events.data(1).path("reasons").asBoolean()).as("false replaces true").isFalse();
        assertThat(apply(events.data(0), events.data(1)))
                .as("all top-level replacements applied")
                .isEqualTo(JSON.readTree(JSON.writeValueAsString(after)));
    }

    private static org.fuin.porelo.planner.store.Board storeBoard(
            final String revision, final List<Story> stories, final List<Issue> issues) {
        return new org.fuin.porelo.planner.store.Board(
                new Revision(revision),
                new Workflow(List.of("Ready", "Review", "Done"), List.of(), List.of("Done")),
                stories,
                issues,
                new Work(Map.of(), List.of()));
    }

    private static org.fuin.porelo.planner.store.Board oneIssue(
            final String revision, final String state) {
        return storeBoard(revision, List.of(story("ST1", "AA1")), List.of(issue("AA1", state)));
    }

    private static Story story(final String id, final String... issues) {
        return new Story(id, "Story " + id, "Ready", "High", null, List.of(issues), "", null, true);
    }

    private static Issue issue(final String id, final String state) {
        return new Issue(
                id,
                "porelo-store",
                "Issue " + id,
                state,
                "S",
                List.of(),
                null,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    private static Board answer(final org.fuin.porelo.planner.store.Board board) {
        return BoardController.boardOf(
                TaskHelds.Counts.NONE,
                board,
                new WorkRun.Snapshot(0, Map.of()),
                false,
                Tracker.GIT,
                List.of());
    }

    private static PlanningStore store(
            final AtomicReference<org.fuin.porelo.planner.store.Board> current) {
        return new PlanningStore() {
            /** Answers the board held by this in-memory store. */
            @Override
            public org.fuin.porelo.planner.store.Board board() {
                return current.get();
            }

            /** Keeps the current board on refresh. */
            @Override
            public org.fuin.porelo.planner.store.Board refresh() {
                return board();
            }
        };
    }

    private static CapturingEmitter changes(final Board before, final Board after)
            throws IOException {
        final CapturingEmitter events = new CapturingEmitter();
        final BoardChanges stream = new BoardChanges();
        stream.send(events, before);
        stream.send(events, after);
        return events;
    }

    /** Applies the wire contract independently of the producer, including repeated ids. */
    private static JsonNode apply(final JsonNode before, final JsonNode delta) {
        final ObjectNode board = ((ObjectNode) before).deepCopy();
        board.set("stories", collection(before, delta, "stories", "removedStories", "storyOrder"));
        board.set("noStory", collection(before, delta, "noStory", "removedIssues", "noStoryOrder"));
        final Set<String> patch =
                Set.of(
                        "frame",
                        "after",
                        "before",
                        "stories",
                        "removedStories",
                        "noStory",
                        "removedIssues",
                        "storyOrder",
                        "noStoryOrder");
        delta.properties()
                .forEach(
                        field -> {
                            if (!patch.contains(field.getKey())) {
                                board.set(field.getKey(), field.getValue());
                            }
                        });
        return board;
    }

    private static ArrayNode collection(
            final JsonNode before,
            final JsonNode delta,
            final String member,
            final String removed,
            final String order) {
        final Map<String, ArrayDeque<JsonNode>> rows = new HashMap<>();
        final List<String> ids = new ArrayList<>();
        before.path(member)
                .forEach(
                        row -> {
                            final String id = row.path("id").asString();
                            ids.add(id);
                            rows.computeIfAbsent(id, key -> new ArrayDeque<>()).add(row);
                        });
        delta.path(removed).forEach(id -> rows.remove(id.asString()));
        delta.path(member).forEach(row -> rows.remove(row.path("id").asString()));
        delta.path(member)
                .forEach(
                        row ->
                                rows.computeIfAbsent(
                                                row.path("id").asString(),
                                                key -> new ArrayDeque<>())
                                        .add(row));
        if (delta.has(order)) {
            ids.clear();
            delta.path(order).forEach(id -> ids.add(id.asString()));
        }
        final ArrayNode result = JSON.createArrayNode();
        for (final String id : ids) {
            result.add(rows.get(id).removeFirst());
        }
        return result;
    }

    /** Captures exactly the SSE fields and the JSON body that the HTTP emitter sends. */
    private static final class CapturingEmitter extends SseEmitter {
        private final List<String> frames = new ArrayList<>();

        /** Writes the event fields and serializes its JSON data for wire assertions. */
        @Override
        public void send(final SseEventBuilder event) throws IOException {
            final StringBuilder wire = new StringBuilder();
            for (final DataWithMediaType item : event.build()) {
                wire.append(
                        item.getData() instanceof String text
                                ? text
                                : JSON.writeValueAsString(item.getData()));
            }
            frames.add(wire.toString());
        }

        private JsonNode data(final int index) {
            final String wire = frames.get(index);
            return JSON.readTree(wire.substring(wire.indexOf("data:") + 5).strip());
        }
    }

    private static ChangesRegistry registry(final FixtureProject project) {
        return new ChangesRegistry(
                StoreConfiguration.fromGit(StoreConfiguration.gitProjects(project.environment())),
                Duration.ofHours(1),
                Executors.newSingleThreadScheduledExecutor());
    }

    private static MockMvc mvc(final FixtureProject project, final ChangesRegistry changes)
            throws Exception {
        final MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new BoardController(changes.projects(), changes),
                                new EventsController(
                                        changes, Duration.ofSeconds(2), new TaskHelds()))
                        .setControllerAdvice(new Problems(changes.projects()))
                        .build();
        mvc.perform(
                        put("/projects/alpha")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(project.source()))
                .andExpect(status().isNoContent());
        return mvc;
    }

    private static int boards(final MockHttpServletResponse events) {
        final Matcher matcher = BOARD.matcher(content(events));
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String lastRevision(final MockHttpServletResponse events) {
        final Matcher matcher = BOARD.matcher(content(events));
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        assertThat(last).as("a board's revision").isNotNull();
        return last;
    }

    private static String content(final MockHttpServletResponse events) {
        try {
            return events.getContentAsString();
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void within(
            final BooleanSupplier condition,
            final String what,
            final MockHttpServletResponse events)
            throws InterruptedException {
        final long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < end)
                    .as("%s within 20 s; the stream so far:%n%s", what, content(events))
                    .isTrue();
            Thread.sleep(50);
        }
    }
}
