package org.fuin.porelo.planner.app;

import java.util.List;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TextFormat;
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
    public org.fuin.porelo.planner.store.Tracker tracker() {
        return store.tracker();
    }

    @Override
    public Story story(final String id) {
        return store.story(id);
    }

    @Override
    public java.util.Map<String, java.util.List<org.fuin.porelo.planner.store.Wait>> waitsNow(
            final java.util.Collection<String> issues) {
        return store.waitsNow(issues);
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
    public Board poll() {
        return BoardLog.read(project, store.poll());
    }

    @Override
    public boolean reloadsAlone() {
        return store.reloadsAlone();
    }

    @Override
    public java.util.Optional<org.fuin.porelo.planner.store.Revision> held() {
        return store.held();
    }

    @Override
    public java.time.Duration pollDelay(
            final java.time.Duration fetched, final java.time.Duration probed) {
        return store.pollDelay(fetched, probed);
    }

    @Override
    public Board save(final Revision revision, final Writes writes) {
        return store.save(revision, writes);
    }

    /** Whether an issue's requested state is written during its creation. */
    @Override
    public boolean createsInState() {
        return store.createsInState();
    }

    @Override
    public boolean setsLabels() {
        return store.setsLabels();
    }

    @Override
    public boolean keepsReasons() {
        return store.keepsReasons();
    }

    @Override
    public boolean keepsRepositoryLabels() {
        return store.keepsRepositoryLabels();
    }

    @Override
    public Board rereadLabels(final String repository) {
        return store.rereadLabels(repository);
    }

    @Override
    public Board correctLabels(
            final String repository,
            final java.util.List<org.fuin.porelo.planner.store.RepositoryWarning.Call> calls) {
        return store.correctLabels(repository, calls);
    }

    @Override
    public NewIssue.Added createIssue(final Revision revision, final NewIssue issue) {
        return store.createIssue(revision, issue);
    }

    @Override
    public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
        return store.createIdea(revision, idea);
    }

    @Override
    public boolean addsUnlistedIssues() {
        return store.addsUnlistedIssues();
    }

    @Override
    public Board addUnlistedIssue(final Revision revision, final String issue) {
        return store.addUnlistedIssue(revision, issue);
    }

    @Override
    public boolean makesStoriesFromIssues() {
        return store.makesStoriesFromIssues();
    }

    @Override
    public NewIdea.Added createStoryFromIssues(
            final Revision revision, final StoryFromIssues story) {
        return store.createStoryFromIssues(revision, story);
    }

    @Override
    public Board setFields(final Revision revision, final String issue, final Fields fields) {
        return store.setFields(revision, issue, fields);
    }

    @Override
    public Board setStoryIssues(
            final Revision revision, final String story, final List<String> issues) {
        return store.setStoryIssues(revision, story, issues);
    }

    @Override
    public Board setBlockedBy(
            final Revision revision, final String issue, final List<String> blockedBy) {
        return store.setBlockedBy(revision, issue, blockedBy);
    }

    @Override
    public Board setText(final Revision revision, final String id, final TextEdit edit) {
        return store.setText(revision, id, edit);
    }

    @Override
    public Board save(final Basis basis, final Writes writes, final Recheck recheck) {
        return store.save(basis, writes, recheck);
    }

    @Override
    public NewIssue.Added createIssue(
            final Basis basis, final NewIssue issue, final Recheck recheck) {
        return store.createIssue(basis, issue, recheck);
    }

    @Override
    public NewIdea.Added createIdea(final Basis basis, final NewIdea idea, final Recheck recheck) {
        return store.createIdea(basis, idea, recheck);
    }

    @Override
    public Board addUnlistedIssue(final Basis basis, final String issue, final Recheck recheck) {
        return store.addUnlistedIssue(basis, issue, recheck);
    }

    @Override
    public NewIdea.Added createStoryFromIssues(
            final Basis basis, final StoryFromIssues story, final Recheck recheck) {
        return store.createStoryFromIssues(basis, story, recheck);
    }

    @Override
    public Board setFields(
            final Basis basis, final String issue, final Fields fields, final Recheck recheck) {
        return store.setFields(basis, issue, fields, recheck);
    }

    @Override
    public Board setStoryIssues(
            final Basis basis,
            final String story,
            final List<String> issues,
            final Recheck recheck) {
        return store.setStoryIssues(basis, story, issues, recheck);
    }

    @Override
    public Board setBlockedBy(
            final Basis basis,
            final String issue,
            final List<String> blockedBy,
            final Recheck recheck) {
        return store.setBlockedBy(basis, issue, blockedBy, recheck);
    }

    @Override
    public Board setText(
            final Basis basis, final String id, final TextEdit edit, final Recheck recheck) {
        return store.setText(basis, id, edit, recheck);
    }

    @Override
    public void comment(final String issue, final String text) {
        store.comment(issue, text);
    }

    @Override
    public Board saveResult(final StageResult result) {
        return store.saveResult(result);
    }

    @Override
    public @Nullable String storyState(final List<String> issueStates) {
        return store.storyState(issueStates);
    }

    @Override
    public TextFormat textFormat() {
        return store.textFormat();
    }

    @Override
    public void retire() {
        store.retire();
    }

    @Override
    public java.util.Optional<org.fuin.porelo.planner.store.ReadProgress> reading() {
        return store.reading();
    }
}
