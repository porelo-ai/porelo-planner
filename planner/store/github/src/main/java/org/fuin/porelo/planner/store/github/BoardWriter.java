package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/** Writes to the GitHub project; the store reads the board again after every write. */
public interface BoardWriter {

    /** A writer that writes nothing, for a store that only reads. */
    BoardWriter NONE = new BoardWriter() {};

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
     * Adds an issue in state Idea.
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
