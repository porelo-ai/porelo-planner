package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The readiness check before the backlog: started, handed in, and saved split by its verdicts. */
class ReadinessCheckTest {

    private static final String WORKING = "Preparing for Backlog";

    private static final String BACK = "Estimated";

    private static final String DONE = "Backlog";

    // Harm: a story dropped on the readiness check starts one task for all repositories, or offers
    // an issue the check does not start from.
    @Test
    void aDropStartsOneTaskPerRepositoryOverTheEstimatedIssuesAlone() {
        final Check f = new Check();

        final List<WorkRun.Offered> tasks = f.order().tasks();

        assertThat(tasks)
                .as("one task per repository, each with its estimated issues")
                .extracting(offered -> offered.task().repository() + " " + offered.task().issues())
                .containsExactlyInAnyOrder("alpha [AA1, AA2]", "beta [BB1]");
        assertThat(tasks)
                .as("each hands back the readiness file")
                .allSatisfy(
                        offered -> assertThat(offered.task().result()).isEqualTo("readiness.json"));
        assertThat(tasks)
                .as("the analyzed issue is offered nothing")
                .noneSatisfy(offered -> assertThat(offered.task().issues()).contains("AA4"));
    }

    // Harm: the check judges an issue on its text alone and calls one not ready for a size or a
    // blocker the board holds beside the text, or its task writes a file the planner refuses.
    @Test
    void eachGivenFileOpensWithTheBoardsFieldsAndThePromptNamesTheReadinessMembers() {
        final Check f = new Check();

        final WorkOrder.Task beta =
                f.order().tasks().stream()
                        .map(WorkRun.Offered::task)
                        .filter(task -> task.repository().equals("beta"))
                        .findFirst()
                        .orElseThrow();

        assertThat(beta.given())
                .as("the board's fields, then the issue's text")
                .containsExactly(
                        new WorkOrder.Given(
                                "BB1.md",
                                "The board's fields of BB1, beside its text below:\n"
                                        + "- Size: M\n"
                                        + "- Blocked by: BB0\n"
                                        + "- Waits on: none\n\n"
                                        + "**What must be true.** It works."));
        assertThat(beta.prompt())
                .as("the members of a readiness entry and the bound of why")
                .contains("ready (true or false", "lacks (a list of PLAN, SIZE, BLOCKERS")
                .contains("why is one line of at most 200 characters")
                .doesNotContain("sizeWhy");
    }

    // Harm: every issue moved to the backlog whatever the check found, or the reason an issue is
    // not ready lost.
    @Test
    void theReadyIssuesMoveToTheBacklogAndOneWithoutAPlanGoesBackWithItsReason() {
        final Check f = new Check();
        final WorkRun.Offered alpha = f.started("alpha");
        final WorkRun.Offered beta = f.started("beta");

        f.handIn(alpha, hand(ready("AA1"), notReady("AA2", List.of("PLAN"), "No plan section.")));
        f.handIn(beta, hand(ready("BB1")));

        assertThat(
                        Stream.of("AA1", "AA2", "BB1", "AA4")
                                .map(id -> id + " " + f.stage.store.state(id)))
                .as("ready ones in the backlog, the one without a plan back, the analyzed kept")
                .containsExactly("AA1 " + DONE, "AA2 " + BACK, "BB1 " + DONE, "AA4 Analyzed");
        assertThat(f.stage.store.results.getFirst().verdicts())
                .as("one save of the first task's verdicts, the reason in fixed words")
                .containsExactly(
                        new StageResult.Verdict("AA1", WORKING, DONE, null),
                        new StageResult.Verdict(
                                "AA2",
                                WORKING,
                                BACK,
                                "Not ready: it lacks a plan. No plan section."));
    }

    // Harm: a result that leaves an issue out moves the others, so the left-out one stays in the
    // working state with nobody at work on it.
    @Test
    void aResultMissingAnIssueMovesNothing() {
        final Check f = new Check();
        final WorkRun.Offered alpha = f.started("alpha");

        final WorkRefusal refused =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(alpha, hand(ready("AA1"))));

