package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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

    private final ProjectConfig config;

    private final Parts parts;

    private final Duration lockWait;

    private final TokenSource tokens;

    private final ReentrantLock lock = new ReentrantLock();

    private volatile @Nullable Snapshot snapshot;

    private volatile boolean retired;

    /**
     * Makes the store; nothing is read until the board is asked for.
     *
     * @param config what the project is read with
     * @param parts its parts
     * @param tokens the person's token, asked for only to scrub it from a refusal's words
     * @param lockWait how long a call waits for another save or read before it is refused as busy
     */
    public GitHubStore(
            final ProjectConfig config,
            final Parts parts,
            final TokenSource tokens,
            final Duration lockWait) {
        this.config = config;
        this.parts = parts;
        this.tokens = tokens;
        this.lockWait = lockWait;
    }

    @Override
    public Board board() {
        final Snapshot current = snapshot;
        return current != null ? current.board() : refresh();
    }

    @Override
    public Board refresh() {
        return locked(this::read).board();
    }

    @Override
    public Board save(final Revision revision, final Writes writes) {
        return writing(
                        () -> {
                            parts.writer().save(current(), revision, writes);
                            return readAfterWriting();
                        })
                .board();
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
        return new NewIssue.Added(Objects.requireNonNull(id[0]), after.board());
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
        return new NewIdea.Added(saved.story(), saved.issues(), after.board());
    }

    @Override
    public Board saveResult(final StageResult result) {
        return writing(
                        () -> {
                            // A stage runs for minutes, so it is saved on the project as it stands
                            // now.
                            final Snapshot now = parts.reader().read(config);
                            for (final StageResult.ForIssue issue : result.issues()) {
                                now.board()
                                        .issue(issue.id())
                                        .orElseThrow(
                                                () -> new StoreException.NoSuchIssue(issue.id()));
                            }
                            final StageEntry stage =
                                    result.issues().size() == 1
                                            ? parts.metrics()
                                                    .stage(result.issues().get(0).id(), result)
                                                    .orElse(null)
                                            : null;
                            parts.writer().saveResult(now, result, stage);
                            return readAfterWriting();
                        })
                .board();
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
        final Snapshot current = snapshot;
        if (current != null && parts.probe().unchanged(current)) {
            return current;
        }
        final Snapshot fresh = parts.reader().read(config);
        snapshot = fresh;
        return fresh;
    }

    /**
     * Reads the board after a write that completed; a failure here says a part was written, so the
     * window is never told nothing was and a stage result is never handed in and saved twice.
     */
    private Snapshot readAfterWriting() {
        try {
            return read();
        } catch (final RuntimeException e) {
            throw new BoardWriter.AfterWriting(e);
        }
    }

    /**
     * Runs a write under the lock; any failure forgets the board kept, as a part may be written.
     */
    private Snapshot writing(final java.util.function.Supplier<Snapshot> work) {
        return locked(
                () -> {
                    try {
                        return work.get();
                    } catch (final RuntimeException e) {
                        // The next board is read from GitHub, never the one from before the save.
                        snapshot = null;
                        throw e;
                    }
                });
    }

    private Snapshot current() {
        final Snapshot current = snapshot;
        return current != null ? current : read();
    }

    private Snapshot locked(final java.util.function.Supplier<Snapshot> work) {
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
