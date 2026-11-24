package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Dependencies;
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

    /*
     * The writes below are checked against the versions the caller saw of their targets instead of
     * the board's revision, as PlanningStore's forms taking a Basis say: each throws
     * StoreException.BasisIncomplete or StoreException.Changed and writes nothing when a target
     * lacks its version or changed, and StoreException.PartlySaved when a later call fails.
     */

    /**
     * Saves states, workers and priorities against the versions the caller saw.
     *
     * @param current the snapshot the store holds
     * @param basis the targets' versions the caller saw
     * @param writes what to write
     */
    default void save(final Snapshot current, final Basis basis, final Writes writes) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Saves as {@link #save(Snapshot, Basis, Writes)} does, judging each target this save already
     * read from GitHub by that read instead of reading it again.
     *
     * @param current the snapshot the store holds
     * @param basis the targets' versions the caller saw
     * @param writes what to write
     * @param read what this save read of the items before writing
     */
    default void save(
            final Snapshot current, final Basis basis, final Writes writes, final ReadBefore read) {
        save(current, basis, writes);
    }

    /**
     * Reads, before the save's targets are read, whether the comment keeping each reason of the
     * writes stands on its item already, so no read lies between the targets' read and the first
     * write.
     *
     * @param current the snapshot the store holds
     * @param writes what the save writes
     * @return whether each reason's comment stands, by the item's id; empty when this writer reads
     *     them while it writes
     */
    default java.util.Map<String, Boolean> reasonsStanding(
            final Snapshot current, final Writes writes) {
        return java.util.Map.of();
    }

    /**
     * What a save read from GitHub before its first write.
     *
     * @param snapshot the snapshot with the items read folded in
     * @param issueIds the node ids of the issues read
     * @param reasons whether the comment keeping each reason stands, by the item's id, read before
     *     the targets; an item it does not name is read while the save writes
     */
    record ReadBefore(
            Snapshot snapshot,
            java.util.Set<String> issueIds,
            java.util.Map<String, Boolean> reasons) {

        /** Nothing read yet. */
        public static ReadBefore none(final Snapshot held) {
            return new ReadBefore(held, java.util.Set.of());
        }

        /** Copies the ids and the reasons read. */
        public ReadBefore {
            issueIds = java.util.Set.copyOf(issueIds);
            reasons = java.util.Map.copyOf(reasons);
        }

        /**
         * What a save read of its items, no reason's comment among it.
         *
         * @param snapshot the snapshot with the items read folded in
         * @param issueIds the node ids of the issues read
         */
        public ReadBefore(final Snapshot snapshot, final java.util.Set<String> issueIds) {
            this(snapshot, issueIds, java.util.Map.of());
        }

        /**
         * The same read with whether each reason's comment stands.
         *
         * @param standing whether each reason's comment stands, by the item's id
         * @return the read
         */
        public ReadBefore withReasons(final java.util.Map<String, Boolean> standing) {
            return new ReadBefore(snapshot, issueIds, standing);
        }
    }

    /**
     * Adds an issue against the version the caller saw of the story it is hung into.
     *
     * @param current the snapshot the store holds
     * @param basis the targets' versions the caller saw
     * @param issue what the person wrote
     * @return its id
     */
    default String createIssue(final Snapshot current, final Basis basis, final NewIssue issue) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Adds an idea, which changes no existing item.
     *
     * @param current the snapshot the store holds
     * @param basis the targets' versions the caller saw, none for an idea
     * @param idea the idea
     * @return the ids it was saved under
     */
    default IdeaIds createIdea(final Snapshot current, final Basis basis, final NewIdea idea) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Adds an issue made outside the project against the version the caller saw of it.
     *
     * @param current the snapshot the store holds
     * @param basis the unlisted issue's version the caller saw
     * @param issue the issue's tracker reference
     */
    default void addUnlistedIssue(final Snapshot current, final Basis basis, final String issue) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Makes a story over issues that stand on the board against the versions the caller saw.
     *
     * @param current the snapshot the store holds
     * @param basis the targets' versions the caller saw
     * @param story the story and its issues
     * @return the ids it was saved under, the issues in the story's order
     */
    default IdeaIds createStoryFromIssues(
            final Snapshot current, final Basis basis, final StoryFromIssues story) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Sets an issue's fields against the version the caller saw of it.
     *
     * @param current the snapshot the store holds
     * @param basis the targets' versions the caller saw
     * @param issue the issue's id
     * @param fields the fields to set
     * @return whether anything was written
     */
    default boolean setFields(
            final Snapshot current, final Basis basis, final String issue, final Fields fields) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Sets a story's whole issue list against the versions the caller saw.
     *
     * @param current the snapshot the store holds
     * @param basis the targets' versions the caller saw
     * @param story the story's id
     * @param issues the issues' ids, in the story's order
     * @return whether anything was written
     */
    default boolean setStoryIssues(
            final Snapshot current,
            final Basis basis,
            final String story,
            final List<String> issues) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Sets an issue's whole list of blockers against the version the caller saw of it.
     *
     * @param current the snapshot the store holds
     * @param basis the targets' versions the caller saw
     * @param issue the issue's id
     * @param blockedBy the blockers' ids
     * @return whether anything was written
     */
    default boolean setBlockedBy(
            final Snapshot current,
            final Basis basis,
            final String issue,
            final List<String> blockedBy) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Sets a story's or an issue's title and named parts of its body against the version the caller
     * saw of it.
     *
     * @param current the snapshot the store holds
     * @param basis the targets' versions the caller saw
     * @param id the item's id
     * @param edit the title and the parts to write or remove
     * @return whether anything was written
     */
    default boolean setText(
            final Snapshot current, final Basis basis, final String id, final TextEdit edit) {
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    /**
     * Reads now the links of an issue the board offers as made outside the project, for the rules
     * that check its add.
     *
     * @param current the snapshot the store holds
     * @param issue the issue's tracker reference
     * @return its links, in the board's ids
     */
    default Dependencies.UnlistedLinks unlistedLinks(final Snapshot current, final String issue) {
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
