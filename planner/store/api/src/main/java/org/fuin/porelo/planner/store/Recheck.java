package org.fuin.porelo.planner.store;

import java.util.Set;
import java.util.function.Consumer;

/**
 * The rules a write is checked against again inside the store's write serialization, on the
 * dependencies the store reads afresh there, before anything is written. A rule refuses by
 * throwing; then nothing is written.
 *
 * @param needs what the store reads afresh into {@link Dependencies#board()} before the rule runs;
 *     none leaves the board as the store holds it
 * @param from the ids the reads start from: the issue whose blockers are walked, the story whose
 *     issues are read, the issue whose parent is read
 * @param rule the rule, run once on the view read
 */
public record Recheck(Set<Need> needs, Set<String> from, Consumer<Dependencies> rule) {

    /** What a rule reads afresh beyond the write's targets. */
    public enum Need {

        /** The blocker graph, walked from each id named until no further blocker is held. */
        BLOCKERS,

        /** Each story named and every issue it holds; each issue named and its sub-issues. */
        STORY_ISSUES,

        /** The parent of each issue named. */
        PARENT
    }

    /** Copies the needs and the ids. */
    public Recheck {
        needs = Set.copyOf(needs);
        from = Set.copyOf(from);
    }

    /**
     * Answers a write with no rule over other items: the store reads nothing for it.
     *
     * @return the empty recheck
     */
    public static Recheck none() {
        return new Recheck(Set.of(), Set.of(), dependencies -> {});
    }
}
