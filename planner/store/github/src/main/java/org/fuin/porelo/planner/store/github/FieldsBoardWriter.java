package org.fuin.porelo.planner.store.github;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * Writes moves, workers, priorities, a stage's result and the edits by hand to the GitHub project's
 * fields, the issues' titles and bodies and their comments, and adds new issues and ideas, with the
 * person's own token. Right before it writes, it reads the items it writes and refuses the save
 * when a person changed one of them since the board was read; GitHub takes no condition on a write,
 * so a change in the moment between that read and the write is not caught.
 */
public final class FieldsBoardWriter implements BoardWriter {

    /** The most pages of an issue's comments read to find whether its stage was written. */
    static final int MOST_COMMENT_PAGES = 20;

    private final ProjectWrites github;

    private final TokenSource tokens;

    private final AccessRefusals refusals;

    private final InstantSource clock;

    /**
     * Makes the writer.
     *
     * @param github the calls a write is made of
     * @param tokens the person's token, asked for once a write
     * @param refusals turns a failure into the store's refusal, scrubbed of the token
     */
    public FieldsBoardWriter(
            final ProjectWrites github, final TokenSource tokens, final AccessRefusals refusals) {
        this(github, tokens, refusals, InstantSource.system());
    }

    /**
     * Makes the writer with its own clock.
     *
     * @param github the calls a write is made of
     * @param tokens the person's token, asked for once a write
     * @param refusals turns a failure into the store's refusal, scrubbed of the token
     * @param clock tells the time a new issue's call is sent at
     */
    public FieldsBoardWriter(
            final ProjectWrites github,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final InstantSource clock) {
        this.github = github;
        this.tokens = tokens;
        this.refusals = refusals;
        this.clock = clock;
    }

    @Override
    public void save(final Snapshot current, final Revision revision, final Writes writes) {
        fresh(current, revision);
        if (writes.none()) {
            return;
        }
        final Plan plan = new Plan(current);
        writes.issues()
                .forEach(
                        (id, state) -> {
                            final ItemView item = issueItem(current, id);
                            plan.state(id, item, state);
                        });
        writes.workers()
                .forEach(
                        (id, worker) ->
                                plan.option(
                                        id,
                                        issueItem(current, id),
                                        FieldNames.WORKER,
                                        worker == Worker.MANUAL ? "Manual" : "Agent"));
        writes.stories()
                .forEach(
                        (id, state) -> {
                            final ItemView item = storyItem(current, id);
                            plan.state(id, item, state);
                        });
        for (final String id : writes.unstated()) {
            plan.clear(id, storyItem(current, id), FieldNames.STATUS);
        }
        writes.priorities()
                .forEach(
                        (id, priority) ->
                                plan.option(
                                        id, storyItem(current, id), FieldNames.PRIORITY, priority));
        final String token = tokens.token(current.config().forge());
        plan.write(token);
    }

    /**
     * Makes the issue in its repository, adds it to the project and sets its fields; a call GitHub
     * took without answering is never sent again blindly.
     */
    @Override
    public String createIssue(
            final Snapshot current, final Revision revision, final NewIssue issue) {
        fresh(current, revision);
        final NewItems adding = new NewItems(github, refusals, clock, current);
        // An issue carries no priority of its own, so it takes the Medium a new issue has in Git.
        final NewItems.Planned planned = adding.issue(issue, "Medium");
        adding.write(tokens.token(current.config().forge()), null, List.of(planned));
        return planned.reference();
    }

    /**
     * Makes the story labeled as one, then each issue, adds them to the project, makes the issues
     * the story's sub-issues in the idea's order and sets their fields.
     */
    @Override
    public IdeaIds createIdea(final Snapshot current, final Revision revision, final NewIdea idea) {
        fresh(current, revision);
        final Set<String> named = new HashSet<>();
        for (final NewIssue issue : idea.issues()) {
            if (!named.add(issue.repository())) {
                throw new StoreException.NotSavable(
                        "", "the idea names " + issue.repository() + " twice");
            }
        }
        final NewItems adding = new NewItems(github, refusals, clock, current);
        final NewItems.Planned story = adding.story(idea);
        final List<NewItems.Planned> issues = new ArrayList<>();
        idea.issues().forEach(issue -> issues.add(adding.issue(issue, idea.priority())));
        adding.write(tokens.token(current.config().forge()), story, issues);
        return new IdeaIds(
                story.reference(), issues.stream().map(NewItems.Planned::reference).toList());
    }

