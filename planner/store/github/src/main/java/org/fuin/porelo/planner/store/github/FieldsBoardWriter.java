package org.fuin.porelo.planner.store.github;

import java.time.Instant;
import java.util.ArrayList;
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
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * Writes moves, workers, priorities and a stage's result to the GitHub project's fields, the
 * issues' bodies and their comments, with the person's own token. Right before it writes, it reads
 * the items it writes and refuses the save when a person changed one of them since the board was
 * read; GitHub takes no condition on a write, so a change in the moment between that read and the
 * write is not caught.
 */
public final class FieldsBoardWriter implements BoardWriter {

    /** The most pages of an issue's comments read to find whether its stage was written. */
    static final int MOST_COMMENT_PAGES = 20;

    private final ProjectWrites github;

    private final TokenSource tokens;

    private final AccessRefusals refusals;

    /**
     * Makes the writer.
     *
     * @param github the calls a write is made of
     * @param tokens the person's token, asked for once a write
     * @param refusals turns a failure into the store's refusal, scrubbed of the token
     */
    public FieldsBoardWriter(
            final ProjectWrites github, final TokenSource tokens, final AccessRefusals refusals) {
        this.github = github;
        this.tokens = tokens;
        this.refusals = refusals;
    }

    @Override
    public void save(final Snapshot current, final Revision revision, final Writes writes) {
        if (!current.board().revision().equals(revision)) {
            throw new StoreException.StaleRevision(current.board().revision().value());
        }
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

        private @Nullable Stage stage;

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
            if (closed == item.open()) {
                // The first closed state is done; any later one, such as Rejected, is not planned.
                openings.put(
                        id,
                        new ProjectWrites.Opening(
                                !closed, closed && workflow.closed().indexOf(state) > 0));
            } else {
                openings.remove(id);
            }
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

        void body(final String id, final ItemView item, final String body) {
            items.putIfAbsent(id, item);
            bodies.put(id, body);
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

        /** Checks that no item changed since the board was read, then makes every call. */
        void write(final String token) {
            final String own = current.config().own();
            if (current.config().projectId().isBlank()) {
                throw new StoreException.Unreadable(own, "the GitHub project's id was not read");
            }
            final Set<String> written = new LinkedHashSet<>();
            try {
                unchanged(token);
            } catch (final StoreException e) {
                throw e;
            } catch (final RuntimeException e) {
                throw refusals.refusal(own, e, token);
            }
            final @Nullable Stage planned = stage;
            final @Nullable Stage toComment =
                    planned == null || stageWritten(token, planned) ? null : planned;
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
            for (final Map.Entry<String, ItemView> entry : items.entrySet()) {
                final String id = entry.getKey();
                final String body = bodies.get(id);
                final ProjectWrites.Opening opening = openings.get(id);
                if (body == null && opening == null) {
                    continue;
                }
                final ItemView item = entry.getValue();
                try {
                    github.updateIssue(
                            token,
                            current.config().project().owner(),
                            item.repository(),
                            item.number(),
                            body,
                            opening);
                } catch (final RuntimeException e) {
                    throw failed(e, item.repository(), written, token);
                }
                written.add(item.repository());
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
                    throw refusals.refusal(current.config().own(), e, token);
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
     * A stage to be written as a comment.
     *
     * @param id the issue's id
     * @param key the stage's key
     * @param comment the comment's text
     */
    private record Stage(String id, String key, String comment) {}

    /**
     * What a person's change to an item shows in: its field values, its parent, its blockers,
     * whether it is open, and its body.
     */
    private record Seen(
            Map<String, String> fields,
            @Nullable String parent,
            List<String> blockers,
            boolean open,
            String body) {

        static Seen of(final ItemView item) {
            return new Seen(
                    item.fields(),
                    item.parentIssueId(),
                    item.blockedByIds(),
                    item.open(),
                    item.body());
        }

        static Seen of(final GitHubGraphQl.IssueOnBoard now, final Map<String, String> fieldNames) {
            final GitHubGraphQl.BoardIssue issue = now.issue();
            return new Seen(
                    ItemsBoardReader.values(now.values(), fieldNames),
                    issue.parentId(),
                    issue.blockedBy().nodes().stream().map(GitHubGraphQl.IssueRef::id).toList(),
                    "OPEN".equals(issue.state()),
                    Objects.requireNonNullElse(issue.body(), ""));
        }
    }
}
