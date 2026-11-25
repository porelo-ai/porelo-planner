package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Dependencies;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.junit.jupiter.api.Test;

/** A reported idea is saved once per task name and number, whatever happens between two reads. */
class HandedIdeasTest {

    private static final String OFFER = ReportTakingTest.OFFER;

    private static final String TASK = ReportTakingTest.TASK;

    private static final String KEY = TASK + "#1";

    private final Store store = new Store();

    private final WorkRunTest.Memory record = new WorkRunTest.Memory();

    private final ReportTakingTest.Porelo porelo = new ReportTakingTest.Porelo();

    private WorkRun run = new WorkRun(store, record, Clock.systemUTC());

    /** One discussion, followed, whose task reported one idea. */
    private void reported() {
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, false);
        porelo.report(TASK, "idea", "good");
    }

    private ReportTaking.Outcome take() {
        return run.takeReports(ReportTakingTest.PROJECT, porelo, porelo, ReportTakingTest::read);
    }

    @Test
    void aSavedReportReadAgainAnswersNothingNewAndSavesNothing() {
        // A report read again after a lost mark would make a second story.
        reported();
        take();
        // The mark lost, the key kept: the report is read again.
        record.value = ReportTaking.marked(record.value, "porelo-proj_x", 1);
        final java.util.Map<String, ReportTaking.Mark> marks =
                new java.util.HashMap<>(record.value.reports());
        marks.remove(TASK);
        record.value =
                record.value.withReading(
                        record.value.generation() + 1, record.value.followed(), marks);

        final ReportTaking.Outcome again = take();

        assertThat(again.stories()).as("the story it was saved as").containsExactly(KEY + " ST1");
        assertThat(store.saved).as("one save for the report").hasSize(1);
        assertThat(record.value.ideas().get(KEY).status())
                .as("the report is recorded as done")
                .isEqualTo(HandedIdeas.DONE);
    }

    @Test
    void theReportIsMarkedTakenInTheSameWriteThatRecordsItsStory() {
        // Harm: a planner stopped between the two writes reads the report again.
        reported();
        store.during = () -> String.valueOf(record.value.reports().get(TASK));
        final int before = record.writes;

        take();

        assertThat(store.seen).as("not yet taken while the store saves").containsExactly("null");
        assertThat(record.writes - before)
                .as(
                        "one write before the save, one after it with the mark, and one keeping"
                                + " the name Porelo recorded the discussion's task under")
                .isEqualTo(3);
        assertThat(record.value.reports().get(TASK).seq()).isEqualTo(1);
    }

    @Test
    void aPartlySavedReportIsKeptAndTakenAsRefusedNamingThePushedRepositories() {
        // A retry after a partial push would put the pushed issues in their repositories twice.
        reported();
        store.failures.add(
                new StoreException.PartlySaved(
                        "porelo-project", List.of("porelo-other"), "refused"));

        final ReportTaking.Outcome first = take();
        final ReportTaking.Outcome second = take();

        assertThat(first.unread()).as("the partial save said, not taken").hasSize(1);
        assertThat(second.refused())
                .as("the second read refused and taken")
                .singleElement()
                .asString()
                .startsWith(KEY + ": The idea of this report was saved only in part")
                .contains("porelo-other");
        assertThat(store.attempts).as("no second save is tried").isEqualTo(1);
        assertThat(record.value.reports().get(TASK).seq()).isEqualTo(1);
    }

    @Test
    void aReportLeftPendingByAStoppedPlannerIsTakenAsRefusedWithoutASave() {
        // A planner stopped mid-save may have pushed; saving again could make a second story.
        run.discussed(OFFER);
        record.value =
                record.value.withIdeas(
                        record.value.generation() + 1,
                        Map.of(
                                KEY,
                                new HandedIdeas.Handed(HandedIdeas.PENDING, null, List.of(), 3)));
        porelo.task(TASK, OFFER, false);
        porelo.report(TASK, "idea", "good");

        final ReportTaking.Outcome outcome = take();

        assertThat(outcome.refused()).as("refused and taken").hasSize(1);
        assertThat(store.attempts).as("no save is tried").isZero();
    }

    @Test
    void anIdeaIsSavedOnABoardAnotherSaveMovedWithoutReadingItWhole() {
        // Harm: every idea taken after another save reads the whole project inside the work
        // record's lock, or is refused though it changes no item held.
        store.moved = true;
        reported();

        assertThat(take().stories()).as("saved on the first try").containsExactly(KEY + " ST1");
        assertThat(store.refreshes).as("no whole read").isZero();
        assertThat(store.attempts).as("one try").isEqualTo(1);
        assertThat(store.bases)
                .as("saved against no item's version, since it changes none")
                .containsExactly(Basis.none());
    }

    @Test
    void anIdeaHeldInTheQueueStaysPendingAndDoesNotBlockTheNextReport() {
        // Harm: one idea held for the owner account is saved again, or makes every later idea's
        // save fail, since its command's id is taken in the outbox.
        final java.util.Set<String> kept = new java.util.HashSet<>();
        final org.fuin.porelo.planner.store.DirectQueue direct =
                new org.fuin.porelo.planner.store.DirectQueue(store);
        final org.fuin.porelo.planner.store.CommandQueue queue =
                group -> {
                    for (final org.fuin.porelo.planner.store.Command command : group.commands()) {
                        if (!kept.add(command.id())) {
                            throw new IllegalArgumentException(
                                    "The outbox holds command " + command.id() + " already.");
                        }
                    }
                    return kept.size() == 1
                            ? new org.fuin.porelo.planner.store.CommandOutcome.Held(
                                    List.of(), group.commands(), "The project stayed busy.")
                            : direct.submit(group);
                };
        run = new WorkRun(store, record, Clock.systemUTC(), why -> {}, queue);
        reported();

        assertThat(take().unread()).as("the first idea is held, not taken").hasSize(1);
        assertThat(record.value.ideas().get(KEY).status())
                .as("pending while its group is held")
                .isEqualTo(HandedIdeas.PENDING);
        porelo.report(TASK, "idea", "good");
        final ReportTaking.Outcome next = take();

        assertThat(next.refused()).as("the held one taken as refused").hasSize(1);
        assertThat(next.stories()).as("the later idea saved").containsExactly(TASK + "#2 ST1");
    }

    @Test
    void aStoreThatCannotWriteTheIdeaIsTakenAsRefused() {
        // Harm: an idea the store refuses for good read again every minute, blocking the task's
        // later reports.
        store.failures.add(new StoreException.NotSavable("", "no prefix"));
        reported();

        final ReportTaking.Outcome outcome = take();

        assertThat(outcome.refused()).as("refused and taken").hasSize(1);
        assertThat(record.value.ideas()).as("its key is not kept").isEmpty();
        assertThat(record.value.reports().get(TASK).seq()).isEqualTo(1);
    }

    @Test
    void aRefusalThatMayHaveWrittenKeepsTheReportPendingSoAReadAgainMakesNoSecondIdea() {
        // Harm: a report read again saves the idea once more after a save whose story may stand.
        store.failures.add(new StoreException.Unreachable("alpha", "timed out"));
        reported();

        assertThat(take().unread()).as("not taken").hasSize(1);
        assertThat(record.value.ideas().get(KEY).status()).isEqualTo("PENDING");
        assertThat(take().refused()).as("read again, refused and taken").hasSize(1);
        assertThat(store.attempts).as("the store asked once").isEqualTo(1);
    }

    @Test
    void anIdeaItsOwnCheckRefusesWhereTheStoreSavesIsTakenAsRefused() {
        // Harm: an idea refused by a check that runs before the store writes anything is read
        // again forever.
        store.failures.add(
                WorkRefusal.ideaRefused("The idea is refused.", List.of("title: taken"), null));
        reported();

        final ReportTaking.Outcome outcome = take();

        assertThat(outcome.refused())
                .as("said with its fault")
                .singleElement()
                .asString()
                .contains("title: taken");
        assertThat(record.value.ideas()).as("its key is not kept").isEmpty();
    }

    @Test
    void theReportIsRecordedAsPendingBeforeTheStoreSaves() {
        // A save that is not preceded by its record cannot be told from a fresh report after a
        // stop.
        store.during = () -> record.value.ideas().get(KEY).status();
        reported();

        take();

        assertThat(store.seen).as("the record during the save").containsExactly("PENDING");
    }

    @Test
    void aKeyIsATaskNameAndANumber() {
        // A key of another form would be kept under a key no check of the record accepts.
        assertThat(HandedIdeas.key(TASK, 7)).isEqualTo(TASK + "#7");
        for (final String name : List.of("-p", "", "a b", "a#b")) {
            assertThat(catchThrowable(() -> HandedIdeas.key(name, 1)))
                    .as("the name %s", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(catchThrowable(() -> HandedIdeas.key(TASK, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRecordKeepsAThousandDoneReportsDroppingTheOldest() {
        // A record growing with every idea would one day refuse all work as full.
        final Map<String, HandedIdeas.Handed> done = new HashMap<>();
        for (int index = 0; index < HandedIdeas.MOST_DONE; index++) {
            done.put(
                    "porelo-proj_old#" + (index + 1),
                    new HandedIdeas.Handed(HandedIdeas.DONE, "ST" + (index + 1), List.of(), index));
        }
        record.value = new WorkRun.Snapshot(5000, Map.of(), done);
        reported();

        take();

        assertThat(record.value.ideas())
                .as("a thousand done reports, the newest kept and the oldest dropped")
                .hasSize(HandedIdeas.MOST_DONE)
                .containsKey(KEY)
                .doesNotContainKey("porelo-proj_old#1")
                .containsKey("porelo-proj_old#2");
    }

    @Test
    void aRecordedReportThatFailsItsCheckIsRefused() {
        // A record edited by hand must not hand a made-up story id to the window.
        assertThat(
                        List.of(
                                new HandedIdeas.Handed(HandedIdeas.DONE, null, List.of(), 1),
                                new HandedIdeas.Handed(HandedIdeas.DONE, "ST01", List.of(), 1),
                                new HandedIdeas.Handed(
                                        HandedIdeas.DONE, "porelo-x#01", List.of(), 1),
                                new HandedIdeas.Handed(HandedIdeas.DONE, "a..b#1", List.of(), 1),
                                new HandedIdeas.Handed(HandedIdeas.PENDING, "ST1", List.of(), 1),
                                new HandedIdeas.Handed(
                                        HandedIdeas.PARTLY, null, List.of("../x"), 1),
                                new HandedIdeas.Handed("SAVED", null, List.of(), 1),
                                new HandedIdeas.Handed(HandedIdeas.DONE, "ST1", List.of(), -1)))
                .as("each record that fails its check")
                .allSatisfy(
                        handed ->
                                assertThat(catchThrowable(() -> HandedIdeas.validate(KEY, handed)))
                                        .as("%s", handed)
                                        .isInstanceOf(IllegalArgumentException.class));
        for (final String key :
                List.of(
                        OFFER,
                        TASK,
                        TASK + "#0",
                        TASK + "#01",
                        "-p#1",
                        "#1",
                        TASK + "#9223372036854775808")) {
            assertThat(
                            catchThrowable(
                                    () ->
                                            HandedIdeas.validate(
                                                    key,
                                                    new HandedIdeas.Handed(
                                                            HandedIdeas.DONE,
                                                            "ST1",
                                                            List.of(),
                                                            1))))
                    .as("the key %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        HandedIdeas.validate(
                KEY, new HandedIdeas.Handed(HandedIdeas.PARTLY, null, List.of("porelo-x"), 0));
        // Harm: a report number the key accepts but the record check refuses writes cleanly and
        // makes the work record unreadable on the next start.
        HandedIdeas.validate(
                HandedIdeas.key(TASK, Long.MAX_VALUE),
                new HandedIdeas.Handed(HandedIdeas.DONE, "ST1", List.of(), 0));
        // Harm: a tracker's new story recorded as handed makes the whole work record unreadable.
        HandedIdeas.validate(
                KEY, new HandedIdeas.Handed(HandedIdeas.DONE, "porelo-x#12", List.of(), 0));
    }

    @Test
    void aStorySavedAnewUnderTheIdOfAnEndedRunStartsWithoutThatRun() {
        // A new story taking over an old ended run shows its tasks and refuses its start.
        final WorkRunTest.Fixture f = new WorkRunTest.Fixture();
        final var offer = f.order().tasks().getFirst();
        f.run.report(
                "task",
                new WorkRun.Report(offer.offer(), WorkRun.Event.STARTED, "box", "box", null));
        f.run.report(
                "task",
                new WorkRun.Report(
                        offer.offer(),
                        WorkRun.Event.ENDED,
                        "box",
                        "box",
                        WorkRunTest.ended("Built")));
        record.value = new WorkRun.Snapshot(7, f.record.value.runs(), Map.of());
        assertThat(record.value.runs()).as("the old run of ST1").containsKey("ST1");
        reported();

        assertThat(take().stories())
                .as("the idea is saved under the old run's id")
                .containsExactly(KEY + " ST1");

        assertThat(record.value.runs()).as("the new ST1 has no run").doesNotContainKey("ST1");
        assertThat(run.work("ST1")).as("its work reads as none").isNull();
    }

    @Test
    void aRunWhoseStoryIsGoneIsDroppedBeforeTheSaveEvenWhenTheSaveFails() {
        // A run kept past a save that did not end would be taken over by the story saved next.
        final WorkRunTest.Fixture f = new WorkRunTest.Fixture();
        f.order();
        record.value = new WorkRun.Snapshot(7, f.record.value.runs(), Map.of());
        store.failures.add(new StoreException.NotSavable("", "no prefix"));
        reported();

        take();

        assertThat(record.value.runs()).as("the gone story's run is dropped").isEmpty();
    }

    @Test
    void aReportIsNotTakenWhileATaskOfAGoneStorysRunStillWorks() {
        // Dropping that run would leave its task unstoppable; keeping it would hand it the new
        // story; taking the report would lose its idea.
        final WorkRunTest.Fixture f = new WorkRunTest.Fixture();
        final var offer = f.order().tasks().getFirst();
        f.run.report(
                "task",
                new WorkRun.Report(offer.offer(), WorkRun.Event.STARTED, "box", "box", null));
        record.value = new WorkRun.Snapshot(7, f.record.value.runs(), Map.of());
        reported();
        final Map<String, WorkRun.Run> runs = record.value.runs();

        final ReportTaking.Outcome outcome = take();

        assertThat(outcome.unread())
                .as("said as work still running")
                .singleElement()
                .asString()
                .contains("WorkRunning");
        assertThat(store.attempts).as("nothing is saved").isZero();
        assertThat(record.value.runs()).as("the task stays stoppable").isEqualTo(runs);
        assertThat(record.value.reports()).as("the report not taken").doesNotContainKey(TASK);
    }

    @Test
    void aRunWhoseStoryIsStillOnTheBoardIsKeptWhenAnIdeaIsSaved() {
        // An idea saved that dropped the runs of stories still shown would lose their work.
        final WorkRunTest.Fixture f = new WorkRunTest.Fixture();
        f.order();
        record.value = new WorkRun.Snapshot(7, f.record.value.runs(), Map.of());
        final Board bare = NewIdeasTest.BOARD;
        store.shown =
                new Board(
                        bare.revision(),
                        f.store.board().workflow(),
                        f.store.board().stories(),
                        bare.issues(),
                        bare.work(),
                        bare.repositories(),
                        bare.faults());
        reported();

        take();

        assertThat(record.value.runs())
                .as("the run of ST1, still on the board")
                .containsOnlyKeys("ST1");
    }

    /** A store that adds ideas as story numbers and can refuse a save before it. */
    private static final class Store implements PlanningStore {

        final Deque<RuntimeException> failures = new ArrayDeque<>();

        final List<NewIdea> saved = new ArrayList<>();

        final List<String> seen = new ArrayList<>();

        java.util.function.@org.jspecify.annotations.Nullable Supplier<String> during;

        int attempts;

        int refreshes;

        /** Whether another save moved the board since it was read, so its revision is stale. */
        boolean moved;

        /** The versions each save by its items was checked against. */
        final List<Basis> bases = new ArrayList<>();

        Board shown = NewIdeasTest.BOARD;

        @Override
        public Board board() {
            return shown;
        }

        @Override
        public Board refresh() {
            refreshes++;
            return shown;
        }

        @Override
        public NewIdea.Added createIdea(
                final Basis basis, final NewIdea idea, final Recheck recheck) {
            bases.add(basis);
            recheck.rule().accept(Dependencies.of(shown));
            return made(idea);
        }

        @Override
        public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
            if (moved) {
                attempts++;
                throw new StoreException.StaleRevision("moved");
            }
            return made(idea);
        }

        private NewIdea.Added made(final NewIdea idea) {
            attempts++;
            final var check = during;
            if (check != null) {
                seen.add(check.get());
            }
            final RuntimeException failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
            saved.add(idea);
            return new NewIdea.Added("ST" + saved.size(), List.of(), NewIdeasTest.BOARD);
        }
    }
}
