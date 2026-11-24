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

        @Override
        public List<CommandOutbox.Group> groups() {
            return List.copyOf(kept);
        }

        @Override
        public CommandOutcome resend(final String group) {
            resent.add(group);
            // Written whole, the group is pruned from the file.
            kept.removeIf(each -> each.id().equals(group));
            current = board("r2");
            return new CommandOutcome.Done(current, Map.of());
        }

        @Override
        public void drop(final String group) {
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

    @BeforeEach
    void serve() throws Exception {
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
        outbox.refused(move.id(), new StoreException.Changed(List.of("ST1")), false, null);
        final CommandRunner runner = new CommandRunner(outbox, store);
        given = new OutboxHeldSaves(outbox, runner, HeldSaves.NO_RUNS);
        return runner;
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
