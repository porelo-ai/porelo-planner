package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Version;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** Missing, changed and ambiguous edit bases fail closed without checking unrelated entries. */
class UnchangedTest {

    private static final Version SEEN = new Version("seen");

    private static Board board() {
        return new Board(
                new Revision("now"),
                new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                List.of(
                        new Story("ST1", "Story", "Idea", "High", null, List.of("AB1"), "", null)
                                .withVersion(SEEN)),
                List.of(
                        new Issue("AB1", "sample", "Issue", "Idea", null, List.of(), null, null, "")
                                .withVersion(SEEN)),
                new Work(Map.of(), List.of()),
                List.of("sample"),
                List.of(),
                null,
                List.of(
                        new UnlistedIssue(
                                "sample#7",
                                "sample",
                                "Issue",
                                UnlistedIssue.Kind.ISSUE,
                                URI.create("https://example.org/sample/issues/7"),
                                null,
                                null,
                                SEEN)));
    }

    @Test
    void everyMissingVersionIsReportedBeforeAnyChangedTarget() {
        // Harm: a missing basis entry bypasses a write's concurrency check.
        final StoreException.BasisIncomplete refusal =
                catchThrowableOfType(
                        StoreException.BasisIncomplete.class,
                        () ->
                                Edits.unchanged(
                                        board(),
                                        Basis.none().with("AB1", new Version("old")),
                                        Set.of("AB1", "ST1", "sample#7", "AB9")));
        assertThat(refusal)
                .as("incompleteness takes precedence over a changed version")
                .isNotNull();
        assertThat(refusal.missing())
                .as("every missing target, even an absent item")
                .containsExactlyInAnyOrder("ST1", "sample#7", "AB9");
    }

    @Test
    void changedAndAbsentTargetsAreAllNamed() {
        // Harm: a write overwrites changed items or silently drops targets no longer readable.
        final Basis old =
                Basis.none()
                        .with("AB1", new Version("old"))
                        .with("ST1", new Version("old"))
                        .with("sample#7", new Version("old"))
                        .with("AB9", SEEN);
        final StoreException.Changed refusal =
                catchThrowableOfType(
                        StoreException.Changed.class,
                        () -> Edits.unchanged(board(), old, old.items().keySet()));
        assertThat(refusal).as("changed targets refuse the whole check").isNotNull();
        assertThat(refusal.ids())
                .as("all kinds of items, including the absent target")
                .containsExactlyInAnyOrder("AB1", "ST1", "sample#7", "AB9");
    }

    @Test
    void aDuplicatedIssueOrStoryIsChangedEvenWhenItsVersionsAgree() {
        // Harm: one version authorizes a write to an ambiguous id.
        final Board held = board();
        final Issue twice = held.issues().getFirst().withWarnings(List.of(Warning.idTwice()));
        final Story twiceStory = held.stories().getFirst().withWarnings(List.of(Warning.idTwice()));
        final Board duplicate =
                new Board(
                        held.revision(),
                        held.workflow(),
                        List.of(twiceStory, twiceStory),
                        List.of(twice, twice),
                        held.work(),
                        held.repositories());
        final StoreException.Changed refusal =
                catchThrowableOfType(
                        StoreException.Changed.class,
                        () ->
                                Edits.unchanged(
                                        duplicate,
                                        Basis.none().with("AB1", SEEN).with("ST1", SEEN),
                                        Set.of("AB1", "ST1")));
        assertThat(refusal).as("duplicates refuse even equal versions").isNotNull();
        assertThat(refusal.ids())
                .as("both ambiguous targets")
                .containsExactlyInAnyOrder("AB1", "ST1");
    }

    @Test
    void anIdSharedByAStoryAndAnIssueIsAmbiguous() {
        // Harm: a version for one kind authorizes editing another kind with the same id.
        final Board held = board();
        final Board collision =
                new Board(
                        held.revision(),
                        held.workflow(),
                        List.of(
                                new Story(
                                                "sample#1",
                                                "Story",
                                                "Idea",
                                                "High",
                                                null,
                                                List.of(),
                                                "",
                                                null)
                                        .withVersion(SEEN)),
                        List.of(
                                new Issue(
                                                "sample#1",
                                                "sample",
                                                "Issue",
                                                "Idea",
                                                null,
                                                List.of(),
                                                null,
                                                null,
                                                "")
                                        .withVersion(SEEN)),
                        held.work());
        final StoreException.Changed refusal =
                catchThrowableOfType(
                        StoreException.Changed.class,
                        () ->
                                Edits.unchanged(
                                        collision,
                                        Basis.none().with("sample#1", SEEN),
                                        Set.of("sample#1")));
        assertThat(refusal).as("one id must identify exactly one item").isNotNull();
        assertThat(refusal.ids()).as("the ambiguous reference").containsExactly("sample#1");
    }

    @Test
    void matchingTargetsAndAnEmptyTargetSetIgnoreExtraBasisEntries() {
        // Harm: unused old versions turn independent writes into whole-board conflicts again.
        final Basis seen =
                Basis.none()
                        .with("AB1", SEEN)
                        .with("ST1", SEEN)
                        .with("sample#7", SEEN)
                        .with("AB9", new Version("obsolete"));
        assertThatCode(() -> Edits.unchanged(board(), seen, Set.of("AB1", "ST1", "sample#7")))
                .as("only targets are checked")
                .doesNotThrowAnyException();
        assertThatCode(() -> Edits.unchanged(board(), seen, Set.of()))
                .as("a creation with no targets needs no item versions")
                .doesNotThrowAnyException();
    }
}
