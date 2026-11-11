package org.fuin.porelo.planner.app;

import java.util.List;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * A project's store whose every board read is logged, through whichever path - a call, a poll or a
 * work step - it was read.
 */
final class LoggedStore implements PlanningStore {

    private final String project;

    private final PlanningStore store;

    /**
     * Wraps a store.
     *
     * @param project the project's checked name
     * @param store the store
     */
    LoggedStore(final String project, final PlanningStore store) {
        this.project = project;
        this.store = store;
    }

    @Override
    public Board board() {
        return BoardLog.read(project, store.board());
    }

    @Override
    public Story story(final String id) {
        return store.story(id);
    }

    @Override
    public Issue issue(final String id) {
        return store.issue(id);
    }

    @Override
    public Board refresh() {
        return BoardLog.read(project, store.refresh());
    }

    @Override
    public Board save(final Revision revision, final Writes writes) {
        return store.save(revision, writes);
    }

    @Override
    public NewIssue.Added createIssue(final Revision revision, final NewIssue issue) {
        return store.createIssue(revision, issue);
    }

    @Override
    public @Nullable String storyState(final List<String> issueStates) {
        return store.storyState(issueStates);
    }

    @Override
    public void retire() {
        store.retire();
    }
}
