package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/** Writes to the GitHub project; the store reads the board again after every write. */
public interface BoardWriter {

    /** A writer that writes nothing, for a store that only reads. */
    BoardWriter NONE = new BoardWriter() {};

    /**
     * A call of a save that failed after an earlier call of the same save wrote; a writer throws it
     * in place of the call's failure, so the refusal says a part was saved and never that nothing
     * was.
     */
    final class AfterWriting extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** What the later call threw, kept apart from the message: its words may hold the token. */
        private final RuntimeException failure;

        /**
         * Wraps the later call's failure.
         *
         * @param failure what the later call threw
         */
        public AfterWriting(final RuntimeException failure) {
            super("A call of the save failed after a part of it was written");
            this.failure = failure;
        }

        /**
         * Answers what the later call threw.
         *
         * @return the failure
         */
        public RuntimeException failure() {
            return failure;
        }
    }

    /**
     * The ids an idea was saved under.
     *
     * @param story the story's id
     * @param issues the issues' ids, in the idea's order
     */
    record IdeaIds(String story, List<String> issues) {

        /** Checks the ids and copies the list. */
        public IdeaIds {
            Ids.story(story);
            issues = List.copyOf(issues);
            issues.forEach(Ids::issue);
        }
    }

    /**
     * Saves states, workers and priorities, all of them or a named part.
     *
     * @param current the snapshot the store holds
     * @param revision the revision the window showed
     * @param writes what to write
     * @throws org.fuin.porelo.planner.store.StoreException.StaleRevision if GitHub moved since that
     *     revision
     * @throws org.fuin.porelo.planner.store.StoreException.PartlySaved if only a part was saved
     */
    default void save(final Snapshot current, final Revision revision, final Writes writes) {
        throw new UnsupportedOperationException("This store does not save moves yet");
    }

    /**
     * Adds an issue in the workflow's first state.
     *
     * @param current the snapshot the store holds
     * @param revision the revision the window showed
     * @param issue what the person wrote
     * @return its id
     */
    default String createIssue(
            final Snapshot current, final Revision revision, final NewIssue issue) {
        throw new UnsupportedOperationException("This store does not add issues yet");
    }

    /**
     * Adds an idea: its story and every issue it names.
     *
     * @param current the snapshot the store holds
     * @param revision the revision the window showed
     * @param idea the idea
     * @return the ids it was saved under
     */
    default IdeaIds createIdea(
            final Snapshot current, final Revision revision, final NewIdea idea) {
        throw new UnsupportedOperationException("This store does not add ideas yet");
    }

    /**
     * Adds an open issue of the project's repositories that is no item of the project, as the board
     * offers it: an issue in the workflow's first state, a story following its issues.
     *
     * @param current the snapshot the store holds
     * @param revision the revision the caller read
     * @param issue the issue's tracker reference
     * @throws org.fuin.porelo.planner.store.StoreException.StaleRevision if the board moved since
     *     that revision or the issue changed on GitHub since it was read
     * @throws org.fuin.porelo.planner.store.StoreException.PartlySaved if only a part was saved
     */
    default void addUnlistedIssue(
            final Snapshot current, final Revision revision, final String issue) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Makes a story over issues that stand on the board, each hung under it in its order.
     *
     * @param current the snapshot the store holds
     * @param revision the revision the caller read
     * @param story the story and its issues
     * @return the ids it was saved under, the issues in the story's order
     */
    default IdeaIds createStoryFromIssues(
            final Snapshot current, final Revision revision, final StoryFromIssues story) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Sets an issue's fields; a value the issue holds is not written again.
     *
     * @param current the snapshot the store holds
     * @param revision the revision the caller read
     * @param issue the issue's id
     * @param fields the fields to set
     * @return whether anything was written
     */
    default boolean setFields(
            final Snapshot current,
            final Revision revision,
            final String issue,
            final Fields fields) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Sets a story's whole issue list.
     *
     * @param current the snapshot the store holds
     * @param revision the revision the caller read
     * @param story the story's id
     * @param issues the issues' ids, in the story's order
     * @return whether anything was written
     */
    default boolean setStoryIssues(
            final Snapshot current,
            final Revision revision,
            final String story,
            final List<String> issues) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Sets an issue's whole list of blockers.
     *
     * @param current the snapshot the store holds
     * @param revision the revision the caller read
     * @param issue the issue's id
     * @param blockedBy the blockers' ids
     * @return whether anything was written
     */
    default boolean setBlockedBy(
            final Snapshot current,
            final Revision revision,
            final String issue,
            final List<String> blockedBy) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Sets a story's or an issue's title and named parts of its body.
     *
     * @param current the snapshot the store holds
     * @param revision the revision the caller read
     * @param id the item's id
     * @param edit the title and the parts to write or remove
     * @return whether anything was written
     */
    default boolean setText(
            final Snapshot current, final Revision revision, final String id, final TextEdit edit) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Writes a comment on an issue.
     *
     * @param current the snapshot the store holds
     * @param issue the issue's id
     * @param text the comment's text
     */
    default void comment(final Snapshot current, final String issue, final String text) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Saves what a stage handed back: the sections it names, the new state, and one stage comment.
     *
     * @param current the snapshot the store holds
     * @param result the stage's result
     * @param stage the stage's numbers from Porelo's record of the task, or null when none are
     *     known
     */
    default void saveResult(
            final Snapshot current, final StageResult result, final @Nullable StageEntry stage) {
        throw new UnsupportedOperationException("This store does not save stage results yet");
    }
}
