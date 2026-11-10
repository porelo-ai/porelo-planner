package org.fuin.porelo.planner.app;

import java.util.List;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;

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

    /** The arguments passed individually to Porelo's Start. */
    public record StartTask(
            String repository,
            List<String> issues,
            String role,
            String agent,
            String model,
            String mode,
            String prompt) {}

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

    static Order order(final WorkOrder order) {
        return new Order(
                order.revision().value(),
                order.tasks().stream()
                        .map(
                                task ->
                                        new StartTask(
                                                task.repository(),
                                                task.issues(),
                                                task.role(),
                                                task.agent(),
                                                task.model(),
                                                task.mode(),
                                                task.prompt()))
                        .toList());
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
