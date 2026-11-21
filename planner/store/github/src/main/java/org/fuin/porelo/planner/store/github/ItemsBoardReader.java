package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TokenSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the whole GitHub project through its fields and every page of its items, and maps them to a
 * board. It only reads: nothing it holds can change the person's project.
 */
public final class ItemsBoardReader implements BoardReader {

    /** The place a read tells it reads, named for a person. */
    private static final String FROM = "GitHub";

    private static final Logger LOG = LoggerFactory.getLogger(ItemsBoardReader.class);

    /**
     * How often, a second apart, the item list is read again while it lacks an issue whose own
     * project items name the project: GitHub's list lags an add for a moment, and a person opening
     * the board waits a few seconds at most for it.
     */
    static final int MOST_REREADS = 3;

    /** The pause before each read again of the item list. */
    static final Duration REREAD_PAUSE = Duration.ofSeconds(1);

    /** The most pages of items or fields one read takes; a project beyond them is not read. */
    static final int MOST_PAGES = 50;

    /** The most items a page carries, as the forge client asks for them. */
    static final int PAGE_SIZE = 100;

    /** The smallest page asked for after GitHub found pages too costly. */
    static final int SMALLEST_PAGE = 12;

    /** The page size the last read ended with, where the next one starts. */
    private volatile int lastSize = PAGE_SIZE;

    /** GitHub's words for a query that cost more than it answers. */
    private static final String TOO_COSTLY = "Resource limits for this query exceeded";

    /** Whether a failure, or one it was caused by, is GitHub's refusal of a too costly query. */
    static boolean tooCostly(final Throwable failure) {
        for (Throwable e = failure; e != null; e = e.getCause()) {
            if (e instanceof org.fuin.porelo.forge.github.GitHubException refused
                    && refused.resourceLimited()) {
                return true;
            }
            final String words = e.getMessage();
            if (words != null && words.contains(TOO_COSTLY)) {
                return true;
            }
            if (e.getCause() == e) {
                break;
            }
        }
        return false;
    }

    /** An owner's or a repository's name as GitHub allows it in an address. */
    private static final Pattern REPOSITORY_PART = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    /** How long one read of the whole project may take. */
    static final Duration DEADLINE = Duration.ofSeconds(60);

    /** The tag of a value that is missing; a length-prefixed text begins with a digit. */
    private static final byte NONE = 0;

    /** The tag of a value that is there. */
    private static final byte SOME = 1;

    private final ProjectQueries queries;

    private final TokenSource tokens;

    private final AccessRefusals refusals;

    private final StoryStates states;

    private final InstantSource clock;

    private final Duration deadline;

    private final Pause pause;

    /** How far the whole read under way has come, or null while none runs. */
    private volatile org.fuin.porelo.planner.store.@Nullable ReadProgress progress;

    /** The items the last whole read held, or null before the first. */
    private volatile @Nullable Integer lastTotal;

    /** Waits between two reads of the item list. */
    @FunctionalInterface
    interface Pause {

