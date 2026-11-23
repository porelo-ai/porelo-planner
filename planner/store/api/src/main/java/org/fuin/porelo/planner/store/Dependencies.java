package org.fuin.porelo.planner.store;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The current values a caller reads for rules. These reads do not enlarge a write's targets. A
 * store supplies this view inside its write serialization, after reading the dependencies afresh. A
 * view answers only what it was given: asked for ranks or an unlisted issue's links it does not
 * hold, it refuses rather than answering none.
 */
public interface Dependencies {

    /**
     * A view of the board alone, for the rules that read nothing else.
     *
     * @param board the current board
     * @return the view
     */
    static Dependencies of(final Board board) {
        return () -> board;
    }

    /** Answers the current board containing the items needed by the rule. */
    Board board();

    /**
     * Answers the current raw ranks of items of the given kind and priority. Items without a
     * recognized priority belong to Medium; an unreadable rank contributes no value.
     *
     * @throws IllegalStateException when this view was not given ranks
     */
    default List<Double> ranks(final boolean story, final String priority) {
        throw new IllegalStateException("This view of the dependencies holds no ranks.");
    }

    /**
     * Answers the current links of an unlisted issue, refusing when they cannot be read.
     *
     * @throws IllegalStateException when this view was not given unlisted links
     */
    default UnlistedLinks unlisted(final String issue) {
        throw new IllegalStateException(
                "This view of the dependencies holds no links of unlisted issues.");
    }

    /**
     * Links of an unlisted issue read from its source, without guessing from the held board.
     *
     * @param parent the parent's id, or null for none
     * @param blockers every blocker's id, including those outside the project
     * @param blockersWhole whether every blocker was read
     * @param projectItems all ids on the project needed to distinguish outside blockers from
     *     project items the board cannot place
     * @param blockedItems project items this issue blocks
     */
    record UnlistedLinks(
            @Nullable String parent,
            List<String> blockers,
            boolean blockersWhole,
            Set<String> projectItems,
            Set<String> blockedItems) {

        /** Copies the source's links and membership. */
        public UnlistedLinks {
            blockers = List.copyOf(blockers);
            projectItems = Set.copyOf(projectItems);
            blockedItems = Set.copyOf(blockedItems);
        }
    }
}
