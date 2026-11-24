package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.fuin.porelo.planner.app.CommandOutbox.State;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.Written;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Command groups run from the outbox without a project lock: one command per item at a time,
 * several items at once, each begun and ended on disk around its write.
 */
class CommandRunnerTest {

    @TempDir Path directory;

    private static Issue issue(final String id, final String state) {
        return new Issue(
                id,
                "porelo-lib",
                "Title of " + id,
                state,
                null,
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

    private static Board board(final Issue... issues) {
        return new Board(
                new Revision("r1"),
                new Workflow(List.of("Ready", "Building", "Review", "Done"), List.of(), List.of()),
                List.of(),
                List.of(issues),
                new Work(Map.of(), List.of()));
    }

    private static final Board BEFORE =
            board(issue("AA1", "Ready"), issue("AB2", "Ready"), issue("AC3", "Ready"));

    private static Command.Write move(final String id, final String issue, final String state) {
        return new Command.Write(
                id,
                issue,
                new Writes(Map.of(issue, state), Map.of()),
                Basis.of(BEFORE),
                Recheck.none());
    }

    /** A store that records its calls and answers each write as told. */
    private static final class Fake implements PlanningStore {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile Board board = BEFORE;
        volatile Function<Command, Written> onWrite = command -> new Written.Done(null, null);
        volatile @Nullable RuntimeException judgeRefusal;
        volatile boolean readFails;
        volatile Runnable onJudge = () -> {};

        @Override
        public Board board() {
            return board;
        }

        @Override
        public Board refresh() {
            return board;
        }

        @Override
        public void judge(final CommandGroup group) {
            calls.add("judge " + group.id());
            onJudge.run();
            final RuntimeException refusal = judgeRefusal;
            if (refusal != null) {
                throw refusal;
            }
        }

        @Override
        public Written writeAlone(final Command command) {
            calls.add("write " + command.id());
            return onWrite.apply(command);
        }

        @Override
        public Board readAfter(final Set<String> written) {
            calls.add("readAfter");
            if (readFails) {
                throw new StoreException.Unreachable("porelo-lib", "timed out");
            }
            return board;
        }
    }

    private CommandOutbox outbox() {
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final CommandOutbox outbox =
                new CommandOutbox(
                        environment,
                        directory,
                        "planning",
                        "porelo-lib",
                        "main",
                        PrivateFiles.of(environment),
                        LimitFactors.DEFAULT);
        outbox.open(() -> BEFORE);
        return outbox;
    }

    private static State state(final CommandOutbox outbox, final String group, final String id) {
        return outbox.told(group).stream()
                .filter(told -> told.id().equals(id))
                .findFirst()
                .orElseThrow()
                .state();
    }

    @Test
    void twoItemsAreInsideTheirWritesAtOnce() throws Exception {
        // Harm: one save waiting for another's round trip to GitHub.
        final Fake store = new Fake();
        final CyclicBarrier both = new CyclicBarrier(2);
        store.onWrite =
                command -> {
                    try {
                        both.await(5, TimeUnit.SECONDS);
                    } catch (final Exception e) {
                        throw new IllegalStateException("the other write never came in", e);
                    }
                    return new Written.Done(null, null);
                };
        final CommandRunner runner = new CommandRunner(outbox(), store);

        final CompletableFuture<CommandOutcome> one =
                CompletableFuture.supplyAsync(
                        () ->
                                runner.submit(
                                        new CommandGroup(
                                                "g1", List.of(move("c1", "AA1", "Building")))));
        final CompletableFuture<CommandOutcome> two =
                CompletableFuture.supplyAsync(
                        () ->
                                runner.submit(
                                        new CommandGroup(
                                                "g2", List.of(move("c2", "AB2", "Building")))));

        assertThat(one.get(10, TimeUnit.SECONDS)).isInstanceOf(CommandOutcome.Done.class);
        assertThat(two.get(10, TimeUnit.SECONDS)).isInstanceOf(CommandOutcome.Done.class);
    }

    @Test
    void oneItemsCommandsRunOneAfterAnother() throws Exception {
        // Harm: two writes of one item racing, so one overwrites the other unchecked.
        final Fake store = new Fake();
        final CountDownLatch firstInside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean secondInside = new AtomicBoolean();
        store.onWrite =
                command -> {
                    if (command.id().equals("c1")) {
                        firstInside.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    } else {
                        secondInside.set(true);
                    }
                    return new Written.Done(null, null);
                };
        final CommandRunner runner = new CommandRunner(outbox(), store);
        final CompletableFuture<CommandOutcome> one =
                CompletableFuture.supplyAsync(
                        () ->
                                runner.submit(
                                        new CommandGroup(
                                                "g1", List.of(move("c1", "AA1", "Building")))));
        assertThat(firstInside.await(10, TimeUnit.SECONDS))
                .as("the first write is inside")
                .isTrue();

        final CompletableFuture<CommandOutcome> two =
                CompletableFuture.supplyAsync(
                        () ->
                                runner.submit(
                                        new CommandGroup(
                                                "g2", List.of(move("c2", "AA1", "Review")))));
        // The second either waits for the item's claim or, wrongly, reaches the store.
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!runner.waiting("AA1") && !secondInside.get() && System.nanoTime() < end) {
            Thread.onSpinWait();
        }

        assertThat(secondInside).as("no second write while the first is inside").isFalse();
        assertThat(runner.waiting("AA1")).as("the second waits for the claim").isTrue();
        release.countDown();
        assertThat(one.get(10, TimeUnit.SECONDS)).isInstanceOf(CommandOutcome.Done.class);
        assertThat(two.get(10, TimeUnit.SECONDS)).isInstanceOf(CommandOutcome.Done.class);
        assertThat(store.calls).containsSubsequence("write c1", "write c2");
        assertThat(runner.claimed()).as("no claim left").isZero();
    }

    @Test
    void theOutboxSaysBegunWhileTheStoreWrites() {
        // Harm: a crash during the call to GitHub leaving no trace that the command may stand.
        final Fake store = new Fake();
        final List<String> seen = new ArrayList<>();
        store.onWrite =
                command -> {
                    try {
                        seen.add(Files.readString(directory.resolve(CommandOutbox.FILE)));
                    } catch (final java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return new Written.Done(null, null);
                };
        final CommandRunner runner = new CommandRunner(outbox(), store);

        runner.submit(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));

        assertThat(seen).singleElement().asString().contains("\"state\":\"IN_FLIGHT\"");
    }

    @Test
    void aFailureAfterWritingIsHeldUnlessTheBoardShowsIt() {
        // Harm: a command that may stand in part sent again, or a written one held without need.
        final Fake store = new Fake();
        store.onWrite =
                command -> {
                    if (command.id().equals("c1")) {
                        store.board = board(issue("AA1", "Building"), issue("AB2", "Ready"));
                    }
                    throw new IllegalStateException("GitHub answered 502");
                };
        final CommandOutbox outbox = outbox();
        final CommandRunner runner = new CommandRunner(outbox, store);

        final CommandOutcome outcome =
                runner.submit(
                        new CommandGroup(
                                "g1",
                                List.of(
                                        move("c1", "AA1", "Building"),
                                        move("c2", "AB2", "Building"))));

        assertThat(state(outbox, "g1", "c1")).as("the board shows it").isEqualTo(State.DONE);
        assertThat(state(outbox, "g1", "c2")).as("it may stand").isEqualTo(State.HELD);
        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Refused.class,
                        refused -> assertThat(refused.mayHaveWritten()).isTrue());
    }

