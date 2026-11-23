package org.fuin.porelo.planner.core;

import java.util.List;
import java.util.Set;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.jspecify.annotations.Nullable;

/** A write's kind and the information needed to determine its targets, without performing it. */
public sealed interface Operation {

    /**
     * A move preview or save. The caller obtains carried and commented issues from the same move
     * plan it saves; targets never infer them from requested states.
     *
     * @param moves every move requested, including moves that keep an item's state
     * @param carried the issues the move plan carries
     * @param commented the issues the move plan gives a closing reason, among them those a story
     *     move sent again comments on although their state stays
     */
    record MoveBatch(List<Moves.Move> moves, Set<String> carried, Set<String> commented)
            implements Operation {
        /** Copies the request and its computed effects. */
        public MoveBatch {
            moves = List.copyOf(moves);
            carried = Set.copyOf(carried);
            commented = Set.copyOf(commented);
        }

        /**
         * Takes the effects from the plan the moves are saved by.
         *
         * @param moves every move requested
         * @param planned the plan {@link Moves#check} made of them
         * @return the batch
         */
        public static MoveBatch of(final List<Moves.Move> moves, final Moves.Planned planned) {
            return new MoveBatch(moves, planned.issues().keySet(), planned.issueReasons().keySet());
        }
    }

    /** A new issue, optionally hung into a story. */
    record CreateIssue(@Nullable String story) implements Operation {}

    /** A change of an issue's worker. */
    record SetWorker(String issue) implements Operation {}

    /** A change of an issue's fields. */
    record SetFields(String issue) implements Operation {}

    /** A change of an issue's blockers; blockers are dependencies, not targets. */
    record SetBlockedBy(String issue) implements Operation {}

    /** A change of an issue's text. */
    record SetIssueText(String issue) implements Operation {}

    /** A change of a story's priority. */
    record SetStoryPriority(String story) implements Operation {}

    /** A change of a story's text. */
    record SetStoryText(String story) implements Operation {}

    /** A story's replacement issue list; issues kept in it retain their parent. */
    record SetStoryIssues(String story, List<String> issues) implements Operation {
        /** Copies the replacement list. */
        public SetStoryIssues {
            issues = List.copyOf(issues);
        }
    }

    /** A story and new issues made from an idea, changing no existing item. */
    record StoryFromIdea() implements Operation {}

    /** A story made over existing issues, including a retry of the same request. */
    record CreateStoryFromIssues(StoryFromIssues asked) implements Operation {}

    /** An issue made outside the project, added by its own version. */
    record AddUnlistedIssue(String issue) implements Operation {}

    /**
     * Story work, with every proposed or chosen issue and every pass-over's issue, whose text,
     * worker and risk feed the task.
     */
    record OrderStoryWork(String story, Set<String> issues) implements Operation {
        /** Copies every issue named by the order. */
        public OrderStoryWork {
            issues = Set.copyOf(issues);
        }
    }

    /**
     * A reading choice. The caller obtains the reconciled deferred issues from the work run that
     * decides them.
     */
    record ReadingChoice(String issue, Set<String> reconciled) implements Operation {
        /** Copies the issues reconciled by the work run. */
        public ReadingChoice {
            reconciled = Set.copyOf(reconciled);
        }
    }

    /** A correction of the item named by the request. */
    record Correct(Corrections.Asked asked) implements Operation {}
}
