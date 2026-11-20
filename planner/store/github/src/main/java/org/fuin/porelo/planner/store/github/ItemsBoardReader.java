package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TokenSource;
import org.jspecify.annotations.Nullable;

/**
 * Reads the whole GitHub project through its fields and every page of its items, and maps them to a
 * board. It only reads: nothing it holds can change the person's project.
 */
public final class ItemsBoardReader implements BoardReader {

    /** The most pages of items or fields one read takes; a project beyond them is not read. */
    static final int MOST_PAGES = 50;

    /** The most items a page carries, as the forge client asks for them. */
    static final int PAGE_SIZE = 100;

    /** How long one read of the whole project may take. */
    static final Duration DEADLINE = Duration.ofSeconds(60);

    private final ProjectQueries queries;

    private final TokenSource tokens;

    private final AccessRefusals refusals;

    private final StoryStates states;

    private final InstantSource clock;

    private final Duration deadline;

    /**
     * Makes the reader.
     *
     * @param queries the project's two reads
     * @param tokens the person's token, asked for once a read
     * @param refusals turns a failure of a read into the store's refusal
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     */
    public ItemsBoardReader(
            final ProjectQueries queries,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final StoryStates states) {
        this(queries, tokens, refusals, states, InstantSource.system(), DEADLINE);
    }

    /**
     * Makes the reader with its own clock and deadline.
     *
     * @param queries the project's two reads
     * @param tokens the person's token, asked for once a read
     * @param refusals turns a failure of a read into the store's refusal
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @param clock tells the time the deadline is measured by
     * @param deadline how long one read may take
     */
    ItemsBoardReader(
            final ProjectQueries queries,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final StoryStates states,
            final InstantSource clock,
            final Duration deadline) {
        this.queries = queries;
        this.tokens = tokens;
        this.refusals = refusals;
        this.states = states;
        this.clock = clock;
        this.deadline = deadline;
    }

    @Override
    public Snapshot read(final ProjectConfig config) {
        final String token = tokens.token(config.forge());
        final Call call = new Call(config, token, clock.instant().plus(deadline));
        final List<String> faults = new ArrayList<>();

        final Map<String, String> fieldIds = new LinkedHashMap<>();
        final Map<String, Map<String, String>> optionIds = new LinkedHashMap<>();
        final Map<String, String> fieldNames = new LinkedHashMap<>();
        final Map<String, String> dataTypes = new LinkedHashMap<>();
        @Nullable String after = null;
        for (int page = 1; ; page++) {
            final @Nullable String cursor = after;
            final GitHubGraphQl.ProjectFields fields =
                    call.of(
                            () ->
                                    queries.projectFields(
                                            token,
                                            config.project().owner(),
                                            config.project().number(),
                                            cursor));
            for (final GitHubGraphQl.ProjectField field : fields.fields()) {
                fieldIds.put(field.name(), field.id());
                fieldNames.put(field.id(), field.name());
                dataTypes.put(field.name(), field.dataType());
                if (!field.options().isEmpty()) {
                    final Map<String, String> byName = new LinkedHashMap<>();
                    field.options().forEach(option -> byName.put(option.name(), option.id()));
                    optionIds.put(field.name(), byName);
                }
            }
            after = call.next(fields.pageInfo(), cursor, page, "fields");
            if (after == null) {
                break;
            }
        }
        checkFields(config, dataTypes, optionIds, faults);

        final Map<String, GitHubGraphQl.ProjectItem> items = new LinkedHashMap<>();
        after = null;
        for (int page = 1; ; page++) {
            final @Nullable String cursor = after;
            final GitHubGraphQl.ProjectItems answer =
                    call.of(
                            () ->
                                    queries.projectItems(
                                            token,
                                            config.project().owner(),
                                            config.project().number(),
                                            cursor));
            if (answer.items().size() > PAGE_SIZE) {
                throw new StoreException.Unreadable(
                        config.own(), "GitHub answered a page with more items than asked for");
            }
            for (final GitHubGraphQl.ProjectItem item : answer.items()) {
                // An item seen again on a later page, as GitHub's order shifts while it is read,
                // counts once.
                items.putIfAbsent(item.id(), item);
            }
            after = call.next(answer.pageInfo(), cursor, page, "items");
            if (after == null) {
                break;
            }
        }

        final ProjectConfig read =
                new ProjectConfig(
                        config.forge(),
                        config.project(),
                        config.commit(),
                        config.workflow(),
                        config.work(),
                        config.repositories(),
                        fieldIds,
                        optionIds);
        final List<ItemView> views = new ArrayList<>();
        for (final GitHubGraphQl.ProjectItem item : items.values()) {
            final ItemView view = view(item, read, fieldNames, faults);
            if (view != null) {
                views.add(view);
            }
        }
        final IssueMapping.Mapped mapped = IssueMapping.read(views, read, states);
        final List<String> allFaults = new ArrayList<>(faults);
        allFaults.addAll(mapped.faults());
        final Board board =
                new Board(
                        revision(config.commit(), items.values(), fieldNames),
                        read.workflow(),
                        mapped.stories(),
                        mapped.issues(),
                        read.work(),
                        read.repositories(),
                        allFaults);
        final Map<String, ItemView> byId = new LinkedHashMap<>();
        for (final ItemView view : views) {
            final String id = IssueMapping.idOf(view);
            if (id != null) {
                byId.putIfAbsent(id, view);
            }
        }
        return new Snapshot(board, byId, read);
    }

