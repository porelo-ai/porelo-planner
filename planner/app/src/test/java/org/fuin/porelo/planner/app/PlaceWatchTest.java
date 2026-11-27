package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.core.IssuePlace;
import org.fuin.porelo.planner.core.PlaceRead;
import org.fuin.porelo.planner.core.PlaceReviews;
import org.fuin.porelo.planner.core.ReviewOpen;
import org.fuin.porelo.planner.core.RunPlace;
import org.fuin.porelo.planner.core.TaskRecord;
import org.fuin.porelo.planner.core.TaskRecords;
import org.fuin.porelo.planner.core.WorkRun;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PlaceWatchTest {

    private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

    private static final String OFFER = "00000000-0000-0000-0000-000000000001";

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final List<String> saved = new CopyOnWriteArrayList<>();

    private final TaskHelds helds = new TaskHelds();

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    @Test
    void aReadLogsHowManyIssuesWaitedAndHowLongItTook() {
        // Harm: the cost of the minute's board reads cannot be seen in a real run, so no one can
        // tell whether a bound is needed.
        final Logger logger = (Logger) LoggerFactory.getLogger(PlaceWatch.class);
        final Level level = logger.getLevel();
        final ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        logger.setLevel(Level.DEBUG);
        try {
            new PlaceWatch(
                            p -> new Fake(WorkRun.PlaceOutcome.KEPT),
                            (project, from) -> List.of(record("box")),
                            (project, place, task) -> answer(),
                            saved::add,
                            helds,
                            scheduler,
                            Duration.ofMinutes(1))
                    .readAll("logged-project");
        } finally {
            logger.detachAppender(lines);
            logger.setLevel(level);
        }

        assertThat(lines.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .contains("logged-project")
                                        .contains("1 issues waiting")
                                        .contains("listing took")
                                        .contains("the read"));
    }

    @Test
    void aWaitingPlaceIsReadByItsTasksRecordThenByWhatIsOpenThereForItsAuthor() {
        // Harm: what is open is asked for another task than the kept one, so the owner's
        // points look answered; or a change reaches no window until the next store poll.
        final Fake work = new Fake(WorkRun.PlaceOutcome.KEPT);
        final List<String> asked = new ArrayList<>();
        final List<Instant> since = new ArrayList<>();
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> work,
                        (project, from) -> {
                            since.add(from);
                            return List.of(record("box-other"), record("box"));
                        },
                        (project, place, task) -> {
                            asked.add(project + " " + place + " " + task);
                            return answer();
                        },
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1));

        assertThat(watch.readAll("project")).containsEntry("ST1/AA1", WorkRun.PlaceOutcome.KEPT);

        assertThat(work.reads)
                .as("the bound container's record first, then the answer")
                .hasSize(2)
                .satisfies(
                        reads -> {
                            assertThat(reads.get(0))
                                    .isEqualTo(new PlaceRead.Recorded(record("box")));
                            assertThat(reads.get(1))
                                    .isEqualTo(
                                            new PlaceRead.Opened(
                                                    RunPlace.gate(null, "task"), "task", answer()));
                        });
        assertThat(asked)
                .as("asked for the author, by the kept task")
                .containsExactly("project " + RunPlace.gate(null, "task") + " task");
        assertThat(since)
                .as("records from a little before the run's start")
                .containsExactly(WHEN.minus(PlaceWatch.SKEW));
        assertThat(saved).as("the windows are told").containsExactly("project");

        saved.clear();
        final Fake same = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        new PlaceWatch(
                        p -> same,
                        (project, from) -> List.of(),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(same.reads)
                .as("no record of the container: what is open is still read")
                .hasSize(1);
        assertThat(saved).as("nothing changed, nobody told").isEmpty();
    }

    @Test
    void aStopWhosePlaceWasLeftEndsAndTellsTheWindowsEvenWithNothingWaiting() {
        // Harm: the person's question about an unreadable place stands on the board after the
        // issue moved on, because nothing of the project waits any more to be read.
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        work.waits.clear();
        work.left = java.util.Set.of("ST1");
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> work,
                        (project, from) -> List.of(),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1));

        assertThat(watch.readAll("project")).as("nothing read").isEmpty();
        assertThat(saved).as("the windows are told of the ended stop").containsExactly("project");

        saved.clear();
        watch.readAll("project");
        assertThat(saved).as("nothing ended, nobody told").isEmpty();
    }

    @Test
    void theFreedIssuesAreOfferedEveryReadEvenWithNothingWaitingAndAfterThePlacesRead() {
        // Harm: an issue held back by a blocker never starts while no place of the project is
        // kept, or waits a minute more because it is offered before its blocker's place is read.
        final Fake idle = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        idle.waits.clear();
        idle.freed = java.util.Set.of("ST1");
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> idle,
                        (project, from) -> List.of(),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1));

        assertThat(watch.readAll("project")).as("no place read").isEmpty();
        assertThat(idle.steps).as("freed with no place waiting").containsExactly("freed");
        assertThat(saved).as("the windows are told of the offer").containsExactly("project");
        saved.clear();
        watch.readAll("project");
        assertThat(saved).as("nothing offered, nobody told").isEmpty();

        final Fake waiting = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        waiting.freed = java.util.Set.of("ST2");
        new PlaceWatch(
                        p -> waiting,
                        (project, from) -> List.of(),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(waiting.steps)
                .as("freed after the waiting place was read")
                .containsExactly("AA1", "freed");
        assertThat(saved).as("the windows are told").containsExactly("project");

        saved.clear();
        idle.left = java.util.Set.of("ST1");
        idle.freed = java.util.Set.of("ST3");
        idle.steps.clear();
        watch.readAll("project");
        assertThat(idle.steps).as("freed even when a stop ended").containsExactly("freed");
        assertThat(saved).as("told once").containsExactly("project");
    }

    @Test
    void theHeldItemsAReadFoundStandForTheirTaskAndForNoOther() {
        // Harm: the card shows no held items though Porelo counted some, those of another task or
        // another project's task of the same name, or 0 where Porelo gave no count.
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        work.started.add(new WorkRun.Started("box-held", WHEN.toString(), true));
        work.started.add(new WorkRun.Started("box", WHEN.toString(), true));
        new PlaceWatch(
                        p -> work,
                        (project, from) ->
                                List.of(
                                        new TaskRecord(
                                                "box-held",
                                                "project",
                                                "alpha",
                                                List.of("AA1"),
                                                WHEN,
                                                null,
                                                null,
                                                List.of(),
                                                org.fuin.porelo.planner.store.Role.Workspace.OWN,
                                                null,
                                                2),
                                        record("box")),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");

        assertThat(
                        java.util.Arrays.asList(
                                helds.of("project", "box-held"),
                                helds.of("project", "box"),
                                helds.of("project", "none")))
                .as(
                        "a count read; a record with no count and a task never read are unknown, never 0")
                .containsExactly(2, null, null);
        new PlaceWatch(
                        p -> work,
                        (project, from) -> List.of(heldRecord("box-held", 5)),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("other");
        assertThat(
                        java.util.Arrays.asList(
                                helds.of("project", "box-held"), helds.of("other", "box-held")))
                .as("the same task name in two projects")
                .containsExactly(2, 5);
    }

    @Test
    void aStartedTaskIsCountedWithNoPlaceWaitingAndTheWindowsAreTold() {
        // Harm: a task's held items show on its card only while a place of its run waits, so a
        // restart shows none until a place is read; or a changed count reaches no window.
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        work.waits.clear();
        work.started.add(new WorkRun.Started("box-held", WHEN.toString(), true));
        final List<Instant> since = new ArrayList<>();
        final List<Integer> count = new ArrayList<>(List.of(2));
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> work,
                        (project, from) -> {
                            since.add(from);
                            return List.of(heldRecord("box-held", count.getFirst()));
                        },
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1));

        assertThat(watch.readAll("project")).as("no place read").isEmpty();
        assertThat(helds.of("project", "box-held")).isEqualTo(2);
        assertThat(since)
                .as("from a little before its start")
                .containsExactly(WHEN.minus(PlaceWatch.SKEW));
        assertThat(saved).as("the windows are told").containsExactly("project");

        saved.clear();
        watch.readAll("project");
        assertThat(saved).as("the same count, nobody told").isEmpty();

        count.set(0, 0);
        watch.readAll("project");
        assertThat(helds.of("project", "box-held")).as("a task at work is read again").isEqualTo(0);
        assertThat(saved).as("a changed count is told").containsExactly("project");
    }

    @Test
    void aNewWatchCountsEveryStartedTaskAtItsFirstReadAndAnEndedOneOnlyOnce() {
        // Harm: after a restart an ended task's card shows no held items; or its record, which
        // can no longer change, is asked of Porelo every minute.
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        work.waits.clear();
        work.started.add(new WorkRun.Started("box-ended", WHEN.toString(), false));
        work.started.add(new WorkRun.Started("box-gone", WHEN.toString(), false));
        final List<Instant> since = new ArrayList<>();
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> work,
                        (project, from) -> {
                            since.add(from);
                            return List.of(heldRecord("box-ended", 3));
                        },
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1));

        watch.readAll("project");
        assertThat(helds.of("project", "box-ended")).as("read at the first read").isEqualTo(3);
        assertThat(since).as("one read").hasSize(1);

        watch.readAll("project");
        assertThat(since)
                .as("an ended task read once, and one Porelo has no record of, are not read again")
                .hasSize(1);

        work.started.add(new WorkRun.Started("box-new", WHEN.minusSeconds(60).toString(), true));
        work.waits.add(waits("AA1"));
        watch.readAll("project");
        assertThat(since)
                .as("one read serves a waiting place and a task at work, from the earlier start")
                .hasSize(2)
                .last()
                .isEqualTo(WHEN.minusSeconds(60).minus(PlaceWatch.SKEW));
    }

    @Test
    void anEndedTaskIsReadOnceMoreAfterItsEndThenNoMore() {
        // Harm: the count read while a task worked stays on its card after it ended, though
        // Porelo counted anew; or the ended task is asked of Porelo every minute.
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        work.waits.clear();
        work.started.add(new WorkRun.Started("box-held", WHEN.toString(), true));
        final List<Integer> count = new ArrayList<>(List.of(1));
        final List<Instant> since = new ArrayList<>();
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> work,
                        (project, from) -> {
                            since.add(from);
                            return List.of(heldRecord("box-held", count.getFirst()));
                        },
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1));
        watch.readAll("project");

        work.started.set(0, new WorkRun.Started("box-held", WHEN.toString(), false));
        count.set(0, 3);
        watch.readAll("project");
        assertThat(helds.of("project", "box-held")).as("its count at its end").isEqualTo(3);

        watch.readAll("project");
        assertThat(since).as("read at work, once after its end, then no more").hasSize(2);
    }

    @Test
    void aTaskWhoseStartIsNotKnownIsNotReadFromTheEpoch() {
        // Harm: one task with no start makes every minute's read ask Porelo for every record it
        // ever kept.
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        work.waits.clear();
        work.started.add(new WorkRun.Started("box-held", null, true));
        final List<Instant> since = new ArrayList<>();
        new PlaceWatch(
                        p -> work,
                        (project, from) -> {
                            since.add(from);
                            return List.of();
                        },
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");

        assertThat(since)
                .singleElement()
                .as("a day back")
                .satisfies(
                        from ->
                                assertThat(from)
                                        .isAfter(
                                                Instant.now()
                                                        .minus(PlaceWatch.UNKNOWN_START)
                                                        .minus(PlaceWatch.SKEW)
                                                        .minusSeconds(60)));
    }

    @Test
    void aPoreloThatCannotAnswerTheCountsLeavesThemAsTheyWere() {
        // Harm: a failed read makes a count read before vanish from the card, or tells the
        // windows of a change that did not happen.
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        work.waits.clear();
        work.started.add(new WorkRun.Started("box-held", WHEN.toString(), true));
        helds.read(
                "project",
                List.of(heldRecord("box-held", 2)),
                List.of(new WorkRun.Started("box-held", WHEN.toString(), true)));
        new PlaceWatch(
                        p -> work,
                        (project, from) -> {
                            throw new TaskRecords.Unanswered("unreachable");
                        },
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");

        assertThat(helds.of("project", "box-held")).isEqualTo(2);
        assertThat(saved).as("nothing changed, nobody told").isEmpty();
    }

    @Test
    void aPoreloThatCannotAnswerMakesEachWaitingPlaceUnreadableInWordsOfItsOwn() {
        // Harm: a missing answer taken as nothing open; or a local path or Porelo's free text
        // reaching the person's screen.
        final Fake refused = new Fake(WorkRun.PlaceOutcome.NEEDS_YOU);
        new PlaceWatch(
                        p -> refused,
                        (project, from) -> List.of(),
                        (project, place, task) -> {
                            throw new PlaceReviews.Refused("PlaceUnreadable", "Unreachable");
                        },
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(refused.reads)
                .containsExactly(
                        new PlaceRead.Unread(
                                RunPlace.gate(null, "task"),
                                "task",
                                "Porelo refused the place: PlaceUnreadable (Unreachable)"));

        final Fake failed = new Fake(WorkRun.PlaceOutcome.NEEDS_YOU);
        new PlaceWatch(
                        p -> failed,
                        (project, from) -> {
                            throw new TaskRecords.Unanswered("/home/someone/bin/porelo timed out");
                        },
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(failed.reads)
                .as("records that cannot be read: no answer read, the place unreadable")
                .containsExactly(
                        new PlaceRead.Unread(
                                RunPlace.gate(null, "task"),
                                "task",
                                "Porelo gave no answer that can be trusted"));

        final Fake missing = new Fake(WorkRun.PlaceOutcome.NEEDS_YOU);
        new PlaceWatch(
                        p -> missing,
                        "Porelo's executable is missing or refused",
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(missing.reads)
                .as("no executable: unreadable, nothing run")
                .containsExactly(
                        new PlaceRead.Unread(
                                RunPlace.gate(null, "task"),
                                "task",
                                "Porelo's executable is missing or refused"));
    }

    @Test
    void aForwardedGatePushIsReadOnAtTheTasksOwnPullRequestAndNeverTakenForTheWorksEnd() {
        // Harm: the forwarding taken for the end of the work, so the points opened on the pull
        // request never send its author back; or a pull request that cannot be read taken for
        // none.
        final ReviewOpen gate = forwardedAnswer();
        final ReviewOpen own = answer();
        final List<String> asked = new ArrayList<>();
        final Fake answered = new Fake(WorkRun.PlaceOutcome.KEPT);
        new PlaceWatch(
                        p -> answered,
                        (project, from) -> List.of(),
                        (project, place, task) -> {
                            asked.add(place + " " + task);
                            return place.ownPullRequest() == null ? gate : own;
                        },
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(answered.reads)
                .as("the gate's answer and the own pull request's, together")
                .containsExactly(
                        new PlaceRead.Forwarded(RunPlace.gate(null, "task"), "task", gate, own));
        assertThat(asked)
                .as("the own pull request asked for the same author task")
                .containsExactly(RunPlace.gate(null, "task") + " task", RunPlace.own() + " task");

        for (final String none : List.of("NoPullRequest", "UnknownForge")) {
            final Fake absent = new Fake(WorkRun.PlaceOutcome.KEPT);
            new PlaceWatch(
                            p -> absent,
                            (project, from) -> List.of(),
                            (project, place, task) -> {
                                if (place.ownPullRequest() == null) {
                                    return gate;
                                }
                                throw new PlaceReviews.Refused(none, null);
                            },
                            saved::add,
                            helds,
                            scheduler,
                            Duration.ofMinutes(1))
                    .readAll("project");
            assertThat(absent.reads)
                    .as("no pull request from the task's branch (" + none + ")")
                    .containsExactly(
                            none.equals("NoPullRequest")
                                    // Forwarded to porelo/task: its pull request may open later.
                                    ? new PlaceRead.Forwarded(
                                            RunPlace.gate(null, "task"), "task", gate, null)
                                    // A forge Porelo does not know never has one: the gate's
                                    // answer stands.
                                    : new PlaceRead.Opened(
                                            RunPlace.gate(null, "task"), "task", gate));
        }

        final Fake unreadable = new Fake(WorkRun.PlaceOutcome.NEEDS_YOU);
        new PlaceWatch(
                        p -> unreadable,
                        (project, from) -> List.of(),
                        (project, place, task) -> {
                            if (place.ownPullRequest() == null) {
                                return gate;
                            }
                            throw new PlaceReviews.Refused("ForgeAdapterMissing", null);
                        },
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(unreadable.reads)
                .as("a pull request Porelo cannot read: the place unreadable, in fixed words")
                .containsExactly(
                        new PlaceRead.Unread(
                                RunPlace.gate(null, "task"),
                                "task",
                                "Porelo refused the place: ForgeAdapterMissing"));

        final Fake unanswered = new Fake(WorkRun.PlaceOutcome.NEEDS_YOU);
        new PlaceWatch(
                        p -> unanswered,
                        (project, from) -> List.of(),
                        (project, place, task) -> {
                            if (place.ownPullRequest() == null) {
                                return gate;
                            }
                            throw new TaskRecords.Unanswered("/home/someone/bin/porelo timed out");
                        },
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(unanswered.reads)
                .as("no answer at the pull request: the place unreadable, no local path shown")
                .containsExactly(
                        new PlaceRead.Unread(
                                RunPlace.gate(null, "task"),
                                "task",
                                "Porelo gave no answer that can be trusted"));

        final List<String> openAsked = new ArrayList<>();
        new PlaceWatch(
                        p -> new Fake(WorkRun.PlaceOutcome.KEPT),
                        (project, from) -> List.of(),
                        (project, place, task) -> {
                            openAsked.add(String.valueOf(place));
                            return answer();
                        },
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(openAsked)
                .as("a gate push still open: no pull request asked")
                .containsExactly(String.valueOf(RunPlace.gate(null, "task")));
    }

    @Test
    void eachRefusedReadIsLoggedWithItsProjectIssueAndPlace() {
        // Harm: a log that says only that some place of a project was refused, so no one can
        // tell which issue waits and where.
        final ReviewOpen gate = forwardedAnswer();
        final Logger logger = (Logger) LoggerFactory.getLogger(PlaceWatch.class);
        final ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            new PlaceWatch(
                            p -> new Fake(WorkRun.PlaceOutcome.NEEDS_YOU),
                            (project, from) -> List.of(),
                            (project, place, task) -> {
                                throw new PlaceReviews.Refused("NoPullRequest", null);
                            },
                            saved::add,
                            helds,
                            scheduler,
                            Duration.ofMinutes(1))
                    .readAll("refused-project");
            new PlaceWatch(
                            p -> new Fake(WorkRun.PlaceOutcome.KEPT),
                            (project, from) -> List.of(),
                            (project, place, task) -> {
                                if (place.ownPullRequest() == null) {
                                    return gate;
                                }
                                throw new PlaceReviews.Refused("NoPullRequest", null);
                            },
                            saved::add,
                            helds,
                            scheduler,
                            Duration.ofMinutes(1))
                    .readAll("forwarded-project");
        } finally {
            logger.detachAppender(lines);
        }

        assertThat(lines.list)
                .filteredOn(line -> line.getLevel() == Level.INFO)
                .extracting(ILoggingEvent::getFormattedMessage)
                .as("the refused place and the own pull request Porelo found none at")
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .contains("refused-project")
                                        .contains("AA1")
                                        .contains(String.valueOf(RunPlace.gate(null, "task")))
                                        .contains("NoPullRequest"))
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .contains("forwarded-project")
                                        .contains("AA1")
                                        .contains(String.valueOf(RunPlace.own()))
                                        .contains("NoPullRequest"));
    }

    @Test
    void oneIssueThatCannotBeReadLeavesTheOthersRead() {
        // Harm: one broken place stops every other issue's rework.
        final Fake work = new Fake(WorkRun.PlaceOutcome.KEPT);
        work.waits.add(0, waits("AA0"));
        work.failFor = "AA0";
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> work,
                        (project, from) -> List.of(),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1));
        assertThat(watch.readAll("project")).as("the other issue read").containsOnlyKeys("ST1/AA1");
    }

    @Test
    void aReadWhoseSaveFailedStillTellsTheWindows() {
        // Harm: a run a failed save halted, or left as it stood, is shown as before until the
        // next store poll, so the person never sees why the issue stopped.
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        work.failFor = "AA1";
        new PlaceWatch(
                        p -> work,
                        (project, from) -> List.of(),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofMinutes(1))
                .readAll("project");
        assertThat(saved).as("the windows are told").containsExactly("project");
    }

    @Test
    void aClosedWatchReadsNothingMore() throws Exception {
        // Harm: a task's event after the planner began to stop starts reads of Porelo that run
        // on past its end.
        final Fake work = new Fake(WorkRun.PlaceOutcome.KEPT);
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> work,
                        (project, from) -> List.of(),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        Executors.newSingleThreadScheduledExecutor(),
                        Duration.ofHours(1));
        watch.close();
        watch.reported("project");
        watch.followed("project");
        Thread.sleep(200);
        assertThat(work.listed.getCount()).as("nothing listed").isEqualTo(2);
        assertThat(watch.watches("project")).as("not polled").isFalse();
    }

    @Test
    void anErrorInARunOfReadsLeavesTheNextAskRead() throws Exception {
        // Harm: one Error in a read leaves the project marked as read forever, so no later event
        // or poll reads its places again.
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        work.error = new Error("broken");
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> work,
                        (project, from) -> List.of(),
                        (project, place, task) -> answer(),
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofHours(1));
        watch.reported("project");
        for (int i = 0; i < 50 && work.listed.getCount() == 2; i++) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
        watch.reported("project");
        assertThat(work.listed.await(5, TimeUnit.SECONDS)).as("read again").isTrue();
    }

    @Test
    void theMockModesWatchReadsNothing() {
        // Harm: the mock board's runs ask a Porelo the mock mode never reaches.
        final PlaceWatch none = PlaceWatch.none();
        none.followed("project");
        none.reported("project");
        assertThat(none.readAll("project")).isEmpty();
        assertThat(none.watches("project")).isFalse();
    }

    @Test
    void aFollowedProjectIsReadOneAtATimeOnceMoreForAnAskMeanwhileAndNoMoreWhenLeft()
            throws Exception {
        // Harm: two reads of one project judge the same answer twice; an event during a read is
        // lost; or Porelo is asked for a project no window follows.
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch twice = new CountDownLatch(2);
        final Fake work = new Fake(WorkRun.PlaceOutcome.UNCHANGED);
        final PlaceWatch watch =
                new PlaceWatch(
                        p -> work,
                        (project, from) -> List.of(),
                        (project, place, task) -> {
                            most.accumulateAndGet(running.incrementAndGet(), Math::max);
                            reads.incrementAndGet();
                            first.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (final InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            running.decrementAndGet();
                            twice.countDown();
                            return answer();
                        },
                        saved::add,
                        helds,
                        scheduler,
                        Duration.ofHours(1));
        watch.followed("project");
        assertThat(watch.watches("project")).as("polled while followed").isTrue();
        assertThat(first.await(5, TimeUnit.SECONDS)).as("read at once").isTrue();
        watch.reported("project");
        watch.reported("project");
        release.countDown();
        assertThat(twice.await(5, TimeUnit.SECONDS)).as("read once more").isTrue();
        Thread.sleep(200);
        assertThat(List.of(reads.get(), most.get()))
                .as("two asks meanwhile make one more read, never two at once")
                .containsExactly(2, 1);
        watch.left("project");
        assertThat(watch.watches("project")).as("not polled once left").isFalse();
    }

    /** A project's work answering every read with one outcome, one issue waiting at the gate. */
    private static final class Fake implements PlaceWatch.Places {
        final List<WorkRun.Waits> waits = new ArrayList<>(List.of(waits("AA1")));
        final List<WorkRun.Started> started = new ArrayList<>();
        final List<PlaceRead> reads = new CopyOnWriteArrayList<>();
        final WorkRun.PlaceOutcome outcome;
        @Nullable String failFor;

        /** Counts down on each listing of the waiting places, from two. */
        final CountDownLatch listed = new CountDownLatch(2);

        /** Thrown by the next listing alone, if set. */
        volatile @Nullable Error error;

        Fake(final WorkRun.PlaceOutcome outcome) {
            this.outcome = outcome;
        }

        /** The stories whose stops the next call ends. */
        java.util.Set<String> left = java.util.Set.of();

        @Override
        public java.util.Set<String> placesLeft() {
            final java.util.Set<String> ended = left;
            left = java.util.Set.of();
            return ended;
        }

        /** The stories whose runs the next call offers freed work in. */
        java.util.Set<String> freed = java.util.Set.of();

        /** What was done, in order: each place read by its issue, and "freed". */
        final List<String> steps = new CopyOnWriteArrayList<>();

        @Override
        public java.util.Set<String> freed() {
            steps.add("freed");
            final java.util.Set<String> offered = freed;
            freed = java.util.Set.of();
            return offered;
        }

        @Override
        public List<WorkRun.Waits> waiting() {
            listed.countDown();
            final Error thrown = error;
            if (thrown != null) {
                error = null;
                throw thrown;
            }
            return waits;
        }

        @Override
        public List<WorkRun.Started> started() {
            return started;
        }

        @Override
        public WorkRun.PlaceOutcome placeRead(
                final String story, final String issue, final PlaceRead found) {
            if (issue.equals(failFor)) {
                throw new IllegalStateException("broken");
            }
            reads.add(found);
            steps.add(issue);
            return found instanceof PlaceRead.Recorded ? WorkRun.PlaceOutcome.UNCHANGED : outcome;
        }
    }

    private static WorkRun.Waits waits(final String issue) {
        return new WorkRun.Waits(
                "ST1", issue, IssuePlace.atGate("task", OFFER), "box", WHEN.toString());
    }

    private static TaskRecord heldRecord(final String container, final int held) {
        return new TaskRecord(
                container,
                "other",
                "alpha",
                List.of("AA1"),
                WHEN,
                null,
                null,
                List.of(),
                org.fuin.porelo.planner.store.Role.Workspace.OWN,
                null,
                held);
    }

    private static TaskRecord record(final String container) {
        return new TaskRecord(
                container,
                "project",
                "alpha",
                List.of("AA1"),
                WHEN,
                null,
                null,
                List.of(),
                org.fuin.porelo.planner.store.Role.Workspace.OWN,
                null,
                null);
    }

    /** The gate's answer once Porelo forwarded the push to the task's pull request. */
    private static ReviewOpen forwardedAnswer() {
        return new ReviewOpen(
                "a".repeat(40),
                ReviewOpen.Decision.APPROVED,
                "b".repeat(40),
                "porelo/task",
                WHEN,
                List.of(),
                0,
                0,
                true,
                null,
                "forwarded");
    }

    private static ReviewOpen answer() {
        return new ReviewOpen(
                null,
                ReviewOpen.Decision.OPEN,
                null,
                null,
                WHEN,
                List.of(),
                0,
                0,
                true,
                null,
                "v1");
    }
}
