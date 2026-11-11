package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.fuin.porelo.planner.core.IdeaDiscussion;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.WorkEnds;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.jspecify.annotations.Nullable;

/** The work proposal and Start argument records described by the HTTP contract. */
public final class WorkAnswers {

    private WorkAnswers() {}

    /** The complete proposal as the window reads it directly. */
    public record Proposal(
            List<String> modes,
            List<Repository> repositories,
            String coordinator,
            String comment,
            int readingRounds,
            List<IssueRounds> issueReadingRounds,
            String revision) {}

    /** The unchanged revision and individual tasks the window starts. */
    public record Order(String revision, List<StartTask> tasks) {}

    /** A proposed repository and the participants whose choices the window echoes. */
    public record Repository(
            String repository, List<String> issues, Worker main, List<Assignment> subAgents) {}

    /** The immutable identity and brief of the main, with its offered agent/model choice. */
    public record Worker(String name, String role, String agent, String model, String brief) {}

    /** An assignment's immutable facts and explicit KEEP, CHANGE or DROP choice. */
    public record Assignment(
            String id,
            String issue,
            String name,
            String kind,
            String agent,
            String model,
            String workingState,
            String brief,
            String choice) {}

    /** An issue's proposed reading limit. */
    public record IssueRounds(String issue, int rounds) {}

    /**
     * The arguments passed individually to Porelo's Start: a story's task names its repository, a
     * discussion reads every repository and names the file it hands back instead.
     */
    public record StartTask(
            String offer,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String repository,
            List<String> issues,
            String role,
            String agent,
            String model,
            String mode,
            String prompt,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String result) {}

    static StartTask discussion(final IdeaDiscussion discussion) {
        return new StartTask(
                UUID.randomUUID().toString(),
                null,
                List.of(),
                "DISCUSSION",
                discussion.agent(),
                discussion.model(),
                discussion.mode(),
                discussion.prompt(),
                IdeaDiscussion.RESULT);
    }

    static Proposal proposal(final WorkProposal proposal) {
        return new Proposal(
                proposal.modes(),
                proposal.repositories().stream().map(WorkAnswers::repository).toList(),
                proposal.coordinator(),
                proposal.comment(),
                proposal.readingRounds(),
                proposal.issueReadingRounds().stream()
                        .map(limit -> new IssueRounds(limit.issue(), limit.rounds()))
                        .toList(),
                proposal.revision().value());
    }

    /** One story's run, including its known reading state. */
    public record PlanWork(
            List<PlanWorkTask> tasks,
            @Nullable String coordinator,
            String since,
            boolean atWork,
            List<Repository> repositories,
            String comment,
            int readingRounds,
            List<IssueRounds> issueReadingRounds,
            List<PlanWorkIssue> issues) {}

    /** A task actually bound by a STARTED report. */
    public record PlanWorkTask(
            String task,
            String containerId,
            @Nullable String machine,
            String repository,
            List<String> issues,
            String state,
            String done,
            String mode,
            String agent,
            String model,
            String role,
            String since,
            boolean atWork,
            WorkRequests.@Nullable TaskEnded ended) {}

    /** A known issue's latest readings and reason for attention. */
    public record PlanWorkIssue(
            String issue,
            String reason,
            int rounds,
            int roundsMost,
            boolean atWork,
            boolean needsYou,
            boolean readingStopped,
            List<Finding> findings) {}

    /** A parsed, display-only finding. */
    public record Finding(
            int number,
            String severity,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String file,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Integer line,
            String text) {}

    /** The read and stop envelope, including null when no run is known. */
    public record StoryWorkReply(@Nullable PlanWork work) {}

    /** An accepted lifecycle report and any follow-up Start arguments. */
    public record TaskEventReply(String revision, PlanWork work, List<StartTask> tasks) {}

    /** A reading choice and the issue after its save. */
    public record ReadingReply(
            Answers.Issue issue, PlanWork work, String revision, List<StartTask> tasks) {}

    static Order order(final WorkRun.Order order) {
        return new Order(order.revision(), starts(order.tasks()));
    }