    @Override
    public void saveResult(
            final Snapshot current, final StageResult result, final @Nullable StageEntry stage) {
        final Workflow workflow = current.config().workflow();
        final Plan plan = new Plan(current);
        for (final StageResult.ForIssue given : result.issues()) {
            final String id = given.id();
            final ItemView item = issueItem(current, id);
            final Issue issue =
                    current.board().issue(id).orElseThrow(() -> new StoreException.NoSuchIssue(id));
            if (!issue.state().equals(given.from())) {
                throw new StoreException.NotSavable(
                        id,
                        "it stands in " + issue.state() + ", not in the state the stage completes");
            }
            if (!workflow.states().contains(given.to()) || workflow.closed().contains(given.to())) {
                throw new StoreException.NotSavable(
                        id, "a result moves it to an open state of the workflow only");
            }
            plan.state(id, item, given.to());
            plan.option(id, item, FieldNames.SIZE, given.size());
            final Integer minutes = given.estimateMinutes();
            if (minutes != null) {
                plan.number(id, item, FieldNames.ESTIMATE, minutes);
            }
            @Nullable String body;
            try {
                body = BodySections.read(item.body()).withResult(result.kind(), given).text();
            } catch (final BodySections.Ambiguous e) {
                // Never written over a guess; the board names the body for a person to repair.
                body = null;
            } catch (final IllegalArgumentException e) {
                throw new StoreException.NotSavable(
                        id, "its body cannot hold the result: " + e.getMessage());
            }
            if (body != null && !body.equals(item.body())) {
                plan.body(id, item, body);
            }
        }
        final String token = tokens.token(current.config().forge());
        if (stage != null && result.issues().size() == 1) {
            final String id = result.issues().get(0).id();
            plan.stage(id, issueItem(current, id), stage);
        }
        plan.write(token);
    }

    @Override
    public boolean setFields(
            final Snapshot current,
            final Revision revision,
            final String issue,
            final Fields fields) {
        fresh(current, revision);
        final ItemView item = issueItem(current, issue);
        final Plan plan = new Plan(current);
        for (final Fields.Field field : fields.named()) {
            switch (field) {
                case SIZE -> plan.optionOrClear(issue, item, FieldNames.SIZE, fields.size());
                case ESTIMATE ->
                        plan.numberOrClear(
                                issue, item, FieldNames.ESTIMATE, fields.estimateMinutes());
                case PRIORITY ->
                        plan.optionOrClear(issue, item, FieldNames.PRIORITY, fields.priority());
                case RANK -> plan.numberOrClear(issue, item, FieldNames.RANK, fields.rank());
            }
        }
        if (plan.empty()) {
            return false;
        }
        plan.write(tokens.token(current.config().forge()));
        return true;
    }

