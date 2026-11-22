package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Warning;

/** Boards a store gives when it could not fully read an issue, for the routes' tests. */
final class HeldBoards {

    /**
     * The warnings GitHub's store gives an item a person edited by hand into what the board cannot
     * hold, each of which holds the item back.
     */
    static final List<Warning> HAND_EDITS =
            List.of(
                    Warning.storyOutsideOwnRepository(),
                    Warning.parentInOtherRepository(),
                    Warning.blockedByStory(),
                    Warning.parentCircle(),
                    Warning.subIssueOfSubIssue(),
                    Warning.pastOnePage(),
                    Warning.dependsOnUnplaced());

    private HeldBoards() {}

    /** The board with one issue warned. */
    static Board warned(final Board board, final String id, final Warning warning) {
        return with(
                board,
                board.issues().stream()
                        .map(
                                issue ->
                                        issue.id().equals(id)
                                                ? issue.withWarnings(List.of(warning))
                                                : issue)
                        .toList(),
                board.repositories());
    }

    /** The board with an issue's id named a second time, by an issue of another repository. */
    static Board twice(final Board board, final String id, final String repository) {
        final List<Issue> issues = new ArrayList<>();
        Issue copy = null;
        for (final Issue issue : board.issues()) {
            if (issue.id().equals(id)) {
                issues.add(issue.withWarnings(List.of(Warning.idTwice())));
                copy =
                        new Issue(
                                        issue.id(),
                                        repository,
                                        issue.title(),
                                        issue.state(),
                                        issue.size(),
                                        List.of(),
                                        issue.parent(),
                                        null,
                                        "",
                                        issue.worker(),
                                        issue.risk())
                                .withWarnings(List.of(Warning.idTwice()));
            } else {
                issues.add(issue);
            }
        }
        if (copy == null) {
            throw new IllegalArgumentException("no issue " + id);
        }
        issues.add(copy);
        final List<String> repositories = new ArrayList<>(board.repositories());
        if (!repositories.contains(repository)) {
            repositories.add(repository);
        }
        return with(board, issues, repositories);
    }

    private static Board with(
            final Board board, final List<Issue> issues, final List<String> repositories) {
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                issues,
                board.work(),
                repositories,
                board.faults(),
                board.owner(),
                board.unlisted());
    }
}
