package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.fuin.porelo.planner.app.answers.ReadingReply;
import org.fuin.porelo.planner.app.answers.TaskEventReply;
import org.fuin.porelo.planner.core.HandedIdeas;
import org.fuin.porelo.planner.core.HandedResults;
import org.fuin.porelo.planner.core.IssuePlace;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.ReportTaking;
import org.fuin.porelo.planner.core.ReviewOpen;
import org.fuin.porelo.planner.core.RunRole;
import org.fuin.porelo.planner.core.StageOrder;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.CallTimes;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.planner.store.files.Seconds;
import org.fuin.porelo.planner.store.git.LogText;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Owns the private, bounded work record and refuses writers from another planner process. */
public final class WorkRecord implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkRecord.class);

    /** A wait for the writer lock longer than this is logged, so a slow read can be explained. */
    private static final long TOLD_WAIT = TimeUnit.SECONDS.toNanos(2);

    static final int MOST_RECORD = 16 * 1024 * 1024;
    private static final int END_RESERVE = 2 * 1024 * 1024;

    /** The work record's JSON, held strict, which the executable's own check also writes. */
    static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .build();

    private final Map<String, String> environment;
    private final PrivateFiles files;
    private final boolean memory;
    private final Replace replace;
    private final LimitFactors limits;
    private final LocalPaths paths;
    private final Duration drain;

    /** Each project's record entry; changed only under the project's gate. */
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** One gate per project, so its opens and forgets come one at a time and never another's. */
    private final Map<String, Object> gates = new ConcurrentHashMap<>();

    /** The projects whose guard stays held until the planner ends, as a queue of them writes. */
    private final java.util.Set<String> kept = ConcurrentHashMap.newKeySet();

    /** How long a caller waiting for the writer lock sleeps before it looks again for a close. */
    private static final long WAIT_STEP = TimeUnit.MILLISECONDS.toNanos(50);

    /**
     * How long closing a record waits for its save in flight, before the factor {@code services}:
     * the store's own wait for its lock and one answer of GitHub.
     */
    static final Duration DRAIN = Duration.ofMinutes(3);

    /** Puts a written record in place of the one before. */
    interface Replace {

        /**
         * Puts it there.
         *
         * @param written the new record
         * @param target where the record is kept
         * @throws IOException when it is refused
         */
        void move(Path written, Path target) throws IOException;
    }

    /** Keeps mock records in memory and resolves real state paths only when a project is used. */
    public WorkRecord(final Map<String, String> environment, final boolean memory) {
        this(environment, memory, PrivateFiles.of(environment));
    }

    /** Keeps records with the given platform's private files. */
    WorkRecord(
            final Map<String, String> environment, final boolean memory, final PrivateFiles files) {
        this(environment, memory, files, LimitFactors.DEFAULT);
    }

    /**
     * Keeps records with the given platform's private files, a refused replacement tried as often
     * as the factor {@code retries} of the planner's factors says.
     */
    WorkRecord(
            final Map<String, String> environment,
            final boolean memory,
            final PrivateFiles files,
            final LimitFactors limits) {
        this(environment, memory, files, WorkRecord::moved, limits);
    }

    /** Keeps records with the platform's private files, a close waiting at most {@code drain}. */
    WorkRecord(final Map<String, String> environment, final boolean memory, final Duration drain) {
        this(
                environment,
                memory,
                PrivateFiles.of(environment),
                WorkRecord::moved,
                LimitFactors.DEFAULT,
                drain);
    }

    private static void moved(final Path written, final Path target) throws IOException {
        Files.move(
                written,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** Keeps records with the given private files, replacing a record as told. */
    WorkRecord(
            final Map<String, String> environment,
            final boolean memory,
            final PrivateFiles files,
            final Replace replace) {
        this(environment, memory, files, replace, LimitFactors.DEFAULT);
    }

    /** Keeps records with the given private files and factors, replacing a record as told. */
    WorkRecord(
            final Map<String, String> environment,
            final boolean memory,
            final PrivateFiles files,
            final Replace replace,
            final LimitFactors limits) {
        this(environment, memory, files, replace, limits, limits.services(DRAIN));
    }

    /** Keeps records with the given private files and factors, a close waiting at most drain. */
    WorkRecord(
            final Map<String, String> environment,
            final boolean memory,
            final PrivateFiles files,
            final Replace replace,
            final LimitFactors limits,
            final Duration drain) {
        this.drain = drain;
        this.environment = Map.copyOf(environment);
        this.memory = memory;
        this.files = files;
        this.replace = replace;
        this.limits = limits;
        this.paths = LocalPaths.of(environment, files.windows());
    }

    /**
     * Puts a whole record in place of a given project's record in memory, as the mock mode's tests
     * ask for one.
     *
     * @param project the project, given before
     * @param record the record it holds from now on
     * @return false when the project was not given
     * @throws IllegalStateException for durable records, which only the planner's flow writes
     */
    public boolean plant(final String project, final WorkRun.Snapshot record) {
        if (!memory) {
            throw new IllegalStateException("A durable work record is written only by its flow");
        }
        synchronized (gate(project)) {
            final Entry entry = entries.get(project);
            if (entry == null || entry.closing) {
                return false;
            }
            entry.start(record);
            return true;
        }
    }

    /** The factors a refused replacement of a record follows. */
    LimitFactors limits() {
        return limits;
    }

    /** The versioned on-disk envelope binds runs to their planning source. */
    public record Document(
            int version,
            String project,
            String repository,
            @Nullable String branch,
            long generation,
            Map<String, WorkRun.Run> runs,
            Map<String, HandedIdeas.Handed> ideas,
            Map<String, HandedResults.Handed> results,
            Map<String, ReportTaking.Followed> followed,
            Map<String, ReportTaking.Mark> reports) {}

    /**
     * Opens a project's one writer, clearing a record from a different repository or branch; a
     * durable record takes its file guard here and is refused while another planner holds it. An
     * entry being closed is never answered: its save is waited for as {@link #forget} waits. Only
     * the project's own gate is held, so a slow save of one project holds up no other.
     */
    public WorkRun.Record open(
            final String project, final String repository, final @Nullable String branch) {
        Ids.name(project);
        synchronized (gate(project)) {
            final Entry existing = entries.get(project);
            if (existing != null
                    && !existing.closing
                    && existing.repository.equals(repository)
                    && java.util.Objects.equals(existing.branch, branch)) {
                return existing;
            }
            if (existing != null) {
                existing.drain(drain);
                entries.remove(project, existing);
            }
            final Entry entry = new Entry(project, repository, branch);
            if (!memory) {
                // The file guard is taken here and held until the record closes, so a second
                // planner on the same state directory is refused before it answers anything.
                entry.acquire();
                entry.load();
            }
            entries.put(project, entry);
            return entry;
        }
    }

    /**
     * Closes a project's writer: refuses its new saves, waits a bounded time for the save in flight
     * to end and be recorded, and only then lets go of its file guard; mock reset also removes
     * every in-memory run. Only the project's own gate is held while it waits.
     *
     * @param project the project
     * @throws WorkRefusal {@code WorkRecordUnavailable} naming the project when its save did not
     *     end in time; the entry then stays, refusing new saves, and the next open or forget waits
     *     for it again
     */
    public void forget(final String project) {
        synchronized (gate(project)) {
            final Entry entry = entries.get(project);
            if (entry != null) {
                entry.drain(drain);
                entries.remove(project, entry);
            }
            kept.remove(project);
        }
    }

    /**
     * Keeps the project's record, and with it its file guard, until the planner ends: a command of
     * its queue may still be at the store, so no second planner may open its outbox.
     *
     * @param project the project
     */
    void keep(final String project) {
        kept.add(project);
    }

    /**
     * Whether the records are files on disk, each guarded against a second planner.
     *
     * @return false for the mock mode's records in memory
     */
    boolean durable() {
        return !memory;
    }

    /**
     * How long a close waits for a save in flight.
     *
     * @return the bound
     */
    Duration drainBound() {
        return drain;
    }

    /**
     * Where an open durable record's project keeps its outbox: its state directory, with the
     * record's files and factors.
     *
     * @param project the project, whose record is open
     * @return the place
     * @throws IllegalStateException for records in memory or a record not open
     */
    ProjectQueues.Place place(final String project) {
        synchronized (gate(project)) {
            final Entry entry = entries.get(project);
            if (memory || entry == null || entry.closing) {
                throw new IllegalStateException("The work record of the project is not open.");
            }
            return new ProjectQueues.Place(
                    environment,
                    entry.opened(),
                    project,
                    entry.repository,
                    entry.branch,
                    files,
                    limits);
        }
    }

    /** Closes this service's own records, each as {@link #forget} does; one left is logged. */
    @Override
    public void close() {
        for (final String project : List.copyOf(entries.keySet())) {
            if (kept.contains(project)) {
                LOG.warn(
                        "The work record of project {} keeps its guard until the planner ends,"
                                + " since a save of its queue did not end",
                        LogText.of(project));
                continue;
            }
            try {
                forget(project);
            } catch (final WorkRefusal refused) {
                LOG.warn(
                        "The work record of project {} is left to the planner's end: {}",
                        LogText.of(project),
                        LogText.of(String.valueOf(refused.getMessage())));
            }
        }
    }

    private Object gate(final String project) {
        return gates.computeIfAbsent(project, any -> new Object());
    }

    private Path directory(final String project) throws IOException {
        try {
            final List<String> steps = new ArrayList<>(files.steps(PrivateFiles.Kind.STATE));
            steps.add(project);
            return files.directories(
                    files.base(PrivateFiles.Kind.STATE, environment),
                    true,
                    PrivateFiles.Kind.STATE,
                    steps);
        } catch (final IllegalStateException | UncheckedIOException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
    }

    /** Refuses a record file that is a link, another account's, open to others or linked twice. */
    private void privateFile(final Path path) throws IOException {
        try {
            files.requireOwnFile(path, true);
        } catch (final IllegalStateException | UncheckedIOException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
    }

    /** Refuses the record with the reason in words, every local path relative to its base. */
    private WorkRefusal unavailable(final String project, final String why) {
        final String reason = paths.relative(why);
        LOG.warn(
                "The work record of project {} is unavailable: {}",
                LogText.of(project),
                LogText.of(reason));
        return WorkRefusal.recordUnavailable(project, reason);
    }

    /** Refuses the record for a failed step, with the check's or the system's own words. */
    private WorkRefusal unavailable(
            final String project, final String step, final Exception failed) {
        final String words = failed.getMessage();
        return unavailable(
                project,
                step
                        + ": "
                        + (words == null || words.isBlank()
                                ? failed.getClass().getSimpleName()
                                : words));
    }

    private static void present(final @Nullable Object... values) {
        for (final @Nullable Object value : values) {
            java.util.Objects.requireNonNull(value);
        }
    }

    /**
     * A save's mark on a record, between the step that marks and the step that records its outcome;
     * made only by its own record and ended at most once.
     */
    public static final class Mark implements WorkRun.Mark {
        private final Object owner;
        private final String id;

        /** Whether the mark still stands; read and changed only under its record's mutex. */
        private boolean open = true;

        private Mark(final Object owner, final String id) {
            this.owner = owner;
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }
    }

    /**
     * One project's record. Two locks guard it, always taken in this order: {@code writers} keeps a
     * caller of {@link #locked} alone across its whole work, and {@code mutex} is held only while
     * the record in memory changes and its file is written and synced. The short steps ({@link
     * #mark}, {@link #outcome}, {@link #abandon}) take the mutex alone, so they are answered while
     * a caller of {@code locked} works.
     */
    final class Entry implements WorkRun.Record, WorkRun.Session {
        private final String project;
        private final String repository;
        private final @Nullable String branch;
        private final ReentrantLock writers = new ReentrantLock();
        private final ReentrantLock mutex = new ReentrantLock();

        /** Signalled under the mutex whenever a mark ends, for a close waiting on the marks. */
        private final java.util.concurrent.locks.Condition marksEnded = mutex.newCondition();

        /** The marks of the saves in flight, by their ids; guarded by the mutex. */
        private final Map<String, Mark> marks = new java.util.HashMap<>();

        /**
         * The generation the caller holding {@code writers} last saw written, set when it enters
         * and on each of its writes; a write after a step changed the record is refused. Guarded by
         * {@code writers}.
         */
        private WorkRun.@Nullable Snapshot expected;

        private @Nullable FileChannel lockChannel;
        private @Nullable FileLock fileLock;
        private @Nullable Path directory;

        /**
         * The generation last written, a save's pending mark included: read from the disk once, at
         * open, and replaced only once a write is in place and synced, so it never runs ahead of
         * the disk.
         */
        private volatile WorkRun.Snapshot stored = new WorkRun.Snapshot(0, Map.of());

        /**
         * The last generation read or written with no save in flight, answered to readers from
         * memory: no other writer reaches the record while this entry holds its lock file.
         */
        private volatile WorkRun.@Nullable Snapshot settled;

        /** Set once the record's file guard is let go: nothing is answered any more. */
        private volatile boolean closed;

        /** Set when closing begins: new saves are refused, readers still answered. */
        private volatile boolean closing;

        private Entry(
                final String project, final String repository, final @Nullable String branch) {
            this.project = project;
            this.repository = repository;
            this.branch = branch;
        }

        /**
         * Keeps the caller alone among the callers of this method across its whole work; the record
         * itself is locked only while its {@link #write} changes it.
         */
        @Override
        public <T extends @Nullable Object> T locked(final WorkRun.Action<T> action) {
            final long started = System.nanoTime();
            final String caller =
                    StackWalker.getInstance()
                            .walk(
                                    frames ->
                                            frames.skip(1)
                                                    .findFirst()
                                                    .map(StackWalker.StackFrame::getMethodName)
                                                    .orElse("unknown"));
            final CallTimes times = CallTimes.open(caller);
            try {
                // A call within a save takes the lock it holds; any other is refused once closing
                // begins, and a caller already waiting looks again every step, so none waits for a
                // save that closing gave up on.
                final boolean within = writers.isHeldByCurrentThread();
                if (!enter(within)) {
                    toldWait(caller, started, false);
                    throw closedRefusal();
                }
                boolean taken = false;
                try {
                    if (closed || !within && closing) {
                        throw closedRefusal();
                    }
                    taken = true;
                    toldWait(caller, started, true);
                    CallTimes.waited("the work record", System.nanoTime() - started);
                    if (!within) {
                        expected = stored;
                    }
                    return action.apply(this);
                } finally {
                    if (!taken) {
                        // A wait that ends refused is the one a person most needs explained.
                        toldWait(caller, started, false);
                    }
                    if (!within) {
                        expected = null;
                    }
                    writers.unlock();
                }
            } finally {
                told(times);
            }
        }

        /** Takes the writer lock, or answers false once the record is closing. */
        private boolean enter(final boolean within) {
            if (within) {
                writers.lock();
                return true;
            }
            while (!closing && !closed) {
                try {
                    if (writers.tryLock(WAIT_STEP, TimeUnit.NANOSECONDS)) {
                        return true;
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw unavailable(
                            project,
                            "The wait for the work record of " + project + " was stopped.");
                }
            }
            return false;
        }

        private WorkRefusal closedRefusal() {
            return unavailable(project, "The work record of " + project + " was closed.");
        }

        /**
         * Refuses new saves, waits at most {@code bound} in all for the caller of {@link #locked}
         * and every mark in flight to end and be recorded, and only then lets go of the file guard;
         * the locks are taken in their one order, {@code writers} then {@code mutex}.
         */
        private void drain(final Duration bound) {
            closing = true;
            final long deadline = System.nanoTime() + bound.toNanos();
            try {
                if (!writers.tryLock(bound.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw notEnded(bound);
                }
                try {
                    if (!mutex.tryLock(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                        throw notEnded(bound);
                    }
                    try {
                        while (!marks.isEmpty()) {
                            final long left = deadline - System.nanoTime();
                            if (left <= 0) {
                                throw notEnded(bound);
                            }
                            marksEnded.awaitNanos(left);
                        }
                        release();
                    } finally {
                        mutex.unlock();
                    }
                } finally {
                    writers.unlock();
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw unavailable(project, "The wait for a save of " + project + " was stopped.");
            }
        }

        private WorkRefusal notEnded(final Duration bound) {
            return unavailable(
                    project,
                    "A save of "
                            + project
                            + " did not end within "
                            + Seconds.of(bound)
                            + " s; the project was not given anew.");
        }

        /**
         * Marks a save in flight: applies {@code change} to the record as it now stands, under the
         * mutex alone, and writes and syncs the result as the next generation.
         *
         * @param id the save's own id, not blank and not the id of another mark in flight
         * @param change what the mark changes; it reads only the record it is given, touches only
         *     its own run or result, and refuses with {@code RunChanged} when that run changed
         *     since the save judged it
         * @return the mark, for its outcome
         */
        @Override
        public Mark mark(
                final String id, final java.util.function.UnaryOperator<WorkRun.Snapshot> change) {
            if (id.isBlank()) {
                throw new IllegalArgumentException("A mark needs an id");
            }
            mutex.lock();
            try {
                if (closing || closed) {
                    throw closedRefusal();
                }
                if (marks.containsKey(id)) {
                    throw new IllegalArgumentException("A mark with this id is in flight");
                }
                writeNext(change);
                final Mark mark = new Mark(this, id);
                marks.put(id, mark);
                return mark;
            } finally {
                mutex.unlock();
            }
        }

        /**
         * Records a save's outcome: applies {@code change} to the record as it now stands, under
         * the mutex alone, writes and syncs the result as the next generation, and ends the mark. A
         * mark already ended changes nothing; a write that fails leaves the mark standing and the
         * record as it was, for the repair at the next open.
         *
         * @param mark the save's mark
         * @param change what the outcome changes, as for {@link #mark}
         * @return the record as it now stands
         */
        @Override
        public WorkRun.Snapshot outcome(
                final WorkRun.Mark given,
                final java.util.function.UnaryOperator<WorkRun.Snapshot> change) {
            final Mark mark = ownMark(given);
            mutex.lock();
            try {
                if (!mark.open) {
                    return stored;
                }
                writeNext(change);
                end(mark);
                return stored;
            } finally {
                mutex.unlock();
            }
        }

        /**
         * Ends a mark with nothing written, for a save refused before it reached the store; its
         * pending mark stays in the record for the repair at the next open. A mark already ended
         * changes nothing.
         *
         * @param mark the save's mark
         */
        @Override
        public void abandon(final WorkRun.Mark given) {
            final Mark mark = ownMark(given);
            mutex.lock();
            try {
                if (mark.open) {
                    end(mark);
                }
            } finally {
                mutex.unlock();
            }
        }

        private Mark ownMark(final WorkRun.Mark given) {
            if (!(given instanceof Mark mark) || mark.owner != this) {
                throw new IllegalArgumentException("The mark is another record's");
            }
            return mark;
        }

        /**
         * Waits, at most the record's bound for a close, until no mark of that id stands; answered
         * at once when none does.
         */
        @Override
        public boolean awaitEnded(final String id) {
            final long deadline = System.nanoTime() + drain.toNanos();
            mutex.lock();
            try {
                while (marks.containsKey(id)) {
                    final long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    marksEnded.awaitNanos(left);
                }
                return true;
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw unavailable(project, "The wait for a save of " + project + " was stopped.");
            } finally {
                mutex.unlock();
            }
        }

        /** Ends an open mark; under the mutex. */
        private void end(final Mark mark) {
            mark.open = false;
            marks.remove(mark.id);
            marksEnded.signalAll();
        }

        /** Writes the next generation of the record as it stands, changed; under the mutex. */
        private void writeNext(final java.util.function.UnaryOperator<WorkRun.Snapshot> change) {
            final WorkRun.Snapshot now = stored;
            final WorkRun.Snapshot changed = change.apply(now);
            final WorkRun.Snapshot next =
                    changed.withGeneration(Math.addExact(now.generation(), 1));
            preflight(next);
            store(next);
        }

        /** Says where a call that wrote or reached GitHub spent its time, once it ended. */
        private void told(final @Nullable CallTimes times) {
            if (times == null) {
                return;
            }
            final String line = times.end();
            if (line != null) {
                LOG.info("Work record call {} of project {}", line, LogText.of(project));
            }
        }

        private void toldWait(final String caller, final long started, final boolean taken) {
            final long waited = System.nanoTime() - started;
            if (waited > TOLD_WAIT) {
                LOG.info(
                        "Work record call {} of project {} waited {} ms for the writer lock and {}",
                        caller,
                        LogText.of(project),
                        TimeUnit.NANOSECONDS.toMillis(waited),
                        taken ? "took it" : "was refused");
            }
        }

        /**
         * Answers the last settled generation from memory, without the lock, the disk or a check of
         * the directories; a save's own pending mark is never answered, so a reader does not take
         * the save in flight for its end.
         */
        @Override
        public WorkRun.Snapshot latest() {
            if (closed) {
                throw unavailable(project, "The work record of " + project + " was closed.");
            }
            if (memory) {
                return stored;
            }
            final WorkRun.Snapshot held = settled;
            return held != null ? held : stored;
        }

        /**
         * Reads the record from the disk, once, when it opens; a record of another repository or
         * branch is held as empty and replaced on the disk by the first write. A record that cannot
         * be read lets the file guard go again, so the project can be opened once it is mended.
         */
        private void load() {
            try {
                final Document doc = readDocument(opened());
                stored =
                        doc == null || !matches(doc)
                                ? new WorkRun.Snapshot(0, Map.of())
                                : snapshot(doc);
                settle(stored);
            } catch (final RuntimeException | Error failed) {
                try {
                    release();
                } catch (final RuntimeException released) {
                    failed.addSuppressed(released);
                }
                throw failed;
            }
        }

        /** Takes the record's file guard once, at open; refused while another planner holds it. */
        private void acquire() {
            try {
                final Path dir;
                try {
                    dir = WorkRecord.this.directory(project);
                } catch (final IOException | IllegalArgumentException failed) {
                    throw unavailable(project, "The work record's directory is refused", failed);
                }
                directory = dir;
                final Path path = dir.resolve("work.lock");
                try {
                    Files.createFile(path, files.ownerOnlyFile());
                } catch (final java.nio.file.FileAlreadyExistsException exists) {
                    /* The stable lock name survives record replacement. */
                }
                privateFile(path);
                final FileChannel channel =
                        FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                final FileLock acquired;
                try {
                    acquired = channel.tryLock();
                } catch (final IOException failed) {
                    channel.close();
                    throw failed;
                } catch (final OverlappingFileLockException inThisProcess) {
                    channel.close();
                    throw heldElsewhere();
                }
                if (acquired == null) {
                    channel.close();
                    throw heldElsewhere();
                }
                lockChannel = channel;
                fileLock = acquired;
            } catch (final IOException
                    | IllegalArgumentException
                    | UnsupportedOperationException failed) {
                throw unavailable(project, "The work record's lock cannot be taken", failed);
            }
        }

        private WorkRefusal heldElsewhere() {
            return unavailable(
                    project, "Another planner holds the work record of " + project + ".");
        }

        /** Holds a whole record in memory, taking both locks in their order. */
        private void start(final WorkRun.Snapshot record) {
            writers.lock();
            try {
                mutex.lock();
                try {
                    stored = record;
                    settle(record);
                } finally {
                    mutex.unlock();
                }
            } finally {
                writers.unlock();
            }
        }

        /** Answers the generation held in memory, read from the disk once, at open. */
        @Override
        public WorkRun.Snapshot read() {
            return stored;
        }

        /**
         * Keeps a generation for readers: every run as written, but a run whose save's pending mark
         * stands in it as it was last settled, so one save in flight holds back no other run.
         */
        private WorkRun.Snapshot settle(final WorkRun.Snapshot snapshot) {
            final WorkRun.Snapshot held = settled;
            final Map<String, WorkRun.Run> runs = new java.util.LinkedHashMap<>();
            snapshot.runs()
                    .forEach(
                            (story, run) -> {
                                final WorkRun.Run before =
                                        held == null ? null : held.runs().get(story);
                                if (run.pending() == null) {
                                    runs.put(story, run);
                                } else if (before != null) {
                                    runs.put(story, before);
                                }
                            });
            settled = snapshot.withRuns(snapshot.generation(), runs);
            return snapshot;
        }

        private @Nullable Document readDocument(final Path dir) {
            final Path path = dir.resolve("work.json");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            return document(tree(recorded(path)));
        }

        private boolean matches(final Document doc) {
            return repository.equals(doc.repository())
                    && java.util.Objects.equals(branch, doc.branch());
        }

        private static WorkRun.Snapshot snapshot(final Document doc) {
            return new WorkRun.Snapshot(
                    doc.generation(),
                    doc.runs(),
                    doc.ideas(),
                    doc.results(),
                    doc.followed(),
                    doc.reports());
        }

        private Path opened() {
            final Path dir = directory;
            if (dir == null) {
                throw unavailable(project, "The work record of " + project + " is not open.");
            }
            return dir;
        }

        /** Reads the record's bytes only once it is checked as the account's and bounded. */
        private byte[] recorded(final Path path) {
            final byte[] bytes;
            try {
                privateFile(path);
                if (Files.size(path) > MOST_RECORD) {
                    throw tooLarge();
                }
                try (var in =
                        java.nio.channels.Channels.newInputStream(
                                FileChannel.open(
                                        path,
                                        StandardOpenOption.READ,
                                        LinkOption.NOFOLLOW_LINKS))) {
                    bytes = in.readNBytes(MOST_RECORD + 1);
                }
            } catch (final IOException | IllegalArgumentException failed) {
                throw unavailable(project, "The work record cannot be read", failed);
            }
            if (bytes.length > MOST_RECORD) {
                throw tooLarge();
            }
            return bytes;
        }

        private WorkRefusal tooLarge() {
            return unavailable(project, "The work record is larger than 16 MB.");
        }

        // A parser's own words can quote the record's content, so they are never passed on.
        private JsonNode tree(final byte[] bytes) {
            final JsonNode tree;
            try {
                tree = JSON.readTree(bytes);
            } catch (final JacksonException invalid) {
                throw unavailable(project, "The work record is not valid JSON.");
            }
            if (tree == null || tree.isMissingNode()) {
                throw unavailable(project, "The work record is not valid JSON.");
            }
            // A record written before ideas were handed in holds none.
            if (tree instanceof ObjectNode root && !root.has("ideas")) {
                root.putObject("ideas");
            }
            // An idea kept under a discussion's offer guarded a hand-in that is gone: ideas are
            // taken from reports now, each kept under its task's name and number.
            if (tree.path("ideas") instanceof ObjectNode ideas) {
                final List<String> offers = new java.util.ArrayList<>();
                ideas.propertyNames()
                        .forEach(
                                key -> {
                                    if (HandedIdeas.offerKey(key)) {
                                        offers.add(key);
                                    }
                                });
                offers.forEach(ideas::remove);
            }
            // A record written before reports were read follows no offer and has taken none.
            if (tree instanceof ObjectNode root && !root.has("followed")) {
                root.putObject("followed");
            }
            // An offer followed before its task names were kept names none.
            for (final JsonNode followed : tree.path("followed")) {
                if (followed instanceof ObjectNode offer && !offer.has("names")) {
                    offer.putArray("names");
                }
            }
            if (tree instanceof ObjectNode root && !root.has("reports")) {
                root.putObject("reports");
            }
            // A record written before stages handed in their results holds none.
            if (tree instanceof ObjectNode root && !root.has("results")) {
                root.putObject("results");
            }
            // A result written before saves were named names none.
            for (final JsonNode result : tree.path("results")) {
                if (result instanceof ObjectNode handed && !handed.has("save")) {
                    handed.put("save", "");
                }
            }
            for (final JsonNode run : tree.path("runs")) {
                // A run written before saves were named carries a bare mark: none, or one whose
                // save the repair at open halts.
                if (run instanceof ObjectNode marked && marked.path("pending").isBoolean()) {
                    if (marked.path("pending").booleanValue()) {
                        final ObjectNode mark = marked.putObject("pending");
                        mark.put("id", "");
                        mark.putArray("items");
                    } else {
                        marked.putNull("pending");
                    }
                }
                // A run written before issues' places were kept keeps none.
                if (run instanceof ObjectNode placed && !placed.has("places")) {
                    placed.putObject("places");
                }
                // A run written before reads of kept places offered work holds none of theirs.
                if (run instanceof ObjectNode placed && !placed.has("placeOffers")) {
                    placed.putArray("placeOffers");
                }
                // A place kept before reviewers ran there names no head a reviewer was offered at.
                for (final JsonNode place : run.path("places")) {
                    if (place instanceof ObjectNode kept && !kept.has("reviewed")) {
                        kept.putNull("reviewed");
                    }
                }
                for (final JsonNode repository : run.path("repositories")) {
                    olderChoice(repository.path("main"));
                    // A main recorded before the rule's offered agents were proposed names none.
                    if (repository.path("main") instanceof ObjectNode main
                            && !main.has("offered")) {
                        main.putArray("offered");
                    }
                    for (final JsonNode sub : repository.path("subAgents")) {
                        olderChoice(sub);
                    }
                }
                for (final JsonNode offer : run.path("offers")) {
                    // Only the newer fields may be absent in older records.
                    for (final String newer : List.of("machine", "rework", "startRefusal")) {
                        if (offer instanceof ObjectNode object && !object.has(newer)) {
                            object.putNull(newer);
                        }
                    }
                    if (offer.path("arguments") instanceof ObjectNode arguments
                            && !arguments.has("reads")) {
                        arguments.putArray("reads");
                    }
                    if (offer.path("ended") instanceof ObjectNode ended) {
                        if (!ended.has("landed")) {
                            ended.putArray("landed");
                        }
                        if (!ended.has("outcome")) {
                            ended.putNull("outcome");
                        }
                    }
                    // A record written before runs named a role and a place names neither, so
                    // Start's defaults hold for it.
                    if (offer.path("arguments") instanceof ObjectNode arguments) {
                        for (final String newer : List.of("runRole", "place")) {
                            if (!arguments.has(newer)) {
                                arguments.putNull(newer);
                            }
                        }
                    }
                    // A record written before the risks names no effort and no rule's risks.
                    if (offer.path("arguments") instanceof ObjectNode arguments
                            && !arguments.has("effort")) {
                        arguments.putNull("effort");
                    }
                    // A record written before the effort was offered names no basis and no effort
                    // used.
                    if (offer.path("arguments") instanceof ObjectNode arguments) {
                        if (!arguments.has("basis")) {
                            arguments.put("basis", "");
                        }
                        if (!arguments.has("used")) {
                            arguments.putNull("used");
                        }
                    }
                    if (offer.path("binding").path("rule") instanceof ObjectNode rule
                            && !rule.has("risks")) {
                        rule.putObject("risks");
                    }
                    // A record written before stages handed back results names none and no files.
                    if (offer.path("binding").path("rule") instanceof ObjectNode rule
                            && !rule.has("result")) {
                        rule.putNull("result");
                    }
                    // A rule recorded before reviewers ran is read by a reader.
                    if (offer.path("binding").path("rule") instanceof ObjectNode rule
                            && !rule.has("reviewer")) {
                        rule.putNull("reviewer");
                    }
                    // A refusal kept before a forge's missing right was told names no right.
                    if (offer.path("refusal") instanceof ObjectNode refusal
                            && !refusal.has("right")) {
                        refusal.putNull("right");
                    }
                    // One kept before a refused save's group was kept names none: it is not saved
                    // again by the owner account, only by the task's repeated report.
                    if (offer.path("refusal") instanceof ObjectNode refusal
                            && !refusal.has("group")) {
                        refusal.putNull("group");
                    }
                    if (offer.path("arguments") instanceof ObjectNode arguments) {
                        if (!arguments.has("result")) {
                            arguments.putNull("result");
                        }
                        if (!arguments.has("given")) {
                            arguments.putArray("given");
                        }
                        // A task recorded before a tracker's work was handed to Start names none.
                        if (!arguments.has("work")) {
                            arguments.putNull("work");
                        }
                    }
                }
            }
            return tree;
        }

        // A repository's main or sub-agent written before the risks names no effort and no basis.
        private static void olderChoice(final JsonNode participant) {
            if (participant instanceof ObjectNode object) {
                if (!object.has("effort")) {
                    object.putNull("effort");
                }
                if (!object.has("basis")) {
                    object.put("basis", "");
                }
            }
        }

        private Document document(final JsonNode tree) {
            final Document doc;
            try {
                doc = JSON.treeToValue(tree, Document.class);
            } catch (final JacksonException | IllegalArgumentException | NullPointerException e) {
                throw unavailable(project, "The work record does not have the record's form.");
            }
            if (doc.version() != 1
                    || !project.equals(doc.project())
                    || doc.generation() < 0
                    || doc.repository().isBlank()) {
                throw unavailable(
                        project,
                        "The work record is not a record of version 1 for " + project + ".");
            }
            try {
                validate(doc);
            } catch (final JacksonException
                    | IllegalArgumentException
                    | NullPointerException
                    | WorkRefusal invalid) {
                throw unavailable(project, "The work record has a run that fails its check.");
            }
            return doc;
        }

        /**
         * Reserves enough capacity for every outstanding end, so cancellation cannot fill the
         * record.
         */
        @Override
        public void preflight(final WorkRun.Snapshot snapshot) {
            final byte[] bytes = bytes(snapshot);
            final long outstanding =
                    snapshot.runs().values().stream()
                            .flatMap(r -> r.offers().stream())
                            .filter(
                                    o ->
                                            o.status().equals("OFFERED")
                                                    || o.status().equals("STARTED"))
                            .count();
            // A stop or refusal can add attention to every issue without releasing another task.
            final long attentionReserve =
                    65536L
                            + snapshot.runs().values().stream()
                                            .mapToLong(run -> run.issues().size())
                                            .sum()
                                    * 64;
            if (bytes.length + attentionReserve + outstanding * END_RESERVE > MOST_RECORD) {
                throw WorkRefusal.recordUnavailable(project, "The work record is full.");
            }
            try {
                for (final WorkRun.Run run : snapshot.runs().values()) {
                    final var offers =
                            run.offers().stream()
                                    .filter(o -> o.status().equals("OFFERED"))
                                    .map(o -> new WorkRun.Offered(o.id(), o.arguments()))
                                    .toList();
                    // A person's issue answers within the same reserve, so the bound needs no
                    // board.
                    final var answer =
                            new TaskEventReply(
                                    "",
                                    WorkAnswers.knownWork(project, run, issue -> false),
                                    WorkAnswers.starts(offers));
                    // Revision changes and cancellation must fit beside the work answer.
                    if (JSON.writeValueAsBytes(answer).length + END_RESERVE > MOST_RECORD) {
                        throw WorkRefusal.recordUnavailable(project, "The work record is full.");
                    }
                }
            } catch (final JacksonException failed) {
                throw unavailable(project, "The work record cannot be written as JSON.");
            }
        }

        /** Checks the complete reading envelope, including the store's current issue fields. */
        @Override
        public void preflightIssue(
                final WorkRun.Snapshot snapshot, final Board board, final String issue) {
            preflight(snapshot);
            WorkRefusal.unlessFree(board, issue, "", "");
            final var found = board.issue(issue).orElseThrow();
            try {
                for (final WorkRun.Run run : snapshot.runs().values()) {
                    if (!run.issues().containsKey(issue)) {
                        continue;
                    }
                    final var offers =
                            run.offers().stream()
                                    .filter(o -> o.status().equals("OFFERED"))
                                    .map(o -> new WorkRun.Offered(o.id(), o.arguments()))
                                    .toList();
                    final var answer =
                            new ReadingReply(
                                    WorkAnswers.issue(
                                            board,
                                            found,
                                            new WorkRun.Snapshot(0, Map.of(run.story(), run))),
                                    WorkAnswers.knownWork(project, board, run),
                                    board.revision().value(),
                                    WorkAnswers.starts(offers));
                    if (JSON.writeValueAsBytes(answer).length + 65536 > MOST_RECORD) {
                        throw WorkRefusal.recordUnavailable(project, "The work record is full.");
                    }
                }
            } catch (final JacksonException failed) {
                throw unavailable(project, "The work record cannot be written as JSON.");
            }
        }

        /**
         * Flushes a new private sibling, atomically replaces the record and syncs its directory,
         * under the mutex. When a step changed the record since this caller of {@link #locked}
         * entered or last wrote, the caller's own changes are laid onto the record as it now
         * stands; refused with {@code RunChanged} only when the step changed a story's run, or an
         * offer's idea or result, that the caller's write changes too.
         */
        @Override
        public void write(final WorkRun.Snapshot snapshot) {
            final boolean writer = writers.isHeldByCurrentThread();
            mutex.lock();
            try {
                final WorkRun.Snapshot seen = expected;
                final WorkRun.Snapshot now = stored;
                final WorkRun.Snapshot written =
                        writer && seen != null && seen != now
                                ? laid(seen, now, snapshot)
                                : snapshot;
                store(written);
                if (writer) {
                    expected = written;
                }
            } finally {
                mutex.unlock();
            }
        }

        /** The caller's changes since {@code seen} laid onto the record as it now stands. */
        private WorkRun.Snapshot laid(
                final WorkRun.Snapshot seen,
                final WorkRun.Snapshot now,
                final WorkRun.Snapshot written) {
            return new WorkRun.Snapshot(
                    Math.max(written.generation(), Math.addExact(now.generation(), 1)),
                    laid(seen.runs(), now.runs(), written.runs(), true),
                    laid(seen.ideas(), now.ideas(), written.ideas(), false),
                    laid(seen.results(), now.results(), written.results(), false),
                    laid(seen.followed(), now.followed(), written.followed(), false),
                    laid(seen.reports(), now.reports(), written.reports(), false));
        }

        /**
         * One part of the record with the caller's changes laid on: an entry the caller left as it
         * saw it, or wrote as it now stands, keeps what stands now.
         */
        private <V> Map<String, V> laid(
                final Map<String, V> seen,
                final Map<String, V> now,
                final Map<String, V> written,
                final boolean runs) {
            final Map<String, V> laid = new java.util.LinkedHashMap<>(now);
            final java.util.TreeSet<String> keys = new java.util.TreeSet<>(seen.keySet());
            keys.addAll(written.keySet());
            for (final String key : keys) {
                final V mine = written.get(key);
                if (java.util.Objects.equals(mine, seen.get(key))
                        || java.util.Objects.equals(mine, now.get(key))) {
                    continue;
                }
                if (!java.util.Objects.equals(seen.get(key), now.get(key))) {
                    throw WorkRefusal.runChanged(
                            runs ? key : project,
                            "The work record changed while this change was judged.");
                }
                if (mine == null) {
                    laid.remove(key);
                } else {
                    laid.put(key, mine);
                }
            }
            return laid;
        }

        /** Writes the record and holds it; under the mutex. */
        private void store(final WorkRun.Snapshot snapshot) {
            final byte[] bytes = bytes(snapshot);
            if (memory) {
                stored = snapshot;
                return;
            }
            final Path dir = opened();
            Path temporary = null;
            String step = "The work record cannot be replaced";
            try {
                final Path target = dir.resolve("work.json");
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    privateFile(target);
                }
                step = "The work record cannot be written";
                temporary = dir.resolve("work-" + UUID.randomUUID() + ".tmp");
                try (FileChannel channel =
                        FileChannel.open(
                                temporary,
                                Set.of(
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS),
                                files.ownerOnlyFile())) {
                    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                final Path written = temporary;
                step = "The work record cannot be put in place";
                SharedFiles.whileShared(() -> replace.move(written, target), limits);
                temporary = null;
                // Windows opens no directory as a file; its move is durable once it returns.
                if (!files.windows()) {
                    step = "The work record's directory cannot be synced";
                    try (FileChannel channel =
                            FileChannel.open(
                                    dir, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                        channel.force(true);
                    }
                }
                // Only a generation in place and synced is held, so a failed write leaves memory
                // at the last one on the disk.
                stored = snapshot;
                settle(snapshot);
            } catch (final IOException | UnsupportedOperationException failed) {
                throw unavailable(project, step, failed);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (final IOException ignored) {
                        // A temporary file is left only after a failed write, whose own reason is
                        // the one the person must see.
                    }
                }
            }
        }

        private byte[] bytes(final WorkRun.Snapshot snapshot) {
            try {
                final byte[] bytes =
                        JSON.writeValueAsBytes(
                                new Document(
                                        1,
                                        project,
                                        repository,
                                        branch,
                                        snapshot.generation(),
                                        snapshot.runs(),
                                        snapshot.ideas(),
                                        snapshot.results(),
                                        snapshot.followed(),
                                        snapshot.reports()));
                if (bytes.length > MOST_RECORD) {
                    throw WorkRefusal.recordUnavailable(project, "The work record is full.");
                }
                return bytes;
            } catch (final JacksonException failed) {
                throw unavailable(project, "The work record cannot be written as JSON.");
            }
        }

        private void validate(final Document doc) {
            doc.ideas().forEach(HandedIdeas::validate);
            doc.followed()
                    .keySet()
                    .forEach(
                            offer -> {
                                if (!HandedIdeas.offerKey(offer)) {
                                    throw new IllegalArgumentException(
                                            "a followed offer fails its check");
                                }
                            });
            doc.reports().keySet().forEach(Ids::task);
            doc.results().forEach(HandedResults::validate);
            final Set<String> ids = new HashSet<>();
            doc.runs()
                    .forEach(
                            (story, run) -> {
                                Ids.story(story);
                                present(
                                        run.to(),
                                        run.mode(),
                                        run.comment(),
                                        run.coordinator(),
                                        run.since());
                                if (!story.equals(run.story())
                                        || !UUID.fromString(run.id()).toString().equals(run.id())
                                        || run.readingRounds() < 1) {
                                    throw new IllegalArgumentException("a run fails its check");
                                }
                                final WorkRun.PendingSave mark = run.pending();
                                if (mark != null) {
                                    WorkRun.PendingSave.checked(mark);
                                }
                                run.issues()
                                        .forEach(
                                                (id, progress) -> {
                                                    Ids.issue(id);
                                                    present(progress.reason(), progress.answer());
                                                    progress.findings()
                                                            .forEach(
                                                                    f ->
                                                                            present(
                                                                                    f.severity(),
                                                                                    f.text()));
                                                    if (progress.rounds().rounds() < 0
                                                            || progress.rounds().roundsMost() < 1
                                                            || progress.findings().size() > 100) {
                                                        throw new IllegalArgumentException(
                                                                "an issue fails its check");
                                                    }
                                                });
                                for (final WorkRun.Offer offer : run.offers()) {
                                    final String machine = offer.machine();
                                    if (machine != null) {
                                        WorkRun.checkedMachine(machine);
                                    }
                                    present(
                                            offer.arguments().role(),
                                            offer.arguments().agent(),
                                            offer.arguments().model(),
                                            offer.arguments().mode(),
                                            offer.arguments().prompt(),
                                            offer.binding().role(),
                                            offer.binding().state(),
                                            offer.binding().mode(),
                                            offer.binding().rule().done(),
                                            offer.binding().rule().per(),
                                            offer.status(),
                                            offer.fingerprint(),
                                            offer.reworkComment());
                                    if (!ids.add(offer.id())
                                            || offer.reworkComment()
                                                            .codePointCount(
                                                                    0,
                                                                    offer.reworkComment().length())
                                                    > 4000
                                            || !UUID.fromString(offer.id())
                                                    .toString()
                                                    .equals(offer.id())
                                            || !story.equals(offer.binding().story())
                                            || !offer.arguments()
                                                    .issues()
                                                    .equals(offer.binding().issues())
                                            || !offer.arguments()
                                                    .repository()
                                                    .equals(offer.binding().repository())
                                            || !Set.of(
                                                            "OFFERED", "STARTED", "ENDED",
                                                            "STOPPED", "VOID")
                                                    .contains(offer.status())) {
                                        throw new IllegalArgumentException(
                                                "an offer fails its check");
                                    }
                                    final String effort = offer.arguments().effort();
                                    final String used = offer.arguments().used();
                                    if (effort != null && !WorkRule.sound(effort)
                                            || used != null && !WorkRule.sound(used)
                                            || offer.arguments().basis().length() > 4000
                                            || !givenFits(offer.arguments())
                                            || offer.arguments().work() != null
                                                    && offer.arguments()
                                                            .role()
                                                            .equals("DISCUSSION")) {
                                        throw new IllegalArgumentException(
                                                "an offer fails its check");
                                    }
                                    final WorkRun.SaveFailure refusal = offer.refusal();
                                    if (refusal != null) {
                                        checkedRefusal(refusal);
                                    }
                                    final WorkRun.StartRefusal startRefusal = offer.startRefusal();
                                    if (startRefusal != null) {
                                        // Only an offer never started stands refused, still
                                        // offered or voided since.
                                        if (!Set.of("OFFERED", "VOID").contains(offer.status())) {
                                            throw new IllegalArgumentException(
                                                    "an offer fails its check");
                                        }
                                        try {
                                            WorkRun.checkedStartRefusal(startRefusal);
                                        } catch (final WorkRefusal invalid) {
                                            throw new IllegalArgumentException(
                                                    "an offer fails its check", invalid);
                                        }
                                    }
                                    final NextStep.Settings rework = offer.rework();
                                    if (rework != null) {
                                        present(rework.mode(), rework.agent(), rework.model());
                                        if (rework.mode().length() > 128
                                                || rework.agent().length() > 128
                                                || rework.model().length() > 128) {
                                            throw new IllegalArgumentException(
                                                    "an offer fails its check");
                                        }
                                    }
                                    if (Set.of("STARTED", "ENDED", "STOPPED")
                                            .contains(offer.status())) {
                                        present(offer.task(), offer.containerId(), offer.since());
                                    }
                                    // A void offer keeps its end, if it had one, as a carried
                                    // run does; every other bound offer has ended exactly when
                                    // its status says so.
                                    if (offer.task() != null
                                            && !offer.status().equals("VOID")
                                            && offer.status().equals("ENDED")
                                                    != (offer.ended() != null)) {
                                        throw new IllegalArgumentException(
                                                "an offer fails its check");
                                    }
                                    if (offer.task() != null) {
                                        WorkRun.checkedReport(
                                                offer.task(),
                                                new WorkRun.Report(
                                                        offer.id(),
                                                        offer.ended() != null
                                                                ? WorkRun.Event.ENDED
                                                                : WorkRun.Event.STARTED,
                                                        String.valueOf(offer.containerId()),
                                                        machine == null ? "unknown" : machine,
                                                        offer.ended()));
                                    }
                                }
                                run.places().forEach((id, place) -> checkedPlace(run, id, place));
                                checkedPlaceOffers(run);
                            });
        }

        /**
         * Refuses a kept place of an issue the run does not hold, one whose reviewed head is no
         * commit, or one whose author run is no ended or void builder's offer of the run owning the
         * issue that pushes its work as its author, bound to the place's task in a container.
         */
        private static void checkedPlace(
                final WorkRun.Run run, final String issue, final IssuePlace place) {
            Ids.issue(issue);
            if (!run.issues().containsKey(issue)) {
                throw new IllegalArgumentException("a place fails its check");
            }
            final String reviewed = place.reviewed();
            if (reviewed != null && !ReviewOpen.commit(reviewed)) {
                throw new IllegalArgumentException("a place fails its check");
            }
            for (final String id : place.authorRuns()) {
                if (run.offers().stream()
                        .noneMatch(
                                o ->
                                        o.id().equals(id)
                                                && o.binding().issues().contains(issue)
                                                && Set.of("MAIN", "BUILDER")
                                                        .contains(o.binding().role())
                                                && o.binding().rule().result() == null
                                                && o.arguments().runRole() != RunRole.REVIEWER
                                                && Set.of("ENDED", "VOID").contains(o.status())
                                                && o.ended() != null
                                                && place.task().equals(o.task())
                                                && o.containerId() != null)) {
                    throw new IllegalArgumentException("a place fails its check");
                }
            }
        }

        /**
         * Refuses an offer a read of a kept place made that is named twice, or that is neither an
         * author's offer of the run at a gate push or at its task's own pull request nor a
         * reviewer's, as a reader, at a place.
         */
        private static void checkedPlaceOffers(final WorkRun.Run run) {
            if (Set.copyOf(run.placeOffers()).size() != run.placeOffers().size()) {
                throw new IllegalArgumentException("a place's offer fails its check");
            }
            for (final String id : run.placeOffers()) {
                if (run.offers().stream()
                        .noneMatch(
                                o ->
                                        o.id().equals(id)
                                                && o.arguments().place() != null
                                                && (o.arguments().runRole() == RunRole.AUTHOR
                                                                && (o.arguments().place().gatePush()
                                                                                != null
                                                                        || o.arguments()
                                                                                        .place()
                                                                                        .ownPullRequest()
                                                                                != null)
                                                        || o.arguments().runRole()
                                                                        == RunRole.REVIEWER
                                                                && o.binding()
                                                                        .role()
                                                                        .equals("READER")))) {
                    throw new IllegalArgumentException("a place's offer fails its check");
                }
            }
        }

        /** Lets go of the file guard; called with no save that can still run. */
        private void release() {
            closing = true;
            closed = true;
            try {
                final FileLock held = fileLock;
                final FileChannel channel = lockChannel;
                if (held != null) {
                    held.close();
                }
                if (channel != null) {
                    channel.close();
                }
            } catch (final IOException failed) {
                throw unavailable(project, "The work record's lock cannot be released", failed);
            }
        }
    }

    /**
     * Refuses a kept refusal that could not be told again as it was made: the window offers a token
     * for the forge and right a missing access names, so neither may be other than the store's.
     */
    private static void checkedRefusal(final WorkRun.SaveFailure refusal) {
        final String group = refusal.group();
        if (group != null && !java.util.UUID.fromString(group).toString().equals(group)) {
            // A group is named as the planner made it: a lowercase UUID.
            throw new IllegalArgumentException("a refusal fails its check");
        }
        if (refusal.name().equals("NoForgeAccess")) {
            if (!new StoreException.NoForgeAccess(refusal.subject(), refusal.right())
                    .subject()
                    .equals(refusal.subject())) {
                throw new IllegalArgumentException("a refusal fails its check");
            }
        } else if (refusal.right() != null) {
            throw new IllegalArgumentException("a refusal fails its check");
        }
    }

    /**
     * Whether an offer's result and given files are what its role is served: none for a discussion,
     * which reports its idea back, a stage's file for its main or builder, then each owned issue at
     * most once by its id within the bounds a task is given, and no file for work that pushes.
     */
    private static boolean givenFits(final WorkOrder.Task task) {
        final String result = task.result();
        final boolean stage =
                result != null
                        && java.util.Arrays.stream(WorkRule.Result.values())
                                .anyMatch(kind -> kind.file().equals(result));
        if (result != null
                && (task.role().equals("DISCUSSION") || !stage || task.role().equals("READER"))) {
            return false;
        }
        // An ended stage's offer is kept compacted, its result named and its files dropped.
        if (!stage && !task.given().isEmpty() || task.given().size() > StageOrder.MOST_FILES) {
            return false;
        }
        final java.util.Set<String> names = new java.util.HashSet<>();
        int all = 0;
        for (final WorkOrder.Given given : task.given()) {
            final int bytes = given.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            all += bytes;
            if (bytes > StageOrder.MOST_TEXT
                    || !names.add(given.name())
                    || task.issues().stream()
                            .noneMatch(id -> given.name().equals(StageOrder.given(id)))) {
                return false;
            }
        }
        return all <= StageOrder.MOST_ALL;
    }
}
