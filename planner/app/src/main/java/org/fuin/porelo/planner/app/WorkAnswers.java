package org.fuin.porelo.planner.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.app.answers.Finding;
import org.fuin.porelo.planner.app.answers.GivenFile;
import org.fuin.porelo.planner.app.answers.IssueReadingRounds;
import org.fuin.porelo.planner.app.answers.Landing;
import org.fuin.porelo.planner.app.answers.PlanAssignment;
import org.fuin.porelo.planner.app.answers.PlanOffer;
import org.fuin.porelo.planner.app.answers.PlanRepositoryWork;
import org.fuin.porelo.planner.app.answers.PlanWork;
import org.fuin.porelo.planner.app.answers.PlanWorkIssue;
import org.fuin.porelo.planner.app.answers.PlanWorkTask;
import org.fuin.porelo.planner.app.answers.PlanWorker;
import org.fuin.porelo.planner.app.answers.PlanningStartTask;
import org.fuin.porelo.planner.app.answers.StoryWorkOrder;
import org.fuin.porelo.planner.app.answers.StoryWorkProposal;
import org.fuin.porelo.planner.app.answers.TaskEnded;
import org.fuin.porelo.planner.app.answers.TaskRole;
import org.fuin.porelo.planner.app.answers.TrackedWork;
import org.fuin.porelo.planner.app.answers.WriteAs;
import org.fuin.porelo.planner.core.IdeaDiscussion;
import org.fuin.porelo.planner.core.IssuePlace;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.OpenWork;
import org.fuin.porelo.planner.core.RunPlace;
import org.fuin.porelo.planner.core.WorkEnds;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Role;
import org.jspecify.annotations.Nullable;

/**
 * Builds the work answers {@code openapi/planner.yaml} describes from the core's runs and
 * proposals; the answer records themselves are generated from the description.
 */
public final class WorkAnswers {

    private WorkAnswers() {}

    /**
     * Answers the task that discusses an idea with the person: no role, every repository of the
     * project read only, the project's services, and only its report to hand back; its own task
     * opens the window's terminal.
     */
    static PlanningStartTask discussion(final String offer, final IdeaDiscussion discussion) {
        return new PlanningStartTask(
                offer,
                null,
                List.of(),
                PlanningStartTask.KindEnum.DISCUSSION,
                null,
                null,
                null,
                true,
                discussion.agent(),
                discussion.model(),
                discussion.effort(),
                said(discussion.basis()),
                discussion.mode(),
                discussion.prompt(),
                null,
                null,
                true,
                true,
                null,
                rights(List.of(Role.Right.REPORT)),
                List.of(),
                true,
                null,
                null,
                null);
    }

    /**
     * Answers a role as the description names it: its name, how its task ends and its parts, each
     * in Porelo's words. Every answer that carries a role maps it here.
     *
     * @param role the role, as the project's work rules define it
     * @return the role's answer
     */
    static TaskRole role(final Role role) {
        return new TaskRole(
                role.name(),
                TaskRole.EndKindEnum.fromValue(role.end().name()),
                TaskRole.WorkspaceEnum.fromValue(role.workspace().word()),
                role.readLanded(),
                role.projectServices(),
                WriteAs.fromValue(role.writeAs().word()),
                rights(role.rights()));
    }

