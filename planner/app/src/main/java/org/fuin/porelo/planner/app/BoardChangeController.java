package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.app.answers.BatchSaved;
import org.fuin.porelo.planner.app.answers.ItemResult;
import org.fuin.porelo.planner.core.BoardChanges;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.core.MoveIssues;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Applies a board change of new and changed issues in one call, as {@code openapi/planner.yaml}
 * describes it. Every reference is resolved before anything is written; then each item is written
 * through the window's own calls, so each is judged by their rules, and a refused item never stops
 * the others.
 */
@RestController
public class BoardChangeController {

    private final Projects projects;

    private final ChangesRegistry changes;

    private final BoardController board;

    private final BoardEdits edits;

    /**
     * Makes the controller.
     *
     * @param projects the projects the window has given
     * @param changes tells a followed project's windows of a save
     * @param board the window's calls that add an issue, set its worker and move it
     * @param edits the window's calls that edit an issue's text, fields, blockers and story
     */
    public BoardChangeController(
            final Projects projects,
            final ChangesRegistry changes,
            final BoardController board,
            final BoardEdits edits) {
        this.projects = projects;
        this.changes = changes;
        this.board = board;
        this.edits = edits;
    }

    /**
     * Applies a board change: the new issues first, in the order sent, then their blockers, then
     * the changes of existing issues.
     *
     * @param project the project's name
     * @param request the request, whose body is the change
     * @return the revision after the call and what became of each item, new issues first
     */
    @PostMapping("/projects/{project}/changes")
    public BatchSaved applyBoardChange(
            @PathVariable("project") final String project, final HttpServletRequest request) {
        final String name = BoardController.name(project);
        final BoardChangeRequests.Change change = BoardChangeRequests.change(request);
        try {
            return applied(name, change);
        } finally {
            changes.saved(name);
        }
    }

    private BatchSaved applied(final String name, final BoardChangeRequests.Change change) {
        final PlanningStore store = projects.store(name);
        final Board read = store.board();
        final Set<String> keys = new java.util.HashSet<>();
        change.creates().forEach(item -> keys.add(item.key()));

        // Every reference is resolved before anything is written.
        final List<Outcome> creates = new ArrayList<>();
        for (final BoardChangeRequests.NewItem item : change.creates()) {
            final Outcome outcome = new Outcome(null, item.key());
            outcome.attempt(
                    () -> {
                        outcome.story = target(read, keys, "", item.story());
                        outcome.blockers = targets(read, keys, "", item.blockedBy());
                    });
            creates.add(outcome);
        }
        final List<Outcome> updates = new ArrayList<>();
        for (final BoardChangeRequests.Update item : change.updates()) {
            final Outcome outcome = new Outcome(item.issue(), null);
            outcome.attempt(
                    () -> {
                        outcome.story = target(read, keys, item.issue(), item.story());
                        final List<String> blockers = item.blockedBy();
                        outcome.blockers =
                                blockers == null
                                        ? null
                                        : targets(read, keys, item.issue(), blockers);
                    });
            updates.add(outcome);
        }

        final Map<String, String> made = new HashMap<>();
        final Set<String> refused = new java.util.HashSet<>();
        for (int index = 0; index < creates.size(); index++) {
            final BoardChangeRequests.NewItem item = change.creates().get(index);
            final Outcome outcome = creates.get(index);
            outcome.attempt(
                    () -> {
                        refusedKeys(outcome.blockers, refused);
                        final String id = create(name, store, item, outcome);
                        made.put(item.key(), id);
                    });
            if (outcome.refusal != null) {
                refused.add(item.key());
            }
        }
        for (final Outcome outcome : creates) {
            final List<BoardChanges.Target> blockers = outcome.blockers;
            final String id = outcome.item;
            if (outcome.refusal == null && id != null && blockers != null && !blockers.isEmpty()) {
                outcome.attempt(
                        () -> edits.setBlockedBy(name, id, seen(store), ids(blockers, made, id)));
            }
        }
        for (int index = 0; index < updates.size(); index++) {
            final BoardChangeRequests.Update item = change.updates().get(index);
            final Outcome outcome = updates.get(index);
            final String before = store.board().revision().value();
            outcome.attempt(() -> update(name, store, item, outcome, made));
            if (outcome.refusal == null) {
                outcome.saved = !store.board().revision().value().equals(before);
            }
        }

        final List<ItemResult> results = new ArrayList<>();
        creates.forEach(outcome -> results.add(outcome.result()));
        updates.forEach(outcome -> results.add(outcome.result()));
        return new BatchSaved(store.board().revision().value(), results);
    }

