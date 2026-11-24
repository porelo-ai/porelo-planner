package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The items a run's judgment reads, and the check of a task's save over them. */
class RunReadsTest {

    /** The story ST1 with its issue AA1, AA1's sub-issue AA1.1, and AA1.2 and BB1 apart. */
    private static Board board() {
        final Board source = NextStepTest.board("Building", "Backlog");
        final List<Issue> issues = new ArrayList<>(source.issues());
        issues.add(issue("AA1.2", "Loose", null));
        issues.add(issue("BB1", "Apart", null));
        return with(source, issues);
    }

    private static Issue issue(final String id, final String title, final @Nullable String parent) {
        return new Issue(id, "alpha", title, "Backlog", null, List.of(), parent, null, "");
    }

    private static Board with(final Board board, final List<Issue> issues) {
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                issues,
                board.work(),
                board.repositories());
    }

    /** The board with an issue replaced. */
    private static Board replaced(final Board board, final Issue issue) {
        return with(
                board,
                board.issues().stream().map(i -> i.id().equals(issue.id()) ? issue : i).toList());
    }

    @Test
    void theItemsReadAreTheStoryItsIssuesAndTheirSubIssuesAndNoneApart() {
        // Harm: a task's save refused by an item its judgment never read, or passed over one
        // it did read.
        assertThat(RunReads.of(board(), "ST1"))
                .as("the items the run's judgment reads")
                .containsExactly("AA1", "AA1.1", "ST1");
    }

    @Test
    void aSaveIsRefusedNamingAReadItemChangedSince() {
        // Harm: a task's end written on a judgment of a sub-issue edited on the tracker since.
        final Board judged = board();
        final Board now = replaced(judged, issue("AA1.1", "Child renamed", "AA1"));

        final StoreException.Changed changed =
                catchThrowableOfType(
                        StoreException.Changed.class, () -> RunReads.unchanged(judged, now, "ST1"));

        assertThat(changed).as("the refusal").isNotNull();
        assertThat(changed.ids()).as("the items named").containsExactly("AA1.1");
    }

    @Test
    void aSubIssueAttachedSinceIsNamedSoItIsReadAgain() {
        // Harm: a judgment made once more without a sub-issue attached on the tracker since,
        // which advances its parent over the open child.
        final Board judged = board();
        final Board now = replaced(judged, issue("AA1.2", "Loose", "AA1"));

        assertThatThrownBy(() -> RunReads.unchanged(judged, now, "ST1"))
                .as("a child attached since")
                .isInstanceOfSatisfying(
                        StoreException.Changed.class,
                        changed ->
                                assertThat(changed.ids())
                                        .as("the items named")
                                        .containsExactly("AA1.2"));
    }

    @Test
    void anItemApartFromTheStoryChangedSinceRefusesNothing() {
        // Harm: a task's save refused whenever anything else on the board moved.
        final Board judged = board();
        final Board now = replaced(judged, issue("BB1", "Moved elsewhere", null));

        RunReads.unchanged(judged, now, "ST1");
    }
}
