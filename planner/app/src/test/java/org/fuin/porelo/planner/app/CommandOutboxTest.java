package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.app.CommandOutbox.State;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.DirectQueue;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's commands kept on disk before each call to the store, and settled at open after the
 * planner stopped before, during or after a command's write.
 */
class CommandOutboxTest {

    @TempDir Path directory;

    private Map<String, String> environment() {
        return Map.of("XDG_STATE_HOME", directory.toString());
    }

    private CommandOutbox outbox(final String branch) {
        return new CommandOutbox(
                environment(),
                directory,
                "planning",
                "porelo-lib",
                branch,
                PrivateFiles.of(environment()),
                LimitFactors.DEFAULT);
    }

    private CommandOutbox outbox() {
        return outbox("main");
    }

    private static Issue issue(final String id, final String state, final List<String> blockedBy) {
        return new Issue(
                id,
                "porelo-lib",
                "Title of " + id,
                state,
                null,
                blockedBy,
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

    private static Board board(final Issue... issues) {
        return new Board(
                new Revision("r1"),
                new Workflow(List.of("Ready", "Building", "Review", "Done"), List.of(), List.of()),
                List.of(),
                List.of(issues),
                new Work(Map.of(), List.of()));
    }

    private static final Board BEFORE =
            board(
                    issue("AA1", "Ready", List.of("AB2")),
                    issue("AB2", "Ready", List.of()),
                    issue("AC3", "Ready", List.of()));

    private static Command.Write move(final String id, final String issue, final String state) {
        return new Command.Write(
                id,
                issue,
                new Writes(Map.of(issue, state), Map.of()),
                Basis.of(BEFORE),
                Recheck.none());
    }

    private static Command.Write close(final String id, final String issue) {
        return new Command.Write(
                id,
                issue,
                new Writes(
                        Map.of(issue, "Done"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(issue, new Writes.Reason("Done", "It is built.")),
                        Map.of()),
                Basis.of(BEFORE),
                Recheck.none());
    }

    private static Board with(final Board board, final Issue changed) {
        final List<Issue> issues = new ArrayList<>(board.issues());
        issues.replaceAll(each -> each.id().equals(changed.id()) ? changed : each);
        return board(issues.toArray(Issue[]::new));
    }

    private static State state(final CommandOutbox outbox, final String id) {
        return outbox.pending().stream()
                .flatMap(group -> group.commands().stream())
                .filter(told -> told.id().equals(id))
                .findFirst()
                .orElseThrow()
                .state();
    }

    /** Leaves a group with its first command begun, as a planner stopped during its call. */
    private void begunAndStopped(final CommandGroup group) {
        final CommandOutbox first = outbox();
        first.open(() -> BEFORE);
        first.enqueue(group);
        first.begin(group.commands().getFirst().id());
        first.close();
    }

    private Path file() {
        return directory.resolve(CommandOutbox.FILE);
    }

    private void edit(final String old, final String now) throws IOException {
        final String text = Files.readString(file());
        assertThat(text).as("the text to forge").contains(old);
        Files.writeString(file(), text.replace(old, now));
    }

    @Test
    void aGroupQueuedBeforeAStopIsQueuedAgainWithItsChanges() {
        // Harm: a save lost by a restart before its first call.
        final Command.Write first = move("c1", "AA1", "Building");
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(first, move("c2", "AB2", "Building"))));
        outbox.close();

        final CommandOutbox again = outbox();
        assertThat(again.open(() -> BEFORE)).as("nothing was begun").isEmpty();

        assertThat(state(again, "c1")).isEqualTo(State.QUEUED);
        assertThat(state(again, "c2")).isEqualTo(State.QUEUED);
        final Command read = again.command("c1");
        assertThat(read)
                .as("the changes and the versions judged, read back")
                .isInstanceOfSatisfying(
                        Command.Write.class,
                        write -> {
                            assertThat(write.writes()).isEqualTo(first.writes());
                            assertThat(write.basis()).isEqualTo(first.basis());
                        });
    }

    @Test
    void aStopAtTheMoveKeepsTheFileBeforeAndTheCommandWaiting() throws IOException {
        // Harm: a command told begun in memory or on disk when its write never reached the file,
        // or a half-written file left in place of the one before.
        final AtomicInteger moves = new AtomicInteger();
        final PrivateFiles files = PrivateFiles.of(environment());
        final DurableFile failing =
                new DurableFile(
                        files,
                        (written, target) -> {
                            if (moves.incrementAndGet() == 2) {
                                throw new IOException("stopped at the move");
                            }
                            Files.move(
                                    written,
                                    target,
                                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        },
                        LimitFactors.DEFAULT);
        final CommandOutbox outbox =
                new CommandOutbox(
                        environment(), directory, "planning", "porelo-lib", "main", files, failing);
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        final String before = Files.readString(file());

        assertThatThrownBy(() -> outbox.begin("c1")).isInstanceOf(WorkRefusal.class);

        assertThat(state(outbox, "c1")).as("memory follows the file only").isEqualTo(State.QUEUED);
        assertThat(Files.readString(file())).as("the file before").isEqualTo(before);
        Files.writeString(
                Files.createFile(
                        directory.resolve("outbox-left.tmp"),
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rw-------"))),
                "{\"torn");
        outbox.close();
        final CommandOutbox again = outbox();
        again.open(() -> BEFORE);
        assertThat(state(again, "c1")).isEqualTo(State.QUEUED);
        assertThat(directory.resolve("outbox-left.tmp"))
                .as("a sibling left is removed")
                .doesNotExist();
    }

    @Test
    void aCommandBegunWhoseTargetStandsAtItsVersionIsQueuedAgain() {
        // Harm: a save lost when the planner stopped before its call reached the store.
        begunAndStopped(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));

        final CommandOutbox again = outbox();
        assertThat(again.open(() -> BEFORE)).containsEntry("c1", State.QUEUED);
        assertThat(state(again, "c1")).isEqualTo(State.QUEUED);
    }

    @Test
    void aCommandBegunThatTheBoardShowsIsDoneAndItsGroupGoesOn() {
        // Harm: a command written twice when the planner stopped after its call.
        begunAndStopped(
                new CommandGroup(
                        "g1",
                        List.of(move("c1", "AA1", "Building"), move("c2", "AB2", "Building"))));
        final Board after = with(BEFORE, issue("AA1", "Building", List.of("AB2")));

        final CommandOutbox again = outbox();
        assertThat(again.open(() -> after)).containsEntry("c1", State.DONE);
        assertThat(state(again, "c1")).isEqualTo(State.DONE);
        assertThat(state(again, "c2")).as("the next one waits").isEqualTo(State.QUEUED);
    }

    @Test
    void aCommandBegunWhoseTargetMovedOtherwiseIsHeldWithItsGroup() {
        // Harm: another's change overwritten by a command sent again on a version that moved.
        begunAndStopped(
                new CommandGroup(
                        "g1",
                        List.of(move("c1", "AA1", "Building"), move("c2", "AB2", "Building"))));
        final Board moved = with(BEFORE, issue("AA1", "Review", List.of("AB2")));

        final CommandOutbox again = outbox();
        assertThat(again.open(() -> moved))
                .containsEntry("c1", State.HELD)
                .containsEntry("c2", State.HELD);
        final CommandOutbox.Told held = again.pending().getFirst().commands().getFirst();
        assertThat(held.why()).isNotNull();
        assertThat(held.why().name()).isEqualTo("Changed");
        assertThat(held.why().subject()).isEqualTo("AA1");
    }

    @Test
    void aCloseStoppedBeforeItsReasonIsHeldNotDone() {
        // Harm: a close told done whose reason comment was never written.
        begunAndStopped(new CommandGroup("g1", List.of(close("c1", "AA1"))));
        final Board closed = with(BEFORE, issue("AA1", "Done", List.of("AB2")));

        final CommandOutbox again = outbox();
        assertThat(again.open(() -> closed)).containsEntry("c1", State.HELD);
    }

    @Test
    void aCreateOrACommentBegunIsHeldNeverSentAgain() {
        // Harm: an issue or a comment made twice by a restart.
        begunAndStopped(
                new CommandGroup(
                        "g1",
                        List.of(
                                new Command.CreateIssue(
                                        "c1",
                                        new NewIssue("porelo-lib", "New", "It works.", null),
                                        Basis.none(),
                                        Recheck.none()))));
        final CommandOutbox again = outbox();
        assertThat(again.open(() -> BEFORE)).containsEntry("c1", State.HELD);
        again.close();

        final CommandOutbox other = outbox("other");
        other.open(() -> BEFORE);
        other.enqueue(new CommandGroup("g2", List.of(new Command.Comment("c2", "AA1", "Seen."))));
        other.begin("c2");
        other.close();
        final CommandOutbox last = outbox("other");
        assertThat(last.open(() -> BEFORE)).containsEntry("c2", State.HELD);
    }

    @Test
    void aCommandReadBackIsCheckedAgainstEveryItemItReadFreshNotOnlyItsTarget() {
        // Harm: a command read back after a restart written over a blocker that moved since it was
        // judged, as no rule of it survives the restart.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        outbox.close();
        final CommandOutbox again = outbox();
        again.open(() -> BEFORE);
        final Command read = again.command("c1");
        assertThat(read.recheck().from())
                .as("every item judged is read afresh")
                .containsExactlyInAnyOrder("AA1", "AB2", "AC3");
        assertThat(read.recheck().needs()).as("a need makes the store read them").isNotEmpty();
        final Board blockerMoved = with(BEFORE, issue("AB2", "Building", List.of()));
        final List<String> saves = new ArrayList<>();
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return blockerMoved;
                    }

                    @Override
                    public Board refresh() {
                        return blockerMoved;
                    }

                    @Override
                    public Board save(final Revision revision, final Writes writes) {
                        saves.add("save");
                        return blockerMoved;
                    }
                };

        final CommandOutcome outcome =
                new DirectQueue(store).submit(new CommandGroup("g1", List.of(read)));

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Refused.class,
                        refused ->
                                assertThat(refused.cause())
                                        .isInstanceOfSatisfying(
                                                StoreException.Changed.class,
                                                changed ->
                                                        assertThat(changed.ids())
                                                                .containsExactly("AB2")));
        assertThat(saves).as("nothing written").isEmpty();
    }

    @Test
    void aFileOfAnotherBranchIsNeverRunAndItsCommandsAreKeptHeld() throws IOException {
        // Harm: commands judged on one branch written to another.
        final CommandOutbox outbox = outbox("main");
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        outbox.close();

        final CommandOutbox other = outbox("feature");
        other.open(() -> BEFORE);
        final List<CommandOutbox.Group> pending = other.pending();
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().earlier()).as("kept aside").isTrue();
        assertThat(pending.getFirst().source().branch()).isEqualTo("main");
        assertThat(pending.getFirst().commands().getFirst().state()).isEqualTo(State.HELD);
        assertThatThrownBy(() -> other.begin("c1"))
                .as("never run")
                .isInstanceOf(IllegalStateException.class);
        other.close();

        edit("\"format\":1", "\"format\":2");
        assertThatThrownBy(() -> outbox("feature").open(() -> BEFORE))
                .as("a newer form")
                .isInstanceOf(WorkRefusal.class);
        edit("\"format\":2", "\"format\":1");
        edit("\"project\":\"planning\"", "\"project\":\"another\"");
        assertThatThrownBy(() -> outbox("feature").open(() -> BEFORE))
                .as("another project's")
                .isInstanceOf(WorkRefusal.class);
    }

    @Test
    void aGroupLeavingNoReserveIsRefusedWholeAndNothingOfItQueued() throws IOException {
        // Harm: a save queued so near the cap that holding or ending its commands cannot be
        // written.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        final String before = Files.readString(file());
        final String large = "x".repeat(CommandOutbox.MOST - CommandOutbox.RESERVE / 2);

        assertThatThrownBy(
                        () ->
                                outbox.enqueue(
                                        new CommandGroup(
                                                "g2",
                                                List.of(new Command.Comment("c2", "AA1", large)))))
                .isInstanceOf(WorkRefusal.class);

        assertThat(Files.readString(file())).as("nothing of it queued").isEqualTo(before);
        assertThat(outbox.pending()).hasSize(1);
    }

    @Test
    void onlyTheOutboxsOwnStepsAreTakenAndAFileOutOfOrderIsRefused() throws IOException {
        // Harm: a command sent before the one it follows, or a file whose order the outbox never
        // makes read as a queue to run.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(
                new CommandGroup(
                        "g1",
                        List.of(move("c1", "AA1", "Building"), move("c2", "AB2", "Building"))));
        assertThatThrownBy(() -> outbox.begin("c2"))
                .as("before the one it follows")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.done("c1"))
                .as("done before begun")
                .isInstanceOf(IllegalStateException.class);
        outbox.begin("c1");
        assertThatThrownBy(() -> outbox.begin("c1"))
                .as("begun twice")
                .isInstanceOf(IllegalStateException.class);
        outbox.done("c1");
        assertThatThrownBy(() -> outbox.done("c1"))
                .as("done twice")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTornOrForgedFileIsRefusedLoud() throws IOException {
        // Harm: a forged or broken queue run against the store.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(
                new CommandGroup(
                        "g1",
                        List.of(move("c1", "AA1", "Building"), move("c2", "AB2", "Building"))));
        outbox.close();
        final String good = Files.readString(file());
        final int second = good.lastIndexOf("\"state\":\"QUEUED\"");

        final List<String> forged =
                List.of(
                        good.substring(0, good.length() / 2),
                        good.replace("\"format\":1", "\"format\":1,\"extra\":1"),
                        good.replace("\"format\":1", "\"format\":1,\"format\":1"),
                        good.replace("\"text\":\"\"", "\"text\":null"),
                        good.replace("\"AA1\":\"Building\"", "\"AA1\":null"),
                        good.replace("\"kind\":\"write\"", "\"kind\":\"comment\""),
                        good.replace("\"why\":{\"name\":\"\"", "\"why\":{\"name\":\"Whatever\""),
                        good.replaceFirst("\"state\":\"QUEUED\"", "\"state\":\"REFUSED\""),
                        good.replace("\"id\":\"c2\"", "\"id\":\"c1\""),
                        good.substring(0, second)
                                + "\"state\":\"DONE\""
                                + good.substring(second + "\"state\":\"QUEUED\"".length()),
                        good.replace("\"state\":\"QUEUED\"", "\"state\":\"IN_FLIGHT\""));
        for (final String text : forged) {
            assertThat(text).as("forged").isNotEqualTo(good);
            Files.writeString(file(), text);
            assertThatThrownBy(() -> outbox().open(() -> BEFORE))
                    .as(text)
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    @Test
    void aClosedOutboxWritesNothing() {
        // Harm: a runner left from before writing beside the outbox of the project opened again.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        outbox.close();

        assertThatThrownBy(
                        () ->
                                outbox.enqueue(
                                        new CommandGroup(
                                                "g2", List.of(move("c2", "AB2", "Building")))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.begin("c1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.done("c1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRefusalIsKeptAsTheStoresKindNeverAsAnExceptionsWords() {
        // Harm: free words of an exception, which may quote local paths or secrets, kept on disk.
        final CommandOutbox.Why other =
                CommandOutbox.Why.of(new IllegalStateException("/home/someone/secret"));
        assertThat(other).isEqualTo(new CommandOutbox.Why("NotOffered", "", ""));
        final CommandOutbox.Why changed =
                CommandOutbox.Why.of(new StoreException.Changed(List.of("AA1")));
        assertThat(changed).isEqualTo(new CommandOutbox.Why("Changed", "AA1", ""));
        assertThat(new CommandOutbox.Why("Changed", "AA1\u0007", "").sound()).isFalse();
        assertThat(new CommandOutbox.Why("Whatever", "AA1", "").sound()).as("no kind").isFalse();
        assertThat(new CommandOutbox.Why("Changed", "A".repeat(257), "").sound())
                .as("unbounded")
                .isFalse();
    }

    @Test
    void aRefusedCallSettlesByWhetherItMayHaveWrittenAndHoldsTheRestOfItsGroup() {
        // Harm: a command that may stand in part sent again, or one refused before writing held
        // for the owner account; a group's later commands sent after one before them failed.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(
                new CommandGroup(
                        "g1",
                        List.of(move("c1", "AA1", "Building"), move("c2", "AB2", "Building"))));
        outbox.enqueue(
                new CommandGroup(
                        "g2", List.of(move("c3", "AC3", "Building"), move("c4", "AB2", "Review"))));
        outbox.enqueue(new CommandGroup("g3", List.of(move("c5", "AC3", "Review"))));

        outbox.begin("c1");
        assertThat(
                        outbox.refused(
                                "c1",
                                new StoreException.PartlySaved("porelo-lib", List.of(), "failed"),
                                true,
                                with(BEFORE, issue("AA1", "Building", List.of("AB2")))))
                .as("the board shows it")
                .isEqualTo(State.DONE);
        outbox.begin("c2");
        assertThat(outbox.refused("c2", new StackOverflowError(), true, null))
                .isEqualTo(State.HELD);
        outbox.begin("c3");
        assertThat(outbox.refused("c3", new StoreException.Changed(List.of("AC3")), false, BEFORE))
                .isEqualTo(State.REFUSED);
        assertThat(state(outbox, "c4")).as("held behind it").isEqualTo(State.HELD);
        outbox.begin("c5");
        outbox.done("c5");

        final List<CommandOutbox.Group> pending = outbox.pending();
        assertThat(pending).extracting(CommandOutbox.Group::id).containsExactly("g1", "g2");
        assertThat(pending.getFirst().commands())
                .as("the whole group, the done one named with its target")
                .extracting(
                        CommandOutbox.Told::id,
                        CommandOutbox.Told::target,
                        CommandOutbox.Told::state)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("c1", "AA1", State.DONE),
                        org.assertj.core.groups.Tuple.tuple("c2", "AB2", State.HELD));
    }

    @Test
    void everyKindOfCommandIsReadBackAsItWasQueued() {
        // Harm: a command read back after a restart writing other changes than those judged.
        final org.fuin.porelo.planner.store.Fields fields =
                new org.fuin.porelo.planner.store.Fields(
                        java.util.Set.of(
                                org.fuin.porelo.planner.store.Fields.Field.SIZE,
                                org.fuin.porelo.planner.store.Fields.Field.RANK),
                        "M",
                        null,
                        null,
                        12);
        final org.fuin.porelo.planner.store.TextEdit text =
                new org.fuin.porelo.planner.store.TextEdit(
                        org.fuin.porelo.planner.store.TextEdit.Kind.ISSUE,
                        "A title",
                        Map.of(org.fuin.porelo.planner.store.TextEdit.Part.PLAN, "The plan."),
                        java.util.Set.of(org.fuin.porelo.planner.store.TextEdit.Part.FINDINGS),
                        List.of());
        final NewIssue issue =
                new NewIssue(
                        "porelo-lib",
                        "New",
                        "It works.",
                        "S",
                        Worker.MANUAL,
                        null,
                        "High",
                        null,
                        "Ready",
                        List.of());
        final List<Command> all =
                List.of(
                        close("c1", "AA1"),
                        new Command.SetFields(
                                "c2", "AA1", fields, Basis.of(BEFORE), Recheck.none()),
                        new Command.SetText("c3", "AA1", text, Basis.of(BEFORE), Recheck.none()),
                        new Command.SetBlockers(
                                "c4", "AA1", List.of("AC3"), Basis.of(BEFORE), Recheck.none()),
                        new Command.SetStoryIssues(
                                "c5", "ST1", List.of("AA1"), Basis.of(BEFORE), Recheck.none()),
                        new Command.CorrectLabels(
                                "c6",
                                "porelo-lib",
                                List.of(
                                        new org.fuin.porelo.planner.store.RepositoryWarning.Call(
                                                org.fuin.porelo.planner.store.RepositoryWarning.Call
                                                        .Action.CREATE,
                                                "topic",
                                                "ffffff",
                                                null))),
                        new Command.Comment("c7", "AA1", "Seen."),
                        new Command.CreateIssue("c8", issue, Basis.of(BEFORE), Recheck.none()),
                        new Command.CreateIdea(
                                "c9",
                                new org.fuin.porelo.planner.store.NewIdea(
                                        "Idea", "Gain", "Test", "Medium", List.of(issue)),
                                Basis.of(BEFORE),
                                Recheck.none()),
                        new Command.CreateStory(
                                "c10",
                                new org.fuin.porelo.planner.store.StoryFromIssues(
                                        "Story", "Gain", "Test", "High", List.of("AA1")),
                                Basis.of(BEFORE),
                                Recheck.none()),
                        new Command.AddUnlisted("c11", "AC3", Basis.of(BEFORE), Recheck.none()));
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", all));
        outbox.close();

        final CommandOutbox again = outbox();
        again.open(() -> BEFORE);
        for (final Command queued : all) {
            final Command read = again.command(queued.id());
            assertThat(withoutRule(read)).as(queued.id()).isEqualTo(withoutRule(queued));
        }
        assertThat(again.pending().getFirst().commands())
                .extracting(CommandOutbox.Told::target)
                .as("each names its target")
                .containsExactly(
                        "AA1",
                        "AA1",
                        "AA1",
                        "AA1",
                        "ST1",
                        "porelo-lib",
                        "AA1",
                        "porelo-lib",
                        "porelo-lib",
                        "porelo-lib",
                        "AC3");
    }

    /** A command's data without its rule, which is code and never kept. */
    private static String withoutRule(final Command command) {
        return command.toString()
                .replaceAll(
                        "recheck=Recheck\\[needs=\\[[^\\]]*\\], from=\\[[^\\]]*\\], rule=[^\\]]*\\]",
                        "");
    }

    @Test
    void aSyncFailureAfterTheMoveKeepsMemoryWithTheFileAndSaysTheGroupMayStand() {
        // Harm: a group told "not queued" that the file holds, so it is run after a restart while
        // the caller made the save again.
        final PrivateFiles files = PrivateFiles.of(environment());
        final AtomicInteger syncs = new AtomicInteger();
        final DurableFile unsynced =
                new DurableFile(
                        files,
                        (written, target) ->
                                Files.move(
                                        written,
                                        target,
                                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING),
                        LimitFactors.DEFAULT,
                        dir -> {
                            if (syncs.incrementAndGet() == 1) {
                                throw new IOException("the disk did not answer");
                            }
                        });
        final CommandOutbox outbox =
                new CommandOutbox(
                        environment(),
                        directory,
                        "planning",
                        "porelo-lib",
                        "main",
                        files,
                        unsynced);
        outbox.open(() -> BEFORE);

        assertThatThrownBy(
                        () ->
                                outbox.enqueue(
                                        new CommandGroup(
                                                "g1", List.of(move("c1", "AA1", "Building")))))
                .isInstanceOf(WorkRefusal.class)
                .hasMessageContaining("may stand");

        assertThat(state(outbox, "c1")).as("memory follows the file").isEqualTo(State.QUEUED);
        outbox.close();
        final CommandOutbox again = outbox();
        again.open(() -> BEFORE);
        assertThat(state(again, "c1")).as("the file holds it").isEqualTo(State.QUEUED);
    }

    @Test
    void aCommandResumedAndThenRefusedIsHeldNeverToldUnwritten() {
        // Harm: a command that may stand in part from before the restart told as never written,
        // so the owner account sends it again over what it wrote.
        begunAndStopped(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        final CommandOutbox again = outbox();
        assertThat(again.open(() -> BEFORE)).containsEntry("c1", State.QUEUED);
        again.begin("c1");

        assertThat(again.refused("c1", new StoreException.Changed(List.of("AA1")), false, BEFORE))
                .isEqualTo(State.HELD);
    }

    @Test
    void aConflictKeepsItsPlacesForTheOwnerAccountAndHoldsTheTail() {
        // Harm: the owner account told of a conflict without where it lies, or a tail written after
        // it.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(
                new CommandGroup(
                        "g1",
                        List.of(move("c1", "AA1", "Building"), move("c2", "AB2", "Building"))));
        outbox.begin("c1");
        outbox.conflict("c1", List.of("STATE", "FIELD:Size"));
        outbox.close();

        final CommandOutbox again = outbox();
        again.open(() -> BEFORE);
        final List<CommandOutbox.Told> told = again.told("g1");
        assertThat(told.getFirst().state()).isEqualTo(State.REFUSED);
        assertThat(told.getFirst().places()).containsExactly("STATE", "FIELD:Size");
        assertThat(told.get(1).state()).isEqualTo(State.HELD);
    }

    @Test
    void onlyAHeldGroupIsDroppedOrResentAndOneOfAnotherSourceIsNeverResent() {
        // Harm: a waiting save dropped unseen, or a save judged on one branch sent to another.
        final CommandOutbox outbox = outbox("main");
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        assertThatThrownBy(() -> outbox.drop("g1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> outbox.resend("g1")).isInstanceOf(IllegalStateException.class);
        outbox.begin("c1");
        outbox.conflict("c1", List.of("STATE"));
        outbox.resend("g1");
        assertThat(state(outbox, "c1")).as("waits again").isEqualTo(State.QUEUED);
        assertThat(outbox.command("c1").basis())
                .as("with the versions it was judged on")
                .isEqualTo(Basis.of(BEFORE));
        outbox.begin("c1");
        outbox.conflict("c1", List.of("STATE"));
        outbox.close();

        final CommandOutbox other = outbox("feature");
        other.open(() -> BEFORE);
        assertThatThrownBy(() -> other.resend("g1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("porelo-lib on main");
        other.drop("g1");
        assertThat(other.pending()).as("dropped").isEmpty();
    }

    @Test
    void aGroupWrittenWholeIsForgottenInMemoryToo() {
        // Harm: memory growing with every save, and a command run again from memory once its group
        // left the file.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        outbox.begin("c1");
        outbox.done("c1");

        assertThatThrownBy(() -> outbox.command("c1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anOpenReadsTheBoardOnlyWhenACommandWasBegun() {
        // Harm: every project open paying a whole board read, though nothing was begun.
        final AtomicInteger reads = new AtomicInteger();
        final CommandOutbox first = outbox();
        first.open(
                () -> {
                    reads.incrementAndGet();
                    return BEFORE;
                });
        first.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        first.close();
        final CommandOutbox waiting = outbox();
        waiting.open(
                () -> {
                    reads.incrementAndGet();
                    return BEFORE;
                });
        waiting.close();
        assertThat(reads).as("nothing begun, nothing read").hasValue(0);

        begunAndStopped(new CommandGroup("g2", List.of(move("c2", "AB2", "Building"))));
        final CommandOutbox begun = outbox();
        begun.open(
                () -> {
                    reads.incrementAndGet();
                    return BEFORE;
                });
        assertThat(reads).as("one read for the begun command").hasValue(1);
    }

    @Test
    void aGroupHeldByTheRepairAtOpenWaitsForTheOwnerAccountWithItsReason() {
        // Harm: a group the run's pending mark names sent again by itself, though its run halted.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(
                new CommandGroup(
                        "g1",
                        List.of(move("c1", "AA1", "Building"), move("c2", "AB2", "Building"))));
        outbox.begin("c1");
        outbox.done("c1");

        outbox.hold("g1", new StoreException.Changed(List.of("AB2")));
        outbox.close();

        final CommandOutbox again = outbox();
        again.open(() -> BEFORE);
        assertThat(again.told("g1"))
                .extracting(CommandOutbox.Told::state)
                .containsExactly(State.DONE, State.HELD);
        assertThat(again.told("g1").get(1).why()).isNotNull();
        assertThat(again.waiting()).as("never run by itself").isEmpty();
    }

    @Test
    void aRunsGroupKeepsItsStoryAndIsNeverSentAgainByTheOwnerAccount() {
        // Harm: a run's save sent again behind its halt, so the story moves while its run stopped.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building")), "ST1"));
        outbox.begin("c1");
        outbox.conflict("c1", List.of("STATE"));
        outbox.close();

        final CommandOutbox again = outbox();
        again.open(() -> BEFORE);
        assertThat(again.story("g1")).as("kept in the file").isEqualTo("ST1");
        assertThat(again.pending().getFirst().story()).isEqualTo("ST1");
        assertThatThrownBy(() -> again.resend("g1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ST1");
    }

    /** Runs whose kept event is saved again at the first resave, or refused as given. */
    private static final class Resaves implements org.fuin.porelo.planner.core.RefusedEventResave {
        boolean kept = true;
        @org.jspecify.annotations.Nullable RuntimeException refusal;

        @Override
        public boolean resavable(final String story, final String group) {
            return kept;
        }

        @Override
        public Board resave(final String story, final String group) {
            final RuntimeException refused = refusal;
            if (refused != null) {
                throw refused;
            }
            kept = false;
            return BEFORE;
        }
    }

    @Test
    void aRunsGroupRefusedBeforeWritingLeavesTheOutboxOnceItsEndIsSavedAgain() {
        // Harm: a run's refused group kept in the outbox after its event was saved again, shown
        // to the owner account as a save still waiting, or kept while the run still needs it.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(
                new CommandGroup(
                        "g1",
                        List.of(move("c1", "AA1", "Building"), move("c2", "AB2", "Building")),
                        "ST1"));
        outbox.begin("c1");
        outbox.refused("c1", new StoreException.NotSavable("AA1", "failed"), false, null);
        final Resaves runs = new Resaves();
        final OutboxResave resaves = new OutboxResave(runs, outbox);
        runs.refusal = new StoreException.Busy("porelo-lib");

        assertThatThrownBy(() -> resaves.resave("ST1", "g1")).isSameAs(runs.refusal);
        assertThat(outbox.refusedUnwritten("g1", "ST1"))
                .as("kept while the run keeps the event under it")
                .isTrue();

        runs.refusal = null;
        assertThat(resaves.resave("ST1", "g1")).as("the board").isSameAs(BEFORE);
        assertThat(outbox.pending()).as("the group left the outbox").isEmpty();
    }

    @Test
    void aRunsGroupThatMayStandInPartStaysInTheOutbox() {
        // Harm: a group that may have written in part dropped unseen, so the owner account never
        // learns what of it stands.
        final CommandOutbox outbox = outbox();
        outbox.open(() -> BEFORE);
        outbox.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building")), "ST1"));
        outbox.begin("c1");
        outbox.refused("c1", new StoreException.Unreachable("porelo-lib", "timed out"), true, null);
        final Resaves runs = new Resaves();
        runs.kept = false;
        runs.refusal = WorkRefusal.runChanged("ST1", "No event is kept.");

        assertThatThrownBy(() -> new OutboxResave(runs, outbox).resave("ST1", "g1"))
                .isInstanceOf(WorkRefusal.class);
        assertThat(outbox.pending()).extracting(CommandOutbox.Group::id).containsExactly("g1");
    }
}
