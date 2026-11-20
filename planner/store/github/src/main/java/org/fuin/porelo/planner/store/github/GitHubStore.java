package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageMetrics;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * A project's planning store in GitHub Issues: one lock, the snapshot every answer comes from, and
 * its parts, each behind its own interface, so they are built and tested apart.
 */
public final class GitHubStore implements PlanningStore {

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

    private volatile ProjectConfig config;

    private volatile @Nullable Snapshot snapshot;

    private volatile boolean retired;

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
        this.config = config;
        this.again = again;
        this.parts = parts;
        this.tokens = tokens;
        this.lockWait = lockWait;
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
        return served(locked(() -> readWhole(configNow())).board());
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
                return served(locked(this::read).board());
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
                            return readAfterWriting();
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
                            return readAfterWriting();
                        });
        final BoardWriter.IdeaIds saved = Objects.requireNonNull(ids[0]);
        return new NewIdea.Added(saved.story(), saved.issues(), served(after.board()));
    }

    @Override
    public Board saveResult(final StageResult result) {
        return served(
                writing(
                                () -> {
                                    // A stage runs for minutes, so it is saved on the project as it
                                    // stands
                                    // now.
                                    final Snapshot now = parts.reader().read(configNow());
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
        return fresh;
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
                board.owner());
    }

    /**
     * Reads the whole board after a write that completed, never asking whether it changed: a change
     * check may not see a field value written. A failure here says a part was written, so the
     * window is never told nothing was and a stage result is never handed in and saved twice.
     */
    private Snapshot readAfterWriting() {
        try {
            return readWhole(configNow());
        } catch (final RuntimeException e) {
            throw new BoardWriter.AfterWriting(e);
        }
    }

    /**
     * Runs a write under the lock; any failure forgets the board kept, as a part may be written.
     */
    private Snapshot writing(final Supplier<Snapshot> work) {
        return locked(
                () -> {
                    try {
                        return work.get();
                    } catch (final StoreException.StaleRevision e) {
                        snapshot = null;
                        throw staleNamingTheBoardNow(e);
                    } catch (final RuntimeException e) {
                        // The next board is read from GitHub, never the one from before the save.
                        snapshot = null;
                        throw e;
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
        } catch (final UnsupportedOperationException e) {
            throw e;
        } catch (final RuntimeException e) {
            final StoreException refusal =
                    e instanceof StoreException known
                            ? known
                            : parts.refusals().refusal(config.own(), e, tokenToScrub());
            if (refusal instanceof StoreException.NoForgeAccess) {
                // Read again next time, so the window is told the refusal, not the board kept.
                snapshot = null;
            }
            throw refusal;
        } finally {
            lock.unlock();
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
