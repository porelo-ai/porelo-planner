package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
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
                                        null,
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
                f.store.board().revision().value(),
                null);
        final var child =
                f.run.choose(
                                "ST1",
                                "AA1.1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "Rework the child",
                                f.store.board().revision().value(),
                                null)
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
        f.store.current =
                NextStepTest.withRule(
                        f.store.board(), "Reworking", NextStepTest.choices("Reading"));
        final var waiting =
                f.run.choose(
                        "ST1",
                        "AA1",
                        1,
                        ReadingCycle.Choice.REWORK,
                        "Preserve bytes",
                        f.store.board().revision().value(),
                        new NextStep.Settings("AGENT", "codex", "model"));
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
                        f.store.board().revision().value(),
                        null);
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
        final WorkOrder.Task resumed = chosen.tasks().getFirst().task();
        assertThat(List.of(resumed.mode(), resumed.agent(), resumed.model()))
                .as("the resumed task keeps the parent's chosen settings, not the rule's first")
                .containsExactly("AGENT", "codex", "model");
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
                                            null,
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
                                    moved(
                                            f.run,
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
                                        revision,
                                        null))
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
        assertThat(f.run.propose("ST1", "Building").repositories())
                .as("a story state its person's issue gives is held by that issue")
                .isNotEmpty();

        f.store.current = withStoryState(held, true);

        assertThatThrownBy(() -> f.run.propose("ST1", "Building"))
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
                                null,
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
                                null,
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
                                        null,
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
                        Worker.MANUAL,
                        Risk.ORDINARY));
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
                f.store.board().revision().value(),
                null);

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
                                        f.store.board().revision().value(),
                                        null))
                .as("the refused choice save halts the run after a counted reading")
                .isInstanceOf(StoreException.Busy.class);
        f.store.failure = null;
        final var decision =
                new WorkOrder.Decision(
                        "AGENT", f.store.board().revision().value(), null, "", 1, null);
        assertThatThrownBy(() -> f.run.order("ST1", "Reworking", decision))
                .as("a halted run still needs an explicit stop before another order")
                .isInstanceOf(WorkRefusal.class);
        final WorkRun.Run stopped = java.util.Objects.requireNonNull(f.run.stop("ST1"));
        assertThat(stopped.stopped())
                .as("stopping a halted run records the person's cancellation")
                .isTrue();
        assertThat(
                        f.run.order(
                                        "ST1",
                                        "Reworking",
                                        decision,
                                        new WorkRun.Start(
                                                null, f.passOver(stopped, "AA1", "STOPPED")))
                                .tasks())
                .as("a stopped halted run accepts work again from the stop's row")
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
    void blockingFindingsLeadThroughThePersonsReworkAndAnotherReadingToReview() {
        // Prevents Review before rework and the next reading finish, and rework nobody chose.
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
                .as("blocking findings with a reading left stay in Reading")
                .isEqualTo("Reading");
        assertThat(blocked.tasks()).as("no rework starts by itself").isEmpty();
        assertThat(blocked.work().issues().get("AA1").reason())
                .as("the person is asked for the findings")
                .isEqualTo("BLOCKING_FINDINGS");
        final var rework =
                f.run.choose(
                                "ST1",
                                "AA1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "",
                                f.store.board().revision().value(),
                                null)
                        .tasks()
                        .getFirst();
        f.run.report(
                "rework",
                new WorkRun.Report(
                        rework.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the started rework moves the issue to Reworking")
                .isEqualTo("Reworking");
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
    void aReaderEndingWithAnUnreadableBlockStopsItsReadingWithNoRoundCounted() {
        // Harm: a person left with no way on after a reading nobody could read.
        final Fixture f = new Fixture();
        final var unreadable = unreadableReading(f);
        final var progress = unreadable.work().issues().get("AA1");
        assertThat(progress.rounds().stopped()).as("the reading stops").isTrue();
        assertThat(progress.rounds().rounds()).as("the round counts").isEqualTo(1);
        assertThat(progress.rounds().roundsMost())
                .as("the limit grows by one with it")
                .isEqualTo(3);
        assertThat(progress.reason()).as("the reason stays").isEqualTo("INVALID_FINDINGS");
        assertThat(progress.needsYou()).as("the person is asked").isTrue();
        assertThat(progress.findings()).as("no findings are kept").isEmpty();
        assertThat(unreadable.tasks()).as("nothing starts by itself").isEmpty();
        final var again =
                f.run.choose(
                        "ST1",
                        "AA1",
                        1,
                        ReadingCycle.Choice.ONE_MORE_ROUND,
                        "",
                        f.store.board().revision().value(),
                        null);
        assertThat(again.tasks()).as("a reader is offered again").hasSize(1);
        assertThat(again.tasks().getFirst().task().reads())
                .as("it reads the same build")
                .containsExactly("task");
        assertThat(again.work().issues().get("AA1").rounds().stopped())
                .as("the choice clears the stop")
                .isFalse();
    }

    @Test
    void aLateCopyOfReadAgainAfterTheNewReaderEndedUnreadableAgainIsRefused() {
        // Harm: a reader run the person never chose.
        final Fixture f = new Fixture();
        final int round = unreadableReading(f).work().issues().get("AA1").rounds().rounds();
        final String revision = f.store.board().revision().value();
        final var again =
                f.run.choose(
                                "ST1",
                                "AA1",
                                round,
                                ReadingCycle.Choice.ONE_MORE_ROUND,
                                "",
                                revision,
                                null)
                        .tasks()
                        .getFirst();
        f.run.report(
                "again",
                new WorkRun.Report(
                        again.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        f.run.report(
                "again",
                new WorkRun.Report(
                        again.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings:\n1. blocking | a.txt | x | not a line")));
        assertThatThrownBy(
                        () ->
                                f.run.choose(
                                        "ST1",
                                        "AA1",
                                        round,
                                        ReadingCycle.Choice.ONE_MORE_ROUND,
                                        "",
                                        revision,
                                        null))
                .as("the late copy of Read again carries an old round")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        e ->
                                assertThat(((WorkRefusal) e).name())
                                        .as("refused for the changed round")
                                        .isEqualTo("ReadingRoundChanged"));
        assertThat(f.run.work("ST1").issues().get("AA1").rounds())
                .as("two unreadable readings count two rounds and raise the limit by two")
                .isEqualTo(new ReadingCycle.Rounds(2, 4, true));
    }

    @Test
    void anUnreadableReadingTakenToReviewAnywaySavesTheDoneState() {
        // Harm: a person left with no way on after a reading nobody could read.
        final Fixture f = new Fixture();
        unreadableReading(f);
        f.run.choose(
                "ST1",
                "AA1",
                1,
                ReadingCycle.Choice.REVIEW_ANYWAY,
                "",
                f.store.board().revision().value(),
                null);
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("review anyway saves the done state")
                .isEqualTo("Review");
    }

    @Test
    void anUnreadableReadingRefusesARework() {
        // Harm: a rework started with nothing to work from.
        final Fixture f = new Fixture();
        unreadableReading(f);
        final String revision = f.store.board().revision().value();
        assertThatThrownBy(
                        () ->
                                f.run.choose(
                                        "ST1",
                                        "AA1",
                                        1,
                                        ReadingCycle.Choice.REWORK,
                                        "Fix it anyway.",
                                        revision,
                                        null))
                .as("a rework without findings is refused")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        e ->
                                assertThat(((WorkRefusal) e).name())
                                        .as("named for the missing findings")
                                        .isEqualTo("ReworkNeedsFindings"));
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the issue stays in Reading")
                .isEqualTo("Reading");
    }

    @Test
    void anUnreadableReadingAfterAReworkKeepsNoneOfTheEarlierFindings() {
        // Harm: findings a rework already answered shown and sent to the next reader as current.
        final Fixture f = new Fixture();
        final var blocking = readingEnded(f, "Findings:\n1. blocking | - | - | Preserve data");
        assertThat(blocking.work().issues().get("AA1").findings())
                .as("the first reading's finding is kept")
                .hasSize(1);
        final var rework =
                f.run.choose(
                                "ST1",
                                "AA1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "",
                                f.store.board().revision().value(),
                                null)
                        .tasks()
                        .getFirst();
        f.run.report(
                "rework",
                new WorkRun.Report(
                        rework.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        final var reader =
                f.run.report(
                                "rework",
                                new WorkRun.Report(
                                        rework.offer(),
                                        WorkRun.Event.ENDED,
                                        "box",
                                        "workstation",
                                        ended("Reworked")))
                        .tasks()
                        .getFirst();
        f.run.report(
                "again",
                new WorkRun.Report(
                        reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        final var unreadable =
                f.run.report(
                        "again",
                        new WorkRun.Report(
                                reader.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Findings:\n1. blocking | a.txt | x | not a line")));
        final var progress = unreadable.work().issues().get("AA1");
        assertThat(progress.findings()).as("the answered finding is gone").isEmpty();
        assertThat(progress.rounds().rounds()).as("both readings count").isEqualTo(2);
        assertThat(progress.rounds().stopped()).as("the reading stops").isTrue();
    }

    @Test
    void anUnreadableReadingStillRefusesAReworkAfterAChoiceThatCouldNotBeOffered() {
        // Harm: a rework started with nothing to work from, once a failed choice renamed the stop.
        final Fixture f = new Fixture();
        unreadableReading(f);
        f.record.value = withoutLandings(f.record.value);
        final var failed =
                f.run.choose(
                        "ST1",
                        "AA1",
                        1,
                        ReadingCycle.Choice.ONE_MORE_ROUND,
                        "",
                        f.store.board().revision().value(),
                        null);
        assertThat(failed.tasks()).as("no reader can be offered").isEmpty();
        assertThat(failed.work().issues().get("AA1").reason())
                .as("the failed choice names its own reason")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
        assertThat(failed.work().issues().get("AA1").rounds().stopped())
                .as("the reading still waits for a choice")
                .isTrue();
        final String revision = f.store.board().revision().value();
        assertThatThrownBy(
                        () ->
                                f.run.choose(
                                        "ST1",
                                        "AA1",
                                        1,
                                        ReadingCycle.Choice.REWORK,
                                        "Fix it anyway.",
                                        revision,
                                        null))
                .as("a rework without findings is still refused")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        e ->
                                assertThat(((WorkRefusal) e).name())
                                        .as("named for the missing findings")
                                        .isEqualTo("ReworkNeedsFindings"));
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the issue stays in Reading")
                .isEqualTo("Reading");
    }

    @Test
    void anUnreadableReadingOfAnIssueMovedByHandMeanwhileAsksThePersonWithoutAStop() {
        // Harm: a stop offered for choices that are all refused, leaving the person no way on.
        final Fixture f = new Fixture();
        final var reader = readerStarted(f);
        f.store.save(
                f.store.board().revision(),
                new Writes(Map.of("AA1", "Review"), Map.of(), List.of()));
        final var ended =
                f.run.report(
                        "reader",
                        new WorkRun.Report(
                                reader.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Findings:\n1. blocking | a.txt | x | not a line")));
        final var progress = ended.work().issues().get("AA1");
        assertThat(progress.rounds().stopped())
                .as("no stop waits for a choice that its state refuses")
                .isFalse();
        assertThat(progress.needsYou()).as("the person is asked").isTrue();
        assertThat(progress.reason())
                .as("as at any end of an issue moved on")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
    }

    /** Builds AA1 and lets its reader end with a block that cannot be read. */
    private static WorkRun.Answer unreadableReading(final Fixture f) {
        return readingEnded(f, "Findings:\n1. blocking | a.txt | x | not a line");
    }

    /** Builds AA1 and lets its reader end with the given answer. */
    private static WorkRun.Answer readingEnded(final Fixture f, final String answer) {
        final var reader = readerStarted(f);
        return f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(), WorkRun.Event.ENDED, "box", "workstation", ended(answer)));
    }

    /** Builds AA1 and starts its reader. */
    private static WorkRun.Offered readerStarted(final Fixture f) {
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
        return reader;
    }

    @Test
    void aBuildEndedBeforeItsWorkLandedOffersItsReaderOnlyWhenItLandsAndOnlyOnce() {
        // A reader offered before the work left the gate starts on nothing and polls; one offered
        // twice reads the same work twice.
        final Fixture f = new Fixture();
        final WorkRun.Offered build = f.order().tasks().getFirst();
        f.start(build);
        final var ended =
                f.run.report(
                        "task",
                        new WorkRun.Report(
                                build.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                unlanded("Built")));
        assertThat(ended.tasks()).as("no reader while the work waits at the gate").isEmpty();
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the end still moves the issue to reading")
                .isEqualTo("Reading");
        assertThat(ended.work().issues().get("AA1").needsYou())
                .as("waiting for the landing asks nobody")
                .isFalse();
        final var landed = f.run.report("task", landed(build, "feature/landed"));
        assertThat(landed.tasks()).as("the landing offers exactly one reader").hasSize(1);
        final WorkOrder.Task reader = landed.tasks().getFirst().task();
        assertThat(reader.role()).as("the offer is the build's reading").isEqualTo("READER");
        assertThat(reader.reads())
                .as("the reader is started naming the landed task")
                .containsExactly("task");
        assertThat(reader.prompt())
                .as("its order names the task and the commit Porelo reported")
                .contains(readsWork("feature/landed"));
        assertThat(
                        landed.work().offers().stream()
                                .filter(o -> o.id().equals(build.offer()))
                                .findFirst()
                                .orElseThrow()
                                .ended()
                                .landed())
                .as("the build's end keeps where its work landed")
                .containsExactly(landing("feature/landed"));
        final var snapshot = f.record.value;
        final int saves = f.store.saves;
        assertThatThrownBy(() -> f.run.report("task", landed(build, "feature/landed")))
                .as("a second landing of the same build is refused")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refused ->
                                assertThat(conflictOf(refused))
                                        .as("a resend is a conflict, which the window may ignore")
                                        .isEqualTo("TaskEventConflict"));
        assertThat(f.record.value)
                .as("the refused landing offers no second reader")
                .isEqualTo(snapshot);
        assertThat(f.store.saves).as("the refused landing writes nothing").isEqualTo(saves);
    }

    @Test
    void aBuildEndedWithItsLandingOffersItsReaderAtOnceAndALandingAfterIsRefused() {
        // A landing after a reader was already offered would offer a second reader of one build.
        final Fixture f = new Fixture();
        final WorkRun.Offered build = f.order().tasks().getFirst();
        f.start(build);
        final var ended =
                f.run.report(
                        "task",
                        new WorkRun.Report(
                                build.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Built")));
        assertThat(ended.tasks()).as("the reader comes with the end").hasSize(1);
        assertThat(ended.tasks().getFirst().task().reads())
                .as("it reads the ended task")
                .containsExactly("task");
        assertThat(ended.tasks().getFirst().task().prompt())
                .as("where the end said it landed")
                .contains(readsWork("work/built"));
        assertThatThrownBy(() -> f.run.report("task", landed(build, "work/built")))
                .as("the build's landing is already known")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refused ->
                                assertThat(conflictOf(refused))
                                        .as("a known landing is a conflict")
                                        .isEqualTo("TaskEventConflict"));
    }

    @Test
    void aLandingOfAParentBuildWhoseReaderCameWithItsChildsEndIsRefused() {
        // The parent's reader follows its child's end, not its own; a landing taken then would
        // offer the parent's work a second reader.
        final Fixture f = new Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        final List<WorkRun.Offered> readers = f.readers();
        assertThat(readers)
                .as("the child's end offers a reader for the parent and the child")
                .hasSize(2);
        final WorkRun.Offer main =
                f.record.value.runs().get("ST1").offers().stream()
                        .filter(o -> o.binding().role().equals("MAIN"))
                        .findFirst()
                        .orElseThrow();
        final var snapshot = f.record.value;
        assertThatThrownBy(
                        () ->
                                f.run.report(
                                        "task",
                                        new WorkRun.Report(
                                                main.id(),
                                                WorkRun.Event.LANDED,
                                                "box",
                                                "workstation",
                                                null,
                                                List.of(landing("work/built")))))
                .as("the parent's end already named where it landed")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refused ->
                                assertThat(conflictOf(refused))
                                        .as("a known landing is a conflict")
                                        .isEqualTo("TaskEventConflict"));
        assertThat(f.record.value)
                .as("the refused landing offers no second reader")
                .isEqualTo(snapshot);
    }

    @Test
    void aLandingOfAParentBuildFromAnOlderRecordWhoseReaderCameWithItsChildsEndIsRefused() {
        // An older record keeps no landing; a landing of a parent already being read would offer
        // the parent's work a second reader.
        final Fixture f = new Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        assertThat(f.readers()).as("the parent's reader came with its child's end").hasSize(2);
        f.record.value = withoutLandings(f.record.value);
        final WorkRun.Offer main =
                f.record.value.runs().get("ST1").offers().stream()
                        .filter(o -> o.binding().role().equals("MAIN"))
                        .findFirst()
                        .orElseThrow();
        final var snapshot = f.record.value;
        assertThatThrownBy(
                        () ->
                                f.run.report(
                                        "task",
                                        new WorkRun.Report(
                                                main.id(),
                                                WorkRun.Event.LANDED,
                                                "box",
                                                "workstation",
                                                null,
                                                List.of(landing("work/built")))))
                .as("a reader of the parent's issue already followed the build")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refused ->
                                assertThat(conflictOf(refused))
                                        .as("a build already read is a conflict")
                                        .isEqualTo("TaskEventConflict"));
        assertThat(f.record.value)
                .as("the refused landing offers no second reader")
                .isEqualTo(snapshot);
    }

    @Test
    void anUnstartedReaderFromAnOlderRecordIsServedNamingTheBuildItReads() {
        // A reader served without the tasks it reads is refused by the window and never starts,
        // or starts without the build's work.
        final Fixture f = new Fixture();
        final WorkRun.Offered build = f.order().tasks().getFirst();
        f.start(build);
        final WorkRun.Report end =
                new WorkRun.Report(
                        build.offer(), WorkRun.Event.ENDED, "box", "workstation", ended("Built"));
        final WorkRun.Offered reader = f.run.report("task", end).tasks().getFirst();
        f.record.value = withoutReads(withoutLandings(f.record.value));
        final var replayed = f.run.report("task", end);
        assertThat(replayed.tasks())
                .as("the replay answers the same unstarted reader")
                .extracting(WorkRun.Offered::offer)
                .containsExactly(reader.offer());
        assertThat(replayed.tasks().getFirst().task().reads())
                .as("the older reader names the build it follows")
                .containsExactly("task");
    }

    @Test
    void anUnstartedReaderFromAnOlderRecordWithNoKnownBuildIsVoidedAndNeedsThePerson() {
        // A reader that names no build would start reading nothing.
        final Fixture f = new Fixture();
        final WorkRun.Offered build = f.order().tasks().getFirst();
        f.start(build);
        final WorkRun.Offered reader =
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
        final WorkRun.Snapshot older = withoutReads(withoutLandings(f.record.value));
        final WorkRun.Run run = older.runs().get("ST1");
        f.record.value =
                new WorkRun.Snapshot(
                        older.generation(),
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
                                        run.offers().stream()
                                                .filter(o -> !o.id().equals(build.offer()))
                                                .toList(),
                                        run.issues(),
                                        run.stopped(),
                                        run.halted(),
                                        run.pending())),
                        older.ideas());
        final WorkRun.Run read = f.run.work("ST1");
        assertThat(
                        read.offers().stream()
                                .filter(o -> o.id().equals(reader.offer()))
                                .findFirst()
                                .orElseThrow()
                                .status())
                .as("a reader that cannot name its build is no longer offered")
                .isEqualTo("VOID");
        assertThat(read.issues().get("AA1").reason())
                .as("the person is asked instead")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
    }

    @Test
    void aLandingOfAnUnknownUnendedFailedOrReadingTaskIsRefusedAndOffersNothing() {
        // A landing taken for work that is not a finished build would start a reader on nothing.
        final Fixture f = new Fixture();
        final WorkRun.Offered build = f.order().tasks().getFirst();
        assertThatThrownBy(
                        () ->
                                f.run.report(
                                        "task",
                                        new WorkRun.Report(
                                                "00000000-0000-4000-8000-000000000000",
                                                WorkRun.Event.LANDED,
                                                "box",
                                                "workstation",
                                                null,
                                                List.of(landing("work/built")))))
                .as("an unknown offer has no build that landed")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refused ->
                                assertThat(conflictOf(refused))
                                        .as("an unknown offer is a conflict, not a missing item")
                                        .isEqualTo("TaskEventConflict"));
        f.start(build);
        assertThatThrownBy(() -> f.run.report("task", landed(build, "work/built")))
                .as("a started task that has not ended has not landed")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refused ->
                                assertThat(conflictOf(refused))
                                        .as("an unended task is a conflict")
                                        .isEqualTo("TaskEventConflict"));
        f.run.report(
                "task",
                new WorkRun.Report(
                        build.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        new WorkRun.Ended("2026-01-01T00:00:00Z", false, "Died", "", 0)));
        final var snapshot = f.record.value;
        assertThatThrownBy(() -> f.run.report("task", landed(build, "work/built")))
                .as("a build that failed did not end done")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refused ->
                                assertThat(conflictOf(refused))
                                        .as("a failed build is a conflict")
                                        .isEqualTo("TaskEventConflict"));
        assertThat(f.record.value).as("the refused landings record nothing").isEqualTo(snapshot);

        final Fixture reading = new Fixture();
        final WorkRun.Offered builder = reading.order().tasks().getFirst();
        reading.start(builder);
        final var reader =
                reading.run
                        .report(
                                "task",
                                new WorkRun.Report(
                                        builder.offer(),
                                        WorkRun.Event.ENDED,
                                        "box",
                                        "workstation",
                                        ended("Built")))
                        .tasks()
                        .getFirst();
        reading.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        reading.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        unlanded("Findings:\n")));
        assertThatThrownBy(
                        () ->
                                reading.run.report(
                                        "reader",
                                        new WorkRun.Report(
                                                reader.offer(),
                                                WorkRun.Event.LANDED,
                                                "box",
                                                "workstation",
                                                null,
                                                List.of(landing("work/read")))))
                .as("a reading is no build whose work lands")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refused ->
                                assertThat(conflictOf(refused))
                                        .as("a reading's landing is a conflict")
                                        .isEqualTo("TaskEventConflict"));
    }

    @Test
    void aReadingTakenUpAgainReadsTheLastBuildWhereItLanded() {
        // A reading taken up again that named no landing, or another build's, reads the wrong work.
        final Fixture f = new Fixture();
        final WorkRun.Offered build = f.order().tasks().getFirst();
        f.start(build);
        f.run.report(
                "task",
                new WorkRun.Report(
                        build.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        unlanded("Built")));
        final var reader = f.run.report("task", landed(build, "feature/aa1")).tasks().getFirst();
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
        final var again =
                f.run.choose(
                        "ST1",
                        "AA1",
                        1,
                        ReadingCycle.Choice.ONE_MORE_ROUND,
                        "",
                        f.store.board().revision().value(),
                        null);
        assertThat(again.tasks()).as("one more reading is offered").hasSize(1);
        assertThat(again.tasks().getFirst().task().reads())
                .as("it reads the last build's task")
                .containsExactly("task");
        assertThat(again.tasks().getFirst().task().prompt())
                .as("where that build landed")
                .contains(readsWork("feature/aa1"));
    }

    @Test
    void aReadingTakenUpAgainOfABuildWithNoKnownLandingIsNotOfferedAndNeedsThePerson() {
        // A reader of a build whose landing is unknown starts on nothing, so the person is asked.
        final Fixture f = new Fixture();
        final WorkRun.Offered build = f.order().tasks().getFirst();
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
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings:\n1. blocking | - | - | Preserve data")));
        f.record.value = withoutLandings(f.record.value);
        assertThatThrownBy(() -> f.run.report("task", landed(build, "work/built")))
                .as(
                        "a build whose reader came with its end, as an older record holds it, has landed")
                .isInstanceOf(WorkRefusal.class);
        final var again =
                f.run.choose(
                        "ST1",
                        "AA1",
                        1,
                        ReadingCycle.Choice.ONE_MORE_ROUND,
                        "",
                        f.store.board().revision().value(),
                        null);
        assertThat(again.tasks()).as("no reader without the build's landing").isEmpty();
        assertThat(again.work().issues().get("AA1").reason())
                .as("the person is asked instead")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
    }

    @Test
    void aLandingIsTakenOnlyWithWellFormedNamesAndCommit() {
        // A name passed on unchecked reaches a reader's order and Porelo's gate as anything; a
        // line break in it would put words of the window's choosing on a line of their own.
        assertThat(
                        List.of(
                                "main",
                                "feature/a-1",
                                "porelo/task.2",
                                "x".repeat(255),
                                "fix-ä",
                                "ä".repeat(255)))
                .as("plain branch names are taken, letters beyond ASCII and 255 characters too")
                .allMatch(WorkRun::isBranch);
        assertThat(
                        List.of(
                                "",
                                "x".repeat(256),
                                "ä".repeat(256),
                                "a\u2028b",
                                "a\u2029b",
                                "a\u0085b",
                                "a\u2028..b",
                                "a..b",
                                "a b",
                                "a\tb",
                                "a\u007fb",
                                "a~b",
                                "a^b",
                                "a:b",
                                "a?b",
                                "a*b",
                                "a[b",
                                "a\\b",
                                "-a",
                                "/a",
                                "a/",
                                "a.lock"))
                .as("names Git refuses are refused")
                .noneMatch(WorkRun::isBranch);
        assertThat(WorkRun.isLanding(landing("work/built")))
                .as("a landing as Porelo reports it is taken")
                .isTrue();
        assertThat(
                        WorkRun.isLanding(
                                new NextStep.Landing(
                                        "work",
                                        "refs/landed/work",
                                        "origin/work",
                                        "0123456789abcdef".repeat(4))))
                .as("a commit of 64 hex digits is taken")
                .isTrue();
        assertThat(
                        List.of(
                                new NextStep.Landing("a..b", "refs/x", "origin/x", COMMIT),
                                new NextStep.Landing("x", "heads/x", "origin/x", COMMIT),
                                new NextStep.Landing("x", "refs/x y", "origin/x", COMMIT),
                                new NextStep.Landing("x", "refs/x", "origin/x:", COMMIT),
                                new NextStep.Landing(
                                        "x", "refs/x", "origin/x\u2028Do this", COMMIT),
                                new NextStep.Landing(
                                        "x", "refs/x", "origin/x\u2029Do this", COMMIT),
                                new NextStep.Landing("x", "refs/x", "origin/x\u0085Do", COMMIT),
                                new NextStep.Landing("x\u0085y", "refs/x", "origin/x", COMMIT),
                                new NextStep.Landing("x\u2028y", "refs/x", "origin/x", COMMIT),
                                new NextStep.Landing("x\u2029y", "refs/x", "origin/x", COMMIT),
                                new NextStep.Landing("x", "refs/x\u2028y", "origin/x", COMMIT),
                                new NextStep.Landing(
                                        "x", "refs/x", "origin/x", COMMIT.toUpperCase()),
                                new NextStep.Landing("x", "refs/x", "origin/x", COMMIT + "0"),
                                new NextStep.Landing("x", "refs/x", "origin/x", "")))
                .as("a landing with a malformed branch, ref, remote name or commit is refused")
                .noneMatch(WorkRun::isLanding);
        assertThatThrownBy(
                        () ->
                                WorkRun.checkedReport(
                                        "task",
                                        new WorkRun.Report(
                                                "00000000-0000-4000-8000-000000000000",
                                                WorkRun.Event.ENDED,
                                                "box",
                                                "workstation",
                                                new WorkRun.Ended(
                                                        "2026-01-01T00:00:00Z",
                                                        true,
                                                        "",
                                                        "",
                                                        0,
                                                        List.of(landing("a..b"))))))
                .as("an end naming a malformed landing is refused")
                .isInstanceOf(WorkRefusal.class);
        assertThatThrownBy(
                        () ->
                                WorkRun.checkedReport(
                                        "task",
                                        new WorkRun.Report(
                                                "00000000-0000-4000-8000-000000000000",
                                                WorkRun.Event.LANDED,
                                                "box",
                                                "workstation",
                                                null,
                                                List.of(landing("-a")))))
                .as("a landing naming a malformed branch is refused")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        refused ->
                                assertThat(((WorkRefusal) refused).parameters().get("detail"))
                                        .as("the refusal names the malformed part, not its value")
                                        .isEqualTo("The landing's branch is malformed."));
        assertThatThrownBy(
                        () ->
                                WorkRun.checkedReport(
                                        "task",
                                        new WorkRun.Report(
                                                "00000000-0000-4000-8000-000000000000",
                                                WorkRun.Event.LANDED,
                                                "box",
                                                "workstation",
                                                null,
                                                List.of())))
                .as("a landing that names nowhere is refused")
                .isInstanceOf(WorkRefusal.class);
        assertThatThrownBy(
                        () ->
                                WorkRun.checkedReport(
                                        "task",
                                        new WorkRun.Report(
                                                "00000000-0000-4000-8000-000000000000",
                                                WorkRun.Event.STOPPED,
                                                "box",
                                                "workstation",
                                                null,
                                                List.of(landing("main")))))
                .as("only a landing carries where work landed beside its end")
                .isInstanceOf(WorkRefusal.class);
    }

    /** The record as one written before ends carried where their work landed reads. */
    private static WorkRun.Snapshot withoutLandings(final WorkRun.Snapshot snapshot) {
        final Map<String, WorkRun.Run> runs = new java.util.LinkedHashMap<>();
        snapshot.runs()
                .forEach(
                        (story, run) ->
                                runs.put(
                                        story,
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
                                                run.offers().stream()
                                                        .map(WorkRunTest::withoutLanding)
                                                        .toList(),
                                                run.issues(),
                                                run.stopped(),
                                                run.halted(),
                                                run.pending())));
        return new WorkRun.Snapshot(snapshot.generation(), runs, snapshot.ideas());
    }

    /** The record as one written before offers named the tasks a reader reads. */
    private static WorkRun.Snapshot withoutReads(final WorkRun.Snapshot snapshot) {
        final Map<String, WorkRun.Run> runs = new java.util.LinkedHashMap<>();
        snapshot.runs()
                .forEach(
                        (story, run) ->
                                runs.put(
                                        story,
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
                                                run.offers().stream()
                                                        .map(WorkRunTest::withoutReads)
                                                        .toList(),
                                                run.issues(),
                                                run.stopped(),
                                                run.halted(),
                                                run.pending())));
        return new WorkRun.Snapshot(snapshot.generation(), runs, snapshot.ideas());
    }

    private static WorkRun.Offer withoutReads(final WorkRun.Offer o) {
        final WorkOrder.Task a = o.arguments();
        return new WorkRun.Offer(
                o.id(),
                new WorkOrder.Task(
                        a.repository(),
                        a.issues(),
                        a.role(),
                        a.agent(),
                        a.model(),
                        a.effort(),
                        a.mode(),
                        a.prompt()),
                o.binding(),
                o.status(),
                o.task(),
                o.containerId(),
                o.machine(),
                o.since(),
                o.ended(),
                o.fingerprint(),
                o.following(),
                o.deferred(),
                o.refusal(),
                o.reviewAnyway(),
                o.reworkComment(),
                o.rework());
    }

    private static WorkRun.Offer withoutLanding(final WorkRun.Offer o) {
        final WorkRun.Ended end = o.ended();
        return new WorkRun.Offer(
                o.id(),
                o.arguments(),
                o.binding(),
                o.status(),
                o.task(),
                o.containerId(),
                o.machine(),
                o.since(),
                end == null
                        ? null
                        : new WorkRun.Ended(
                                end.at(), end.finished(), end.why(), end.answer(), end.unpushed()),
                o.fingerprint(),
                o.following(),
                o.deferred(),
                o.refusal(),
                o.reviewAnyway(),
                o.reworkComment(),
                o.rework());
    }

    @Test
    void aHandMoveThatLeavesAnIssueWhereItStandsKeepsItsNeedsYou() {
        // An issue stuck in a working state with its warning cleared is seen by nobody and worked
        // on by nothing.
        final Fixture f = new Fixture();
        final var build = f.order().tasks().getFirst();
        f.start(build);
        f.run.report(
                "task",
                new WorkRun.Report(
                        build.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        new WorkRun.Ended("2026-01-01T00:00:00Z", false, "Died", "", 0)));
        // A second issue of the story behind it, so the story's move carries that one and leaves
        // the failed one where it stands, as a move the planner takes can.
        final Board before = f.store.board();
        f.store.current =
                new Board(
                        before.revision(),
                        before.workflow(),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story",
                                        "Building",
                                        false,
                                        "High",
                                        null,
                                        List.of("AA1", "AA2"),
                                        "",
                                        null)),
                        List.of(
                                before.issue("AA1").orElseThrow(),
                                new Issue(
                                        "AA2", "alpha", "Other", "Backlog", null, List.of(), null,
                                        null, "")),
                        before.work(),
                        before.repositories());

        moved(
                f.run,
                List.of(new Moves.Move(true, "ST1", "Review")),
                () -> f.store.save(f.store.board().revision(), writes("AA2", "Review")));

        final NextStep.Progress progress =
                java.util.Objects.requireNonNull(f.run.work("ST1")).issues().get("AA1");
        assertThat(progress.needsYou()).as("the Needs-you row stays").isTrue();
        assertThat(progress.reason()).as("the warning stays").isEqualTo("FAILED");
    }

    @Test
    void aHandMoveOfAnIssueWhoseTaskFailedRetiresItsNeedsYou() {
        // A Needs-you row nothing can clear would hold the story's work back for good.
        final Fixture f = new Fixture();
        final var build = f.order().tasks().getFirst();
        f.start(build);
        final var failed =
                f.run.report(
                        "task",
                        new WorkRun.Report(
                                build.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                new WorkRun.Ended("2026-01-01T00:00:00Z", false, "Died", "", 0)));
        assertThat(failed.work().issues().get("AA1").reason())
                .as("the failed task raised the issue")
                .isEqualTo("FAILED");

        moved(
                f.run,
                List.of(new Moves.Move(false, "AA1", "Backlog")),
                () -> f.store.save(f.store.board().revision(), writes("AA1", "Backlog")));

        final NextStep.Progress progress =
                java.util.Objects.requireNonNull(f.run.work("ST1")).issues().get("AA1");
        assertThat(progress.needsYou()).as("no Needs-you row stays").isFalse();
        assertThat(progress.reason()).as("no warning stays").isEqualTo("NONE");
    }

    @Test
    void aHandMoveOfAnIssueWhoseReadingStoppedRetiresTheStop() {
        // A stop nothing can clear would refuse every later start of the story's work.
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

        moved(
                f.run,
                List.of(new Moves.Move(false, "AA1.1", "Review")),
                () -> f.store.save(f.store.board().revision(), writes("AA1.1", "Review")));

        final WorkRun.Run run = java.util.Objects.requireNonNull(f.run.work("ST1"));
        assertThat(run.issues().get("AA1.1").rounds().stopped())
                .as("the moved issue's reading no longer waits")
                .isFalse();
        assertThat(run.issues().get("AA1").rounds().stopped())
                .as("an issue not moved keeps its stop")
                .isTrue();
    }

    @Test
    void aReworkChoiceOutsideTheReworkingRulesSettingsIsRefusedAndSavesNothing() {
        // A rework in a mode or with a model the work rules do not allow would run unchecked work.
        for (final NextStep.Settings wrong :
                List.of(
                        new NextStep.Settings("UNATTENDED", "gemini", "opus"),
                        new NextStep.Settings("UNATTENDED", "claude", "model"),
                        new NextStep.Settings("SHELL", "claude", "opus"))) {
            final Fixture f = stoppedOnFirstOfTwo();
            final int saves = f.store.saves;
            final String revision = f.store.board().revision().value();
            assertThatThrownBy(
                            () ->
                                    f.run.choose(
                                            "ST1",
                                            "AA1",
                                            1,
                                            ReadingCycle.Choice.REWORK,
                                            "",
                                            revision,
                                            wrong))
                    .as("%s is refused", wrong)
                    .isInstanceOf(WorkRefusal.class)
                    .satisfies(
                            e ->
                                    assertThat(((WorkRefusal) e).name())
                                            .as("the settings are named as invalid")
                                            .isEqualTo("InvalidSettings"));
            assertThat(f.store.saves).as("nothing is saved for %s", wrong).isEqualTo(saves);
            assertThat(
                            java.util.Objects.requireNonNull(f.run.work("ST1"))
                                    .issues()
                                    .get("AA1")
                                    .rounds()
                                    .stopped())
                    .as("the reading still waits after %s", wrong)
                    .isTrue();
        }
    }

    @Test
    void aReworkChoiceRunsWithTheChosenSettingsOrTheRulesFirst() {
        // A rework with other settings than the person saw would run an agent they did not choose.
        final Fixture chosen = stoppedOnFirstOfTwo();
        final WorkOrder.Task task =
                chosen.run
                        .choose(
                                "ST1",
                                "AA1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "",
                                chosen.store.board().revision().value(),
                                new NextStep.Settings("AGENT", "codex", "model"))
                        .tasks()
                        .getFirst()
                        .task();
        assertThat(List.of(task.mode(), task.agent(), task.model()))
                .as("the chosen mode, agent and model")
                .containsExactly("AGENT", "codex", "model");
        assertThat(task.prompt())
                .as("the blocking findings go with a rework given no words")
                .contains("Preserve data");

        final Fixture first = stoppedOnFirstOfTwo();
        final WorkOrder.Task defaulted =
                first.run
                        .choose(
                                "ST1",
                                "AA1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "",
                                first.store.board().revision().value(),
                                null)
                        .tasks()
                        .getFirst()
                        .task();
        assertThat(List.of(defaulted.mode(), defaulted.agent(), defaulted.model()))
                .as("no choice takes the Reworking rule's first mode and model")
                .containsExactly("UNATTENDED", "claude", "opus");
    }

    @Test
    void aReworkChosenWithNoSettingsForAHarmIssueRunsWithHarmsModelAndEffort() {
        // A rework given the rule's light first model for work that can do harm would rework it
        // carelessly.
        final Fixture f = stoppedOnFirstOfTwo();
        f.store.current =
                NextStepTest.risked(
                        NextStepTest.withRule(
                                f.store.board(), "Reworking", NextStepTest.risky("Reading")),
                        Risk.HARM);
        final WorkOrder.Task task =
                f.run.choose(
                                "ST1",
                                "AA1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "",
                                f.store.board().revision().value(),
                                null)
                        .tasks()
                        .getFirst()
                        .task();
        assertThat(List.of(task.agent(), task.model()))
                .as("the model the Reworking rule names for harm")
                .containsExactly("codex", "careful");
        assertThat(task.effort()).as("the effort it names for harm").isEqualTo("max");
    }

    @Test
    void aDeferredHarmParentResumedAfterItsChildKeepsHarmsChoice() {
        // A parent's risk lost while it waits for its child would rework harmful work lightly.
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
        f.store.current =
                NextStepTest.risked(
                        NextStepTest.withRule(
                                f.store.board(), "Reworking", NextStepTest.risky("Reading")),
                        Risk.HARM);
        final var waiting =
                f.run.choose(
                        "ST1",
                        "AA1",
                        1,
                        ReadingCycle.Choice.REWORK,
                        "",
                        f.store.board().revision().value(),
                        null);
        assertThat(waiting.tasks()).as("the stopped child holds its parent's rework").isEmpty();
        final var chosen =
                f.run.choose(
                        "ST1",
                        "AA1.1",
                        1,
                        ReadingCycle.Choice.REVIEW_ANYWAY,
                        "",
                        f.store.board().revision().value(),
                        null);
        final WorkOrder.Task resumed = chosen.tasks().getFirst().task();
        assertThat(List.of(resumed.issues().getFirst(), resumed.model()))
                .as("the resumed parent's rework takes harm's model")
                .containsExactly("AA1", "careful");
        assertThat(resumed.effort()).as("and harm's effort").isEqualTo("max");
    }

    @Test
    void anOrderedTaskIsOfferedWithItsRisksEffort() {
        // An effort dropped where the run saves the window's order would start work that can do
        // harm without the care the rule names.
        final Fixture f = new Fixture();
        final WorkRule building = NextStepTest.rule("Building");
        f.store.current =
                NextStepTest.risked(
                        NextStepTest.withRule(
                                f.store.board(),
                                "Building",
                                new WorkRule(
                                        building.from(),
                                        building.done(),
                                        building.then(),
                                        building.blocked(),
                                        building.per(),
                                        building.modes(),
                                        building.order(),
                                        building.models(),
                                        Map.of(
                                                Risk.HARM,
                                                new WorkRule.ForRisk(
                                                        Map.of("codex", "careful"), "max")))),
                        Risk.HARM);
        final WorkOrder.Task task = f.order().tasks().getFirst().task();
        assertThat(List.of(task.issues().getFirst(), task.model()))
                .as("the offered task of a harm issue takes harm's model")
                .containsExactly("AA1", "careful");
        assertThat(task.effort()).as("and keeps harm's effort").isEqualTo("max");
    }

    /** One issue whose first of two readings stopped on a blocking finding. */
    private static Fixture stoppedOnFirstOfTwo() {
        final Fixture f = new Fixture();
        f.store.current =
                NextStepTest.withRule(
                        f.store.board(), "Reworking", NextStepTest.choices("Reading"));
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
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings:\n1. blocking | - | - | Preserve data")));
        return f;
    }

    @Test
    void aReworkThatEndsLandedMovesItsIssueOnToTheReadingItsRuleNames() {
        // A rework whose then the next rule's from does not name stranded its issue in Reworking,
        // with nobody to read the reworked build.
        for (final boolean landedAtEnd : List.of(false, true)) {
            final Fixture f = reworkingThenReading();
            final var rework =
                    f.run.choose(
                                    "ST1",
                                    "AA1",
                                    1,
                                    ReadingCycle.Choice.REWORK,
                                    "",
                                    f.store.board().revision().value(),
                                    null)
                            .tasks()
                            .getFirst();
            f.run.report(
                    "rework",
                    new WorkRun.Report(
                            rework.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
            final var ended =
                    f.run.report(
                            "rework",
                            new WorkRun.Report(
                                    rework.offer(),
                                    WorkRun.Event.ENDED,
                                    "box",
                                    "workstation",
                                    landedAtEnd ? ended("Reworked") : unlanded("Reworked")));
            final var readers =
                    landedAtEnd
                            ? ended.tasks()
                            : f.run.report(
                                            "rework",
                                            new WorkRun.Report(
                                                    rework.offer(),
                                                    WorkRun.Event.LANDED,
                                                    "box",
                                                    "workstation",
                                                    null,
                                                    List.of(landing("rework"))))
                                    .tasks();
            assertThat(f.store.board().issue("AA1").orElseThrow().state())
                    .as(
                            "the rework's end moves the issue to its rule's then (landed at end %s)",
                            landedAtEnd)
                    .isEqualTo("Reading");
            assertThat(readers)
                    .as(
                            "a reader of the rework's landed build is offered (landed at end %s)",
                            landedAtEnd)
                    .extracting(o -> o.task().role(), o -> o.task().reads())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("READER", List.of("rework")));
            final NextStep.Progress progress =
                    java.util.Objects.requireNonNull(f.run.work("ST1").issues().get("AA1"));
            assertThat(List.of(progress.needsYou(), progress.reason()))
                    .as("the issue needs nobody (landed at end %s)", landedAtEnd)
                    .containsExactly(false, "NONE");
        }
    }

    @Test
    void aReworkWhoseIssueTheStoreMovedMeanwhileSaysWhyItsNextStepIsUnavailable() {
        // A rework whose own commits moved its issue, once they reach the store, left the issue
        // asking the person with no word of why, in the log or anywhere else.
        final Fixture f = reworkingThenReading();
        final List<String> told = new java.util.ArrayList<>();
        final WorkRun run = new WorkRun(f.store, f.record, f.clock, told::add);
        final var rework =
                run.choose(
                                "ST1",
                                "AA1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "",
                                f.store.board().revision().value(),
                                null)
                        .tasks()
                        .getFirst();
        run.report(
                "rework",
                new WorkRun.Report(
                        rework.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        f.store.current = inState(f.store.board(), "AA1", "Review");
        final int saves = f.store.saves;
        final var ended =
                run.report(
                        "rework",
                        new WorkRun.Report(
                                rework.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Reworked")));
        assertThat(ended.tasks()).as("no task follows an issue moved meanwhile").isEmpty();
        assertThat(f.store.saves).as("the planner writes no move over it").isEqualTo(saves);
        assertThat(java.util.Objects.requireNonNull(ended.work().issues().get("AA1")).reason())
                .as("the issue asks the person")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
        assertThat(told)
                .as(
                        "one line names the issue, its story, where it stands and where the task worked")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("AA1", "ST1", "Review", "Reworking");
    }

    @Test
    void everyAgentsOrderSaysThePlannerAloneMovesItsIssues() {
        // An agent that moves its own issue lands moves the planner did not make, beside its own.
        final Fixture f = reworkingThenReading();
        final List<WorkOrder.Task> orders = new java.util.ArrayList<>(f.offered);
        orders.add(
                f.run.choose(
                                "ST1",
                                "AA1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "",
                                f.store.board().revision().value(),
                                null)
                        .tasks()
                        .getFirst()
                        .task());
        assertThat(orders)
                .as("the build's, the reader's and the rework's order")
                .extracting(WorkOrder.Task::role)
                .containsExactly("MAIN", "READER", "BUILDER");
        assertThat(orders)
                .as("each order leaves the issues' moves and stats/ to the planner")
                .allSatisfy(
                        o ->
                                assertThat(o.prompt())
                                        .as("the order of the %s", o.role())
                                        .contains(WorkOrder.PLANNER_MOVES));
    }

    @Test
    void aLineThatCannotBeToldFailsNoEndWhoseRunIsSaved() {
        // An end answered as failed after its run was saved would have the window send it again
        // against a run that has already moved on.
        final Fixture f = reworkingThenReading();
        final WorkRun run =
                new WorkRun(
                        f.store,
                        f.record,
                        f.clock,
                        why -> {
                            throw new IllegalStateException("the log is gone");
                        });
        final var rework =
                run.choose(
                                "ST1",
                                "AA1",
                                1,
                                ReadingCycle.Choice.REWORK,
                                "",
                                f.store.board().revision().value(),
                                null)
                        .tasks()
                        .getFirst();
        run.report(
                "rework",
                new WorkRun.Report(
                        rework.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        f.store.current = inState(f.store.board(), "AA1", "Review");
        final var ended =
                run.report(
                        "rework",
                        new WorkRun.Report(
                                rework.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Reworked")));
        assertThat(java.util.Objects.requireNonNull(ended.work().issues().get("AA1")).reason())
                .as("the end is answered with the saved run")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
    }

    @Test
    void aThenThatLeadsBackIntoBuildingOffersOneReaderAndNoFurtherTask() {
        // A then that led from building back into building, offering a builder at every end,
        // would start agent tasks that cost money without end.
        for (final Map<String, String> then :
                List.of(
                        Map.of("Building", "Building"),
                        Map.of("Building", "Reworking", "Reworking", "Building"))) {
            final Fixture f = new Fixture();
            Board board = f.store.board();
            for (final Map.Entry<String, String> rule : then.entrySet()) {
                board =
                        NextStepTest.withRule(
                                board,
                                rule.getKey(),
                                rule(
                                        List.of("Backlog", "Building", "Reworking"),
                                        rule.getValue(),
                                        null,
                                        WorkRule.Per.REPOSITORY));
            }
            f.store.current = board;
            final var build = f.order().tasks().getFirst();
            f.start(build);
            final var after =
                    f.run.report(
                                    "task",
                                    new WorkRun.Report(
                                            build.offer(),
                                            WorkRun.Event.ENDED,
                                            "box",
                                            "workstation",
                                            ended("Built")))
                            .tasks();
            assertThat(after)
                    .as("the build's end offers only its reader (%s)", then)
                    .extracting(o -> o.task().role())
                    .containsExactly("READER");
            final var reader = after.getFirst();
            f.run.report(
                    "reader",
                    new WorkRun.Report(
                            reader.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
            final var read =
                    f.run.report(
                            "reader",
                            new WorkRun.Report(
                                    reader.offer(),
                                    WorkRun.Event.ENDED,
                                    "box",
                                    "workstation",
                                    ended("Findings:")));
            assertThat(read.tasks()).as("the reading's end offers no task (%s)", then).isEmpty();
            assertThat(f.store.board().issue("AA1").orElseThrow().state())
                    .as("the reading's end leaves the work in done (%s)", then)
                    .isEqualTo("Review");
        }
    }

    /** The board with one issue standing in another state, as a store read gives it. */
    private static Board inState(final Board board, final String id, final String state) {
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                board.issues().stream()
                        .map(
                                i ->
                                        !i.id().equals(id)
                                                ? i
                                                : new Issue(
                                                        i.id(),
                                                        i.repository(),
                                                        i.title(),
                                                        state,
                                                        i.size(),
                                                        i.blockedBy(),
                                                        i.parent(),
                                                        i.link(),
                                                        i.text(),
                                                        i.worker(),
                                                        i.risk()))
                        .toList(),
                board.work(),
                board.repositories());
    }

    /**
     * One issue whose first of two readings stopped on a blocking finding, under rules where the
     * reading takes work only from the build and the rework's then leads back to the reading.
     */
    private static Fixture reworkingThenReading() {
        final Fixture f = new Fixture();
        Board board = f.store.board();
        board =
                NextStepTest.withRule(
                        board,
                        "Reading",
                        rule(List.of("Building"), null, "Reworking", WorkRule.Per.STORY));
        board =
                NextStepTest.withRule(
                        board,
                        "Reworking",
                        rule(List.of("Reading"), "Reading", null, WorkRule.Per.REPOSITORY));
        f.store.current = board;
        final var build =
                f.run.order(
                                "ST1",
                                "Building",
                                new WorkOrder.Decision("AGENT", "board@1", null, "", 2, null))
                        .tasks()
                        .getFirst();
        f.offered.add(build.task());
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
        f.offered.add(reader.task());
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
        final NextStep.Progress stopped =
                java.util.Objects.requireNonNull(f.run.work("ST1").issues().get("AA1"));
        assertThat(List.of(stopped.reason(), stopped.rounds().rounds()))
                .as("the reading stopped on its blocking finding, one round of two counted")
                .containsExactly("BLOCKING_FINDINGS", 1);
        return f;
    }

    /** A rule in the fixture's mode and model, with the given from, then, blocked and per. */
    private static WorkRule rule(
            final List<String> from,
            final @Nullable String then,
            final @Nullable String blocked,
            final WorkRule.Per per) {
        return new WorkRule(
                from,
                "Review",
                then,
                blocked,
                per,
                List.of("AGENT"),
                "Work {story}: {issues}; {tasks}",
                Map.of("codex", "model"));
    }

    private static Writes writes(final String issue, final String state) {
        return new Writes(Map.of(issue, state), Map.of(), List.of());
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
                f.run.choose(
                        "ST1", "AA1", 1, ReadingCycle.Choice.ONE_MORE_ROUND, "", revision, null);
        assertThat(chosen.tasks()).as("one extra reader is offered").hasSize(1);
        assertThatThrownBy(
                        () ->
                                f.run.choose(
                                        "ST1",
                                        "AA1",
                                        1,
                                        ReadingCycle.Choice.ONE_MORE_ROUND,
                                        "",
                                        revision,
                                        null))
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
        stories.add(
                new Story(
                        "ST2", "Other", "Backlog", false, "High", null, List.of("AA2"), "", null));
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
                    f.store.board().revision().value(),
                    null);
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
        final WorkRun.Offered current =
                f.order(new WorkRun.Start(f.run.work("ST1").id(), null)).tasks().getFirst();
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
    void anUnfinishedReadingNeverCountsOrBecomesFindings() {
        // Prevents failed work from being accepted as a completed review.
        for (final WorkRun.Ended end :
                List.of(
                        new WorkRun.Ended(
                                "2026-01-01T00:00:00Z", false, "Failed", "Findings:\n", 0))) {
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
            assertThat(progress.rounds().roundsMost() - progress.rounds().rounds())
                    .as(
                            "a failed or unpushed reading uses up none of the readings granted, one by the order")
                    .isEqualTo(1);
            assertThat(progress.needsYou()).as("failed delivery needs attention").isTrue();
            assertThat(answer.tasks()).as("no continuation follows a failed end").isEmpty();
        }
    }

    @Test
    void aBuildThatDeclaresItsWorkBlockedMovesNothingOffersNoReaderAndKeepsItsWhy() {
        // A task that said its work cannot go on, taken as done, moves its issue on with no work.
        final Fixture f = new Fixture();
        final var build = f.order().tasks().getFirst();
        f.start(build);
        final String before = f.store.board().issue("AA1").orElseThrow().state();
        final int saves = f.store.saves;
        final var answer = f.run.report("task", blocked(build, WorkEnds.Declared.BLOCKED));
        assertThat(answer.tasks()).as("a blocked end offers no reader").isEmpty();
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("a blocked end leaves the issue where it stands")
                .isEqualTo(before);
        assertThat(f.store.saves).as("a blocked end writes no state").isEqualTo(saves);
        final NextStep.Progress progress = answer.work().issues().get("AA1");
        assertThat(progress.needsYou()).as("a blocked end needs the person").isTrue();
        assertThat(progress.reason()).as("the issue is raised as blocked").isEqualTo("BLOCKED");
        assertThat(
                        answer.work().offers().stream()
                                .filter(o -> o.id().equals(build.offer()))
                                .findFirst()
                                .orElseThrow()
                                .ended())
                .as("the end keeps the task's why and its declared outcome")
                .extracting(WorkRun.Ended::why, WorkRun.Ended::outcome)
                .containsExactly("A key is missing", WorkEnds.Declared.BLOCKED);
    }

    @Test
    void aBlockedEndReplaysAsItselfAndAnEndWithAnotherOutcomeIsAConflict() {
        // An end replayed with another outcome taken as the same would hide which end counted.
        final Fixture f = new Fixture();
        final var build = f.order().tasks().getFirst();
        f.start(build);
        f.run.report("task", blocked(build, WorkEnds.Declared.BLOCKED));
        final var snapshot = f.record.value;
        assertThat(
                        new WorkRun(f.store, f.record, f.clock)
                                .report("task", blocked(build, WorkEnds.Declared.BLOCKED))
                                .tasks())
                .as("the same blocked end replays offering nothing")
                .isEmpty();
        assertThat(f.record.value).as("the replay records nothing new").isEqualTo(snapshot);
        for (final WorkEnds.@Nullable Declared other :
                java.util.Arrays.asList(WorkEnds.Declared.DONE, null)) {
            assertThatThrownBy(() -> f.run.report("task", blocked(build, other)))
                    .as("the end resent declaring %s", other)
                    .isInstanceOf(WorkRefusal.class)
                    .satisfies(
                            refused ->
                                    assertThat(conflictOf(refused))
                                            .as("another outcome is another end")
                                            .isEqualTo("TaskEventConflict"));
        }
        assertThat(f.record.value).as("the refused ends record nothing").isEqualTo(snapshot);
    }

    @Test
    void aLandingOfABuildThatDeclaredItsWorkBlockedOrFailedIsRefusedAndOffersNothing() {
        // A reader started on blocked work reads what its builder said is not there.
        for (final WorkEnds.Declared declared :
                List.of(WorkEnds.Declared.BLOCKED, WorkEnds.Declared.FAILED)) {
            final Fixture f = new Fixture();
            final var build = f.order().tasks().getFirst();
            f.start(build);
            f.run.report("task", blocked(build, declared));
            final var snapshot = f.record.value;
            assertThatThrownBy(() -> f.run.report("task", landed(build, "work/built")))
                    .as("the landing of a build that declared %s", declared)
                    .isInstanceOf(WorkRefusal.class)
                    .satisfies(
                            refused ->
                                    assertThat(conflictOf(refused))
                                            .as("it did not end done")
                                            .isEqualTo("TaskEventConflict"));
            assertThat(f.record.value)
                    .as("the refused landing records nothing")
                    .isEqualTo(snapshot);
        }
    }

    @Test
    void aReaderThatDeclaresItsWorkBlockedCountsNoReadingWhateverItsAnswerSays() {
        // A clean block from a reader that could not read would pass an issue nobody read.
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
                                new WorkRun.Ended(
                                        "2026-01-01T00:00:00Z",
                                        true,
                                        "The work is not there",
                                        "Findings: none",
                                        0,
                                        List.of(),
                                        WorkEnds.Declared.BLOCKED)));
        final NextStep.Progress progress = answer.work().issues().get("AA1");
        assertThat(progress.rounds().roundsMost() - progress.rounds().rounds())
                .as("a blocked reading uses up none of the readings granted, one by the order")
                .isEqualTo(1);
        assertThat(progress.reason()).as("the issue is raised as blocked").isEqualTo("BLOCKED");
        assertThat(answer.tasks()).as("nothing follows a blocked reading").isEmpty();
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the issue does not reach review")
                .isEqualTo("Reading");
    }

    /** A build's end that finished and pushed everything, declaring {@code declared}. */
    private static WorkRun.Report blocked(
            final WorkRun.Offered build, final WorkEnds.@Nullable Declared declared) {
        return new WorkRun.Report(
                build.offer(),
                WorkRun.Event.ENDED,
                "box",
                "workstation",
                new WorkRun.Ended(
                        "2026-01-01T00:00:00Z",
                        true,
                        "A key is missing",
                        "Built",
                        0,
                        List.of(),
                        declared));
    }

    @Test
    void aReadingStartedByHandWithAChangedEffortStartsEachReaderWithIt() {
        // A person's effort replaced by the rule's on the way to the readers it starts.
        final Fixture f = readInReview();
        final WorkProposal proposal = f.run.propose("ST1", "Reading", List.of("AA1"));
        final List<WorkProposal.Repository> chosen =
                proposal.repositories().stream()
                        .map(
                                repository -> {
                                    final WorkProposal.Main main = repository.main();
                                    return new WorkProposal.Repository(
                                            repository.repository(),
                                            repository.issues(),
                                            new WorkProposal.Main(
                                                    main.name(),
                                                    main.role(),
                                                    main.agent(),
                                                    main.model(),
                                                    main.brief(),
                                                    "low",
                                                    main.basis()),
                                            repository.subAgents());
                                })
                        .toList();
        final var order =
                f.run.order(
                        "ST1",
                        "Reading",
                        new WorkOrder.Decision(
                                "AGENT",
                                f.store.board().revision().value(),
                                chosen,
                                "",
                                null,
                                null,
                                List.of("AA1")));
        assertThat(order.tasks())
                .as("every reader starts with the effort the person chose, and used it")
                .extracting(o -> o.task().effort(), o -> o.task().used())
                .containsOnly(org.assertj.core.groups.Tuple.tuple("low", "low"));
    }

    @Test
    void aTaskStartedByItselfNamesItsRulesChoiceAndNoEffortUsed() {
        // An offered effort answered as the one a task used, which nobody confirmed.
        final Fixture f = new Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        final WorkRun.Offered reader = f.readers().getFirst();
        assertThat(reader.task().basis())
                .as("a reader started by itself names where its choice came from")
                .isEqualTo("Reading, risk ordinary");
        assertThat(reader.task().used()).as("nobody said which effort it used").isNull();
    }

    @Test
    void aReadingStartedByHandReadsTheLastBuildAndCountsOnFromTheRun() {
        // A reader started on nothing, or a reading that counted as a build, passes an issue to
        // Review nobody read.
        final Fixture f = readInReview();
        final var order = f.handReading();
        assertThat(order.tasks()).as("one reader for the issue").hasSize(1);
        final WorkOrder.Task reader = order.tasks().getFirst().task();
        assertThat(reader.role()).as("the task reads").isEqualTo("READER");
        assertThat(reader.reads()).as("it reads the issue's last build").containsExactly("task");
        assertThat(reader.prompt())
                .as("its order names where that build landed")
                .contains(readsWork("work/built"));
        f.run.report(
                "again",
                new WorkRun.Report(
                        order.tasks().getFirst().offer(),
                        WorkRun.Event.STARTED,
                        "box",
                        "workstation",
                        null));
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the started reading takes the issue to Reading")
                .isEqualTo("Reading");
        final var blocked =
                f.run.report(
                        "again",
                        new WorkRun.Report(
                                order.tasks().getFirst().offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Findings:\n1. blocking | - | - | Preserve data")));
        final NextStep.Progress progress = blocked.work().issues().get("AA1");
        assertThat(progress.rounds().rounds())
                .as("the reading counts on from the run's first")
                .isEqualTo(2);
        assertThat(progress.rounds().stopped()).as("blocking findings ask the person").isTrue();
        assertThat(blocked.tasks()).as("nothing starts by itself").isEmpty();
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the issue waits in Reading")
                .isEqualTo("Reading");
    }

    @Test
    void aCleanReadingStartedByHandLeadsToReview() {
        // A clean hand reading left in Reading holds an issue back that was found sound.
        final Fixture f = readInReview();
        final var offer = f.handReading().tasks().getFirst();
        f.run.report(
                "again",
                new WorkRun.Report(
                        offer.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        f.run.report(
                "again",
                new WorkRun.Report(
                        offer.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings: none")));
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the clean reading leads to Review")
                .isEqualTo("Review");
    }

    @Test
    void aReadingByHandOfAnIssueWithNoKnownBuildIsRefusedAndRecordsNothing() {
        // A reader with no build to read reads whatever stands and passes it.
        final Fixture f = new Fixture();
        f.store.current = handReadable(f.store.current, "Review");
        final var before = f.record.value;
        assertThatThrownBy(f::handReading)
                .as("no build of the issue is known")
                .isInstanceOf(WorkRefusal.class)
                .hasMessageContaining("No landed build");
        assertThat(f.record.value).as("the refused reading records nothing").isEqualTo(before);
    }

    @Test
    void anOrderIsRefusedWhileAnEndsFollowUpWaitsToStart() {
        // An order replacing the run drops a reader an end offered, and the issue is never read.
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
                        .tasks();
        assertThat(reader).as("the end offered a reader").hasSize(1);
        final var before = f.record.value;
        assertThatThrownBy(
                        () ->
                                f.run.order(
                                        "ST1",
                                        "Building",
                                        new WorkOrder.Decision(
                                                "AGENT",
                                                f.store.board().revision().value(),
                                                null,
                                                "",
                                                1,
                                                null)))
                .as("a new order while the reader waits to start")
                .isInstanceOf(WorkRefusal.class)
                .hasMessageContaining("waits to start");
        assertThat(f.record.value).as("the refused order records nothing").isEqualTo(before);
    }

    @Test
    void anOrderIsRefusedWhileWorkAPersonsChoiceOfferedWaitsToStart() {
        // An order replacing the run drops the rework a person chose, and the work leaves unread.
        final Fixture f = blockedHandReading();
        f.store.current =
                NextStepTest.withRule(
                        f.store.board(), "Reworking", NextStepTest.choices("Reading"));
        final var rework =
                f.run.choose(
                                "ST1",
                                "AA1",
                                2,
                                ReadingCycle.Choice.REWORK,
                                "",
                                f.store.board().revision().value(),
                                null)
                        .tasks();
        assertThat(rework).as("the choice offered a rework").hasSize(1);
        final var before = f.record.value;
        assertThatThrownBy(
                        () ->
                                f.run.order(
                                        "ST1",
                                        "Building",
                                        new WorkOrder.Decision(
                                                "AGENT",
                                                f.store.board().revision().value(),
                                                null,
                                                "",
                                                1,
                                                null)))
                .as("a new order while the chosen rework waits to start")
                .isInstanceOf(WorkRefusal.class)
                .hasMessageContaining("waits to start");
        assertThat(f.record.value).as("the refused order records nothing").isEqualTo(before);
    }

    @Test
    void anotherRoundOfAReadingStartedByHandReadsTheSameBuild() {
        // A round with no build to read is lost, and the issue stays stuck in Reading.
        final Fixture f = blockedHandReading();
        final var again =
                f.run.choose(
                        "ST1",
                        "AA1",
                        2,
                        ReadingCycle.Choice.ONE_MORE_ROUND,
                        "",
                        f.store.board().revision().value(),
                        null);
        assertThat(again.tasks()).as("one more reader is offered").hasSize(1);
        assertThat(again.tasks().getFirst().task().reads())
                .as("it reads the build the hand reading read")
                .containsExactly("task");
    }

    @Test
    void aChoiceThatCannotOfferItsTaskLeavesTheReadingStoppedForAnotherChoice() {
        // A choice lost to a task that cannot be made leaves the person nothing to choose again.
        final Fixture f = blockedHandReading();
        final Board blocked = f.store.board();
        f.store.current =
                NextStepTest.withRule(blocked, "Reworking", NextStepTest.choices("Backlog"));
        final var lost =
                f.run.choose(
                        "ST1",
                        "AA1",
                        2,
                        ReadingCycle.Choice.REWORK,
                        "",
                        f.store.board().revision().value(),
                        null);
        assertThat(lost.tasks()).as("no rework can be made from Reading").isEmpty();
        assertThat(lost.work().issues().get("AA1").rounds().stopped())
                .as("the reading still waits for the person's choice")
                .isTrue();
        assertThat(
                        f.run.choose(
                                        "ST1",
                                        "AA1",
                                        2,
                                        ReadingCycle.Choice.ONE_MORE_ROUND,
                                        "",
                                        f.store.board().revision().value(),
                                        null)
                                .tasks())
                .as("another choice is taken")
                .hasSize(1);
    }

    /** A reading started by hand of the issue in Review, ended with blocking findings. */
    private static Fixture blockedHandReading() {
        final Fixture f = readInReview();
        final var offer = f.handReading().tasks().getFirst();
        f.run.report(
                "again",
                new WorkRun.Report(
                        offer.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        final var blocked =
                f.run.report(
                        "again",
                        new WorkRun.Report(
                                offer.offer(),
                                WorkRun.Event.ENDED,
                                "box",
                                "workstation",
                                ended("Findings:\n1. blocking | - | - | Preserve data")));
        assertThat(blocked.work().issues().get("AA1").rounds().stopped())
                .as("the hand reading stopped for the person")
                .isTrue();
        return f;
    }

    /** A story whose issue was built where it landed, read clean once and now stands in Review. */
    private static Fixture readInReview() {
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
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        ended("Findings:\n")));
        assertThat(f.store.board().issue("AA1").orElseThrow().state())
                .as("the first reading reached Review")
                .isEqualTo("Review");
        f.store.current = handReadable(f.store.current, null);
        return f;
    }

    /** The board whose Reading rule also reads from Review, with AA1 in {@code state} if given. */
    private static Board handReadable(final Board board, final @Nullable String state) {
        final Map<String, WorkRule> rules = new java.util.LinkedHashMap<>(board.work().rules());
        final WorkRule reading = rules.get("Reading");
        rules.put(
                "Reading",
                new WorkRule(
                        List.of("Building", "Reworking", "Review"),
                        reading.done(),
                        reading.then(),
                        reading.blocked(),
                        reading.per(),
                        reading.modes(),
                        reading.order(),
                        reading.models()));
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                state == null
                        ? board.issues()
                        : WorkProposalTest.with(board, Map.of("AA1", state)).issues(),
                new Work(rules, board.work().faults()),
                board.repositories());
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

    /** An end whose work already landed, as Porelo reports it once its work left the gate. */
    static WorkRun.Ended ended(final String answer) {
        return new WorkRun.Ended(
                "2026-01-01T00:00:00Z", true, "", answer, 0, List.of(landing("work/built")));
    }

    /** An end whose work still waits at the gate. */
    static WorkRun.Ended unlanded(final String answer) {
        return new WorkRun.Ended("2026-01-01T00:00:00Z", true, "", answer, 0, List.of());
    }

    static WorkRun.Report landed(final WorkRun.Offered offer, final String branch) {
        return new WorkRun.Report(
                offer.offer(),
                WorkRun.Event.LANDED,
                "box",
                "workstation",
                null,
                List.of(landing(branch)));
    }

    /** A commit as Porelo names it on a task's row. */
    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    /** Where Porelo reports a task's work landed on {@code branch}. */
    static NextStep.Landing landing(final String branch) {
        return new NextStep.Landing(
                branch, "refs/landed/task/" + branch, "origin/landed/task/" + branch, COMMIT);
    }

    /** The order's words for the task's work landed on {@code branch}. */
    static String readsWork(final String branch) {
        return "the work of Porelo task task: commit "
                + COMMIT
                + " on origin/landed/task/"
                + branch
                + ", after `git fetch origin`";
    }

    @Test
    void aStartPassingOverASaveRefusalReplacesTheHaltedRunInOneWrite() {
        // A halted run nothing but a stop could start again leaves the row without its answer.
        final Fixture f = new Fixture();
        final WorkRun.Run halted = f.halted();
        final int writes = f.record.writes;

        final WorkRun.Order order =
                f.order(new WorkRun.Start(null, f.passOver(halted, "AA1", "SAVE_REFUSED")));

        final WorkRun.Run now = java.util.Objects.requireNonNull(f.run.work("ST1"));
        assertThat(order.tasks()).as("the new run offers its tasks").isNotEmpty();
        assertThat(now.id())
                .as("a new run stands in place of the halted one")
                .isNotEqualTo(halted.id());
        assertThat(now.halted()).as("the new run is not halted").isFalse();
        assertThat(f.record.writes - writes)
                .as("the halted run is replaced in one write under the lock")
                .isEqualTo(1);
    }

    @Test
    void aHaltedRunTakesNoStartPassingOverAnotherReason() {
        // A Start over a refused save nobody looked at would launch an agent past it.
        final Fixture f = new Fixture();
        final WorkRun.Run halted = f.halted();

        assertThatThrownBy(
                        () ->
                                f.order(
                                        new WorkRun.Start(
                                                null, f.passOver(halted, "AA1", "STOPPED"))))
                .as("a reason that is not the issue's is a conflict")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
        assertThat(f.run.work("ST1").id()).as("the halted run stands").isEqualTo(halted.id());
    }

    @Test
    void aStartPassingOverAStopStartsTheStoryAgain() {
        // Without it a stopped run's row could only be moved on, never started again.
        final Fixture f = new Fixture();
        f.order();
        final WorkRun.Run stopped = java.util.Objects.requireNonNull(f.run.stop("ST1"));

        final WorkRun.Order order =
                f.order(new WorkRun.Start(null, f.passOver(stopped, "AA1", "STOPPED")));

        assertThat(order.tasks()).as("the new run offers its tasks").isNotEmpty();
        assertThat(f.run.work("ST1").id()).as("a new run stands").isNotEqualTo(stopped.id());
    }

    @Test
    void aStartPassingOverAnotherRunsIdIsAConflict() {
        // Another window may have started and stopped again, back at the same reason.
        final Fixture f = new Fixture();
        f.order();
        final WorkRun.Run stopped = java.util.Objects.requireNonNull(f.run.stop("ST1"));
        final WorkRun.PassOver other =
                new WorkRun.PassOver(
                        "AA1",
                        "STOPPED",
                        f.store.board().revision().value(),
                        java.util.UUID.randomUUID().toString());

        assertThatThrownBy(() -> f.order(new WorkRun.Start(null, other)))
                .as("a pass-over of a run that no longer stands is a conflict")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
        assertThat(f.run.work("ST1").id()).as("the stopped run stands").isEqualTo(stopped.id());
    }

    @Test
    void aStartPassingOverAnOlderRevisionIsAConflict() {
        // The row the person answered no longer shows what stands.
        final Fixture f = new Fixture();
        f.order();
        final WorkRun.Run stopped = java.util.Objects.requireNonNull(f.run.stop("ST1"));
        final WorkRun.PassOver older =
                new WorkRun.PassOver("AA1", "STOPPED", "board@0", stopped.id());

        assertThatThrownBy(() -> f.order(new WorkRun.Start(null, older)))
                .as("the revision the row showed is checked")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("StaleRevision"));
    }

    @Test
    void aStartPassingOverOneIssuesReasonIsAConflictWhileAnotherIssueNeedsAttentionForAnother() {
        // One row's Start would otherwise pass over another row's findings nobody answered.
        final Fixture f = new Fixture();
        f.order();
        final WorkRun.Run stopped = java.util.Objects.requireNonNull(f.run.stop("ST1"));
        final Map<String, NextStep.Progress> issues =
                new java.util.LinkedHashMap<>(stopped.issues());
        issues.put("AA2", NextStep.Progress.start(1).attention("BLOCKING_FINDINGS"));
        final WorkRun.Run both =
                new WorkRun.Run(
                        stopped.id(),
                        stopped.story(),
                        stopped.to(),
                        stopped.mode(),
                        stopped.comment(),
                        stopped.coordinator(),
                        stopped.readingRounds(),
                        stopped.issueReadingRounds(),
                        stopped.repositories(),
                        stopped.since(),
                        stopped.offers(),
                        issues,
                        stopped.stopped(),
                        stopped.halted(),
                        stopped.pending());
        f.record.value = new WorkRun.Snapshot(f.record.value.generation(), Map.of("ST1", both));

        assertThatThrownBy(
                        () -> f.order(new WorkRun.Start(null, f.passOver(both, "AA1", "STOPPED"))))
                .as("another issue's other reason is not passed over")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
    }

    @Test
    void aStartPassingOverAStopIsRefusedWhileATaskIsStillAtWork() {
        // A second agent would work beside the first, which nobody has stopped yet.
        final Fixture f = new Fixture();
        final WorkRun.Offered offer = f.order().tasks().getFirst();
        f.start(offer);
        final WorkRun.Run stopped = java.util.Objects.requireNonNull(f.run.stop("ST1"));

        assertThatThrownBy(
                        () ->
                                f.order(
                                        new WorkRun.Start(
                                                null, f.passOver(stopped, "AA1", "STOPPED"))))
                .as("work still at work refuses the Start")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("WorkRunning"));
    }

    @Test
    void aStartPassingOverAFailedBuildStartsTheRunAgain() {
        // Harm: a builder that ran out of time leaves its row with no way on but a move by hand.
        final Fixture f = new Fixture();
        final WorkRun.Run failed = failedBuild(f, 0);

        assertThat(f.run.propose("ST1", "Building", List.of("AA1")).repositories())
                .as("the row's Start is proposed for the raised issue where it stands")
                .isNotEmpty();
        final WorkRun.Order order = again(f, f.passOver(failed, "AA1", "FAILED"));

        final WorkRun.Run now = java.util.Objects.requireNonNull(f.run.work("ST1"));
        assertThat(now.id()).as("a new run stands").isNotEqualTo(failed.id());
        assertThat(order.tasks())
                .as("the new run offers a builder for the issue the failed task covered")
                .extracting(o -> o.task().issues())
                .containsExactly(List.of("AA1"));
        assertThat(now.issues().get("AA1").needsYou())
                .as("the new run no longer raises the issue")
                .isFalse();
    }

    @Test
    void aStartPassingOverAFailedBuildIsAConflictWhileAnotherIssueHoldsAnotherReason() {
        // Harm: one row's Start passes over another row's findings nobody looked at.
        final Fixture f = new Fixture();
        final WorkRun.Run failed = failedBuild(f, 0);
        final Map<String, NextStep.Progress> issues =
                new java.util.LinkedHashMap<>(failed.issues());
        issues.put("AA2", NextStep.Progress.start(1).attention("BLOCKING_FINDINGS"));
        final WorkRun.Run both = withIssues(failed, issues, failed.offers());
        f.record.value = new WorkRun.Snapshot(f.record.value.generation(), Map.of("ST1", both));

        assertThatThrownBy(() -> again(f, f.passOver(both, "AA1", "FAILED")))
                .as("another issue's other reason is not passed over")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
        assertThat(f.run.work("ST1").id()).as("the failed run stands").isEqualTo(failed.id());
    }

    @Test
    void aStartPassingOverAFailedBuildIsRefusedWhileATaskIsStillAtWork() {
        // Harm: a second agent works beside one nobody has stopped.
        final Fixture f = new Fixture();
        final WorkRun.Run failed = failedBuild(f, 0);
        final WorkRun.Offer ended = failed.offers().getFirst();
        final WorkRun.Offer atWork =
                new WorkRun.Offer(
                        java.util.UUID.randomUUID().toString(),
                        ended.arguments(),
                        ended.binding(),
                        "STARTED",
                        "other",
                        "box",
                        "workstation",
                        ended.since(),
                        null,
                        "",
                        List.of(),
                        List.of(),
                        null);
        final WorkRun.Run working = withIssues(failed, failed.issues(), List.of(ended, atWork));
        f.record.value = new WorkRun.Snapshot(f.record.value.generation(), Map.of("ST1", working));

        assertThatThrownBy(() -> again(f, f.passOver(working, "AA1", "FAILED")))
                .as("work still at work refuses the Start")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("WorkRunning"));
        assertThat(f.run.work("ST1").id()).as("the failed run stands").isEqualTo(failed.id());
    }

    @Test
    void aStartPassingOverAFailedBuildThatLeftCommitsUnpushedIsRefused() {
        // Harm: a new builder starts beside commits only the failed task can push, and they are
        // lost.
        final Fixture f = new Fixture();
        final WorkRun.Run failed = failedBuild(f, 2);

        assertThatThrownBy(() -> again(f, f.passOver(failed, "AA1", "FAILED")))
                .as("unpushed work of the failed task refuses the Start")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("WorkNotAllowed"));
        assertThat(f.run.work("ST1").id()).as("the failed run stands").isEqualTo(failed.id());
    }

    @Test
    void aStartPassingOverTheStopOfAReaderThatLeftFilesUnpushedIsNotRefusedForThem() {
        // Harm: a reader's checked-out leftovers, no work of its own, block the person's Start.
        final Fixture f = new Fixture();
        final var reader = readerStarted(f);
        f.run.report(
                "reader",
                new WorkRun.Report(
                        reader.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "workstation",
                        new WorkRun.Ended(
                                "2026-01-01T00:00:00Z", true, "Read", "No block here.", 4)));
        final WorkRun.Run stopped = java.util.Objects.requireNonNull(f.run.stop("ST1"));

        final Throwable refused =
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> again(f, f.passOver(stopped, "AA1", "STOPPED")));
        assertThat(refused == null ? "" : String.valueOf(refused.getMessage()))
                .as("the Start is not refused for the reader's leftovers")
                .doesNotContain("unpushed");
    }

    @Test
    void aStartPassingOverTheStopOfAFailedBuildThatLeftCommitsUnpushedIsRefused() {
        // Harm: a stop renames the failure, and a new builder starts beside commits only the failed
        // task can push, which are lost.
        final Fixture f = new Fixture();
        failedBuild(f, 2);
        final WorkRun.Run stopped = java.util.Objects.requireNonNull(f.run.stop("ST1"));
        assertThat(stopped.issues().get("AA1").reason())
                .as("the stop renamed the failure")
                .isEqualTo("STOPPED");

        assertThatThrownBy(() -> again(f, f.passOver(stopped, "AA1", "STOPPED")))
                .as("unpushed work of an ended task refuses a Start over any reason")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("WorkNotAllowed"));
        assertThat(f.run.work("ST1").id()).as("the stopped run stands").isEqualTo(stopped.id());
    }

    @Test
    void aStartWithoutAPassOverNamingAnIssueWhereItStandsIsRefusedByTheRule() {
        // Harm: a builder starts on an issue a person moved into the working state by hand.
        final Fixture f = new Fixture();
        final WorkRun.Run failed = failedBuild(f, 0);
        f.record.value =
                new WorkRun.Snapshot(
                        f.record.value.generation(),
                        Map.of(
                                "ST1",
                                withIssues(
                                        failed,
                                        Map.of("AA1", NextStep.Progress.start(1)),
                                        failed.offers())));

        assertThatThrownBy(() -> naming(f, null, List.of("AA1")))
                .as("only a raised row's Start works an issue again where it stands")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("WorkNotAllowed"));
        assertThatThrownBy(() -> f.run.propose("ST1", "Building", List.of("AA1")))
                .as("nor is it proposed")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("WorkNotAllowed"));
    }

    @Test
    void aStartPassingOverOneIssueNamingAnotherThatIsNotRaisedIsRefusedByTheRule() {
        // Harm: one row's Start works another issue nobody raised, where a person put it.
        final Fixture f = new Fixture();
        final WorkRun.Run failed = failedBuild(f, 0);
        final Board before = f.store.board();
        f.store.current =
                new Board(
                        before.revision(),
                        before.workflow(),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story",
                                        "Building",
                                        false,
                                        "High",
                                        null,
                                        List.of("AA1", "AA2"),
                                        "",
                                        null)),
                        List.of(
                                before.issue("AA1").orElseThrow(),
                                new Issue(
                                        "AA2",
                                        "alpha",
                                        "Other",
                                        "Building",
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        "")),
                        before.work(),
                        before.repositories());

        assertThatThrownBy(() -> naming(f, f.passOver(failed, "AA1", "FAILED"), List.of("AA2")))
                .as("the issue named is not one the run raised")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("WorkNotAllowed"));
        assertThat(f.run.work("ST1").id()).as("the failed run stands").isEqualTo(failed.id());
    }

    @Test
    void aStartPassingOverABlockedBuildIsRefused() {
        // Harm: an agent started again over a block the task named, which nothing has lifted.
        final Fixture f = new Fixture();
        final var build = f.order().tasks().getFirst();
        f.start(build);
        final WorkRun.Run blocked =
                f.run.report("task", blocked(build, WorkEnds.Declared.BLOCKED)).work();
        assertThat(blocked.issues().get("AA1").reason())
                .as("the blocked task raised the issue")
                .isEqualTo("BLOCKED");

        assertThatThrownBy(() -> again(f, f.passOver(blocked, "AA1", "BLOCKED")))
                .as("a block is never passed over")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("InvalidSettings"));
        assertThat(f.run.work("ST1").id()).as("the blocked run stands").isEqualTo(blocked.id());
    }

    @Test
    void aReaderEndingFailedOrBlockedStopsItsReadingForReadAgainAndNeverForAStart() {
        // Harm: a builder started for an issue standing in a reading, or a reading nobody can
        // read again.
        for (final WorkEnds.Declared declared :
                List.of(WorkEnds.Declared.FAILED, WorkEnds.Declared.BLOCKED)) {
            final Fixture f = new Fixture();
            final var reader = readerStarted(f);
            final var ended =
                    f.run.report(
                            "reader",
                            new WorkRun.Report(
                                    reader.offer(),
                                    WorkRun.Event.ENDED,
                                    "box",
                                    "workstation",
                                    new WorkRun.Ended(
                                            "2026-01-01T00:00:00Z",
                                            true,
                                            "Could not read",
                                            "Findings:\n1. blocking | - | - | Broken",
                                            0,
                                            List.of(),
                                            declared)));
            final NextStep.Progress progress = ended.work().issues().get("AA1");
            assertThat(progress.rounds().stopped()).as("%s stops the reading", declared).isTrue();
            assertThat(progress.rounds().rounds()).as("%s counts a round", declared).isEqualTo(1);
            assertThat(progress.findings()).as("%s keeps no findings", declared).isEmpty();
            assertThat(progress.reason())
                    .as("%s stays the reason", declared)
                    .isEqualTo(declared.name());
            assertThat(ended.tasks()).as("nothing starts by itself after %s", declared).isEmpty();

            final WorkRun.Run stopped = ended.work();
            final String revision = f.store.board().revision().value();
            if (declared == WorkEnds.Declared.FAILED) {
                assertThatThrownBy(() -> again(f, f.passOver(stopped, "AA1", "FAILED")))
                        .as("a Start over a reader's failure starts no builder")
                        .satisfies(
                                e ->
                                        assertThat(conflictOf(e))
                                                .as("named")
                                                .isEqualTo("WorkNotAllowed"));
            }
            assertThatThrownBy(
                            () ->
                                    f.run.choose(
                                            "ST1",
                                            "AA1",
                                            1,
                                            ReadingCycle.Choice.REWORK,
                                            "Fix it anyway.",
                                            revision,
                                            null))
                    .as("a rework after %s has nothing to work from", declared)
                    .satisfies(
                            e ->
                                    assertThat(conflictOf(e))
                                            .as("named")
                                            .isEqualTo("ReworkNeedsFindings"));
            final var again =
                    f.run.choose(
                            "ST1",
                            "AA1",
                            1,
                            ReadingCycle.Choice.ONE_MORE_ROUND,
                            "",
                            revision,
                            null);
            assertThat(again.tasks())
                    .as("Read again after %s offers one reader", declared)
                    .singleElement()
                    .satisfies(
                            o ->
                                    assertThat(o.task().reads())
                                            .as("it reads the same build")
                                            .containsExactly("task"));
        }
    }

    /** A Start from AA1's raised row: it names the issue and passes over its reason. */
    private static WorkRun.Order again(final Fixture f, final WorkRun.PassOver passOver) {
        return naming(f, passOver, List.of("AA1"));
    }

    /** A Start naming {@code issues}, passing over {@code passOver}, or nothing when null. */
    private static WorkRun.Order naming(
            final Fixture f, final WorkRun.@Nullable PassOver passOver, final List<String> issues) {
        return f.run.order(
                "ST1",
                "Building",
                new WorkOrder.Decision(
                        "AGENT", f.store.board().revision().value(), null, "", 1, null, issues),
                new WorkRun.Start(null, passOver));
    }

    /** Builds AA1 and lets its builder run out of time, leaving {@code unpushed} commits. */
    private static WorkRun.Run failedBuild(final Fixture f, final int unpushed) {
        final var build = f.order().tasks().getFirst();
        f.start(build);
        final WorkRun.Run failed =
                f.run.report(
                                "task",
                                new WorkRun.Report(
                                        build.offer(),
                                        WorkRun.Event.ENDED,
                                        "box",
                                        "workstation",
                                        new WorkRun.Ended(
                                                "2026-01-01T00:00:00Z",
                                                false,
                                                "Out of time",
                                                "",
                                                unpushed)))
                        .work();
        assertThat(failed.issues().get("AA1").reason())
                .as("the failed task raised the issue")
                .isEqualTo("FAILED");
        return failed;
    }

    /** The run with other issues' progress and offers. */
    private static WorkRun.Run withIssues(
            final WorkRun.Run run,
            final Map<String, NextStep.Progress> issues,
            final List<WorkRun.Offer> offers) {
        return new WorkRun.Run(
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
                offers,
                issues,
                run.stopped(),
                run.halted(),
                run.pending());
    }

    @Test
    void aRepeatedStartIsRefusedWhileOffersAreUnstartedUnlessItNamesTheirRun() {
        // A replayed Start would get a second set of offers to launch beside the first.
        final Fixture f = new Fixture();
        f.order();
        final String first = f.run.work("ST1").id();

        assertThatThrownBy(f::order)
                .as("a Start naming no run is refused while offers wait")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
        assertThatThrownBy(
                        () ->
                                f.order(
                                        new WorkRun.Start(
                                                java.util.UUID.randomUUID().toString(), null)))
                .as("a Start naming another run is refused")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
        assertThat(f.run.work("ST1").id()).as("the first run stands").isEqualTo(first);
        assertThat(f.order(new WorkRun.Start(first, null)).tasks())
                .as("a Start naming the run standing replaces its offers")
                .isNotEmpty();
    }

    @Test
    void aStopNamingAnotherRunIsAConflictAndStopsNothing() {
        // A stop meant for an older run would end work the person never saw.
        final Fixture f = new Fixture();
        f.order();

        assertThatThrownBy(() -> f.run.stop("ST1", java.util.UUID.randomUUID().toString()))
                .as("a stop of a run that does not stand is a conflict")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
        assertThat(f.run.work("ST1").stopped()).as("the run standing goes on").isFalse();
        assertThat(f.run.stop("ST1", f.run.work("ST1").id()).stopped())
                .as("a stop naming the run standing stops it")
                .isTrue();
    }

    @Test
    void aStartPassingOverNothingIsAConflictOverAStopNobodyAnswered() {
        // A replayed Start would start an agent again over a stop nobody looked at.
        final Fixture f = new Fixture();
        f.order();
        final WorkRun.Run stopped = java.util.Objects.requireNonNull(f.run.stop("ST1"));

        assertThatThrownBy(f::order)
                .as("a Start naming no reason is refused over the stop")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
        assertThatThrownBy(() -> f.order(new WorkRun.Start(stopped.id(), null)))
                .as("naming the stopped run it replaces passes over no reason either")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
        assertThat(f.run.work("ST1").id()).as("the stopped run stands").isEqualTo(stopped.id());
    }

    @Test
    void aStartReplacingAnotherRunIsAConflictOverAHaltedRun() {
        // The window reads a run that changed as a conflict and reloads, never as a refused move.
        final Fixture f = new Fixture();
        final WorkRun.Run halted = f.halted();

        assertThatThrownBy(
                        () ->
                                f.order(
                                        new WorkRun.Start(
                                                java.util.UUID.randomUUID().toString(), null)))
                .as("another run's id is a conflict before the halt is judged")
                .satisfies(e -> assertThat(conflictOf(e)).as("named").isEqualTo("RunChanged"));
        assertThat(f.run.work("ST1").id()).as("the halted run stands").isEqualTo(halted.id());
    }

    @Test
    void aHandMoveWhoseSaveStandsClearsAHaltedRun() {
        // The refused save is moot once the person's own stands; a halt kept would hold the story.
        final Fixture f = new Fixture();
        f.halted();

        moved(
                f.run,
                List.of(new Moves.Move(false, "AA1", "Review")),
                () -> f.store.save(f.store.board().revision(), writes("AA1", "Review")));

        assertThat(f.run.work("ST1").halted()).as("the run is no longer halted").isFalse();
    }

    @Test
    void aSaveConfirmedForAnotherRepositoryIsRefusedAndWritesNothing() {
        // Harm: a move written to a repository it was never shown for, after a rebind.
        final Fixture f = new Fixture();
        final List<Moves.Move> moves = List.of(new Moves.Move(false, "AA1", "Backlog"));
        final MovePreview preview = f.run.preview(moves, f.store.board().revision().value());
        final var saved = new java.util.concurrent.atomic.AtomicBoolean();

        final Throwable refused =
                catchThrowable(
                        () ->
                                f.run.moves(
                                        moves,
                                        preview.revision(),
                                        preview.digest("https://example.org/old.git", null),
                                        false,
                                        "https://example.org/new.git",
                                        null,
                                        () -> saved.getAndSet(true)));

        assertThat(refused).as("refused").isInstanceOf(WorkRefusal.class);
        assertThat(conflictOf(refused)).isEqualTo("PreviewChanged");
        assertThat(saved).as("nothing reaches the store").isFalse();
    }

    @Test
    void aSaveWhoseRunningTasksChangedSinceItsPreviewIsRefusedAndWritesNothing() {
        // Harm: a move confirmed over the work it showed, saved over a task started since.
        final Fixture f = new Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        final List<WorkRun.Offered> tasks = f.order().tasks();
        final WorkRun.Offered main = role(tasks, "MAIN");
        final WorkRun.Offered child = role(tasks, "BUILDER");
        f.start(main);
        startChild(f, child);
        final List<Moves.Move> moves = List.of(new Moves.Move(true, "ST1", "Backlog"));
        final String revision = f.store.board().revision().value();
        final MovePreview preview = f.run.preview(moves, revision);
        assertThat(preview.warnings().getFirst().tasks()).as("the tasks at work shown").hasSize(2);
        endMain(f, main);
        assertThat(f.store.board().revision().value())
                .as("only the running work changed, not the board")
                .isEqualTo(revision);
        final var saved = new java.util.concurrent.atomic.AtomicBoolean();

        final Throwable refused =
                catchThrowable(
                        () ->
                                f.run.moves(
                                        moves,
                                        revision,
                                        preview.digest("https://example.org/own.git", null),
                                        false,
                                        "https://example.org/own.git",
                                        null,
                                        () -> saved.getAndSet(true)));

        assertThat(refused).as("refused").isInstanceOf(WorkRefusal.class);
        assertThat(conflictOf(refused)).isEqualTo("PreviewChanged");
        assertThat(saved).as("nothing reaches the store").isFalse();
    }

    @Test
    void aConfirmedSaveWhoseRunningTasksChangedSinceItsPreviewStopsNothing() {
        // Harm: the move's stop ends a task the person was never shown, started while the
        // confirmation stood open.
        final Fixture f = new Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        final List<WorkRun.Offered> tasks = f.order().tasks();
        final WorkRun.Offered main = role(tasks, "MAIN");
        final WorkRun.Offered child = role(tasks, "BUILDER");
        f.start(main);
        startChild(f, child);
        final List<Moves.Move> moves = List.of(new Moves.Move(true, "ST1", "Backlog"));
        final String revision = f.store.board().revision().value();
        final MovePreview preview = f.run.preview(moves, revision);
        endMain(f, main);
        assertThat(f.store.board().revision().value()).as("the board as shown").isEqualTo(revision);
        final var saved = new java.util.concurrent.atomic.AtomicBoolean();

        final Throwable refused =
                catchThrowable(
                        () ->
                                f.run.moves(
                                        moves,
                                        revision,
                                        preview.digest("https://example.org/own.git", null),
                                        true,
                                        "https://example.org/own.git",
                                        null,
                                        () -> saved.getAndSet(true)));

        assertThat(refused).as("refused").isInstanceOf(WorkRefusal.class);
        assertThat(conflictOf(refused)).isEqualTo("PreviewChanged");
        assertThat(saved).as("nothing reaches the store").isFalse();
        assertThat(java.util.Objects.requireNonNull(f.run.work("ST1")).stopped())
                .as("the run goes on")
                .isFalse();
        assertThat(f.active("ST1")).as("the task still at work goes on").hasSize(1);
    }

    @Test
    void aConfirmedSaveOverRunningWorkStopsItsRunAndNamesTheTasksToEnd() {
        // Harm: agents left working on a story that moved on, or a stop the window cannot end
        // because it does not know which tasks it stopped.
        final Fixture f = new Fixture();
        final WorkRun.Offered main = role(f.order().tasks(), "MAIN");
        f.start(main);
        final List<Moves.Move> moves = List.of(new Moves.Move(true, "ST1", "Backlog"));
        final MovePreview preview = f.run.preview(moves, f.store.board().revision().value());
        final var saved = new java.util.concurrent.atomic.AtomicBoolean();

        final Throwable unconfirmed =
                catchThrowable(
                        () ->
                                f.run.moves(
                                        moves,
                                        preview.revision(),
                                        preview.digest("https://example.org/own.git", null),
                                        false,
                                        "https://example.org/own.git",
                                        null,
                                        () -> saved.getAndSet(true)));
        assertThat(conflictOf(unconfirmed)).as("unconfirmed").isEqualTo("WorkRunning");
        assertThat(java.util.Objects.requireNonNull(f.run.work("ST1")).stopped())
                .as("an unconfirmed save stops nothing")
                .isFalse();

        final WorkRun.MoveSaved<Boolean> answer =
                f.run.moves(
                        moves,
                        preview.revision(),
                        preview.digest("https://example.org/own.git", null),
                        true,
                        "https://example.org/own.git",
                        null,
                        () -> saved.getAndSet(true));

        assertThat(saved).as("the move is saved").isTrue();
        final WorkRun.Run run = java.util.Objects.requireNonNull(f.run.work("ST1"));
        assertThat(run.stopped()).as("the run is recorded stopped with the save").isTrue();
        assertThat(answer.stopped())
                .as("the run stopped and the task the window must end, with its container")
                .containsExactly(
                        new WorkRun.StoppedRun(
                                "ST1", run.id(), List.of(new WorkRun.StoppedTask("task", "box"))));
    }

    @Test
    void anIssueMoveInAStoryWhoseRunHasAStartedTaskIsRefusedAsWorkRunning() {
        // Harm: work moved under a running agent, through an issue its task was not given.
        final Fixture f = new Fixture();
        f.start(role(f.order().tasks(), "MAIN"));
        withSecondIssueAndOtherStory(f);
        final List<Moves.Move> moves = List.of(new Moves.Move(false, "AA2", "Review"));
        final MovePreview preview = f.run.preview(moves, f.store.board().revision().value());
        assertThat(preview.warnings())
                .as("the story's running work is shown before the save")
                .extracting(MovePreview.Warning::kind, MovePreview.Warning::item)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("RUNNING_WORK", "ST1"));
        final var saved = new java.util.concurrent.atomic.AtomicBoolean();

        final Throwable refused =
                catchThrowable(
                        () ->
                                f.run.moves(
                                        moves,
                                        preview.revision(),
                                        preview.digest("https://example.org/own.git", null),
                                        false,
                                        "https://example.org/own.git",
                                        null,
                                        () -> saved.getAndSet(true)));

        assertThat(refused).as("refused").isInstanceOf(WorkRefusal.class);
        assertThat(conflictOf(refused)).as("as running work").isEqualTo("WorkRunning");
        assertThat(saved).as("nothing reaches the store").isFalse();
    }

    @Test
    void anIssueMoveInAStoryWithoutARunIsSaved() {
        // Harm: a run of one story holding back the issues of every other story.
        final Fixture f = new Fixture();
        f.start(role(f.order().tasks(), "MAIN"));
        withSecondIssueAndOtherStory(f);
        final var saved = new java.util.concurrent.atomic.AtomicBoolean();

        moved(f.run, List.of(new Moves.Move(false, "AA3", "Review")), () -> saved.getAndSet(true));

        assertThat(saved).as("the move is saved").isTrue();
    }

    /** ST1 gains AA2, which its started task was not given, and ST2 without a run holds AA3. */
    private static void withSecondIssueAndOtherStory(final Fixture f) {
        final Board before = f.store.board();
        f.store.current =
                new Board(
                        before.revision(),
                        before.workflow(),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story",
                                        "Building",
                                        false,
                                        "High",
                                        null,
                                        List.of("AA1", "AA2"),
                                        "",
                                        null),
                                new Story(
                                        "ST2",
                                        "Other",
                                        "Backlog",
                                        false,
                                        "High",
                                        null,
                                        List.of("AA3"),
                                        "",
                                        null)),
                        List.of(
                                before.issue("AA1").orElseThrow(),
                                new Issue(
                                        "AA2", "alpha", "Second", "Backlog", null, List.of(), null,
                                        null, ""),
                                new Issue(
                                        "AA3", "alpha", "Other", "Backlog", null, List.of(), null,
                                        null, "")),
                        before.work(),
                        before.repositories());
    }

    private static WorkRun.Offered role(final List<WorkRun.Offered> tasks, final String role) {
        return tasks.stream().filter(o -> o.task().role().equals(role)).findFirst().orElseThrow();
    }

    private static void endMain(final Fixture f, final WorkRun.Offered main) {
        f.run.report(
                "task",
                new WorkRun.Report(
                        main.offer(), WorkRun.Event.ENDED, "box", "workstation", ended("Built")));
    }

    private static void startChild(final Fixture f, final WorkRun.Offered child) {
        f.run.report(
                "child",
                new WorkRun.Report(
                        child.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
    }

    @Test
    void aSaveOnABoardThatMovedSinceItsPreviewAnswersStaleRevision() {
        // Harm: a board changed under a confirmation read as a changed preview, so the window
        // would not read the board again before asking anew.
        final Fixture f = new Fixture();
        final List<Moves.Move> moves = List.of(new Moves.Move(false, "AA1", "Backlog"));
        final MovePreview preview = f.run.preview(moves, f.store.board().revision().value());
        final var saved = new java.util.concurrent.atomic.AtomicBoolean();

        final Throwable refused =
                catchThrowable(
                        () ->
                                f.run.moves(
                                        moves,
                                        "another",
                                        preview.digest("https://example.org/own.git", null),
                                        false,
                                        "https://example.org/own.git",
                                        null,
                                        () -> saved.getAndSet(true)));

        assertThat(refused).isInstanceOf(StoreException.StaleRevision.class);
        assertThat(saved).as("nothing reaches the store").isFalse();
    }

    /** Moves as the window does: the preview first, then the save with its digest. */
    static <T> T moved(
            final WorkRun run,
            final List<Moves.Move> moves,
            final java.util.function.Supplier<T> save) {
        final MovePreview preview =
                run.preview(moves, run.view((board, work) -> board.revision().value()));
        return run.moves(
                        moves,
                        preview.revision(),
                        preview.digest("https://example.org/own.git", null),
                        false,
                        "https://example.org/own.git",
                        null,
                        save)
                .saved();
    }

    static String conflictOf(final Throwable refused) {
        return ((WorkRefusal) refused).name();
    }

    static final class Memory implements WorkRun.Record {
        WorkRun.Snapshot value = new WorkRun.Snapshot(0, Map.of());
        boolean refuseReaders;
        boolean held;
        int writes;
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
                            writes++;
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

        /** The orders a fixture offered on its way, as the window was given them. */
        final List<WorkOrder.Task> offered = new java.util.ArrayList<>();

        WorkRun.Order order() {
            return order(WorkRun.Start.NONE);
        }

        WorkRun.Order order(final WorkRun.Start start) {
            return run.order(
                    "ST1",
                    "Building",
                    new WorkOrder.Decision(
                            "AGENT", store.board().revision().value(), null, "", 1, null),
                    start);
        }

        /** The story's run halted by a refused save of its task's start, the task since ended. */
        WorkRun.Run halted() {
            final WorkRun.Offered offer = order().tasks().getFirst();
            store.failure = new StoreException.Busy("alpha");
            assertThatThrownBy(() -> start(offer))
                    .as("the start's save is refused")
                    .isInstanceOf(StoreException.Busy.class);
            store.failure = null;
            run.report(
                    "task",
                    new WorkRun.Report(
                            offer.offer(),
                            WorkRun.Event.ENDED,
                            "box",
                            "workstation",
                            ended("Built")));
            final WorkRun.Run halted = java.util.Objects.requireNonNull(run.work("ST1"));
            assertThat(halted.halted()).as("the refused save halts the run").isTrue();
            assertThat(active("ST1")).as("no task of the halted run is at work").isEmpty();
            return halted;
        }

        List<String> active(final String story) {
            return run.active(story);
        }

        WorkRun.PassOver passOver(final WorkRun.Run of, final String issue, final String reason) {
            return new WorkRun.PassOver(issue, reason, store.board().revision().value(), of.id());
        }

        WorkRun.Order handReading() {
            return run.order(
                    "ST1",
                    "Reading",
                    new WorkOrder.Decision(
                            "AGENT",
                            store.board().revision().value(),
                            null,
                            "",
                            null,
                            null,
                            List.of("AA1")));
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
                                                    i.worker(),
                                                    i.risk()))
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
                                                    null,
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
