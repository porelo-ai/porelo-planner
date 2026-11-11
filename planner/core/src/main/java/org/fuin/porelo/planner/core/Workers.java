package org.fuin.porelo.planner.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;

/**
 * Checks a change of who works an issue against the board a window shows and answers what saving it
 * writes: the worker, nothing when it is the issue's already, or a refusal.
 */
public final class Workers {

    private Workers() {}

    /**
     * Checks a worker change.
     *
     * @param board the board the window shows
     * @param issue the issue's id
     * @param worker the worker it is to have
     * @return what saving the change writes; nothing when the issue has that worker already
     * @throws Moves.Refused {@code NoSuchIssue} for an issue the board lacks, {@code
     *     MoveNotAllowed} for a sub-issue, which is worked as its parent is
     */
    public static Writes check(final Board board, final String issue, final Worker worker) {
        final Issue found =
                board.issue(issue)
                        .orElseThrow(
                                () ->
                                        new Moves.Refused(
                                                "NoSuchIssue",
                                                "There is no issue " + issue + ".",
                                                Map.of("issue", issue)));
        final String parent = found.parent();
        if (parent != null) {
            throw refused(
                    issue,
                    "A sub-issue is worked as its parent " + parent + " is, never by its own.");
        }
        if (found.worker() == worker) {
            return new Writes(Map.of(), Map.of(), List.of());
        }
        return new Writes(Map.of(), Map.of(), List.of(), Map.of(issue, worker));
    }

    /**
     * A refusal of a worker change, as the window reads a refused move.
     *
     * @param issue the issue's checked id
     * @param reason the words the window shows
     * @return the refusal, {@code MoveNotAllowed} naming the issue
     */
    public static Moves.Refused refused(final String issue, final String reason) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", issue);
        parameters.put("from", "");
        parameters.put("to", "");
        parameters.put("reason", reason);
        return new Moves.Refused("MoveNotAllowed", reason, parameters);
    }
}
