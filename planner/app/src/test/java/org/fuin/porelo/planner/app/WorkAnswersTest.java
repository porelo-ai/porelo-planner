package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.ReadingCycle;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

class WorkAnswersTest {

    // Harm: the window starts a tracked project's stage without the tracker's issue and phase, so
    // Porelo's Start records the task for no issue.
    @Test
    void aStartHandsTheWindowTheTrackersIssueAndPhaseItsTaskNames() {
        final org.fuin.porelo.planner.core.WorkOrder.Task task =
                new org.fuin.porelo.planner.core.WorkOrder.Task(
                                "alpha",
                                List.of("alpha#2", "alpha#4"),
                                "MAIN",
                                "codex",
                                "model",
                                null,
                                "AGENT",
                                "Build.")
                        .working(
                                new org.fuin.porelo.planner.core.WorkOrder.Tracked(
                                        "Example-Org/alpha#2", "build"));
        final List<org.fuin.porelo.planner.app.answers.PlanningStartTask> starts =
                WorkAnswers.starts(
                        List.of(
                                new WorkRun.Offered("o-1", task),
                                new WorkRun.Offered("o-2", task.working(null))));
        assertThat(starts)
                .as("the tracker's work, or none")
                .extracting(org.fuin.porelo.planner.app.answers.PlanningStartTask::work)
                .containsExactly(
                        new org.fuin.porelo.planner.app.answers.TrackedWork(
                                "Example-Org/alpha#2", "build"),
                        null);
    }