        /**
         * Waits.
         *
         * @param length how long
         * @throws InterruptedException if the thread is stopped while it waits
         */
        void take(Duration length) throws InterruptedException;
    }

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
        this(queries, tokens, refusals, states, clock, deadline, Thread::sleep);
    }

    /**
     * Makes the reader with its own clock, deadline and pause.
     *
     * @param queries the project's reads
     * @param tokens the person's token, asked for once a read
     * @param refusals turns a failure of a read into the store's refusal
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @param clock tells the time the deadline is measured by
     * @param deadline how long one read may take
     * @param pause waits before the item list is read again
     */
    ItemsBoardReader(
            final ProjectQueries queries,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final StoryStates states,
            final InstantSource clock,
            final Duration deadline,
            final Pause pause) {
        this.queries = queries;
        this.tokens = tokens;
        this.refusals = refusals;
        this.states = states;
        this.clock = clock;
        this.deadline = deadline;
        this.pause = pause;
    }

    @Override
    public java.util.Optional<org.fuin.porelo.planner.store.ReadProgress> progress() {
        return java.util.Optional.ofNullable(progress);
    }

    @Override
    public Snapshot read(final ProjectConfig config) {
        progress =
                new org.fuin.porelo.planner.store.ReadProgress(
                        clock.instant(), 0, lastTotal, null, FROM);
        try {
            final Snapshot read = readWhole(config);
            lastTotal = read.raw().size();
            return read;
        } finally {
            progress = null;
        }
    }

    private Snapshot readWhole(final ProjectConfig config) {
        final String token = tokens.token(config.forge());
        final Call call = new Call(config, token, clock.instant().plus(deadline));
        final List<String> faults = new ArrayList<>();

        final Map<String, String> fieldIds = new LinkedHashMap<>();
        final Map<String, Map<String, String>> optionIds = new LinkedHashMap<>();
        final Map<String, String> fieldNames = new LinkedHashMap<>();
        final Map<String, String> dataTypes = new LinkedHashMap<>();
        @Nullable String after = null;
        String projectId = "";
        for (int page = 1; ; page++) {
            final @Nullable String cursor = after;
            final GitHubGraphQl.ProjectFields fields =
                    call.of(
                            "the project fields",
                            () ->
                                    queries.projectFields(
                                            token,
                                            config.project().owner(),
                                            config.project().number(),
                                            cursor));
            if (fields.fields().size() > PAGE_SIZE) {
                throw new StoreException.Unreadable(
                        config.own(), "GitHub answered a page with more fields than asked for");
            }
            projectId = fields.id();
            for (final GitHubGraphQl.ProjectField field : fields.fields()) {
                fieldIds.put(field.name(), field.id());
                fieldNames.put(field.id(), field.name());
                dataTypes.put(field.name(), field.dataType());
                // A later field of the same name stands for it, so no option of an earlier one
                // is written to it.
                optionIds.remove(field.name());
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
        final ProjectConfig read =
                new ProjectConfig(
                        config.forge(),
                        config.project(),
                        config.commit(),
                        config.workflow(),
                        config.work(),
                        config.repositories(),
                        fieldIds,
                        optionIds,
                        projectId);

        ItemsRead current = items(call, read, fieldNames);
        final Set<String> lagging = onProject(call, read, current.missing());
        int reads = 1;
        while (current.lacksAny(lagging) && reads <= MOST_REREADS) {
            pause(config);
            current = items(call, read, fieldNames);
            reads++;
        }
        if (!lagging.isEmpty()) {
            LOG.info(
                    "GitHub's item list of the project lacked {} sub-issues that name the project;"
                            + " it was read {} times and {}",
                    lagging.size(),
                    reads,
                    current.lacksAny(lagging) ? "still lacked them" : "then held them");
        }
        return snapshot(read, faults, current, fieldNames);
    }

    /**
     * Reads again only the issues a save touched, by their node ids, puts them in place of what the
     * snapshot held, and maps the whole board anew from the items in memory; a snapshot without
     * GitHub's items, or one read with other project files, is read whole.
     */
    @Override
    public Snapshot reread(
            final ProjectConfig config, final Snapshot held, final Touched.Ids touched) {
        final ProjectConfig read = held.config();
        if (held.raw().isEmpty() || !read.commit().equals(config.commit())) {
            return read(config);
        }
        final Set<String> nodes = new LinkedHashSet<>(touched.issues());
        for (final GitHubGraphQl.ProjectItem item : held.raw().values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            if (issue == null) {
                continue;
            }
            final String name = issue.repository();
            final String repository =
                    name.substring(name.indexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (touched.items().contains(item.id())
                    || touched.numbered().contains(repository + "#" + issue.number())
                    || touched.databaseIds().contains(issue.databaseId())) {
                nodes.add(issue.id());
            }
        }
        final String token = tokens.token(config.forge());
        final Call call = new Call(config, token, clock.instant().plus(deadline));
        final Map<String, GitHubGraphQl.ProjectItem> items = new LinkedHashMap<>(held.raw());
        final List<String> all = List.copyOf(nodes);
        for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_ISSUES) {
            final List<String> chunk =
                    all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_ISSUES));
            final GitHubGraphQl.IssueBoard answer =
                    call.of(
                            "the issues a save touched",
                            () -> queries.issueBoard(token, chunk, read.projectId()));
            for (int at = 0; at < chunk.size(); at++) {
                final String issueId = chunk.get(at);
                final GitHubGraphQl.IssueOnBoard on =
                        at < answer.issues().size() ? answer.issues().get(at) : null;
                GitHubGraphQl.@Nullable ProjectItem kept = null;
                for (final GitHubGraphQl.ProjectItem item : items.values()) {
                    final GitHubGraphQl.BoardIssue issue = item.issue();
                    if (issue != null && issue.id().equals(issueId)) {
                        kept = item;
                        break;
                    }
                }
                final String itemId = on == null ? null : on.itemId();
                if (on == null || on.issue() == null || itemId == null) {
                    // No longer an item of the project.
                    if (kept != null) {
                        items.remove(kept.id());
                    }
                    continue;
                }
                final boolean archived = kept != null && kept.archived();
                if (kept != null && !kept.id().equals(itemId)) {
                    items.remove(kept.id());
                }
                items.put(
                        itemId,
                        new GitHubGraphQl.ProjectItem(
                                itemId, archived, on.issue(), on.values(), on.valuesPage()));
            }
        }
        LOG.info("Read {} issues again after a save, in place of the whole project", all.size());
        return snapshot(
                read,
                held.fieldFaults(),
                mapped(items, read, held.fieldNames()),
                held.fieldNames());
    }

    /** Maps the items read to the board and the snapshot that holds it. */
    private Snapshot snapshot(
            final ProjectConfig read,
            final List<String> faults,
            final ItemsRead current,
            final Map<String, String> fieldNames) {
        final List<String> allFaults = new ArrayList<>(faults);
        allFaults.addAll(current.faults());
        final IssueMapping.Mapped mapped = current.mapped();
        final Board board =
                new Board(
                        revision(read.commit(), current.items().values(), fieldNames, allFaults),
                        read.workflow(),
                        mapped.stories(),
                        mapped.issues(),
                        read.work(),
                        read.repositories(),
                        allFaults,
                        read.project().owner());
        final Map<String, ItemView> byId = new LinkedHashMap<>();
        for (final ItemView view : current.views()) {
            final String id = IssueMapping.idOf(view);
            if (id != null) {
                byId.putIfAbsent(id, view);
            }
        }
        return new Snapshot(board, byId, read, current.items(), faults, fieldNames);
    }

    /**
     * One read of the project's item list and what it maps to.
     *
     * @param items the items by their ids, in GitHub's order
     * @param views the items the board maps
     * @param faults the faults of the items and their mapping, in that order
     * @param mapped the stories and issues
     * @param missing the node ids of sub-issues named outside the project that no item of the list
     *     holds
     */
    private record ItemsRead(
            Map<String, GitHubGraphQl.ProjectItem> items,
            List<ItemView> views,
            List<String> faults,
            IssueMapping.Mapped mapped,
            Set<String> missing) {

        /** Whether the list still lacks one of the issues given. */
        boolean lacksAny(final Set<String> issueIds) {
            return issueIds.stream().anyMatch(missing::contains);
        }
    }

    /** Reads every page of the project's items and maps them. */
    private ItemsRead items(
            final Call call, final ProjectConfig read, final Map<String, String> fieldNames) {
        final String token = call.token;
        final Map<String, GitHubGraphQl.ProjectItem> items = new LinkedHashMap<>();
        @Nullable String after = null;
        int size = lastSize;
        for (int page = 1; ; page++) {
            final @Nullable String cursor = after;
            final int first = size;
            final GitHubGraphQl.ProjectItems answer;
            try {
                answer =
                        call.of(
                                "the project items",
                                () ->
                                        first == PAGE_SIZE
                                                ? queries.projectItems(
                                                        token,
                                                        read.project().owner(),
                                                        read.project().number(),
                                                        cursor)
                                                : queries.projectItems(
                                                        token,
                                                        read.project().owner(),
                                                        read.project().number(),
                                                        cursor,
                                                        first));
            } catch (final RuntimeException e) {
                // GitHub refuses a page whose items cost too much to answer; a smaller page of
                // the same items fits, read from the same cursor.
                if (first > SMALLEST_PAGE && tooCostly(e)) {
                    size = Math.max(SMALLEST_PAGE, first / 2);
                    lastSize = size;
                    LOG.info(
                            "GitHub found a page of {} items too costly; it is asked for {}",
                            first,
                            size);
                    page--;
                    continue;
                }
                throw e;
            }
            if (answer.items().size() > first) {
                throw new StoreException.Unreadable(
                        read.own(), "GitHub answered a page with more items than asked for");
            }
            @Nullable String last = null;
            for (final GitHubGraphQl.ProjectItem item : answer.items()) {
                // An item seen again on a later page, as GitHub's order shifts while it is read,
                // counts once.
                items.putIfAbsent(item.id(), item);
                final GitHubGraphQl.BoardIssue issue = item.issue();
                if (issue != null) {
                    final String name = issue.repository();
                    last =
                            name.substring(name.indexOf('/') + 1).toLowerCase(Locale.ROOT)
                                    + "#"
                                    + issue.number();
                }
            }
            final org.fuin.porelo.planner.store.ReadProgress now = progress;
            if (now != null) {
                progress =
                        new org.fuin.porelo.planner.store.ReadProgress(
                                now.since(),
                                items.size(),
                                // GitHub's count of the project's items, told on every page.
                                answer.totalCount(),
                                last != null ? last : now.lastRead(),
                                FROM);
            }
            after = call.next(answer.pageInfo(), cursor, page, "items");
            if (after == null) {
                break;
            }
        }
        // The last answer may come after the deadline as well.
        call.inTime();
        return mapped(items, read, fieldNames);
    }

    /** Maps the project's items, as read or as held, to the board's stories and issues. */
    private ItemsRead mapped(
            final Map<String, GitHubGraphQl.ProjectItem> items,
            final ProjectConfig read,
            final Map<String, String> fieldNames) {
        final List<String> faults = new ArrayList<>();
        final List<ItemView> views = new ArrayList<>();
        final Set<String> overflowing = new HashSet<>();
        final Set<String> listed = new HashSet<>();
        for (final GitHubGraphQl.ProjectItem item : items.values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            if (issue != null) {
                listed.add(issue.id());
            }
            final ItemView view = view(item, read, fieldNames, faults, overflowing);
            if (view != null) {
                views.add(view);
            }
        }
        final IssueMapping.Mapped mapped = IssueMapping.read(views, overflowing, read, states);
        faults.addAll(mapped.faults());
        // A sub-issue the list holds lies in a repository the project does not plan: no lag.
        final Set<String> missing = new LinkedHashSet<>(mapped.outside());
        missing.removeAll(listed);
        return new ItemsRead(items, views, faults, mapped, missing);
    }

    /**
     * Answers those of the issues given whose own project items name the project, in one read of
     * each hundred; none is read when none is given.
     */
    private Set<String> onProject(
            final Call call, final ProjectConfig read, final Set<String> issueIds) {
        final Set<String> onProject = new LinkedHashSet<>();
        final List<String> all = List.copyOf(issueIds);
        for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_ISSUES) {
            final List<String> chunk =
                    all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_ISSUES));
            final GitHubGraphQl.IssueBoard answer =
                    call.of(
                            "the issues named outside the project",
                            () -> queries.issueBoard(call.token, chunk, read.projectId()));
            for (int at = 0; at < chunk.size() && at < answer.issues().size(); at++) {
                final GitHubGraphQl.IssueOnBoard issue = answer.issues().get(at);
                if (issue != null && issue.itemId() != null) {
                    onProject.add(chunk.get(at));
                }
            }
        }
        return onProject;
    }

    /** Waits before the item list is read again; a read stopped while it waits is refused. */
    private void pause(final ProjectConfig config) {
        try {
            pause.take(REREAD_PAUSE);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException.Unreachable(
                    config.own(), "the read was stopped while it waited for GitHub's item list");
        }
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

        /** Refuses the read once its deadline has passed. */
        void inTime() {
            if (clock.instant().isAfter(until)) {
                throw new StoreException.Unreachable(
                        config.own(),
                        "GitHub did not give the whole project within "
                                + deadline.toSeconds()
                                + " s");
            }
        }

        <T> T of(final String what, final Supplier<T> read) {
            inTime();
            try {
                try {
                    return read.get();
                } catch (final GitHubException e) {
                    if (e.rateLimited() || (e.status() != 0 && e.status() < 500)) {
                        throw e;
                    }
                    // A read changes nothing on GitHub, so one that failed on GitHub's side or on
                    // the way is sent once more before it is answered as unreachable.
                    LOG.warn(
                            "GitHub's read of {} failed (HTTP {}, {}); it is sent once more",
                            what,
                            e.status(),
                            e.delivery());
                    pauseBeforeRetry();
                    inTime();
                    return read.get();
                }
            } catch (final StoreException | UnsupportedOperationException e) {
                throw e;
            } catch (final GitHubException e) {
                LOG.warn(
                        "GitHub's read of {} failed (HTTP {}, {}, rate limited {})",
                        what,
                        e.status(),
                        e.delivery(),
                        e.rateLimited());
                if (e.rateLimited()) {
                    throw new StoreException.Unreachable(
                            config.own(), "GitHub's rate limit is spent; " + rereadIn(e.delay()));
                }
                throw refusals.refusal(config.own(), e, token);
            } catch (final RuntimeException e) {
                LOG.warn("The read of {} failed: {}", what, e.getClass().getName());
                throw refusals.refusal(config.own(), e, token);
            }
        }

        /** Waits a second before a read is sent once more; a stopped wait sends it at once. */
        private void pauseBeforeRetry() {
            try {
                pause.take(REREAD_PAUSE);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
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
            if (end == null || end.isBlank() || end.equals(cursor)) {
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
        options.put(
                FieldNames.RISK, Arrays.stream(Risk.values()).map(Risk::spelled).sorted().toList());
        for (final Map.Entry<String, List<String>> field : options.entrySet()) {
            // A field of another kind is named once above; its options would only repeat it.
            if (!FieldNames.Kind.SINGLE_SELECT.name().equals(dataTypes.get(field.getKey()))) {
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
     * Maps one item, or answers null for an item the board leaves out unseen: one without an issue
     * silently, one outside the project's owner with a fault. An item whose sub-issues or blockers
     * run past one page is mapped and named among the overflowing, so the board leaves it out but
     * knows it.
     */
    private static @Nullable ItemView view(
            final GitHubGraphQl.ProjectItem item,
            final ProjectConfig config,
            final Map<String, String> fieldNames,
            final List<String> faults,
            final Set<String> overflowing) {
        final GitHubGraphQl.BoardIssue issue = item.issue();
        if (issue == null) {
            // A draft or a pull request is no planning item.
            return null;
        }
        final String nameWithOwner = issue.repository();
        final int slash = nameWithOwner.indexOf('/');
        // GitHub's words reach a fault only in a form a repository's address can have.
        final boolean shaped =
                slash > 0
                        && REPOSITORY_PART.matcher(nameWithOwner.substring(0, slash)).matches()
                        && REPOSITORY_PART.matcher(nameWithOwner.substring(slash + 1)).matches();
        final String where = shaped ? "Item " + nameWithOwner + "#" + issue.number() : "An item";
        if (!shaped
                || !nameWithOwner.substring(0, slash).equalsIgnoreCase(config.project().owner())) {
            faults.add(where + " lies outside the project's owner and is left out");
            return null;
        }
        final String repository;
        try {
            // GitHub keeps a repository's name in the case it was made with; an id takes it
            // lowercased.
            repository = Ids.name(nameWithOwner.substring(slash + 1).toLowerCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            faults.add(where + " lies outside the project's repositories and is left out");
            return null;
        }
        if (Boolean.TRUE.equals(issue.subIssues().pageInfo().hasNextPage())
                || Boolean.TRUE.equals(issue.blockedBy().pageInfo().hasNextPage())) {
            faults.add(
                    where
                            + " has more sub-issues or blockers than one page carries and is left"
                            + " out; its story is not whole");
            overflowing.add(item.id());
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
     * The item's field values by field name, each as text; a field the project lacks is skipped.
     */
    private static Map<String, String> values(
            final GitHubGraphQl.ProjectItem item, final Map<String, String> fieldNames) {
        return values(item.values(), fieldNames);
    }

    /**
     * Field values by field name, each as text as the board reads it; a field not named is skipped.
     *
     * @param given the values as GitHub gives them
     * @param fieldNames the fields' names by their ids
     * @return the values
     */
    static Map<String, String> values(
            final List<GitHubGraphQl.ItemValue> given, final Map<String, String> fieldNames) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final GitHubGraphQl.ItemValue value : given) {
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
     * Digests, in a fixed order, the commit of the project's files, the board's faults, and every
     * item's node id, issue, repository, number, title, body, labels, change time, open and
     * archived state and its reason, parent, sub-issues, blockers and field values, so a change of
     * any of them, a field's alone or a fault's included, gives another revision.
     */
    private static Revision revision(
            final String commit,
            final Iterable<GitHubGraphQl.ProjectItem> items,
            final Map<String, String> fieldNames,
            final List<String> faults) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
        add(digest, commit);
        // The faults follow GitHub's order of the items, which may shift between two reads.
        add(digest, faults.stream().sorted().toList());
        final Map<String, GitHubGraphQl.ProjectItem> sorted = new TreeMap<>();
        items.forEach(item -> sorted.put(item.id(), item));
        add(digest, Integer.toString(sorted.size()));
        for (final GitHubGraphQl.ProjectItem item : sorted.values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            add(digest, item.id());
            if (issue == null) {
                // A tag no length-prefixed text begins with, so no id stands for it.
                digest.update(NONE);
                continue;
            }
            digest.update(SOME);
            add(digest, issue.id());
            add(digest, issue.repository());
            add(digest, Integer.toString(issue.number()));
            add(digest, issue.title());
            addNullable(digest, issue.body());
            add(digest, issue.labels());
            add(digest, issue.updatedAt().toString());
            add(digest, issue.state());
            addNullable(digest, issue.stateReason());
            add(digest, String.valueOf(item.archived()));
            addNullable(digest, issue.parentId());
            add(digest, issue.subIssues());
            add(digest, issue.blockedBy());
            final Map<String, String> values = new TreeMap<>(values(item, fieldNames));
            add(digest, Integer.toString(values.size()));
            for (final Map.Entry<String, String> value : values.entrySet()) {
                add(digest, value.getKey());
                add(digest, value.getValue());
            }
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

    /** Adds a list of texts with their count before them. */
    private static void add(final MessageDigest digest, final List<String> texts) {
        add(digest, Integer.toString(texts.size()));
        texts.forEach(text -> add(digest, text));
    }

    /** Adds a text that may be missing, told apart from an empty one by a tag. */
    private static void addNullable(final MessageDigest digest, final @Nullable String text) {
        if (text == null) {
            digest.update(NONE);
        } else {
            digest.update(SOME);
            add(digest, text);
        }
    }

    /** Adds a text with its length before it, so two texts never run into one another. */
    private static void add(final MessageDigest digest, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
    }
}