    /**
     * Sets a story's sub-issues to the list: takes out those it no longer names, makes the new ones
     * its sub-issues, then orders them as the list does. An issue the story holds only under {@code
     * Also needs} in its body keeps its place after the sub-issues and is refused taken out, since
     * this write does not change the body.
     */
    @Override
    public boolean setStoryIssues(
            final Snapshot current,
            final Revision revision,
            final String story,
            final List<String> issues) {
        fresh(current, revision);
        final ItemView parent = storyItem(current, story);
        final Story found =
                current.board()
                        .story(story)
                        .orElseThrow(() -> new StoreException.NoSuchStory(story));
        final Map<String, String> idOf = new LinkedHashMap<>();
        current.items().forEach((id, view) -> idOf.put(view.issueId(), id));
        final List<String> subs = new ArrayList<>();
        for (final String node : parent.subIssueIds()) {
            final String id = idOf.get(node);
            if (id != null && found.issues().contains(id)) {
                subs.add(id);
            }
        }
        final Plan plan = new Plan(current);
        final List<String> order = new ArrayList<>();
        for (final String id : subs) {
            if (issues.contains(id)) {
                order.add(id);
            } else {
                plan.link(Link.Kind.REMOVE_SUB, story, parent, issueItem(current, id));
            }
        }
        for (final String id : found.issues()) {
            if (!subs.contains(id) && !issues.contains(id)) {
                throw new StoreException.NotSavable(
                        story,
                        "the issue "
                                + id
                                + " stands under Also needs in the story's body, which this"
                                + " write does not change");
            }
        }
        final List<String> also = found.issues().stream().filter(id -> !subs.contains(id)).toList();
        if (!issues.subList(issues.size() - also.size(), issues.size()).equals(also)) {
            // GitHub orders sub-issues only; the board puts the body's issues after them.
            throw new StoreException.NotSavable(
                    story,
                    "the issues under Also needs in the story's body stand after its sub-issues,"
                            + " in their order, which this write does not change");
        }
        for (final String id : issues) {
            if (found.issues().contains(id)) {
                continue;
            }
            final ItemView child = issueItem(current, id);
            if (child.parentIssueId() != null) {
                throw new StoreException.NotSavable(
                        story, "the issue " + id + " has a parent on GitHub already");
            }
            plan.link(Link.Kind.ADD_SUB, story, parent, child);
            order.add(id);
        }
        final List<String> wanted = issues.stream().filter(id -> order.contains(id)).toList();
        for (int at = 0; at < wanted.size(); at++) {
            final String id = wanted.get(at);
            if (order.get(at).equals(id)) {
                continue;
            }
            order.remove(id);
            final ItemView sibling = issueItem(current, order.get(at));
            order.add(at, id);
            final ItemView child = issueItem(current, id);
            if (at == 0) {
                plan.order(story, parent, child, null, sibling);
            } else {
                plan.order(story, parent, child, issueItem(current, wanted.get(at - 1)), null);
            }
        }
        if (plan.empty()) {
            return false;
        }
        plan.write(tokens.token(current.config().forge()));
        return true;
    }

    @Override
    public boolean setBlockedBy(
            final Snapshot current,
            final Revision revision,
            final String issue,
            final List<String> blockedBy) {
        fresh(current, revision);
        final ItemView item = issueItem(current, issue);
        final Issue found =
                current.board()
                        .issue(issue)
                        .orElseThrow(() -> new StoreException.NoSuchIssue(issue));
        final Plan plan = new Plan(current);
        for (final String id : found.blockedBy()) {
            if (!blockedBy.contains(id)) {
                plan.link(Link.Kind.REMOVE_BLOCKER, issue, item, issueItem(current, id));
            }
        }
        for (final String id : blockedBy) {
            if (!found.blockedBy().contains(id)) {
                plan.link(Link.Kind.ADD_BLOCKER, issue, item, issueItem(current, id));
            }
        }
        if (plan.empty()) {
            return false;
        }
        plan.write(tokens.token(current.config().forge()));
        return true;
    }

    /**
     * Sets an item's title and named parts: the new body is built from the body the board read,
     * which the write's check confirms GitHub still holds, so every byte outside the parts named
     * stays; title and body go in one update.
     */
    @Override
    public boolean setText(
            final Snapshot current, final Revision revision, final String id, final TextEdit edit) {
        fresh(current, revision);
        final ItemView item =
                edit.kind() == TextEdit.Kind.STORY
                        ? storyItem(current, id)
                        : issueItem(current, id);
        final String body = body(id, item.body(), edit);
        final String title = edit.title();
        final boolean retitled = title != null && !title.equals(item.title());
        final boolean rewritten = !body.equals(item.body());
        if (!retitled && !rewritten) {
            return false;
        }
        final Plan plan = new Plan(current);
        plan.text(id, item, retitled ? title : null, rewritten ? body : null);
        plan.write(tokens.token(current.config().forge()));
        return true;
    }