    /** Makes one new issue in its story and state, then writes its other parts. */
    private String create(
            final String name,
            final PlanningStore store,
            final BoardChangeRequests.NewItem item,
            final Outcome outcome) {
        final Map<TextEdit.Part, String> rest = new LinkedHashMap<>(item.parts());
        final String mustBeTrue = rest.remove(TextEdit.Part.WHAT_MUST_BE_TRUE);
        final org.fuin.porelo.planner.store.@Nullable Worker worker = item.worker();
        final NewIssue.Added added =
                board.addIssue(
                        name,
                        new WorkRequests.IssueRequest(
                                item.repository(),
                                item.title(),
                                mustBeTrue,
                                item.size(),
                                worker == null ? null : worker.name(),
                                seen(store),
                                null,
                                item.priority(),
                                storyOf(outcome.story, Map.of(), ""),
                                item.state(),
                                false,
                                List.of()));
        outcome.item = added.id();
        outcome.saved = true;
        if (!rest.isEmpty()) {
            edits.setText(
                    name,
                    added.id(),
                    seen(store),
                    new TextEdit(
                            TextEdit.Kind.ISSUE, null, rest, EnumSet.noneOf(TextEdit.Part.class)));
        }
        return added.id();
    }

    /** Writes one existing issue's changes, each through the window's own call. */
    private void update(
            final String name,
            final PlanningStore store,
            final BoardChangeRequests.Update item,
            final Outcome outcome,
            final Map<String, String> made) {
        final String issue = item.issue();
        final org.fuin.porelo.planner.store.Issue held =
                store.board()
                        .issue(issue)
                        .orElseThrow(
                                () ->
                                        new Moves.Refused(
                                                "NoSuchIssue",
                                                "There is no issue " + issue + ".",
                                                Map.of("issue", issue)));
        final TextEdit parts = item.parts();
        if (item.title() != null || parts != null) {
            edits.setText(
                    name,
                    issue,
                    seen(store),
                    new TextEdit(
                            TextEdit.Kind.ISSUE,
                            item.title(),
                            parts == null ? Map.of() : parts.written(),
                            parts == null ? Set.of() : parts.removed()));
        }
        // A field equal to the issue's is not named, so a change of nothing writes nothing.
        final BoardChangeRequests.@Nullable Size asked = item.size();
        final BoardChangeRequests.@Nullable Size size =
                asked == null || java.util.Objects.equals(asked.value(), held.size())
                        ? null
                        : asked;
        final @Nullable String priority =
                java.util.Objects.equals(item.priority(), held.priority()) ? null : item.priority();
        if (size != null || priority != null) {
            final Set<Fields.Field> named = EnumSet.noneOf(Fields.Field.class);
            if (size != null) {
                named.add(Fields.Field.SIZE);
            }
            if (priority != null) {
                named.add(Fields.Field.PRIORITY);
            }
            edits.setFields(
                    name,
                    issue,
                    seen(store),
                    new Fields(named, size == null ? null : size.value(), null, priority, null));
        }
        final org.fuin.porelo.planner.store.@Nullable Worker worker = item.worker();
        if (worker != null) {
            board.workerOf(name, issue, new WorkRequests.WorkerRequest(worker, seen(store)));
        }
        final List<BoardChanges.Target> blockers = outcome.blockers;
        if (blockers != null) {
            edits.setBlockedBy(name, issue, seen(store), ids(blockers, made, issue));
        }
        final String story = storyOf(outcome.story, made, issue);
        if (story != null) {
            final List<String> issues =
                    new ArrayList<>(
                            store.board().story(story).map(Story::issues).orElse(List.of()));
            if (!issues.contains(issue)) {
                issues.add(issue);
                edits.setStoryIssues(name, story, seen(store), issues);
            }
        }
        final String state = item.state();
        if (state != null) {
            final ItemResult moved =
                    board.moveMany(name, List.of(new MoveIssues.Ask(issue, state, null)))
                            .results()
                            .get(0);
            if (moved.refusal() != null) {
                outcome.moveRefusal = moved.refusal();
                return;
            }
            outcome.state = moved.state();
        }
    }