    @Test
    void anErrorDuringTheWriteLeavesTheCommandBegunForTheNextOpen() {
        // Harm: a command the planner cannot tell about settled as refused, though it may stand.
        final Fake store = new Fake();
        store.onWrite =
                command -> {
                    throw new StackOverflowError();
                };
        final CommandOutbox outbox = outbox();
        final CommandRunner runner = new CommandRunner(outbox, store);

        final CommandOutcome outcome =
                runner.submit(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));

        assertThat(state(outbox, "g1", "c1")).isEqualTo(State.IN_FLIGHT);
        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Refused.class,
                        refused -> assertThat(refused.mayHaveWritten()).isTrue());
    }

    @Test
    void aConflictRefusesForTheOwnerWithItsPlacesAndHoldsTheRest() {
        // Harm: commands after a conflict written although their group stopped.
        final Fake store = new Fake();
        store.onWrite =
                command ->
                        command.id().equals("c2")
                                ? new Written.Conflict(List.of("STATE"))
                                : new Written.Done(null, null);
        final CommandOutbox outbox = outbox();
        final CommandRunner runner = new CommandRunner(outbox, store);
        final Command first = move("c1", "AA1", "Building");
        final Command second = move("c2", "AB2", "Building");
        final Command third = move("c3", "AC3", "Building");

        final CommandOutcome outcome =
                runner.submit(new CommandGroup("g1", List.of(first, second, third)));

        assertThat(store.calls).doesNotContain("write c3");
        assertThat(state(outbox, "g1", "c2")).isEqualTo(State.REFUSED);
        assertThat(outbox.told("g1").get(1).places()).containsExactly("STATE");
        assertThat(state(outbox, "g1", "c3")).isEqualTo(State.HELD);
        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Refused.class,
                        refused -> {
                            assertThat(refused.refused()).isEqualTo(second);
                            assertThat(refused.done()).containsExactly(first);
                            assertThat(refused.unsent()).containsExactly(third);
                            assertThat(refused.cause()).isInstanceOf(StoreException.Changed.class);
                        });
        assertThatThrownBy(() -> CommandGroups.done(outcome, store))
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> {
                            assertThat(partly.issues())
                                    .as("the issues that stand")
                                    .containsExactly("AA1=Building");
                            assertThat(partly.pushed()).containsExactly("porelo-lib");
                        });
    }

    @Test
    void aGroupIsJudgedOnceBeforeAndReadOnceAfter() {
        // Harm: a move of N issues reading N times what one save reads today.
        final Fake store = new Fake();
        final CommandRunner runner = new CommandRunner(outbox(), store);

        runner.submit(
                new CommandGroup(
                        "g1",
                        List.of(
                                move("c1", "AA1", "Building"),
                                move("c2", "AB2", "Building"),
                                move("c3", "AC3", "Building"))));

        assertThat(store.calls)
                .containsExactly("judge g1", "write c1", "write c2", "write c3", "readAfter");
    }

    @Test
    void aGroupRefusedAtItsJudgmentWritesNothingAndStaysNowhere() {
        // Harm: a save refused before any write kept for the owner account as if part stood, or
        // written although its read set moved.
        final Fake store = new Fake();
        store.judgeRefusal = new StoreException.Changed(List.of("AB2"));
        final CommandOutbox outbox = outbox();
        final CommandRunner runner = new CommandRunner(outbox, store);

        final CommandOutcome outcome =
                runner.submit(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));

        assertThat(store.calls).containsExactly("judge g1");
        assertThat(outbox.pending()).isEmpty();
        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Refused.class,
                        refused -> assertThat(refused.mayHaveWritten()).isFalse());
    }

    @Test
    void aBusyStoreIsTriedAgainThenTheGroupIsHeldForTheOwnerAccountNeverRefused() {
        // Harm: a save refused for good because another held the project a moment, or one told it
        // waits that nothing ever runs again until a restart, or a part written told as nothing.
        final Fake store = new Fake();
        store.onWrite =
                command -> {
                    if (command.id().equals("c2")) {
                        throw new StoreException.Busy("porelo-lib");
                    }
                    return new Written.Done(null, null);
                };
        final CommandOutbox outbox = outbox();
        final CommandRunner runner = new CommandRunner(outbox, store);
        final Command first = move("c1", "AA1", "Building");
        final Command second = move("c2", "AB2", "Building");

        final CommandOutcome outcome =
                runner.submit(new CommandGroup("g1", List.of(first, second)));

        assertThat(store.calls.stream().filter(call -> call.equals("write c2")).count())
                .isEqualTo(CommandRunner.BUSY_TRIES);
        assertThat(state(outbox, "g1", "c2"))
                .as("held for the owner account")
                .isEqualTo(State.HELD);
        assertThat(outbox.waiting()).as("never run again unasked").isEmpty();
        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Held.class,
                        held -> {
                            assertThat(held.done()).containsExactly(first);
                            assertThat(held.unsent()).containsExactly(second);
                        });
        assertThatThrownBy(() -> CommandGroups.done(outcome, store))
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> assertThat(partly.issues()).containsExactly("AA1=Building"));

        store.onWrite = command -> new Written.Done(null, null);
        assertThat(runner.resend("g1")).isInstanceOf(CommandOutcome.Done.class);
    }

    @Test
    void aFailedReadAfterStillTellsTheGroupDone() {
        // Harm: a group written whole told as failed, so the person makes it again.
        final Fake store = new Fake();
        store.readFails = true;
        final CommandRunner runner = new CommandRunner(outbox(), store);

        final CommandOutcome outcome =
                runner.submit(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Done.class, done -> assertThat(done.board()).isNull());
    }

    @Test
    void aHeldGroupIsSentAgainOnlyWhenTheOwnerAccountSaysSo() {
        // Harm: a held save lost, or sent again with nobody asking.
        final Fake store = new Fake();
        store.onWrite = command -> new Written.Conflict(List.of("STATE"));
        final CommandOutbox outbox = outbox();
        final CommandRunner runner = new CommandRunner(outbox, store);
        runner.submit(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        runner.resume();
        assertThat(store.calls.stream().filter(call -> call.equals("write c1")).count())
                .as("never resumed on its own")
                .isEqualTo(1);

        store.onWrite = command -> new Written.Done(null, null);
        final CommandOutcome outcome = runner.resend("g1");

        assertThat(outcome).isInstanceOf(CommandOutcome.Done.class);
        assertThat(outbox.pending()).isEmpty();
    }

    @Test
    void aResentGroupRunsOnOneThreadOnlyThoughResumeOrAnotherResendComesMeanwhile()
            throws Exception {
        // Harm: a group run by two threads at once, so a command such as a comment or a create is
        // written twice, or the trailing run holds a command the leading one has at the store.
        final Fake store = new Fake();
        store.onWrite = command -> new Written.Conflict(List.of("STATE"));
        final CommandOutbox outbox = outbox();
        final CommandRunner runner = new CommandRunner(outbox, store);
        runner.submit(
                new CommandGroup(
                        "g1",
                        List.of(move("c1", "AA1", "Building"), move("c2", "AB2", "Building"))));
        store.onWrite = command -> new Written.Done(null, null);
        store.calls.clear();
        final CountDownLatch judging = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        store.onJudge =
                () -> {
                    if (first.getAndSet(false)) {
                        judging.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
        final CompletableFuture<CommandOutcome> resent =
                CompletableFuture.supplyAsync(() -> runner.resend("g1"));
        assertThat(judging.await(5, TimeUnit.SECONDS)).as("queued again, being judged").isTrue();

        runner.resume();
        assertThatThrownBy(() -> runner.resend("g1"))
                .as("a second resend leaves the running group alone")
                .isInstanceOf(IllegalStateException.class);
        release.countDown();

        assertThat(resent.get(10, TimeUnit.SECONDS)).isInstanceOf(CommandOutcome.Done.class);
        assertThat(store.calls)
                .as("judged and written once, by the resend alone")
                .containsExactly("judge g1", "write c1", "write c2", "readAfter");
        assertThat(outbox.pending()).as("done whole").isEmpty();
    }

    @Test
    void groupsLeftWaitingAtOpenAreRunByResume() {
        // Harm: a save queued before a restart never sent.
        final Fake store = new Fake();
        final CommandOutbox first = outbox();
        first.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        first.close();
        final CommandOutbox again = outbox();
        final CommandRunner runner = new CommandRunner(again, store);

        runner.resume();

        assertThat(store.calls).containsExactly("judge g1", "write c1", "readAfter");
        assertThat(again.pending()).isEmpty();
    }

    @Test
    void drainingWaitsForTheWriteAtTheStoreSoTheNextOpenFindsItDone() throws Exception {
        // Harm: the planner stopping while a write is at GitHub, so the next open finds the
        // command begun and queues it again, though it was written.
        final Fake store = new Fake();
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        store.onWrite =
                command -> {
                    inside.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new Written.Done(null, null);
                };
        final CommandRunner runner = new CommandRunner(outbox(), store);
        final CompletableFuture<CommandOutcome> save =
                CompletableFuture.supplyAsync(
                        () ->
                                runner.submit(
                                        new CommandGroup(
                                                "g1", List.of(move("c1", "AA1", "Building")))));
        assertThat(inside.await(5, TimeUnit.SECONDS)).as("the write is at the store").isTrue();

        final CompletableFuture<Boolean> drained =
                CompletableFuture.supplyAsync(() -> runner.drain(Duration.ofSeconds(10)));
        assertThatThrownBy(() -> drained.get(300, TimeUnit.MILLISECONDS))
                .as("drain waits for the write")
                .isInstanceOf(TimeoutException.class);
        assertThatThrownBy(
                        () ->
                                runner.submit(
                                        new CommandGroup(
                                                "g2", List.of(move("c2", "AB2", "Building")))))
                .as("nothing new is taken, and the caller is told to wait")
                .isInstanceOf(StoreException.Busy.class);
        release.countDown();

        assertThat(drained.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(save.get(10, TimeUnit.SECONDS)).isInstanceOf(CommandOutcome.Done.class);
        assertThat(outbox().pending()).as("done in the file, nothing to queue again").isEmpty();
    }

    private CommandOutbox brokenWhen(final AtomicBoolean broken) {
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final PrivateFiles files = PrivateFiles.of(environment);
        final DurableFile durable =
                new DurableFile(
                        files,
                        (written, target) -> {
                            if (broken.get()) {
                                throw new java.io.IOException("the disk is full");
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
                        environment, directory, "planning", "porelo-lib", "main", files, durable);
        outbox.open(() -> BEFORE);
        return outbox;
    }

    @Test
    void aCommandTheOutboxCannotMarkBegunWaitsAndIsNeverSent() {
        // Harm: a write sent with no trace on disk, or a save lost because its mark failed.
        final AtomicBoolean broken = new AtomicBoolean();
        final CommandOutbox outbox = brokenWhen(broken);
        final Fake store = new Fake();
        store.onJudge = () -> broken.set(true);
        final CommandRunner runner = new CommandRunner(outbox, store);

        final CommandOutcome outcome =
                runner.submit(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));

        assertThat(outcome).isInstanceOf(CommandOutcome.Held.class);
        assertThat(store.calls).doesNotContain("write c1");
        assertThat(state(outbox, "g1", "c1")).isEqualTo(State.QUEUED);
        assertThat(runner.claimed()).as("its claim released").isZero();
    }

    @Test
    void aWriteThatStandsIsNeverToldFailedWhenTheOutboxCannotMarkItDone() {
        // Harm: a save that stands told as failed, so the person makes it again.
        final AtomicBoolean broken = new AtomicBoolean();
        final CommandOutbox outbox = brokenWhen(broken);
        final Fake store = new Fake();
        store.onWrite =
                command -> {
                    broken.set(true);
                    return new Written.Done(null, null);
                };
        final CommandRunner runner = new CommandRunner(outbox, store);

        final CommandOutcome outcome =
                runner.submit(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));

        assertThat(outcome).isInstanceOf(CommandOutcome.Done.class);
    }

    @Test
    void aRefusedSaveTheOutboxCannotDropIsToldItMayStillBeSent() {
        // Harm: a save told refused that the file keeps, so a restart sends it unseen.
        final AtomicBoolean broken = new AtomicBoolean();
        final CommandOutbox outbox = brokenWhen(broken);
        final Fake store = new Fake();
        store.onJudge = () -> broken.set(true);
        store.judgeRefusal = new StoreException.Changed(List.of("AA1"));
        final CommandRunner runner = new CommandRunner(outbox, store);

        final CommandOutcome outcome =
                runner.submit(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));

        assertThat(outcome).as("may still be sent").isInstanceOf(CommandOutcome.Held.class);
    }

    @Test
    void aRecoveredGroupRefusedAtItsJudgmentIsKeptForTheOwnerAccount() {
        // Harm: a save the window was told waits, dropped unseen at a restart's judgment.
        final CommandOutbox first = outbox();
        first.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        first.close();
        final CommandOutbox again = outbox();
        final Fake store = new Fake();
        store.judgeRefusal = new StoreException.Changed(List.of("AA1"));
        final CommandRunner runner = new CommandRunner(again, store);

        runner.resume();

        assertThat(state(again, "g1", "c1")).isEqualTo(State.REFUSED);
        assertThat(store.calls).doesNotContain("write c1");
    }

    @Test
    void oneRecoveredGroupThatFailsNeverStopsTheOthers() {
        // Harm: one broken group leaving every save after it unsent until the next restart.
        final CommandOutbox first = outbox();
        for (final String n : List.of("1", "2", "3")) {
            first.enqueue(new CommandGroup("g" + n, List.of(move("c" + n, "AA" + n, "Building"))));
        }
        first.close();
        final CommandOutbox again = outbox();
        final Fake store = new Fake();
        // The owner account drops g2 between the list of waiting groups and its run.
        store.onJudge =
                () -> {
                    if (store.calls.size() == 1) {
                        again.unqueue("g2");
                    }
                };
        final CommandRunner runner = new CommandRunner(again, store);

        runner.resume();

        assertThat(store.calls).contains("judge g1", "judge g3");
    }

    @Test
    void aResumedCommandTheBoardShowsIsDoneThoughItsCallWasRefused() {
        // Harm: a command that stood before the restart held for the owner account as unknown.
        final CommandOutbox first = outbox();
        first.enqueue(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
        first.begin("c1");
        first.close();
        final CommandOutbox again = outbox();
        final Fake store = new Fake();
        store.onWrite =
                command -> {
                    store.board = board(issue("AA1", "Building"), issue("AB2", "Ready"));
                    throw new StoreException.Changed(List.of("AA1"));
                };
        final CommandRunner runner = new CommandRunner(again, store);

        runner.resume();

        assertThat(again.pending()).as("done, nothing kept").isEmpty();
    }
}