    /** The body with the edit's parts removed and written, or the refusal in fixed words. */
    private static String body(final String id, final String held, final TextEdit edit) {
        try {
            BodySections body = BodySections.read(held);
            for (final TextEdit.Part part : TextEdit.Part.values()) {
                if (edit.removed().contains(part)) {
                    body = body.without(BodySections.Part.of(part));
                }
            }
            for (final TextEdit.Part part : TextEdit.Part.values()) {
                final String text = edit.written().get(part);
                if (text != null) {
                    body = body.withEdited(BodySections.Part.of(part), text);
                }
            }
            return body.text();
        } catch (final IllegalArgumentException e) {
            // BodySections' words, an ambiguous body's repair too, are fixed and never repeat it.
            throw new TextEdit.Refused(id, String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void comment(final Snapshot current, final String issue, final String text) {
        final ItemView item = issueItem(current, issue);
        final String token = tokens.token(current.config().forge());
        try {
            github.createIssueComment(
                    token,
                    current.config().project().owner(),
                    item.repository(),
                    item.number(),
                    text);
        } catch (final RuntimeException e) {
            throw new Plan(current).refusedCall(e, issue, item.repository(), Set.of(), token);
        }
    }

    /** Refuses a write planned on another board than the store holds, before any call. */
    private static void fresh(final Snapshot current, final Revision revision) {
        if (!current.board().revision().equals(revision)) {
            throw new StoreException.StaleRevision(current.board().revision().value());
        }
    }

    /** The item of an issue on the board, or the refusal that it has none. */
    private static ItemView issueItem(final Snapshot current, final String id) {
        final ItemView item = current.items().get(id);
        if (item == null || current.board().issue(id).isEmpty()) {
            throw new StoreException.NoSuchIssue(id);
        }
        return item;
    }

    /** The item of a story on the board, or the refusal that it has none. */
    private static ItemView storyItem(final Snapshot current, final String id) {
        final ItemView item = current.items().get(id);
        if (item == null || current.board().story(id).isEmpty()) {
            throw new StoreException.NoSuchStory(id);
        }
        return item;
    }

    /** What one write sets, by item, and the calls it makes once every part is checked. */
    private final class Plan {

        private final Snapshot current;

        /** The items written, by their Porelo id, in the order first named. */
        private final Map<String, ItemView> items = new LinkedHashMap<>();

        /** The field values, one per item and field, the last named winning. */
        private final Map<String, Value> values = new LinkedHashMap<>();

        private final Map<String, ProjectWrites.Opening> openings = new LinkedHashMap<>();

        private final Map<String, String> bodies = new LinkedHashMap<>();

        private final Map<String, String> titles = new LinkedHashMap<>();

        private @Nullable Stage stage;

        /** The links to change, in their order, after every field value. */
        private final List<Link> links = new ArrayList<>();

        /** What GitHub held of each item right before the write, by the issue's node id. */
        private final Map<String, GitHubGraphQl.BoardIssue> reads = new LinkedHashMap<>();

        Plan(final Snapshot current) {
            this.current = current;
        }

        void state(final String id, final ItemView item, final String state) {
            final Workflow workflow = current.config().workflow();
            if (!workflow.states().contains(state)) {
                throw new StoreException.NotSavable(id, "its state is none of the workflow");
            }
            option(id, item, FieldNames.STATUS, state);
            final boolean closed = workflow.closed().contains(state);
            // The first closed state is done; any later one, such as Rejected, is not planned.
            final boolean notPlanned = closed && workflow.closed().indexOf(state) > 0;
            if (closed == item.open() || (closed && otherReason(item.stateReason(), notPlanned))) {
                openings.put(id, new ProjectWrites.Opening(!closed, notPlanned));
            } else {
                openings.remove(id);
            }
        }

        /**
         * Whether GitHub closed an issue for another reason than its closed state gives; a
         * duplicate counts as not planned, so a person's choice of it stays.
         */
        private static boolean otherReason(
                final @Nullable String reason, final boolean notPlanned) {
            return notPlanned ? "completed".equals(reason) : !"completed".equals(reason);
        }

        void option(final String id, final ItemView item, final String field, final String name) {
            final String fieldId = fieldId(id, field);
            final String optionId =
                    current.config().optionIds().getOrDefault(field, Map.of()).get(name);
            if (optionId == null) {
                throw new StoreException.NotSavable(
                        id, "the project's field " + field + " has no option " + name);
            }
            put(id, item, field, GitHubGraphQl.FieldValue.option(item.itemId(), fieldId, optionId));
        }

        void number(final String id, final ItemView item, final String field, final double n) {
            put(
                    id,
                    item,
                    field,
                    GitHubGraphQl.FieldValue.number(item.itemId(), fieldId(id, field), n));
        }

        void clear(final String id, final ItemView item, final String field) {
            put(id, item, field, GitHubGraphQl.FieldValue.clear(item.itemId(), fieldId(id, field)));
        }

        /** Sets an option, or clears the field for null; a value the item holds is left. */
        void optionOrClear(
                final String id,
                final ItemView item,
                final String field,
                final @Nullable String name) {
            final String held = item.fields().getOrDefault(field, "");
            if (name == null) {
                if (!held.isEmpty()) {
                    clear(id, item, field);
                }
            } else if (!name.equals(held)) {
                option(id, item, field, name);
            }
        }

        /** Sets a whole number, or clears the field for null; a value the item holds is left. */
        void numberOrClear(
                final String id,
                final ItemView item,
                final String field,
                final @Nullable Integer n) {
            final String held = item.fields().getOrDefault(field, "");
            if (n == null) {
                if (!held.isEmpty()) {
                    clear(id, item, field);
                }
            } else if (!Integer.toString(n).equals(held)) {
                number(id, item, field, n);
            }
        }

        /** Adds a link to change; both items are read before the write and checked unchanged. */
        void link(final Link.Kind kind, final String id, final ItemView from, final ItemView to) {
            items.putIfAbsent(id, from);
            items.putIfAbsent(idOf(to), to);
            links.add(new Link(kind, id, from, to, null, null));
        }

        /** Moves a sub-issue right after one sibling or right before another. */
        void order(
                final String id,
                final ItemView parent,
                final ItemView child,
                final @Nullable ItemView after,
                final @Nullable ItemView before) {
            items.putIfAbsent(id, parent);
            for (final ItemView each : java.util.Arrays.asList(child, after, before)) {
                if (each != null) {
                    items.putIfAbsent(idOf(each), each);
                }
            }
            links.add(new Link(Link.Kind.ORDER, id, parent, child, after, before));
        }

        /** Whether nothing is to be written. */
        boolean empty() {
            return values.isEmpty()
                    && openings.isEmpty()
                    && bodies.isEmpty()
                    && titles.isEmpty()
                    && links.isEmpty()
                    && stage == null;
        }

        private String idOf(final ItemView item) {
            for (final Map.Entry<String, ItemView> entry : current.items().entrySet()) {
                if (entry.getValue().issueId().equals(item.issueId())) {
                    return entry.getKey();
                }
            }
            throw new IllegalStateException("An item of the snapshot has no id");
        }

        void body(final String id, final ItemView item, final String body) {
            items.putIfAbsent(id, item);
            bodies.put(id, body);
        }

        /** Sets a title, a body or both, written in one update. */
        void text(
                final String id,
                final ItemView item,
                final @Nullable String title,
                final @Nullable String body) {
            items.putIfAbsent(id, item);
            if (title != null) {
                titles.put(id, title);
            }
            if (body != null) {
                bodies.put(id, body);
            }
        }

        /**
         * Adds a stage: its comment and its figures added to the number fields as they stand; a
         * field without a value counts as 0.
         */
        void stage(final String id, final ItemView item, final StageEntry entry) {
            final String comment;
            try {
                comment = StageComment.write(entry);
            } catch (final IllegalArgumentException e) {
                throw new StoreException.NotSavable(
                        id, "its stage cannot be written as a comment: " + e.getMessage());
            }
            items.putIfAbsent(id, item);
            if (StageComment.summed(entry)) {
                number(
                        id,
                        item,
                        FieldNames.MINUTES,
                        base(id, item, FieldNames.MINUTES) + entry.minutes());
                number(
                        id,
                        item,
                        FieldNames.VM_MINUTES,
                        base(id, item, FieldNames.VM_MINUTES) + entry.vmMinutes());
                number(
                        id,
                        item,
                        FieldNames.TOKENS,
                        base(id, item, FieldNames.TOKENS) + StageComment.newTokens(entry));
                number(id, item, FieldNames.STAGES, base(id, item, FieldNames.STAGES) + 1);
            }
            stage = new Stage(id, entry.key(), comment);
        }

        private double base(final String id, final ItemView item, final String field) {
            final String given = item.fields().getOrDefault(field, "").strip();
            if (given.isEmpty()) {
                return 0;
            }
            try {
                final double value = Double.parseDouble(given);
                if (Double.isFinite(value)) {
                    return value;
                }
            } catch (final NumberFormatException e) {
                // Named below, as a value that is no number.
            }
            throw new StoreException.NotSavable(id, "its field " + field + " holds no number");
        }

        private String fieldId(final String id, final String field) {
            final String fieldId = current.config().fieldIds().get(field);
            if (fieldId == null) {
                throw new StoreException.NotSavable(id, "the project has no field " + field);
            }
            return fieldId;
        }

        private void put(
                final String id,
                final ItemView item,
                final String field,
                final GitHubGraphQl.FieldValue value) {
            items.putIfAbsent(id, item);
            values.put(item.itemId() + "\n" + field, new Value(id, item, value));
        }

        /**
         * Checks that no item changed since the board was read, then makes every call: bodies and
         * open states first, field values next, the stage's comment last, so a body GitHub refuses
         * leaves the state and the sums unwritten and the result can be handed in again.
         */
        void write(final String token) {
            final String own = current.config().own();
            if (current.config().projectId().isBlank()) {
                throw new StoreException.Unreadable(own, "the GitHub project's id was not read");
            }
            final Set<String> written = new LinkedHashSet<>();
            final @Nullable Stage planned = stage;
            // The comments may take many calls, so they are read before the check, never between
            // the check and the write.
            final @Nullable Stage toComment =
                    planned == null || stageWritten(token, planned) ? null : planned;
            try {
                unchanged(token);
            } catch (final StoreException e) {
                throw e;
            } catch (final RuntimeException e) {
                throw refusals.refusal(own, e, token);
            }
            for (final Map.Entry<String, ItemView> entry : items.entrySet()) {
                final String id = entry.getKey();
                final String title = titles.get(id);
                final String body = bodies.get(id);
                final ProjectWrites.Opening opening = openings.get(id);
                if (title == null && body == null && opening == null) {
                    continue;
                }
                final ItemView item = entry.getValue();
                try {
                    github.updateIssue(
                            token,
                            current.config().project().owner(),
                            item.repository(),
                            item.number(),
                            title,
                            body,
                            opening);
                } catch (final RuntimeException e) {
                    // GitHub's words may repeat the title or body sent, so a refusal is fixed.
                    throw refusedCall(e, id, item.repository(), written, token);
                }
                written.add(item.repository());
            }
            final List<Value> all = new ArrayList<>(values.values());
            if (planned != null && toComment == null) {
                // The stage stands already, so its figures were added with it.
                all.removeIf(
                        value ->
                                value.id().equals(planned.id())
                                        && summedField(value.value().fieldId()));
            }
            final String projectId = current.config().projectId();
            for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_BATCH) {
                final List<Value> chunk =
                        all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_BATCH));
                final Batch<String> batch;
                try {
                    batch =
                            github.setFieldValues(
                                    token, projectId, chunk.stream().map(Value::value).toList());
                } catch (final RuntimeException e) {
                    throw failed(e, chunk.get(0).item().repository(), written, token);
                }
                @Nullable Value refused = null;
                @Nullable String error = null;
                for (int at = 0; at < chunk.size(); at++) {
                    final Batch.Outcome<String> outcome =
                            at < batch.outcomes().size() ? batch.outcomes().get(at) : null;
                    if (outcome != null && outcome.saved()) {
                        written.add(chunk.get(at).item().repository());
                    } else if (refused == null) {
                        refused = chunk.get(at);
                        error = outcome == null ? null : outcome.error();
                    }
                }
                if (refused != null) {
                    final String reason =
                            "GitHub refused a field value of "
                                    + refused.id()
                                    + (error == null
                                            ? ""
                                            : ": " + Scrubbed.text(error, List.of(token)));
                    if (written.isEmpty()) {
                        throw new StoreException.NotSavable(refused.id(), reason);
                    }
                    throw new StoreException.PartlySaved(
                            refused.item().repository(), List.copyOf(written), reason);
                }
            }
            final String owner = current.config().project().owner();
            for (final Link link : links) {
                final ItemView from = link.from();
                try {
                    change(token, owner, link);
                } catch (final RuntimeException e) {
                    throw refusedCall(e, link.id(), from.repository(), written, token);
                }
                written.add(from.repository());
            }
            if (toComment != null) {
                final ItemView item = Objects.requireNonNull(items.get(toComment.id()));
                try {
                    github.createIssueComment(
                            token,
                            current.config().project().owner(),
                            item.repository(),
                            item.number(),
                            toComment.comment());
                } catch (final RuntimeException e) {
                    throw failed(e, item.repository(), written, token);
                }
            }
        }

        private void change(final String token, final String owner, final Link link) {
            final ItemView from = link.from();
            final long to = databaseId(link.to());
            switch (link.kind()) {
                case ADD_SUB ->
                        github.addSubIssue(token, owner, from.repository(), from.number(), to);
                case REMOVE_SUB ->
                        github.removeSubIssue(token, owner, from.repository(), from.number(), to);
                case ORDER -> {
                    final ItemView after = link.after();
                    final ItemView before = link.before();
                    github.reprioritizeSubIssue(
                            token,
                            owner,
                            from.repository(),
                            from.number(),
                            to,
                            after == null ? null : databaseId(after),
                            before == null ? null : databaseId(before));
                }
                case ADD_BLOCKER ->
                        github.addBlockedBy(token, owner, from.repository(), from.number(), to);
                case REMOVE_BLOCKER ->
                        github.removeBlockedBy(token, owner, from.repository(), from.number(), to);
            }
        }

        /** An issue's id in REST, as GitHub answered it in the read right before the write. */
        private long databaseId(final ItemView item) {
            final GitHubGraphQl.BoardIssue read = reads.get(item.issueId());
            if (read == null) {
                throw new IllegalStateException("An item linked was not read before the write");
            }
            return read.databaseId();
        }

        /**
         * The refusal of a call GitHub refused or did not answer: a refusal of the request itself,
         * such as a link GitHub does not allow, is not savable in fixed words, since GitHub's own
         * words may repeat what was sent; partly saved once anything was written before it.
         */
        StoreException refusedCall(
                final RuntimeException e,
                final String id,
                final String repository,
                final Set<String> written,
                final String token) {
            if (e instanceof GitHubException refused
                    && refused.delivery() != Delivery.SENT_UNANSWERED
                    && refused.status() >= 400
                    && refused.status() < 500
                    && refused.status() != 401
                    && refused.status() != 403
                    && refused.status() != 429) {
                final String reason = "GitHub refused the call with status " + refused.status();
                if (written.isEmpty()) {
                    return new StoreException.NotSavable(id, reason);
                }
                return new StoreException.PartlySaved(
                        repository,
                        List.copyOf(written),
                        "A part of the save was written before a call failed: " + reason);
            }
            return failed(e, repository, written, token);
        }

        private boolean summedField(final String fieldId) {
            for (final String field :
                    List.of(
                            FieldNames.MINUTES,
                            FieldNames.VM_MINUTES,
                            FieldNames.TOKENS,
                            FieldNames.STAGES)) {
                if (fieldId.equals(current.config().fieldIds().get(field))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Reads the items to be written and refuses the save, naming the first, when one of them
         * differs from the board the save was planned on.
         */
        private void unchanged(final String token) {
            final Map<String, String> fieldNames = new LinkedHashMap<>();
            current.config().fieldIds().forEach((name, fieldId) -> fieldNames.put(fieldId, name));
            final List<Map.Entry<String, ItemView>> all = new ArrayList<>(items.entrySet());
            for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_ISSUES) {
                final List<Map.Entry<String, ItemView>> chunk =
                        all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_ISSUES));
                final GitHubGraphQl.IssueBoard read =
                        github.issueBoard(
                                token,
                                chunk.stream().map(e -> e.getValue().issueId()).toList(),
                                current.config().projectId());
                for (int at = 0; at < chunk.size(); at++) {
                    final ItemView item = chunk.get(at).getValue();
                    final GitHubGraphQl.IssueOnBoard now =
                            at < read.issues().size() ? read.issues().get(at) : null;
                    if (now == null
                            || !item.itemId().equals(now.itemId())
                            || !Seen.of(item).equals(Seen.of(now, fieldNames))) {
                        throw new StoreException.StaleRevision(
                                current.board().revision().value(), chunk.get(at).getKey());
                    }
                    reads.put(item.issueId(), now.issue());
                }
            }
        }

        /** Whether the stage's comment stands on its issue already, found by its key. */
        private boolean stageWritten(final String token, final Stage written) {
            final ItemView item = Objects.requireNonNull(items.get(written.id()));
            for (int page = 1; page <= MOST_COMMENT_PAGES; page++) {
                final ProjectWrites.CommentsPage comments;
                try {
                    comments =
                            github.issueComments(
                                    token,
                                    current.config().project().owner(),
                                    item.repository(),
                                    item.number(),
                                    page);
                } catch (final RuntimeException e) {
                    throw refusals.refusal(item.repository(), e, token);
                }
                for (final String body : comments.bodies()) {
                    final StageComment.Read read =
                            StageComment.read(new CommentView("comment", body, Instant.EPOCH))
                                    .orElse(null);
                    if (read instanceof StageComment.Stage found
                            && found.entry().key().equals(written.key())) {
                        return true;
                    }
                }
                if (!comments.more()) {
                    return false;
                }
            }
            throw new StoreException.NotSavable(
                    written.id(),
                    "it holds more comments than are read to find whether its stage stands");
        }

        /**
         * The refusal of a call that failed: partly saved once anything was written before it or
         * when GitHub may have taken it without answering, so the window reads the board anew and
         * nothing is made twice.
         */
        private StoreException failed(
                final RuntimeException e,
                final String repository,
                final Set<String> written,
                final String token) {
            final StoreException refusal = refusals.refusal(repository, e, token);
            final boolean unanswered =
                    e instanceof GitHubException unknown
                            && unknown.delivery() == Delivery.SENT_UNANSWERED;
            if (written.isEmpty() && !unanswered) {
                return refusal;
            }
            return new StoreException.PartlySaved(
                    repository,
                    List.copyOf(written),
                    (unanswered
                                    ? "GitHub took a call without answering, so whether it was"
                                            + " written is unknown"
                                    : "A part of the save was written before a call failed")
                            + ": "
                            + refusal.getMessage());
        }
    }

