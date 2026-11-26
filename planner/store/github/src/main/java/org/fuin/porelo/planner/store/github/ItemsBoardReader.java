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
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.OverlaidItem;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.fuin.porelo.planner.store.cache.TrackedWait;
import org.fuin.porelo.planner.store.cache.TrackerBoard;
import org.fuin.porelo.planner.store.files.Seconds;
import org.fuin.porelo.wire.LimitFactors;
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
     * the board waits a few seconds at most for it. Before the factor {@code retries}.
     */
    static final int MOST_REREADS = 3;

    /**
     * The pause before each read again of the item list, and before a failed read is sent again,
     * before the factor {@code retries}.
     */
    static final Duration REREAD_PAUSE = Duration.ofSeconds(1);

    /** How often a read that failed on GitHub's side or on the way is sent, before its factor. */
    static final int READ_TRIES = 2;

    /**
     * The longest wait for GitHub's rate limit a whole read takes, before the factor {@code
     * retryPause}; a longer one fails the read with the time GitHub named.
     */
    static final Duration RATE_WAIT_MOST = Duration.ofSeconds(60);

    /** The most time a whole read spends waiting for GitHub's rate limit, before its factor. */
    static final Duration RATE_WAITS_MOST = Duration.ofMinutes(2);

    /** The most items a page carries, as the forge client asks for them. */
    static final int PAGE_SIZE = 100;

    /** The smallest page asked for after GitHub found pages too costly. */
    static final int SMALLEST_PAGE = 12;

    // Small nested lists reduce page cost; 30 values leave room for built-in and planner fields.
    private static final GitHubGraphQl.ItemLimits ITEM_LIMITS =
            new GitHubGraphQl.ItemLimits(10, 20, 10, 30);

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

    /**
     * How long GitHub may leave a read of the project without an answer; a read whose answers keep
     * coming takes as long as the project needs.
     */
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

    private final Duration rateWaitMost;

    private final Duration rateWaitsMost;

    /** How long the whole read under way has waited for GitHub's rate limit, over all its calls. */
    private volatile Duration waitedForRate = Duration.ZERO;

    /** How often the item list is read again while it lacks an issue that names the project. */
    private final int mostRereads;

    /** The pause before the item list is read again, or a failed read is sent again. */
    private final Duration rereadPause;

    /** How often a read that failed on GitHub's side or on the way is sent in all. */
    private final int readTries;

    /** The pages of each repository's open issues, or null when they are not read. */
    private final RepositoryIssues.@Nullable Pages issuePages;

    /** Each repository's open issues as last read, with the tags of their pages. */
    private final RepositoryIssues unlisted;

    /** How far the whole read under way has come, or null while none runs. */
    private volatile org.fuin.porelo.planner.store.@Nullable ReadProgress progress;

    /** The items the last whole read held, or null before the first. */
    private volatile @Nullable Integer lastTotal;

    /** The organization's issue fields as GitHub answers them. */
    @FunctionalInterface
    public interface IssueFields {

        /**
         * Reads the issue fields of an organization.
         *
         * @param token the person's token
         * @param owner the project's owner
         * @return the fields
         */
        List<IssueField> read(String token, String owner);
    }

    /** The organization's issue fields, or null when they are not read. */
    private final @Nullable IssueFields issueFields;

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
     * Takes the wait of a rate limit a read failed on, so every look of the person's token holds
     * until it ends; a rate limit a read waits out and goes on from is no failure, and holds
     * nothing.
     */
    @FunctionalInterface
    public interface RateHolds {

        /** Holds nothing. */
        RateHolds NONE = wait -> {};

        /**
         * Holds every look until the wait ends.
         *
         * @param wait the wait GitHub named, or null when it named none
         */
        void spent(@Nullable Duration wait);
    }

    /** Takes the wait of a rate limit a read failed on. */
    private final RateHolds holds;

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
     * Makes the reader that also reads the open issues of the project's repositories, so the board
     * names those that are no items of the project.
     *
     * @param queries the project's two reads
     * @param issuePages the pages of a repository's open issues
     * @param tokens the person's token, asked for once a read
     * @param refusals turns a failure of a read into the store's refusal
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @param silence how long GitHub may leave the project's read without an answer
     * @param limits the factors the lists' deadline, the reads again and their pauses follow
     */
    public ItemsBoardReader(
            final ProjectQueries queries,
            final RepositoryIssues.Pages issuePages,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final StoryStates states,
            final Duration silence,
            final LimitFactors limits) {
        this(
                queries,
                tokens,
                refusals,
                states,
                InstantSource.system(),
                silence,
                Thread::sleep,
                issuePages,
                limits);
    }

    /**
     * Makes the reader that also reads the open issues of the project's repositories and the
     * organization's issue fields, so the board names the issues that are no items of the project
     * and offers to set a story's priority to one the organization's field Priority holds.
     *
     * @param queries the project's two reads
     * @param issuePages the pages of a repository's open issues
     * @param issueFields the organization's issue fields
     * @param tokens the person's token, asked for once a read
     * @param refusals turns a failure of a read into the store's refusal
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @param silence how long GitHub may leave the project's read without an answer
     * @param limits the factors the lists' deadline, the reads again and their pauses follow
     * @param holds takes the wait of a rate limit a read failed on
     */
    public ItemsBoardReader(
            final ProjectQueries queries,
            final RepositoryIssues.Pages issuePages,
            final IssueFields issueFields,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final StoryStates states,
            final Duration silence,
            final LimitFactors limits,
            final RateHolds holds) {
        this(
                queries,
                tokens,
                refusals,
                states,
                InstantSource.system(),
                silence,
                Thread::sleep,
                issuePages,
                limits,
                issueFields,
                holds);
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
        this(queries, tokens, refusals, states, clock, deadline, pause, null);
    }

    /**
     * Makes the reader with its own clock, deadline and pause, reading the open issues of the
     * project's repositories when it is given their pages.
     *
     * @param queries the project's reads
     * @param tokens the person's token, asked for once a read
     * @param refusals turns a failure of a read into the store's refusal
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @param clock tells the time the deadlines are measured by
     * @param deadline how long one read of the project may take
     * @param pause waits before the item list is read again
     * @param issuePages the pages of a repository's open issues, or null when none are read
     */
    ItemsBoardReader(
            final ProjectQueries queries,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final StoryStates states,
            final InstantSource clock,
            final Duration deadline,
            final Pause pause,
            final RepositoryIssues.@Nullable Pages issuePages) {
        this(
                queries,
                tokens,
                refusals,
                states,
                clock,
                deadline,
                pause,
                issuePages,
                LimitFactors.DEFAULT);
    }

    /**
     * Makes the reader with its own clock, deadline and pause, reading the open issues of the
     * project's repositories when it is given their pages, its other limits following the factors.
     *
     * @param queries the project's reads
     * @param tokens the person's token, asked for once a read
     * @param refusals turns a failure of a read into the store's refusal
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @param clock tells the time the deadlines are measured by
     * @param deadline how long GitHub may leave the project's read without an answer
     * @param pause waits before the item list is read again
     * @param issuePages the pages of a repository's open issues, or null when none are read
     * @param limits the factors the lists' deadline, the reads again and their pauses follow
     */
    ItemsBoardReader(
            final ProjectQueries queries,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final StoryStates states,
            final InstantSource clock,
            final Duration deadline,
            final Pause pause,
            final RepositoryIssues.@Nullable Pages issuePages,
            final LimitFactors limits) {
        this(
                queries,
                tokens,
                refusals,
                states,
                clock,
                deadline,
                pause,
                issuePages,
                limits,
                null,
                RateHolds.NONE);
    }

    /** Makes the reader as the next one does, holding no other look at a rate limit it fails on. */
    ItemsBoardReader(
            final ProjectQueries queries,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final StoryStates states,
            final InstantSource clock,
            final Duration deadline,
            final Pause pause,
            final RepositoryIssues.@Nullable Pages issuePages,
            final LimitFactors limits,
            final @Nullable IssueFields issueFields) {
        this(
                queries,
                tokens,
                refusals,
                states,
                clock,
                deadline,
                pause,
                issuePages,
                limits,
                issueFields,
                RateHolds.NONE);
    }

    /**
     * Makes the reader with its own clock, deadline and pause, reading the open issues of the
     * project's repositories when it is given their pages and the organization's issue fields when
     * it is given them, its other limits following the factors.
     *
     * @param queries the project's reads
     * @param tokens the person's token, asked for once a read
     * @param refusals turns a failure of a read into the store's refusal
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @param clock tells the time the deadlines are measured by
     * @param deadline how long GitHub may leave the project's read without an answer
     * @param pause waits before the item list is read again
     * @param issuePages the pages of a repository's open issues, or null when none are read
     * @param limits the factors the lists' deadline, the reads again and their pauses follow
     * @param issueFields the organization's issue fields, or null when they are not read, so no
     *     story's priority is offered to set
     * @param holds takes the wait of a rate limit a read failed on
     */
    ItemsBoardReader(
            final ProjectQueries queries,
            final TokenSource tokens,
            final AccessRefusals refusals,
            final StoryStates states,
            final InstantSource clock,
            final Duration deadline,
            final Pause pause,
            final RepositoryIssues.@Nullable Pages issuePages,
            final LimitFactors limits,
            final @Nullable IssueFields issueFields,
            final RateHolds holds) {
        this.holds = holds;
        this.issueFields = issueFields;
        this.issuePages = issuePages;
        this.unlisted = new RepositoryIssues(limits.services(RepositoryIssues.DEADLINE));
        this.mostRereads = limits.tries(MOST_REREADS);
        this.rereadPause = limits.retryPause(REREAD_PAUSE);
        this.readTries = limits.tries(READ_TRIES);
        this.rateWaitMost = limits.retryPause(RATE_WAIT_MOST);
        this.rateWaitsMost = limits.retryPause(RATE_WAITS_MOST);
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
        return read(config, false);
    }

    @Override
    public Snapshot read(final ProjectConfig config, final boolean rateWaits) {
        // Only one read that may wait runs at a time: the store's whole read beside the saves.
        waitedForRate = Duration.ZERO;
        progress =
                new org.fuin.porelo.planner.store.ReadProgress(
                        clock.instant(), 0, lastTotal, null, FROM);
        try {
            final Snapshot read = readWhole(config, rateWaits);
            lastTotal = read.raw().size();
            return read;
        } finally {
            progress = null;
        }
    }

    private Snapshot readWhole(final ProjectConfig config, final boolean rateWaits) {
        final String token = tokens.token(config.forge());
        final List<String> faults = new ArrayList<>();
        // Read before the project's items, so an issue added to the project meanwhile is an item
        // of this read and never shown as outside it; its own silence limit leaves the project's
        // whole.
        final List<Snapshot.Unlisted> repositoryIssues =
                readUnlisted(config, token, faults, rateWaits);
        final Call call = new Call(config, token, clock.instant().plus(deadline), false, rateWaits);

        final Map<String, String> fieldIds = new LinkedHashMap<>();
        final Map<String, Map<String, String>> optionIds = new LinkedHashMap<>();
        final Map<String, String> fieldNames = new LinkedHashMap<>();
        final Map<String, String> dataTypes = new LinkedHashMap<>();
        final Map<String, Long> restFieldIds = new LinkedHashMap<>();
        @Nullable String after = null;
        final Set<String> fieldCursors = new HashSet<>();
        String projectId = "";
        while (true) {
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
                restFieldIds.put(field.id(), field.databaseId());
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
            after = call.next(fields.pageInfo(), fields.fields().size(), fieldCursors, "fields");
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
                        projectId,
                        priorities(call, config, faults));

        ItemsRead current = items(call, read, fieldNames);
        final Set<String> lagging = onProject(call, read, current.missing());
        int reads = 1;
        while (current.lacksAny(lagging) && reads <= mostRereads) {
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
        final Map<String, Snapshot.Waited> waits =
                readWaits(call, read, offBoard(current.items(), read));
        return snapshot(
                read,
                faults,
                mapped(current.items(), read, fieldNames, waits),
                fieldNames,
                restFieldIds,
                repositoryIssues);
    }

    /**
     * Reads the organization's issue fields within the project's read, answering the priorities its
     * field Priority holds. An owner GitHub knows no issue fields of, a user among them, holds none
     * and is no fault; a read that fails adds its fault in fixed words and holds none, so no
     * story's priority is offered to set.
     */
    private List<String> priorities(
            final Call call, final ProjectConfig config, final List<String> faults) {
        final IssueFields fields = issueFields;
        if (fields == null) {
            return List.of();
        }
        final String owner = config.project().owner();
        try {
            return IssuePriority.offered(
                    call.of(
                            "the organization's issue fields",
                            () -> fields.read(call.token, owner),
                            true));
        } catch (final GitHubException e) {
            if (e.status() == NOT_FOUND) {
                return List.of();
            }
            faults.add(IssuePriority.unread("GitHub answered HTTP " + e.status()));
            return List.of();
        } catch (final StoreException e) {
            faults.add(
                    IssuePriority.unread(
                            e instanceof StoreException.Unreachable
                                    ? "GitHub could not be reached in time"
                                    : "GitHub refused the read"));
            return List.of();
        }
    }

    /** GitHub's answer for an owner without issue fields, a user's account among them. */
    private static final int NOT_FOUND = 404;

    /**
     * Reads the open issues of the project's repositories within their own silence limit, adding to
     * the faults each repository whose list could not be read; a reader without their pages reads
     * none.
     */
    private List<Snapshot.Unlisted> readUnlisted(
            final ProjectConfig config,
            final String token,
            final List<String> faults,
            final boolean rateWaits) {
        final RepositoryIssues.Pages pages = issuePages;
        if (pages == null) {
            return List.of();
        }
        // The lists' own deadline: a read sent again never runs into the project's.
        final Call call =
                new Call(config, token, clock.instant().plus(unlisted.deadline()), true, rateWaits);
        final String owner = config.project().owner();
        final RepositoryIssues.Read read =
                unlisted.read(
                        config.names(),
                        (repository, page, etag) ->
                                call.of(
                                        "the open issues of " + repository,
                                        () -> pages.page(token, owner, repository, page, etag),
                                        true),
                        clock,
                        token);
        LOG.info(
                "Read the open issues of {} repositories in {} requests, {} of them answered"
                        + " unchanged",
                config.repositories().size(),
                read.requests(),
                read.unchanged());
        faults.addAll(read.faults());
        return read.issues();
    }

    /**
     * Reads again only the issues a save touched, by their node ids, puts them in place of what the
     * snapshot held, and maps the whole board anew from the items in memory; a snapshot without
     * GitHub's items, or one read with other project files, is read whole.
     */
    @Override
    public Snapshot reread(
            final ProjectConfig config, final Snapshot held, final Touched.Ids touched) {
        return reread(config, held, touched, Map.of());
    }

    /**
     * Reads again only the issues named, as {@link #reread(ProjectConfig, Snapshot, Touched.Ids)}
     * does, each item archived as the look's list showed it and otherwise as the snapshot held it.
     */
    @Override
    public Snapshot reread(
            final ProjectConfig config,
            final Snapshot held,
            final Touched.Ids touched,
            final Map<String, Boolean> archivedNow) {
        return apply(held, fetch(config, held, touched, archivedNow));
    }

    /**
     * Reads the issues named from GitHub, and the blockers they name that the board held lacks,
     * without laying them onto a board; a snapshot not read from GitHub's project, or one read with
     * other project files, is read whole. A project without items is read by id as any other, so an
     * issue made in it is held over a list that lags it.
     */
    @Override
    public Reread fetch(
            final ProjectConfig config,
            final Snapshot held,
            final Touched.Ids touched,
            final Map<String, Boolean> archivedNow) {
        final ProjectConfig read = held.config();
        if (read.projectId().isEmpty() || !read.commit().equals(config.commit())) {
            return Reread.whole(read(config));
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
        final Map<String, GitHubGraphQl.ProjectItem> put = new LinkedHashMap<>();
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
                // A read by id does not tell whether the item is archived: the look's list does.
                final Boolean listed = archivedNow.get(itemId);
                final boolean archived = listed != null ? listed : kept != null && kept.archived();
                if (kept != null && !kept.id().equals(itemId)) {
                    items.remove(kept.id());
                }
                final GitHubGraphQl.ProjectItem fresh =
                        new GitHubGraphQl.ProjectItem(
                                itemId,
                                archived,
                                on.issue(),
                                on.values(),
                                on.valuesPage(),
                                on.valueCount());
                items.put(itemId, fresh);
                put.put(itemId, fresh);
            }
        }
        LOG.info("Read {} issues again after a save, in place of the whole project", all.size());
        final Map<String, GitHubGraphQl.IssueRef> blockers = offBoard(items, read);
        final Map<String, Snapshot.Waited> waits = new LinkedHashMap<>();
        final Map<String, GitHubGraphQl.IssueRef> unread = new LinkedHashMap<>();
        final Set<String> named = new HashSet<>();
        for (final GitHubGraphQl.ProjectItem item : items.values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            if (issue != null && nodes.contains(issue.id())) {
                issue.blockedBy().nodes().forEach(ref -> named.add(ref.id()));
            }
        }
        for (final Map.Entry<String, GitHubGraphQl.IssueRef> blocker : blockers.entrySet()) {
            final Snapshot.Waited kept = held.waits().get(blocker.getKey());
            // Only the touched issues' blockers are read again; one first seen is read too.
            if (kept == null || named.contains(blocker.getKey())) {
                unread.put(blocker.getKey(), blocker.getValue());
            } else {
                waits.put(blocker.getKey(), kept);
            }
        }
        return new Reread(read, null, put, nodes, archivedNow, readWaits(call, read, unread));
    }

    /**
     * Lays the issues a fetch read onto the newest board held: each item of an issue read is
     * replaced, or gone when GitHub no longer lists it on the project, matched by its issue as well
     * as its item; an item keeps the archived flag the newest board gives it unless a look's list
     * named it; the board is mapped anew from every item, so a story's state never lags its issues.
     */
    @Override
    public Snapshot apply(final Snapshot newest, final Reread delta) {
        final Snapshot whole = delta.whole();
        if (whole != null) {
            return whole;
        }
        // A delta lays items and waits only: the board keeps the settings it was read with.
        final ProjectConfig read = newest.config();
        final Map<String, GitHubGraphQl.ProjectItem> items = new LinkedHashMap<>(newest.raw());
        items.values()
                .removeIf(
                        item -> {
                            final GitHubGraphQl.BoardIssue issue = item.issue();
                            return issue != null
                                    && delta.issues().contains(issue.id())
                                    && !delta.put().containsKey(item.id());
                        });
        for (final GitHubGraphQl.ProjectItem fresh : delta.put().values()) {
            final GitHubGraphQl.ProjectItem kept = newest.raw().get(fresh.id());
            final Boolean listed = delta.archived().get(fresh.id());
            final boolean archived =
                    listed != null ? listed : kept != null ? kept.archived() : fresh.archived();
            items.put(
                    fresh.id(),
                    new GitHubGraphQl.ProjectItem(
                            fresh.id(),
                            archived,
                            fresh.issue(),
                            fresh.values(),
                            fresh.valuesPage(),
                            fresh.valueCount()));
        }
        final Map<String, Snapshot.Waited> waits = new LinkedHashMap<>(newest.waits());
        waits.putAll(delta.waits());
        return snapshot(
                read,
                newest.fieldFaults(),
                mapped(items, read, newest.fieldNames(), ordered(offBoard(items, read), waits)),
                newest.fieldNames(),
                newest.restFieldIds(),
                newest.unlisted());
    }

    @Override
    public @Nullable Reread fetchWaits(final ProjectConfig config, final Snapshot held) {
        final ProjectConfig read = held.config();
        if (held.raw().isEmpty() || !read.commit().equals(config.commit())) {
            return Reread.whole(read(config));
        }
        final Call call =
                new Call(config, tokens.token(config.forge()), clock.instant().plus(deadline));
        final Map<String, Snapshot.Waited> waits =
                readWaits(call, read, offBoard(held.raw(), read));
        if (waits.equals(held.waits())) {
            return null;
        }
        LOG.info("Read again the state of {} issues the board's issues wait on", waits.size());
        return new Reread(read, null, Map.of(), Set.of(), Map.of(), waits);
    }

    @Override
    public Snapshot rewait(final ProjectConfig config, final Snapshot held) {
        final ProjectConfig read = held.config();
        if (held.raw().isEmpty() || !read.commit().equals(config.commit())) {
            return read(config);
        }
        final Call call =
                new Call(config, tokens.token(config.forge()), clock.instant().plus(deadline));
        final Map<String, GitHubGraphQl.ProjectItem> items = new LinkedHashMap<>(held.raw());
        final Map<String, GitHubGraphQl.IssueRef> blockers = offBoard(items, read);
        final Map<String, Snapshot.Waited> waits = readWaits(call, read, blockers);
        if (waits.equals(held.waits())) {
            return held;
        }
        LOG.info("Read again the state of {} issues the board's issues wait on", waits.size());
        return snapshot(
                read,
                held.fieldFaults(),
                mapped(items, read, held.fieldNames(), waits),
                held.fieldNames(),
                held.restFieldIds(),
                held.unlisted());
    }

    @Override
    public StartRead waitsOf(
            final ProjectConfig config, final Snapshot held, final Set<String> issueIds) {
        final Map<String, GitHubGraphQl.IssueRef> blockers = offBoard(held.raw(), held.config());
        final Map<String, GitHubGraphQl.IssueRef> wanted = new LinkedHashMap<>();
        final Set<String> onBoard = new HashSet<>();
        for (final ItemView item : held.items().values()) {
            onBoard.add(item.issueId());
            if (issueIds.contains(item.issueId())) {
                for (final String blocker : item.blockedByIds()) {
                    final GitHubGraphQl.IssueRef ref = blockers.get(blocker);
                    if (ref != null) {
                        wanted.put(blocker, ref);
                    }
                }
            }
        }
        final List<String> asked = new ArrayList<>(new java.util.TreeSet<>(issueIds));
        wanted.keySet().stream().filter(id -> !issueIds.contains(id)).forEach(asked::add);
        if (asked.isEmpty()) {
            return new StartRead(Map.of(), Set.of(), Map.of());
        }
        final Call call =
                new Call(config, tokens.token(config.forge()), clock.instant().plus(deadline));
        final ProjectConfig read = held.config();
        final Map<String, List<GitHubGraphQl.IssueRef>> named = new LinkedHashMap<>();
        final Set<String> unread = new HashSet<>();
        final Map<String, Snapshot.Waited> waits = new LinkedHashMap<>();
        // The issues and their known waits in one call, a hundred to a call.
        for (int from = 0; from < asked.size(); from += GitHubGraphQl.MAX_ISSUES) {
            final List<String> chunk =
                    asked.subList(from, Math.min(asked.size(), from + GitHubGraphQl.MAX_ISSUES));
            final GitHubGraphQl.IssueBoard answer =
                    call.of(
                            "the issues a start offers and what they wait on",
                            () -> queries.issueBoard(call.token, chunk, read.projectId()));
            for (int at = 0; at < chunk.size(); at++) {
                final String id = chunk.get(at);
                final GitHubGraphQl.IssueOnBoard on =
                        at < answer.issues().size() ? answer.issues().get(at) : null;
                final GitHubGraphQl.BoardIssue answered = on == null ? null : on.issue();
                final GitHubGraphQl.BoardIssue issue =
                        answered != null && answered.id().equals(id) ? answered : null;
                if (issueIds.contains(id)) {
                    if (issue != null && !issue.blockedBy().pageInfo().hasNextPage()) {
                        named.put(id, issue.blockedBy().nodes());
                    }
                    // A blocker GitHub counts but does not name holds the start as one not read.
                    if (issue == null
                            || issue.blockedBy().pageInfo().hasNextPage()
                            || unseen(issue.blockedBy()) > 0) {
                        unread.add(id);
                    }
                }
                final GitHubGraphQl.IssueRef ref = wanted.get(id);
                if (ref != null) {
                    waits.put(
                            id,
                            issue != null
                                    ? new Snapshot.Waited(
                                            issue.repository(), issue.number(), done(issue.state()))
                                    : new Snapshot.Waited(ref.repository(), ref.number(), null));
                }
            }
        }
        // A blocker added since the board was read is not known done until a look reads it.
        for (final List<GitHubGraphQl.IssueRef> refs : named.values()) {
            for (final GitHubGraphQl.IssueRef ref : refs) {
                if (!onBoard.contains(ref.id()) && !waits.containsKey(ref.id())) {
                    waits.put(ref.id(), new Snapshot.Waited(ref.repository(), ref.number(), null));
                }
            }
        }
        return new StartRead(named, unread, waits);
    }

    /**
     * The blockers of the items' issues that are no issues of items in the project's repositories,
     * by their node ids, each once in the order the items name them.
     */
    static Map<String, GitHubGraphQl.IssueRef> offBoard(
            final Map<String, GitHubGraphQl.ProjectItem> items, final ProjectConfig config) {
        final Set<String> onBoard = new HashSet<>();
        for (final GitHubGraphQl.ProjectItem item : items.values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            if (issue != null && planned(issue.repository(), config)) {
                onBoard.add(issue.id());
            }
        }
        final Map<String, GitHubGraphQl.IssueRef> blockers = new LinkedHashMap<>();
        for (final GitHubGraphQl.ProjectItem item : items.values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            if (issue == null) {
                continue;
            }
            for (final GitHubGraphQl.IssueRef ref : issue.blockedBy().nodes()) {
                if (!onBoard.contains(ref.id())) {
                    blockers.putIfAbsent(ref.id(), ref);
                }
            }
        }
        return blockers;
    }

    /** Whether GitHub's name of a repository is one of the project's, under its owner. */
    private static boolean planned(final String nameWithOwner, final ProjectConfig config) {
        final int slash = nameWithOwner.indexOf('/');
        return slash > 0
                && nameWithOwner.substring(0, slash).equalsIgnoreCase(config.project().owner())
                && config.names()
                        .contains(nameWithOwner.substring(slash + 1).toLowerCase(Locale.ROOT));
    }

    /** The waits in the order the blockers are named. */
    private static Map<String, Snapshot.Waited> ordered(
            final Map<String, GitHubGraphQl.IssueRef> blockers,
            final Map<String, Snapshot.Waited> waits) {
        final Map<String, Snapshot.Waited> ordered = new LinkedHashMap<>();
        for (final String id : blockers.keySet()) {
            final Snapshot.Waited wait = waits.get(id);
            if (wait != null) {
                ordered.put(id, wait);
            }
        }
        return ordered;
    }

    /**
     * Reads the state of the blockers given, in one read of each hundred; one GitHub answers
     * nothing for, as for an issue the person's token cannot read, is held with no state, so it
     * holds its issue back.
     */
    /** Done only when GitHub says closed; open, none or a state it names anew is not known done. */
    private static @Nullable Boolean done(final @Nullable String state) {
        if ("CLOSED".equals(state)) {
            return Boolean.TRUE;
        }
        return "OPEN".equals(state) ? Boolean.FALSE : null;
    }

    private Map<String, Snapshot.Waited> readWaits(
            final Call call,
            final ProjectConfig read,
            final Map<String, GitHubGraphQl.IssueRef> blockers) {
        final Map<String, Snapshot.Waited> waits = new LinkedHashMap<>();
        final List<String> all = List.copyOf(blockers.keySet());
        for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_ISSUES) {
            final List<String> chunk =
                    all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_ISSUES));
            final GitHubGraphQl.IssueBoard answer =
                    call.of(
                            "the issues the board's issues wait on",
                            () -> queries.issueBoard(call.token, chunk, read.projectId()));
            for (int at = 0; at < chunk.size(); at++) {
                final GitHubGraphQl.IssueRef ref =
                        Objects.requireNonNull(blockers.get(chunk.get(at)));
                final GitHubGraphQl.IssueOnBoard on =
                        at < answer.issues().size() ? answer.issues().get(at) : null;
                final GitHubGraphQl.BoardIssue issue = on == null ? null : on.issue();
                waits.put(
                        chunk.get(at),
                        issue == null || !issue.id().equals(chunk.get(at))
                                ? new Snapshot.Waited(ref.repository(), ref.number(), null)
                                : new Snapshot.Waited(
                                        issue.repository(), issue.number(), done(issue.state())));
            }
        }
        return waits;
    }

    /**
     * Answers the board's copy, taken now; see {@link BoardCopyMapping#toCopy}.
     *
     * @param held the snapshot
     * @param cursor where the tracker's look for changes stands
     * @param heldItems the items held over GitHub's last whole list until it shows them
     * @return the copy, or null for a snapshot not read from GitHub's item list
     */
    @Override
    public @Nullable BoardCopy toCopy(
            final Snapshot held, final ChangeCursor cursor, final List<OverlaidItem> heldItems) {
        return toCopy(held, cursor, heldItems, Map.of());
    }

    /** Answers the board's copy, each item with its time as the list last showed it. */
    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public @Nullable BoardCopy toCopy(
            final Snapshot held,
            final ChangeCursor cursor,
            final List<OverlaidItem> heldItems,
            final Map<String, Instant> listedAt) {
        if (held.config().projectId().isEmpty()) {
            // Not read from GitHub: its board could not be mapped again from its items.
            return null;
        }
        return BoardCopyMapping.toCopy(held, cursor, heldItems, listedAt, clock.instant());
    }

    /**
     * Maps the board anew from a copy's items, as a read of them from GitHub maps it, so the board
     * and its revision are the ones the copy was taken of.
     *
     * @param copy the copy
     * @param config what the project is read with, as its files name it now
     * @return the snapshot, or null when the copy is of another project, other project files or
     *     other repositories, or holds a priority none of the planner's
     */
    @Override
    public @Nullable Snapshot fromCopy(final BoardCopy copy, final ProjectConfig config) {
        if (!copy.project().equals(BoardCopyMapping.project(config))
                || !copy.filesCommit().equals(config.commit())
                || !copy.repositories().equals(config.names())) {
            return null;
        }
        final TrackerBoard board = copy.board();
        if (!Story.PRIORITIES.containsAll(board.priorities())) {
            return null;
        }
        final ProjectConfig read =
                new ProjectConfig(
                        config.forge(),
                        config.project(),
                        config.commit(),
                        config.workflow(),
                        config.work(),
                        config.repositories(),
                        board.fieldIds(),
                        board.optionIds(),
                        board.id(),
                        board.priorities());
        final Map<String, GitHubGraphQl.ProjectItem> items = new LinkedHashMap<>();
        for (final TrackedItem item : copy.items()) {
            items.putIfAbsent(item.id(), BoardCopyMapping.item(item));
        }
        final Map<String, Snapshot.Waited> waits = new LinkedHashMap<>();
        for (final TrackedWait wait : copy.waits()) {
            waits.put(
                    wait.id(),
                    new Snapshot.Waited(wait.repository(), wait.number(), wait.closed()));
        }
        final List<Snapshot.Unlisted> repositoryIssues =
                copy.unlisted().stream()
                        .map(
                                issue ->
                                        new Snapshot.Unlisted(
                                                issue.id(),
                                                issue.repository(),
                                                issue.number(),
                                                issue.title(),
                                                issue.type(),
                                                issue.author(),
                                                issue.authorAssociation()))
                        .toList();
        // A repository the next read cannot list keeps what the copy held of it.
        unlisted.keep(repositoryIssues);
        return snapshot(
                read,
                board.faults(),
                mapped(items, read, board.fieldNames(), waits),
                board.fieldNames(),
                board.restFieldIds(),
                repositoryIssues);
    }

    /** Maps the items read to the board and the snapshot that holds it. */
    private Snapshot snapshot(
            final ProjectConfig read,
            final List<String> faults,
            final ItemsRead current,
            final Map<String, String> fieldNames,
            final Map<String, Long> restFieldIds,
            final List<Snapshot.Unlisted> repositoryIssues) {
        final List<String> allFaults = new ArrayList<>(faults);
        allFaults.addAll(current.faults());
        final IssueMapping.Mapped mapped = current.mapped();
        final List<UnlistedIssue> unlistedIssues =
                unlisted(read, current.items(), mapped, repositoryIssues, allFaults);
        final Board board =
                new Board(
                        revision(
                                read.commit(),
                                current.items().values(),
                                fieldNames,
                                allFaults,
                                current.waits(),
                                unlistedIssues),
                        read.workflow(),
                        mapped.stories(),
                        mapped.issues(),
                        read.work(),
                        read.repositories(),
                        allFaults,
                        read.project().owner(),
                        unlistedIssues);
        final Map<String, ItemView> byId = new LinkedHashMap<>();
        for (final ItemView view : current.views()) {
            final String id = IssueMapping.idOf(view);
            final ItemView before = id == null ? null : byId.get(id);
            // An archived item of an id a live item holds too never takes the live one's writes.
            if (id != null && (before == null || before.archived() && !view.archived())) {
                byId.put(id, view);
            }
        }
        return new Snapshot(
                board,
                byId,
                read,
                current.items(),
                faults,
                fieldNames,
                restFieldIds,
                current.waits(),
                repositoryIssues);
    }

    /**
     * The repositories' open issues that are no items of the project, archived items included: each
     * named by its reference within one of the project's repositories, never by an id the board
     * already holds. One that cannot be named so is a fault and left out, so it never fails the
     * board.
     */
    private static List<UnlistedIssue> unlisted(
            final ProjectConfig read,
            final Map<String, GitHubGraphQl.ProjectItem> items,
            final IssueMapping.Mapped mapped,
            final List<Snapshot.Unlisted> repositoryIssues,
            final List<String> faults) {
        if (repositoryIssues.isEmpty()) {
            return List.of();
        }
        final Set<String> itemIssues = new HashSet<>();
        for (final GitHubGraphQl.ProjectItem item : items.values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            if (issue != null) {
                itemIssues.add(issue.id());
            }
        }
        final Set<String> taken = new HashSet<>();
        mapped.stories().forEach(story -> taken.add(story.id()));
        mapped.issues().forEach(issue -> taken.add(issue.id()));
        final Set<String> nodes = new HashSet<>();
        final List<UnlistedIssue> unlisted = new ArrayList<>();
        for (final Snapshot.Unlisted issue : repositoryIssues) {
            if (itemIssues.contains(issue.nodeId()) || !nodes.add(issue.nodeId())) {
                continue;
            }
            final String repository = issue.repository();
            final String id = repository + "#" + issue.number();
            // A repository's name reaches a fault only in a form an address can have.
            final String where =
                    REPOSITORY_PART.matcher(repository).matches()
                            ? "An open issue of " + repository
                            : "An open issue";
            if (!Ids.isReference(id) || !read.names().contains(repository)) {
                faults.add(
                        where
                                + " outside the project has no reference into its repositories"
                                + " and is left out");
                continue;
            }
            if (!taken.add(id)) {
                faults.add(
                        "The open issue "
                                + id
                                + " outside the project bears the reference of another issue and"
                                + " is left out");
                continue;
            }
            final boolean story = IssueMapping.STORY_TYPE.equals(issue.type());
            if (story && !repository.equals(read.own())) {
                // Once a project item, such a story stands on the board as a warned issue no work
                // starts on, so it is not offered for an add.
                faults.add(
                        "The open issue "
                                + id
                                + " outside the project is a story outside the project's own"
                                + " repository; as a project item it stands on the board as a warned"
                                + " issue, so it is not offered");
                continue;
            }
            unlisted.add(
                    new UnlistedIssue(
                            id,
                            repository,
                            issue.title(),
                            story ? UnlistedIssue.Kind.STORY : UnlistedIssue.Kind.ISSUE,
                            URI.create(
                                    read.forge()
                                            + "/"
                                            + read.project().owner()
                                            + "/"
                                            + repository
                                            + "/issues/"
                                            + issue.number()),
                            // A copy read back may hold any text: one of no known form is left
                            // out.
                            RepositoryIssues.author(issue.author()),
                            RepositoryIssues.association(issue.authorAssociation())));
        }
        return unlisted;
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
     * @param waits the blockers that are no items of the board, by their node ids, as last read
     */
    private record ItemsRead(
            Map<String, GitHubGraphQl.ProjectItem> items,
            List<ItemView> views,
            List<String> faults,
            IssueMapping.Mapped mapped,
            Set<String> missing,
            Map<String, Snapshot.Waited> waits) {

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
        final Set<String> itemCursors = new HashSet<>();
        int size = lastSize;
        while (true) {
            final @Nullable String cursor = after;
            final int first = size;
            final GitHubGraphQl.ProjectItems answer;
            try {
                answer =
                        call.of(
                                "the project items",
                                () ->
                                        queries.projectItems(
                                                token,
                                                read.project().owner(),
                                                read.project().number(),
                                                cursor,
                                                first,
                                                ITEM_LIMITS));
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
            after = call.next(answer.pageInfo(), answer.items().size(), itemCursors, "items");
            if (after == null) {
                break;
            }
        }
        completeItems(call, read, items);
        return mapped(items, read, fieldNames, Map.of());
    }

    private void completeItems(
            final Call call,
            final ProjectConfig read,
            final Map<String, GitHubGraphQl.ProjectItem> items) {
        final Set<String> nodes = new LinkedHashSet<>();
        final Map<String, List<GitHubGraphQl.ProjectItem>> keptByIssue = new LinkedHashMap<>();
        for (final GitHubGraphQl.ProjectItem item : items.values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            if (issue != null) {
                keptByIssue.computeIfAbsent(issue.id(), ignored -> new ArrayList<>()).add(item);
                if (incomplete(item, issue)) {
                    nodes.add(issue.id());
                }
            }
        }
        final List<String> all = List.copyOf(nodes);
        int size = GitHubGraphQl.MAX_ISSUES;
        for (int from = 0; from < all.size(); ) {
            final List<String> chunk = all.subList(from, Math.min(all.size(), from + size));
            final GitHubGraphQl.IssueBoard answer;
            try {
                answer =
                        call.of(
                                "the items with incomplete lists",
                                () -> queries.issueBoard(call.token, chunk, read.projectId()));
            } catch (final RuntimeException e) {
                if (chunk.size() > SMALLEST_PAGE && tooCostly(e)) {
                    size = Math.max(SMALLEST_PAGE, chunk.size() / 2);
                    LOG.info(
                            "GitHub found a batch of {} issues too costly; it is asked for {}",
                            chunk.size(),
                            size);
                    continue;
                }
                throw e;
            }
            for (int at = 0; at < chunk.size(); at++) {
                final List<GitHubGraphQl.ProjectItem> kept =
                        Objects.requireNonNull(keptByIssue.get(chunk.get(at)));
                final GitHubGraphQl.IssueOnBoard on =
                        at < answer.issues().size() ? answer.issues().get(at) : null;
                final String itemId = on == null ? null : on.itemId();
                for (final GitHubGraphQl.ProjectItem item : kept) {
                    if (on == null || on.issue() == null || !item.id().equals(itemId)) {
                        items.remove(item.id());
                    }
                }
                if (on != null && on.issue() != null && itemId != null) {
                    // Only the page tells whether an item is archived.
                    final GitHubGraphQl.ProjectItem listed =
                            kept.stream()
                                    .filter(item -> item.id().equals(itemId))
                                    .findFirst()
                                    .orElse(kept.get(0));
                    items.put(
                            itemId,
                            new GitHubGraphQl.ProjectItem(
                                    itemId,
                                    listed.archived(),
                                    on.issue(),
                                    on.values(),
                                    on.valuesPage(),
                                    on.valueCount()));
                }
            }
            from += chunk.size();
        }
    }

    private static boolean incomplete(
            final GitHubGraphQl.ProjectItem item, final GitHubGraphQl.BoardIssue issue) {
        return Boolean.TRUE.equals(issue.subIssues().pageInfo().hasNextPage())
                || Boolean.TRUE.equals(issue.blockedBy().pageInfo().hasNextPage())
                || Boolean.TRUE.equals(issue.labelsPage().hasNextPage())
                || Boolean.TRUE.equals(item.valuesPage().hasNextPage());
    }

    /** Maps the project's items, as read or as held, to the board's stories and issues. */
    private ItemsRead mapped(
            final Map<String, GitHubGraphQl.ProjectItem> items,
            final ProjectConfig read,
            final Map<String, String> fieldNames,
            final Map<String, Snapshot.Waited> waits) {
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
        final IssueMapping.Mapped mapped =
                IssueMapping.read(views, overflowing, read, states, waits);
        faults.addAll(mapped.faults());
        // A sub-issue the list holds lies in a repository the project does not plan: no lag.
        final Set<String> missing = new LinkedHashSet<>(mapped.outside());
        missing.removeAll(listed);
        return new ItemsRead(items, views, faults, mapped, missing, waits);
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
            pause.take(rereadPause);
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

        /**
         * When the read is refused: the moment GitHub has left it without an answer for its silence
         * limit, moved on by each answer.
         */
        private Instant until;

        /** Whether it reads the repositories' issue lists, whose silence ends only their read. */
        private final boolean lists;

        /** Whether it waits out a short rate limit GitHub names; only a read beside the saves. */
        private final boolean rateWaits;

        Call(final ProjectConfig config, final String token, final Instant until) {
            this(config, token, until, false, false);
        }

        Call(
                final ProjectConfig config,
                final String token,
                final Instant until,
                final boolean lists,
                final boolean rateWaits) {
            this.config = config;
            this.token = token;
            this.until = until;
            this.lists = lists;
            this.rateWaits = rateWaits;
        }

        /**
         * Waits out a rate limit GitHub named, when the read may and the wait is short and within
         * the read's whole share; the silence limit starts again after it, so the wait counts as no
         * silence.
         *
         * @return whether it waited, so the read is sent again
         */
        private boolean waitedForRate(final String what, final GitHubException e) {
            final @Nullable Duration delay = e.delay();
            if (!rateWaits
                    || delay == null
                    || !delay.isPositive()
                    || delay.compareTo(rateWaitMost) > 0
                    || waitedForRate.plus(delay).compareTo(rateWaitsMost) > 0) {
                return false;
            }
            LOG.warn(
                    "GitHub's rate limit holds the read of {} (HTTP {}); it waits {} s as GitHub"
                            + " asked and goes on",
                    what,
                    e.status(),
                    Seconds.of(delay));
            try {
                pause.take(delay);
            } catch (final InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new StoreException.Unreachable(
                        config.own(),
                        "the read was stopped while it waited for GitHub's rate limit");
            }
            waitedForRate = waitedForRate.plus(delay);
            until = clock.instant().plus(lists ? unlisted.deadline() : deadline);
            return true;
        }

        /** Refuses the read once its deadline has passed. */
        void inTime() {
            if (lists && clock.instant().isAfter(until)) {
                throw new RepositoryIssues.OutOfTime(unlisted.deadline());
            }
            if (clock.instant().isAfter(until)) {
                throw new StoreException.Unreachable(
                        config.own(),
                        "GitHub gave no answer to the project's read within "
                                + Seconds.of(deadline)
                                + " s");
            }
        }

        /**
         * Takes an answer: one that came after the silence limit is refused, and otherwise the
         * limit starts again from it, so a large project or list whose pages keep coming is read to
         * its end.
         */
        private <T> T answered(final T answer) {
            inTime();
            until = clock.instant().plus(lists ? unlisted.deadline() : deadline);
            return answer;
        }

        <T> T of(final String what, final Supplier<T> read) {
            return of(what, read, false);
        }

        /**
         * Answers a read, sent again after a failure on GitHub's side or on the way, as often as
         * the factor {@code retries} says.
         *
         * @param what the read, named for the log
         * @param read the read
         * @param repositoryRefusals whether GitHub's refusal of one repository's list is handed
         *     back as GitHub gave it, for the caller to keep that repository's list
         */
        <T> T of(final String what, final Supplier<T> read, final boolean repositoryRefusals) {
            try {
                for (int attempt = 1; ; attempt++) {
                    inTime();
                    try {
                        return answered(read.get());
                    } catch (final GitHubException e) {
                        if (e.rateLimited() && waitedForRate(what, e)) {
                            // A wait GitHub asked for is no failed try.
                            attempt--;
                            continue;
                        }
                        if (attempt >= readTries
                                || e.rateLimited()
                                || (e.status() != 0 && e.status() < 500)) {
                            throw e;
                        }
                        // A read changes nothing on GitHub, so one that failed on GitHub's side or
                        // on the way is sent again before it is answered as unreachable.
                        LOG.warn(
                                "GitHub's read of {} failed (HTTP {}, {}); it is sent again, try {}"
                                        + " of {}",
                                what,
                                e.status(),
                                e.delivery(),
                                attempt + 1,
                                readTries);
                        pauseBeforeRetry();
                    }
                }
            } catch (final StoreException
                    | UnsupportedOperationException
                    | RepositoryIssues.OutOfTime e) {
                throw e;
            } catch (final GitHubException e) {
                if (repositoryRefusals && RepositoryIssues.refusedFor(e)) {
                    throw e;
                }
                LOG.warn(
                        "GitHub's read of {} failed (HTTP {}, {}, rate limited {})",
                        what,
                        e.status(),
                        e.delivery(),
                        e.rateLimited());
                if (e.rateLimited()) {
                    // Only a rate limit the read fails on holds the person's other looks.
                    holds.spent(e.delay());
                    throw new StoreException.Unreachable(
                            config.own(), "GitHub's rate limit is spent; " + rereadIn(e.delay()));
                }
                throw refusals.refusal(config.own(), e, token);
            } catch (final RuntimeException e) {
                LOG.warn("The read of {} failed: {}", what, e.getClass().getName());
                throw refusals.refusal(config.own(), e, token);
            }
        }

        /** Waits before a read is sent again; a stopped wait sends it at once. */
        private void pauseBeforeRetry() {
            try {
                pause.take(rereadPause);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Answers the cursor of the next page, or null after the last. No count of pages ends a
         * list GitHub still continues; only a page that does not move the read on does, since
         * GitHub could answer such pages without end: a cursor given before, or an empty page that
         * names a next one.
         *
         * @param info the page's own account of what follows it
         * @param carried how many entries the page carried
         * @param seen every cursor this list has given so far, the one returned added to it
         * @param what the list, as the refusal names it
         * @throws StoreException.Unreadable if the page carried nothing and names a next one, or
         *     the cursor names no place this list has not read
         */
        @Nullable String next(
                final GitHubGraphQl.PageInfo info,
                final int carried,
                final Set<String> seen,
                final String what) {
            if (!Boolean.TRUE.equals(info.hasNextPage())) {
                return null;
            }
            if (carried == 0) {
                throw new StoreException.Unreadable(
                        config.own(),
                        "GitHub named a next page of " + what + " after a page without any");
            }
            final String end = info.endCursor();
            if (end == null || end.isBlank() || !seen.add(end)) {
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
        // A closed state is GitHub's close alone, so the field needs the open states only.
        options.put(
                FieldNames.STATUS,
                config.workflow().states().stream()
                        .filter(state -> !config.workflow().closed().contains(state))
                        .toList());
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
     * silently, one outside the project's owner with a fault. An item whose sub-issues, blockers,
     * labels or values run past one page is mapped and named among the overflowing, so the board
     * shows it warned.
     */
    static @Nullable ItemView view(
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
        if (incomplete(item, issue)) {
            // Shown as far as it was read, warned, so no work starts on what was read in part.
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
                URI.create(
                        config.forge()
                                + "/"
                                + config.project().owner()
                                + "/"
                                + repository
                                + "/issues/"
                                + issue.number()),
                issue.updatedAt(),
                item.archived(),
                issue.blockedBy().nodes().stream()
                        .map(ref -> new ItemView.Blocker(ref.id(), ref.repository(), ref.number()))
                        .toList(),
                unseen(issue.blockedBy()),
                issue.issueType(),
                issue.closedAt(),
                issue.subIssues().totalCount(),
                issue.blockedBy().totalCount(),
                partial(item.valuesPage(), issue),
                ItemView.unseen(issue.labelCount(), issue.labels().size()),
                ItemView.unseen(item.valueCount(), item.values().size()));
    }

    /**
     * Whether an item's labels or field values lie past the one page read of them, told as one, as
     * the board's copy keeps it.
     */
    static boolean partial(
            final GitHubGraphQl.PageInfo values, final GitHubGraphQl.BoardIssue issue) {
        return Boolean.TRUE.equals(values.hasNextPage())
                || Boolean.TRUE.equals(issue.labelsPage().hasNextPage());
    }

    /**
     * How many more issues GitHub counts in a whole first page than it names, as it leaves out one
     * the person's login cannot read; none where more pages follow, as such an item is left out.
     */
    static int unseen(final GitHubGraphQl.IssueRefs refs) {
        return Boolean.TRUE.equals(refs.pageInfo().hasNextPage())
                ? 0
                : Math.max(0, refs.totalCount() - refs.nodes().size());
    }

    /**
     * The item's field values by field name, each as text; a field the project lacks is skipped.
     */
    private static Map<String, String> values(
            final GitHubGraphQl.ProjectItem item, final Map<String, String> fieldNames) {
        return values(item.values(), fieldNames, item.issue());
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
            if (field != null && !FieldNames.PRIORITY.equals(field) && text != null) {
                values.put(field, text);
            }
        }
        return values;
    }

    /** Combines project values with the Priority option carried by the issue itself. */
    static Map<String, String> values(
            final List<GitHubGraphQl.ItemValue> given,
            final Map<String, String> fieldNames,
            final GitHubGraphQl.@Nullable BoardIssue issue) {
        final Map<String, String> values = values(given, fieldNames);
        final String priority = issue == null ? null : IssuePriority.held(issue);
        if (priority != null) {
            values.put(FieldNames.PRIORITY, priority);
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
     * item's node id, issue, repository, number, title, body, labels, whether labels or field
     * values lie past one page, change time, open and archived state and its reason, parent,
     * sub-issues, blockers and field values, and every open issue outside the project the board
     * names with its title and kind, so a change of any of them, a field's alone or a fault's
     * included, gives another revision.
     */
    private static Revision revision(
            final String commit,
            final Iterable<GitHubGraphQl.ProjectItem> items,
            final Map<String, String> fieldNames,
            final List<String> faults,
            final Map<String, Snapshot.Waited> waits,
            final List<UnlistedIssue> unlisted) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
        add(digest, commit);
        // The faults follow GitHub's order of the items, which may shift between two reads.
        add(
                digest,
                faults.stream()
                        .map(
                                fault ->
                                        fault.startsWith(RepositoryIssues.CUT_SHORT)
                                                ? RepositoryIssues.CUT_SHORT
                                                : fault)
                        .sorted()
                        .toList());
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
            // Whether labels or values lie past one page decides a warning on its own.
            add(digest, String.valueOf(Boolean.TRUE.equals(issue.labelsPage().hasNextPage())));
            add(digest, String.valueOf(Boolean.TRUE.equals(item.valuesPage().hasNextPage())));
            // GitHub's counts tell a label or value added past that page.
            add(digest, Integer.toString(issue.labelCount()));
            add(digest, Integer.toString(item.valueCount()));
            // The type makes an item a story or an issue.
            addNullable(digest, issue.issueType());
            add(digest, issue.updatedAt().toString());
            add(digest, issue.state());
            addNullable(digest, issue.stateReason());
            // The time it closed is a story's tested day.
            final Instant closedAt = issue.closedAt();
            addNullable(digest, closedAt == null ? null : closedAt.toString());
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
        // A wait's state changes the board's, as a blocker on it closing offers its issue.
        final Map<String, Snapshot.Waited> sortedWaits = new TreeMap<>(waits);
        add(digest, Integer.toString(sortedWaits.size()));
        for (final Map.Entry<String, Snapshot.Waited> wait : sortedWaits.entrySet()) {
            add(digest, wait.getKey());
            add(digest, wait.getValue().repository());
            add(digest, Integer.toString(wait.getValue().number()));
            addNullable(digest, String.valueOf(wait.getValue().closed()));
        }
        // An issue made outside the project, or a new title or author of one, is told to the window
        // too.
        final Map<String, UnlistedIssue> sortedUnlisted = new TreeMap<>();
        unlisted.forEach(issue -> sortedUnlisted.put(issue.id(), issue));
        add(digest, Integer.toString(sortedUnlisted.size()));
        for (final UnlistedIssue issue : sortedUnlisted.values()) {
            add(digest, issue.id());
            add(digest, issue.title());
            add(digest, issue.kind().name());
            addNullable(digest, issue.author());
            addNullable(digest, issue.authorAssociation());
        }
        return new Revision(HexFormat.of().formatHex(digest.digest()));
    }

    private static void add(final MessageDigest digest, final GitHubGraphQl.IssueRefs refs) {
        add(
                digest,
                refs.nodes().size()
                        + (Boolean.TRUE.equals(refs.pageInfo().hasNextPage()) ? "+" : "")
                        + "/"
                        + refs.totalCount());
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