    // Harm: the window hands Start no role or place, or one the planner did not offer, so a run
    // works at the wrong place; an offer without them must name none, so Start takes its defaults.
    @Test
    void aStartHandsTheWindowTheRunsRoleAndPlaceOnlyWhereTheOfferNamesThem() throws Exception {
        final org.fuin.porelo.planner.core.WorkOrder.Task task =
                new org.fuin.porelo.planner.core.WorkOrder.Task(
                        "alpha",
                        List.of("alpha#2"),
                        "MAIN",
                        "codex",
                        "model",
                        null,
                        "AGENT",
                        "Build.");
        final List<org.fuin.porelo.planner.app.answers.PlanningStartTask> starts =
                WorkAnswers.starts(
                        List.of(
                                new WorkRun.Offered("o-1", task),
                                new WorkRun.Offered(
                                        "o-2",
                                        task.at(
                                                org.fuin.porelo.planner.core.RunRole.AUTHOR,
                                                org.fuin.porelo.planner.core.RunPlace.gate(
                                                        "docs", "cart"))),
                                new WorkRun.Offered(
                                        "o-3",
                                        task.at(
                                                org.fuin.porelo.planner.core.RunRole.REVIEWER,
                                                new org.fuin.porelo.planner.core.RunPlace(
                                                        null,
                                                        "https://github.com/owner/shop/pull/7",
                                                        null)))));
        final com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readTree(json.writeValueAsString(starts.get(0))).has("runRole"))
                .as("no role written where the offer names none")
                .isFalse();
        assertThat(json.readTree(json.writeValueAsString(starts.get(0))).has("place")).isFalse();
        assertThat(json.readTree(json.writeValueAsString(starts.get(1))).get("place").toString())
                .as("the gate push alone, with its repository")
                .isEqualTo("{\"gatePush\":{\"repository\":\"docs\",\"name\":\"cart\"}}");
        assertThat(starts.get(1).runRole())
                .isEqualTo(
                        org.fuin.porelo.planner.app.answers.PlanningStartTask.RunRoleEnum.AUTHOR);
        assertThat(json.readTree(json.writeValueAsString(starts.get(2))).get("place").toString())
                .isEqualTo("{\"pullRequest\":\"https://github.com/owner/shop/pull/7\"}");
        assertThat(
                        json.readTree(
                                        json.writeValueAsString(
                                                WorkAnswers.place(
                                                        org.fuin.porelo.planner.core.RunPlace.gate(
                                                                null, "cart"))))
                                .toString())
                .as("a gate push of the task's own repository names none")
                .isEqualTo("{\"gatePush\":{\"name\":\"cart\"}}");
        assertThat(
                        json.writeValueAsString(
                                WorkAnswers.place(
                                        new org.fuin.porelo.planner.core.RunPlace(
                                                null, null, true))))
                .isEqualTo("{\"ownPullRequest\":true}");
        assertThat(starts.get(2).runRole())
                .isEqualTo(
                        org.fuin.porelo.planner.app.answers.PlanningStartTask.RunRoleEnum.REVIEWER);
    }

    @Test
    void aFreedIssuesStartIsServedOnlyWhileTheBoardStillLetsItBeBuilt() {
        // Harm: the window is served a freed issue's start the board no longer lets be built, such
        // as one whose blockers no longer hold it, and starts it without the person.
        final WorkRule rule = board().work().rules().get("Building");
        final org.fuin.porelo.planner.core.WorkOrder.Task task =
                new org.fuin.porelo.planner.core.WorkOrder.Task(
                                "alpha",
                                List.of("AA1"),
                                "BUILDER",
                                "codex",
                                "model",
                                null,
                                "AGENT",
                                "Build.")
                        .at(org.fuin.porelo.planner.core.RunRole.AUTHOR, null);
        final WorkRun.Offer freed =
                new WorkRun.Offer(
                        "00000000-0000-0000-0000-000000000003",
                        task,
                        new NextStep.Binding(
                                "ST1",
                                "alpha",
                                List.of("AA1"),
                                "BUILDER",
                                "Building",
                                "AGENT",
                                rule),
                        "OFFERED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "",
                        List.of(),
                        List.of(),
                        null);
        final WorkRun.Run run =
                new WorkRun.Run(
                        "00000000-0000-0000-0000-000000000001",
                        "ST1",
                        "Building",
                        "AGENT",
                        "",
                        "START",
                        1,
                        List.of(),
                        List.of(),
                        "2026-01-01T00:00:00Z",
                        List.of(freed),
                        Map.of(),
                        false,
                        false,
                        null,
                        Map.of(),
                        List.of(freed.id()),
                        List.of());
        assertThat(WorkRun.freedOffer(run, freed)).as("a freed issue's offer").isTrue();
        assertThat(WorkAnswers.knownWork(run, id -> false).starts())
                .as("counted in the bound of the answer's size")
                .hasSize(1);
        assertThat(WorkAnswers.knownWork(board(), run, TaskHelds.Counts.NONE).starts())
                .as("not served on a board whose workflow tells no backlog state")
                .isEmpty();
    }

    private static WorkRun.Offer started(
            final String id,
            final String task,
            final org.fuin.porelo.planner.core.WorkOrder.Task arguments) {
        final var rule =
                new WorkRule(
                        List.of("Backlog"),
                        "Review",
                        null,
                        null,
                        WorkRule.Per.REPOSITORY,
                        List.of("AGENT"),
                        "Build",
                        Map.of("codex", "model"));
        return new WorkRun.Offer(
                id,
                arguments,
                new NextStep.Binding(
                        "ST1", "alpha", List.of(), arguments.role(), "Building", "AGENT", rule),
                "STARTED",
                task,
                "box-" + task,
                "workstation",
                "2026-01-01T00:00:00Z",
                null,
                "",
                List.of(),
                List.of(),
                null);
    }

    private static WorkRun.Run runOf(final WorkRun.Offer... offers) {
        return new WorkRun.Run(
                "00000000-0000-0000-0000-000000000001",
                "ST1",
                "Building",
                "AGENT",
                "",
                "START",
                1,
                List.of(),
                List.of(),
                "2026-01-01T00:00:00Z",
                List.of(offers),
                Map.of(),
                false,
                false,
                null);
    }

    /** The issue AA1 with the progress given, its offer still offered under the refusal given. */
    private static WorkRun.Run refusedOn(
            final NextStep.Progress progress, final WorkRun.StartRefusal refusal) {
        final org.fuin.porelo.planner.core.WorkOrder.Task task =
                new org.fuin.porelo.planner.core.WorkOrder.Task(
                        "alpha", List.of("AA1"), "MAIN", "codex", "model", null, "AGENT", "Build.");
        final WorkRun.Offer offered = started("a", "task", task);
        final WorkRun.Offer refused =
                new WorkRun.Offer(
                        offered.id(),
                        task,
                        new NextStep.Binding(
                                "ST1",
                                "alpha",
                                List.of("AA1"),
                                "MAIN",
                                "Building",
                                "AGENT",
                                offered.binding().rule()),
                        "OFFERED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "",
                        List.of(),
                        List.of(),
                        null,
                        false,
                        "",
                        null,
                        refusal);
        return new WorkRun.Run(
                "00000000-0000-0000-0000-000000000001",
                "ST1",
                "Building",
                "AGENT",
                "",
                "START",
                1,
                List.of(),
                List.of(),
                "2026-01-01T00:00:00Z",
                List.of(refused),
                Map.of("AA1", progress),
                false,
                false,
                null);
    }

    // Harm: a refusal shown on an issue that asks for another reason, or on one moved on since,
    // names a cause that is not why the issue asks, or asks nothing any more.
    @Test
    void aStartRefusalIsServedOnlyWhileTheIssueAsksBecauseOfIt() {
        final WorkRun.StartRefusal refusal =
                new WorkRun.StartRefusal("UnknownForge", Map.of(), "Porelo's words.");
        final java.util.function.Function<NextStep.Progress, Object> served =
                progress ->
                        String.valueOf(
                                WorkAnswers.knownWork(refusedOn(progress, refusal), id -> false)
                                        .issues()
                                        .getFirst()
                                        .startRefusal());

        assertThat(
                        List.of(
                                new NextStep.Progress(
                                        rounds(), List.of(), true, WorkRun.START_REFUSED, ""),
                                new NextStep.Progress(
                                        rounds(), List.of(), true, WorkRun.PLACE_CHANGED, "")))
                .as("served while the refusal's reason stands")
                .allSatisfy(progress -> assertThat(served.apply(progress)).isNotEqualTo("null"));
        assertThat(
                        List.of(
                                new NextStep.Progress(rounds(), List.of(), false, "NONE", ""),
                                new NextStep.Progress(
                                        rounds(), List.of(), true, "NEXT_STEP_UNAVAILABLE", ""),
                                new NextStep.Progress(
                                        rounds(), List.of(), false, WorkRun.START_REFUSED, "")))
                .as("none once the issue moved on, or asks for another reason")
                .allSatisfy(progress -> assertThat(served.apply(progress)).isEqualTo("null"));
    }

    // Harm: a reviewer's card is shown as an author's, or a task shows the place or the held items
    // of another task, so the person reads the wrong run.
    @Test
    void eachStartedTaskAnswersItsOwnRunRolePlaceAndHeldItems() {
        final org.fuin.porelo.planner.core.WorkOrder.Task base =
                new org.fuin.porelo.planner.core.WorkOrder.Task(
                        "alpha", List.of(), "MAIN", "codex", "model", null, "AGENT", "Build.");
        final WorkRun.Offer author = started("a", "task-author", base);
        final WorkRun.Offer reviewer =
                started(
                        "b",
                        "task-reviewer",
                        base.at(
                                org.fuin.porelo.planner.core.RunRole.REVIEWER,
                                new org.fuin.porelo.planner.core.RunPlace(
                                        null, "https://github.com/owner/shop/pull/7", null)));
        final WorkRun.Offer resumed =
                started(
                        "c",
                        "task-resumed",
                        base.at(
                                org.fuin.porelo.planner.core.RunRole.AUTHOR,
                                org.fuin.porelo.planner.core.RunPlace.gate("docs", "cart")));
        final TaskHelds helds = new TaskHelds();
        helds.read(
                "project",
                List.of(
                        record("task-reviewer", 3),
                        record("task-author", null),
                        record("task-other", 9),
                        record("task-reviewer", 4)),
                List.of(
                        new WorkRun.Started("task-reviewer", null, true),
                        new WorkRun.Started("task-author", null, true),
                        new WorkRun.Started("task-other", null, true)));
        helds.read(
                "other",
                List.of(record("task-resumed", 7)),
                List.of(new WorkRun.Started("task-resumed", null, true)));

        final List<org.fuin.porelo.planner.app.answers.PlanWorkTask> tasks =
                WorkAnswers.knownWork(
                                runOf(author, reviewer, resumed),
                                issue -> false,
                                helds.in("project"))
                        .tasks();

        assertThat(tasks)
                .extracting(
                        org.fuin.porelo.planner.app.answers.PlanWorkTask::task,
                        t -> String.valueOf(t.runRole()),
                        t -> String.valueOf(t.place()),
                        org.fuin.porelo.planner.app.answers.PlanWorkTask::held)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("task-author", "null", "null", null),
                        org.assertj.core.groups.Tuple.tuple(
                                "task-reviewer",
                                "REVIEWER",
                                String.valueOf(WorkAnswers.place(reviewer.arguments().place())),
                                4),
                        org.assertj.core.groups.Tuple.tuple(
                                "task-resumed",
                                "AUTHOR",
                                String.valueOf(WorkAnswers.place(resumed.arguments().place())),
                                null));
        assertThat(tasks.get(1).place().pullRequest())
                .as("the pull request as offered")
                .isEqualTo("https://github.com/owner/shop/pull/7");
    }

    private static org.fuin.porelo.planner.core.TaskRecord record(
            final String task, final Integer held) {
        return new org.fuin.porelo.planner.core.TaskRecord(
                task,
                "project",
                "alpha",
                List.of(),
                java.time.Instant.EPOCH,
                null,
                null,
                List.of(),
                null,
                null,
                held,
                null);
    }

    // Harm: a value the core can give that the answer's enum lacks fails every board read with a
    // 500.
    @Test
    void everyValueOfTheCoresEnumsIsOneTheAnswersCanWrite() {
        for (final org.fuin.porelo.planner.core.WorkEnds.Reason reason :
                org.fuin.porelo.planner.core.WorkEnds.Reason.values()) {
            assertThat(
                            org.fuin.porelo.planner.app.answers.PlanWorkIssue.ReasonEnum.fromValue(
                                            reason.name())
                                    .getValue())
                    .as("the run's reason %s", reason)
                    .isEqualTo(reason.name());
            assertThat(
                            org.fuin.porelo.planner.app.answers.Issue.AttentionEnum.fromValue(
                                            reason.name())
                                    .getValue())
                    .as("the issue's attention %s", reason)
                    .isEqualTo(reason.name());
        }
        // A read naming another place than the one kept stops the issue for this reason.
        final String changed = org.fuin.porelo.planner.core.WorkRun.PLACE_CHANGED;
        assertThat(
                        List.of(
                                org.fuin.porelo.planner.app.answers.PlanWorkIssue.ReasonEnum
                                        .fromValue(changed)
                                        .getValue(),
                                org.fuin.porelo.planner.app.answers.Issue.AttentionEnum.fromValue(
                                                changed)
                                        .getValue(),
                                org.fuin.porelo.planner.app.answers.IssueDetail.AttentionEnum
                                        .fromValue(changed)
                                        .getValue()))
                .as("a changed place, in the run's reason, the issue's and its detail's attention")
                .containsOnly(changed);
        // A read where an issue's work waits at the gate stops it for one of these.
        for (final String stop :
                List.of(
                        org.fuin.porelo.planner.core.WorkRun.REJECTED_WITHOUT_POINTS,
                        org.fuin.porelo.planner.core.WorkRun.REVIEW_ROUNDS_USED,
                        org.fuin.porelo.planner.core.WorkRun.PLACE_UNREADABLE,
                        org.fuin.porelo.planner.core.WorkRun.PLACE_CLOSED,
                        org.fuin.porelo.planner.core.WorkRun.MERGED_OTHER_HEAD)) {
            assertThat(
                            List.of(
                                    org.fuin.porelo.planner.app.answers.PlanWorkIssue.ReasonEnum
                                            .fromValue(stop)
                                            .getValue(),
                                    org.fuin.porelo.planner.app.answers.Issue.AttentionEnum
                                            .fromValue(stop)
                                            .getValue(),
                                    org.fuin.porelo.planner.app.answers.IssueDetail.AttentionEnum
                                            .fromValue(stop)
                                            .getValue()))
                    .as("the stop %s, in the run's reason and both attentions", stop)
                    .containsOnly(stop);
        }
        for (final org.fuin.porelo.planner.core.WorkEnds.Severity severity :
                org.fuin.porelo.planner.core.WorkEnds.Severity.values()) {
            assertThat(
                            org.fuin.porelo.planner.app.answers.Finding.SeverityEnum.fromValue(
                                            severity.name())
                                    .getValue())
                    .as("the severity %s", severity)
                    .isEqualTo(severity.name());
        }
        for (final org.fuin.porelo.planner.core.WorkEnds.Declared declared :
                org.fuin.porelo.planner.core.WorkEnds.Declared.values()) {
            assertThat(
                            org.fuin.porelo.planner.app.answers.TaskEnded.OutcomeEnum.fromValue(
                                            declared.name())
                                    .getValue())
                    .as("the declared outcome %s", declared)
                    .isEqualTo(declared.name());
        }
        for (final Worker worker : Worker.values()) {
            assertThat(
                            org.fuin.porelo.planner.app.answers.Worker.fromValue(worker.name())
                                    .getValue())
                    .as("the worker %s", worker)
                    .isEqualTo(worker.name());
        }
    }

    private static final Issue AGENTS =
            new Issue("AA1", "alpha", "An agent's", "Building", null, List.of(), null, null, "");

    private static final Issue MANUAL =
            new Issue(
                    "AA2",
                    "alpha",
                    "A person's",
                    "Building",
                    null,
                    List.of(),
                    null,
                    null,
                    "",
                    Worker.MANUAL,
                    Risk.ORDINARY);

    private static final WorkRun.Snapshot NO_RUN = new WorkRun.Snapshot(0, Map.of());

    @Test
    void anAgentsIssueInAWorkingStateWithNoRunHereIsShownAndNotRaised() {
        // Work started elsewhere raised as needing the person buries the rows that really do.
        final org.fuin.porelo.planner.app.answers.Issue answered =
                WorkAnswers.issue(board(), AGENTS, NO_RUN);

        assertThat(answered.needsYou())
                .as("an issue with no run here asks the person for nothing")
                .isFalse();
        assertThat(answered.noRunHere())
                .as("the board says the issue is worked outside this planner")
                .isTrue();
        assertThat(answered.attention().getValue())
                .as("no reason is given for it")
                .isEqualTo("NONE");
    }

    @Test
    void aPersonsIssueInAWorkingStateIsNeitherRaisedNorWorkedOutsideThePlanner() {
        // A person's own issue counted as work outside this planner would warn of work that is
        // theirs.
        final org.fuin.porelo.planner.app.answers.Issue answered =
                WorkAnswers.issue(board(), MANUAL, NO_RUN);

        assertThat(answered.needsYou()).as("a person's issue is not raised").isFalse();
        assertThat(answered.noRunHere()).as("a person's issue has no run by design").isFalse();
    }

    @Test
    void aRaisedIssueNamesItsRunsReasonAndAnUnraisedOneNone() {
        // A raised row without its reason cannot offer the action that answers it.
        final org.fuin.porelo.planner.app.answers.Issue stopped =
                WorkAnswers.issue(
                        board(),
                        AGENTS,
                        run(new NextStep.Progress(rounds(), List.of(), true, "STOPPED", "")));
        final org.fuin.porelo.planner.app.answers.Issue quiet =
                WorkAnswers.issue(
                        board(),
                        AGENTS,
                        run(new NextStep.Progress(rounds(), List.of(), false, "NONE", "")));

        assertThat(stopped.needsYou()).as("a stopped run raises its issue").isTrue();
        assertThat(stopped.attention().getValue())
                .as("the raised issue names why")
                .isEqualTo("STOPPED");
        assertThat(stopped.noRunHere()).as("the issue has a run here").isFalse();
        assertThat(quiet.attention().getValue())
                .as("an issue not raised names no reason")
                .isEqualTo("NONE");
    }

    // Harm: an issue moved by hand beside a run that ended shown as that run's work, not as work
    // started elsewhere.
    @Test
    void anIssueBesideARunThatHoldsNothingNowReadsAsWorkStartedElsewhere() {
        final org.fuin.porelo.planner.app.answers.Issue answered =
                WorkAnswers.issue(
                        board(),
                        AGENTS,
                        run(new NextStep.Progress(rounds(), List.of(), false, "NONE", "")));

        assertThat(answered.noRunHere())
                .as("the story's run has no task offered or started")
                .isTrue();
        assertThat(
                        WorkAnswers.issue(
                                        board(),
                                        AGENTS,
                                        run(
                                                new NextStep.Progress(
                                                        rounds(), List.of(), false, "NONE", ""),
                                                List.of(offer("STARTED"))))
                                .noRunHere())
                .as("a task of the story's run is at work")
                .isFalse();
    }

    // Harm: a run the person started by hand in the author's task after a Reject, working at the
    // place the issue waits at, reads as nobody at work.
    @Test
    void aRunStartedByHandAtTheKeptPlaceOfItsTaskIsAtWork() {
        final WorkRun.Offer ended = offer("ENDED");
        final WorkRun.Offer author =
                new WorkRun.Offer(
                        ended.id(),
                        ended.arguments(),
                        new NextStep.Binding(
                                "ST1",
                                "alpha",
                                List.of("AA1"),
                                "MAIN",
                                "Building",
                                "AGENT",
                                ended.binding().rule()),
                        "ENDED",
                        "task",
                        "box",
                        "workstation",
                        null,
                        new WorkRun.Ended("2026-01-01T00:00:00Z", true, "", "Built", 0, List.of()),
                        "",
                        List.of(),
                        List.of(),
                        null);
        final org.fuin.porelo.planner.core.IssuePlace place =
                org.fuin.porelo.planner.core.IssuePlace.atGate("task", author.id());

        assertThat(WorkAnswers.issue(inReview(), REVIEWED, kept(author, place)).atWork())
                .as("nobody works while the place only waits")
                .isFalse();
        final WorkRun.Snapshot again = kept(author, place.runAgain("2026-01-01T01:00:00Z"));
        assertThat(WorkAnswers.issue(inReview(), REVIEWED, again).atWork())
                .as("the issue is at work")
                .isTrue();
        assertThat(WorkAnswers.issue(board(), AGENTS, again).atWork())
                .as("not once it was moved away from where its author left it")
                .isFalse();
        final var work = WorkAnswers.knownWork(again.runs().get("ST1"), issue -> false);
        assertThat(work.atWork()).as("the story's work is at work").isTrue();
        assertThat(work.tasks().getFirst().atWork()).as("in the author's task").isTrue();
    }

    @Test
    void onlyTheIssueWhosePlaceARunStartedByHandWorksAtIsAtWork() {
        // Harm: an offer of two issues serves both as at work while the person's own run works at
        // the place of only one of them.
        final WorkRun.Offer ended = offer("ENDED");
        final WorkRun.Offer author =
                new WorkRun.Offer(
                        ended.id(),
                        ended.arguments(),
                        new NextStep.Binding(
                                "ST1",
                                "alpha",
                                List.of("AA1", "AA2"),
                                "MAIN",
                                "Building",
                                "AGENT",
                                ended.binding().rule()),
                        "ENDED",
                        "task",
                        "box",
                        "workstation",
                        null,
                        new WorkRun.Ended("2026-01-01T00:00:00Z", true, "", "Built", 0, List.of()),
                        "",
                        List.of(),
                        List.of(),
                        null);
        final org.fuin.porelo.planner.core.IssuePlace place =
                org.fuin.porelo.planner.core.IssuePlace.atGate("task", author.id());
        final WorkRun.Snapshot again =
                kept(author, Map.of("AA1", place.runAgain("2026-01-01T01:00:00Z"), "AA2", place));
        final Board board = inReview();
        assertThat(
                        List.of(
                                WorkAnswers.issue(board, REVIEWED, again).atWork(),
                                WorkAnswers.issue(board, REVIEWED_TOO, again).atWork()))
                .as("the issue worked at, not the other")
                .containsExactly(true, false);
        assertThat(
                        WorkAnswers.knownWork(board, again.runs().get("ST1"), TaskHelds.Counts.NONE)
                                .issues()
                                .stream()
                                .map(i -> i.atWork())
                                .toList())
                .as("the run's issues alike")
                .containsExactly(true, false);
    }

    private static final Issue REVIEWED =
            new Issue("AA1", "alpha", "An agent's", "Review", null, List.of(), null, null, "");

    private static final Issue REVIEWED_TOO =
            new Issue("AA2", "alpha", "Another's", "Review", null, List.of(), null, null, "");

    /** The board with both issues where their author's end left them. */
    private static Board inReview() {
        final Board board = board();
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                List.of(REVIEWED, REVIEWED_TOO),
                board.work(),
                board.repositories());
    }

    /** A running run whose ended author's offer given left AA1 at the place given. */
    private static WorkRun.Snapshot kept(
            final WorkRun.Offer author, final org.fuin.porelo.planner.core.IssuePlace place) {
        return kept(author, Map.of("AA1", place));
    }

    /** A running run whose ended author's offer given left its issues at the places given. */
    private static WorkRun.Snapshot kept(
            final WorkRun.Offer author,
            final Map<String, org.fuin.porelo.planner.core.IssuePlace> places) {
        final Map<String, NextStep.Progress> issues = new java.util.TreeMap<>();
        places.keySet()
                .forEach(
                        id ->
                                issues.put(
                                        id,
                                        new NextStep.Progress(
                                                rounds(), List.of(), false, "NONE", "")));
        return new WorkRun.Snapshot(
                1,
                Map.of(
                        "ST1",
                        new WorkRun.Run(
                                "run",
                                "ST1",
                                "Building",
                                "AGENT",
                                "",
                                "",
                                2,
                                List.of(),
                                List.of(),
                                "2026-01-01T00:00:00Z",
                                List.of(author),
                                issues,
                                false,
                                false,
                                null,
                                places)));
    }

    private static WorkRun.Offer offer(final String status) {
        final WorkRule rule = board().work().rules().get("Building");
        return new WorkRun.Offer(
                "00000000-0000-0000-0000-000000000002",
                new org.fuin.porelo.planner.core.WorkOrder.Task(
                        "alpha", List.of(), "MAIN", "codex", "careful", "max", "AGENT", "Build"),
                new NextStep.Binding("ST1", "alpha", List.of(), "MAIN", "Building", "AGENT", rule),
                status,
                "task",
                "box",
                null,
                null,
                null,
                "",
                List.of(),
                List.of(),
                null);
    }

    private static ReadingCycle.Rounds rounds() {
        return new ReadingCycle.Rounds(0, 2, false);
    }

    private static WorkRun.Snapshot run(final NextStep.Progress progress) {
        return run(progress, List.of());
    }

    private static WorkRun.Snapshot run(
            final NextStep.Progress progress, final List<WorkRun.Offer> offers) {
        return new WorkRun.Snapshot(
                1,
                Map.of(
                        "ST1",
                        new WorkRun.Run(
                                "run",
                                "ST1",
                                "Building",
                                "AGENT",
                                "",
                                "",
                                2,
                                List.of(),
                                List.of(),
                                "2026-01-01T00:00:00Z",
                                offers,
                                Map.of("AA1", progress),
                                true,
                                false,
                                null)));
    }

    private static Board board() {
        final WorkRule build =
                new WorkRule(
                        List.of("Backlog"),
                        "Review",
                        null,
                        null,
                        WorkRule.Per.REPOSITORY,
                        List.of("AGENT"),
                        "Build {story} {issues}",
                        Map.of("codex", "model"));
        return new Board(
                new Revision("r1"),
                new Workflow(List.of("Backlog", "Building", "Review"), List.of(), List.of()),
                List.of(
                        new Story(
                                "ST1",
                                "Story",
                                "Building",
                                "High",
                                null,
                                List.of("AA1", "AA2"),
                                "",
                                null)),
                List.of(AGENTS, MANUAL),
                new Work(Map.of("Building", build), List.of()),
                org.fuin.porelo.planner.store.ProjectRepository.named(List.of("alpha")));
    }

    @Test
    void theWorkAnswersAReadsReworkInStartsAndNoOrdersOwnOffer() {
        // Harm: a window starts nothing for a rework offered on a poll, or starts an order's own
        // offer beside the form that starts it.
        final org.fuin.porelo.planner.core.WorkRun.Run sample =
                SerializationCheck.workRecord().runs().get("ST1");
        final org.fuin.porelo.planner.core.WorkRun.Offer rework = sample.offers().getFirst();
        final org.fuin.porelo.planner.core.WorkRun.Offer author =
                new org.fuin.porelo.planner.core.WorkRun.Offer(
                        "00000000-0000-0000-0000-000000000002",
                        rework.arguments().at(null, null),
                        rework.binding(),
                        "ENDED",
                        "task",
                        "box",
                        "workstation",
                        "2026-01-01T00:00:00Z",
                        null,
                        "",
                        List.of(),
                        List.of(),
                        null);
        final org.fuin.porelo.planner.core.WorkRun.Offer order =
                new org.fuin.porelo.planner.core.WorkRun.Offer(
                        "00000000-0000-0000-0000-000000000005",
                        rework.arguments(),
                        rework.binding(),
                        "OFFERED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "",
                        List.of(),
                        List.of(),
                        null);
        final org.fuin.porelo.planner.core.WorkRun.Run run =
                new org.fuin.porelo.planner.core.WorkRun.Run(
                        sample.id(),
                        sample.story(),
                        sample.to(),
                        sample.mode(),
                        sample.comment(),
                        sample.coordinator(),
                        sample.readingRounds(),
                        sample.issueReadingRounds(),
                        sample.repositories(),
                        sample.since(),
                        List.of(author, rework, order),
                        sample.issues(),
                        false,
                        false,
                        null,
                        sample.places(),
                        sample.placeOffers());
        final var starts = WorkAnswers.knownWork(run, issue -> false).starts();
        assertThat(starts.stream().map(s -> s.offer()).toList())
                .as("the rework alone")
                .containsExactly(rework.id());
        assertThat(String.valueOf(starts.getFirst().runRole()) + " " + starts.getFirst().place())
                .as("with its role and place")
                .contains("AUTHOR", "task");
    }
}
