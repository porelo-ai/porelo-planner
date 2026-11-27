package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Version;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** Every write's targets, with dependencies and unrelated items left outside its basis. */
class TargetsTest {

    private static final StoryFromIssues FROM_ISSUES =
            new StoryFromIssues("A story", "A gain", "A test", "High", List.of("AB1", "AB2"));

    private static Story story(final String id, final List<String> issues, final String body) {
        return new Story(id, "A story", "Idea", "High", null, issues, body, null);
    }

    private static Board board() {
        return new Board(
                new Revision("before"),
                new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                List.of(
                        story("ST1", List.of("AB1", "AB2"), ""),
                        story("ST2", List.of("AB1"), FROM_ISSUES.marker()),
                        story("ST3", List.of("AB3"), FROM_ISSUES.marker())),
                List.of(issue("AB1"), issue("AB2"), issue("AB3"), issue("AB4")),
                new Work(Map.of(), List.of()),
                org.fuin.porelo.planner.store.ProjectRepository.named(List.of("sample")),
                List.of(),
                null,
                List.of(
                        new UnlistedIssue(
                                "sample#7",
                                "sample",
                                "Unlisted",
                                UnlistedIssue.Kind.ISSUE,
                                URI.create("https://example.org/sample/issues/7"))));
    }

    private static Issue issue(final String id) {
        return new Issue(id, "sample", "An issue", "Idea", null, List.of(), null, null, "");
    }

    private record Example(Operation operation, Set<String> targets) {}

    private static List<Example> examples() {
        return List.of(
                new Example(
                        new Operation.MoveBatch(
                                List.of(
                                        new Moves.Move(false, "AB1", "Idea"),
                                        new Moves.Move(false, "AB2", "Finished", "Done.")),
                                Set.of("AB3"),
                                Set.of("AB2")),
                        Set.of("AB1", "AB2", "AB3")),
                new Example(
                        new Operation.MoveBatch(
                                List.of(new Moves.Move(true, "ST1", "Idea")), Set.of(), Set.of()),
                        Set.of("ST1")),
                new Example(new Operation.CreateIssue(null), Set.of()),
                new Example(new Operation.CreateIssue("ST1"), Set.of("ST1")),
                new Example(new Operation.SetWorker("AB1"), Set.of("AB1")),
                new Example(new Operation.SetFields("AB1"), Set.of("AB1")),
                new Example(new Operation.SetBlockedBy("AB1"), Set.of("AB1")),
                new Example(new Operation.SetIssueText("AB1"), Set.of("AB1")),
                new Example(new Operation.SetStoryPriority("ST1"), Set.of("ST1")),
                new Example(new Operation.SetStoryText("ST1"), Set.of("ST1")),
                new Example(
                        new Operation.SetStoryIssues("ST1", List.of("AB2", "AB3")),
                        Set.of("ST1", "AB1", "AB3", "ST3")),
                new Example(
                        new Operation.SetStoryIssues("ST1", List.of("AB2", "AB1")), Set.of("ST1")),
                new Example(new Operation.StoryFromIdea(), Set.of()),
                new Example(
                        new Operation.CreateStoryFromIssues(FROM_ISSUES),
                        Set.of("AB1", "AB2", "ST2")),
                new Example(new Operation.AddUnlistedIssue("sample#7"), Set.of("sample#7")),
                new Example(
                        new Operation.OrderStoryWork("ST1", Set.of("AB1", "AB2", "AB3")),
                        Set.of("ST1", "AB1", "AB2", "AB3")),
                new Example(
                        new Operation.ReadingChoice("AB1", Set.of("AB2")), Set.of("AB1", "AB2")),
                new Example(new Operation.ReadingChoice("AB1", Set.of()), Set.of("AB1")),
                new Example(
                        new Operation.Correct(
                                new Corrections.Asked(false, "AB1", "NoState", "Idea")),
                        Set.of("AB1")),
                new Example(
                        new Operation.Correct(
                                new Corrections.Asked(true, "ST1", "NoState", "Idea")),
                        Set.of("ST1")));
    }

    @Test
    void eachWriteChecksExactlyTheItemsItChanges() {
        // Harm: an omitted target permits lost edits, and an extra target refuses unrelated work.
        for (final Example example : examples()) {
            assertThat(Targets.of(board(), example.operation()))
                    .as("the exact targets of %s", example.operation())
                    .containsExactlyInAnyOrderElementsOf(example.targets());
        }
    }

