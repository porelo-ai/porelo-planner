package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The reports of the planner's own tasks, read and taken once, page by page. */
class ReportTakingTest {

    static final Instant T0 = Instant.parse("2026-11-25T08:00:00Z");

    static final String OFFER = "4f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f";

    static final String TASK = "porelo-proj_talk";

    static final String PROJECT = "proj";

    private final Ideas store = new Ideas();

    private final WorkRunTest.Memory record = new WorkRunTest.Memory();

    private final Porelo porelo = new Porelo();

    private WorkRun run = new WorkRun(store, record, Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    void aDiscussionsIdeaReportBecomesOneStoryAndReadAgainMakesNone() {
        // Harm: a discussion's idea lost, or saved once per read so the board fills with copies.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, false);
        porelo.report(TASK, "idea", "good");

        final ReportTaking.Outcome first = take();
        // A restart: a new planner on the same record.
        run = new WorkRun(store, record, Clock.fixed(T0, ZoneOffset.UTC));
        final ReportTaking.Outcome again = take();

        assertThat(first.stories()).as("one story").containsExactly(TASK + "#1 ST1");
        assertThat(again.stories()).as("none the second time").isEmpty();
        assertThat(store.saved).as("one save").hasSize(1);
        assertThat(record.value.reports().get(TASK).seq()).as("the mark").isEqualTo(1);
        assertThat(porelo.asked)
                .as("read on after the mark the second time")
                .containsExactly(TASK + " 0", TASK + " 1");
    }

    @Test
    void aReportOfAnotherKindChangesNothingButIsTaken() {
        // Harm: a status report saved as an idea, or read again on every read.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, false);
        porelo.report(TASK, "status", "Half done.");

        final ReportTaking.Outcome outcome = take();

        assertThat(store.saved).as("nothing saved").isEmpty();
        assertThat(outcome.stories()).as("no story").isEmpty();
        assertThat(outcome.refused()).as("nothing refused").isEmpty();
        assertThat(record.value.reports().get(TASK).seq()).as("taken").isEqualTo(1);
    }

    @Test
    void aRefusedIdeaIsTakenAndSaidWithItsFaultsAndMakesNothing() {
        // Harm: a refused idea read again forever, blocking the task's later reports, or refused
        // without a word of why.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, false);
        porelo.report(TASK, "idea", "bad");
        porelo.report(TASK, "idea", "good");

        final ReportTaking.Outcome outcome = take();

        assertThat(outcome.refused())
                .as("the refused one, with its faults")
                .singleElement()
                .asString()
                .startsWith(TASK + "#1: ")
                .contains("text: The text is no idea.");
        assertThat(outcome.stories()).as("the next one saved").containsExactly(TASK + "#2 ST1");
        assertThat(record.value.ideas())
                .as("the refused one kept no key")
                .doesNotContainKey(TASK + "#1");
        assertThat(record.value.reports().get(TASK).seq()).as("both taken").isEqualTo(2);
    }

    @Test
    void reportsOfATaskAnotherCallerStartedAreNeverRead() {
        // Harm: the planner saves ideas of tasks it never started, from another caller's key or
        // none.
        run.discussed(OFFER);
        porelo.task("porelo-proj_other", "5f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f", false);
        porelo.task("porelo-proj_none", null, false);
        porelo.report("porelo-proj_other", "idea", "good");
        porelo.report("porelo-proj_none", "idea", "good");

        take();

        assertThat(porelo.asked).as("no report read").isEmpty();
        assertThat(store.saved).as("nothing saved").isEmpty();
    }

    @Test
    void nothingIsAskedOfPoreloWhileNoOfferIsFollowed() {
        // Harm: Porelo run every minute for a project the planner started nothing in.
        final ReportTaking.Outcome outcome = take();

        assertThat(porelo.since).as("no records read").isEmpty();
        assertThat(outcome.unread()).as("nothing unread").isEmpty();
    }

    @Test
    void pagesAreReadOnFromTheLastReportOfEachPageNeverFromLast() {
        // Harm: reports between a page's end and the name's last skipped when more follow.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, false);
        porelo.page = 2;
        for (int i = 0; i < 5; i++) {
            porelo.report(TASK, "status", "s" + i);
        }

        take();

        assertThat(porelo.asked)
                .as("each page from the last one taken")
                .containsExactly(TASK + " 0", TASK + " 2", TASK + " 4");
        assertThat(record.value.reports().get(TASK).seq()).as("all taken").isEqualTo(5);
    }

    @Test
    void aReadStopsAfterTheMostPagesAndDoesNotCloseAnEndedOfferWithReportsLeft() {
        // Harm: a task with endless reports holds a read for ever, or an ended offer is closed
        // with reports unread, which are then lost.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, true);
        porelo.page = 1;
        for (int i = 0; i < ReportTaking.MOST_PAGES + 5; i++) {
            porelo.report(TASK, "status", "s" + i);
        }

        final ReportTaking.Outcome first = take();

        assertThat(porelo.asked).as("only the most pages").hasSize(ReportTaking.MOST_PAGES);
        assertThat(record.value.reports().get(TASK).seq())
                .as("taken as far as read")
                .isEqualTo(ReportTaking.MOST_PAGES);
        assertThat(record.value.followed()).as("not closed").containsKey(OFFER);
        assertThat(first.notes()).as("no end said").isEmpty();

        take();

        assertThat(record.value.reports().get(TASK).seq())
                .as("the next read goes on")
                .isEqualTo(ReportTaking.MOST_PAGES + 5);
        assertThat(record.value.followed()).as("closed once all read").doesNotContainKey(OFFER);
    }

    @Test
    void aPageThatAnswersAReportAlreadyTakenTakesNothing() {
        // Harm: Porelo answering from the wrong number makes the planner take reports twice or
        // skip some.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, false);
        porelo.report(TASK, "idea", "good");
        take();
        porelo.ignoreAfter = true;
        porelo.report(TASK, "idea", "good");

        final ReportTaking.Outcome outcome = take();

        assertThat(outcome.unread()).as("said").singleElement().asString().contains(TASK);
        assertThat(store.saved).as("only the first saved").hasSize(1);
        assertThat(record.value.reports().get(TASK).seq()).as("the mark kept").isEqualTo(1);
    }

    @Test
    void anEndedDiscussionWhoseReportsAreAllTakenIsNoLongerFollowed() {
        // Harm: Porelo asked every minute, forever, for every discussion ever offered.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, true);
        porelo.report(TASK, "idea", "good");

        take();
        final int asked = porelo.since.size();
        take();

        assertThat(record.value.followed()).as("dropped").doesNotContainKey(OFFER);
        assertThat(record.value.reports()).as("its mark kept").containsKey(TASK);
        assertThat(porelo.since).as("nothing asked again").hasSize(asked);
    }

    @Test
    void aRunningDiscussionStaysFollowed() {
        // Harm: a discussion that reports its idea after a first read is never read again.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, false);

        take();
        porelo.report(TASK, "idea", "good");
        take();

        assertThat(store.saved).as("its later idea saved").hasSize(1);
        assertThat(record.value.followed()).as("still followed").containsKey(OFFER);
    }

    @Test
    void aFollowedDiscussionsMarkOutlivesTheBoundSoNoReportIsTakenTwice() {
        // Harm: past the mark bound a running discussion's mark is dropped, so its reports are
        // read from the start again and each idea is saved a second time.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, false);
        porelo.report(TASK, "idea", "good");
        take();
        assertThat(store.saved).as("its idea saved once").hasSize(1);
        assertThat(record.value.followed().get(OFFER).names())
                .as("the name Porelo recorded its task under")
                .containsExactly(TASK);

        WorkRun.Snapshot snapshot = record.value;
        for (int i = 0; i <= ReportTaking.MOST_MARKS; i++) {
            snapshot = ReportTaking.marked(snapshot, "t-" + i, 1);
        }
        record.value = snapshot;
        assertThat(record.value.reports())
                .as("the discussion's mark kept, the oldest other dropped")
                .containsKey(TASK)
                .doesNotContainKey("t-0")
                .hasSize(ReportTaking.MOST_MARKS);

        take();
        assertThat(store.saved).as("no idea saved twice").hasSize(1);
    }

    @Test
    void aFollowedDiscussionsTaskRecordedUnderNoTasksNameStopsNoOtherTaskOfIt() {
        // Harm: one record of a discussion under a name that is no task's name makes every take
        // of the project fail, so no idea of any task is saved again.
        run.discussed(OFFER);
        porelo.task(TASK, OFFER, false);
        porelo.report(TASK, "idea", "good");
        take();
        porelo.task("no task\u202ename", OFFER, false);
        porelo.report(TASK, "idea", "good");

        final ReportTaking.Outcome outcome = take();

        assertThat(outcome.stories()).as("the named task's new idea saved").hasSize(1);
        assertThat(outcome.unread())
                .as("the record without a task's name said")
                .containsExactly("A task record names no task's name; its reports are not read.");
        assertThat(record.value.followed().get(OFFER).names())
                .as("only a task's name kept")
                .containsExactly(TASK);
    }

    @Test
    void aDiscussionPoreloNeverRecordedIsDroppedAfterAWeek() {
        // Harm: an offer the person never started is followed, and Porelo asked, forever.
        run.discussed(OFFER);
        take();
        assertThat(record.value.followed()).as("kept a day later").containsKey(OFFER);

        run =
                new WorkRun(
                        store,
                        record,
                        Clock.fixed(T0.plus(ReportTaking.UNSEEN).plusSeconds(1), ZoneOffset.UTC));
        final ReportTaking.Outcome outcome = take();

        assertThat(record.value.followed()).as("dropped").doesNotContainKey(OFFER);
        assertThat(outcome.notes()).as("said").hasSize(1);
    }

    @Test
    void recordsAreAskedFromTheOldestFollowedOfferLessAMinute() {
        // Harm: a task that ended just before the planner's clock read the offer is never found.
        run.discussed(OFFER);

        take();

        assertThat(porelo.since).containsExactly(T0.minus(ReportTaking.MARGIN));
    }

    @Test
    void anUnansweredReadOfOneTaskTakesNothingOfItAndGoesOnWithTheNext() {
        // Harm: one task Porelo cannot read stops every other task's reports, or a failure is
        // taken as no reports.
        final String other = "6f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f";
        run.discussed(OFFER);
        run.discussed(other);
        porelo.task(TASK, OFFER, true);
        porelo.task("porelo-proj_second", other, true);
        porelo.report(TASK, "idea", "good");
        porelo.report("porelo-proj_second", "idea", "good");
        porelo.unreadable = TASK;

        final ReportTaking.Outcome outcome = take();

        assertThat(outcome.unread()).as("said").singleElement().asString().startsWith(TASK);
        assertThat(outcome.stories()).as("the other saved").hasSize(1);
        assertThat(record.value.followed())
                .as("the unread one still followed, the other closed")
                .containsKey(OFFER)
                .doesNotContainKey(other);
    }

    @Test
    void porelosRecordsUnansweredAreThrownAndTakeNothing() {
        // Harm: a Porelo that cannot answer read as one that has nothing.
        run.discussed(OFFER);
        porelo.recordsFail = true;

        assertThat(catchThrowable(this::take)).isInstanceOf(TaskRecords.Unanswered.class);
        assertThat(record.value.followed()).as("still followed").containsKey(OFFER);
    }

    @Test
    void aStartedRunOffersTaskIsReadAndClosedOnceEndedAndDrained() {
        // Harm: an idea a run's task reports is lost, or an ended run's task read forever.
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
        record.value = f.record.value;
        final Board bare = NewIdeasTest.BOARD;
        // The run's story stays on the board, so the idea's save keeps its run.
        store.shown =
                new Board(
                        bare.revision(),
                        f.store.board().workflow(),
                        f.store.board().stories(),
                        bare.issues(),
                        bare.work(),
                        bare.repositories(),
                        bare.faults());
        porelo.task(TASK, offer.offer(), true);
        porelo.report(TASK, "idea", "good");

        final ReportTaking.Outcome outcome = take();
        final int asked = porelo.asked.size();
        take();

        assertThat(outcome.stories()).as("the run's task's idea saved").hasSize(1);
        assertThat(record.value.followed().get(offer.offer()).closed())
                .as("closed while its run holds it")
                .isTrue();
        assertThat(porelo.asked).as("not read again").hasSize(asked);
    }

    @Test
    void aFollowedOfferMustBeACanonicalUuid() {
        // Harm: a key no record check accepts makes the whole work record unreadable.
        assertThat(catchThrowable(() -> run.discussed("4F2C1D7E-8A3B-4C5D-9E6F-0A1B2C3D4E5F")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(record.value.followed()).isEmpty();
    }

    @Test
    void theFollowedOffersAreBoundedTheOldestDropped() {
        // Harm: a record growing with every discussion offered would one day refuse all work.
        for (int i = 0; i <= ReportTaking.MOST_FOLLOWED; i++) {
            run.discussed(String.format("00000000-0000-4000-8000-%012d", i));
        }

        assertThat(record.value.followed())
                .hasSize(ReportTaking.MOST_FOLLOWED)
                .doesNotContainKey("00000000-0000-4000-8000-000000000000");
    }

    @Test
    void theMarksAreBoundedTheOldestOtherDropped() {
        // Harm: marks growing with every task name ever read would one day fill the record.
        WorkRun.Snapshot snapshot = record.value;
        for (int i = 0; i <= ReportTaking.MOST_MARKS; i++) {
            snapshot = ReportTaking.marked(snapshot, "t-" + i, 1);
        }

        assertThat(snapshot.reports())
                .hasSize(ReportTaking.MOST_MARKS)
                .doesNotContainKey("t-0")
                .containsKey("t-" + ReportTaking.MOST_MARKS);
    }

    /** A run whose one offer started and ended at the given time, loaded into the record. */
    private WorkRun.Offered endedRun(final String endedAt, final boolean end) {
        final WorkRunTest.Fixture f = new WorkRunTest.Fixture();
        final var offer = f.order().tasks().getFirst();
        f.run.report(
                "task",
                new WorkRun.Report(offer.offer(), WorkRun.Event.STARTED, "box", "box", null));
        if (end) {
            f.run.report(
                    "task",
                    new WorkRun.Report(
                            offer.offer(),
                            WorkRun.Event.ENDED,
                            "box",
                            "box",
                            new WorkRun.Ended(
                                    endedAt,
                                    true,
                                    "",
                                    "Built",
                                    0,
                                    List.of(WorkRunTest.landing("work/built")))));
        }
        record.value = f.record.value;
        return offer;
    }

    @Test
    void anEndedRunOffersTaskPoreloNeverRecordedIsClosedAfterAWeek() {
        // Harm: a run that ended before records named their caller, or whose record aged out, is
        // asked for every minute forever.
        final var offer = endedRun("2026-01-01T00:00:00Z", true);

        final ReportTaking.Outcome first = take();
        final int asked = porelo.since.size();
        take();

        assertThat(record.value.followed().get(offer.offer()).closed())
                .as("closed while its run holds it")
                .isTrue();
        assertThat(first.notes()).as("said").hasSize(1);
        assertThat(porelo.since).as("not asked again").hasSize(asked);
    }

    @Test
    void anEndedRunOffersTaskPoreloHasNotYetRecordedStaysOpenWithinAWeek() {
        // Harm: a run offer closed before Porelo's record of its task shows up loses its reports.
        final var offer = endedRun(T0.minus(java.time.Duration.ofDays(1)).toString(), true);

        take();
        final int asked = porelo.since.size();
        take();

        assertThat(record.value.followed()).as("not closed").doesNotContainKey(offer.offer());
        assertThat(porelo.since).as("asked again").hasSizeGreaterThan(asked);
    }

    @Test
    void aClosedRunOffersIsNeverEvictedByTheBoundOfTheDiscussions() {
        // Harm: a closed run offer evicted makes its ended run's task asked for again.
        final var offer = endedRun("2026-01-01T00:00:00Z", true);
        take();
        for (int i = 0; i <= ReportTaking.MOST_FOLLOWED; i++) {
            run.discussed(String.format("00000000-0000-4000-8000-%012d", i));
        }

        assertThat(record.value.followed())
                .as("the closed run offer kept, the discussions bounded")
                .containsKey(offer.offer())
                .hasSize(ReportTaking.MOST_FOLLOWED + 1);
        take();
        assertThat(record.value.followed().get(offer.offer()).closed()).as("still closed").isTrue();
    }

    @Test
    void theMarkOfAnOpenRunOffersTaskIsNeverDropped() {
        // Harm: a mark dropped for the bound makes the task's reports read again from the start.
        endedRun("2026-01-01T00:00:00Z", false);
        WorkRun.Snapshot snapshot = ReportTaking.marked(record.value, "box", 3);
        for (int i = 0; i <= ReportTaking.MOST_MARKS; i++) {
            snapshot = ReportTaking.marked(snapshot, "t-" + i, 1);
        }

        assertThat(snapshot.reports())
                .as("the open run's mark kept, the oldest other dropped")
                .containsKey("box")
                .doesNotContainKey("t-0");
        assertThat(snapshot.reports()).hasSize(ReportTaking.MOST_MARKS);
    }

    ReportTaking.Outcome take() {
        return run.takeReports(PROJECT, porelo, porelo, ReportTakingTest::read);
    }

    /** Reads {@code good} as a sound idea and anything else as no idea. */
    static ReportTaking.Read read(final String text) {
        if (!text.equals("good")) {
            throw WorkRefusal.ideaRefused(
                    "The report's text is no idea.", List.of("text: The text is no idea."), null);
        }
        return new ReportTaking.Read(NewIdeasTest.idea(), List.of());
    }

    /** Porelo's records and reports, kept in memory. */
    static final class Porelo implements TaskRecords, TaskReports {

        final List<TaskRecord> records = new ArrayList<>();

        final Map<String, List<TaskReport>> reports = new HashMap<>();

        final List<Instant> since = new ArrayList<>();

        final List<String> asked = new ArrayList<>();

        int page = 100;

        boolean ignoreAfter;

        boolean recordsFail;

        @Nullable String unreadable;

        void task(final String name, final @Nullable String key, final boolean ended) {
            records.add(
                    new TaskRecord(
                            name,
                            PROJECT,
                            null,
                            List.of(),
                            T0,
                            ended ? T0.plusSeconds(60) : null,
                            ended ? TaskRecord.End.DONE : null,
                            List.of(),
                            key));
        }

        void report(final String name, final String kind, final String text) {
            final List<TaskReport> kept = reports.computeIfAbsent(name, n -> new ArrayList<>());
            kept.add(new TaskReport(kept.size() + 1, kind, text, "2026-11-25T08:00:01Z"));
        }

        @Override
        public List<TaskRecord> since(final String project, final Instant from) {
            if (recordsFail) {
                throw new Unanswered("Porelo cannot read its task records");
            }
            since.add(from);
            return List.copyOf(records);
        }

        @Override
        public Page after(final String container, final long after) {
            asked.add(container + " " + after);
            if (container.equals(unreadable)) {
                throw new TaskRecords.Unanswered("Porelo cannot read the reports of " + container);
            }
            final List<TaskReport> all = reports.getOrDefault(container, List.of());
            final List<TaskReport> later =
                    all.stream().filter(r -> ignoreAfter || r.seq() > after).toList();
            final List<TaskReport> answered = later.subList(0, Math.min(page, later.size()));
            return new Page(answered, all.size(), answered.size() < later.size());
        }
    }

    /** A store that adds ideas as story numbers. */
    static final class Ideas implements PlanningStore {

        final List<NewIdea> saved = new ArrayList<>();

        Board shown = NewIdeasTest.BOARD;

        @Override
        public Board board() {
            return shown;
        }

        @Override
        public Board refresh() {
            return shown;
        }

        @Override
        public NewIdea.Added createIdea(
                final org.fuin.porelo.planner.store.Basis basis,
                final NewIdea idea,
                final org.fuin.porelo.planner.store.Recheck recheck) {
            saved.add(idea);
            return new NewIdea.Added("ST" + saved.size(), List.of(), NewIdeasTest.BOARD);
        }

        @Override
        public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
            saved.add(idea);
            return new NewIdea.Added("ST" + saved.size(), List.of(), NewIdeasTest.BOARD);
        }
    }
}