    /** One read's calls: its token, its deadline, and how a failure is answered. */
    private final class Call {

        private final ProjectConfig config;

        private final String token;

        private final Instant until;

        Call(final ProjectConfig config, final String token, final Instant until) {
            this.config = config;
            this.token = token;
            this.until = until;
        }

        <T> T of(final Supplier<T> read) {
            if (clock.instant().isAfter(until)) {
                throw new StoreException.Unreachable(
                        config.own(),
                        "GitHub did not give the whole project within "
                                + deadline.toSeconds()
                                + " s");
            }
            try {
                return read.get();
            } catch (final StoreException | UnsupportedOperationException e) {
                throw e;
            } catch (final GitHubException e) {
                if (e.rateLimited()) {
                    throw new StoreException.Unreachable(
                            config.own(), "GitHub's rate limit is spent; " + rereadIn(e.delay()));
                }
                throw refusals.refusal(config.own(), e, token);
            } catch (final RuntimeException e) {
                throw refusals.refusal(config.own(), e, token);
            }
        }

        /**
         * Answers the cursor of the next page, or null after the last.
         *
         * @throws StoreException.Unreadable if the bound is passed or the cursor does not advance
         */
        @Nullable String next(
                final GitHubGraphQl.PageInfo info,
                final @Nullable String cursor,
                final int page,
                final String what) {
            if (!Boolean.TRUE.equals(info.hasNextPage())) {
                return null;
            }
            if (page >= MOST_PAGES) {
                throw new StoreException.Unreadable(
                        config.own(),
                        "the project holds more "
                                + what
                                + " than "
                                + MOST_PAGES
                                + " pages carry; nothing of it is shown rather than a part");
            }
            final String end = info.endCursor();
            if (end == null || end.isEmpty() || end.equals(cursor)) {
                throw new StoreException.Unreadable(
                        config.own(),
                        "GitHub named a next page of " + what + " without a new place to read on");
            }
            return end;
        }
    }

    /** Names the wait GitHub asked for, in whole seconds rounded up. */
    private static String rereadIn(final @Nullable Duration wait) {
        if (wait == null) {
            return "it gave no time to read again";
        }
        final long whole = wait.toSeconds();
        return "it reads again in " + (wait.toNanosPart() > 0 ? whole + 1 : whole) + " s";
    }

    /** Names every field the store uses that the project lacks or holds of another kind. */
    private static void checkFields(
            final ProjectConfig config,
            final Map<String, String> dataTypes,
            final Map<String, Map<String, String>> optionIds,
            final List<String> faults) {
        for (final Map.Entry<String, FieldNames.Kind> field :
                new TreeMap<>(FieldNames.ALL).entrySet()) {
            final String type = dataTypes.get(field.getKey());
            if (type == null) {
                faults.add("The project has no field " + field.getKey());
            } else if (!type.equals(field.getValue().name())) {
                faults.add("The project's field " + field.getKey() + " is not of the kind used");
            }
        }
        final Map<String, List<String>> options = new LinkedHashMap<>();
        options.put(FieldNames.STATUS, config.workflow().states());
        options.put(FieldNames.PRIORITY, Story.PRIORITIES);
        options.put(FieldNames.SIZE, IssueMapping.SIZES);
        options.put(FieldNames.WORKER, List.of("Agent", "Manual"));
        for (final Map.Entry<String, List<String>> field : options.entrySet()) {
            if (!dataTypes.containsKey(field.getKey())) {
                continue;
            }
            final Map<String, String> held = optionIds.getOrDefault(field.getKey(), Map.of());
            for (final String option : field.getValue()) {
                if (!held.containsKey(option)) {
                    faults.add(
                            "The project's field " + field.getKey() + " has no option " + option);
                }
            }
        }
    }

