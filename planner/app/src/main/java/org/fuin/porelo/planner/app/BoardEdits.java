package org.fuin.porelo.planner.app;

import java.util.List;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.core.NewIdeas;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.git.LogText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The edits a person, or an agent acting for them, keeps a project's board with by hand, each a
 * whole use case: the project's lock, the check of the revision and of core's rule, the store's
 * write and the windows told of it. It knows no HTTP; every refusal is the core's or the store's.
 */
@Service
public class BoardEdits {

    private static final Logger LOG = LoggerFactory.getLogger(BoardEdits.class);

    private final Projects projects;

    private final ChangesRegistry changes;

    /**
     * Makes the service.
     *
     * @param projects the projects the window has given
     * @param changes tells a followed project's windows of a save
     */
    public BoardEdits(final Projects projects, final ChangesRegistry changes) {
        this.projects = projects;
        this.changes = changes;
    }

    /**
     * Makes a story with its issues from an idea, against the revision the caller read.
     *
     * @param project the project's checked name
     * @param revision the revision the caller read
     * @param idea the idea, each member null where it is missing
     * @param shape the faults of the idea's shape its reader found
     * @return the story's and its issues' ids and the board after the save
     * @throws org.fuin.porelo.planner.store.StoreException.StaleRevision when the board moved
     * @throws org.fuin.porelo.planner.core.WorkRefusal {@code IdeaRefused} for a malformed idea,
     *     {@code WorkRunning} while a task of a gone story's run still works
     * @throws UnsupportedOperationException when the store adds no ideas
     */
    public NewIdea.Added createStory(
            final String project,
            final String revision,
            final NewIdeas.Idea idea,
            final List<String> shape) {
        try {
            final NewIdea.Added added =
                    projects.work(project)
                            .newStory(
                                    board -> {
                                        Edits.fresh(board, revision);
                                        return NewIdeas.check(board, idea, shape);
                                    },
                                    (board, checked) ->
                                            projects.store(project)
                                                    .createIdea(board.revision(), checked));
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
     * Sets an issue's fields, against the revision the caller read.
     *
     * @param project the project's checked name
     * @param issue the issue's checked id
     * @param revision the revision the caller read
     * @param fields the fields to set
     * @return the revision after the save
     */
    public String setFields(
            final String project, final String issue, final String revision, final Fields fields) {
        try {
            return projects.work(project)
                    .alone(
                            () -> {
                                final PlanningStore store = projects.store(project);
                                final Board board = store.board();
                                Edits.fields(board, revision, issue, fields);
                                final String saved =
                                        store.setFields(board.revision(), issue, fields)
                                                .revision()
                                                .value();
                                saved("the fields of issue", issue, project, saved);
                                return saved;
                            });
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Sets a story's whole issue list, against the revision the caller read; a list equal to the
     * story's writes nothing.
     *
     * @param project the project's checked name
     * @param story the story's checked id
     * @param revision the revision the caller read
     * @param issues the issues' checked ids, in the story's order
     * @return the revision after the save, the current one when nothing changed
     */
    public String setStoryIssues(
            final String project,
            final String story,
            final String revision,
            final List<String> issues) {
        try {
            return projects.work(project)
                    .storyIssues(
                            story,
                            issues,
                            board -> Edits.storyIssues(board, revision, story, issues),
                            board -> {
                                if (board.story(story)
                                        .map(Story::issues)
                                        .orElseThrow()
                                        .equals(issues)) {
                                    return board.revision().value();
                                }
                                final String saved =
                                        projects.store(project)
                                                .setStoryIssues(board.revision(), story, issues)
                                                .revision()
                                                .value();
                                saved("the issues of story", story, project, saved);
                                return saved;
                            });
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Sets an issue's whole list of blockers, against the revision the caller read; a list equal to
     * the issue's writes nothing.
     *
     * @param project the project's checked name
     * @param issue the issue's checked id
     * @param revision the revision the caller read
     * @param blockedBy the blockers' checked ids
     * @return the revision after the save, the current one when nothing changed
     */
    public String setBlockedBy(
            final String project,
            final String issue,
            final String revision,
            final List<String> blockedBy) {
        try {
            return projects.work(project)
                    .alone(
                            () -> {
                                final PlanningStore store = projects.store(project);
                                final Board board = store.board();
                                if (!Edits.blockedBy(board, revision, issue, blockedBy)) {
                                    return board.revision().value();
                                }
                                final String saved =
                                        store.setBlockedBy(board.revision(), issue, blockedBy)
                                                .revision()
                                                .value();
                                saved("the blockers of issue", issue, project, saved);
                                return saved;
                            });
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Sets a story's or an issue's title and named parts, against the revision the caller read; a
     * text equal to the item's writes nothing. A task at work on the issue does not refuse it.
     *
     * @param project the project's checked name
     * @param id the item's checked id
     * @param revision the revision the caller read
     * @param edit the change
     * @return the revision after the save, the current one when nothing changed
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code EditNotAllowed} for a change core
     *     or the store refuses
     */
    public String setText(
            final String project, final String id, final String revision, final TextEdit edit) {
        try {
            return projects.work(project)
                    .alone(
                            () -> {
                                final PlanningStore store = projects.store(project);
                                final Board board = store.board();
                                final TextEdit checked = Edits.text(board, revision, id, edit);
                                final String saved;
                                try {
                                    saved =
                                            store.setText(board.revision(), id, checked)
                                                    .revision()
                                                    .value();
                                } catch (final TextEdit.Refused refused) {
                                    // The store's words are fixed and never repeat the text.
                                    throw Edits.refused(
                                            refused.item(),
                                            "",
                                            String.valueOf(refused.getMessage()));
                                }
                                saved(
                                        edit.kind() == TextEdit.Kind.STORY
                                                ? "the text of story"
                                                : "the text of issue",
                                        id,
                                        project,
                                        saved);
                                return saved;
                            });
        } finally {
            changes.saved(project);
        }
    }

    /**
     * Writes a comment on an issue with the person's own access; the board shows no comment, so no
     * revision is checked and the windows are told nothing.
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
                            store.comment(issue, text);
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