    /** Answers rights in Porelo's words, in the order given. */
    private static Set<org.fuin.porelo.planner.app.answers.Right> rights(
            final List<Role.Right> rights) {
        return rights.stream()
                .map(right -> org.fuin.porelo.planner.app.answers.Right.fromValue(right.word()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static StoryWorkProposal proposal(final WorkProposal proposal) {
        return new StoryWorkProposal(
                proposal.modes(),
                proposal.repositories().stream().map(WorkAnswers::repository).toList(),
                StoryWorkProposal.CoordinatorEnum.fromValue(proposal.coordinator()),
                proposal.comment(),
                proposal.readingRounds(),
                proposal.issueReadingRounds().stream()
                        .map(limit -> new IssueReadingRounds(limit.issue(), limit.rounds()))
                        .toList(),
                proposal.revision().value(),
                proposal.manual(),
                proposal.held().stream()
                        .map(
                                held ->
                                        new org.fuin.porelo.planner.app.answers.HeldIssue(
                                                held.issue(), held.reason()))
                        .toList());
    }

    /**
     * Answers an item's warnings as the board shows them, each saying whether it holds the item
     * back.
     *
     * @param warnings the store's warnings on the item
     * @return them, in order
     */
    static List<org.fuin.porelo.planner.app.answers.ItemWarning> warnings(
            final List<org.fuin.porelo.planner.store.Warning> warnings) {
        return warnings.stream()
                .map(
                        warning ->
                                new org.fuin.porelo.planner.app.answers.ItemWarning(
                                        warning.kind(),
                                        warning.text(),
                                        warning.holdsWork(),
                                        correction(warning.correction())))
                .toList();
    }

    private static org.fuin.porelo.planner.app.answers.@Nullable Correction correction(
            final org.fuin.porelo.planner.store.@Nullable Correction correction) {
        return correction == null
                ? null
                : new org.fuin.porelo.planner.app.answers.Correction(
                        correction.kind(), correction.choices());
    }

    /** Answers a person's order; its tasks are new, so none runs a kept task again. */
    static StoryWorkOrder order(final WorkRun.Order order) {
        return new StoryWorkOrder(order.revision(), starts(order.tasks(), Map.of()));
    }

    /**
     * Answers offers as Start takes them, for a caller that holds no run's kept places: a rework at
     * its task's own pull request, which only the kept place names, is refused.
     *
     * @param tasks the offers
     * @return their start orders
     */
    static List<PlanningStartTask> starts(final List<WorkRun.Offered> tasks) {
        return starts(tasks, Map.of());
    }

    /**
     * Answers offers as Start takes them, each with the members of its role and place and nothing
     * else, the first that qualifies opening the window's terminal.
     *
     * @param tasks the offers
     * @param places the run's kept places, by issue, which name the task a rework runs again
     * @return their start orders
     * @throws IllegalStateException for an offer Start must not be handed: one at another task's
     *     work without a place, a rework whose kept place is not known, or one without a role
     */
    static List<PlanningStartTask> starts(
            final List<WorkRun.Offered> tasks, final Map<String, IssuePlace> places) {
        final @Nullable String terminal =
                tasks.stream()
                        .filter(offer -> opensTerminal(offer.task()))
                        .map(WorkRun.Offered::offer)
                        .findFirst()
                        .orElse(null);
        return tasks.stream()
                .map(offer -> start(offer, places, offer.offer().equals(terminal)))
                .toList();
    }

    /**
     * Whether the window opens its terminal on a task: an agent's task whose role pushes work, or a
     * discussion's own.
     */
    private static boolean opensTerminal(final WorkOrder.Task task) {
        final Role role = task.role();
        return role == null || task.mode().equals("AGENT") && role.pushesWork();
    }

    private static PlanningStartTask start(
            final WorkRun.Offered offer,
            final Map<String, IssuePlace> places,
            final boolean terminal) {
        final WorkOrder.Task task = offer.task();
        final Role role = task.role();
        if (role == null) {
            throw new IllegalStateException("The offer " + offer.offer() + " names no role");
        }
        final RunPlace place = task.place();
        // Start would take a task at another task's work without its place as one at its own.
        if (role.readsAnothersWork() && place == null) {
            throw new IllegalStateException(
                    "The offer " + offer.offer() + " reads another task's work at no place");
        }
        final WorkOrder.Tracked work = task.work();
        final Role.Workspace workspace = role.workspace();
        return new PlanningStartTask(
                offer.offer(),
                task.repository(),
                task.issues(),
                PlanningStartTask.KindEnum.WORK,
                role(role),
                rerun(offer, places),
                ownTask(task),
                terminal ? true : null,
                task.agent(),
                task.model(),
                task.effort(),
                said(task.basis()),
                task.mode(),
                task.prompt(),
                task.result(),
                task.given().isEmpty()
                        ? null
                        : task.given().stream()
                                .map(given -> new GivenFile(given.name(), given.text()))
                                .toList(),
                workspace == Role.Workspace.READ_ONLY
                                || workspace == Role.Workspace.ALL_REPOSITORIES
                        ? true
                        : null,
                workspace == Role.Workspace.ALL_REPOSITORIES ? true : null,
                workspace == Role.Workspace.NONE ? true : null,
                rights(role.rights()),
                task.readLanded(),
                role.projectServices(),
                // Start refuses an account to write as anywhere but at a pull request.
                place != null && (place.pullRequest() != null || place.ownPullRequest() != null)
                        ? WriteAs.fromValue(role.writeAs().word())
                        : null,
                work == null ? null : new TrackedWork(work.issue(), work.phase()),
                place == null ? null : place(place));
    }

    /**
     * Whether Start is asked under a task name of the window's own choosing: for a task at another
     * task's work, never under that task's name, and for the building work of an issue the run's
     * start held back, which has no task of its own yet; null, absent, for every other.
     */
    private static @Nullable Boolean ownTask(final WorkOrder.Task task) {
        return task.atAnothersWork() || task.again() && task.place() == null ? true : null;
    }

    /**
     * The task a rework runs again: an author sent back to its own work at its task's gate push,
     * which names it, or at its task's own pull request, whose kept place names it; null for a new
     * task and for one at another task's work.
     */
    private static @Nullable String rerun(
            final WorkRun.Offered offer, final Map<String, IssuePlace> places) {
        final WorkOrder.Task task = offer.task();
        final RunPlace place = task.place();
        if (!task.again() || place == null || task.atAnothersWork()) {
            return null;
        }
        final RunPlace.GatePush gate = place.gatePush();
        if (gate != null) {
            return gate.name();
        }
        if (place.ownPullRequest() == null) {
            return null;
        }
        final IssuePlace kept = places.get(task.issues().getFirst());
        if (kept == null) {
            throw new IllegalStateException(
                    "The rework offer " + offer.offer() + " has no kept place to name its task");
        }
        return kept.task();
    }

    /** Answers a run's place as Start is handed it, its one member present and no other. */
    static org.fuin.porelo.planner.app.answers.RunPlace place(final RunPlace place) {
        final RunPlace.GatePush gate = place.gatePush();
        return new org.fuin.porelo.planner.app.answers.RunPlace(
                gate == null
                        ? null
                        : new org.fuin.porelo.planner.app.answers.GatePush(
                                gate.repository(), gate.name()),
                place.pullRequest(),
                place.ownPullRequest());
    }

    static @Nullable PlanWork work(
            final Board board, final WorkRun.@Nullable Run run, final TaskHelds.Counts held) {
        return run == null ? null : knownWork(board, run, held);
    }

    /**
     * Answers the run as the board stands: a freed issue's building work is among its starts only
     * while that issue is still free to be built.
     */
    static PlanWork knownWork(
            final Board board, final WorkRun.Run run, final TaskHelds.Counts held) {
        return knownWork(
                board, run, issue -> manual(board, issue), held, WorkRun.placeStarts(run, board));
    }

    /** The run's work for a caller that knows no held items. */
    static PlanWork knownWork(final WorkRun.Run run, final Predicate<String> manual) {
        return knownWork(run, manual, TaskHelds.Counts.NONE);
    }

    /**
     * Answers the run with every start it may serve, unchecked against the board, as a bound of its
     * size.
     */
    static PlanWork knownWork(
            final WorkRun.Run run, final Predicate<String> manual, final TaskHelds.Counts held) {
        return knownWork(null, run, manual, held, WorkRun.placeStarts(run));
    }

    /**
     * Answers the run, a person's issue reported as theirs: no task takes it and it waits on no
     * agent's choice, whatever the run recorded before the switch.
     */
    private static PlanWork knownWork(
            final @Nullable Board board,
            final WorkRun.Run run,
            final Predicate<String> manual,
            final TaskHelds.Counts held,
            final List<WorkRun.Offered> starts) {
        final List<PlanWorkTask> tasks =
                run.offers().stream()
                        .filter(o -> o.task() != null)
                        .map(
                                o ->
                                        new PlanWorkTask(
                                                String.valueOf(o.task()),
                                                String.valueOf(o.containerId()),
                                                o.machine(),
                                                o.arguments().repository(),
                                                o.binding().issues(),
                                                o.binding().state(),
                                                o.binding().rule().done(),
                                                o.arguments().mode(),
                                                o.arguments().agent(),
                                                o.arguments().model(),
                                                // The effort its order said it uses, never the
                                                // offer's.
                                                o.arguments().used(),
                                                // The role the task was started with, which
                                                // its offer's rule bound.
                                                role(
                                                        Objects.requireNonNull(
                                                                o.arguments().role(),
                                                                "a run's task has a role")),
                                                o.arguments().readLanded(),
                                                String.valueOf(o.since()),
                                                WorkRun.atWork(run, o, board),
                                                ended(o.ended()),
                                                o.arguments().place() == null
                                                        ? null
                                                        : place(o.arguments().place()),
                                                held.of(String.valueOf(o.task()))))
                        .toList();
        return new PlanWork(
                run.id(),
                tasks,
                // The task that leads its repository's building work and is at work coordinates.
                run.offers().stream()
                        .filter(
                                o ->
                                        o.task() != null
                                                && o.arguments().lead()
                                                && o.binding().role().pushesWork()
                                                && WorkRun.atWork(run, o, board))
                        .map(o -> String.valueOf(o.task()))
                        .findFirst()
                        .orElse(null),
                run.since(),
                tasks.stream().anyMatch(PlanWorkTask::atWork),
                run.repositories().stream().map(WorkAnswers::repository).toList(),
                run.comment(),
                run.readingRounds(),
                run.issueReadingRounds().stream()
                        .map(l -> new IssueReadingRounds(l.issue(), l.rounds()))
                        .toList(),
                run.issues().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .map(
                                entry -> {
                                    final boolean person = manual.test(entry.getKey());
                                    final NextStep.Progress p =
                                            person ? entry.getValue().manual() : entry.getValue();
                                    return new PlanWorkIssue(
                                            entry.getKey(),
                                            PlanWorkIssue.ReasonEnum.fromValue(p.reason()),
                                            p.rounds().rounds(),
                                            p.rounds().roundsMost(),
                                            atWork(board, run, entry.getKey()),
                                            p.needsYou(),
                                            !person && p.rounds().stopped(),
                                            findings(p.findings()),
                                            person ? null : startRefusal(run, entry.getKey(), p));
                                })
                        .toList(),
                starts(starts, run.places()));
    }

    /**
     * The refusal of a Start that stands on an issue's offer, served only while the issue asks the
     * person because of it; under any other reason, or once nothing is asked, none.
     */
    private static org.fuin.porelo.planner.app.answers.@Nullable TaskRefusal startRefusal(
            final WorkRun.Run run, final String issue, final NextStep.Progress progress) {
        if (!progress.needsYou()
                || !(progress.reason().equals(WorkRun.START_REFUSED)
                        || progress.reason().equals(WorkRun.PLACE_CHANGED))) {
            return null;
        }
        return run.offers().stream()
                .filter(o -> o.status().equals("OFFERED") && o.startRefusal() != null)
                .filter(o -> o.binding().issues().contains(issue))
                .map(o -> Objects.requireNonNull(o.startRefusal()))
                .map(
                        refusal ->
                                new org.fuin.porelo.planner.app.answers.TaskRefusal(
                                        refusal.name(), refusal.parameters(), refusal.detail()))
                .findFirst()
                .orElse(null);
    }

    private static boolean manual(final Board board, final String issue) {
        return board.issue(issue)
                .map(i -> i.worker() == org.fuin.porelo.planner.store.Worker.MANUAL)
                .orElse(false);
    }

    /**
     * Whether an issue is at work in a run: bound by a started offer, or its own place worked at by
     * a run the person started by hand there, judged on the board given or, without one, unchecked
     * against it.
     */
    static boolean atWork(final @Nullable Board board, final WorkRun.Run run, final String issue) {
        return run.offers().stream()
                        .anyMatch(
                                o ->
                                        o.status().equals("STARTED")
                                                && o.binding().issues().contains(issue))
                || WorkRun.againAt(run, issue, board);
    }

    static List<Finding> findings(final List<WorkEnds.Finding> findings) {
        return findings.stream()
                .map(
                        f ->
                                new Finding(
                                        f.number(),
                                        Finding.SeverityEnum.fromValue(f.severity().name()),
                                        f.file(),
                                        f.line(),
                                        f.text()))
                .toList();
    }

    static NextStep.@Nullable Progress progress(
            final WorkRun.Snapshot snapshot, final String issue) {
        return snapshot.runs().values().stream()
                .filter(r -> r.issues().containsKey(issue))
                .min(
                        Comparator.comparing((WorkRun.Run r) -> !atWork(null, r, issue))
                                .thenComparing(
                                        Comparator.comparingInt(
                                                        (WorkRun.Run r) ->
                                                                Objects.requireNonNull(
                                                                                r.issues()
                                                                                        .get(issue))
                                                                        .rounds()
                                                                        .rounds())
                                                .reversed())
                                .thenComparing(WorkRun.Run::story))
                .map(r -> Objects.requireNonNull(r.issues().get(issue)))
                .orElse(null);
    }

    static boolean atWork(final Board board, final WorkRun.Snapshot snapshot, final String issue) {
        return snapshot.runs().values().stream().anyMatch(run -> atWork(board, run, issue));
    }

    static boolean lost(final Board board, final WorkRun.Snapshot snapshot, final Issue issue) {
        if (issue.worker() == org.fuin.porelo.planner.store.Worker.MANUAL
                || !board.work().rules().containsKey(issue.state())) {
            return false;
        }
        final List<org.fuin.porelo.planner.store.Story> mine =
                board.stories().stream()
                        .filter(
                                s ->
                                        s.issues().contains(issue.id())
                                                || issue.parent() != null
                                                        && s.issues().contains(issue.parent()))
                        .toList();
        // The rule a hand move is let in by, so an issue moved beside an ended run reads as
        // elsewhere.
        return !WorkRun.held(snapshot)
                .over(
                        issue.id(),
                        mine.stream()
                                .map(org.fuin.porelo.planner.store.Story::id)
                                .collect(java.util.stream.Collectors.toSet()));
    }

    static org.fuin.porelo.planner.app.answers.Issue issue(
            final Board board, final Issue issue, final WorkRun.Snapshot snapshot) {
        return issue(board, issue, snapshot, OpenWork.minutesLeft(board));
    }

    /**
     * Answers an issue as the board shows it, with its minutes left read from those given.
     *
     * @param board the board
     * @param issue the issue
     * @param snapshot the runs
     * @param minutes each issue's minutes left, as {@link OpenWork#minutesLeft(Board)} answers
     * @return the issue's answer
     */
    static org.fuin.porelo.planner.app.answers.Issue issue(
            final Board board,
            final Issue issue,
            final WorkRun.Snapshot snapshot,
            final Map<String, Double> minutes) {
        // A person's issue needs no agent's choice, whatever a run recorded before the switch.
        final boolean manual = issue.worker() == org.fuin.porelo.planner.store.Worker.MANUAL;
        final NextStep.Progress progress = progress(snapshot, issue.id());
        // Work this planner did not start is only shown; it asks the person for nothing here.
        final boolean raised = !manual && progress != null && progress.needsYou();
        return new org.fuin.porelo.planner.app.answers.Issue(
                issue.version().value(),
                issue.id(),
                issue.repository(),
                issue.title(),
                issue.state(),
                issue.size(),
                issue.priority(),
                issue.labels(),
                decimal(minutes.get(issue.id())),
                issue.blockedBy(),
                waits(issue),
                issue.parent(),
                issue.link(),
                org.fuin.porelo.planner.app.answers.Worker.fromValue(issue.worker().name()),
                progress == null ? null : progress.rounds().rounds(),
                progress == null ? null : progress.rounds().roundsMost(),
                atWork(board, snapshot, issue.id()),
                raised,
                !manual && progress != null && progress.rounds().stopped(),
                // A run that raised the issue is this planner's, whatever its offers say.
                !raised && lost(board, snapshot, issue),
                org.fuin.porelo.planner.app.answers.Issue.AttentionEnum.fromValue(
                        raised && progress != null ? progress.reason() : "NONE"),
                progress == null ? null : findings(progress.findings()),
                warnings(issue.warnings()));
    }

    /**
     * What an issue waits on outside the board, as the answer carries it.
     *
     * @param issue the issue
     * @return its waits, in the store's order
     */
    static List<org.fuin.porelo.planner.app.answers.Wait> waits(final Issue issue) {
        return issue.waitsOn().stream()
                .map(
                        wait ->
                                new org.fuin.porelo.planner.app.answers.Wait(
                                        wait.reference(), wait.done(), wait.link()))
                .toList();
    }

    /**
     * Minutes as the answer carries them, rounded to one decimal, or null.
     *
     * @param minutes the minutes, or null
     * @return the number, or null
     */
    static @Nullable BigDecimal decimal(final @Nullable Double minutes) {
        return minutes == null
                ? null
                : BigDecimal.valueOf(minutes).setScale(1, RoundingMode.HALF_UP);
    }

    private static @Nullable TaskEnded ended(final WorkRun.@Nullable Ended ended) {
        if (ended == null) {
            return null;
        }
        final WorkEnds.Declared outcome = ended.outcome();
        return new TaskEnded(
                ended.at(),
                ended.finished(),
                ended.why(),
                ended.answer(),
                ended.unpushed(),
                // Left out while the work still waits at the gate.
                ended.landed().isEmpty()
                        ? null
                        : ended.landed().stream()
                                .map(l -> new Landing(l.branch(), l.ref(), l.remote(), l.commit()))
                                .toList(),
                outcome == null ? null : TaskEnded.OutcomeEnum.fromValue(outcome.name()));
    }

    private static PlanRepositoryWork repository(final WorkProposal.Repository repository) {
        final WorkProposal.Main main = repository.main();
        return new PlanRepositoryWork(
                repository.repository(),
                repository.issues(),
                new PlanWorker(
                        main.name(),
                        role(main.role()),
                        main.agent(),
                        main.model(),
                        main.brief(),
                        main.effort(),
                        said(main.basis()),
                        // Left out where the main is no proposal's, as in a run's record.
                        main.offered().isEmpty()
                                ? null
                                : main.offered().stream()
                                        .map(o -> new PlanOffer(o.agent(), o.model()))
                                        .toList()),
                repository.subAgents().stream()
                        .map(
                                sub ->
                                        new PlanAssignment(
                                                sub.id(),
                                                sub.issue(),
                                                sub.name(),
                                                sub.kind(),
                                                sub.agent(),
                                                sub.model(),
                                                sub.workingState(),
                                                sub.brief(),
                                                PlanAssignment.ChoiceEnum.fromValue(sub.choice()),
                                                sub.effort(),
                                                said(sub.basis())))
                        .toList());
    }

    /** Where a choice came from, or null when the rule said nothing. */
    private static @Nullable String said(final String basis) {
        return basis.isEmpty() ? null : basis;
    }
}
