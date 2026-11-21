package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageMetrics;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.Writes;
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
     * The parts the store is made of; each one left out does nothing or refuses.
     *
     * @param reader reads the project
     * @param writer writes to it
     * @param probe tells whether it changed
     * @param refusals turns a failure into the store's refusal
     * @param metrics gives a stage's numbers
     * @param storyStates derives a story's state
     */
    public record Parts(
            BoardReader reader,
            BoardWriter writer,
            ChangeProbe probe,
            AccessRefusals refusals,
            StageMetrics metrics,
            StoryStates storyStates) {

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
                    storyStates);
        }

        /**
         * The same parts with another writer.
         *
         * @param other the writer
         * @return the parts
         */
        public Parts writer(final BoardWriter other) {
            return new Parts(reader, other, probe, refusals, metrics, storyStates);
        }

        /**
         * The same parts with another change probe.
         *
         * @param other the probe
         * @return the parts
         */
        public Parts probe(final ChangeProbe other) {
            return new Parts(reader, writer, other, refusals, metrics, storyStates);
        }

        /**
         * The same parts with other refusals.
         *
         * @param other the refusals
         * @return the parts
         */
        public Parts refusals(final AccessRefusals other) {
            return new Parts(reader, writer, probe, other, metrics, storyStates);
        }

        /**
         * The same parts with another source of stage numbers.
         *
         * @param other the source
         * @return the parts
         */
        public Parts metrics(final StageMetrics other) {
            return new Parts(reader, writer, probe, refusals, other, storyStates);
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
    }

    @Override
    public Board board() {
        final Snapshot current = snapshot;
        return current != null ? served(current.board()) : refresh();
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
        return served(wholeBesideSaves(now, true).board());
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

    /**
     * Writes a comment under the lock; the board shows no comment, so it is not read again and the
     * board kept stays.
     */
    @Override
    public void comment(final String issue, final String text) {
        locked(
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
    }

    @Override
    public java.util.Optional<org.fuin.porelo.planner.store.ReadProgress> reading() {
        return parts.reader().progress();
    }

    /**
     * A poll: asks whether the project changed and reads it whole only when it did, beside the
     * saves, never holding them for the whole read.
     */
    private Snapshot pollBesideSaves() {
        refuseRetired();
        final ProjectConfig now;
        final Snapshot current = snapshot;
        final boolean same;
        try {
            now = configNow();
            if (current == null || !current.config().commit().equals(now.commit())) {
                return wholeBesideSaves(now, true);
            }
            same = parts.probe().unchanged(current);
        } catch (final RuntimeException e) {
            throw refused(e);
        }
        // The probe saw the change and keeps its tags for the board this read gives.
        return same ? current : wholeBesideSaves(now, false);
    }

    /**
     * Reads the whole project outside the save lock, so a save goes to GitHub at once against the
     * board held; what the saves meanwhile touched is read again on top of the result before it
     * becomes the board. A second whole read asked for meanwhile waits for this one.
     *
     * @param now what the project is read with
     * @param whole whether the probe forgets what it learned and takes this read's board after it,
     *     as a whole read outside the probe does
     */
    private Snapshot wholeBesideSaves(final ProjectConfig now, final boolean whole) {
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
            return awaitRead(mine);
        }
        try {
            if (whole) {
                parts.probe().forget();
            }
            final Snapshot read = parts.reader().read(now);
            final Snapshot fresh =
                    locked(
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
                                snapshot = result;
                                synchronized (fold) {
                                    inFlight = null;
                                }
                                if (whole) {
                                    settle(result, false);
                                }
                                return result;
                            });
            mine.complete(fresh);
            return fresh;
        } catch (final RuntimeException e) {
            synchronized (fold) {
                if (inFlight == mine) {
                    inFlight = null;
                }
            }
            final RuntimeException refusal = refused(e);
            mine.completeExceptionally(refusal);
            throw refusal;
        }
    }

    /** Waits for the whole read under way, as long as a save waits for the lock. */
    private Snapshot awaitRead(final CompletableFuture<Snapshot> read) {
        try {
            return read.get(lockWait.toMillis(), TimeUnit.MILLISECONDS);
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
            return readWhole(now);
        }
        if (parts.probe().unchanged(current)) {
            return current;
        }
        // The probe saw the change and keeps its tags for the board this read gives.
        final Snapshot fresh = parts.reader().read(now);
        snapshot = fresh;
        return fresh;
    }

    /**
     * Reads the whole project outside the probe, which forgets what it learned, so its next look
     * never takes tags from before this read for the board it gives.
     */
    private Snapshot readWhole(final ProjectConfig now) {
        parts.probe().forget();
        final Snapshot fresh = parts.reader().read(now);
        snapshot = fresh;
        settle(fresh, false);
        return fresh;
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
        final List<String> pace = parts.probe().pace().faults();
        if (pace.isEmpty()) {
            return board;
        }
        final List<String> faults = new java.util.ArrayList<>(board.faults());
        faults.addAll(pace);
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                board.issues(),
                board.work(),
                board.repositories(),
                faults,
                board.owner(),
                board.outside());
    }

    /**
     * Reads again the issues a stage result names onto the board held, so the result is written
     * into each as it stands now; a store that holds no board, or one without such an issue, reads
     * the board whole.
     */
    private Snapshot withResultsIssues(final StageResult result) {
        final Snapshot held = snapshot;
        if (held == null) {
            return readWhole(configNow());
        }
        final Set<String> issues = new LinkedHashSet<>();
        for (final StageResult.ForIssue issue : result.issues()) {
            final ItemView item = held.items().get(issue.id());
            if (item == null) {
                // An issue the board held does not know may be new on GitHub: read it all.
                return readWhole(configNow());
            }
            issues.add(item.issueId());
        }
        final Snapshot fresh =
                parts.reader()
                        .reread(
                                configNow(),
                                held,
                                new Touched.Ids(issues, Set.of(), Set.of(), Set.of()));
        snapshot = fresh;
        return fresh;
    }

    /**
     * Reads the whole board after a write that completed, never asking whether it changed: a change
     * check may not see a field value written. A failure here says a part was written, so the
     * window is never told nothing was and a stage result is never handed in and saved twice.
     */
    private Snapshot readAfterWriting() {
        try {
            final Snapshot held = snapshot;
            if (held == null) {
                return readWhole(configNow());
            }
            final Snapshot fresh = parts.reader().reread(configNow(), held, touched.ids());
            snapshot = fresh;
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
     * Runs a write under the lock; any failure forgets the board kept, as a part may be written.
     */
    private Snapshot writing(final Supplier<Snapshot> work) {
        final CompletableFuture<Snapshot> running;
        synchronized (fold) {
            running = inFlight;
        }
        if (snapshot == null && running != null) {
            // No board to save against yet: the whole read under way gives it, not a second one.
            awaitRead(running);
        }
        return locked(
                () -> {
                    touched.clear();
                    try {
                        return work.get();
                    } catch (final StoreException.StaleRevision e) {
                        snapshot = null;
                        throw staleNamingTheBoardNow(e);
                    } catch (final RuntimeException e) {
                        // The next board is read from GitHub, never the one from before the save.
                        snapshot = null;
                        throw e;
                    } finally {
                        synchronized (fold) {
                            if (inFlight != null) {
                                pending.add(touched.ids());
                            }
                        }
                    }
                });
    }

    /**
     * Answers a refusal for an item a person changed with the revision GitHub holds now, read anew,
     * so the window is never told the revision it showed is current.
     */
    private StoreException.StaleRevision staleNamingTheBoardNow(
            final StoreException.StaleRevision stale) {
        final String changed = stale.changed();
        if (changed == null) {
            return stale;
        }
        try {
            return new StoreException.StaleRevision(read().board().revision().value(), changed);
        } catch (final RuntimeException e) {
            // The board read next tells the window; the refusal stands as it is.
            return stale;
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

    private Snapshot locked(final Supplier<Snapshot> work) {
        refuseRetired();
        final boolean held;
        try {
            held = lock.tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException.Busy(config.own());
        }
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
            snapshot = null;
        }
        return refusal;
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