        assertThat(refused).as("the result is refused").isNotNull();
        assertThat(f.stage.store.results).as("nothing saved").isEmpty();
        assertThat(f.stage.store.state("AA1")).as("AA1 stays").isEqualTo(WORKING);
    }

    static Stream<Arguments> misnamed() {
        return Stream.of(
                Arguments.of(
                        "an issue named twice",
                        List.of("AA1", "AA1", "AA2"),
                        "issues[1].id: The issue AA1 is named twice."),
                Arguments.of(
                        "an issue outside the order",
                        List.of("AA1", "AA2", "BB1"),
                        "issues[2].id: The id is none of the order's issues."));
    }

    // Harm: a result that names an issue twice saves two verdicts for one issue, or one that names
    // another task's issue moves an issue this task was never given.
    @ParameterizedTest(name = "{0}")
    @MethodSource("misnamed")
    void aResultNamingAnIssueTwiceOrOutsideTheOrderMovesNothing(
            final String what, final List<String> ids, final String fault) {
        final HandedResults.Entry[] entries =
                ids.stream().map(ReadinessCheckTest::ready).toArray(HandedResults.Entry[]::new);
        assertThat(
                        HandedResults.check(
                                WorkRule.Result.READINESS, List.of("AA1", "AA2"), hand(entries)))
                .as("the check names the issue at fault")
                .contains(fault);
        final Check f = new Check();
        final WorkRun.Offered alpha = f.started("alpha");
        final List<String> before =
                Stream.of("AA1", "AA2", "BB1")
                        .map(id -> id + " " + f.stage.store.state(id))
                        .toList();

        final WorkRefusal refused =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(alpha, hand(entries)));

        assertThat(refused).as("the result is refused").isNotNull();
        assertThat(f.stage.store.results).as("nothing saved").isEmpty();
        assertThat(Stream.of("AA1", "AA2", "BB1").map(id -> id + " " + f.stage.store.state(id)))
                .as("every issue stays where it was")
                .containsExactlyElementsOf(before);
        assertThat(before).as("the check's issues are at work").startsWith("AA1 " + WORKING);
    }

    static Stream<Arguments> unsound() {
        return Stream.of(
                Arguments.of(
                        "ready with lacks",
                        entry("AA1", true, List.of("PLAN"), null),
                        "issues[0]: A ready issue names no lacks and no why."),
                Arguments.of(
                        "ready with an empty lacks",
                        entry("AA1", true, List.of(), null),
                        "issues[0]: A ready issue names no lacks and no why."),
                Arguments.of(
                        "no ready",
                        entry("AA1", null, null, null),
                        "issues[0].ready: Each issue must say whether it is ready, true or false."),
                Arguments.of(
                        "not ready without lacks",
                        entry("AA1", false, null, "Nothing."),
                        "issues[0].lacks: An issue not ready lacks one to three of PLAN, SIZE,"
                                + " BLOCKERS, each once."),
                Arguments.of(
                        "a lack twice",
                        entry("AA1", false, List.of("SIZE", "SIZE"), "Nothing."),
                        "issues[0].lacks: An issue not ready lacks one to three of PLAN, SIZE,"
                                + " BLOCKERS, each once."),
                Arguments.of(
                        "an unknown lack",
                        entry("AA1", false, List.of("TESTS"), "Nothing."),
                        "issues[0].lacks: An issue not ready lacks one to three of PLAN, SIZE,"
                                + " BLOCKERS, each once."),
                Arguments.of(
                        "not ready without why",
                        entry("AA1", false, List.of("PLAN"), null),
                        "issues[0].why: An issue not ready needs one line why."),
                Arguments.of(
                        "a why too long",
                        entry("AA1", false, List.of("PLAN"), "w".repeat(201)),
                        "issues[0].why: Why it is not ready must be one line of 1 to 200"
                                + " characters."),
                Arguments.of(
                        "a why with a private character",
                        entry("AA1", false, List.of("PLAN"), "Odd \uE000 sign."),
                        "issues[0].why: Why it is not ready holds a character a reason may not."),
                Arguments.of(
                        "an analysis's member",
                        new HandedResults.Entry(
                                "AA1", null, "M", null, null, null, null, true, null, null),
                        "issues[0]: A readiness check names only id, ready, lacks and why."));
    }

    // Harm: a malformed readiness entry saved, or refused with words that do not say what to fix.
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsound")
    void anUnsoundEntryIsRefusedNamingItsField(
            final String name, final HandedResults.Entry entry, final String fault) {
        assertThat(HandedResults.check(WorkRule.Result.READINESS, List.of("AA1"), hand(entry)))
                .as(name)
                .contains(fault);
    }

    // Harm: an analysis that carries a readiness member saved as if the member meant something.
    @Test
    void anAnalysisNamingReadyIsRefused() {
        final HandedResults.Entry entry =
                new HandedResults.Entry(
                        "AA1", "Plan.", "M", "One module.", null, null, null, true, null, null);
        assertThat(
                        HandedResults.check(
                                WorkRule.Result.ANALYSIS,
                                List.of("AA1"),
                                new HandedResults.HandIn(
                                        new HandedResults.File(
                                                StageOrder.FORMAT, "ANALYSIS", List.of(entry)),
                                        null,
                                        List.of())))
                .as("refused")
                .containsExactly("issues[0]: Only a readiness check names ready, lacks and why.");
    }

    // Harm: the kept reason's words, and so its comment's hash, change with the order the task
    // listed what an issue lacks, or the longest reason breaks the bound a reason is kept in.
    @Test
    void theReasonNamesWhatIsLackingInOneOrderAndStaysInItsBound() {
        assertThat(HandedResults.reason(List.of("SIZE", "PLAN"), " Thin. "))
                .as("two, in the fixed order, why stripped")
                .isEqualTo("Not ready: it lacks a plan and a size. Thin.");
        assertThat(HandedResults.reason(List.of("BLOCKERS"), "x"))
                .as("one")
                .isEqualTo("Not ready: it lacks its blockers. x");
        final String longest =
                HandedResults.reason(
                        List.of("BLOCKERS", "SIZE", "PLAN"), "w".repeat(HandedResults.MOST_WHY));
        assertThat(longest)
                .as("three")
                .startsWith("Not ready: it lacks a plan, a size and its blockers. ");
        assertThat(Writes.Reason.refusal(longest)).as("the longest a reason may be").isNull();
    }

    static HandedResults.HandIn hand(final HandedResults.Entry... entries) {
        return new HandedResults.HandIn(
                new HandedResults.File(StageOrder.FORMAT, "READINESS", List.of(entries)),
                null,
                List.of());
    }

    static HandedResults.Entry ready(final String id) {
        return entry(id, true, null, null);
    }

    static HandedResults.Entry notReady(
            final String id, final List<String> lacks, final String why) {
        return entry(id, false, lacks, why);
    }

    static HandedResults.Entry entry(
            final String id,
            final @Nullable Boolean ready,
            final @Nullable List<String> lacks,
            final @Nullable String why) {
        return new HandedResults.Entry(id, null, null, null, null, null, null, ready, lacks, why);
    }

    /** A story of estimated issues in two repositories, and one analyzed, before the backlog. */
    static final class Check {
        final HandedResultsTest.Stage stage = new HandedResultsTest.Stage();
        final List<String> told = new ArrayList<>();
        final WorkRun run =
                new WorkRun(
                        stage.store,
                        stage.record,
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                        told::add);

        Check() {
            final WorkRule readiness =
                    new WorkRule(
                            List.of(BACK),
                            DONE,
                            null,
                            null,
                            WorkRule.Per.REPOSITORY,
                            List.of("AGENT"),
                            "Check {story}: {issues}; {tasks}",
                            Map.of("codex", "model"),
                            Map.of(),
                            WorkRule.Result.READINESS,
                            Roles.forRule(null, WorkRule.Result.READINESS));
            stage.store.current =
                    new Board(
                            new Revision("board@1"),
                            new Workflow(
                                    List.of("Analyzed", BACK, WORKING, DONE, "Finished"),
                                    List.of(),
                                    List.of("Finished")),
                            List.of(
                                    new Story(
                                            "ST1",
                                            "Story",
                                            BACK,
                                            "High",
                                            null,
                                            List.of("AA1", "AA2", "BB1", "AA4"),
                                            "",
                                            null)),
                            List.of(
                                    issue("AA1", "alpha", BACK),
                                    issue("AA2", "alpha", BACK),
                                    blocked(issue("BB1", "beta", BACK), "BB0"),
                                    issue("BB0", "beta", "Finished"),
                                    issue("AA4", "alpha", "Analyzed")),
                            new Work(Map.of(WORKING, readiness), List.of()),
                            org.fuin.porelo.planner.store.ProjectRepository.named(
                                    List.of("alpha", "beta")));
        }

        private static Issue issue(final String id, final String repository, final String state) {
            return new Issue(
                    id,
                    repository,
                    "Issue " + id,
                    state,
                    "M",
                    List.of(),
                    null,
                    null,
                    "**What must be true.** It works.",
                    Worker.AGENT,
                    Risk.ORDINARY);
        }

        private static Issue blocked(final Issue issue, final String by) {
            return new Issue(
                    issue.id(),
                    issue.repository(),
                    issue.title(),
                    issue.state(),
                    issue.size(),
                    List.of(by),
                    issue.parent(),
                    issue.link(),
                    issue.text(),
                    issue.worker(),
                    issue.risk());
        }

        WorkRun.Order order() {
            return run.order(
                    "ST1",
                    WORKING,
                    new WorkOrder.Decision(
                            "AGENT", Basis.of(stage.store.board()), null, "", 1, null),
                    WorkRun.Start.NONE);
        }

        private WorkRun.@Nullable Order ordered;

        WorkRun.Offered started(final String repository) {
            if (ordered == null) {
                ordered = order();
            }
            final WorkRun.Offered offer =
                    ordered.tasks().stream()
                            .filter(o -> o.task().repository().equals(repository))
                            .findFirst()
                            .orElseThrow();
            run.report(
                    "task-" + repository,
                    new WorkRun.Report(
                            offer.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
            return offer;
        }

        HandedResults.Answer handIn(
                final WorkRun.Offered offer, final HandedResults.HandIn handIn) {
            return run.result("task-" + offer.task().repository(), offer.offer(), handIn);
        }
    }
}
