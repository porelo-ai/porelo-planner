package org.fuin.porelo.planner.app;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.core.NewIdeas;
import org.fuin.porelo.planner.core.Operation;
import org.fuin.porelo.planner.core.Rules;
import org.fuin.porelo.planner.core.Targets;
import org.fuin.porelo.planner.core.WrittenVersions;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.git.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The edits a person, or an agent acting for them, keeps a project's board with by hand, each a
 * whole use case: the project's lock, the check of the versions seen and of core's rule, the
 * store's write and the windows told of it. It knows no HTTP; every refusal is the core's or the
 * store's.
 */
@Service
public class BoardEdits {

    private static final Logger LOG = LoggerFactory.getLogger(BoardEdits.class);

    private final Projects projects;

    private final ChangesRegistry changes;

    private final CommandQueues queues;

    /**
     * Makes the service, its edits written by calling each project's store directly.
     *
     * @param projects the projects the window has given
     * @param changes tells a followed project's windows of a save
     */
    public BoardEdits(final Projects projects, final ChangesRegistry changes) {
        this(projects, changes, CommandQueues.DIRECT);
    }

    /**
     * Makes the service.
     *
     * @param projects the projects the window has given
     * @param changes tells a followed project's windows of a save
     * @param queues gives each project's edits the queue that writes their commands
     */
    @Autowired
    public BoardEdits(
            final Projects projects, final ChangesRegistry changes, final CommandQueues queues) {
        this.projects = projects;
        this.changes = changes;
        this.queues = queues;
    }

    /**
     * Hands one edit to the project's queue as a command of its own, under the lock its path holds,
     * and answers what it wrote.
     */
    private CommandGroups.Written written(
            final String project, final Function<String, Command> command) {
        final PlanningStore store = projects.store(project);
        return CommandGroups.one(queues.queue(project, store), store, command);
    }

    /**
     * A saved edit and the versions its targets hold after it, including their holding stories.
     *
     * @param board the board the save returned, or the current board for an equal edit
     * @param versions the versions for the caller's next write
     */
    public record Saved(Board board, Map<String, String> versions) {

        /**
         * Answers the revision of the saved board.
         *
         * @return the revision
         */
        public String revision() {
            return board.revision().value();
        }
    }

    /**
     * A created story, its issues and their versions, including the stories the issues left.
     *
     * @param story the story's id
     * @param issues its issues' ids
     * @param board the board the save returned
     * @param versions the versions for the caller's next write
     */
    public record Added(
            String story, List<String> issues, Board board, Map<String, String> versions) {}

    /** Includes the created ids beside the operation's targets, using only the saved board. */
    private static Added added(
            final Board before,
            final Set<String> targets,
            final CommandGroups.Written written,
            final Basis seen) {
        final CommandOutcome.Created made = written.made();
        final Board after = written.afterCreate();
        final Set<String> ids = new LinkedHashSet<>(targets);
        ids.add(made.item());
        ids.addAll(made.issues());
        return new Added(
                made.item(), made.issues(), after, WrittenVersions.of(before, after, ids, seen));
    }

    /** Keeps the board and versions together so no controller reads again after a save. */
    private static Saved saved(
            final Board before, final Board after, final Operation operation, final Basis seen) {
        return new Saved(
                after, WrittenVersions.of(before, after, Targets.of(before, operation), seen));
    }

    /** A missing read-back must never turn the store's held versions into fresh ones. */
    private static Saved saved(
            final Board before,
            final CommandGroups.Written written,
            final PlanningStore store,
            final Operation operation,
            final Basis seen) {
        final Board after = written.judged(store);
        return new Saved(
                after,
                written.board() == null
                        ? Map.of()
                        : WrittenVersions.of(before, after, Targets.of(before, operation), seen));
    }

