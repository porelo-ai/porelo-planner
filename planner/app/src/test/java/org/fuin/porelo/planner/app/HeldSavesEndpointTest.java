package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.core.RefusedEventResave;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.ForgeAnswer;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.Written;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The saves a project keeps for the owner account: on the board, resent once, or dropped. */
class HeldSavesEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final String WINDOW = "11111111-1111-4111-8111-111111111111";

    private static final String RUN = "22222222-2222-4222-8222-222222222222";

    private static final String ELSEWHERE = "33333333-3333-4333-8333-333333333333";

    private static final String RUNNING = "44444444-4444-4444-8444-444444444444";

    private static final CommandOutbox.Source OWN = new CommandOutbox.Source("porelo-store", "");

    @TempDir Path directory;

    private Board current = board("r1");

    private final Saves saves = new Saves();

    private HeldSaves given = saves;

    private MockMvc mvc;

    private PlanningStore store;

    /** What the store's judgment refuses a group with, or null when it takes it. */
    private @Nullable RuntimeException judged;

    /** What the store's board is refused with, or null when it is read. */
    private @Nullable RuntimeException boardFailure;

    /** What the store refuses every write with, or null when it writes. */
    private @Nullable RuntimeException writing;

    private ChangesRegistry changes;

    /** A held save's store of groups as the outbox and its runs keep them, counting every call. */
    private final class Saves implements HeldSaves {

        private final List<CommandOutbox.Group> kept = new ArrayList<>();

        private final List<String> resent = new ArrayList<>();

        private final List<String> dropped = new ArrayList<>();

        private final List<String> resaved = new ArrayList<>();

        private final Set<String> resavable = new HashSet<>();

        /** The groups whose drop the outbox refuses, as while it drains. */
        private final Set<String> undroppable = new HashSet<>();

        /** What a resend is refused with, or null when it writes. */
        private @Nullable RuntimeException resendRefusal;

        @Override
        public List<CommandOutbox.Group> groups() {
            return List.copyOf(kept);
        }

        @Override
        public CommandOutcome resend(final String group) {
            final RuntimeException refusal = resendRefusal;
            if (refusal != null) {
                throw refusal;
            }
            resent.add(group);
            // Written whole, the group is pruned from the file.
            kept.removeIf(each -> each.id().equals(group));
            current = board("r2");
            return new CommandOutcome.Done(current, Map.of());
        }

        @Override
        public void drop(final String group) {
            if (undroppable.contains(group)) {
                throw new StoreException.Busy("porelo-store");
            }
            dropped.add(group);
            kept.removeIf(each -> each.id().equals(group));
        }

        @Override
        public RefusedEventResave runs() {
            return new RefusedEventResave() {
                @Override
                public boolean resavable(final String story, final String group) {
                    return resavable.contains(group);
                }

                @Override
                public Board resave(final String story, final String group) {
                    resaved.add(group);
                    kept.removeIf(each -> each.id().equals(group));
                    current = board("r3");
                    return current;
                }
            };
        }
    }

    private static Board board(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Backlog", "Finished"), List.of(), List.of("Finished")),
                List.of(new Story("ST1", "A story", "Backlog", "High", null, List.of(), "", null)),
                List.of(),
                new Work(Map.of(), List.of()));
    }

    private static CommandOutbox.Told told(
            final String group,
            final int at,
            final String target,
            final CommandOutbox.State state,
            final WorkRun.@Nullable SaveFailure why) {
        return new CommandOutbox.Told(group + "/" + at, "write", target, state, why, List.of());
    }

    private static CommandOutbox.Group kept(
            final String id,
            final CommandOutbox.Source source,
            final boolean earlier,
            final String story,
            final CommandOutbox.Told... commands) {
        return new CommandOutbox.Group(source, id, List.of(commands), earlier, story);
    }

    private static CommandOutbox.Group refused(final String id, final String story) {
        return kept(
                id,
                OWN,
                false,
                story,
                told(id, 0, "ST1", CommandOutbox.State.DONE, null),
                told(
                        id,
                        1,
                        "porelo-store",
                        CommandOutbox.State.REFUSED,
                        new WorkRun.SaveFailure("Changed", "porelo-store", List.of())));
    }

    private static final java.time.Instant READ = java.time.Instant.parse("2026-05-06T07:08:09Z");

    @BeforeEach
    void serve() throws Exception {
        store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        final RuntimeException failure = boardFailure;
                        if (failure != null) {
                            throw failure;
                        }
                        return current;
                    }

                    @Override
                    public Board refresh() {
                        return current;
                    }

                    @Override
                    public java.util.Optional<java.time.Instant> boardReadAt(final Board board) {
                        return board == current
                                ? java.util.Optional.of(READ)
                                : java.util.Optional.empty();
                    }

                    @Override
                    public void judge(final CommandGroup group) {
                        final RuntimeException refusal = judged;
                        if (refusal != null) {
                            throw refusal;
                        }
                        PlanningStore.super.judge(group);
                    }

                    @Override
                    public Written writeAlone(final Command command) {
                        final RuntimeException refusal = writing;
                        if (refusal != null) {
                            throw refusal;
                        }
                        return PlanningStore.super.writeAlone(command);
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        final HeldSavesOf held = project -> given;
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor(),
                        Duration.ofHours(1),
                        held);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new BoardController(projects, changes),
                                new HeldSavesController(projects, changes, held))
                        .setControllerAdvice(new Problems(projects))
                        .build();
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    @AfterEach
    void close() {
        changes.close();
    }

    /** The held saves of the board a window is told, as the stream writes them. */
    private static JsonNode held(final Changes.Frame frame) {
        return JsonMapper.builder().build().valueToTree(frame.held());
    }

    private static List<String> ids(final JsonNode held) {
        final List<String> ids = new ArrayList<>();
        held.forEach(each -> ids.add(each.path("id").asText()));
        return ids;
    }

    @Test
    void aHeldGroupIsOnTheBoardAndResentOnce() throws Exception {
        // Harm: a save held for the person is invisible, so nobody resends or drops it, or one
        // resend writes it twice.
        saves.kept.add(refused(WINDOW, ""));
        final Changes.Subscription window = changes.subscribe("alpha");
        final JsonNode shown = held(window.firstFrame());
        assertThat(ids(shown)).as("the held group on the board").containsExactly(WINDOW);
        assertThat(shown.path(0).path("resendable").asBoolean())
                .as("a window's save can be resent")
                .isTrue();
        assertThat(shown.path(0).path("commands").path(1).path("state").asText())
                .as("its refused command")
                .isEqualTo("REFUSED");
        assertThat(shown.path(0).path("commands").path(1).path("reason").asText())
                .as("its reason as the store's refusal names it")
                .isEqualTo("Changed");
        assertThat(shown.path(0).path("commands").path(1).path("subject").asText())
                .as("what the reason is about")
                .isEqualTo("porelo-store");

        mvc.perform(post("/projects/alpha/saves/" + WINDOW + "/resend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));
        mvc.perform(post("/projects/alpha/saves/" + WINDOW + "/resend"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchSave"))
                .andExpect(jsonPath("$.parameters.save").value(WINDOW));

        assertThat(saves.resent).as("resent once, through the runner").containsExactly(WINDOW);
        assertThat(ids(held(window.nextFrame(Duration.ofSeconds(2)).orElseThrow())))
                .as("the windows are told the board without it")
                .isEmpty();
    }

    @Test
    void anOutboxsRefusedGroupIsOnTheBoardFromTheFileAndADropLeavesIt() throws Exception {
        // Harm: a group the outbox keeps refused on disk never reaches the owner account, or a
        // drop leaves it in the file, where it comes back after a restart.
        final CommandOutbox outbox = outbox();
        keptRefused(outbox);

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());

        assertThat(ids(shown)).as("the refused group from the file").containsExactly(WINDOW);
        assertThat(shown.path(0).path("commands").path(0).path("kind").asText())
                .as("its command's kind")
                .isEqualTo("WRITE");
        assertThat(shown.path(0).path("commands").path(0).path("target").asText())
                .as("its command's target")
                .isEqualTo("ST1");
        assertThat(shown.path(0).path("commands").path(0).path("state").asText())
                .as("its command refused")
                .isEqualTo("REFUSED");
        assertThat(shown.path(0).path("reason").asText())
                .as("why, in one plain sentence")
                .isEqualTo("ST1 was changed elsewhere since it was read.");
        assertThat(shown.path(0).path("changes"))
                .as("what it would change, beside the value last read and its time")
                .isEqualTo(
                        JsonMapper.builder()
                                .build()
                                .readTree(
                                        "[{\"target\":\"ST1\",\"field\":\"state\",\"now\":\"Backlog\","
                                                + "\"nowAt\":\"2026-05-06T07:08:09Z\",\"saved\":\"Finished\"}]"));
        assertThat(shown.path(0).path("tries").size()).as("its one try from the file").isEqualTo(1);
        final JsonNode tried = shown.path(0).path("tries").path(0);
        assertThat(java.time.Instant.parse(tried.path("at").asText()))
                .as("when it ended, as the outbox kept it")
                .isEqualTo(outbox.tries(WINDOW).getFirst().at());
        assertThat(List.of(tried.path("result").asText(), tried.path("reason").asText()))
                .as("how it ended and why")
                .containsExactly("REFUSED", "ST1 was changed elsewhere since it was read.");
        final JsonNode answer =
                JsonMapper.builder()
                        .build()
                        .readTree(
                                "{\"status\":409,\"text\":\"Conflict\",\"requestId\":\"C0DE:1234\"}");
        assertThat(tried.path("answer")).as("the forge's answer to it").isEqualTo(answer);
        assertThat(shown.path(0).path("answer")).as("the answer of the last try").isEqualTo(answer);

        mvc.perform(delete("/projects/alpha/saves/" + WINDOW)).andExpect(status().isNoContent());
        outbox.close();
        final CommandOutbox reopened = outbox();
        reopened.open(() -> current);
        assertThat(reopened.pending()).as("the dropped group left the file").isEmpty();
    }

    /**
     * Opens the outbox with a window's group refused on disk, and serves it through a real runner.
     */
    private CommandRunner keptRefused(final CommandOutbox outbox) {
        outbox.open(() -> current);
        final Command.Write move =
                new Command.Write(
                        WINDOW + "/0",
                        "ST1",
                        new Writes(Map.of(), Map.of("ST1", "Finished")),
                        Basis.of(current),
                        Recheck.none());
        outbox.enqueue(new CommandGroup(WINDOW, List.of(move)));
        outbox.begin(move.id());
        outbox.refused(
                move.id(),
                new StoreException.Changed(List.of("ST1"))
                        .answered(new ForgeAnswer(409, "Conflict", "C0DE:1234")),
                false,
                null);
        final CommandRunner runner = new CommandRunner(outbox, store);
        given = new OutboxHeldSaves(outbox, runner, HeldSaves.NO_RUNS);
        return runner;
    }

    private static CommandOutbox.Try tried(
            final int minute,
            final CommandOutbox.State result,
            final CommandOutbox.Why why,
            final CommandOutbox.Answer answer) {
        return new CommandOutbox.Try(READ.plusSeconds(60L * minute), result, why, answer);
    }

    private static CommandOutbox.Group triedGroup(final List<CommandOutbox.Try> tries) {
        final CommandOutbox.Group refused = refused(WINDOW, "");
        return new CommandOutbox.Group(
                refused.source(), WINDOW, refused.commands(), false, "", tries);
    }

    @Test
    void everyTryIsOnTheBoardWithItsAnswerAndTheLastTrysAnswerTopsIt() throws Exception {
        // Harm: the person sees a held save without what GitHub answered and without when it
        // was tried, so they cannot tell a passing outage from a lasting refusal, or quote the
        // request id to GitHub.
        saves.kept.add(
                triedGroup(
                        List.of(
                                tried(
                                        1,
                                        CommandOutbox.State.REFUSED,
                                        new CommandOutbox.Why("Unreachable", "", ""),
                                        CommandOutbox.Answer.NONE),
                                tried(
                                        2,
                                        CommandOutbox.State.HELD,
                                        new CommandOutbox.Why("Changed", "porelo-store", ""),
                                        new CommandOutbox.Answer(502, "Bad Gateway", "C0DE:9")))));

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame()).path(0);

        assertThat(shown.path("tries"))
                .as("each try, oldest first, the one without an answer without one")
                .isEqualTo(
                        JsonMapper.builder()
                                .build()
                                .readTree(
                                        "[{\"at\":\"2026-05-06T07:09:09Z\",\"result\":\"REFUSED\","
                                                + "\"reason\":\""
                                                + HeldReasons.of("Unreachable", "")
                                                + "\"},{\"at\":\"2026-05-06T07:10:09Z\","
                                                + "\"result\":\"HELD\",\"reason\":\"porelo-store was"
                                                + " changed elsewhere since it was read.\","
                                                + "\"answer\":{\"status\":502,\"text\":\"Bad Gateway\","
                                                + "\"requestId\":\"C0DE:9\"}}]"));
        assertThat(shown.path("answer"))
                .as("the save's answer, its last try's")
                .isEqualTo(shown.path("tries").path(1).path("answer"));
    }

    @Test
    void aLastTryWithoutAnAnswerLeavesTheSaveWithoutOne() throws Exception {
        // Harm: an older try's answer is shown as what GitHub said last, so the person acts on
        // a refusal that no longer stands.
        saves.kept.add(
                triedGroup(
                        List.of(
                                tried(
                                        1,
                                        CommandOutbox.State.REFUSED,
                                        new CommandOutbox.Why("CredentialRefused", "", ""),
                                        new CommandOutbox.Answer(401, "Bad credentials", "")),
                                tried(
                                        2,
                                        CommandOutbox.State.REFUSED,
                                        new CommandOutbox.Why("Unreachable", "", ""),
                                        CommandOutbox.Answer.NONE))));

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame()).path(0);

        assertThat(shown.has("answer")).as("no answer of the save").isFalse();
        assertThat(shown.path("tries").path(0).path("answer").has("requestId"))
                .as("an answer without a request id names none")
                .isFalse();
    }

    @Test
    void aSaveTriedMoreOftenThanTheBoardCarriesShowsItsNewestTries() throws Exception {
        // Harm: a save tried without end grows the board past what its description bounds, so
        // the window refuses the whole board, or the newest tries are the ones left out.
        final List<CommandOutbox.Try> tries = new ArrayList<>();
        for (int i = 0; i <= HeldSavesController.MOST_TRIES; i++) {
            tries.add(
                    tried(
                            i,
                            CommandOutbox.State.REFUSED,
                            new CommandOutbox.Why("Unreachable", "", ""),
                            CommandOutbox.Answer.NONE));
        }
        saves.kept.add(triedGroup(tries));

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame()).path(0).path("tries");

        assertThat(shown.size()).as("the tries told").isEqualTo(HeldSavesController.MOST_TRIES);
        assertThat(
                        List.of(
                                shown.path(0).path("at").asText(),
                                shown.path(shown.size() - 1).path("at").asText()))
                .as("the oldest left out, the newest kept")
                .containsExactly(tries.get(1).at().toString(), tries.getLast().at().toString());
    }

    @Test
    void aResendRefusedAtItsJudgmentStaysOnTheBoard() throws Exception {
        // Harm: a resent save refused before it writes leaves the outbox, so it is gone unseen.
        keptRefused(outbox());
        judged = new StoreException.Changed(List.of("ST1"));

        mvc.perform(post("/projects/alpha/saves/" + WINDOW + "/resend"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:ItemChanged"));

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());
        assertThat(ids(shown)).as("still held for the person").containsExactly(WINDOW);
        assertThat(shown.path(0).path("commands").path(0).path("state").asText())
                .as("refused again")
                .isEqualTo("REFUSED");
    }

    @Test
    void aResendTheStoreStaysBusyForStaysOnTheBoardAsHeld() throws Exception {
        // Harm: a resent save the store was too busy for waits queued, off the board, where the
        // person can neither see nor drop it.
        keptRefused(outbox());
        writing = new StoreException.Busy("porelo-store");

        mvc.perform(post("/projects/alpha/saves/" + WINDOW + "/resend"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Busy"));

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());
        assertThat(ids(shown)).as("still on the board").containsExactly(WINDOW);
        assertThat(shown.path(0).path("commands").path(0).path("state").asText())
                .as("held, waiting for the person")
                .isEqualTo("HELD");
    }

    @Test
    void aResendWhileSavesStopIsAnsweredBusyAndKeepsTheGroup() throws Exception {
        // Harm: a resend while the planner stops is told as a save no longer held, so the person
        // gives up a save that is still kept, or it leaves the board.
        final CommandRunner runner = keptRefused(outbox());
        assertThat(runner.drain(Duration.ofSeconds(5))).as("nothing under way").isTrue();

        mvc.perform(post("/projects/alpha/saves/" + WINDOW + "/resend"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Busy"))
                .andExpect(jsonPath("$.parameters.repository").value("porelo-store"));

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());
        assertThat(ids(shown)).as("still on the board").containsExactly(WINDOW);
        assertThat(shown.path(0).path("commands").path(0).path("state").asText())
                .as("still refused, as before")
                .isEqualTo("REFUSED");
    }

    @Test
    void aHeldCreateIsOnTheBoardByItsRepositorysName() throws Exception {
        // Harm: a new idea or story the outbox keeps refused is left off the board for its clone
        // URL, so the person never sees it, resends it or drops it.
        saves.kept.add(
                kept(
                        WINDOW,
                        OWN,
                        false,
                        "",
                        new CommandOutbox.Told(
                                WINDOW + "/0",
                                "createIdea",
                                "git@example.org:a/porelo-store.git",
                                CommandOutbox.State.REFUSED,
                                new WorkRun.SaveFailure("Changed", "porelo-store", List.of()),
                                List.of())));

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());

        assertThat(ids(shown)).as("the create's group is on the board").containsExactly(WINDOW);
        assertThat(shown.path(0).path("commands").path(0).path("kind").asText())
                .as("told as a new idea")
                .isEqualTo("CREATE_IDEA");
        assertThat(shown.path(0).path("commands").path(0).path("target").asText())
                .as("its repository by its name")
                .isEqualTo("porelo-store");
    }

    @Test
    void aDropWhileSavesStopIsAnsweredBusyAndKeepsTheGroup() throws Exception {
        // Harm: a drop while the planner stops is told as a save no longer held, or reaches an
        // outbox already closed, so the person believes a kept save is gone.
        final CommandRunner runner = keptRefused(outbox());
        assertThat(runner.drain(Duration.ofSeconds(5))).as("nothing under way").isTrue();

        mvc.perform(delete("/projects/alpha/saves/" + WINDOW))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Busy"));
    }

    /** The project's outbox in its state directory, as the planner keeps it. */
    private CommandOutbox outbox() {
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        return new CommandOutbox(
                environment,
                directory,
                "alpha",
                "porelo-store",
                "main",
                PrivateFiles.of(environment),
                LimitFactors.DEFAULT);
    }

    private static final String OLDER = "a0000000-0000-4000-8000-000000000001";

    private static final String NEWER = "a0000000-0000-4000-8000-000000000002";

    /** A group setting the worker of the issue AA1, and priorities, refused, tried at a minute. */
    private static CommandOutbox.Group setting(
            final String id,
            final CommandOutbox.Source source,
            final boolean earlier,
            final String story,
            final String worker,
            final Map<String, String> priorities,
            final int minute) {
        final Map<String, Writes.Reason> none = Map.of();
        final CommandOutbox.Why why = new CommandOutbox.Why("Unreachable", "", "");
        return new CommandOutbox.Group(
                source,
                id,
                List.of(
                        new CommandOutbox.Told(
                                id + "/0",
                                "write",
                                "AA1",
                                CommandOutbox.State.REFUSED,
                                why.failure(),
                                List.of(),
                                new CommandOutbox.Changes(
                                        Map.of(),
                                        Map.of(),
                                        Map.of("AA1", worker),
                                        priorities,
                                        none,
                                        none,
                                        Map.of(),
                                        Map.of(),
                                        List.of(),
                                        List.of(),
                                        false,
                                        List.of(),
                                        List.of(),
                                        "",
                                        List.of()))),
                earlier,
                story,
                List.of(
                        tried(
                                minute,
                                CommandOutbox.State.REFUSED,
                                why,
                                CommandOutbox.Answer.NONE)));
    }

    private static CommandOutbox.Group assigned(final String id, final int minute) {
        return setting(id, OWN, false, "", "someone", Map.of(), minute);
    }

    @Test
    void savesOfOneFieldValueAndSourceFoldIntoOneLineToldByTheNewest() throws Exception {
        // Harm: the person sees one change many times and resends each, or saves that differ in
        // value, source, run or in a further field are shown as one and resent as one.
        final CommandOutbox.Source other = new CommandOutbox.Source("other-store", "");
        saves.kept.add(assigned(OLDER, 1));
        saves.kept.add(setting(WINDOW, OWN, false, "", "another", Map.of(), 2));
        saves.kept.add(setting(ELSEWHERE, other, true, "", "someone", Map.of(), 3));
        saves.kept.add(setting(RUN, OWN, false, "ST1", "someone", Map.of(), 4));
        saves.kept.add(setting(RUNNING, OWN, false, "", "someone", Map.of("AA1", "Low"), 5));
        saves.kept.add(assigned(NEWER, 6));

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());

        assertThat(ids(shown))
                .as("one line for the two alike, told by the newer; the others each alone")
                .containsExactly(NEWER, WINDOW, ELSEWHERE, RUN, RUNNING);
        final List<String> folded = new ArrayList<>();
        shown.path(0).path("saves").forEach(each -> folded.add(each.asText()));
        assertThat(folded).as("every save of the line, oldest first").containsExactly(OLDER, NEWER);
        final List<String> at = new ArrayList<>();
        shown.path(0).path("tries").forEach(each -> at.add(each.path("at").asText()));
        assertThat(at)
                .as("the tries of both")
                .containsExactly(READ.plusSeconds(60).toString(), READ.plusSeconds(360).toString());
        assertThat(shown.path(1).path("saves").size()).as("a save alone names itself").isEqualTo(1);
        assertThat(shown.path(1).path("saves").path(0).asText()).isEqualTo(WINDOW);
    }

    @Test
    void aResendOfAFoldedLineWritesTheNewestOnceAndDropsTheOthers() throws Exception {
        // Harm: the same change is written once per save folded into the line, or the saves
        // folded beside the one written stay held and are resent again.
        saves.kept.add(assigned(OLDER, 1));
        saves.kept.add(assigned(NEWER, 2));

        mvc.perform(post("/projects/alpha/saves/" + OLDER + "/resend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(saves.resent).as("the newest written once").containsExactly(NEWER);
        assertThat(saves.dropped).as("the other dropped").containsExactly(OLDER);
        assertThat(ids(held(changes.subscribe("alpha").firstFrame())))
                .as("nothing held after")
                .isEmpty();
    }

    /** The group with a command before its own, refused as well. */
    private static CommandOutbox.Group beside(
            final CommandOutbox.Group group,
            final String kind,
            final String target,
            final String text) {
        final Map<String, Writes.Reason> none = Map.of();
        final List<CommandOutbox.Told> commands = new ArrayList<>();
        commands.add(
                new CommandOutbox.Told(
                        group.id() + "/9",
                        kind,
                        target,
                        CommandOutbox.State.REFUSED,
                        new CommandOutbox.Why("Unreachable", "", "").failure(),
                        List.of(),
                        new CommandOutbox.Changes(
                                Map.of(), Map.of(), Map.of(), Map.of(), none, none, Map.of(),
                                Map.of(), List.of(), List.of(), false, List.of(), List.of(), text,
                                List.of())));
        commands.addAll(group.commands());
        return new CommandOutbox.Group(
                group.source(),
                group.id(),
                commands,
                group.earlier(),
                group.story(),
                group.tries());
    }

    /** The group tried at each of the minutes. */
    private static CommandOutbox.Group triedAt(
            final CommandOutbox.Group group, final int... minutes) {
        final List<CommandOutbox.Try> tries = new ArrayList<>();
        for (final int minute : minutes) {
            tries.add(
                    tried(
                            minute,
                            CommandOutbox.State.REFUSED,
                            new CommandOutbox.Why("Unreachable", "", ""),
                            CommandOutbox.Answer.NONE));
        }
        return new CommandOutbox.Group(
                group.source(),
                group.id(),
                group.commands(),
                group.earlier(),
                group.story(),
                tries);
    }

    private static String save(final int number) {
        return String.format("b0000000-0000-4000-8000-%012d", number);
    }

    @Test
    void aSaveWithACommandBesideItsOneChangeIsNeverFolded() throws Exception {
        // Harm: a resend of the line writes only the newest save, so a comment or a new item of
        // a save folded into it is dropped unwritten.
        saves.kept.add(beside(assigned(save(1), 1), "comment", "AA1", "first"));
        saves.kept.add(beside(assigned(save(2), 2), "comment", "AA1", "second"));
        saves.kept.add(beside(assigned(save(3), 3), "createIssue", "porelo-store", ""));
        saves.kept.add(beside(assigned(save(4), 4), "createIssue", "porelo-store", ""));

        assertThat(ids(held(changes.subscribe("alpha").firstFrame())))
                .as("each a line of its own")
                .containsExactly(save(1), save(2), save(3), save(4));
    }

    @Test
    void theEarlierFlagAloneKeepsSavesApartAndTriesOfALineInterleaveInTime() throws Exception {
        // Harm: a save kept from before, never written here, is resent with this source's; or a
        // line's tries are told out of their order.
        saves.kept.add(triedAt(assigned(OLDER, 1), 1, 5));
        saves.kept.add(setting(save(1), OWN, true, "", "someone", Map.of(), 2));
        saves.kept.add(triedAt(assigned(NEWER, 3), 3));

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());

        assertThat(ids(shown)).as("the earlier one apart").containsExactly(NEWER, save(1));
        final List<String> at = new ArrayList<>();
        shown.path(0).path("tries").forEach(each -> at.add(each.path("at").asText()));
        assertThat(at)
                .as("both saves' tries in time")
                .containsExactly(
                        READ.plusSeconds(60).toString(),
                        READ.plusSeconds(180).toString(),
                        READ.plusSeconds(300).toString());
    }

    @Test
    void aLineFoldsAtMostItsBoundOfSaves() throws Exception {
        // Harm: a line names more saves than the board carries, so the window's read fails.
        for (int i = 1; i <= HeldSavesController.MOST_SAVES + 1; i++) {
            saves.kept.add(assigned(save(i), 1));
        }

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());

        assertThat(List.of(shown.path(0).path("saves").size(), shown.path(1).path("saves").size()))
                .as("a full line, and one more")
                .containsExactly(HeldSavesController.MOST_SAVES, 1);
    }

    @Test
    void aResendWhoseFoldedDropIsRefusedStandsAndOneRefusedDropsNothing() throws Exception {
        // Harm: a save written is answered as failed, so the person resends it again; or the
        // saves folded beside one the store refused are dropped unwritten.
        saves.kept.add(assigned(OLDER, 1));
        saves.kept.add(assigned(NEWER, 2));
        saves.undroppable.add(OLDER);

        mvc.perform(post("/projects/alpha/saves/" + NEWER + "/resend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));
        assertThat(ids(held(changes.subscribe("alpha").firstFrame())))
                .as("the one not dropped stays held")
                .containsExactly(OLDER);

        saves.undroppable.clear();
        saves.kept.add(assigned(NEWER, 2));
        saves.resendRefusal = new StoreException.Busy("porelo-store");
        mvc.perform(post("/projects/alpha/saves/" + NEWER + "/resend"))
                .andExpect(status().isServiceUnavailable());
        assertThat(saves.dropped).as("nothing dropped beside a refused resend").isEmpty();
    }

    @Test
    void aDropRefusedPartwayIsAnsweredAndKeepsWhatWasDropped() throws Exception {
        // Harm: a drop the outbox refused for a folded save is answered as done, so the person
        // thinks the line gone while a save of it stays held.
        saves.kept.add(assigned(OLDER, 1));
        saves.kept.add(assigned(NEWER, 2));
        saves.undroppable.add(OLDER);

        mvc.perform(delete("/projects/alpha/saves/" + NEWER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Busy"));

        assertThat(saves.dropped).as("the newest dropped before").containsExactly(NEWER);
    }

    @Test
    void aSaveIsDroppedThoughTheBoardCannotBeRead() throws Exception {
        // Harm: a project whose workflow is missing keeps its held saves for good.
        saves.kept.add(assigned(OLDER, 1));
        saves.kept.add(assigned(NEWER, 2));
        boardFailure = new StoreException.WorkflowMissing("porelo-store");

        mvc.perform(delete("/projects/alpha/saves/" + NEWER)).andExpect(status().isNoContent());

        assertThat(saves.dropped).containsExactlyInAnyOrder(OLDER, NEWER);
    }

    @Test
    void aRunsLineIsResentOnlyWhenItsNewestIsResavable() throws Exception {
        // Harm: a halted run's newest save is resent around its run's Stop or Start.
        saves.kept.add(setting(OLDER, OWN, false, "ST1", "someone", Map.of(), 1));
        saves.kept.add(setting(NEWER, OWN, false, "ST1", "someone", Map.of(), 2));
        saves.resavable.add(OLDER);

        mvc.perform(post("/projects/alpha/saves/" + OLDER + "/resend"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:SaveOfRun"))
                .andExpect(jsonPath("$.parameters.save").value(NEWER));
        assertThat(saves.resaved).as("nothing saved again").isEmpty();
    }

    @Test
    void aDropOfAFoldedLineDropsEverySaveOfIt() throws Exception {
        // Harm: dropping the line the person sees leaves the saves folded into it held.
        saves.kept.add(assigned(OLDER, 1));
        saves.kept.add(assigned(NEWER, 2));

        mvc.perform(delete("/projects/alpha/saves/" + NEWER)).andExpect(status().isNoContent());

        assertThat(saves.dropped).as("both dropped").containsExactlyInAnyOrder(OLDER, NEWER);
        assertThat(saves.resent).as("nothing written").isEmpty();
    }

    @Test
    void aDroppedGroupLeavesTheOutbox() throws Exception {
        // Harm: a held save the person gave up stays kept forever and keeps the outbox full.
        saves.kept.add(refused(WINDOW, ""));
        final Changes.Subscription window = changes.subscribe("alpha");

        mvc.perform(delete("/projects/alpha/saves/" + WINDOW)).andExpect(status().isNoContent());

        assertThat(saves.dropped).as("the group dropped from the outbox").containsExactly(WINDOW);
        assertThat(saves.resent).as("nothing written").isEmpty();
        assertThat(ids(held(window.nextFrame(Duration.ofSeconds(2)).orElseThrow())))
                .as("the windows are told the board without it")
                .isEmpty();
        mvc.perform(delete("/projects/alpha/saves/" + WINDOW))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchSave"));
    }

    @Test
    void aRunsGroupIsNotResent() throws Exception {
        // Harm: a halted run's save written behind its halt, or a run's refused task end resent
        // through the outbox instead of its run, which then never clears its refusal.
        saves.kept.add(refused(RUN, "ST1"));
        final JsonNode halted = held(changes.subscribe("alpha").firstFrame());
        assertThat(halted.path(0).path("run").asBoolean()).as("a run's group").isTrue();
        assertThat(halted.path(0).path("resendable").asBoolean())
                .as("a halted run's group is not offered to resend")
                .isFalse();

        mvc.perform(post("/projects/alpha/saves/" + RUN + "/resend"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:SaveOfRun"))
                .andExpect(jsonPath("$.parameters.save").value(RUN));
        assertThat(saves.resent).as("not resent through the runner").isEmpty();
        assertThat(saves.resaved).as("not saved again by the halted run").isEmpty();

        saves.resavable.add(RUN);
        mvc.perform(post("/projects/alpha/saves/" + RUN + "/resend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r3"));
        assertThat(saves.resaved).as("saved again once, by its run").containsExactly(RUN);
        assertThat(saves.resent).as("never through the runner").isEmpty();
    }

    @Test
    void aHaltedRunsGroupInTheOutboxAnswersSaveOfRun() throws Exception {
        // Harm: a halted run's save the outbox keeps is offered to resend and written through the
        // runner behind the run's halt, as if it were a window's.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> current);
        final Command.Write move =
                new Command.Write(
                        RUN + "/0",
                        "ST1",
                        new Writes(Map.of(), Map.of("ST1", "Finished")),
                        Basis.of(current),
                        Recheck.none());
        outbox.enqueue(new CommandGroup(RUN, List.of(move), "ST1"));
        outbox.begin(move.id());
        outbox.refused(
                move.id(), new StoreException.Unreachable("porelo-store", "timed out"), true, null);
        given = new OutboxHeldSaves(outbox, new CommandRunner(outbox, store), HeldSaves.NO_RUNS);

        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());
        assertThat(ids(shown)).as("the run's group").containsExactly(RUN);
        assertThat(shown.path(0).path("run").asBoolean()).as("told as a run's").isTrue();
        assertThat(shown.path(0).path("resendable").asBoolean()).as("not resendable").isFalse();

        mvc.perform(post("/projects/alpha/saves/" + RUN + "/resend"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:SaveOfRun"))
                .andExpect(jsonPath("$.parameters.save").value(RUN));
        assertThat(outbox.pending())
                .as("kept for the run's Stop or Start")
                .extracting(CommandOutbox.Group::id)
                .containsExactly(RUN);
        assertThat(outbox.told(RUN).getFirst().state())
                .as("nothing sent: held as the halt left it")
                .isEqualTo(CommandOutbox.State.HELD);
    }

    @Test
    void anotherSourcesGroupIsNotResent() throws Exception {
        // Harm: a save judged against another repository or branch is written to this one.
        saves.kept.add(
                kept(
                        ELSEWHERE,
                        new CommandOutbox.Source("git@example.org:a/old-store.git", "planning"),
                        true,
                        "",
                        told(
                                ELSEWHERE,
                                0,
                                "ST1",
                                CommandOutbox.State.HELD,
                                new WorkRun.SaveFailure("Changed", "ST1", List.of()))));
        final JsonNode shown = held(changes.subscribe("alpha").firstFrame());
        assertThat(shown.path(0).path("earlier").asBoolean()).as("kept from elsewhere").isTrue();
        assertThat(shown.path(0).path("resendable").asBoolean()).as("not resendable").isFalse();
        assertThat(shown.path(0).path("branch").asText()).as("its branch").isEqualTo("planning");

        mvc.perform(post("/projects/alpha/saves/" + ELSEWHERE + "/resend"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:SaveFromElsewhere"))
                .andExpect(
                        jsonPath("$.parameters.repository")
                                .value("git@example.org:a/old-store.git"))
                .andExpect(jsonPath("$.parameters.branch").value("planning"));
        assertThat(saves.resent).as("not resent").isEmpty();

        mvc.perform(delete("/projects/alpha/saves/" + ELSEWHERE)).andExpect(status().isNoContent());
        assertThat(saves.dropped).as("but dropped").containsExactly(ELSEWHERE);
    }

    @Test
    void aRunningGroupIsNotDroppedOrResent() throws Exception {
        // Harm: a group under way resent beside itself, written twice, or dropped while its
        // command is at the store, so its outcome is lost; one refused beside a command under way
        // told as held.
        saves.kept.add(
                kept(
                        RUNNING,
                        OWN,
                        false,
                        "",
                        told(RUNNING, 0, "ST1", CommandOutbox.State.DONE, null),
                        told(RUNNING, 1, "porelo-store", CommandOutbox.State.IN_FLIGHT, null)));
        saves.kept.add(
                kept(
                        WINDOW,
                        OWN,
                        false,
                        "",
                        told(
                                WINDOW,
                                0,
                                "ST1",
                                CommandOutbox.State.REFUSED,
                                new WorkRun.SaveFailure("Changed", "ST1", List.of())),
                        told(WINDOW, 1, "porelo-store", CommandOutbox.State.IN_FLIGHT, null)));

        assertThat(ids(held(changes.subscribe("alpha").firstFrame())))
                .as("neither on the board")
                .isEmpty();
        for (final String id : List.of(RUNNING, WINDOW)) {
            mvc.perform(post("/projects/alpha/saves/" + id + "/resend"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:SaveNotHeld"));
            mvc.perform(delete("/projects/alpha/saves/" + id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:SaveNotHeld"));
        }
        assertThat(saves.resent).as("nothing resent").isEmpty();
        assertThat(saves.dropped).as("nothing dropped").isEmpty();
    }

    @Test
    void aSaveIdThatIsNoneIsRefusedBeforeAnythingIsAsked() throws Exception {
        // Harm: a path's words reach the outbox or a refusal unchecked.
        mvc.perform(post("/projects/alpha/saves/not-a-save/resend"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
        mvc.perform(delete("/projects/beta/saves/" + WINDOW))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"));
        assertThat(saves.resent).as("nothing resent").isEmpty();
        assertThat(saves.dropped).as("nothing dropped").isEmpty();
    }
}
