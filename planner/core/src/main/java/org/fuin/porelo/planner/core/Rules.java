package org.fuin.porelo.planner.core;

import java.util.List;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Dependencies;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/** Rules evaluated on dependencies read now, independently of a caller's edit basis. */
public final class Rules {

    private Rules() {}

    /**
     * Answers the live blockers not known to be done, neither closed nor in review, including
     * outside waits.
     */
    public static List<String> openBlockers(final Dependencies dependencies, final String issue) {
        final Board board = dependencies.board();
        return WorkProposal.openBlockers(board, issue(board, issue));
    }

    /** Checks blocker eligibility, the limit and cycles against the live blocker graph. */
    public static boolean blockedBy(
            final Dependencies dependencies, final String issue, final List<String> blockers) {
        final Board board = dependencies.board();
        single(board, issue);
        blockers.forEach(blocker -> single(board, blocker));
        return Edits.blockedBy(board, issue, blockers);
    }

    /**
     * Answers the rank after the last of the same kind and priority, capped at the field's limit.
     * Invalid raw ranks contribute nothing, as they do when reading the board.
     */
    public static int nextRank(
            final Dependencies dependencies, final boolean story, final String priority) {
        int highest = 0;
        for (final double rank : dependencies.ranks(story, priority)) {
            if (Double.isFinite(rank) && rank >= 1 && rank <= Fields.MOST_RANK) {
                highest = Math.max(highest, (int) rank);
            }
        }
        return Math.min(highest + 1, Fields.MOST_RANK);
    }

    /** Checks an idea, including one issue per repository, against the current project. */
    public static NewIdea idea(final Dependencies dependencies, final NewIdeas.Idea idea) {
        return NewIdeas.check(dependencies.board(), idea, List.of());
    }

    /**
     * Derives a story's state from all of its current issues, as the store derives it. An
     * incomplete view, or an issue in a state outside the workflow, refuses rather than deriving
     * from only part of the story; a warning that holds an issue's work back does not, since it
     * bears on moves rather than on derivation. An empty story has no derived state.
     */
    public static @Nullable String derivedState(
            final Dependencies dependencies,
            final String story,
            final java.util.function.Function<List<String>, String> derive) {
        final Board board = dependencies.board();
        single(board, story);
        final Story found =
                board.story(story)
                        .orElseThrow(
                                () ->
                                        new StoreException.NotSavable(
                                                story, "The story is not uniquely readable."));
        if (!found.issuesWhole()) {
            throw new StoreException.NotSavable(story, "Not every issue of the story was read.");
        }
        final List<String> states =
                found.issues().stream()
                        .map(
                                id -> {
                                    final Issue current = issue(board, id);
                                    if (!board.workflow().states().contains(current.state())) {
                                        throw new StoreException.NotSavable(
                                                story,
                                                "Its issue "
                                                        + id
                                                        + " is not readable for deriving a state.");
                                    }
                                    return current.state();
                                })
                        .toList();
        return states.isEmpty() ? null : derive.apply(states);
    }

    /**
     * Gives a sub-issue its live parent issue's current worker and risk. The sub-issue given is a
     * board's, whose worker and risk may already be inherited from an earlier parent value, so they
     * take no part.
     */
    public static Issue inherited(final Dependencies dependencies, final Issue own) {
        final String parent = own.parent();
        if (parent == null) {
            return own;
        }
        final Issue current = issue(dependencies.board(), parent);
        return own.withWorkerAndRisk(current.worker(), current.risk());
    }

    /**
     * Answers the recheck of a worker change: {@link #worker} on the issue and its sub-issues as
     * the store reads them now.
     *
     * @param issue the issue's id
     * @param worker the worker it is to have
     * @return the recheck the store runs before it writes the change
     */
    public static Recheck workerRecheck(final String issue, final Worker worker) {
        return new Recheck(
                java.util.Set.of(Recheck.Need.STORY_ISSUES),
                java.util.Set.of(issue),
                dependencies -> worker(dependencies, issue, worker));
    }

    /** Checks a worker change, including the live states of the issue and its sub-issues. */
    public static Writes worker(
            final Dependencies dependencies, final String issue, final Worker worker) {
        single(dependencies.board(), issue);
        return Workers.check(dependencies.board(), issue, worker);
    }

    /** Checks moves, including live parent and story-completion checks, without saving them. */
    public static Moves.Planned moves(
            final Dependencies dependencies, final List<Moves.Move> moves, final Moves.Runs runs) {
        moves.forEach(move -> single(dependencies.board(), move.id()));
        return Moves.check(dependencies.board(), moves, runs);
    }

    /**
     * Checks an unlisted issue's parent and blockers on the current view, including project items
     * not placed on the board. Outside blockers remain waits and are eligible.
     */
    public static void unlisted(final Dependencies dependencies, final String id) {
        final Board board = dependencies.board();
        final UnlistedIssue offered =
                board.unlisted().stream()
                        .filter(item -> item.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> Edits.notUnlisted(id));
        final Dependencies.UnlistedLinks links = dependencies.unlisted(id);
        if (!links.blockersWhole() || links.blockers().size() > Edits.MOST_BLOCKERS) {
            throw new StoreException.NotSavable(
                    id, "Not every blocker can be read within the blocker limit.");
        }
        final boolean story = offered.kind() == UnlistedIssue.Kind.STORY;
        if (story
                && (!offered.repository().equals(board.repositories().getFirst().name())
                        || !links.blockedItems().isEmpty())) {
            throw new StoreException.NotSavable(
                    id,
                    "A story belongs to the project's own repository and blocks no project item.");
        }
        final String parent = links.parent();
        if (parent != null) {
            single(board, parent);
            final Issue parentIssue = board.issue(parent).orElse(null);
            if (board.twice(parent).isPresent()
                    || (parentIssue == null && board.story(parent).isEmpty())
                    || (parentIssue != null
                            && (parentIssue.parent() != null
                                    || (!story
                                            && !parentIssue
                                                    .repository()
                                                    .equals(offered.repository()))))) {
                throw new StoreException.NotSavable(
                        id, "Its parent " + parent + " is not eligible on the project.");
            }
        }
        for (final String blocker : links.blockers()) {
            single(board, blocker);
            if (blocker.equals(id)
                    || board.story(blocker).isPresent()
                    || board.twice(blocker).isPresent()
                    || (links.projectItems().contains(blocker) && board.issue(blocker).isEmpty())) {
                throw new StoreException.NotSavable(
                        id, "Its blocker " + blocker + " is not an eligible issue.");
            }
        }
    }

    /** Refuses an id that names both a story and an issue: no rule can tell which it means. */
    private static void single(final Board board, final String id) {
        if (board.issues().stream().anyMatch(item -> item.id().equals(id))
                && board.stories().stream().anyMatch(item -> item.id().equals(id))) {
            throw new StoreException.NotSavable(
                    id,
                    id + " names both a story and an issue, so no rule can tell which it means.");
        }
    }

    private static Issue issue(final Board board, final String id) {
        single(board, id);
        return board.issue(id)
                .orElseThrow(
                        () ->
                                new StoreException.NotSavable(
                                        id, "The issue is not uniquely readable."));
    }
}
