package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WorkOrderTest {

    @Test
    void acceptingDefaultsOnlyReturnsTasksAndLeavesTheBoardAndRevisionAlone() {
        // Preparing work must neither move a story nor create a run without the window.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final Board before =
                new Board(
                        board.revision(),
                        board.workflow(),
                        board.stories(),
                        board.issues(),
                        board.work(),
                        board.repositories());
        final WorkOrder order =
                WorkOrder.accept(board, "ST1", "Building", decision(null, null, null, null));
        assertThat(order.tasks())
                .as("two mains and one open builder")
                .extracting(WorkOrder.Task::role)
                .containsExactly("MAIN", "BUILDER", "MAIN");
        assertThat(order.tasks().getFirst().issues())
                .as("main owns only its repository")
                .containsExactly("AA1", "AA1.1");
        assertThat(order.tasks().get(1).issues())
                .as("builder owns only its sub-issue")
                .containsExactly("AA1.1");
        assertThat(order.tasks().getFirst().prompt())
                .as("the main's expanded order and successful destination")
                .contains("ST1", "AA1", "alpha/AA1.1/BUILDER", "Review")
                .doesNotContain("BB1", "SECRET DESCRIPTION", "Private notes");
        assertThat(order.tasks().get(1).prompt())
                .as("sub-issue expansion has no siblings or helper tasks")
                .contains("Keep the data.")
                .doesNotContain("BB1", "alpha/AA1.1/BUILDER");
        assertThat(order.tasks())
                .as("selected default mode and model")
                .allSatisfy(
                        task -> {
                            assertThat(task.mode())
                                    .as("mode of the window's selection")
                                    .isEqualTo("AGENT");
                            assertThat(task.agent()).as("ordered default agent").isEqualTo("zeta");
                            assertThat(task.model()).as("ordered default model").isEqualTo("large");
                        });
        assertThat(board).as("acceptance preserves every board field").isEqualTo(before);
        assertThat(order.revision())
                .as("the order uses the original revision")
                .isEqualTo(before.revision());
    }

    @Test
    void storyWorkKeepsItsMainInTheOwnRepositoryAndBuildersWithTheirIssues() {
        // Grouping story work must not send builders into a checkout that lacks their sub-issue.
        final WorkOrder order =
                WorkOrder.accept(
                        WorkProposalTest.board(WorkRule.Per.STORY),
                        "ST1",
                        "Building",
                        decision(null, null, null, null));
        assertThat(order.tasks().getFirst().repository())
                .as("story main uses the own repository")
                .isEqualTo("project");
        assertThat(order.tasks().getFirst().issues())
                .as("story main names every open issue once")
                .containsExactly("AA1", "AA1.1", "BB1");
        assertThat(order.tasks().get(1).repository())
                .as("builder uses the issue's actual repository")
                .isEqualTo("alpha");
    }

    @Test
    void droppingAHelperGivesItsBriefToTheMainAndChangingUsesOnlyAnOfferedPair() {
        // Dropped work must not vanish and replacement work needs an explicit reviewed choice.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final List<WorkProposal.Repository> proposed =
                WorkProposal.propose(board, "ST1", "Building").repositories();
        final List<WorkProposal.Repository> dropped =
                changed(proposed, sub -> sub(sub, "DROP", sub.agent(), sub.model()));
        final WorkOrder order =
                WorkOrder.accept(
                        board,
                        "ST1",
                        "Building",
                        decision(
                                dropped,
                                "Do it yourself.",
                                3,
                                List.of(new WorkOrder.Limit("AA1.1", 4))));
        assertThat(order.tasks()).as("a dropped builder starts no task").hasSize(2);
        assertThat(order.tasks().getFirst().prompt())
                .as("main receives the entire dropped brief as its own work")
                .contains(
                        "Own work",
                        proposed.getFirst().subAgents().getFirst().brief(),
                        "Do it yourself.");
        final List<WorkProposal.Repository> replaced =
                changed(proposed, sub -> sub(sub, "CHANGE", "alpha", "small"));
        final WorkOrder replacement =
                WorkOrder.accept(
                        board,
                        "ST1",
                        "Building",
                        decision(replaced, "Use the other model.", null, null));
        assertThat(replacement.tasks().get(1).agent())
                .as("explicit offered replacement")
                .isEqualTo("alpha");
        assertThat(replacement.tasks().get(1).model())
                .as("the replacement's offered model")
                .isEqualTo("small");
        assertThat(replacement.tasks().get(1).prompt())
                .as("the operator comment reaches each retained helper")
                .contains("Use the other model.");
    }

    @Test
    void aStaleDecisionIsRefusedBeforeRecordedRunningWork() {
        // A repeated or outdated acceptance must not authorize duplicate work.
        final Board board = WorkProposalTest.board(WorkRule.Per.STORY);
        final WorkProposal.RunningWork running =
                story -> List.of("recorded-main", "recorded-builder");
        assertThatThrownBy(
                        () ->
                                WorkOrder.accept(
                                        board,
                                        "ST1",
                                        "Building",
                                        new WorkOrder.Decision(
                                                "AGENT", "old", null, null, null, null),
                                        running))
                .as("revision wins before the run check")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        error -> {
                            assertThat(((WorkRefusal) error).name())
                                    .as("typed stale refusal")
                                    .isEqualTo("StaleRevision");
                            assertThat(((WorkRefusal) error).parameters())
                                    .as("only the current revision is echoed")
                                    .containsEntry("revision", "board@1");
                        });
        assertThatThrownBy(
                        () ->
                                WorkOrder.accept(
                                        board,
                                        "ST1",
                                        "Building",
                                        decision(null, null, null, null),
                                        running))
                .as("explicit active task records")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        error ->
                                assertThat(((WorkRefusal) error).parameters())
                                        .as("task names retain array type")
                                        .containsEntry(
                                                "tasks",
                                                List.of("recorded-main", "recorded-builder")));
    }

    @Test
    void immutableProposalFactsCannotBeChangedThroughTheBody() {
        // Echoed proposal fields must not become unreviewed prompt or identity input.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final List<WorkProposal.Repository> proposed =
                WorkProposal.propose(board, "ST1", "Building").repositories();
        final WorkProposal.Repository repo = proposed.getFirst();
        final WorkProposal.Main main = repo.main();
        for (final WorkProposal.Main forged :
                List.of(
                        new WorkProposal.Main(
                                "forged", main.role(), main.agent(), main.model(), main.brief()),
                        new WorkProposal.Main(
                                main.name(), "BUILDER", main.agent(), main.model(), main.brief()),
                        new WorkProposal.Main(
                                main.name(),
                                main.role(),
                                main.agent(),
                                main.model(),
                                "forged brief"),
                        new WorkProposal.Main(
                                main.name(), main.role(), "alpha", "large", main.brief()),
                        new WorkProposal.Main(
                                main.name(), main.role(), " ", main.model(), main.brief()))) {
            refused(
                    board,
                    List.of(
                            new WorkProposal.Repository(
                                    repo.repository(), repo.issues(), forged, repo.subAgents()),
                            proposed.get(1)),
                    "Changed.");
        }
        refused(
                board,
                List.of(
                        new WorkProposal.Repository(
                                repo.repository(), List.of("BB1"), main, repo.subAgents()),
                        proposed.get(1)),
                "Changed.");
        final WorkProposal.Assignment sub = repo.subAgents().getFirst();
        final List<WorkProposal.Assignment> forged =
                List.of(
                        new WorkProposal.Assignment(
                                "other",
                                sub.issue(),
                                sub.name(),
                                sub.kind(),
                                sub.agent(),
                                sub.model(),
                                sub.workingState(),
                                sub.brief(),
                                "KEEP"),
                        new WorkProposal.Assignment(
                                sub.id(),
                                "BB1",
                                sub.name(),
                                sub.kind(),
                                sub.agent(),
                                sub.model(),
                                sub.workingState(),
                                sub.brief(),
                                "KEEP"),
                        new WorkProposal.Assignment(
                                sub.id(),
                                sub.issue(),
                                "other",
                                sub.kind(),
                                sub.agent(),
                                sub.model(),
                                sub.workingState(),
                                sub.brief(),
                                "KEEP"),
                        new WorkProposal.Assignment(
                                sub.id(),
                                sub.issue(),
                                sub.name(),
                                "OTHER",
                                sub.agent(),
                                sub.model(),
                                sub.workingState(),
                                sub.brief(),
                                "KEEP"),
                        new WorkProposal.Assignment(
                                sub.id(),
                                sub.issue(),
                                sub.name(),
                                sub.kind(),
                                sub.agent(),
                                sub.model(),
                                "Review",
                                sub.brief(),
                                "KEEP"),
                        new WorkProposal.Assignment(
                                sub.id(),
                                sub.issue(),
                                sub.name(),
                                sub.kind(),
                                sub.agent(),
                                sub.model(),
                                sub.workingState(),
                                "forged brief",
                                "KEEP"));
        for (final WorkProposal.Assignment fake : forged) {
            assertThatThrownBy(
                            () ->
                                    WorkOrder.accept(
                                            board,
                                            "ST1",
                                            "Building",
                                            decision(
                                                    changed(proposed, ignored -> fake),
                                                    "Changed.",
                                                    null,
                                                    null)))
                    .as("every helper fact is immutable")
                    .isInstanceOf(WorkRefusal.class)
                    .satisfies(
                            error ->
                                    assertThat(((WorkRefusal) error).parameters())
                                            .as("stable helper refusal reason")
                                            .containsEntry(
                                                    "reason",
                                                    "A helper identity or brief was changed."));
        }
        refused(
                board,
                changed(proposed, value -> sub(value, "KEEP", "alpha", "small")),
                "Changed.");
    }

    @Test
    void everyRepositoryAndAssignmentMustBeChosenExactlyOnce() {
        // Missing, duplicate or added participants would lose work or authorize extra tasks.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final List<WorkProposal.Repository> proposed =
                WorkProposal.propose(board, "ST1", "Building").repositories();
        final WorkProposal.Repository repo = proposed.getFirst();
        refused(board, List.of(), "");
        refused(board, List.of(repo, repo), "");
        refused(
                board,
                List.of(
                        new WorkProposal.Repository(
                                "unknown", repo.issues(), repo.main(), repo.subAgents()),
                        proposed.get(1)),
                "");
        refused(
                board,
                List.of(
                        new WorkProposal.Repository(
                                repo.repository(), repo.issues(), repo.main(), List.of()),
                        proposed.get(1)),
                "");
        refused(
                board,
                List.of(
                        new WorkProposal.Repository(
                                repo.repository(),
                                repo.issues(),
                                repo.main(),
                                List.of(repo.subAgents().getFirst(), repo.subAgents().getFirst())),
                        proposed.get(1)),
                "");
    }

    @Test
    void unknownChoicesAndUnreviewedOrUnofferedReplacementsAreRefused() {
        // Changing a worker without review or beyond the rule bypasses the operator's decision.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final List<WorkProposal.Repository> proposed =
                WorkProposal.propose(board, "ST1", "Building").repositories();
        for (final String choice : List.of("CHANGE", "DROP")) {
            refused(
                    board,
                    changed(proposed, sub -> sub(sub, choice, sub.agent(), sub.model())),
                    " \n");
        }
        refused(
                board,
                changed(proposed, sub -> sub(sub, "SURPRISE", sub.agent(), sub.model())),
                "Chosen.");
        refused(board, changed(proposed, sub -> sub(sub, "CHANGE", "unknown", "large")), "Chosen.");
        refused(board, changed(proposed, sub -> sub(sub, "CHANGE", "alpha", "large")), "Chosen.");
        refused(board, changed(proposed, sub -> sub(sub, "CHANGE", "alpha", " ")), "Chosen.");
        assertThatThrownBy(
                        () ->
                                WorkOrder.accept(
                                        board,
                                        "ST1",
                                        "Building",
                                        new WorkOrder.Decision(
                                                "UNATTENDED", "board@1", null, null, null, null)))
                .as("mode must be offered by the rule")
                .isInstanceOf(WorkRefusal.class);
    }

    @Test
    void limitsAreBoundedIntegersForDistinctProposedIssues() {
        // Invalid limits can bypass the reading stop or keep work running indefinitely.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        for (final Number invalid :
                List.<Number>of(0, -1, 11, new BigDecimal("1.5"), 2.0, Double.NaN)) {
            assertThatThrownBy(
                            () ->
                                    WorkOrder.accept(
                                            board,
                                            "ST1",
                                            "Building",
                                            decision(null, null, invalid, null)))
                    .as("overall rounds must be an integer from one to ten")
                    .isInstanceOf(WorkRefusal.class);
            assertThatThrownBy(
                            () ->
                                    WorkOrder.accept(
                                            board,
                                            "ST1",
                                            "Building",
                                            decision(
                                                    null,
                                                    null,
                                                    null,
                                                    List.of(new WorkOrder.Limit("AA1", invalid)))))
                    .as("issue rounds have the same bounds")
                    .isInstanceOf(WorkRefusal.class);
        }
        for (final List<WorkOrder.Limit> invalid :
                List.of(
                        List.of(new WorkOrder.Limit("AA1", 2), new WorkOrder.Limit("AA1", 3)),
                        List.of(new WorkOrder.Limit("AA1.2", 2)),
                        List.of(new WorkOrder.Limit("ZZ9", 2)))) {
            assertThatThrownBy(
                            () ->
                                    WorkOrder.accept(
                                            board,
                                            "ST1",
                                            "Building",
                                            decision(null, null, null, invalid)))
                    .as("overrides refer to each open proposed issue at most once")
                    .isInstanceOf(WorkRefusal.class);
        }
        assertThatThrownBy(
                        () ->
                                WorkOrder.accept(
                                        board,
                                        "ST1",
                                        "Building",
                                        decision(null, "x".repeat(4001), null, null)))
                .as("comments are bounded")
                .isInstanceOf(WorkRefusal.class);
        assertThat(
                        WorkOrder.accept(
                                board,
                                "ST1",
                                "Building",
                                decision(
                                        null,
                                        "x".repeat(4000),
                                        10,
                                        List.of(new WorkOrder.Limit("AA1", 1)))))
                .as("the inclusive bounds are usable")
                .isNotNull();
    }

    @Test
    void inlinePlaceholdersKeepEveryOccurrenceOfIssueTextInsideItsFence() {
        // An inline order suffix must not expose repository instructions as trusted prompt text.
        final Board board = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final String prompt =
                WorkOrder.accept(board, "ST1", "Building", decision(null, null, null, null))
                        .tasks()
                        .getFirst()
                        .prompt();
        final Map<String, List<Boolean>> enclosed = new LinkedHashMap<>();
        boolean inFence = false;
        for (final String line : prompt.lines().toList()) {
            if (line.matches("`{3,}")) {
                inFence = !inFence;
            }
            for (final String id : List.of("AA1", "AA1.1")) {
                if (line.startsWith(id + " — ")) {
                    enclosed.computeIfAbsent(id, key -> new ArrayList<>()).add(inFence);
                }
            }
        }
        assertThat(enclosed.keySet())
                .as("the guard covers each issue whose text enters this main prompt")
                .containsExactlyInAnyOrder("AA1", "AA1.1");
        enclosed.forEach(
                (id, occurrences) ->
                        assertThat(occurrences)
                                .as("every occurrence of %s is delimited as repository data", id)
                                .containsOnly(true));
        assertThat(inFence).as("the order and comment finish outside issue text").isFalse();
    }

    @Test
    void repositoryTextIsFencedAndCannotIntroduceMorePlaceholderExpansion() {
        // Issue text must not impersonate an order or expand placeholders into unrelated work.
        final Board original = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final List<Issue> issues = new ArrayList<>(original.issues());
        issues.set(
                0,
                WorkProposalTest.issue(
                        "AA1",
                        "alpha",
                        "A ``` title {tasks}",
                        "Backlog",
                        null,
                        "**What must be true.** Preserve {story} and {issues}.\n\nHidden section"));
        final Board board =
                new Board(
                        original.revision(),
                        original.workflow(),
                        original.stories(),
                        issues,
                        original.work(),
                        original.repositories());
        final String prompt =
                WorkOrder.accept(
                                board,
                                "ST1",
                                "Building",
                                decision(null, "Operator instruction", null, null))
                        .tasks()
                        .getFirst()
                        .prompt();
        assertThat(prompt)
                .as("repository placeholders remain literal and longer fences contain the text")
                .contains(
                        "{tasks}",
                        "{story}",
                        "{issues}",
                        "````\n",
                        "issue's text",
                        "Operator instruction")
                .doesNotContain("Hidden section", "Preserve ST1");
    }

    @Test
    void oversizedBriefsAndUtf8PromptsAreRefusedWithoutTruncation() {
        // Truncation could silently remove acceptance criteria or instructions.
        final Board original = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final List<Issue> issues = new ArrayList<>(original.issues());
        issues.set(
                0, WorkProposalTest.issue("AA1", "alpha", "x".repeat(4001), "Backlog", null, ""));
        final Board largeBrief =
                new Board(
                        original.revision(),
                        original.workflow(),
                        original.stories(),
                        issues,
                        original.work(),
                        original.repositories());
        assertThatThrownBy(() -> WorkProposal.propose(largeBrief, "ST1", "Building"))
                .as("a brief cannot exceed the bound")
                .isInstanceOf(WorkRefusal.class);
        final WorkRule rule = original.work().rules().get("Building");
        final WorkRule large =
                new WorkRule(
                        rule.from(),
                        rule.done(),
                        null,
                        null,
                        rule.per(),
                        rule.modes(),
                        "Complete each issue.",
                        rule.models());
        final List<Issue> manyIssues = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            manyIssues.add(
                    WorkProposalTest.issue(
                            "DD" + index, "alpha", "é".repeat(2800), "Backlog", null, ""));
        }
        final Board largePrompt =
                new Board(
                        original.revision(),
                        original.workflow(),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story",
                                        "Backlog",
                                        true,
                                        "High",
                                        null,
                                        manyIssues.stream().map(Issue::id).toList(),
                                        "",
                                        null)),
                        manyIssues,
                        new Work(Map.of("Building", large), List.of()),
                        original.repositories());
        assertThat(
                        WorkProposal.propose(largePrompt, "ST1", "Building")
                                .repositories()
                                .getFirst()
                                .issues())
                .as("each brief is valid before their combined prompt is checked")
                .containsExactlyElementsOf(manyIssues.stream().map(Issue::id).toList());
        assertThat("é".repeat(2800).getBytes(StandardCharsets.UTF_8).length * manyIssues.size())
                .as("fixture exceeds bytes rather than characters")
                .isGreaterThan(65536);
        assertThatThrownBy(
                        () ->
                                WorkOrder.accept(
                                        largePrompt,
                                        "ST1",
                                        "Building",
                                        decision(null, null, null, null)))
                .as("a prompt is refused in full")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        error ->
                                assertThat(((WorkRefusal) error).parameters())
                                        .as("the task prompt bound is the refusal")
                                        .containsEntry(
                                                "reason",
                                                "A task prompt exceeds 65536 UTF-8 bytes."));
    }

    @Test
    void aDecisionForAHarmTaskMayChooseOnlyWhatHarmOffersAndItsTasksKeepHarmsEffort() {
        // A window choosing the rule's light model for work that can do harm would bypass harm's
        // choice; an effort lost on the way would start it without the care the rule names.
        final Board board =
                WorkProposalTest.risked(
                        WorkProposalTest.board(WorkRule.Per.REPOSITORY),
                        Map.of("AA1.1", Risk.HARM));
        final List<WorkProposal.Repository> proposed =
                WorkProposal.propose(board, "ST1", "Building").repositories();
        final WorkProposal.Repository first = proposed.getFirst();
        final WorkProposal.Main main = first.main();
        refused(
                board,
                List.of(
                        new WorkProposal.Repository(
                                first.repository(),
                                first.issues(),
                                new WorkProposal.Main(
                                        main.name(), main.role(), "alpha", "small", main.brief()),
                                first.subAgents()),
                        proposed.get(1)),
                "Lighter.");
        refused(board, changed(proposed, sub -> sub(sub, "CHANGE", "alpha", "small")), "Lighter.");
        final WorkOrder order =
                WorkOrder.accept(
                        board,
                        "ST1",
                        "Building",
                        decision(
                                List.of(
                                        new WorkProposal.Repository(
                                                first.repository(),
                                                first.issues(),
                                                new WorkProposal.Main(
                                                        main.name(),
                                                        main.role(),
                                                        "zeta",
                                                        "large",
                                                        main.brief(),
                                                        main.effort(),
                                                        main.basis()),
                                                first.subAgents()),
                                        proposed.get(1)),
                                null,
                                null,
                                null));
        assertThat(order.tasks())
                .as(
                        "a model harm offers is taken, and each task keeps the effort its decision kept")
                .extracting(WorkOrder.Task::role, WorkOrder.Task::model, WorkOrder.Task::effort)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MAIN", "large", "max"),
                        org.assertj.core.groups.Tuple.tuple("BUILDER", "huge", "max"),
                        org.assertj.core.groups.Tuple.tuple("MAIN", "large", null));
    }

    @Test
    void theEffortAPersonChoseIsTheOneTheMainStartsWithAndNoneIsNone() {
        // The window starting another effort than the person chose, or the rule's where the
        // window said it starts none.
        final Board board = harmBoard();
        final List<WorkProposal.Repository> proposed =
                WorkProposal.propose(board, "ST1", "Building").repositories();
        assertThat(
                        WorkOrder.accept(
                                        board,
                                        "ST1",
                                        "Building",
                                        decision(mainEffort(proposed, "low"), null, null, null))
                                .tasks()
                                .getFirst())
                .as("the changed effort reaches the main's Start, and is the one it used")
                .extracting(WorkOrder.Task::effort, WorkOrder.Task::used)
                .containsExactly("low", "low");
        assertThat(
                        WorkOrder.accept(
                                        board,
                                        "ST1",
                                        "Building",
                                        decision(mainEffort(proposed, null), null, null, null))
                                .tasks()
                                .getFirst()
                                .effort())
                .as("a main sent back without an effort starts with none")
                .isNull();
    }

    @Test
    void anEffortThatIsNoSoundNameIsRefusedBeforeAnyTask() {
        // A name reaching the window's Start unchecked.
        final Board board = harmBoard();
        final List<WorkProposal.Repository> proposed =
                WorkProposal.propose(board, "ST1", "Building").repositories();
        for (final String unsound :
                List.of("", "  ", "high\nlow", "high ", "x".repeat(WorkRule.LONGEST + 1))) {
            refused(board, mainEffort(proposed, unsound), "");
            refused(
                    board,
                    changed(proposed, sub -> effort(sub, "CHANGE", unsound)),
                    "Another effort.");
        }
    }

    @Test
    void aKeptHelperStartsWithTheOffersEffortSentBackOrNotAndOnlyAChangeChangesIt() {
        // A person who kept the proposal gets a helper started without the effort they were shown,
        // or with one nobody chose for it.
        final Board board = harmBoard();
        final List<WorkProposal.Repository> proposed =
                WorkProposal.propose(board, "ST1", "Building").repositories();
        refused(board, changed(proposed, sub -> effort(sub, "KEEP", "low")), "");
        assertThat(builder(board, changed(proposed, sub -> effort(sub, "KEEP", null)), ""))
                .as("a kept helper sent back without an effort starts with the offer's")
                .extracting(WorkOrder.Task::effort)
                .isEqualTo("max");
        assertThat(builder(board, changed(proposed, sub -> effort(sub, "KEEP", "max")), ""))
                .as("a kept helper keeps the offer's effort")
                .extracting(WorkOrder.Task::effort)
                .isEqualTo("max");
        assertThat(
                        builder(
                                board,
                                changed(proposed, sub -> effort(sub, "CHANGE", "low")),
                                "Lighter."))
                .as("a changed helper starts with the effort chosen")
                .extracting(WorkOrder.Task::effort, WorkOrder.Task::used)
                .containsExactly("low", "low");
    }

    @Test
    void whereAChoiceCameFromIsTheRulesWhateverTheDecisionSays() {
        // A caller's words standing as the rule's reason for a model and effort.
        final Board board = harmBoard();
        final List<WorkProposal.Repository> proposed =
                WorkProposal.propose(board, "ST1", "Building").repositories();
        final WorkProposal.Repository first = proposed.getFirst();
        final WorkProposal.Main main = first.main();
        final List<WorkProposal.Repository> forged =
                List.of(
                        new WorkProposal.Repository(
                                first.repository(),
                                first.issues(),
                                new WorkProposal.Main(
                                        main.name(),
                                        main.role(),
                                        main.agent(),
                                        main.model(),
                                        main.brief(),
                                        "low",
                                        "Forged"),
                                first.subAgents().stream()
                                        .map(
                                                sub ->
                                                        new WorkProposal.Assignment(
                                                                sub.id(),
                                                                sub.issue(),
                                                                sub.name(),
                                                                sub.kind(),
                                                                sub.agent(),
                                                                sub.model(),
                                                                sub.workingState(),
                                                                sub.brief(),
                                                                sub.choice(),
                                                                sub.effort(),
                                                                "Forged"))
                                        .toList()),
                        proposed.get(1));
        final WorkOrder order =
                WorkOrder.accept(board, "ST1", "Building", decision(forged, null, null, null));
        assertThat(order.repositories().getFirst().main().basis())
                .as("the accepted main names the rule's choice")
                .isEqualTo(main.basis());
        assertThat(order.repositories().getFirst().subAgents().getFirst().basis())
                .as("the accepted helper names the rule's choice")
                .isEqualTo(first.subAgents().getFirst().basis());
        assertThat(order.tasks())
                .as("every task names the rule's choice, none the caller's")
                .extracting(WorkOrder.Task::basis)
                .doesNotContain("Forged")
                .doesNotContain("");
        assertThat(order.repositories().getFirst().main().effort())
                .as("the accepted main keeps the effort chosen")
                .isEqualTo("low");
    }

    private static Board harmBoard() {
        return WorkProposalTest.risked(
                WorkProposalTest.board(WorkRule.Per.REPOSITORY), Map.of("AA1.1", Risk.HARM));
    }

    private static WorkOrder.Task builder(
            final Board board, final List<WorkProposal.Repository> repos, final String comment) {
        return WorkOrder.accept(board, "ST1", "Building", decision(repos, comment, null, null))
                .tasks()
                .stream()
                .filter(task -> task.role().equals("BUILDER"))
                .findFirst()
                .orElseThrow();
    }

    private static List<WorkProposal.Repository> mainEffort(
            final List<WorkProposal.Repository> proposed, final @Nullable String effort) {
        final WorkProposal.Repository first = proposed.getFirst();
        final WorkProposal.Main main = first.main();
        return List.of(
                new WorkProposal.Repository(
                        first.repository(),
                        first.issues(),
                        new WorkProposal.Main(
                                main.name(),
                                main.role(),
                                main.agent(),
                                main.model(),
                                main.brief(),
                                effort,
                                main.basis()),
                        first.subAgents()),
                proposed.get(1));
    }

    private static WorkProposal.Assignment effort(
            final WorkProposal.Assignment source,
            final String choice,
            final @Nullable String effort) {
        return new WorkProposal.Assignment(
                source.id(),
                source.issue(),
                source.name(),
                source.kind(),
                source.agent(),
                source.model(),
                source.workingState(),
                source.brief(),
                choice,
                effort,
                source.basis());
    }

    private static WorkOrder.Decision decision(
            final @Nullable List<WorkProposal.Repository> repos,
            final @Nullable String comment,
            final @Nullable Number rounds,
            final @Nullable List<WorkOrder.Limit> overrides) {
        return new WorkOrder.Decision("AGENT", "board@1", repos, comment, rounds, overrides);
    }

    private static List<WorkProposal.Repository> changed(
            final List<WorkProposal.Repository> proposed,
            final UnaryOperator<WorkProposal.Assignment> change) {
        final WorkProposal.Repository first = proposed.getFirst();
        return List.of(
                new WorkProposal.Repository(
                        first.repository(),
                        first.issues(),
                        first.main(),
                        first.subAgents().stream().map(change).toList()),
                proposed.get(1));
    }

    private static WorkProposal.Assignment sub(
            final WorkProposal.Assignment source,
            final String choice,
            final String agent,
            final String model) {
        return new WorkProposal.Assignment(
                source.id(),
                source.issue(),
                source.name(),
                source.kind(),
                agent,
                model,
                source.workingState(),
                source.brief(),
                choice);
    }

    private static void refused(
            final Board board, final List<WorkProposal.Repository> repos, final String comment) {
        assertThatThrownBy(
                        () ->
                                WorkOrder.accept(
                                        board,
                                        "ST1",
                                        "Building",
                                        decision(repos, comment, null, null)))
                .as("forged or incomplete choices must not produce a task")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(
                        error ->
                                assertThat(((WorkRefusal) error).name())
                                        .as("typed semantic refusal")
                                        .isEqualTo("WorkNotAllowed"));
    }
}
