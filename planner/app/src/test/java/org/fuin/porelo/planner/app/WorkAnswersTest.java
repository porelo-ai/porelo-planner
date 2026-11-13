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

    private static ReadingCycle.Rounds rounds() {
        return new ReadingCycle.Rounds(0, 2, false);
    }

    private static WorkRun.Snapshot run(final NextStep.Progress progress) {
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
                                List.of(),
                                Map.of("AA1", progress),
                                true,
                                false,
                                false)));
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
                                false,
                                "High",
                                null,
                                List.of("AA1", "AA2"),
                                "",
                                null)),
                List.of(AGENTS, MANUAL),
                new Work(Map.of("Building", build), List.of()),
                List.of("alpha"));
    }
}