    static List<StartTask> starts(final List<WorkRun.Offered> tasks) {
        return tasks.stream()
                .map(
                        offer -> {
                            final WorkOrder.Task task = offer.task();
                            return new StartTask(
                                    offer.offer(),
                                    task.repository(),
                                    task.issues(),
                                    task.role(),
                                    task.agent(),
                                    task.model(),
                                    task.mode(),
                                    task.prompt(),
                                    null);
                        })
                .toList();
    }

    static @Nullable PlanWork work(final WorkRun.@Nullable Run run) {
        return run == null ? null : knownWork(run);
    }

    static PlanWork knownWork(final WorkRun.Run run) {
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
                                                o.arguments().role(),
                                                String.valueOf(o.since()),
                                                o.status().equals("STARTED"),
                                                ended(o.ended())))
                        .toList();
        return new PlanWork(
                tasks,
                tasks.stream()
                        .filter(t -> t.role().equals("MAIN") && t.atWork())
                        .map(PlanWorkTask::task)
                        .findFirst()
                        .orElse(null),
                run.since(),
                tasks.stream().anyMatch(PlanWorkTask::atWork),
                run.repositories().stream().map(WorkAnswers::repository).toList(),
                run.comment(),
                run.readingRounds(),
                run.issueReadingRounds().stream()
                        .map(l -> new IssueRounds(l.issue(), l.rounds()))
                        .toList(),
                run.issues().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey())
                        .map(
                                entry -> {
                                    final NextStep.Progress p = entry.getValue();
                                    return new PlanWorkIssue(
                                            entry.getKey(),
                                            p.reason(),
                                            p.rounds().rounds(),
                                            p.rounds().roundsMost(),
                                            atWork(run, entry.getKey()),
                                            p.needsYou(),
                                            p.rounds().stopped(),
                                            findings(p.findings()));
                                })
                        .toList());
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
                                        f.severity().name(),
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
        return board.work().rules().containsKey(issue.state())
                && board.stories().stream()
                        .filter(
                                s ->
                                        s.issues().contains(issue.id())
                                                || issue.parent() != null
                                                        && s.issues().contains(issue.parent()))
                        .anyMatch(s -> !snapshot.runs().containsKey(s.id()));
    }

    static Answers.Issue issue(
            final Board board, final Issue issue, final WorkRun.Snapshot snapshot) {
        final NextStep.Progress progress = progress(snapshot, issue.id());
        final var link = issue.link();
        return new Answers.Issue(
                issue.id(),
                issue.repository(),
                issue.title(),
                issue.state(),
                issue.size(),
                issue.blockedBy(),
                issue.parent(),
                link == null ? null : link.toString(),
                progress == null ? null : progress.rounds().rounds(),
                progress == null ? null : progress.rounds().roundsMost(),
                atWork(snapshot, issue.id()),
                progress == null ? lost(board, snapshot, issue) : progress.needsYou(),
                progress != null && progress.rounds().stopped(),
                progress == null ? null : findings(progress.findings()));
    }

    private static WorkRequests.@Nullable TaskEnded ended(final WorkRun.@Nullable Ended ended) {
        return ended == null
                ? null
                : new WorkRequests.TaskEnded(
                        ended.at(),
                        ended.finished(),
                        ended.why(),
                        ended.answer(),
                        ended.unpushed());
    }

    private static Repository repository(final WorkProposal.Repository repository) {
        final WorkProposal.Main main = repository.main();
        return new Repository(
                repository.repository(),
                repository.issues(),
                new Worker(main.name(), main.role(), main.agent(), main.model(), main.brief()),
                repository.subAgents().stream()
                        .map(
                                sub ->
                                        new Assignment(
                                                sub.id(),
                                                sub.issue(),
                                                sub.name(),
                                                sub.kind(),
                                                sub.agent(),
                                                sub.model(),
                                                sub.workingState(),
                                                sub.brief(),
                                                sub.choice()))
                        .toList());
    }
}
