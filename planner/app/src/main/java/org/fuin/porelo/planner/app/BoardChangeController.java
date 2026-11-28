package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.fuin.porelo.planner.app.answers.BatchSaved;
import org.fuin.porelo.planner.app.answers.ChangedItem;
import org.fuin.porelo.planner.app.answers.ItemResult;
import org.fuin.porelo.planner.app.answers.Problem;
import org.fuin.porelo.planner.core.BoardChanges;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.core.MoveIssues;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WrittenVersions;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Applies a board change of new and changed issues in one call, as {@code openapi/planner.yaml}
 * describes it. Every item is judged on what needs no write before anything is written; then each
 * is written through the window's own calls, so each is judged by their rules, and a refused item
 * never stops the others, a failure of the store included.
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
        final BoardChangeRequests.Change change =
                BoardChangeRequests.change(request, () -> projects.store(name).board());
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
        final Call call = new Call(name, changed -> changedItems(name, changed), read);

        final List<Outcome> creates = new ArrayList<>();
        final List<BoardChanges.Item> asked = new ArrayList<>();
        for (final BoardChangeRequests.NewItem item : change.creates()) {
            creates.add(new Outcome(call, null, item.key(), item.repository()));
            asked.add(
                    new BoardChanges.Item(
                            item.key(),
                            "",
                            restOf(item),
                            item.story(),
                            item.blockedBy(),
                            item.state()));
        }
        final List<Outcome> updates = new ArrayList<>();
        for (final BoardChangeRequests.Update item : change.updates()) {
            final String repository =
                    read.issue(item.issue())
                            .map(org.fuin.porelo.planner.store.Issue::repository)
                            .orElse("");
            updates.add(new Outcome(call, item.issue(), null, repository));
            final @Nullable TextEdit parts = item.parts();
            asked.add(
                    new BoardChanges.Item(
                            null,
                            item.issue(),
                            item.title() == null && parts == null
                                    ? null
                                    : new TextEdit(
                                            TextEdit.Kind.ISSUE,
                                            item.title(),
                                            parts == null ? Map.of() : parts.written(),
                                            parts == null ? Set.of() : parts.removed()),
                            item.story(),
                            item.blockedBy(),
                            item.state()));
        }

        // Every item is judged on what needs no write before any is written.
        final List<Outcome> outcomes = new ArrayList<>(creates);
        outcomes.addAll(updates);
        BoardChanges.judge(read, asked).forEach((at, refusal) -> outcomes.get(at).fail(refusal));
        for (int index = 0; index < creates.size(); index++) {
            final BoardChangeRequests.NewItem item = change.creates().get(index);
            final Outcome outcome = creates.get(index);
            outcome.attempt(
                    () -> {
                        outcome.story = target(read, keys, "", item.story());
                        outcome.blockers = targets(read, keys, "", item.blockedBy());
                    });
        }
        for (int index = 0; index < updates.size(); index++) {
            final BoardChangeRequests.Update item = change.updates().get(index);
            final Outcome outcome = updates.get(index);
            outcome.attempt(
                    () -> {
                        outcome.story = target(read, keys, item.issue(), item.story());
                        final List<String> blockers = item.blockedBy();
                        outcome.blockers =
                                blockers == null
                                        ? null
                                        : targets(read, keys, item.issue(), blockers);
                    });
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
            if (outcome.refused()) {
                refused.add(item.key());
            }
        }
        for (final Outcome outcome : creates) {
            final List<BoardChanges.Target> blockers = outcome.blockers;
            final String id = outcome.item;
            if (!outcome.refused() && id != null && blockers != null && !blockers.isEmpty()) {
                outcome.attempt(
                        () ->
                                outcome.write(
                                        store,
                                        "blockers",
                                        Set.of(id),
                                        () ->
                                                edits.setBlockedBy(
                                                        name,
                                                        id,
                                                        seen(store),
                                                        ids(blockers, made, id))));
            }
        }
        for (int index = 0; index < updates.size(); index++) {
            final BoardChangeRequests.Update item = change.updates().get(index);
            final Outcome outcome = updates.get(index);
            outcome.attempt(() -> update(name, store, item, outcome, made));
        }

        final List<ItemResult> results = new ArrayList<>();
        // An item no step of which wrote or answered has no version: the board read before any
        // write is not the board a lock judged, so none is taken from it.
        for (final Outcome outcome : outcomes) {
            results.add(outcome.result(call.versions));
        }
        return new BatchSaved(call.after.revision().value(), results, Map.copyOf(call.versions));
    }

    /** The parts of a new issue a text edit writes after it is made: all but What must be true. */
    private static @Nullable TextEdit restOf(final BoardChangeRequests.NewItem item) {
        final Map<TextEdit.Part, String> rest = new LinkedHashMap<>(item.parts());
        rest.remove(TextEdit.Part.WHAT_MUST_BE_TRUE);
        if (rest.isEmpty()) {
            return null;
        }
        return new TextEdit(TextEdit.Kind.ISSUE, null, rest, EnumSet.noneOf(TextEdit.Part.class));
    }

    /** The items an ItemChanged refusal names, as the board holds them now, or gone. */
    private List<ChangedItem> changedItems(
            final String name, final StoreException.Changed changed) {
        try {
            return BoardController.changed(
                    TaskHelds.Counts.NONE, projects.work(name), changed.ids());
        } catch (final RuntimeException unreadable) {
            // The refusal stands; only the items it would show cannot be read now.
            return changed.ids().stream()
                    .map(id -> new ChangedItem(id, true, null, null, null))
                    .toList();
        }
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
        final @Nullable String story = storyOf(outcome.story, Map.of(), "");
        final Set<String> joined = story == null ? Set.of() : Set.of(story);
        final BoardController.AddedIssue added =
                outcome.guarded(
                        joined,
                        () ->
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
                                                story,
                                                item.state(),
                                                false,
                                                List.of())));
        outcome.item = added.id();
        final Set<String> targets = new java.util.HashSet<>(joined);
        targets.add(added.id());
        outcome.call.saved(added.board(), added.versions(), targets);
        outcome.stands("the new issue");
        if (!rest.isEmpty()) {
            outcome.write(
                    store,
                    "text",
                    Set.of(added.id()),
                    () ->
                            edits.setText(
                                    name,
                                    added.id(),
                                    seen(store),
                                    new TextEdit(
                                            TextEdit.Kind.ISSUE,
                                            null,
                                            rest,
                                            EnumSet.noneOf(TextEdit.Part.class))));
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
            outcome.write(
                    store,
                    "text",
                    Set.of(issue),
                    () ->
                            edits.setText(
                                    name,
                                    issue,
                                    seen(store),
                                    new TextEdit(
                                            TextEdit.Kind.ISSUE,
                                            item.title(),
                                            parts == null ? Map.of() : parts.written(),
                                            parts == null ? Set.of() : parts.removed())));
        }
        // A field equal to the issue's is not named, so a change of nothing writes nothing.
        final BoardChangeRequests.@Nullable Size asked = item.size();
        final BoardChangeRequests.@Nullable Size size =
                asked == null || java.util.Objects.equals(asked.value(), held.size())
                        ? null
                        : asked;
        final BoardChangeRequests.@Nullable Estimate estimate =
                unchanged(item.estimateMinutes(), held.estimate()) ? null : item.estimateMinutes();
        final @Nullable String priority =
                java.util.Objects.equals(item.priority(), held.priority()) ? null : item.priority();
        if (size != null || estimate != null || priority != null) {
            final Set<Fields.Field> named = EnumSet.noneOf(Fields.Field.class);
            if (size != null) {
                named.add(Fields.Field.SIZE);
            }
            if (estimate != null) {
                named.add(Fields.Field.ESTIMATE);
            }
            if (priority != null) {
                named.add(Fields.Field.PRIORITY);
            }
            outcome.write(
                    store,
                    "fields",
                    Set.of(issue),
                    () ->
                            edits.setFields(
                                    name,
                                    issue,
                                    seen(store),
                                    new Fields(
                                            named,
                                            size == null ? null : size.value(),
                                            estimate == null ? null : estimate.minutes(),
                                            priority,
                                            null)));
        }
        final org.fuin.porelo.planner.store.@Nullable Worker worker = item.worker();
        if (worker != null) {
            outcome.write(
                    store,
                    "worker",
                    Set.of(issue),
                    () ->
                            board.workerOf(
                                    name,
                                    issue,
                                    new WorkRequests.WorkerRequest(worker, seen(store))));
        }
        final List<BoardChanges.Target> blockers = outcome.blockers;
        if (blockers != null) {
            outcome.write(
                    store,
                    "blockers",
                    Set.of(issue),
                    () -> edits.setBlockedBy(name, issue, seen(store), ids(blockers, made, issue)));
        }
        final String story = storyOf(outcome.story, made, issue);
        if (story != null) {
            // The story's list and the versions seen are read from one board, so an issue another
            // call added meanwhile is not dropped from the list.
            final Board current = store.board();
            final List<String> issues =
                    new ArrayList<>(current.story(story).map(Story::issues).orElse(List.of()));
            if (!issues.contains(issue)) {
                issues.add(issue);
                outcome.write(
                        store,
                        "story",
                        Set.of(issue, story),
                        () -> edits.setStoryIssues(name, story, Basis.of(current), issues));
            }
        }
        final String state = item.state();
        if (state != null) {
            final BoardController.SavedMoves batch =
                    outcome.guarded(
                            Set.of(issue),
                            () ->
                                    board.moveMany(
                                            name, List.of(new MoveIssues.Ask(issue, state, null))));
            final ItemResult moved = batch.answer().results().get(0);
            final @Nullable Problem refusal = moved.refusal();
            if (refusal != null) {
                // A refused move wrote nothing, so the version an earlier step gave stands.
                outcome.refuse(refusal);
                return;
            }
            final Set<String> moves = new java.util.HashSet<>(batch.answer().versions().keySet());
            moves.add(issue);
            outcome.call.saved(batch.board(), batch.answer().versions(), moves);
            outcome.state = moved.state();
            if (moved.outcome() == ItemResult.OutcomeEnum.SAVED) {
                outcome.stands("state");
            }
        }
    }

    /**
     * Whether an update's estimate is what the issue holds already, so naming it writes nothing.
     *
     * @param asked the estimate the update names, or null when it names none
     * @param held the issue's estimate in minutes, or null when it has none
     * @return true when the update names no estimate or the one the issue holds
     */
    private static boolean unchanged(
            final BoardChangeRequests.@Nullable Estimate asked, final @Nullable Double held) {
        if (asked == null) {
            return true;
        }
        final @Nullable Integer minutes = asked.minutes();
        return minutes == null ? held == null : held != null && held == minutes.doubleValue();
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

    /** What one call of the change shares across its items. */
    private static final class Call {
        private final String project;
        private final Function<StoreException.Changed, List<ChangedItem>> items;
        private Board after;
        private final Map<String, String> versions = new java.util.TreeMap<>();

        /** The refusal of a store that cannot be reached or is busy, which the later items get. */
        private @Nullable Problem stopped;

        Call(
                final String project,
                final Function<StoreException.Changed, List<ChangedItem>> items,
                final Board before) {
            this.project = project;
            this.items = items;
            this.after = before;
        }

        /**
         * Merges a completed step: the versions of its targets replace what earlier steps gave, and
         * no version of a story that holds or held a target stays, since the step changed it too.
         * The change names no versions seen, so a story is answered only when it is a target.
         */
        void saved(
                final Board board, final Map<String, String> written, final Set<String> targets) {
            forget(targets, board);
            written.forEach(
                    (id, version) -> {
                        if (targets.contains(id)) {
                            versions.put(id, version);
                        }
                    });
            after = board;
        }

        /**
         * Leaves out the versions of a step that threw, whatever it wrote before: of its targets
         * and of every story that holds one, and of any earlier step.
         */
        void lost(final Set<String> targets) {
            forget(targets, after);
        }

        private void forget(final Set<String> targets, final Board board) {
            versions.keySet().removeAll(targets);
            versions.keySet().removeAll(WrittenVersions.holding(after, board, targets));
        }
    }

    /** What became of one item of the change. */
    private static final class Outcome {
        private final Call call;
        private @Nullable String item;
        private final @Nullable String key;
        private final String repository;
        private BoardChanges.@Nullable Target story;
        private @Nullable List<BoardChanges.Target> blockers;
        private final List<String> stands = new ArrayList<>();
        private @Nullable String state;
        private @Nullable Problem problem;

        Outcome(
                final Call call,
                final @Nullable String item,
                final @Nullable String key,
                final String repository) {
            this.call = call;
            this.item = item;
            this.key = key;
            this.repository = repository;
        }

        boolean refused() {
            return problem != null;
        }

        /** Notes that a step of the item wrote. */
        void stands(final String step) {
            stands.add(step);
        }

        /**
         * Runs one write of the item and notes it as standing when it changed the item or its
         * story; that is told by the versions of the targets before and after, so another call's
         * save elsewhere on the board is no write of this item.
         */
        void write(
                final PlanningStore store,
                final String step,
                final Set<String> targets,
                final java.util.function.Supplier<BoardEdits.Saved> write) {
            final Board before = store.board();
            final BoardEdits.Saved saved = guarded(targets, write);
            call.saved(saved.board(), saved.versions(), targets);
            final Basis basis = Basis.of(before);
            final Basis after = Basis.of(saved.board());
            if (saved.versions().isEmpty()
                    || targets.stream()
                            .anyMatch(id -> !Objects.equals(basis.of(id), after.of(id)))) {
                stands(step);
            }
        }

        /**
         * Runs one call of a step and, when it throws, leaves its targets out of the versions: it
         * may have written some of them before it threw.
         */
        <T> T guarded(final Set<String> targets, final java.util.function.Supplier<T> step) {
            try {
                return step.get();
            } catch (final RuntimeException failure) {
                this.call.lost(targets);
                throw failure;
            }
        }

        /** Runs one step of the item, unless an earlier step refused it, keeping its refusal. */
        void attempt(final Runnable step) {
            if (problem != null) {
                return;
            }
            final @Nullable Problem stopped = call.stopped;
            if (stopped != null) {
                refuse(stopped);
                return;
            }
            try {
                step.run();
            } catch (final RuntimeException failure) {
                fail(failure);
            }
        }

        /**
         * Answers the item with the refusal a failure stands for; a failure that is none of the
         * planner's refusals is the call's own and goes on.
         */
        void fail(final RuntimeException failure) {
            final Problem answer;
            if (failure instanceof StoreException.NotSavable notSavable) {
                answer =
                        Problems.answer(
                                Edits.refused(
                                        item == null ? "" : item,
                                        notSavable.field(),
                                        String.valueOf(notSavable.getMessage())));
            } else if (failure instanceof StoreException refusal) {
                answer = Problems.answer(refusal, call.items);
                if (refusal instanceof StoreException.Unreachable
                        || refusal instanceof StoreException.Busy
                        || refusal instanceof StoreException.StaleRevision) {
                    // The items after it are not tried against a store that cannot answer.
                    call.stopped = answer;
                }
            } else if (failure instanceof UnsupportedOperationException) {
                answer =
                        Problems.answer(
                                new Refusal(
                                        "NotOffered",
                                        HttpStatus.NOT_IMPLEMENTED,
                                        "This planner does not offer this change.",
                                        Map.of("project", call.project)));
            } else if (failure instanceof Moves.Refused
                    || failure instanceof WorkRefusal
                    || failure instanceof Refusal) {
                answer = Problems.answer(failure);
            } else {
                throw failure;
            }
            refuse(answer);
        }

        /**
         * Refuses the item; a changed issue some step of which wrote already is refused as
         * PartlySaved, never silently. A new issue that stands shows its id beside its refusal.
         */
        void refuse(final Problem refusal) {
            problem =
                    stands.isEmpty() || key != null || "PartlySaved".equals(refusal.title())
                            ? refusal
                            : Problems.partlySaved(
                                    item == null ? "" : item, repository, stands, refusal);
        }

        /** Answers the final version only for an item whose change was not refused. */
        ItemResult result(final Map<String, String> versions) {
            final @Nullable Problem refused = problem;
            if (refused != null) {
                return new ItemResult(
                        item, key, ItemResult.OutcomeEnum.REFUSED, null, null, null, refused);
            }
            final ItemResult.OutcomeEnum outcome =
                    stands.isEmpty()
                            ? ItemResult.OutcomeEnum.UNCHANGED
                            : ItemResult.OutcomeEnum.SAVED;
            final String id = item;
            final String version = id == null ? null : versions.get(id);
            return new ItemResult(item, key, outcome, state, version, null, null);
        }
    }
}
