package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.CallTimes;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.ReadProgress;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.RepositoryWarning;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageMetrics;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.Tracker;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.cache.BoardCopies;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A project's planning store in GitHub Issues: one lock, the snapshot every answer comes from, and
 * its parts, each behind its own interface, so they are built and tested apart.
 */
public final class GitHubStore implements PlanningStore {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubStore.class);

    /**
     * The revision a stale refusal names when the board could not be read anew: never one of a
     * board, so the window saves nothing against it and reads the board first.
     */
    static final String UNREAD = "unread";

    /**
     * The start of the board's fault naming items a save wrote and GitHub was not asked for again.
     */
    static final String UNREAD_SAVED = "Saved, not read again from GitHub yet: ";

    /**
     * The parts the store is made of; each one left out does nothing or refuses.
     *
     * @param reader reads the project
     * @param writer writes to it
     * @param probe tells whether it changed
     * @param refusals turns a failure into the store's refusal
     * @param metrics gives a stage's numbers
     * @param storyStates derives a story's state
     * @param copies keeps the board's copy on disk, which a start serves before GitHub is read, or
     *     null to keep none
     * @param changes tells what changed on the board since its copy's last look
     * @param labels the repositories' labels, read at the project's first read and at each refresh
     * @param copyWrites runs each write of the copy, the newest board waiting the one written
     */
    public record Parts(
            BoardReader reader,
            BoardWriter writer,
            ChangeProbe probe,
            AccessRefusals refusals,
            StageMetrics metrics,
            StoryStates storyStates,
            @Nullable BoardCopies copies,
            TrackerChanges changes,
            RepositoryLabels labels,
            Executor copyWrites) {

        /**
         * Parts that only read, knowing no change probe and no stage numbers.
         *
         * @param reader reads the project
         * @param storyStates derives a story's state
         * @return the parts
         */
        public static Parts reading(final BoardReader reader, final StoryStates storyStates) {
            return new Parts(
                    reader,
                    BoardWriter.NONE,
                    ChangeProbe.ALWAYS_CHANGED,
                    AccessRefusals.DEFAULT,
                    StageMetrics.NONE,
                    storyStates,
                    null,
                    TrackerChanges.NONE,
                    RepositoryLabels.NONE,
                    Runnable::run);
        }

        /**
         * The same parts with another writer.
         *
         * @param other the writer
         * @return the parts
         */
        public Parts writer(final BoardWriter other) {
            return new Parts(
                    reader,
                    other,
                    probe,
                    refusals,
                    metrics,
                    storyStates,
                    copies,
                    changes,
                    labels,
                    copyWrites);
        }

        /**
         * The same parts with another change probe.
         *
         * @param other the probe
         * @return the parts
         */
        public Parts probe(final ChangeProbe other) {
            return new Parts(
                    reader,
                    writer,
                    other,
                    refusals,
                    metrics,
                    storyStates,
                    copies,
                    changes,
                    labels,
                    copyWrites);
        }

        /**
         * The same parts with other refusals.
         *
         * @param other the refusals
         * @return the parts
         */
        public Parts refusals(final AccessRefusals other) {
            return new Parts(
                    reader,
                    writer,
                    probe,
                    other,
                    metrics,
                    storyStates,
                    copies,
                    changes,
                    labels,
                    copyWrites);
        }

        /**
         * The same parts with another source of stage numbers.
         *
         * @param other the source
         * @return the parts
         */
        public Parts metrics(final StageMetrics other) {
            return new Parts(
                    reader,
                    writer,
                    probe,
                    refusals,
                    other,
                    storyStates,
                    copies,
                    changes,
                    labels,
                    copyWrites);
        }

        /**
         * The same parts keeping the board's copy in another place.
         *
         * @param other the copies, or null to keep none
         * @return the parts
         */
        public Parts copies(final @Nullable BoardCopies other) {
            return new Parts(
                    reader,
                    writer,
                    probe,
                    refusals,
                    metrics,
                    storyStates,
                    other,
                    changes,
                    labels,
                    copyWrites);
        }

        /**
         * The same parts asking another tracker what changed since the copy's last look.
         *
         * @param other the tracker's changes
         * @return the parts
         */
        public Parts changes(final TrackerChanges other) {
            return new Parts(
                    reader,
                    writer,
                    probe,
                    refusals,
                    metrics,
                    storyStates,
                    copies,
                    other,
                    labels,
                    copyWrites);
        }

        /**
         * The same parts keeping the repositories' labels through another forge.
         *
         * @param other the labels
         * @return the parts
         */
        public Parts labels(final RepositoryLabels other) {
            return new Parts(
                    reader,
                    writer,
                    probe,
                    refusals,
                    metrics,
                    storyStates,
                    copies,
                    changes,
                    other,
                    copyWrites);
        }

        /**
         * The parts with the board's copy written by [other], so a save never waits for the disk.
         *
         * @param other runs each write of the copy; the newest board waiting is the one written
         * @return the parts
         */
        public Parts copyWrites(final Executor other) {
            return new Parts(
                    reader,
                    writer,
                    probe,
                    refusals,
                    metrics,
                    storyStates,
                    copies,
                    changes,
                    labels,
                    other);
        }
    }

    private final Supplier<ProjectConfig> again;

    private final Parts parts;

    private final Duration lockWait;

    private final TokenSource tokens;

    private final ReentrantLock lock = new ReentrantLock();

    private final Touched touched;

    private final Executor looks;

    private volatile ProjectConfig config;

    private volatile @Nullable Snapshot snapshot;

    private volatile boolean retired;

    /** Whether the copy on disk was looked for; it is, once, on the first read of the board. */
    private volatile boolean copyLooked;

    /**
     * Whether the board held was taken from the copy and GitHub has not been read whole since, so
     * the next poll reads it whole rather than asking whether it changed since the copy.
     */
    private volatile boolean fromCopy;

    /**
     * What saves wrote that GitHub could not be asked for again after them; the board held keeps
     * every other item and names these unread until the next look or save reads them by id.
     */
    private volatile Touched.@Nullable Ids unread;

    /**
     * Held while the board held is replaced, its copy offered with it; nothing under it asks GitHub
     * or writes to disk.
     */
    private final Object holding = new Object();

    /** Writes the board's copy behind the saves, the newest board waiting the one written. */
    private final CopyWriter copyWriter;

    /** Where the tracker's look for changes stands, kept with the copy. */
    private volatile ChangeCursor cursor = ChangeCursor.NONE;

    /** The planner's own saves the tracker's list may not show yet, kept with the copy. */
    private final HeldSaves saves = new HeldSaves();

    /**
     * Each item's own last change as the tracker's list showed it at the last look that took it,
     * kept with the copy, so a look sees a change the list shows by the item's time alone.
     */
    private final Map<String, Instant> listedAt = new ConcurrentHashMap<>();

    /** How far the look for changes under way has come, or null while none runs. */
    private volatile @Nullable ReadProgress looking;

    /** A whole read whose items are read, while it waits to take them in; null otherwise. */
    private volatile @Nullable ReadProgress finishing;

    /** Whether a save or a comment holds the project's lock, which a read then waits for. */
    private final java.util.concurrent.atomic.AtomicInteger savesHolding =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The whole reads waiting for the project's lock to take their items in. */
    private final java.util.concurrent.atomic.AtomicInteger wholeWaiting =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The looks waiting for the project's lock to fold their answer in. */
    private final java.util.concurrent.atomic.AtomicInteger lookWaiting =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Guards the Reloads asked for. */
    private final Object reloads = new Object();

    /** The Reloads asked for whose whole read has not ended, each waiting or reading. */
    private int reloadsAsked;

    /** When the first Reload still asked for was asked, or null while none is. */
    private volatile java.time.@Nullable Instant reloadAskedAt;

    /** What the saves since the look under way began touched; null while none runs. */
    private @Nullable List<Touched.Ids> lookSaves;

    /** Guards the whole read under way and what saves touched while it ran. */
    private final Object fold = new Object();

    /** The whole read under way outside the save lock; null while none runs. */
    private @Nullable CompletableFuture<Snapshot> inFlight;

    /** What the saves since the whole read under way began touched, read again on its result. */
    private final List<Touched.Ids> pending = new ArrayList<>();

    /**
     * Makes the store; nothing is read until the board is asked for.
     *
     * @param config what the project is read with, as its files named it at opening
     * @param again gives what the project is read with as its files name it now, asked before each
     *     read of the board
     * @param parts its parts
     * @param tokens the person's token, asked for only to scrub it from a refusal's words
     * @param lockWait how long a call waits for another save or read before it is refused as busy
     */
    public GitHubStore(
            final ProjectConfig config,
            final Supplier<ProjectConfig> again,
            final Parts parts,
            final TokenSource tokens,
            final Duration lockWait) {
        this(config, again, parts, tokens, lockWait, new Touched());
    }

    /**
     * Makes the store, told what its writer's calls touch, so a save reads again only that.
     *
     * @param config what the project is read with, as its files named it at opening
     * @param again gives what the project is read with as its files name it now
     * @param parts its parts
     * @param tokens the person's token, asked for only to scrub it from a refusal's words
     * @param lockWait how long a call waits for another save or read before it is refused as busy
     * @param touched what the writer's calls record they touched
     */
    public GitHubStore(
            final ProjectConfig config,
            final Supplier<ProjectConfig> again,
            final Parts parts,
            final TokenSource tokens,
            final Duration lockWait,
            final Touched touched) {
        this(config, again, parts, tokens, lockWait, touched, Runnable::run);
    }

    /**
     * Makes the store, told what its writer's calls touch and where the change probe's look after a
     * read or save runs.
     *
     * @param config what the project is read with, as its files named it at opening
     * @param again gives what the project is read with as its files name it now
     * @param parts its parts
     * @param tokens the person's token, asked for only to scrub it from a refusal's words
     * @param lockWait how long a call waits for another save or read before it is refused as busy
     * @param touched what the writer's calls record they touched
     * @param looks runs the change probe's look right after a read or save
     */
    public GitHubStore(
            final ProjectConfig config,
            final Supplier<ProjectConfig> again,
            final Parts parts,
            final TokenSource tokens,
            final Duration lockWait,
            final Touched touched,
            final Executor looks) {
        this.looks = looks;
        this.config = config;
        this.again = again;
        this.parts = parts;
        this.tokens = tokens;
        this.lockWait = lockWait;
        this.touched = touched;
        this.copyWriter = new CopyWriter(parts.copyWrites());
    }

    @Override
    public Tracker tracker() {
        return Tracker.GITHUB;
    }

    @Override
    public Board board() {
        final Snapshot current = snapshot;
        if (current != null) {
            return served(current.board());
        }
        final Snapshot copied = fromCopy();
        return copied != null ? served(copied.board()) : refresh();
    }

    /**
     * Takes the board from its copy on disk, once per store and under the lock, so a start shows
     * the board before GitHub is read; the next poll then reads GitHub whole.
     *
     * @return the board held now, or null when there is none and the copy gave none
     */
    private @Nullable Snapshot fromCopy() {
        final BoardCopies copies = parts.copies();
        if (copies == null || copyLooked) {
            return null;
        }
        refuseRetired();
        try {
            if (!lock.tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS)) {
                // Another call holds the project; the whole read answers as it always did.
                return null;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException.Busy(config.own());
        }
        try {
            if (copyLooked) {
                return snapshot;
            }
            copyLooked = true;
            if (snapshot != null) {
                return snapshot;
            }
            final long began = System.nanoTime();
            final ProjectConfig now = configNow();
            final long configured = System.nanoTime();
            final BoardCopy copy =
                    copies.read(BoardCopyMapping.project(now), now.commit()).orElse(null);
            if (copy == null) {
                return null;
            }
            final Snapshot loaded = parts.reader().fromCopy(copy, now);
            final long taken = System.nanoTime();
            if (loaded == null) {
                LOG.info("The board's copy is not of the project as its files name it now");
                return null;
            }
            snapshot = loaded;
            fromCopy = true;
            // The probe knows nothing from before the copy: its first look takes what it sees as
            // this board, while the item look tells what changed on it since.
            parts.probe().adoptNext();
            cursor = copy.cursor();
            saves.load(copy.held(), copy.cursor());
            listedAt.clear();
            for (final TrackedItem item : copy.items()) {
                final Instant at = item.updatedAt();
                if (at != null) {
                    listedAt.put(item.id(), at);
                }
            }
            LOG.info(
                    "Took the board from its copy of {} items held at {} in {} ms ({} ms the"
                            + " project's files, {} ms the copy); GitHub is read next",
                    copy.items().size(),
                    copy.takenAt(),
                    millis(began, taken),
                    millis(began, configured),
                    millis(configured, taken));
            return loaded;
        } catch (final RuntimeException e) {
            // The copy only spares a whole read: one that cannot be used leaves the read to do.
            LOG.warn(
                    "The board's copy could not be taken: {}; GitHub is read whole",
                    e.getClass().getSimpleName());
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads the whole project, never asking first whether it changed: a person's reload sees a
     * field or option made on GitHub, which no change check asks about, and while GitHub's rate
     * limit is spent it asks once and is told GitHub's refusal.
     */
    @Override
    public Board refresh() {
        refuseRetired();
        final ProjectConfig now;
        try {
            now = configNow();
        } catch (final RuntimeException e) {
            throw refused(e);
        }
        final WholeRead why = snapshot == null ? WholeRead.FIRST : WholeRead.RELOAD;
        final Board read = wholeBesideSaves(now, true, why).board();
        if (parts.labels().kept()) {
            // A refresh is the one read after the first that checks the labels again.
            readLabels(read.names());
        }
        return served(read);
    }

    /** Why the board is read whole, named in the log so a read after the first can be explained. */
    private enum WholeRead {
        FIRST("no board is held yet"),
        CONFIG("the project's files changed"),
        UNTOLD("the change look gave no answer"),
        UNLISTED("the change check saw a change the item list does not show"),
        COPY_CUT("the copy's issues outside the project were not all read"),
        PROBED("the change check saw a change"),
        RELOAD("a read anew was asked for"),
        RESULT("a stage result names an issue the board does not hold"),
        WRITTEN("a write completed with no board held");

        private final String words;

        WholeRead(final String words) {
            this.words = words;
        }
    }

    /** Whether a board's lists of the issues outside the project were read in part. */
    private static boolean listsCutShort(final Snapshot board) {
        return board.board().faults().stream()
                .anyMatch(fault -> fault.startsWith(RepositoryIssues.CUT_SHORT));
    }

    private static long millis(final long from, final long to) {
        return (to - from) / 1_000_000L;
    }

    /**
     * Asks GitHub whether the project changed and reads it whole only when it did, unless GitHub's
     * rate limit is spent: then GitHub is not asked until its wait ends, and the board kept is
     * answered with a fault saying when.
     */
    @Override
    public Board poll() {
        final Pace pace = parts.probe().pace();
        if (pace.hold() == null) {
            try {
                return served(pollBesideSaves().board());
            } catch (final StoreException.Unreachable e) {
                // A rate limit this poll found spent serves the board kept, as the polls after it.
                if (pace.hold() == null) {
                    throw e;
                }
            }
        }
        final Snapshot current = snapshot;
        if (current == null) {
            throw new StoreException.Unreachable(
                    config.own(), Objects.requireNonNullElse(pace.hold(), "GitHub asked to wait"));
        }
        return served(current.board());
    }

    /** Asks GitHub as often as a project whose changes are probed, as its pace allows. */
    /** A whole read of a large board takes minutes, longer than the window waits for a reload. */
    @Override
    public boolean reloadsAlone() {
        return true;
    }

    /** The board held, never read for it, so a reload before the first board does not wait. */
    @Override
    public java.util.Optional<org.fuin.porelo.planner.store.Revision> held() {
        final Snapshot current = snapshot;
        return current != null
                ? java.util.Optional.of(current.board().revision())
                : java.util.Optional.empty();
    }

    @Override
    public Duration pollDelay(final Duration fetched, final Duration probed) {
        return parts.probe().pace().delay(probed);
    }

    @Override
    public Board save(final Revision revision, final Writes writes) {
        return served(
                writing(
                                () -> {
                                    parts.writer().save(current(), revision, writes);
                                    return readAfterWriting();
                                })
                        .board());
    }

    /** Writes the topic labels of an issue or a story and keeps every other label it carries. */
    @Override
    public boolean setsLabels() {
        return true;
    }

    /** Whether an issue's requested state is written during its creation. */
    @Override
    public boolean createsInState() {
        return true;
    }

    /** Whether a move's reason is kept: as a comment on each item it closes. */
    @Override
    public boolean keepsReasons() {
        return true;
    }

    @Override
    public Map<String, List<Wait>> waitsNow(final java.util.Collection<String> issues) {
        final Snapshot held = current();
        final Map<String, ItemView> named = new LinkedHashMap<>();
        for (final String id : issues) {
            final ItemView item = held.items().get(id);
            if (item != null && held.board().issue(id).isPresent()) {
                named.put(id, item);
            }
        }
        final BoardReader.StartRead read;
        try {
            read =
                    parts.reader()
                            .waitsOf(
                                    configNow(),
                                    held,
                                    named.values().stream()
                                            .map(ItemView::issueId)
                                            .collect(java.util.stream.Collectors.toSet()));
        } catch (final RuntimeException e) {
            throw refused(e);
        }
        final Map<String, String> boardIds = new LinkedHashMap<>();
        held.items().forEach((id, item) -> boardIds.put(item.issueId(), id));
        final java.net.URI forge = held.config().forge();
        final Map<String, List<Wait>> waits = new LinkedHashMap<>();
        for (final Map.Entry<String, ItemView> each : named.entrySet()) {
            final ItemView item = each.getValue();
            final List<Wait> fresh = new ArrayList<>();
            final List<org.fuin.porelo.forge.github.GitHubGraphQl.IssueRef> now =
                    read.blockers().get(item.issueId());
            // A wait the board holds that the read named none for, such as one not readable, stays.
            final List<Wait> carried =
                    held.board().issue(each.getKey()).orElseThrow().waitsOn().stream()
                            .filter(wait -> wait.reference() == null)
                            .toList();
            if (read.unread().contains(item.issueId()) && carried.isEmpty()) {
                // Its blockers could not be read whole, so it is not known free; a wait not
                // readable carried over says so already.
                fresh.add(Wait.unreadable());
            }
            if (now == null) {
                for (final String blocker : item.blockedByIds()) {
                    if (held.waits().containsKey(blocker)) {
                        fresh.add(IssueMapping.wait(item, blocker, read.waits(), forge));
                    }
                }
            } else {
                for (final org.fuin.porelo.forge.github.GitHubGraphQl.IssueRef ref : now) {
                    final String boardId = boardIds.get(ref.id());
                    final org.fuin.porelo.planner.store.Issue blocking =
                            boardId == null ? null : held.board().issue(boardId).orElse(null);
                    if (blocking == null) {
                        fresh.add(IssueMapping.wait(item, ref.id(), read.waits(), forge));
                    } else if (!item.blockedByIds().contains(ref.id())) {
                        // A blocker of the board added since the board was read, judged by it.
                        fresh.add(
                                IssueMapping.wait(
                                        item,
                                        ref.id(),
                                        Map.of(
                                                ref.id(),
                                                new Snapshot.Waited(
                                                        ref.repository(),
                                                        ref.number(),
                                                        held.board()
                                                                .workflow()
                                                                .closed()
                                                                .contains(blocking.state()))),
                                        forge));
                    }
                }
            }
            fresh.addAll(carried);
            waits.put(each.getKey(), fresh);
        }
        return waits;
    }

    @Override
    public NewIssue.Added createIssue(final Revision revision, final NewIssue issue) {
        final String[] id = new String[1];
        final Snapshot after =
                writing(
                        () -> {
                            id[0] = parts.writer().createIssue(current(), revision, issue);
                            return readAfterMaking(List.of(id[0]));
                        });
        return new NewIssue.Added(Objects.requireNonNull(id[0]), served(after.board()));
    }

    @Override
    public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
        final BoardWriter.IdeaIds[] ids = new BoardWriter.IdeaIds[1];
        final Snapshot after =
                writing(
                        () -> {
                            ids[0] = parts.writer().createIdea(current(), revision, idea);
                            final List<String> made = new ArrayList<>();
                            made.add(ids[0].story());
                            made.addAll(ids[0].issues());
                            return readAfterMaking(made);
                        });
        final BoardWriter.IdeaIds saved = Objects.requireNonNull(ids[0]);
        return new NewIdea.Added(saved.story(), saved.issues(), served(after.board()));
    }

    @Override
    public boolean addsUnlistedIssues() {
        return true;
    }

    /**
     * Adds the issue under the save's lock, where the writer checks the revision and the offer
     * again, then reads the board after it as after a made issue.
     */
    @Override
    public Board addUnlistedIssue(final Revision revision, final String issue) {
        final Snapshot after =
                writing(
                        () -> {
                            try {
                                parts.writer().addUnlistedIssue(current(), revision, issue);
                            } catch (final StoreException.NoSuchIssue gone) {
                                // The tracker no longer gives the issue: the board held still
                                // offers it, so only a whole read shows the offer as it is.
                                drop();
                                throw gone;
                            }
                            return readAfterMaking(List.of(issue));
                        });
        return served(after.board());
    }

    @Override
    public boolean makesStoriesFromIssues() {
        return true;
    }

    @Override
    public NewIdea.Added createStoryFromIssues(
            final Revision revision, final StoryFromIssues story) {
        final BoardWriter.IdeaIds[] ids = new BoardWriter.IdeaIds[1];
        final Snapshot after =
                writing(
                        () -> {
                            ids[0] =
                                    parts.writer()
                                            .createStoryFromIssues(current(), revision, story);
                            return readAfterMaking(List.of(ids[0].story()));
                        });
        final BoardWriter.IdeaIds saved = Objects.requireNonNull(ids[0]);
        return new NewIdea.Added(saved.story(), saved.issues(), served(after.board()));
    }

    @Override
    public Board setFields(final Revision revision, final String issue, final Fields fields) {
        return edited(snapshot -> parts.writer().setFields(snapshot, revision, issue, fields));
    }

    @Override
    public Board setStoryIssues(
            final Revision revision, final String story, final List<String> issues) {
        return edited(snapshot -> parts.writer().setStoryIssues(snapshot, revision, story, issues));
    }

    @Override
    public Board setBlockedBy(
            final Revision revision, final String issue, final List<String> blockedBy) {
        return edited(
                snapshot -> parts.writer().setBlockedBy(snapshot, revision, issue, blockedBy));
    }

    @Override
    public Board setText(final Revision revision, final String id, final TextEdit edit) {
        return edited(snapshot -> parts.writer().setText(snapshot, revision, id, edit));
    }

    @Override
    public Board save(final Basis basis, final Writes writes, final Recheck recheck) {
        return served(
                writing(
                                () -> {
                                    final Snapshot held = current();
                                    // The reasons' comments are read before the targets, so the
                                    // targets' read is the last one before the first write.
                                    final Map<String, Boolean> reasons =
                                            parts.writer().reasonsStanding(held, writes);
                                    final BoardWriter.ReadBefore read =
                                            readBefore(held, recheck, targets(held, writes))
                                                    .withReasons(reasons);
                                    CallTimes.phase(
                                            "write",
                                            () -> {
                                                parts.writer().save(held, basis, writes, read);
                                                return held;
                                            });
                                    return readAfterWriting();
                                })
                        .board());
    }

    @Override
    public NewIssue.Added createIssue(
            final Basis basis, final NewIssue issue, final Recheck recheck) {
        final String[] id = new String[1];
        final Snapshot after =
                writing(
                        () -> {
                            final Snapshot now = rechecked(recheck);
                            id[0] = parts.writer().createIssue(now, basis, issue);
                            return readAfterMaking(List.of(id[0]));
                        });
        return new NewIssue.Added(Objects.requireNonNull(id[0]), served(after.board()));
    }

    @Override
    public NewIdea.Added createIdea(final Basis basis, final NewIdea idea, final Recheck recheck) {
        final BoardWriter.IdeaIds[] ids = new BoardWriter.IdeaIds[1];
        final Snapshot after =
                writing(
                        () -> {
                            final Snapshot now = rechecked(recheck);
                            ids[0] = parts.writer().createIdea(now, basis, idea);
                            final List<String> made = new ArrayList<>();
                            made.add(ids[0].story());
                            made.addAll(ids[0].issues());
                            return readAfterMaking(made);
                        });
        final BoardWriter.IdeaIds saved = Objects.requireNonNull(ids[0]);
        return new NewIdea.Added(saved.story(), saved.issues(), served(after.board()));
    }

    @Override
    public Board addUnlistedIssue(final Basis basis, final String issue, final Recheck recheck) {
        final Snapshot after =
                writing(
                        () -> {
                            final Snapshot now = rechecked(recheck);
                            try {
                                parts.writer().addUnlistedIssue(now, basis, issue);
                            } catch (final StoreException.NoSuchIssue gone) {
                                // The tracker no longer gives the issue: the board held still
                                // offers it, so only a whole read shows the offer as it is.
                                drop();
                                throw gone;
                            }
                            return readAfterMaking(List.of(issue));
                        });
        return served(after.board());
    }

    @Override
    public NewIdea.Added createStoryFromIssues(
            final Basis basis, final StoryFromIssues story, final Recheck recheck) {
        final BoardWriter.IdeaIds[] ids = new BoardWriter.IdeaIds[1];
        final Snapshot after =
                writing(
                        () -> {
                            final Snapshot now = rechecked(recheck);
                            ids[0] = parts.writer().createStoryFromIssues(now, basis, story);
                            return readAfterMaking(List.of(ids[0].story()));
                        });
        final BoardWriter.IdeaIds saved = Objects.requireNonNull(ids[0]);
        return new NewIdea.Added(saved.story(), saved.issues(), served(after.board()));
    }

    @Override
    public Board setFields(
            final Basis basis, final String issue, final Fields fields, final Recheck recheck) {
        return edited(
                recheck, snapshot -> parts.writer().setFields(snapshot, basis, issue, fields));
    }

    @Override
    public Board setStoryIssues(
            final Basis basis,
            final String story,
            final List<String> issues,
            final Recheck recheck) {
        return edited(
                recheck, snapshot -> parts.writer().setStoryIssues(snapshot, basis, story, issues));
    }

    @Override
    public Board setBlockedBy(
            final Basis basis,
            final String issue,
            final List<String> blockedBy,
            final Recheck recheck) {
        return edited(
                recheck,
                snapshot -> parts.writer().setBlockedBy(snapshot, basis, issue, blockedBy));
    }

    @Override
    public Board setText(
            final Basis basis, final String id, final TextEdit edit, final Recheck recheck) {
        return edited(recheck, snapshot -> parts.writer().setText(snapshot, basis, id, edit));
    }

    /** Runs an edit after its rules; the board is read again only when the edit wrote anything. */
    private Board edited(final Recheck recheck, final Predicate<Snapshot> edit) {
        return served(
                writing(
                                () -> {
                                    final Snapshot now = rechecked(recheck);
                                    final boolean wrote =
                                            CallTimes.phase("write", () -> edit.test(now));
                                    return wrote ? readAfterWriting() : now;
                                })
                        .board());
    }

    /**
     * Runs a write's rules, under the lock, on the dependencies they need read afresh from GitHub,
     * and answers the board held, which the write is planned on; a rule that refuses ends the write
     * before anything is written.
     */
    private Snapshot rechecked(final Recheck recheck) {
        final Snapshot now = current();
        readBefore(now, recheck, Set.of());
        return now;
    }

    /**
     * Runs the recheck on what it reads afresh, reading the write's targets in its first read, and
     * answers what it read for the write to judge them by.
     */
    private BoardWriter.ReadBefore readBefore(
            final Snapshot now, final Recheck recheck, final Set<String> targets) {
        return CallTimes.phase(
                "recheck",
                () -> {
                    final FreshDependencies fresh =
                            new FreshDependencies(
                                    now,
                                    recheck,
                                    (base, ids) -> parts.reader().reread(configNow(), base, ids),
                                    parts.writer(),
                                    targets);
                    recheck.rule().accept(fresh);
                    return fresh.readBefore();
                });
    }

    /** The node ids of the items a save writes, as the snapshot holds them. */
    private static Set<String> targets(final Snapshot now, final Writes writes) {
        final Set<String> ids = new LinkedHashSet<>();
        ids.addAll(writes.issues().keySet());
        ids.addAll(writes.workers().keySet());
        ids.addAll(writes.stories().keySet());
        ids.addAll(writes.priorities().keySet());
        ids.addAll(writes.issueReasons().keySet());
        ids.addAll(writes.storyReasons().keySet());
        final Set<String> nodes = new LinkedHashSet<>();
        for (final String id : ids) {
            final ItemView item = now.items().get(id);
            if (item != null) {
                nodes.add(item.issueId());
            }
        }
        return nodes;
    }

    /**
     * Writes a comment under the lock; the board shows no comment, so it is not read again and the
     * board kept stays.
     */
    @Override
    public void comment(final String issue, final String text) {
        lockedForSave(
                () -> {
                    final Snapshot now = current();
                    parts.writer().comment(now, issue, text);
                    return now;
                });
    }

    /** Runs an edit; the board is read again only when the edit wrote anything. */
    private Board edited(final Predicate<Snapshot> edit) {
        return served(
                writing(
                                () -> {
                                    final Snapshot now = current();
                                    return edit.test(now) ? readAfterWriting() : now;
                                })
                        .board());
    }

    @Override
    public Board saveResult(final StageResult result) {
        return served(
                writing(
                                () -> {
                                    // A stage runs for minutes, so its issues are read as they
                                    // stand now before the result is written into them.
                                    final Snapshot now = withResultsIssues(result);
                                    for (final StageResult.ForIssue issue : result.issues()) {
                                        now.board()
                                                .issue(issue.id())
                                                .orElseThrow(
                                                        () ->
                                                                new StoreException.NoSuchIssue(
                                                                        issue.id()));
                                    }
                                    final StageEntry stage =
                                            result.issues().size() == 1
                                                    ? parts.metrics()
                                                            .stage(
                                                                    result.issues().get(0).id(),
                                                                    result)
                                                            .orElse(null)
                                                    : null;
                                    parts.writer().saveResult(now, result, stage);
                                    return readAfterWriting();
                                })
                        .board());
    }

    @Override
    public @Nullable String storyState(final List<String> issueStates) {
        return parts.storyStates().storyState(issueStates);
    }

    @Override
    public TextFormat textFormat() {
        return TextFormat.MARKDOWN;
    }

    @Override
    public void retire() {
        retired = true;
        // The next start shows the last board held, not one a write still waiting would replace.
        copyWriter.finish();
    }

    @Override
    public java.util.Optional<ReadProgress> reading() {
        // A whole read, a Reload's among them, wins over a look running beside it.
        final java.util.Optional<ReadProgress> whole = parts.reader().progress();
        if (whole.isPresent()) {
            return whole;
        }
        final ReadProgress done = finishing;
        if (done != null) {
            return java.util.Optional.of(waiting(done, wholeWaiting));
        }
        final java.time.Instant asked = reloadAskedAt;
        if (asked != null) {
            // A Reload asked for waits behind a look the planner's own poll runs: told as the
            // whole read it is, before it reads anything.
            return java.util.Optional.of(new ReadProgress(asked, 0, null, null, "GitHub"));
        }
        final ReadProgress look = looking;
        return look == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(waiting(look, lookWaiting));
    }

    /** A read's progress, told as waiting for a save while it waits for the lock a save holds. */
    private ReadProgress waiting(
            final ReadProgress progress, final java.util.concurrent.atomic.AtomicInteger waits) {
        return waits.get() > 0 && savesHolding.get() > 0
                ? progress.waiting(ReadProgress.SAVE)
                : progress;
    }

    @Override
    public void reloadAsked() {
        synchronized (reloads) {
            if (reloadsAsked++ == 0) {
                reloadAskedAt = java.time.Instant.now();
            }
        }
    }

    @Override
    public void reloadEnded() {
        synchronized (reloads) {
            if (reloadsAsked > 0 && --reloadsAsked == 0) {
                reloadAskedAt = null;
            }
        }
    }

    /**
     * A poll: asks whether the project changed and reads it whole only when it did, beside the
     * saves, never holding them for the whole read.
     */
    private Snapshot pollBesideSaves() {
        refuseRetired();
        final Snapshot before = snapshot;
        if (unread != null && before != null) {
            // A board read by id gives the look its items as GitHub holds them; a failed read
            // forgets the board, which this poll then reads whole.
            locked(
                    () -> {
                        readUnread();
                        return Objects.requireNonNullElse(snapshot, before);
                    });
        }
        final ProjectConfig now;
        final Snapshot current = snapshot;
        final boolean same;
        try {
            now = configNow();
            if (current == null || !current.config().commit().equals(now.commit())) {
                return wholeBesideSaves(
                        now, true, current == null ? WholeRead.FIRST : WholeRead.CONFIG);
            }
            if (fromCopy && listsCutShort(current)) {
                // The copy holds lists a read before it cut short: no look tells what they lack,
                // so they are read anew at once, never shown with the fault of a past read.
                return wholeBesideSaves(now, true, WholeRead.COPY_CUT);
            }
            if (fromCopy || parts.changes() != TrackerChanges.NONE) {
                final Looked looked = lookBesideSaves(now, current);
                if (looked != null) {
                    if (looked.named()) {
                        // The look read again what it named; the probe takes what it sees as this
                        // board, so the next poll asks it from here.
                        parts.probe().adoptNext();
                        parts.probe().unchanged(looked.board());
                        return waitsBesideSaves(now, looked.board());
                    }
                    // The item list carries only the board's items: an issue of the project's
                    // repositories it lacks changes only the probe's answer, and is read whole.
                    return parts.probe().unlistedUnchanged(looked.board())
                            ? waitsBesideSaves(now, looked.board())
                            : wholeBesideSaves(now, false, WholeRead.UNLISTED);
                }
                // A tracker that cannot tell what changed has the board read whole, never the probe
                // alone: within its wait after a save, the probe takes another's change for the
                // save's own.
                return wholeBesideSaves(now, true, WholeRead.UNTOLD);
            }
            same = parts.probe().unchanged(current);
        } catch (final RuntimeException e) {
            throw refused(e);
        }
        // The probe saw the change and keeps its tags for the board this read gives.
        return same
                ? waitsBesideSaves(now, current)
                : wholeBesideSaves(now, false, WholeRead.PROBED);
    }

    /**
     * Reads again the state of the issues the board waits on outside the project when the probe
     * says one of their repositories changed, so a blocker closed there offers its issue at this
     * look; the board is the one given when nothing waits or nothing changed.
     */
    private Snapshot waitsBesideSaves(final ProjectConfig now, final Snapshot board) {
        try {
            if (board.waits().isEmpty() || !parts.probe().waitsChanged(board)) {
                return board;
            }
        } catch (final RuntimeException e) {
            throw refused(e);
        }
        final boolean[] reread = new boolean[1];
        final Snapshot read =
                locked(
                        () -> {
                            final Snapshot base = snapshot;
                            if (base == null || base != board) {
                                // A save or a read replaced the board meanwhile: the change the
                                // probe saw stays unread until a look reads the waits again.
                                return base == null ? board : base;
                            }
                            final Snapshot fresh = parts.reader().rewait(now, base);
                            reread[0] = true;
                            if (fresh != base) {
                                hold(fresh, false);
                            }
                            return fresh;
                        });
        if (reread[0]) {
            parts.probe().waitsRead();
        }
        return read;
    }

    /** The items of a board, as the copy holds them, by their ids, the saves held laid over. */
    private Map<String, TrackedItem> known(final Snapshot current) {
        final Map<String, TrackedItem> known = new LinkedHashMap<>();
        current.raw()
                .forEach(
                        (id, item) ->
                                known.put(id, BoardCopyMapping.tracked(item, listedAt.get(id))));
        return saves.overlaid(known);
    }

    /**
     * A look's board and whether the look named any item changed, new or gone.
     *
     * @param board the board with the look's answer folded in
     * @param named whether the answer named any item
     */
    private record Looked(Snapshot board, boolean named) {}

    /**
     * Asks the tracker what changed since the last look, beside the saves: they go on against the
     * board held while the look runs, and what they touched is laid onto its answer.
     *
     * @return the board with the answer folded in, or null when the tracker cannot tell, the answer
     *     names an item new on the board that cannot be read by its issue, or a failed save dropped
     *     the board meanwhile
     */
    private @Nullable Looked lookBesideSaves(final ProjectConfig now, final Snapshot base) {
        final List<Touched.Ids> during = new ArrayList<>();
        synchronized (fold) {
            lookSaves = during;
        }
        final java.time.Instant began = java.time.Instant.now();
        final int held = base.raw().size();
        looking = new ReadProgress(began, 0, held, null, "GitHub", ReadProgress.Kind.LOOK, null);
        final java.util.function.IntConsumer pageRead =
                listedSoFar ->
                        looking =
                                new ReadProgress(
                                        began,
                                        listedSoFar,
                                        Math.max(held, listedSoFar),
                                        null,
                                        "GitHub",
                                        ReadProgress.Kind.LOOK,
                                        null);
        try {
            final String token = tokens.token(now.forge());
            final Map<String, TrackedItem> known = known(base);
            // The board's own read names the project's node id and fields, which its files do
            // not; the commit is the files' own, checked before the look.
            final ProjectConfig read = base.config();
            final TrackerChanges.Changed changed =
                    (parts.changes() instanceof GitHubChanges github
                                    ? github.since(
                                            read,
                                            token,
                                            cursor,
                                            known,
                                            GitHubChanges.plannersFields(base),
                                            pageRead)
                                    : parts.changes().since(read, token, cursor, known))
                            .orElse(null);
            if (changed == null) {
                return null;
            }
            final Map<String, String> added = new LinkedHashMap<>();
            for (final String item : changed.added()) {
                final String issue =
                        parts.changes() instanceof ItemIssues issues ? issues.issueOf(item) : null;
                if (issue == null) {
                    // An item the look cannot name the issue of is read with the whole board.
                    return null;
                }
                added.put(item, issue);
            }
            final Snapshot board = folded(now, changed, added, during);
            return board == null
                    ? null
                    : new Looked(
                            board,
                            !changed.changed().isEmpty()
                                    || !changed.added().isEmpty()
                                    || !changed.gone().isEmpty());
        } finally {
            synchronized (fold) {
                lookSaves = null;
            }
            looking = null;
        }
    }

    /**
     * Folds what changed since the last look into the board held now, under the lock: the gone
     * items dropped, the changed ones read again by id, the new ones by their issues, the saves
     * made during the look that touched any of them read again too, and the sub-issues a parent
     * gained or lost read again, so their parent is right. A board a failed save dropped meanwhile
     * drops the look, and the board is read whole.
     *
     * @param added the new items' issues' node ids by the items' ids
     * @param during what the saves since the look began touched, taken under the lock, so no save
     *     falls between them and the fold
     */
    private @Nullable Snapshot folded(
            final ProjectConfig now,
            final TrackerChanges.Changed changed,
            final Map<String, String> added,
            final List<Touched.Ids> during) {
        final java.util.function.Function<String, ItemIssues.@Nullable Listed> listed =
                parts.changes() instanceof ItemIssues issues ? issues::listed : id -> null;
        final @Nullable Snapshot result =
                this.<@Nullable Snapshot>lockedForRead(
                        lookWaiting,
                        () -> {
                            // A save holds the lock until it has noted what it touched, so every
                            // save before this point is in the list, and every later one is made
                            // against the board folded here.
                            final List<Touched.Ids> saved;
                            synchronized (fold) {
                                lookSaves = null;
                                saved = List.copyOf(during);
                            }
                            final Snapshot base = snapshot;
                            if (base == null) {
                                return null;
                            }
                            if (changed.changed().isEmpty()
                                    && changed.added().isEmpty()
                                    && changed.gone().isEmpty()) {
                                // Nothing changed: the board stays, and its copy is written only
                                // for a
                                // look that moved.
                                boolean moved = !changed.next().equals(cursor) || saves.any();
                                cursor = changed.next();
                                saves.looked(changed, base, saved, listed);
                                moved |= tookTimes(base, listed);
                                final Snapshot read = readUnshown(now, base, saved);
                                if (moved || read != base) {
                                    hold(read, false);
                                }
                                return read;
                            }
                            final Map<String, GitHubGraphQl.ProjectItem> kept =
                                    new LinkedHashMap<>(base.raw());
                            changed.gone().forEach(kept::remove);
                            final Snapshot without =
                                    new Snapshot(
                                            base.board(),
                                            base.items(),
                                            base.config(),
                                            kept,
                                            base.fieldFaults(),
                                            base.fieldNames(),
                                            base.restFieldIds(),
                                            base.waits(),
                                            base.unlisted());
                            final Set<String> items = new LinkedHashSet<>(changed.changed());
                            final Set<String> issues = new LinkedHashSet<>(added.values());
                            issues.addAll(HeldSaves.issuesOf(base, items));
                            final List<Touched.Ids> overlapping = new ArrayList<>();
                            for (final Touched.Ids save : saved) {
                                if (overlaps(base, items, issues, save)) {
                                    overlapping.add(save);
                                }
                            }
                            final List<Touched.Ids> all = new ArrayList<>(overlapping);
                            all.add(new Touched.Ids(issues, items, Set.of(), Set.of()));
                            // A read by id does not tell whether an item is archived; the list
                            // does.
                            final Map<String, Boolean> archived = new LinkedHashMap<>();
                            final Set<String> named = new LinkedHashSet<>(changed.changed());
                            named.addAll(added.keySet());
                            for (final String id : named) {
                                final ItemIssues.Listed shown = listed.apply(id);
                                if (shown != null) {
                                    archived.put(id, shown.archived());
                                }
                            }
                            Snapshot folded =
                                    parts.reader().reread(now, without, merged(all), archived);
                            // A parent gone, new or changed may have gained or lost a sub-issue,
                            // whose own item the list shows unchanged.
                            final Set<String> children = new LinkedHashSet<>();
                            final Set<String> moved = new LinkedHashSet<>(named);
                            moved.addAll(changed.gone());
                            for (final String id : moved) {
                                children.addAll(
                                        movedChildren(base.raw().get(id), folded.raw().get(id)));
                            }
                            if (!children.isEmpty()) {
                                folded =
                                        parts.reader()
                                                .reread(
                                                        now,
                                                        folded,
                                                        new Touched.Ids(
                                                                children, Set.of(), Set.of(),
                                                                Set.of()),
                                                        archived);
                            }
                            cursor = changed.next();
                            saves.looked(changed, folded, saved, listed);
                            tookTimes(folded, listed);
                            folded = readUnshown(now, folded, saved);
                            hold(folded, false);
                            LOG.info(
                                    "Took {} changed, {} new and {} gone items since the last look into"
                                            + " the board, and {} saves made during it",
                                    changed.changed().size(),
                                    added.size(),
                                    changed.gone().size(),
                                    overlapping.size());
                            return folded;
                        });
        if (result == null) {
            // A failed save dropped the board while the look ran; its answer is not laid on.
            LOG.info("The look's answer is dropped, since a failed save dropped the board");
        }
        return result;
    }

    /**
     * Whether a save made during a look touched any item the look named, by any of the names
     * GitHub's calls gave it, so it is read again with them.
     */
    private static boolean overlaps(
            final Snapshot base,
            final Set<String> items,
            final Set<String> issues,
            final Touched.Ids save) {
        if (save.items().stream().anyMatch(items::contains)
                || save.issues().stream().anyMatch(issues::contains)) {
            return true;
        }
        for (final GitHubGraphQl.ProjectItem item : base.raw().values()) {
            final GitHubGraphQl.BoardIssue issue = item.issue();
            final boolean named =
                    items.contains(item.id()) || (issue != null && issues.contains(issue.id()));
            if (named && HeldSaves.matches(BoardCopyMapping.tracked(item), save)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes each item's own time as the look's list showed it, for a board that holds the item as
     * it is now: read again by id when the list named it, or unchanged when it did not.
     *
     * @return whether any time changed
     */
    private boolean tookTimes(
            final Snapshot board,
            final java.util.function.Function<String, ItemIssues.@Nullable Listed> listed) {
        boolean changed = false;
        for (final String id : board.raw().keySet()) {
            final ItemIssues.Listed shown = listed.apply(id);
            if (shown != null && !shown.updatedAt().equals(listedAt.put(id, shown.updatedAt()))) {
                changed = true;
            }
        }
        return changed;
    }

    /** The node ids of the sub-issues an issue gained or lost between two reads of it. */
    private static Set<String> movedChildren(
            final GitHubGraphQl.@Nullable ProjectItem before,
            final GitHubGraphQl.@Nullable ProjectItem after) {
        final Set<String> was = childrenOf(before);
        final Set<String> is = childrenOf(after);
        final Set<String> moved = new LinkedHashSet<>(was);
        moved.addAll(is);
        final Set<String> both = new LinkedHashSet<>(was);
        both.retainAll(is);
        moved.removeAll(both);
        return moved;
    }

    private static Set<String> childrenOf(final GitHubGraphQl.@Nullable ProjectItem item) {
        final GitHubGraphQl.BoardIssue issue = item == null ? null : item.issue();
        final Set<String> children = new LinkedHashSet<>();
        if (issue != null) {
            issue.subIssues().nodes().forEach(ref -> children.add(ref.id()));
        }
        return children;
    }

    /**
     * Reads again by id the saves held of items a look's list does not carry yet, so the board
     * takes GitHub's answer for them at the look instead of at the next whole read; runs under the
     * lock.
     */
    private Snapshot readUnshown(
            final ProjectConfig now, final Snapshot board, final List<Touched.Ids> during) {
        final Map<String, String> unshown = saves.unshown(cursor, during);
        if (unshown.isEmpty()) {
            return board;
        }
        final Snapshot read;
        try {
            read =
                    parts.reader()
                            .reread(
                                    now,
                                    board,
                                    new Touched.Ids(
                                            new LinkedHashSet<>(unshown.keySet()),
                                            Set.of(),
                                            Set.of(),
                                            Set.of()));
        } catch (final RuntimeException e) {
            // The look's own changes are folded and its cursor taken; only these saves wait.
            LOG.warn(
                    "Could not read {} own saves again by id; they stay held: {}",
                    unshown.size(),
                    refused(e).getMessage());
            return board;
        }
        saves.refreshed(read, unshown.values(), cursor);
        LOG.info(
                "Read {} own saves again by id that GitHub's list does not carry yet",
                unshown.size());
        return read;
    }

    /**
     * Keeps the saves held that a whole read of GitHub's list does not show yet: each is read again
     * by its issue, which GitHub answers as it stands, the save included; the others are released.
     */
    private Snapshot keptHeld(final ProjectConfig now, final Snapshot read) {
        final Map<String, String> lagging = saves.lagging(read);
        if (lagging.isEmpty()) {
            return read;
        }
        final Snapshot kept =
                parts.reader()
                        .reread(
                                now,
                                read,
                                new Touched.Ids(
                                        new LinkedHashSet<>(lagging.keySet()),
                                        Set.of(),
                                        Set.of(),
                                        Set.of()));
        saves.refreshed(kept, lagging.values(), cursor);
        LOG.info("Read {} own saves again that GitHub's list did not show yet", lagging.size());
        return kept;
    }

    /**
     * Reads the whole project outside the save lock, so a save goes to GitHub at once against the
     * board held; what the saves meanwhile touched is read again on top of the result before it
     * becomes the board. A second whole read asked for meanwhile waits for this one.
     *
     * @param now what the project is read with
     * @param whole whether the probe forgets what it learned and takes this read's board after it,
     *     as a whole read outside the probe does
     * @param why why the board is read whole
     */
    private Snapshot wholeBesideSaves(
            final ProjectConfig now, final boolean whole, final WholeRead why) {
        final CompletableFuture<Snapshot> mine;
        final boolean began;
        synchronized (fold) {
            final CompletableFuture<Snapshot> running = inFlight;
            began = running == null;
            if (running != null) {
                mine = running;
            } else {
                mine = new CompletableFuture<>();
                inFlight = mine;
                pending.clear();
            }
        }
        if (!began) {
            // A reader waits for the read it joins as long as GitHub keeps answering it.
            return awaitRead(mine, false);
        }
        final long started = System.nanoTime();
        final java.time.Instant since = java.time.Instant.now();
        LOG.info("Reading the board whole: {}", why.words);
        try {
            if (whole) {
                parts.probe().forget();
            }
            // Beside the saves, so a wait for GitHub's rate limit holds none of them.
            final Snapshot read = parts.reader().read(now, true);
            parts.probe().readWhole();
            LOG.info(
                    "Read the board whole in {} ms: {}",
                    millis(started, System.nanoTime()),
                    why.words);
            finishing =
                    new ReadProgress(since, read.raw().size(), read.raw().size(), null, "GitHub");
            final Snapshot fresh =
                    lockedForRead(
                            wholeWaiting,
                            () -> {
                                final List<Touched.Ids> saved;
                                synchronized (fold) {
                                    saved = List.copyOf(pending);
                                    pending.clear();
                                }
                                Snapshot result = read;
                                if (!saved.isEmpty()) {
                                    result =
                                            parts.reader().reread(configNow(), read, merged(saved));
                                    LOG.info(
                                            "Took {} saves made during the whole read into its"
                                                    + " board",
                                            saved.size());
                                }
                                result = keptHeld(configNow(), result);
                                hold(result, true);
                                synchronized (fold) {
                                    inFlight = null;
                                }
                                if (whole) {
                                    settle(result, false);
                                }
                                return result;
                            });
            finishing = null;
            mine.complete(fresh);
            return fresh;
        } catch (final RuntimeException e) {
            finishing = null;
            synchronized (fold) {
                if (inFlight == mine) {
                    inFlight = null;
                }
            }
            final RuntimeException refusal = refused(e);
            mine.completeExceptionally(refusal);
            throw refusal;
        } catch (final Error e) {
            // Readers wait for this read without a bound, so it ends for them however it ends.
            finishing = null;
            synchronized (fold) {
                if (inFlight == mine) {
                    inFlight = null;
                }
            }
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /**
     * Waits for the whole read under way: a save as long as it waits for the lock, a reader until
     * the read ends, which its own silence limit bounds.
     */
    private Snapshot awaitRead(final CompletableFuture<Snapshot> read, final boolean save) {
        try {
            return save ? read.get(lockWait.toMillis(), TimeUnit.MILLISECONDS) : read.get();
        } catch (final TimeoutException e) {
            throw new StoreException.Busy(config.own());
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof RuntimeException refused) {
                throw refused;
            }
            throw new IllegalStateException(e.getCause());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException.Busy(config.own());
        }
    }

    private static Touched.Ids merged(final List<Touched.Ids> all) {
        final Set<String> issues = new LinkedHashSet<>();
        final Set<String> items = new LinkedHashSet<>();
        final Set<String> numbered = new LinkedHashSet<>();
        final Set<Long> databaseIds = new LinkedHashSet<>();
        for (final Touched.Ids one : all) {
            issues.addAll(one.issues());
            items.addAll(one.items());
            numbered.addAll(one.numbered());
            databaseIds.addAll(one.databaseIds());
        }
        return new Touched.Ids(issues, items, numbered, databaseIds);
    }

    private Snapshot read() {
        final ProjectConfig now = configNow();
        final Snapshot current = snapshot;
        if (current == null || !current.config().commit().equals(now.commit())) {
            return readWhole(now, current == null ? WholeRead.FIRST : WholeRead.CONFIG);
        }
        if (parts.probe().unchanged(current)) {
            return current;
        }
        // The probe saw the change and keeps its tags for the board this read gives.
        final Snapshot fresh = keptHeld(now, timedRead(now, WholeRead.PROBED));
        hold(fresh, true);
        return fresh;
    }

    /**
     * Reads the whole project outside the probe, which forgets what it learned, so its next look
     * never takes tags from before this read for the board it gives.
     */
    private Snapshot readWhole(final ProjectConfig now, final WholeRead why) {
        parts.probe().forget();
        final Snapshot fresh = keptHeld(now, timedRead(now, why));
        hold(fresh, true);
        settle(fresh, false);
        return fresh;
    }

    private Snapshot timedRead(final ProjectConfig now, final WholeRead why) {
        final long started = System.nanoTime();
        LOG.info("Reading the board whole: {}", why.words);
        final Snapshot read = parts.reader().read(now);
        LOG.info(
                "Read the board whole in {} ms: {}", millis(started, System.nanoTime()), why.words);
        return read;
    }

    /**
     * Has the change probe look at once and take what it sees as the board just read or saved: the
     * probe holds no tags after a whole read, and a save's own change is in the board already, so
     * its next look would otherwise read the whole project for them. Taken at once, a change
     * another makes after it is seen at the next look.
     */
    private void settle(final Snapshot fresh, final boolean saved) {
        if (saved) {
            parts.probe().adoptSave();
        } else {
            parts.probe().adoptNext();
        }
        if (parts.probe().pace().hold() != null) {
            // GitHub asked to wait; the look waits too, and the next poll's look takes this board.
            return;
        }
        try {
            looks.execute(
                    () -> {
                        try {
                            parts.probe().unchanged(fresh);
                        } catch (final RuntimeException e) {
                            // The next look then sees the change and reads whole, as before.
                            LOG.info(
                                    "The change check after a read failed: {}",
                                    e.getClass().getSimpleName());
                        }
                    });
        } catch (final RuntimeException e) {
            LOG.info(
                    "The change check after a read was not started: {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * The board with what the pace says of GitHub's rate limit among its faults; every board the
     * store answers goes through here, so the window is told the pace with each.
     */
    private Board served(final Board board) {
        final RepositoryLabels labels = parts.labels();
        if (labels.kept() && !labels.everRead()) {
            // The project's first read, by whichever way the board came, checks its labels once.
            readLabels(board.names());
        }
        final List<String> faults = new ArrayList<>(labels.faults(board.names()));
        faults.addAll(parts.probe().pace().faults());
        faults.addAll(unreadFaults());
        return board.withRepositories(labels.warnings(board.names()), faults);
    }

    /**
     * Reads every repository's labels anew with the person's token for GitHub alone; a read that
     * cannot be made leaves each repository a fault until the next.
     */
    private void readLabels(final List<String> repositories) {
        final String token;
        try {
            token = tokens.token(config.forge());
        } catch (final RuntimeException e) {
            parts.labels().unread(repositories, "no token for GitHub is at hand");
            return;
        }
        parts.labels().read(token, config.project().owner(), repositories);
    }

    @Override
    public boolean keepsRepositoryLabels() {
        return parts.labels().kept();
    }

    /**
     * Reads one of the project's repositories' labels anew with the person's token for GitHub
     * alone, and answers the board with them; a repository the board does not name is read nothing
     * for.
     */
    @Override
    public Board rereadLabels(final String repository) {
        if (!parts.labels().kept()) {
            throw new UnsupportedOperationException(NOT_OFFERED);
        }
        final Board board = board();
        if (!board.names().contains(repository)) {
            return board;
        }
        try {
            parts.labels()
                    .reread(tokens.token(config.forge()), config.project().owner(), repository);
        } catch (final RuntimeException e) {
            // A refusal of the login or its rate limit is answered as any other call's.
            throw refused(e);
        }
        return board();
    }

    /**
     * Makes a correction's calls on one repository's labels under the lock, with the person's token
     * for GitHub alone; the board held is not read again, since no item changed.
     */
    @Override
    public Board correctLabels(final String repository, final List<RepositoryWarning.Call> calls) {
        if (!parts.labels().kept()) {
            throw new UnsupportedOperationException(NOT_OFFERED);
        }
        final Snapshot now;
        try {
            now =
                    lockedForSave(
                            () -> {
                                final Snapshot held = current();
                                parts.labels()
                                        .correct(
                                                tokens.token(config.forge()),
                                                config.project().owner(),
                                                repository,
                                                calls);
                                return held;
                            });
        } catch (final RuntimeException e) {
            // A refusal of the login or its rate limit is answered as any other call's.
            throw refused(e);
        }
        return served(now.board());
    }

    /**
     * Reads again the issues a stage result names onto the board held, so the result is written
     * into each as it stands now; a store that holds no board, or one without such an issue, reads
     * the board whole.
     */
    private Snapshot withResultsIssues(final StageResult result) {
        final Snapshot held = snapshot;
        if (held == null) {
            return readWhole(configNow(), WholeRead.FIRST);
        }
        final Set<String> issues = new LinkedHashSet<>();
        for (final StageResult.ForIssue issue : result.issues()) {
            final ItemView item = held.items().get(issue.id());
            if (item == null) {
                // An issue the board held does not know may be new on GitHub: read it all.
                return readWhole(configNow(), WholeRead.RESULT);
            }
            issues.add(item.issueId());
        }
        final Snapshot fresh =
                merged(
                        parts.reader()
                                .fetch(
                                        configNow(),
                                        held,
                                        new Touched.Ids(issues, Set.of(), Set.of(), Set.of()),
                                        Map.of()),
                        null);
        return fresh != null ? fresh : readWhole(configNow(), WholeRead.RESULT);
    }

    /**
     * Reads the whole board after a write that completed, never asking whether it changed: a change
     * check may not see a field value written. A failure here says a part was written, so the
     * window is never told nothing was and a stage result is never handed in and saved twice.
     */
    private Snapshot readAfterWriting() {
        return CallTimes.phase("reread", this::readAfterWritingTimed);
    }

    private Snapshot readAfterWritingTimed() {
        try {
            final Snapshot held = snapshot;
            if (held == null) {
                return readWhole(configNow(), WholeRead.WRITTEN);
            }
            final Touched.Ids written = touched.ids();
            // GitHub's lists show the save a moment after it; until then it is held.
            final Snapshot fresh =
                    merged(parts.reader().fetch(configNow(), held, written, Map.of()), written);
            if (fresh == null) {
                return readWhole(configNow(), WholeRead.WRITTEN);
            }
            settle(fresh, true);
            return fresh;
        } catch (final RuntimeException e) {
            throw new BoardWriter.AfterWriting(e);
        }
    }

    /**
     * Reads the whole board after new issues were made; a failure is refused as partly saved,
     * naming each repository written and, in its words, each reference made, so the window never
     * takes the items for unmade and hands them in again.
     *
     * @param made the references of the issues made, in order
     */
    private Snapshot readAfterMaking(final List<String> made) {
        try {
            return readAfterWriting();
        } catch (final BoardWriter.AfterWriting after) {
            final StoreException refusal =
                    parts.refusals().refusal(config.own(), after, tokenToScrub());
            final Set<String> repositories = new LinkedHashSet<>();
            for (final String reference : made) {
                repositories.add(reference.substring(0, reference.lastIndexOf('#')));
            }
            throw new StoreException.PartlySaved(
                    config.own(),
                    List.copyOf(repositories),
                    refusal.getMessage()
                            + "; GitHub holds "
                            + String.join(", ", made)
                            + " from this save");
        }
    }

    /**
     * Runs a write under the lock. A write that fails or comes back stale keeps the board held:
     * what it touched, and the item a stale refusal names, is read again by id onto it, so no call
     * waits behind a whole read; only a board that cannot be read again so is forgotten.
     */
    private Snapshot writing(final Supplier<Snapshot> work) {
        final CompletableFuture<Snapshot> running;
        synchronized (fold) {
            running = inFlight;
        }
        if (snapshot == null && running != null) {
            // No board to save against yet: the whole read under way gives it, not a second one.
            awaitRead(running, true);
        }
        return lockedForSave(
                () -> {
                    readUnread();
                    touched.clear();
                    try {
                        return work.get();
                    } catch (final StoreException.StaleRevision e) {
                        final Touched.Ids written = touched.ids();
                        final String changed = e.changed();
                        final Snapshot held = snapshot;
                        if (changed != null && held != null && !held.items().containsKey(changed)) {
                            // An item the board held does not name: only a whole read shows it.
                            drop();
                            throw staleNamingTheBoardNow(changed);
                        }
                        final Snapshot kept =
                                readAgainById(
                                        written, changed == null ? List.of() : List.of(changed));
                        if (changed == null) {
                            throw e;
                        }
                        // The writer named the board the window showed: the board read again is
                        // named instead, and a board not read again names none to save against.
                        throw new StoreException.StaleRevision(
                                kept == null ? UNREAD : kept.board().revision().value(), changed);
                    } catch (final StoreException.Changed e) {
                        // The board shows the changed targets as GitHub holds them now.
                        readAgainById(touched.ids(), e.ids());
                        throw e;
                    } catch (final BoardWriter.AfterWriting e) {
                        // The read after the writes failed: the board held keeps every other item
                        // and names what was written unread until the next look or save reads it.
                        final Touched.Ids written = touched.ids();
                        saves.forget(written);
                        if (written.none()) {
                            // Nothing says what was written, so only a whole read shows it.
                            drop();
                        } else if (snapshot != null) {
                            unread = joined(unread, written);
                        }
                        throw e;
                    } catch (final RuntimeException e) {
                        readAgainById(touched.ids(), List.of());
                        throw e;
                    } finally {
                        synchronized (fold) {
                            if (inFlight != null) {
                                pending.add(touched.ids());
                            }
                            final List<Touched.Ids> during = lookSaves;
                            if (during != null) {
                                during.add(touched.ids());
                            }
                        }
                    }
                });
    }

    /** Reads by id what earlier saves wrote and GitHub was not asked for again after them. */
    private void readUnread() {
        final Touched.Ids pending = unread;
        if (pending == null) {
            return;
        }
        unread = null;
        readAgainById(pending, List.of());
    }

    private static Touched.Ids joined(final Touched.@Nullable Ids one, final Touched.Ids two) {
        if (one == null) {
            return two;
        }
        final Set<String> issues = new LinkedHashSet<>(one.issues());
        issues.addAll(two.issues());
        final Set<String> items = new LinkedHashSet<>(one.items());
        items.addAll(two.items());
        final Set<String> numbered = new LinkedHashSet<>(one.numbered());
        numbered.addAll(two.numbered());
        final Set<Long> databaseIds = new LinkedHashSet<>(one.databaseIds());
        databaseIds.addAll(two.databaseIds());
        return new Touched.Ids(issues, items, numbered, databaseIds);
    }

    /** The board's fault naming the items saved and not read again, by the board's ids. */
    private List<String> unreadFaults() {
        final Touched.Ids pending = unread;
        final Snapshot held = snapshot;
        if (pending == null || held == null) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        held.items()
                .forEach(
                        (id, item) -> {
                            if (pending.issues().contains(item.issueId())
                                    || pending.items().contains(item.itemId())) {
                                names.add(id);
                            }
                        });
        if (names.isEmpty()) {
            names.addAll(pending.numbered());
        }
        return List.of(
                UNREAD_SAVED
                        + (names.isEmpty() ? "items of a save" : String.join(", ", names))
                        + "; they are read again at the next look or save");
    }

    /**
     * Answers a refusal for an item the board held does not name with the revision GitHub holds
     * now, read anew, so the window is never told the revision it showed is current.
     */
    private StoreException.StaleRevision staleNamingTheBoardNow(final String changed) {
        try {
            return new StoreException.StaleRevision(read().board().revision().value(), changed);
        } catch (final RuntimeException e) {
            // The board read next tells the window; the refusal names no board to save against.
            return new StoreException.StaleRevision(UNREAD, changed);
        }
    }

    /**
     * Reads again by id, onto the board held, what a failed or stale save touched and the item a
     * stale refusal names, so the board shows what GitHub holds of them; a save that touched
     * nothing leaves the board as it is. A read that fails forgets the board, which is then read
     * whole.
     *
     * @param written what the save's writes touched
     * @param changed the board's ids of the items a person changed
     * @return the board held afterwards, or null when it was forgotten
     */
    private @Nullable Snapshot readAgainById(
            final Touched.Ids written, final List<String> changed) {
        final Snapshot held = snapshot;
        if (held == null) {
            return null;
        }
        final Set<String> issues = new LinkedHashSet<>(written.issues());
        final Set<String> items = new LinkedHashSet<>(written.items());
        for (final String id : changed) {
            final ItemView item = held.items().get(id);
            if (item != null) {
                issues.add(item.issueId());
                items.add(item.itemId());
            }
        }
        if (issues.isEmpty()
                && items.isEmpty()
                && written.numbered().isEmpty()
                && written.databaseIds().isEmpty()) {
            return held;
        }
        try {
            final boolean wrote = !written.none();
            // A part a save wrote is held until GitHub's list shows it, as after a save that ended;
            // an earlier save of the same items stays held until this one replaces it.
            final Snapshot fresh =
                    merged(
                            parts.reader()
                                    .fetch(
                                            configNow(),
                                            held,
                                            new Touched.Ids(
                                                    issues,
                                                    items,
                                                    written.numbered(),
                                                    written.databaseIds()),
                                            Map.of()),
                            wrote ? written : null);
            if (fresh == null) {
                return null;
            }
            if (wrote) {
                settle(fresh, true);
            }
            return fresh;
        } catch (final RuntimeException e) {
            LOG.info(
                    "The items a failed save touched could not be read again, the board is read"
                            + " whole: {}",
                    refused(e).getMessage());
            drop();
            return null;
        }
    }

    /** Asks for the project's files as they stand now; a failure is the read's refusal. */
    private ProjectConfig configNow() {
        final ProjectConfig now = again.get();
        config = now;
        return now;
    }

    private Snapshot current() {
        final Snapshot current = snapshot;
        return current != null ? current : read();
    }

    /**
     * Runs a read's part under the lock, counted as waiting until it holds the lock, so its
     * progress tells that it waits while a save holds the project.
     */
    private <T extends @Nullable Object> T lockedForRead(
            final java.util.concurrent.atomic.AtomicInteger waits, final Supplier<T> work) {
        final java.util.concurrent.atomic.AtomicBoolean waiting =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        waits.incrementAndGet();
        try {
            return locked(
                    () -> {
                        if (waiting.getAndSet(false)) {
                            waits.decrementAndGet();
                        }
                        return work.get();
                    });
        } finally {
            if (waiting.getAndSet(false)) {
                waits.decrementAndGet();
            }
        }
    }

    /**
     * Runs a save's or a comment's part under the lock, counted as holding it while it runs, so a
     * read waiting meanwhile tells that it waits for a save.
     */
    private <T extends @Nullable Object> T lockedForSave(final Supplier<T> work) {
        return locked(
                () -> {
                    savesHolding.incrementAndGet();
                    try {
                        return work.get();
                    } finally {
                        savesHolding.decrementAndGet();
                    }
                });
    }

    private <T extends @Nullable Object> T locked(final Supplier<T> work) {
        refuseRetired();
        final boolean held;
        final long waiting = System.nanoTime();
        try {
            held = lock.tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException.Busy(config.own());
        }
        CallTimes.waited("the project", System.nanoTime() - waiting);
        if (!held) {
            throw new StoreException.Busy(config.own());
        }
        try {
            refuseRetired();
            return work.get();
        } catch (final RuntimeException e) {
            throw refused(e);
        } finally {
            lock.unlock();
        }
    }

    /** A failure as the store refuses it, GitHub's words scrubbed of the token. */
    private RuntimeException refused(final RuntimeException e) {
        if (e instanceof UnsupportedOperationException || e instanceof TextEdit.Refused) {
            // Refused before any call, in fixed words: the caller's to correct, not GitHub's.
            return e;
        }
        final StoreException refusal =
                e instanceof StoreException known
                        ? known
                        : parts.refusals().refusal(config.own(), e, tokenToScrub());
        if (refusal instanceof StoreException.NoForgeAccess) {
            // Read again next time, so the window is told the refusal, not the board kept.
            saves.clear();
            drop();
        }
        return refusal;
    }

    /**
     * Holds a board read or saved, and writes its copy at once, so the next start shows it.
     *
     * @param fresh the board
     * @param whole whether GitHub was read whole for it, which ends a board taken from the copy
     */
    private void hold(final Snapshot fresh, final boolean whole) {
        synchronized (holding) {
            holdNow(fresh, whole);
        }
    }

    /**
     * Lays what a read by id found onto the newest board held, in one step with holding it, the
     * saves held and the copy offered, so neither a merge of other items nor a copy is lost.
     *
     * @param delta what was read
     * @param written what a save wrote, held until GitHub's list shows it; null for no save
     * @return the board held now, or null when the board was forgotten or read with other project
     *     files meanwhile, which the caller then reads whole
     */
    private @Nullable Snapshot merged(final Reread delta, final Touched.@Nullable Ids written) {
        synchronized (holding) {
            final Snapshot newest = snapshot;
            if (newest == null || !newest.config().commit().equals(delta.read().commit())) {
                return null;
            }
            final Snapshot fresh = parts.reader().apply(newest, delta);
            if (written != null) {
                saves.hold(fresh, written, cursor, newest);
            }
            holdNow(fresh, false);
            return fresh;
        }
    }

    private void holdNow(final Snapshot fresh, final boolean whole) {
        snapshot = fresh;
        // Only the items held keep their times; one gone and back is compared anew.
        listedAt.keySet().retainAll(fresh.raw().keySet());
        if (whole) {
            unread = null;
            // A look's cursor from before a whole read does not describe its board.
            fromCopy = false;
            cursor = ChangeCursor.NONE;
        }
        final BoardCopies copies = parts.copies();
        if (copies == null) {
            return;
        }
        // Taken now, so the copy describes this board even when it is written after the next.
        final ChangeCursor at = cursor;
        final List<TrackedItem> held = saves.items();
        final Map<String, Instant> listed = Map.copyOf(listedAt);
        final boolean taken =
                copyWriter.offer(
                        () -> {
                            try {
                                final BoardCopy copy =
                                        parts.reader().toCopy(fresh, at, held, listed);
                                if (copy == null) {
                                    copies.remove(BoardCopyMapping.project(fresh.config()));
                                } else {
                                    copies.write(copy);
                                }
                            } catch (final RuntimeException e) {
                                // A copy that may be older than the board held is never shown at
                                // the next
                                // start.
                                LOG.warn(
                                        "The board's copy could not be written: {}",
                                        e.getClass().getSimpleName());
                                removeCopyNow(copies);
                            }
                        });
        if (!taken) {
            LOG.info("The board's copy is not written, since the store was retired");
        }
    }

    /** Forgets the board held and removes its copy, so neither comes back at the next read. */
    private void drop() {
        snapshot = null;
        unread = null;
        listedAt.clear();
        fromCopy = false;
        cursor = ChangeCursor.NONE;
        removeCopy();
    }

    private void removeCopy() {
        final BoardCopies copies = parts.copies();
        if (copies == null) {
            return;
        }
        // Ordered with the writes, so a copy removed never comes back from one waiting before it.
        copyWriter.offer(() -> removeCopyNow(copies));
    }

    private void removeCopyNow(final BoardCopies copies) {
        try {
            copies.remove(BoardCopyMapping.project(config));
        } catch (final RuntimeException e) {
            LOG.warn("The board's copy could not be removed: {}", e.getClass().getSimpleName());
        }
    }

    private String tokenToScrub() {
        try {
            return tokens.token(config.forge());
        } catch (final RuntimeException e) {
            // No token was taken, so none can stand in the words.
            return "";
        }
    }

    private void refuseRetired() {
        if (retired) {
            throw new StoreException.Unreadable(
                    config.own(), "the project was given anew while this read waited");
        }
    }
}