    /**
     * One field value, with the item it is written to.
     *
     * @param id the item's Porelo id
     * @param item the item
     * @param value the value
     */
    private record Value(String id, ItemView item, GitHubGraphQl.FieldValue value) {}

    /**
     * A link of one issue to another to change.
     *
     * @param kind what changes
     * @param id the Porelo id of the item it is changed on
     * @param from the parent, or the issue whose blockers change
     * @param to the sub-issue, or the blocker
     * @param after the sibling an ordered sub-issue follows, or null
     * @param before the sibling an ordered sub-issue precedes, or null
     */
    private record Link(
            Kind kind,
            String id,
            ItemView from,
            ItemView to,
            @Nullable ItemView after,
            @Nullable ItemView before) {

        /** What a link's change does. */
        enum Kind {
            ADD_SUB,
            REMOVE_SUB,
            ORDER,
            ADD_BLOCKER,
            REMOVE_BLOCKER
        }
    }

    /**
     * A stage to be written as a comment.
     *
     * @param id the issue's id
     * @param key the stage's key
     * @param comment the comment's text
     */
    private record Stage(String id, String key, String comment) {}

    /**
     * What a person's change to an item shows in: its field values, its parent, its sub-issues, its
     * blockers, its labels, whether either list runs past the one page read, whether it is open,
     * its title and its body.
     */
    private record Seen(
            Map<String, String> fields,
            @Nullable String parent,
            List<String> subIssues,
            List<String> blockers,
            List<String> labels,
            boolean more,
            boolean open,
            String title,
            String body) {

        static Seen of(final ItemView item) {
            // The board leaves out an item whose lists run past one page, so one read has none.
            return new Seen(
                    item.fields(),
                    item.parentIssueId(),
                    item.subIssueIds(),
                    item.blockedByIds(),
                    item.labels(),
                    false,
                    item.open(),
                    item.title(),
                    item.body());
        }

        static Seen of(final GitHubGraphQl.IssueOnBoard now, final Map<String, String> fieldNames) {
            final GitHubGraphQl.BoardIssue issue = now.issue();
            return new Seen(
                    ItemsBoardReader.values(now.values(), fieldNames),
                    issue.parentId(),
                    ids(issue.subIssues()),
                    ids(issue.blockedBy()),
                    List.copyOf(issue.labels()),
                    Boolean.TRUE.equals(issue.subIssues().pageInfo().hasNextPage())
                            || Boolean.TRUE.equals(issue.blockedBy().pageInfo().hasNextPage()),
                    "OPEN".equals(issue.state()),
                    Objects.requireNonNullElse(issue.title(), ""),
                    Objects.requireNonNullElse(issue.body(), ""));
        }

        private static List<String> ids(final GitHubGraphQl.IssueRefs refs) {
            return refs.nodes().stream().map(GitHubGraphQl.IssueRef::id).toList();
        }
    }
}