    /**
     * Maps one item, or answers null for an item the board leaves out: one without an issue
     * silently, any other with a fault.
     */
    private static @Nullable ItemView view(
            final GitHubGraphQl.ProjectItem item,
            final ProjectConfig config,
            final Map<String, String> fieldNames,
            final List<String> faults) {
        final GitHubGraphQl.BoardIssue issue = item.issue();
        if (issue == null) {
            // A draft or a pull request is no planning item.
            return null;
        }
        final String where = "Item " + issue.repository() + "#" + issue.number();
        final String repository = ownName(issue.repository(), config.project().owner());
        if (repository == null) {
            faults.add(where + " lies outside the project's owner and is left out");
            return null;
        }
        if (Boolean.TRUE.equals(issue.subIssues().pageInfo().hasNextPage())
                || Boolean.TRUE.equals(issue.blockedBy().pageInfo().hasNextPage())) {
            faults.add(
                    where
                            + " has more sub-issues or blockers than one page carries and is left"
                            + " out; its story is not whole");
            return null;
        }
        final String reason = issue.stateReason();
        return new ItemView(
                item.id(),
                issue.id(),
                repository,
                issue.number(),
                issue.title(),
                Objects.requireNonNullElse(issue.body(), ""),
                "OPEN".equals(issue.state()),
                reason == null ? null : reason.toLowerCase(Locale.ROOT),
                values(item, fieldNames),
                issue.labels(),
                issue.parentId(),
                issue.subIssues().nodes().stream().map(GitHubGraphQl.IssueRef::id).toList(),
                issue.blockedBy().nodes().stream().map(GitHubGraphQl.IssueRef::id).toList(),
                null,
                URI.create(
                        config.forge()
                                + "/"
                                + config.project().owner()
                                + "/"
                                + repository
                                + "/issues/"
                                + issue.number()),
                issue.updatedAt(),
                item.archived());
    }

    /**
     * Answers a repository's short name from GitHub's {@code owner/name}, or null when it names no
     * owner or another than the project's; GitHub compares owners without case.
     */
    private static @Nullable String ownName(final String nameWithOwner, final String owner) {
        final int slash = nameWithOwner.indexOf('/');
        if (slash < 1 || slash != nameWithOwner.lastIndexOf('/')) {
            return null;
        }
        if (!nameWithOwner.substring(0, slash).equalsIgnoreCase(owner)) {
            return null;
        }
        final String name = nameWithOwner.substring(slash + 1);
        try {
            return Ids.name(name);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The item's field values by field name, each as text; a field the project lacks is skipped.
     */
    private static Map<String, String> values(
            final GitHubGraphQl.ProjectItem item, final Map<String, String> fieldNames) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final GitHubGraphQl.ItemValue value : item.values()) {
            final String field = fieldNames.get(value.fieldId());
            final String text = text(value);
            if (field != null && text != null) {
                values.put(field, text);
            }
        }
        return values;
    }

    private static @Nullable String text(final GitHubGraphQl.ItemValue value) {
        final String name = value.name();
        final String text = value.text();
        final Double number = value.number();
        if (value.optionId() != null && name != null) {
            return name;
        }
        if (text != null) {
            return text;
        }
        if (number != null) {
            final double n = number;
            return Double.isFinite(n) && n == Math.rint(n) && Math.abs(n) < 1e15
                    ? Long.toString((long) n)
                    : Double.toString(n);
        }
        return name;
    }

    /**
     * Digests, in a fixed order, every item's node id, change time, open and archived state,
     * parent, sub-issues, blockers and field values, with the commit of the project's files, so a
     * change of any of them, a field's alone included, gives another revision.
     */
    private static Revision revision(
            final String commit,
            final Iterable<GitHubGraphQl.ProjectItem> items,
            final Map<String, String> fieldNames) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
        add(digest, commit);
        final Map<String, GitHubGraphQl.ProjectItem> sorted = new TreeMap<>();
        items.forEach(item -> sorted.put(item.id(), item));
        for (final GitHubGraphQl.ProjectItem item : sorted.values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            add(digest, item.id());
            if (issue == null) {
                add(digest, "no issue");
                continue;
            }
            add(digest, issue.id());
            add(digest, issue.updatedAt().toString());
            add(digest, issue.state());
            add(digest, String.valueOf(item.archived()));
            add(digest, Objects.requireNonNullElse(issue.parentId(), ""));
            add(digest, issue.subIssues());
            add(digest, issue.blockedBy());
            for (final Map.Entry<String, String> value :
                    new TreeMap<>(values(item, fieldNames)).entrySet()) {
                add(digest, value.getKey());
                add(digest, value.getValue());
            }
            add(digest, "end of item");
        }
        return new Revision(HexFormat.of().formatHex(digest.digest()));
    }

    private static void add(final MessageDigest digest, final GitHubGraphQl.IssueRefs refs) {
        add(
                digest,
                refs.nodes().size()
                        + (Boolean.TRUE.equals(refs.pageInfo().hasNextPage()) ? "+" : ""));
        refs.nodes().forEach(ref -> add(digest, ref.id()));
    }

    /** Adds a text with its length before it, so two texts never run into one another. */
    private static void add(final MessageDigest digest, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
    }
}
