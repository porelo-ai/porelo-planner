package org.fuin.porelo.planner.app;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import org.fuin.porelo.planner.app.answers.Finding;
import org.fuin.porelo.planner.app.answers.IssueReadingRounds;
import org.fuin.porelo.planner.app.answers.Landing;
import org.fuin.porelo.planner.app.answers.PlanAssignment;
import org.fuin.porelo.planner.app.answers.PlanRepositoryWork;
import org.fuin.porelo.planner.app.answers.PlanWork;
import org.fuin.porelo.planner.app.answers.PlanWorkIssue;
import org.fuin.porelo.planner.app.answers.PlanWorkTask;
import org.fuin.porelo.planner.app.answers.PlanWorker;
import org.fuin.porelo.planner.app.answers.PlanningStartTask;
import org.fuin.porelo.planner.app.answers.StoryWorkOrder;
import org.fuin.porelo.planner.app.answers.StoryWorkProposal;
import org.fuin.porelo.planner.app.answers.TaskEnded;
import org.fuin.porelo.planner.core.IdeaDiscussion;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.WorkEnds;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.jspecify.annotations.Nullable;

/**
 * Builds the work answers {@code openapi/planner.yaml} describes from the core's runs and
 * proposals; the answer records themselves are generated from the description.
 */
public final class WorkAnswers {

    private WorkAnswers() {}

    static PlanningStartTask discussion(final IdeaDiscussion discussion) {
        return new PlanningStartTask(
                UUID.randomUUID().toString(),
                null,
                List.of(),
                PlanningStartTask.RoleEnum.DISCUSSION,
                discussion.agent(),
                discussion.model(),
                discussion.effort(),
                said(discussion.basis()),
                discussion.mode(),
                discussion.prompt(),
                IdeaDiscussion.RESULT,
                List.of());
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
                proposal.manual());
    }

    static StoryWorkOrder order(final WorkRun.Order order) {
        return new StoryWorkOrder(order.revision(), starts(order.tasks()));
    }

    static List<PlanningStartTask> starts(final List<WorkRun.Offered> tasks) {
        return tasks.stream()
                .map(
                        offer -> {
                            final WorkOrder.Task task = offer.task();
                            return new PlanningStartTask(
                                    offer.offer(),
                                    task.repository(),
                                    task.issues(),
                                    PlanningStartTask.RoleEnum.fromValue(task.role()),
                                    task.agent(),
                                    task.model(),
                                    task.effort(),
                                    said(task.basis()),
                                    task.mode(),
                                    task.prompt(),
                                    null,
                                    task.reads());
                        })
                .toList();
    }

    static @Nullable PlanWork work(final Board board, final WorkRun.@Nullable Run run) {
        return run == null ? null : knownWork(board, run);
    }

    static PlanWork knownWork(final Board board, final WorkRun.Run run) {
        return knownWork(run, issue -> manual(board, issue));
    }

    /**
     * Answers the run, a person's issue reported as theirs: no task takes it and it waits on no
     * agent's choice, whatever the run recorded before the switch.
     */
    static PlanWork knownWork(final WorkRun.Run run, final Predicate<String> manual) {
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
                                                PlanWorkTask.RoleEnum.fromValue(
                                                        o.arguments().role()),
                                                String.valueOf(o.since()),
                                                o.status().equals("STARTED"),
                                                ended(o.ended())))
                        .toList();
        return new PlanWork(
                run.id(),
                tasks,
                tasks.stream()
                        .filter(t -> t.role() == PlanWorkTask.RoleEnum.MAIN && t.atWork())
                        .map(PlanWorkTask::task)
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
                                            atWork(run, entry.getKey()),
                                            p.needsYou(),
                                            !person && p.rounds().stopped(),
                                            findings(p.findings()));
                                })
                        .toList());
    }

    private static boolean manual(final Board board, final String issue) {
        return board.issue(issue)
                .map(i -> i.worker() == org.fuin.porelo.planner.store.Worker.MANUAL)
                .orElse(false);
    }

    static boolean atWork(final WorkRun.Run run, final String issue) {
        return run.offers().stream()
                .anyMatch(
                        o -> o.status().equals("STARTED") && o.binding().issues().contains(issue));
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
                        Comparator.comparing((WorkRun.Run r) -> !atWork(r, issue))
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

    static boolean atWork(final WorkRun.Snapshot snapshot, final String issue) {
        return snapshot.runs().values().stream().anyMatch(run -> atWork(run, issue));
    }

    static boolean lost(final Board board, final WorkRun.Snapshot snapshot, final Issue issue) {
        return issue.worker() != org.fuin.porelo.planner.store.Worker.MANUAL
                && board.work().rules().containsKey(issue.state())
                && board.stories().stream()
                        .filter(
                                s ->
                                        s.issues().contains(issue.id())
                                                || issue.parent() != null
                                                        && s.issues().contains(issue.parent()))
                        .anyMatch(s -> !snapshot.runs().containsKey(s.id()));
    }

    static org.fuin.porelo.planner.app.answers.Issue issue(
            final Board board, final Issue issue, final WorkRun.Snapshot snapshot) {
        // A person's issue needs no agent's choice, whatever a run recorded before the switch.
        final boolean manual = issue.worker() == org.fuin.porelo.planner.store.Worker.MANUAL;
        final NextStep.Progress progress = progress(snapshot, issue.id());
        // Work this planner did not start is only shown; it asks the person for nothing here.
        final boolean raised = !manual && progress != null && progress.needsYou();
        return new org.fuin.porelo.planner.app.answers.Issue(
                issue.id(),
                issue.repository(),
                issue.title(),
                issue.state(),
                issue.size(),
                issue.blockedBy(),
                issue.parent(),
                issue.link(),
                org.fuin.porelo.planner.app.answers.Worker.fromValue(issue.worker().name()),
                progress == null ? null : progress.rounds().rounds(),
                progress == null ? null : progress.rounds().roundsMost(),
                atWork(snapshot, issue.id()),
                raised,
                !manual && progress != null && progress.rounds().stopped(),
                progress == null && lost(board, snapshot, issue),
                org.fuin.porelo.planner.app.answers.Issue.AttentionEnum.fromValue(
                        raised && progress != null ? progress.reason() : "NONE"),
                progress == null ? null : findings(progress.findings()));
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
                        PlanWorker.RoleEnum.fromValue(main.role()),
                        main.agent(),
                        main.model(),
                        main.brief(),
                        main.effort(),
                        said(main.basis())),
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