    /** Refuses a new issue before it is made when a blocker's new issue was refused already. */
    private static void refusedKeys(
            final @Nullable List<BoardChanges.Target> blockers, final Set<String> refused) {
        if (blockers == null) {
            return;
        }
        for (final BoardChanges.Target blocker : blockers) {
            if (blocker instanceof BoardChanges.Key key && refused.contains(key.key())) {
                BoardChanges.id(blocker, Map.of(), "");
            }
        }
    }

    private static Basis seen(final PlanningStore store) {
        return Basis.of(store.board());
    }

    private static BoardChanges.@Nullable Target target(
            final Board read,
            final Set<String> keys,
            final String item,
            final @Nullable String ref) {
        return ref == null ? null : BoardChanges.target(read, keys, item, ref);
    }

    private static List<BoardChanges.Target> targets(
            final Board read, final Set<String> keys, final String item, final List<String> refs) {
        return refs.stream().map(ref -> BoardChanges.target(read, keys, item, ref)).toList();
    }

    private static List<String> ids(
            final List<BoardChanges.Target> targets,
            final Map<String, String> made,
            final String item) {
        return targets.stream().map(target -> BoardChanges.id(target, made, item)).toList();
    }

    /** A story is named by its id; a key names a new issue, which is no story. */
    private static @Nullable String storyOf(
            final BoardChanges.@Nullable Target target,
            final Map<String, String> made,
            final String item) {
        if (target == null) {
            return null;
        }
        if (target instanceof BoardChanges.Key) {
            throw Edits.refused(item, "story", "The story is no story of the project.");
        }
        return BoardChanges.id(target, made, item);
    }

    /** What became of one item of the change. */
    private static final class Outcome {
        private @Nullable String item;
        private final @Nullable String key;
        private BoardChanges.@Nullable Target story;
        private @Nullable List<BoardChanges.Target> blockers;
        private boolean saved;
        private @Nullable String state;
        private @Nullable RuntimeException refusal;
        private org.fuin.porelo.planner.app.answers.@Nullable Problem moveRefusal;

        Outcome(final @Nullable String item, final @Nullable String key) {
            this.item = item;
            this.key = key;
        }

        /** Runs one step of the item, unless an earlier step refused it, keeping its refusal. */
        void attempt(final Runnable step) {
            if (refusal != null || moveRefusal != null) {
                return;
            }
            try {
                step.run();
            } catch (final Moves.Refused | WorkRefusal | Refusal refused) {
                refusal = refused;
            } catch (final StoreException.NotSavable notSavable) {
                refusal =
                        Edits.refused(
                                item == null ? "" : item,
                                notSavable.field(),
                                String.valueOf(notSavable.getMessage()));
            }
        }

        ItemResult result() {
            final @Nullable RuntimeException refused = refusal;
            if (refused != null || moveRefusal != null) {
                return new ItemResult(
                        item,
                        key,
                        ItemResult.OutcomeEnum.REFUSED,
                        null,
                        null,
                        refused != null ? Problems.answer(refused) : moveRefusal);
            }
            return new ItemResult(
                    item,
                    key,
                    saved ? ItemResult.OutcomeEnum.SAVED : ItemResult.OutcomeEnum.UNCHANGED,
                    state,
                    null,
                    null);
        }
    }
}
