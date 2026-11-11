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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.fuin.porelo.planner.core.HandedIdeas;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.git.LogText;
import org.fuin.porelo.planner.store.git.PrivateFiles;
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

    static final int MOST_RECORD = 16 * 1024 * 1024;
    private static final int END_RESERVE = 2 * 1024 * 1024;
    private static final JsonMapper JSON =
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
    private final LocalPaths paths;
    private final Map<String, Entry> entries = new HashMap<>();

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
        this(
                environment,
                memory,
                files,
                (written, target) ->
                        Files.move(
                                written,
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING));
    }

    /** Keeps records with the given private files, replacing a record as told. */
    WorkRecord(
            final Map<String, String> environment,
            final boolean memory,
            final PrivateFiles files,
            final Replace replace) {
        this.environment = Map.copyOf(environment);
        this.memory = memory;
        this.files = files;
        this.replace = replace;
        this.paths = LocalPaths.of(environment, files.windows());
    }

    /** The versioned on-disk envelope binds runs to their planning source. */
    public record Document(
            int version,
            String project,
            String repository,
            @Nullable String branch,
            long generation,
            Map<String, WorkRun.Run> runs,
            Map<String, HandedIdeas.Handed> ideas) {}

    /** Opens a project's one writer, clearing a record from a different repository or branch. */
    public synchronized WorkRun.Record open(
            final String project, final String repository, final @Nullable String branch) {
        Ids.name(project);
        final Entry existing = entries.get(project);
        if (existing != null
                && existing.repository.equals(repository)
                && java.util.Objects.equals(existing.branch, branch)) {
            return existing;
        }
        if (existing != null) {
            existing.close();
        }
        final Entry entry = new Entry(project, repository, branch);
        entries.put(project, entry);
        return entry;
    }

    /** Releases an old project's writer; mock reset also removes every in-memory run. */
    public synchronized void forget(final String project) {
        final Entry entry = entries.remove(project);
        if (entry != null) {
            entry.close();
        }
    }

    /** Releases only this service's own project locks. */
    @Override
    public synchronized void close() {
        entries.values().forEach(Entry::close);
        entries.clear();
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

    private final class Entry implements WorkRun.Record, WorkRun.Session {
        private final String project;
        private final String repository;
        private final @Nullable String branch;
        private final ReentrantLock mutex = new ReentrantLock();
        private @Nullable FileChannel lockChannel;
        private @Nullable FileLock fileLock;
        private @Nullable Path directory;
        private WorkRun.Snapshot stored = new WorkRun.Snapshot(0, Map.of());
        private boolean closed;

        private Entry(
                final String project, final String repository, final @Nullable String branch) {
            this.project = project;
            this.repository = repository;
            this.branch = branch;
        }

        /** Holds the same writer lock across reading, saving and finalizing a generation. */
        @Override
        public <T extends @Nullable Object> T locked(final WorkRun.Action<T> action) {
            mutex.lock();
            try {
                if (closed) {
                    throw unavailable(project, "The work record of " + project + " was closed.");
                }
                if (!memory) {
                    acquire();
                }
                return action.apply(this);
            } finally {
                mutex.unlock();
            }
        }

        private void acquire() {
            try {
                final FileLock held = fileLock;
                if (held != null && held.isValid() && directory != null) {
                    return;
                }
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

        /** Reads only after checking type, ownership and size, and refuses unreadable history. */
        @Override
        public WorkRun.Snapshot read() {
            if (memory) {
                return stored;
            }
            final Path dir = opened();
            final Path path = dir.resolve("work.json");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return new WorkRun.Snapshot(0, Map.of());
            }
            final Document doc = document(tree(recorded(path)));
            if (!repository.equals(doc.repository())
                    || !java.util.Objects.equals(branch, doc.branch())) {
                final WorkRun.Snapshot reset = new WorkRun.Snapshot(0, Map.of());
                write(reset);
                return reset;
            }
            return new WorkRun.Snapshot(doc.generation(), doc.runs(), doc.ideas());
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
            for (final JsonNode run : tree.path("runs")) {
                for (final JsonNode offer : run.path("offers")) {
                    // Only the newer fields may be absent in older records.
                    for (final String newer : List.of("machine", "rework")) {
                        if (offer instanceof ObjectNode object && !object.has(newer)) {
                            object.putNull(newer);
                        }
                    }
                }
            }
            return tree;
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
                            new WorkAnswers.TaskEventReply(
                                    "",
                                    WorkAnswers.knownWork(run, issue -> false),
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
                            new WorkAnswers.ReadingReply(
                                    WorkAnswers.issue(
                                            board,
                                            found,
                                            new WorkRun.Snapshot(0, Map.of(run.story(), run))),
                                    WorkAnswers.knownWork(board, run),
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
         * Flushes a new private sibling, atomically replaces the record and syncs its directory.
         */
        @Override
        public void write(final WorkRun.Snapshot snapshot) {
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
                SharedFiles.whileShared(() -> replace.move(written, target));
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
                                        snapshot.ideas()));
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
                                    if (offer.task() != null) {
                                        WorkRun.checkedReport(
                                                offer.task(),
                                                new WorkRun.Report(
                                                        offer.id(),
                                                        offer.status().equals("ENDED")
                                                                ? WorkRun.Event.ENDED
                                                                : WorkRun.Event.STARTED,
                                                        String.valueOf(offer.containerId()),
                                                        machine == null ? "unknown" : machine,
                                                        offer.ended()));
                                    }
                                }
                            });
        }

        private void close() {
            mutex.lock();
            try {
                closed = true;
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
            } finally {
                mutex.unlock();
            }
        }
    }
}