    /**
     * Makes a story with its issues from an idea, which changes no item that stands, so the
     * versions seen are not checked.
     *
     * @param project the project's checked name
     * @param seen the versions the caller saw
     * @param idea the idea, each member null where it is missing
     * @param shape the faults of the idea's shape its reader found
     * @return the story's and its issues' ids and the board after the save
     * @throws org.fuin.porelo.planner.core.WorkRefusal {@code IdeaRefused} for a malformed idea,
     *     {@code WorkRunning} while a task of a gone story's run still works
     * @throws UnsupportedOperationException when the store adds no ideas
     */
    public Added createStory(
            final String project,
            final Basis seen,
            final NewIdeas.Idea idea,
            final List<String> shape) {
        try {
            final Added added =
                    projects.work(project)
                            .newStory(
                                    board -> {
                                        Edits.unchanged(
                                                board,
                                                seen,
                                                Targets.of(board, new Operation.StoryFromIdea()));
                                        return NewIdeas.check(board, idea, shape);
                                    },
                                    (board, checked) ->
                                            added(
                                                    board,
                                                    Targets.of(
                                                            board, new Operation.StoryFromIdea()),
                                                    written(
                                                            project,
                                                            id ->
                                                                    new Command.CreateIdea(
                                                                            id,
                                                                            checked,
                                                                            seen,
                                                                            Recheck.none())),
                                                    seen));
            LOG.info(
                    "Made story {} in project {} as revision {}",
                    LogText.of(added.story()),
                    LogText.of(project),
                    LogText.of(added.board().revision().value()));
            return added;
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Answers whether the project's store makes a story over issues that stand.
     *
     * @param project the project's checked name
     * @return true when it does
     */
    public boolean makesStoriesFromIssues(final String project) {
        return projects.store(project).makesStoriesFromIssues();
    }

    /**
     * Makes a story over issues that stand on the board, against the versions the caller saw of
     * them and of the story an earlier attempt made; a started task at work on one of them refuses
     * it.
     *
     * @param project the project's checked name
     * @param seen the versions the caller saw
     * @param story the story, each member null where it is missing; null when it is no object
     * @param existing the issues' checked ids, in the story's order
     * @param shape the faults of the story's shape its reader found
     * @return the story's and its issues' ids and the board after the save
     * @throws org.fuin.porelo.planner.core.WorkRefusal {@code IdeaRefused} naming each issue that
     *     cannot be taken, {@code IssueInStory} for one another story holds, {@code WorkRunning}
     *     while a task works one of them, naming it, or a gone story's run
     * @throws UnsupportedOperationException when the store makes no story from issues
     */
    public Added createStoryFromIssues(
            final String project,
            final Basis seen,
            final NewIdeas.@Nullable Story story,
            final List<String> existing,
            final List<String> shape) {
        try {
            final Added added =
                    projects.work(project)
                            .newStory(
                                    existing,
                                    board -> {
                                        final StoryFromIssues checked =
                                                NewIdeas.checkFromIssues(
                                                        board, story, existing, shape);
                                        Edits.unchanged(
                                                board,
                                                seen,
                                                Targets.of(
                                                        board,
                                                        new Operation.CreateStoryFromIssues(
                                                                checked)));
                                        return checked;
                                    },
                                    (board, checked) ->
                                            added(
                                                    board,
                                                    Targets.of(
                                                            board,
                                                            new Operation.CreateStoryFromIssues(
                                                                    checked)),
                                                    written(
                                                            project,
                                                            id ->
                                                                    new Command.CreateStory(
                                                                            id,
                                                                            checked,
                                                                            seen,
                                                                            Recheck.none())),
                                                    seen));
            LOG.info(
                    "Made story {} of {} issues in project {} as revision {}",
                    LogText.of(added.story()),
                    added.issues().size(),
                    LogText.of(project),
                    LogText.of(added.board().revision().value()));
            return added;
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Sets an issue's fields, against the version the caller saw of it.
     *
     * @param project the project's checked name
     * @param issue the issue's checked id
     * @param seen the versions the caller saw
     * @param fields the fields to set
     * @return the saved board and versions
     */
    public Saved setFields(
            final String project, final String issue, final Basis seen, final Fields fields) {
        try {
            return projects.work(project)
                    .alone(
                            () -> {
                                final PlanningStore store = projects.store(project);
                                final Board board = store.board();
                                Edits.unchanged(
                                        board,
                                        seen,
                                        Targets.of(board, new Operation.SetFields(issue)));
                                Edits.fields(board, issue, fields);
                                final Saved saved =
                                        saved(
                                                board,
                                                written(
                                                        project,
                                                        id ->
                                                                new Command.SetFields(
                                                                        id,
                                                                        issue,
                                                                        fields,
                                                                        seen,
                                                                        Recheck.none())),
                                                store,
                                                new Operation.SetFields(issue),
                                                seen);
                                saved("the fields of issue", issue, project, saved.revision());
                                return saved;
                            });
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Sets a story's whole issue list, against the versions the caller saw of the story and of
     * every issue it gains or loses; a list equal to the story's writes nothing.
     *
     * @param project the project's checked name
     * @param story the story's checked id
     * @param seen the versions the caller saw
     * @param issues the issues' checked ids, in the story's order
     * @return the saved board and versions, current when nothing changed
     */
    public Saved setStoryIssues(
            final String project, final String story, final Basis seen, final List<String> issues) {
        try {
            return projects.work(project)
                    .storyIssues(
                            story,
                            issues,
                            board -> {
                                Edits.unchanged(
                                        board,
                                        seen,
                                        Targets.of(
                                                board,
                                                new Operation.SetStoryIssues(story, issues)));
                                Edits.storyIssues(board, story, issues);
                            },
                            board -> {
                                if (board.story(story)
                                        .map(Story::issues)
                                        .orElseThrow()
                                        .equals(issues)) {
                                    return saved(
                                            board,
                                            board,
                                            new Operation.SetStoryIssues(story, issues),
                                            seen);
                                }
                                final Saved saved =
                                        saved(
                                                board,
                                                written(
                                                        project,
                                                        id ->
                                                                new Command.SetStoryIssues(
                                                                        id,
                                                                        story,
                                                                        issues,
                                                                        seen,
                                                                        Recheck.none())),
                                                projects.store(project),
                                                new Operation.SetStoryIssues(story, issues),
                                                seen);
                                saved("the issues of story", story, project, saved.revision());
                                return saved;
                            });
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Sets an issue's whole list of blockers, against the version the caller saw of it; whether the
     * blockers lead back to it is checked again on the blockers the store reads at the write. A
     * list equal to the issue's writes nothing.
     *
     * @param project the project's checked name
     * @param issue the issue's checked id
     * @param seen the versions the caller saw
     * @param blockedBy the blockers' checked ids
     * @return the saved board and versions, current when nothing changed
     */
    public Saved setBlockedBy(
            final String project,
            final String issue,
            final Basis seen,
            final List<String> blockedBy) {
        try {
            return projects.work(project)
                    .alone(
                            () -> {
                                final PlanningStore store = projects.store(project);
                                final Board board = store.board();
                                Edits.unchanged(
                                        board,
                                        seen,
                                        Targets.of(board, new Operation.SetBlockedBy(issue)));
                                if (!Edits.blockedBy(board, issue, blockedBy)) {
                                    return saved(
                                            board, board, new Operation.SetBlockedBy(issue), seen);
                                }
                                final Recheck blockers =
                                        new Recheck(
                                                Set.of(Recheck.Need.BLOCKERS),
                                                Set.of(issue),
                                                dependencies ->
                                                        Rules.blockedBy(
                                                                dependencies, issue, blockedBy));
                                final Saved saved =
                                        saved(
                                                board,
                                                written(
                                                        project,
                                                        id ->
                                                                new Command.SetBlockers(
                                                                        id, issue, blockedBy, seen,
                                                                        blockers)),
                                                store,
                                                new Operation.SetBlockedBy(issue),
                                                seen);
                                saved("the blockers of issue", issue, project, saved.revision());
                                return saved;
                            });
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Sets a story's or an issue's title and named parts, against the version the caller saw of it;
     * a text equal to the item's writes nothing. A task at work on the issue does not refuse it.
     *
     * @param project the project's checked name
     * @param id the item's checked id
     * @param seen the versions the caller saw
     * @param edit the change
     * @return the saved board and versions, current when nothing changed
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code EditNotAllowed} for a change core
     *     or the store refuses
     */
    public Saved setText(
            final String project, final String id, final Basis seen, final TextEdit edit) {
        try {
            return projects.work(project)
                    .alone(
                            () -> {
                                final PlanningStore store = projects.store(project);
                                final Board board = store.board();
                                Edits.unchanged(
                                        board,
                                        seen,
                                        Targets.of(
                                                board,
                                                edit.kind() == TextEdit.Kind.STORY
                                                        ? new Operation.SetStoryText(id)
                                                        : new Operation.SetIssueText(id)));
                                final TextEdit checked = Edits.text(board, id, edit);
                                final Saved saved;
                                try {
                                    saved =
                                            saved(
                                                    board,
                                                    written(
                                                            project,
                                                            command ->
                                                                    new Command.SetText(
                                                                            command,
                                                                            id,
                                                                            checked,
                                                                            seen,
                                                                            Recheck.none())),
                                                    store,
                                                    edit.kind() == TextEdit.Kind.STORY
                                                            ? new Operation.SetStoryText(id)
                                                            : new Operation.SetIssueText(id),
                                                    seen);
                                } catch (final TextEdit.Refused refused) {
                                    throw Edits.refused(refused, "");
                                }
                                saved(
                                        edit.kind() == TextEdit.Kind.STORY
                                                ? "the text of story"
                                                : "the text of issue",
                                        id,
                                        project,
                                        saved.revision());
                                return saved;
                            });
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Adds an issue made outside the project from the board's offer, against the version the caller
     * saw of it. A store that adds none is asked first, so it is refused as not offered for any
     * issue; then core checks the offer, and the store checks it again under its save's lock.
     *
     * @param project the project's checked name
     * @param issue the issue's checked tracker reference
     * @param seen the versions the caller saw
     * @return the saved board and versions
     * @throws UnsupportedOperationException when the store adds no issue made outside the project
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code NotUnlisted} for an issue the board
     *     does not offer or the tracker no longer holds, {@code EditNotAllowed} for one the board
     *     could not hold once added
     */
    public Saved addUnlistedIssue(final String project, final String issue, final Basis seen) {
        try {
            return projects.work(project)
                    .alone(
                            () -> {
                                final PlanningStore store = projects.store(project);
                                if (!store.addsUnlistedIssues()) {
                                    throw new UnsupportedOperationException(
                                            PlanningStore.NOT_OFFERED);
                                }
                                final Board board = store.board();
                                Edits.unlisted(board, issue);
                                Edits.unchanged(
                                        board,
                                        seen,
                                        Targets.of(board, new Operation.AddUnlistedIssue(issue)));
                                final Saved saved;
                                try {
                                    saved =
                                            saved(
                                                    board,
                                                    written(
                                                            project,
                                                            id ->
                                                                    new Command.AddUnlisted(
                                                                            id,
                                                                            issue,
                                                                            seen,
                                                                            Recheck.none())),
                                                    store,
                                                    new Operation.AddUnlistedIssue(issue),
                                                    seen);
                                } catch (final StoreException.NoSuchIssue gone) {
                                    throw Edits.notUnlisted(issue);
                                } catch (final StoreException.NotSavable refused) {
                                    // The store's words are fixed and never repeat the tracker's.
                                    throw Edits.refused(
                                            issue, "issue", String.valueOf(refused.getMessage()));
                                }
                                saved(
                                        "the issue made outside the project",
                                        issue,
                                        project,
                                        saved.revision());
                                return saved;
                            });
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Writes a comment on an issue with the person's own access; the board shows no comment, so no
     * version is checked and the windows are told nothing.
     *
     * @param project the project's checked name
     * @param issue the issue's checked id
     * @param text the comment's text
     */
    public void comment(final String project, final String issue, final String text) {
        projects.work(project)
                .alone(
                        () -> {
                            final PlanningStore store = projects.store(project);
                            Edits.comment(store.board(), issue, text);
                            written(project, id -> new Command.Comment(id, issue, text));
                            // Its words are the person's, so only that it was written is logged.
                            LOG.info(
                                    "Wrote a comment on issue {} of project {}",
                                    LogText.of(issue),
                                    LogText.of(project));
                            return issue;
                        });
    }

    private static void saved(
            final String what, final String item, final String project, final String revision) {
        LOG.info(
                "Saved {} {} of project {} as revision {}",
                what,
                LogText.of(item),
                LogText.of(project),
                LogText.of(revision));
    }
}
