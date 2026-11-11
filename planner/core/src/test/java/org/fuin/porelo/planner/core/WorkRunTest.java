package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WorkRunTest {

    @Test
    void aWriteOfItsOwnRunsUnderTheRecordsLock() {
        // A write beside the record's would move the board under a run's save, which then halts.
        final Fixture f = new Fixture();

        assertThat(f.run.alone(() -> f.record.held))
                .as("the write runs while the record is held")
                .isTrue();
    }

    @Test
    void aStartedTaskKeepsItsMachineAndAnEndCannotRebindIt() {
        // Prevents a later report from directing a bound task to another machine.
        final Fixture f = new Fixture();
        final var offer = f.order().tasks().getFirst();
        assertThat(f.record.value.runs().values().iterator().next().offers().getFirst().machine())
                .as("an offer has no machine before a report binds it")
                .isNull();
        final String machine = "WSL Ubuntu Äpfel";
        final var started =
                f.run.report(
                        "task",
                        new WorkRun.Report(
                                offer.offer(), WorkRun.Event.STARTED, "box", machine, null));
        assertThat(started.work().offers().getFirst().machine())
                .as("STARTED binds the person's machine name verbatim")
                .isEqualTo(machine);
        final var snapshot = f.record.value;
        final var board = f.store.current;
        final int saves = f.store.saves;
        assertThatThrownBy(
                        () ->
                                f.run.report(
                                        "task",
                                        new WorkRun.Report(
                                                offer.offer(),
                                                WorkRun.Event.ENDED,
                                                "box",
                                                "Other machine",
                                                ended("Built"))))
                .as("another machine contradicts the saved task binding")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refusal ->
                                assertThat(((WorkRefusal) refusal).name())
                                        .as("a machine contradiction is an event conflict")
                                        .isEqualTo("TaskEventConflict"));
        assertThat(f.record.value)
                .as("the refused end changes no work generation or offers")
                .isEqualTo(snapshot);
        assertThat(f.store.current)
                .as("the refused end changes no planning state")
                .isEqualTo(board);
        assertThat(f.store.saves).as("the refused end writes nothing").isEqualTo(saves);
    }

    @Test
    void aTaskFromAnOlderRecordLearnsItsMachineWhenItEnds() {
        // Prevents unknown legacy machines from stranding work or allowing rebinding.
        final Fixture f = new Fixture();
        final var offer = f.order().tasks().getFirst();
        final var started =
                f.run.report(
                        "task",
                        new WorkRun.Report(
                                offer.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        final var run = started.work();
        final var bound = run.offers().getFirst();
        final var legacyOffer =
                new WorkRun.Offer(
                        bound.id(),
                        bound.arguments(),
                        bound.binding(),
                        bound.status(),
                        bound.task(),
                        bound.containerId(),
                        null,
                        bound.since(),
                        bound.ended(),
                        bound.fingerprint(),
                        bound.following(),
                        bound.deferred(),
                        bound.refusal());
        final var legacyRun =
                new WorkRun.Run(
                        run.id(),
                        run.story(),
                        run.to(),
                        run.mode(),
                        run.comment(),
                        run.coordinator(),
                        run.readingRounds(),
                        run.issueReadingRounds(),
                        run.repositories(),
                        run.since(),
                        List.of(legacyOffer),
                        run.issues(),
                        run.stopped(),
                        run.halted(),
                        run.pending());
        f.record.value =
                new WorkRun.Snapshot(f.record.value.generation(), Map.of(run.story(), legacyRun));
        final var ended =
                f.run.report(
                        "task",
                        new WorkRun.Report(
                                offer.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "WSL Ubuntu Äpfel",
                                ended("Built")));
        assertThat(ended.work().offers().getFirst().machine())
                .as("the first accepted terminal report names an unknown legacy machine")
                .isEqualTo("WSL Ubuntu Äpfel");
        final var snapshot = f.record.value;
        assertThatThrownBy(
                        () ->
                                f.run.report(
                                        "task",
                                        new WorkRun.Report(
                                                offer.offer(),
                                                WorkRun.Event.ENDED,
                                                "box",
                                                "Other machine",
                                                ended("Built"))))
                .as("a replay cannot replace the newly recorded machine")
                .isInstanceOf(WorkRefusal.class);
        assertThat(f.record.value)
                .as("a contradictory replay preserves the complete record")
                .isEqualTo(snapshot);
    }

    @Test
    void reportsCannotForgeScopeOrRebindATask() {
        // Prevents a window report from changing another story or claiming another task's offer.
        final Fixture f = new Fixture();
        final WorkRun.Offered offer = f.order().tasks().getFirst();
        assertThatThrownBy(
                        () ->
                                f.run.report(
                                        "wrong",
                                        new WorkRun.Report(
                                                "00000000-0000-0000-0000-000000000000",
                                                WorkRun.Event.STARTED,
                                                "box",
                                                "workstation",
                                                null)))
                .as("an offer never given cannot write")
                .isInstanceOf(WorkRefusal.class);
        f.start(offer);
        assertThatThrownBy(
                        () ->
                                f.run.report(
                                        "other",
                                        new WorkRun.Report(
                                                offer.offer(),
                                                WorkRun.Event.ENDED,
                                                "box",
                                                "workstation",
                                                ended(""))))
                .as("the first binding wins")
                .isInstanceOf(WorkRefusal.class);
        assertThat(f.store.saves).as("forged reports never save").isEqualTo(1);
    }

    @Test
    void anIssueCannotBeWorkedByTwoStoryRuns() {
        // Prevents simultaneous story runs from writing the same issue twice.
        final Fixture f = new Fixture();
        final Board board = f.store.current;
        f.store.current =
                new Board(
                        board.revision(),
                        board.workflow(),
                        List.of(
                                board.stories().getFirst(),
                                new Story(
                                        "ST2",
                                        "Other",
                                        "Backlog",
                                        false,
                                        "High",
                                        List.of("AA1"),
                                        "",
                                        null)),
                        board.issues(),
                        board.work(),
                        board.repositories());
        final var first = f.order().tasks().getFirst();
        final var second =
                f.run.order(
                                "ST2",
                                "Building",
                                new WorkOrder.Decision(
                                        "AGENT", board.revision().value(), null, "", 1, null))
                        .tasks()
                        .getFirst();
        f.start(first);
        assertThatThrownBy(
                        () ->
                                f.run.report(
                                        "second",
                                        new WorkRun.Report(
                                                second.offer(),
                                                WorkRun.Event.STARTED,
                                                "box",
                                                "workstation",
                                                null)))
                .as("an active issue already has an owner")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        e ->
                                assertThat(((WorkRefusal) e).name())
                                        .as("the existing run is identified")
                                        .isEqualTo("WorkRunning"));
        assertThat(f.run.active("ST2")).as("the conflicting report changes no binding").isEmpty();
    }

    @Test
    void repeatedEndsAfterRestartKeepTheSameOfferAndCountOnlyOnce() {
        // Prevents retries and restarts from duplicating readers or reading counts.
        final Fixture f = new Fixture();
        final WorkRun.Offered builder = f.order().tasks().getFirst();
        f.start(builder);
        final WorkRun.Report end =
                new WorkRun.Report(
                        builder.offer(), WorkRun.Event.ENDED, "box", "workstation", ended("Built"));
        final WorkRun.Answer first = f.run.report("task", end);
        final WorkRun restarted = new WorkRun(f.store, f.record, f.clock);
        assertThat(restarted.report("task", end).tasks())
                .as("the same accepted end replays the same offers")
                .isEqualTo(first.tasks())
                .hasSize(1);
        final WorkRun.Offered reader = first.tasks().getFirst();
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        final WorkRun.Report read =
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings:\n"));
        f.run.report("reader", read);
        restarted.report("reader", read);
        assertThat(restarted.work("ST1").issues().get("AA1").rounds().rounds())
                .as("one successful reading counts once")
                .isEqualTo(1);
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the clean reading reaches review")
                .isEqualTo("Review");
    }

    @Test
    void aRepeatedStartReplaysTheParentWorkItReleased() {
        // Prevents a lost STARTED response from stranding a deferred parent's follow-up task.
        final Fixture f = new Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        for (final WorkRun.Offered reader : f.readers()) {
            f.run.report(
                    "reader-" + reader.task().issues().getFirst(),
                    new WorkRun.Report(
                            reader.offer(),
                            WorkRun.Event.ENDED,
                            "box",
                            "workstation",
                            ended("Findings:\n1. blocking | - | - | Preserve data")));
        }
        f.run.choose(
                "ST1",
                "AA1",
                1,
                ReadingCycle.Choice.REWORK,
                "Rework the parent",
                f.store.board().revision().value());
        final var child =
                f.run.choose(
                                "ST1",
                                "AA1.1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "Rework the child",
                                f.store.board().revision().value())
                        .tasks()
                        .getFirst();
        final var start =
                new WorkRun.Report(
                        child.offer(), WorkRun.Event.STARTED, "box", "workstation", null);
        final var first = f.run.report("child-rework", start);
        assertThat(first.tasks())
                .as("starting the child's rework releases the parent's deferred rework")
                .extracting(o -> o.task().issues())
                .containsExactly(List.of("AA1"));
        final int saves = f.store.saves;
        final WorkRun restarted = new WorkRun(f.store, f.record, f.clock);
        assertThat(restarted.report("child-rework", start).tasks())
                .as("retrying STARTED recovers the same still-valid follow-up")
                .isEqualTo(first.tasks());
        assertThat(f.store.saves).as("a replay does not save again").isEqualTo(saves);
        f.run.report(
                "parent-rework",
                new WorkRun.Report(
                        first.tasks().getFirst().offer(),
                        WorkRun.Event.STARTED,
                        "box",
                        "workstation",
                        null));
        assertThat(restarted.report("child-rework", start).tasks())
                .as("a follow-up that has already started is no longer offered")
                .isEmpty();
    }

    @Test
    void aChildsReadingChoiceResumesItsDeferredParent() {
        // Prevents a parent's deferred rework from remaining in Reading after its child settles.
        final Fixture f = new Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        for (final WorkRun.Offered reader : f.readers()) {
            f.run.report(
                    "reader-" + reader.task().issues().getFirst(),
                    new WorkRun.Report(
                            reader.offer(),
                            WorkRun.Event.ENDED,
                            "box",
                            "workstation",
                            ended("Findings:\n1. blocking | - | - | Preserve data")));
        }
        final var waiting =
                f.run.choose(
                        "ST1",
                        "AA1",
                        1,
                        ReadingCycle.Choice.REWORK,
                        "Preserve bytes",
                        f.store.board().revision().value());
        assertThat(waiting.tasks()).as("the stopped child holds its parent's rework").isEmpty();
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the deferred parent still waits in Reading")
                .isEqualTo("Reading");
        final var chosen =
                f.run.choose(
                        "ST1",
                        "AA1.1",
                        1,
                        ReadingCycle.Choice.REVIEW_ANYWAY,
                        "",
                        f.store.board().revision().value());
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("settling the child resumes the parent's recorded rework choice")
                .isEqualTo("Reworking");
        assertThat(chosen.tasks())
                .as("the child's choice returns the parent's follow-up task")
                .extracting(o -> o.task().issues())
                .containsExactly(List.of("AA1"));
        assertThat(chosen.tasks().getFirst().task().prompt())
                .as("the resumed task keeps the parent's operator words")
                .contains("Preserve bytes");
        assertThat(
                        java.util.Objects.requireNonNull(chosen.work().issues().get("AA1"))
                                .rounds()
                                .rounds())
                .as("resuming the parent does not count its reading twice")
                .isEqualTo(1);
    }

    @Test
    void aStoryMoveCannotChangeIssuesBoundByAnotherStorysRun() {
        // Prevents moving a shared issue or its child out from under another story's active task.
        for (final String role : List.of("MAIN", "BUILDER")) {
            final Fixture f = new Fixture();
            final Board board = NextStepTest.board("Backlog", "Backlog");
            f.store.current =
                    new Board(
                            board.revision(),
                            board.workflow(),
                            List.of(
                                    board.stories().getFirst(),
                                    new Story(
                                            "ST2",
                                            "Other",
                                            "Backlog",
                                            false,
                                            "High",
                                            List.of("AA1"),
                                            "",
                                            null)),
                            board.issues(),
                            board.work(),
                            board.repositories());
            final var offer =
                    f.order().tasks().stream()
                            .filter(o -> o.task().role().equals(role))
                            .findFirst()
                            .orElseThrow();
            f.start(offer);
            final var saved = new java.util.concurrent.atomic.AtomicBoolean();
            assertThatThrownBy(
                            () ->
                                    f.run.moves(
                                            List.of(new Moves.Move(true, "ST2", "Review")),
                                            () -> {
                                                saved.set(true);
                                                return f.store.board();
                                            }))
                    .as("a story move respects the %s task's bound issues", role)
                    .isInstanceOf(WorkRefusal.class)
                    .satisfies(
                            e ->
                                    assertThat(((WorkRefusal) e).name())
                                            .as("the active owning run refuses the move")
                                            .isEqualTo("WorkRunning"));
            assertThat(saved).as("a refused move never reaches the save").isFalse();
        }
    }

    @Test
    void aWorkerChangeOfAnIssueARunningTaskOwnsIsRefused() {
        // A worker changed under a running task would hand a person an issue an agent still works.
        final Fixture f = new Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        f.start(f.order().tasks().getFirst());
        final var saved = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(
                        () ->
                                f.run.worker(
                                        "AA1",
                                        () -> {
                                            saved.set(true);
                                            return f.store.board();
                                        }))
                .as("a running task owns AA1, so its worker stays")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        e ->
                                assertThat(((WorkRefusal) e).name())
                                        .as("the owning run refuses the change")
                                        .isEqualTo("WorkRunning"));
        assertThat(saved).as("a refused change never reaches the save").isFalse();
    }

    @Test
    void aPersonsIssueInAWorkingStateWithoutARunDoesNotHoldBackTheStorysWork() {
        // A person's issue in Implementing would otherwise read as lost work and block the agents.
        final Fixture f = new Fixture();
        f.store.current = ManualIssues.of(withSecondIssue(f.store.board(), "Building"), "BB1");

        final WorkProposal proposal = f.run.propose("ST1", "Building");

        assertThat(proposal.repositories())
                .as("the agent's issue gets its task")
                .flatExtracting(WorkProposal.Repository::issues)
                .containsExactly("AA1");
        assertThat(proposal.manual()).as("the person's issue is named").containsExactly("BB1");
    }

    @Test
    void aStoppedReadingOfAnIssueAPersonNowWorksNeitherHoldsTheRunNorStartsAnAgent() {
        // A stop that only an agent's choice clears would hang the story's other issues for good.
        final Fixture f = new Fixture();
        f.store.current = withSecondIssue(f.store.board(), "Backlog");
        final WorkRun.Offered builder =
                f.order().tasks().stream()
                        .filter(o -> o.task().issues().equals(List.of("AA1")))
                        .findFirst()
                        .orElseThrow();
        f.start(builder);
        final var reader =
                f.run.report(
                                "task",
                                new WorkRun.Report(
                                        builder.offer(),
                                        WorkRun.Event.ENDED,
                                        "box",
                                        "workstation",
                                        ended("Built")))
                        .tasks()
                        .getFirst();
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings:\n1. blocking | - | - | Preserve data")));
        assertThat(f.run.work("ST1").issues().get("AA1").rounds().stopped())
                .as("the reading stopped at its limit")
                .isTrue();
        f.store.current = ManualIssues.of(f.store.board(), "AA1");
        final String revision = f.store.board().revision().value();

        assertThatThrownBy(
                        () ->
                                f.run.choose(
                                        "ST1",
                                        "AA1",
                                        1,
                                        ReadingCycle.Choice.ONE_MORE_ROUND,
                                        "",
                                        revision))
                .as("no reader is started for the person's issue")
                .isInstanceOf(WorkRefusal.class)
                .hasMessageContaining("AA1");
        assertThat(f.run.propose("ST1", "Building").repositories())
                .as("the story's agent issue can still be proposed")
                .flatExtracting(WorkProposal.Repository::issues)
                .containsExactly("BB1");
    }

    @Test
    void aStoryWhoseWorkingStateAPersonSetIsNotHeldThereByAPersonsIssue() {
        // A state a person set stands over the issues, so a working story state without a run
        // would otherwise pass as a person's work and start agents over lost work.
        final Fixture f = new Fixture();
        final Board held =
                ManualIssues.of(
                        withSecondIssue(NextStepTest.board("Reading", "Reading"), "Backlog"),
                        "AA1");
        f.store.current = held;
        assertThat(f.run.propose("ST1", "Reworking").repositories())
                .as("a story state its person's issue gives is held by that issue")
                .isNotEmpty();

        f.store.current = withStoryState(held, true);

        assertThatThrownBy(() -> f.run.propose("ST1", "Reworking"))
                .as("the stated working state without a run needs attention")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        e ->
                                assertThat(((WorkRefusal) e).name())
                                        .as("the proposal is not allowed")
                                        .isEqualTo("WorkNotAllowed"));
    }

    /** The board with its first story's state stated by its head, or given by its issues. */
    private static Board withStoryState(final Board board, final boolean stateGiven) {
        final Story story = board.stories().getFirst();
        return new Board(
                board.revision(),
                board.workflow(),
                List.of(
                        new Story(
                                story.id(),
                                story.title(),
                                story.state(),
                                stateGiven,
                                story.priority(),
                                story.issues(),
                                story.description(),
                                story.link())),
                board.issues(),
                board.work(),
                board.repositories());
    }

    /** The fixture's board with BB1, an agent's issue of another repository, in the story too. */
    private static Board withSecondIssue(final Board board, final String state) {
        final Story story = board.stories().getFirst();
        return new Board(
                board.revision(),
                board.workflow(),
                List.of(
                        new Story(
                                story.id(),
                                story.title(),
                                story.state(),
                                false,
                                story.priority(),
                                List.of("AA1", "BB1"),
                                story.description(),
                                story.link())),
                java.util.stream.Stream.concat(
                                board.issues().stream(),
                                java.util.stream.Stream.of(
                                        new Issue(
                                                "BB1", "beta", "Other", state, null, List.of(),
                                                null, null, "")))
                        .toList(),
                board.work(),
                List.of("alpha", "beta"));
    }

    @Test
    void aWorkerChangeOfAParentWhoseSubIssueARunningTaskOwnsIsRefused() {
        // A parent's worker is its sub-issues' worker, so the change would reach a running task.
        final Fixture f = new Fixture();
        final Board board = NextStepTest.board("Backlog", "Backlog");
        f.store.current =
                new Board(
                        board.revision(),
                        board.workflow(),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story",
                                        "Backlog",
                                        false,
                                        "High",
                                        List.of("AA1.1"),
                                        "",
                                        null)),
                        board.issues(),
                        board.work(),
                        board.repositories());
        final WorkRun.Order order = f.order();
        assertThat(order.tasks().stream().flatMap(t -> t.task().issues().stream()))
                .as("the story names only the sub-issue, so no task binds its parent")
                .doesNotContain("AA1")
                .contains("AA1.1");
        f.start(order.tasks().getFirst());
        final var saved = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(
                        () ->
                                f.run.worker(
                                        "AA1",
                                        () -> {
                                            saved.set(true);
                                            return f.store.board();
                                        }))
                .as("a running task owns AA1.1, which takes AA1's worker")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        e ->
                                assertThat(((WorkRefusal) e).name())
                                        .as("the run owning the sub-issue refuses the change")
                                        .isEqualTo("WorkRunning"));
        assertThat(saved).as("a refused change never reaches the save").isFalse();
    }

    @Test
    void aReadingChoiceChecksItsSaveAgainstABoardThatKeepsEveryIssuesWorker() {
        // A checked board that turns a person's issue into an agent's would plan work for it.
        final Fixture f = new Fixture();
        final Board board = f.store.current;
        final List<Issue> issues = new java.util.ArrayList<>(board.issues());
        issues.add(
                new Issue(
                        "AA2",
                        "alpha",
                        "By hand",
                        "Backlog",
                        null,
                        List.of(),
                        null,
                        null,
                        "",
                        Worker.MANUAL));
        f.store.current =
                new Board(
                        board.revision(),
                        board.workflow(),
                        board.stories(),
                        issues,
                        board.work(),
                        board.repositories());
        final WorkRun.Offered builder = f.order().tasks().getFirst();
        f.start(builder);
        final WorkRun.Offered reader =
                f.run.report(
                                "task",
                                new WorkRun.Report(
                                        builder.offer(),
                                        WorkRun.Event.ENDED,
                                        "box",
                                        "workstation",
                                        ended("Built")))
                        .tasks()
                        .getFirst();
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings:\n1. blocking | - | - | Preserve data")));
        f.run.choose(
                "ST1",
                "AA1",
                1,
                ReadingCycle.Choice.REWORK,
                "Rework it",
                f.store.board().revision().value());

        assertThat(f.record.checkedBoard)
                .as("the reading choice's save is checked against a board")
                .isNotNull();
        assertThat(f.record.checkedBoard.issue("AA2").orElseThrow().worker())
                .as("the checked board keeps the issue a person works")
                .isEqualTo(Worker.MANUAL);
    }

    @Test
    void aWorkerChangeOfAnIssueNoRunOwnsIsSavedUnderTheRecordsLock() {
        // A save beside the record's would move the board under a run's save, which then halts.
        final Fixture f = new Fixture();

        assertThat(f.run.worker("AA1", () -> f.record.held))
                .as("the save runs while the record is held")
                .isTrue();
    }

    @Test
    void aHaltedRunCanBeOrderedAgainOnlyAfterItIsStopped() {
        // Prevents save failures from permanently blocking work or resetting counted readings.
        final Fixture f = new Fixture();
        final var builder = f.order().tasks().getFirst();
        f.start(builder);
        final var reader =
                f.run.report(
                                "task",
                                new WorkRun.Report(
                                        builder.offer(),
                                        WorkRun.Event.ENDED,
                                        "box",
                                        "workstation",
                                        ended("Built")))
                        .tasks()
                        .getFirst();
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings:\n1. blocking | - | - | Preserve data")));
        f.store.failure = new StoreException.Busy("alpha");
        assertThatThrownBy(
                        () ->
                                f.run.choose(
                                        "ST1",
                                        "AA1",
                                        1,
                                        ReadingCycle.Choice.REVIEW_ANYWAY,
                                        "",
                                        f.store.board().revision().value()))
                .as("the refused choice save halts the run after a counted reading")
                .isInstanceOf(StoreException.Busy.class);
        f.store.failure = null;
        final var decision =
                new WorkOrder.Decision(
                        "AGENT", f.store.board().revision().value(), null, "", 1, null);
        assertThatThrownBy(() -> f.run.order("ST1", "Reworking", decision))
                .as("a halted run still needs an explicit stop before another order")
                .isInstanceOf(WorkRefusal.class);
        assertThat(java.util.Objects.requireNonNull(f.run.stop("ST1")).stopped())
                .as("stopping a halted run records the person's cancellation")
                .isTrue();
        assertThat(f.run.order("ST1", "Reworking", decision).tasks())
                .as("a stopped halted run accepts work again")
                .isNotEmpty();
        final var progress =
                java.util.Objects.requireNonNull(
                        java.util.Objects.requireNonNull(f.run.work("ST1")).issues().get("AA1"));
        assertThat(progress.rounds().rounds())
                .as("the completed reading remains counted")
                .isEqualTo(1);
        assertThat(progress.rounds().roundsMost())
                .as("the existing allowance is retained")
                .isEqualTo(1);
    }

    @Test
    void blockingFindingsLeadThroughReworkAndAnotherReadingToReview() {
        // Prevents Review before rework and the next reading finish.
        final Fixture f = new Fixture();
        final var build =
                f.run.order(
                                "ST1",
                                "Building",
                                new WorkOrder.Decision("AGENT", "board@1", null, "", 2, null))
                        .tasks()
                        .getFirst();
        f.start(build);
        final var reader =
                f.run.report(
                                "task",
                                new WorkRun.Report(
                                        build.offer(),
                                        WorkRun.Event.ENDED,
                                        "box",
                                        "workstation",
                                        ended("Built")))
                        .tasks()
                        .getFirst();
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        final var blocked =
                f.run.report(
                        "reader",
                        new WorkRun.Report(
                                reader.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Findings:\n1. blocking | - | - | Preserve data")));
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("blocking findings use the remaining allowance for rework")
                .isEqualTo("Reworking");
        final var rework = blocked.tasks().getFirst();
        f.run.report(
                "rework",
                new WorkRun.Report(
                        rework.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        final var reworked =
                f.run.report(
                        "rework",
                        new WorkRun.Report(
                                rework.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Reworked")));
        assertThat(reworked.work().issues().get("AA1").rounds().rounds())
                .as("rework is not a completed reading")
                .isEqualTo(1);
        final var last = reworked.tasks().getFirst();
        f.run.report(
                "last-reader",
                new WorkRun.Report(
                        last.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        final var reviewed =
                f.run.report(
                        "last-reader",
                        new WorkRun.Report(
                                last.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Findings:\n1. note | - | - | Follow up later")));
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("notes on the last allowed reading reach review")
                .isEqualTo("Review");
        assertThat(reviewed.work().issues().get("AA1").rounds().rounds())
                .as("two successful readings count exactly twice")
                .isEqualTo(2);
        assertThat(reviewed.work().issues().get("AA1").findings().getFirst().severity())
                .as("the latest notes remain available in review")
                .isEqualTo(WorkEnds.Severity.NOTE);
        assertThat(reviewed.tasks()).as("review offers no further task").isEmpty();
    }

    @Test
    void everySaveRefusalHaltsAllUnstartedWork() {
        // Prevents continuation after any failed save, including a partially pushed batch.
        for (final StoreException refusal :
                List.of(
                        new StoreException.Busy("alpha"),
                        new StoreException.StaleRevision("board@2"),
                        new StoreException.NoSuchStory("ST1"),
                        new StoreException.NoSuchIssue("AA1"),
                        new StoreException.NotSavable("AA1", "failed"),
                        new StoreException.WorkflowMissing("alpha"),
                        new StoreException.CredentialRefused("alpha"),
                        new StoreException.Unreachable("alpha", "failed"),
                        new StoreException.Unreadable("alpha", "failed"),
                        new StoreException.PartlySaved("alpha", List.of("beta"), "failed"))) {
            final Fixture f = new Fixture();
            final WorkRun.Offered offer = f.order().tasks().getFirst();
            f.start(offer);
            f.store.failure = refusal;
            assertThatThrownBy(
                            () ->
                                    f.run.report(
                                            "task",
                                            new WorkRun.Report(
                                                    offer.offer(),
                                                    WorkRun.Event.ENDED,
                                                    "box",
                                                    "workstation",
                                                    ended("Built"))))
                    .as("a save refusal reaches the caller")
                    .isSameAs(refusal);
            assertThat(f.run.work("ST1").halted()).as("every refusal halts the run").isTrue();
            assertThat(f.run.work("ST1").issues().get("AA1").reason())
                    .as("the accepted end needs attention")
                    .isEqualTo("SAVE_REFUSED");
            assertThat(f.run.work("ST1").offers())
                    .as("no unstarted offer survives a refusal")
                    .noneMatch(o -> o.status().equals("OFFERED"));
        }
    }

    @Test
    void aHaltedRunStillRecordsActiveEndsWithoutSavingAgain() {
        // Prevents halted runs from losing active ends or writing again.
        final Fixture f = new Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        final var tasks = f.order().tasks();
        final var main =
                tasks.stream()
                        .filter(o -> o.task().role().equals("MAIN"))
                        .findFirst()
                        .orElseThrow();
        final var child =
                tasks.stream()
                        .filter(o -> o.task().role().equals("BUILDER"))
                        .findFirst()
                        .orElseThrow();
        f.start(main);
        f.run.report(
                "child",
                new WorkRun.Report(
                        child.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        f.store.failure = new StoreException.Busy("alpha");
        assertThatThrownBy(
                        () ->
                                f.run.report(
                                        "child",
                                        new WorkRun.Report(
                                                child.offer(),
                                                WorkRun.Event.ENDED,
                                                "box",
                                                "workstation",
                                                ended("Built"))))
                .as("a refused save halts the run")
                .isInstanceOf(StoreException.Busy.class);
        f.store.failure = null;
        final int saves = f.store.saves;
        final var late =
                f.run.report(
                        "task",
                        new WorkRun.Report(
                                main.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Built")));
        assertThat(late.tasks()).as("a late end releases no continuation").isEmpty();
        assertThat(f.run.active("ST1")).as("accepted late ends clear actual activity").isEmpty();
        assertThat(f.store.saves).as("a halted run performs no further save").isEqualTo(saves);
    }

    @Test
    void aRunningStoryLeftOffTheBoardCanStillBeStopped() {
        // A story whose file broke while its task ran would otherwise run on with nobody able to
        // stop it.
        final Fixture f = new Fixture();
        final WorkRun.Offered builder = f.order().tasks().getFirst();
        f.start(builder);
        final Board board = f.store.current;
        f.store.current =
                new Board(
                        board.revision(),
                        board.workflow(),
                        List.of(),
                        board.issues(),
                        board.work(),
                        board.repositories());

        assertThat(java.util.Objects.requireNonNull(f.run.stop("ST1")).stopped())
                .as("the run is stopped though its story is no longer on the board")
                .isTrue();
    }

    @Test
    void aLateEndAfterStopCannotCountSaveOrReleaseWork() {
        // Prevents cancellation racing with an end from restarting work or consuming a reading.
        final Fixture f = new Fixture();
        final WorkRun.Offered builder = f.order().tasks().getFirst();
        f.start(builder);
        f.run.stop("ST1");
        final int saves = f.store.saves;
        final WorkRun.Answer late =
                f.run.report(
                        "task",
                        new WorkRun.Report(
                                builder.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Built")));
        assertThat(late.tasks()).as("a cancelled run releases no work").isEmpty();
        assertThat(f.store.saves).as("a late end preserves planning states").isEqualTo(saves);
        assertThat(f.run.active("ST1")).as("only the actual end clears activity").isEmpty();
    }

    @Test
    void aReadingChoiceClearsItsStopBeforeAnyOfferLeaves() {
        // Prevents repeated choices on one round from granting or launching work twice.
        final Fixture f = new Fixture();
        final WorkRun.Offered builder = f.order().tasks().getFirst();
        f.start(builder);
        final var reader =
                f.run.report(
                                "task",
                                new WorkRun.Report(
                                        builder.offer(),
                                        WorkRun.Event.ENDED,
                                        "box",
                                        "workstation",
                                        ended("Built")))
                        .tasks()
                        .getFirst();
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings:\n1. blocking | - | - | Preserve data")));
        final String revision = f.store.board().revision().value();
        final var chosen =
                f.run.choose("ST1", "AA1", 1, ReadingCycle.Choice.ONE_MORE_ROUND, "", revision);
        assertThat(chosen.tasks()).as("one extra reader is offered").hasSize(1);
        assertThatThrownBy(
                        () ->
                                f.run.choose(
                                        "ST1",
                                        "AA1",
                                        1,
                                        ReadingCycle.Choice.ONE_MORE_ROUND,
                                        "",
                                        revision))
                .as("the same stop cannot grant another choice")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        e ->
                                assertThat(((WorkRefusal) e).name())
                                        .as("the stop is already cleared")
                                        .isEqualTo("ReadingNotStopped"));
    }

    @Test
    void anotherStoryUsesTheRecordedActiveCoordinator() {
        // Prevents a second story from starting a duplicate project coordinator.
        final Fixture f = new Fixture();
        f.start(f.order().tasks().getFirst());
        final Board board = f.store.current;
        final List<Story> stories = new java.util.ArrayList<>(board.stories());
        stories.add(new Story("ST2", "Other", "Backlog", false, "High", List.of("AA2"), "", null));
        final List<Issue> issues = new java.util.ArrayList<>(board.issues());
        issues.add(new Issue("AA2", "alpha", "Other", "Backlog", null, List.of(), null, null, ""));
        f.store.current =
                new Board(
                        board.revision(),
                        board.workflow(),
                        stories,
                        issues,
                        board.work(),
                        board.repositories());
        assertThat(f.run.propose("ST2", "Building").coordinator())
                .as("the active main supplies coordinator knowledge")
                .isEqualTo("TELL");
    }

    @Test
    void aReadingChoiceWaitsForParticipatingChildrenAndKeepsTheFindings() {
        // Prevents a parent's reading choice from bypassing unfinished child readings.
        for (final ReadingCycle.Choice choice :
                List.of(ReadingCycle.Choice.REVIEW_ANYWAY, ReadingCycle.Choice.REWORK)) {
            final Fixture f = new Fixture();
            f.store.current = NextStepTest.board("Backlog", "Backlog");
            final WorkRun.Order order = f.order();
            final var main =
                    order.tasks().stream()
                            .filter(t -> t.task().role().equals("MAIN"))
                            .findFirst()
                            .orElseThrow();
            final var child =
                    order.tasks().stream()
                            .filter(t -> t.task().role().equals("BUILDER"))
                            .findFirst()
                            .orElseThrow();
            f.start(main);
            f.run.report(
                    "child",
                    new WorkRun.Report(
                            child.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
            f.run.report(
                    "task",
                    new WorkRun.Report(
                            main.offer(),
                            WorkRun.Event.ENDED,
                            "box",
                            "workstation",
                            ended("Built")));
            final var readers =
                    f.run.report(
                                    "child",
                                    new WorkRun.Report(
                                            child.offer(),
                                            WorkRun.Event.ENDED,
                                            "box",
                                            "workstation",
                                            ended("Built")))
                            .tasks();
            final var parentReader =
                    readers.stream()
                            .filter(t -> t.task().issues().equals(List.of("AA1")))
                            .findFirst()
                            .orElseThrow();
            final var childReader =
                    readers.stream()
                            .filter(t -> t.task().issues().equals(List.of("AA1.1")))
                            .findFirst()
                            .orElseThrow();
            f.run.report(
                    "parent-reader",
                    new WorkRun.Report(
                            parentReader.offer(),
                            WorkRun.Event.STARTED,
                            "box",
                            "workstation",
                            null));
            f.run.report(
                    "child-reader",
                    new WorkRun.Report(
                            childReader.offer(),
                            WorkRun.Event.STARTED,
                            "box",
                            "workstation",
                            null));
            f.run.report(
                    "parent-reader",
                    new WorkRun.Report(
                            parentReader.offer(),
                            WorkRun.Event.ENDED,
                            "box",
                            "workstation",
                            ended("Findings:\n1. blocking | - | - | Preserve data")));
            f.run.choose(
                    "ST1",
                    "AA1",
                    1,
                    choice,
                    "Preserve bytes, including ``` fences.",
                    f.store.board().revision().value());
            assertThat(f.store.board().issue("AA1").orElseThrow().state())
                    .as("the parent waits while its child is reading")
                    .isEqualTo("Reading");
            final var resumed =
                    f.run.report(
                            "child-reader",
                            new WorkRun.Report(
                                    childReader.offer(),
                                    WorkRun.Event.ENDED,
                                    "box",
                                    "workstation",
                                    ended("Findings:\n")));
            assertThat(f.store.board().issue("AA1").orElseThrow().state())
                    .as("the recorded choice takes effect after the child")
                    .isEqualTo(
                            choice == ReadingCycle.Choice.REVIEW_ANYWAY ? "Review" : "Reworking");
            if (choice == ReadingCycle.Choice.REWORK) {
                assertThat(resumed.tasks().getFirst().task().prompt())
                        .as("deferred rework retains the operator's words")
                        .contains("Preserve bytes, including ``` fences.", "````\n");
            }
            assertThat(f.run.work("ST1").issues().get("AA1").findings())
                    .as("the reading choice retains the blocking finding")
                    .hasSize(1);
        }
    }

    @Test
    void replacingAnUnstartedOrderVoidsItsOffersAndLostWorkCannotRestart() {
        // Prevents a superseded offer or a lost record from silently starting a second run.
        final Fixture f = new Fixture();
        final WorkRun.Offered old = f.order().tasks().getFirst();
        final WorkRun.Offered current = f.order().tasks().getFirst();
        assertThat(old.offer())
                .as("a replacement has a fresh identity")
                .isNotEqualTo(current.offer());
        assertThatThrownBy(() -> f.start(old))
                .as("superseded work cannot start")
                .isInstanceOf(WorkRefusal.class);
        f.start(current);
        final Board active = f.store.current;
        final WorkRule rule = NextStepTest.rule("Building");
        final Map<String, WorkRule> rules = new java.util.LinkedHashMap<>(active.work().rules());
        rules.put(
                "Building",
                new WorkRule(
                        List.of("Backlog", "Building"),
                        rule.done(),
                        rule.then(),
                        rule.blocked(),
                        rule.per(),
                        rule.modes(),
                        rule.order(),
                        rule.models()));
        f.store.current =
                new Board(
                        active.revision(),
                        active.workflow(),
                        active.stories(),
                        active.issues(),
                        new Work(rules, List.of()),
                        active.repositories());
        assertThat(
                        WorkProposal.propose(f.store.current, "ST1", "Building")
                                .repositories()
                                .getFirst()
                                .issues())
                .as("the rule itself allows retrying exactly this issue")
                .containsExactly("AA1");
        final WorkRun lost = new WorkRun(f.store, new Memory(), f.clock);
        assertThatThrownBy(() -> lost.propose("ST1", "Building"))
                .as("a working state without a record needs attention")
                .isInstanceOf(WorkRefusal.class);
        assertThat(lost.work("ST1")).as("missing work does not manufacture counts").isNull();
    }

    @Test
    void anUnfinishedOrUnpushedReadingNeverCountsOrBecomesFindings() {
        // Prevents failed or unpushed work from being accepted as a completed review.
        for (final WorkRun.Ended end :
                List.of(
                        new WorkRun.Ended(
                                "2026-01-01T00:00:00Z", false, "Failed", "Findings:\n", 0),
                        new WorkRun.Ended(
                                "2026-01-01T00:00:00Z", true, "Push failed", "Findings:\n", 1))) {
            final Fixture f = new Fixture();
            final var build = f.order().tasks().getFirst();
            f.start(build);
            final var reader =
                    f.run.report(
                                    "task",
                                    new WorkRun.Report(
                                            build.offer(),
                                            WorkRun.Event.ENDED,
                                            "box",
                                            "workstation",
                                            ended("Built")))
                            .tasks()
                            .getFirst();
            f.run.report(
                    "reader",
                    new WorkRun.Report(
                            reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
            final var answer =
                    f.run.report(
                            "reader",
                            new WorkRun.Report(
                                    reader.offer(),
                                    WorkRun.Event.ENDED,
                                    "box",
                                    "workstation",
                                    end));
            final var progress = answer.work().issues().get("AA1");
            assertThat(progress.rounds().rounds())
                    .as("only successful parsed readings count")
                    .isZero();
            assertThat(progress.needsYou()).as("failed delivery needs attention").isTrue();
            assertThat(answer.tasks()).as("no continuation follows a failed end").isEmpty();
        }
    }

    @Test
    void capacityForAFollowUpCannotRefuseTheAcceptedEnd() {
        // Prevents a full follow-up record from losing an accepted task end.
        final Fixture f = new Fixture();
        final var build = f.order().tasks().getFirst();
        f.start(build);
        f.record.refuseReaders = true;
        final var answer =
                f.run.report(
                        "task",
                        new WorkRun.Report(
                                build.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Built")));
        assertThat(answer.tasks()).as("unpublishable readers are not released").isEmpty();
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the valid transition is still saved")
                .isEqualTo("Reading");
        assertThat(answer.work().issues().get("AA1").needsYou())
                .as("continuation needs attention")
                .isTrue();
    }

    @Test
    void aPendingSaveNeverReplaysAfterRestart() {
        // Prevents an interrupted save from being repeated when its pushed state is ambiguous.
        final Fixture f = new Fixture();
        final var offer = f.order().tasks().getFirst();
        f.start(offer);
        final WorkRun.Run run = f.run.work("ST1");
        f.record.value =
                new WorkRun.Snapshot(
                        1,
                        Map.of(
                                "ST1",
                                new WorkRun.Run(
                                        run.id(),
                                        run.story(),
                                        run.to(),
                                        run.mode(),
                                        run.comment(),
                                        run.coordinator(),
                                        run.readingRounds(),
                                        run.issueReadingRounds(),
                                        run.repositories(),
                                        run.since(),
                                        run.offers(),
                                        run.issues(),
                                        false,
                                        false,
                                        true)));
        assertThat(new WorkRun(f.store, f.record, f.clock).work("ST1").halted())
                .as("ambiguous persistence stops for attention")
                .isTrue();
        assertThat(f.store.saves).as("startup never retries the pending save").isEqualTo(1);
    }

    static WorkRun.Ended ended(final String answer) {
        return new WorkRun.Ended("2026-01-01T00:00:00Z", true, "", answer, 0);
    }

    static final class Memory implements WorkRun.Record {
        WorkRun.Snapshot value = new WorkRun.Snapshot(0, Map.of());
        boolean refuseReaders;
        boolean held;
        @Nullable Board checkedBoard;

        /** Serializes actions as a disk record must. */
        @Override
        public synchronized <T extends @Nullable Object> T locked(final WorkRun.Action<T> action) {
            held = true;
            try {
                return apply(action);
            } finally {
                held = false;
            }
        }

        private <T extends @Nullable Object> T apply(final WorkRun.Action<T> action) {
            return action.apply(
                    new WorkRun.Session() {
                        /** Preserves state between service instances. */
                        @Override
                        public WorkRun.Snapshot read() {
                            return value;
                        }

                        /** Replaces one complete generation. */
                        @Override
                        public void write(final WorkRun.Snapshot snapshot) {
                            value = snapshot;
                        }

                        /** Fakes only capacity, not the store. */
                        @Override
                        public void preflight(final WorkRun.Snapshot snapshot) {
                            if (refuseReaders
                                    && snapshot.runs().values().stream()
                                            .flatMap(r -> r.offers().stream())
                                            .anyMatch(
                                                    o ->
                                                            o.status().equals("OFFERED")
                                                                    && o.binding()
                                                                            .role()
                                                                            .equals("READER"))) {
                                throw WorkRefusal.recordUnavailable(
                                        "project", "The work record is full.");
                            }
                        }

                        /** Keeps the board a decision's save is checked against. */
                        @Override
                        public void preflightIssue(
                                final WorkRun.Snapshot snapshot,
                                final Board board,
                                final String issue) {
                            checkedBoard = board;
                            preflight(snapshot);
                        }
                    });
        }
    }

    static final class Fixture {
        final FakeStore store = new FakeStore();
        final Memory record = new Memory();
        final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        final WorkRun run = new WorkRun(store, record, clock);

        WorkRun.Order order() {
            return run.order(
                    "ST1",
                    "Building",
                    new WorkOrder.Decision(
                            "AGENT", store.board().revision().value(), null, "", 1, null));
        }

        void start(final WorkRun.Offered offer) {
            run.report(
                    "task",
                    new WorkRun.Report(
                            offer.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        }

        List<WorkRun.Offered> readers() {
            final var tasks = order().tasks();
            final var main =
                    tasks.stream()
                            .filter(o -> o.task().role().equals("MAIN"))
                            .findFirst()
                            .orElseThrow();
            final var child =
                    tasks.stream()
                            .filter(o -> o.task().role().equals("BUILDER"))
                            .findFirst()
                            .orElseThrow();
            start(main);
            run.report(
                    "child",
                    new WorkRun.Report(
                            child.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
            run.report(
                    "task",
                    new WorkRun.Report(
                            main.offer(),
                            WorkRun.Event.ENDED,
                            "box",
                            "workstation",
                            ended("Built")));
            final var readers =
                    run.report(
                                    "child",
                                    new WorkRun.Report(
                                            child.offer(),
                                            WorkRun.Event.ENDED,
                                            "box",
                                            "workstation",
                                            ended("Built")))
                            .tasks();
            for (final WorkRun.Offered reader : readers) {
                run.report(
                        "reader-" + reader.task().issues().getFirst(),
                        new WorkRun.Report(
                                reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
            }
            return readers;
        }
    }

    static final class FakeStore implements PlanningStore {
        Board current;
        int saves;
        @Nullable StoreException failure;

        FakeStore() {
            final Board source = NextStepTest.board("Backlog", "Finished");
            current =
                    new Board(
                            source.revision(),
                            source.workflow(),
                            source.stories(),
                            List.of(source.issues().getFirst()),
                            source.work(),
                            source.repositories());
        }

        /** Returns exactly the saved planning state. */
        @Override
        public Board board() {
            return current;
        }

        /** Models refresh without another system. */
        @Override
        public Board refresh() {
            return current;
        }

        /** Makes store writes observable and can refuse before changing them. */
        @Override
        public Board save(final Revision revision, final Writes writes) {
            if (failure != null) {
                throw failure;
            }
            saves++;
            final List<Issue> issues =
                    current.issues().stream()
                            .map(
                                    i ->
                                            new Issue(
                                                    i.id(),
                                                    i.repository(),
                                                    i.title(),
                                                    writes.issues().getOrDefault(i.id(), i.state()),
                                                    i.size(),
                                                    i.blockedBy(),
                                                    i.parent(),
                                                    i.link(),
                                                    i.text(),
                                                    i.worker()))
                            .toList();
            final List<Story> stories =
                    current.stories().stream()
                            .map(
                                    s ->
                                            new Story(
                                                    s.id(),
                                                    s.title(),
                                                    writes.stories()
                                                            .getOrDefault(
                                                                    s.id(),
                                                                    storyState(
                                                                            s.issues().stream()
                                                                                    .map(
                                                                                            id ->
                                                                                                    issues
                                                                                                            .stream()
                                                                                                            .filter(
                                                                                                                    i ->
                                                                                                                            id
                                                                                                                                    .equals(
                                                                                                                                            i
                                                                                                                                                    .id()))
                                                                                                            .findFirst()
                                                                                                            .orElseThrow()
                                                                                                            .state())
                                                                                    .toList())),
                                                    false,
                                                    s.priority(),
                                                    s.issues(),
                                                    s.description(),
                                                    s.link()))
                            .toList();
            current =
                    new Board(
                            new Revision("board@" + (saves + 1)),
                            current.workflow(),
                            stories,
                            issues,
                            current.work(),
                            current.repositories());
            return current;
        }

        /** One issue's state is the aggregate in this fixture. */
        @Override
        public String storyState(final List<String> states) {
            return states.getFirst();
        }
    }
}