    @Test
    void aChangeOutsideEveryOperationsTargetsDoesNotRefuseTheWrite() {
        // Harm: concurrent work on an unrelated item prevents every write on the board.
        final Board before = board();
        final Board after =
                new Board(
                        new Revision("after"),
                        before.workflow(),
                        before.stories(),
                        before.issues().stream()
                                .map(
                                        issue ->
                                                issue.id().equals("AB4")
                                                        ? issue.withVersion(new Version("changed"))
                                                        : issue)
                                .toList(),
                        before.work(),
                        before.repositories(),
                        before.faults(),
                        before.owner(),
                        before.unlisted());
        Basis basis = Basis.none();
        for (final Issue issue : before.issues()) {
            basis = basis.with(issue.id(), issue.version());
        }
        for (final Story story : before.stories()) {
            basis = basis.with(story.id(), story.version());
        }
        for (final UnlistedIssue issue : before.unlisted()) {
            basis = basis.with(issue.id(), issue.version());
        }
        final Basis seen = basis;
        for (final Example example : examples()) {
            final Set<String> targets = Targets.of(after, example.operation());
            assertThat(targets)
                    .as("AB4 is unrelated to %s", example.operation())
                    .doesNotContain("AB4");
            assertThatCode(() -> Edits.unchanged(after, seen, targets))
                    .as("an unrelated change does not refuse %s", example.operation())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void aFirstStoryFromIssuesAttemptHasOnlyItsNamedIssuesAsTargets() {
        // Harm: a creation checks a story that does not exist and can never pass its basis check.
        final Board held = board();
        final Board first =
                new Board(held.revision(), held.workflow(), List.of(), held.issues(), held.work());
        assertThat(Targets.of(first, new Operation.CreateStoryFromIssues(FROM_ISSUES)))
                .as("no earlier story is invented")
                .containsExactlyInAnyOrder("AB1", "AB2");
    }

    @Test
    void computedEffectsAreTargetsEvenWhenTheHeldBoardDoesNotContainThem() {
        // Harm: a write drops carried or reconciled items from its checks because its board lacks
        // them.
        assertThat(
                        Targets.of(
                                board(),
                                new Operation.MoveBatch(
                                        List.of(new Moves.Move(false, "AB1", "Idea")),
                                        Set.of("AB8"),
                                        Set.of("AB9"))))
                .as("the move plan supplies every dynamic target")
                .containsExactlyInAnyOrder("AB1", "AB8", "AB9");
        assertThat(Targets.of(board(), new Operation.ReadingChoice("AB1", Set.of("AB8"))))
                .as("the work run supplies every dynamic target")
                .containsExactlyInAnyOrder("AB1", "AB8");
    }

    @Test
    void aMembershipEditOfAnAbsentOrDuplicatedStoryReportsMissingVersionsFirst() {
        // Harm: a caller without versions is told its items changed instead of which versions it
        // must send, and retries a write that can never pass.
        final Board held = board();
        final Story twice =
                story("ST1", List.of("AB1"), "").withWarnings(List.of(Warning.idTwice()));
        final Board duplicated =
                new Board(
                        held.revision(),
                        held.workflow(),
                        List.of(twice, twice),
                        held.issues(),
                        held.work());
        for (final Board board : List.of(held, duplicated)) {
            final String story = board == held ? "ST9" : "ST1";
            final Operation edit = new Operation.SetStoryIssues(story, List.of("AB1"));
            final Set<String> targets = Targets.of(board, edit);
            assertThatThrownBy(() -> Edits.unchanged(board, Basis.none(), targets))
                    .as("every missing version is named first for %s", story)
                    .isInstanceOfSatisfying(
                            StoreException.BasisIncomplete.class,
                            refusal ->
                                    assertThat(refusal.missing())
                                            .containsExactlyInAnyOrder(story, "AB1"));
            final Basis full =
                    Basis.none()
                            .with(story, new Version("seen"))
                            .with("AB1", issue("AB1").version());
            assertThatThrownBy(() -> Edits.unchanged(board, full, targets))
                    .as("with every version sent, the story is refused as changed: %s", story)
                    .isInstanceOfSatisfying(
                            StoreException.Changed.class,
                            refusal -> assertThat(refusal.ids()).containsExactly(story));
        }
    }

    @Test
    void aStoryCloseSentAgainChecksTheIssuesItWritesClosingCommentsOn() {
        // Harm: a retried story close writes closing comments on issues whose edits it never
        // checked, over a change made since they were read.
        final Board closed =
                new Board(
                        new Revision("closed"),
                        new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                        List.of(
                                new Story(
                                        "ST1",
                                        "A story",
                                        "Finished",
                                        "High",
                                        null,
                                        List.of("AB1"),
                                        "",
                                        null)),
                        List.of(
                                new Issue(
                                        "AB1",
                                        "sample",
                                        "An issue",
                                        "Finished",
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        ""),
                                new Issue(
                                        "AB1.1",
                                        "sample",
                                        "A part",
                                        "Finished",
                                        null,
                                        List.of(),
                                        "AB1",
                                        null,
                                        "")),
                        new Work(Map.of(), List.of()));
        final List<Moves.Move> moves = List.of(new Moves.Move(true, "ST1", "Finished", "Done."));
        final Moves.Planned planned = Moves.check(closed, moves, (id, s) -> false);
        assertThat(planned.issues()).as("no issue changes its state").isEmpty();
        assertThat(Targets.of(closed, Operation.MoveBatch.of(moves, planned)))
                .as("the story and every issue the plan comments on")
                .containsExactlyInAnyOrder("ST1", "AB1", "AB1.1");
    }
}
