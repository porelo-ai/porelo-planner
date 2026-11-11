package org.fuin.porelo.planner.core;

import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Worker;

/**
 * Boards whose named issues a person works, their sub-issues with them, as the store reads them.
 */
final class ManualIssues {

    private ManualIssues() {}

    /**
     * Answers the board with the named issues and their sub-issues worked by a person.
     *
     * @param board the board
     * @param ids the issues a person works
     * @return the changed board
     */
    static Board of(final Board board, final String... ids) {
        final Set<String> manual = Set.of(ids);
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                board.issues().stream()
                        .map(
                                i ->
                                        manual.contains(i.id())
                                                        || i.parent() != null
                                                                && manual.contains(i.parent())
                                                ? new Issue(
                                                        i.id(),
                                                        i.repository(),
                                                        i.title(),
                                                        i.state(),
                                                        i.size(),
                                                        i.blockedBy(),
                                                        i.parent(),
                                                        i.link(),
                                                        i.text(),
                                                        Worker.MANUAL)
                                                : i)
                        .toList(),
                board.work(),
                board.repositories());
    }
}
