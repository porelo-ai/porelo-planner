package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Owns the private, bounded work record and refuses writers from another planner process. */
public final class WorkRecord implements AutoCloseable {

    static final int MOST_RECORD = 16 * 1024 * 1024;
    private static final int END_RESERVE = 2 * 1024 * 1024;
    private static final Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<java.nio.file.attribute.PosixFilePermission> FILE =
            PosixFilePermissions.fromString("rw-------");
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
    private final boolean memory;
    private final Map<String, Entry> entries = new HashMap<>();

    /** Keeps mock records in memory and resolves real state paths only when a project is used. */
    public WorkRecord(final Map<String, String> environment, final boolean memory) {
        this.environment = Map.copyOf(environment);
        this.memory = memory;
    }

    /** The versioned on-disk envelope binds runs to their planning source. */
    public record Document(
            int version,
            String project,
            String repository,
            @Nullable String branch,
            long generation,
            Map<String, WorkRun.Run> runs) {}

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
        final String home = environment.get("HOME");
        if (home != null) {
            absolute(home);
        }
        final String xdg = environment.get("XDG_STATE_HOME");
        final Path root;
        if (xdg != null) {
            root = absolute(xdg);
        } else if (home != null) {
            root = absolute(home).resolve(".local/state");
        } else {
            throw unavailable(project);
        }
        final Path directory = root.resolve("porelo-planner").resolve(project);
        Path current = directory.getRoot();
        if (current == null) {
            throw unavailable(project);
        }
        for (final Path piece : directory) {
            current = current.resolve(piece);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current, PosixFilePermissions.asFileAttribute(DIRECTORY));
                } catch (final java.nio.file.FileAlreadyExistsException raced) {
                    /* Another owner operation may have created the ancestor. */
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(current)) {
                throw unavailable(project);
            }
            safeAncestor(current);
        }
        privatePath(java.util.Objects.requireNonNull(directory.getParent()), true);
        privatePath(directory, true);
        return directory;
    }

    private static Path absolute(final String value) {
        final Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("A state root must be absolute");
        }
        for (final Path piece : path) {
            if (piece.toString().equals("..")) {
                throw new IllegalArgumentException("A state root cannot traverse parents");
            }
        }
        return path;
    }

    private static void safeAncestor(final Path path) throws IOException {
        final int mode =
                ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS))
                        .intValue();
        final int uid =
                ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS))
                        .intValue();
        final String account = System.getProperty("user.name");
        if (account == null) {
            throw new IOException("The owner identity is unavailable");
        }
        final UserPrincipal owner =
                path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account);
        // Sticky system directories protect our own entries; other writable ancestors do not.
        if (uid != 0 && !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)
                || (mode & 0022) != 0 && (mode & 01000) == 0) {
            throw new IOException("The work path can be replaced by another account");
        }
    }

    private static void privatePath(final Path path, final boolean directory) throws IOException {
        final BasicFileAttributes attrs =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        final String account = System.getProperty("user.name");
        if (account == null) {
            throw new IOException("The owner identity is unavailable");
        }
        final UserPrincipal owner =
                path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account);
        if (attrs.isSymbolicLink()
                || (directory ? !attrs.isDirectory() : !attrs.isRegularFile())
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                        .equals(directory ? DIRECTORY : FILE)
                || !directory
                        && ((Number)
                                                Files.getAttribute(
                                                        path,
                                                        "unix:nlink",
                                                        LinkOption.NOFOLLOW_LINKS))
                                        .intValue()
                                != 1) {
            throw new IOException("The work path is not private");
        }
    }

    private static WorkRefusal unavailable(final String project) {
        return WorkRefusal.recordUnavailable(project, "The work record is unavailable.");
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
                    throw unavailable(project);
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
                final Path dir = WorkRecord.this.directory(project);
                directory = dir;
                final FileLock held = fileLock;
                if (held != null && held.isValid()) {
                    return;
                }
                final Path path = dir.resolve("work.lock");
                try {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE));
                } catch (final java.nio.file.FileAlreadyExistsException exists) {
                    /* The stable lock name survives record replacement. */
                }
                privatePath(path, false);
                final FileChannel channel =
                        FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                final FileLock acquired;
                try {
                    acquired = channel.tryLock();
                } catch (final IOException | OverlappingFileLockException failed) {
                    channel.close();
                    throw failed;
                }
                if (acquired == null) {
                    channel.close();
                    throw unavailable(project);
                }
                lockChannel = channel;
                fileLock = acquired;
            } catch (final IOException
                    | IllegalArgumentException
                    | UnsupportedOperationException
                    | OverlappingFileLockException failed) {
                throw unavailable(project);
            }
        }

        /** Reads only after checking type, ownership and size, and refuses unreadable history. */
        @Override
        public WorkRun.Snapshot read() {
            if (memory) {
                return stored;
            }
            final Path dir = directory;
            if (dir == null) {
                throw unavailable(project);
            }
            final Path path = dir.resolve("work.json");
            try {
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    return new WorkRun.Snapshot(0, Map.of());
                }
                privatePath(path, false);
                if (Files.size(path) > MOST_RECORD) {
                    throw unavailable(project);
                }
                final byte[] bytes;
                try (var in =
                        java.nio.channels.Channels.newInputStream(
                                FileChannel.open(
                                        path,
                                        StandardOpenOption.READ,
                                        LinkOption.NOFOLLOW_LINKS))) {
                    bytes = in.readNBytes(MOST_RECORD + 1);
                }
                if (bytes.length > MOST_RECORD) {
                    throw unavailable(project);
                }
                final Document doc = JSON.readValue(bytes, Document.class);
                validate(doc);
                if (!repository.equals(doc.repository())
                        || !java.util.Objects.equals(branch, doc.branch())) {
                    final WorkRun.Snapshot reset = new WorkRun.Snapshot(0, Map.of());
                    write(reset);
                    return reset;
                }
                return new WorkRun.Snapshot(doc.generation(), doc.runs());
            } catch (final IOException
                    | JacksonException
                    | IllegalArgumentException
                    | NullPointerException
                    | WorkRefusal invalid) {
                throw unavailable(project);
            }
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
                    final var answer =
                            new WorkAnswers.TaskEventReply(
                                    "", WorkAnswers.knownWork(run), WorkAnswers.starts(offers));
                    // Revision changes and cancellation must fit beside the work answer.
                    if (JSON.writeValueAsBytes(answer).length + END_RESERVE > MOST_RECORD) {
                        throw WorkRefusal.recordUnavailable(project, "The work record is full.");
                    }
                }
            } catch (final JacksonException failed) {
                throw unavailable(project);
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
                                    WorkAnswers.knownWork(run),
                                    board.revision().value(),
                                    WorkAnswers.starts(offers));
                    if (JSON.writeValueAsBytes(answer).length + 65536 > MOST_RECORD) {
                        throw WorkRefusal.recordUnavailable(project, "The work record is full.");
                    }
                }
            } catch (final JacksonException failed) {
                throw unavailable(project);
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
            final Path dir = directory;
            if (dir == null) {
                throw unavailable(project);
            }
            Path temporary = null;
            try {
                final Path target = dir.resolve("work.json");
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    privatePath(target, false);
                }
                temporary = dir.resolve("work-" + UUID.randomUUID() + ".tmp");
                try (FileChannel channel =
                        FileChannel.open(
                                temporary,
                                Set.of(
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS),
                                PosixFilePermissions.asFileAttribute(FILE))) {
                    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                temporary = null;
                try (FileChannel channel =
                        FileChannel.open(dir, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    channel.force(true);
                }
            } catch (final IOException | UnsupportedOperationException failed) {
                throw unavailable(project);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (final IOException failed) {
                        throw unavailable(project);
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
                                        snapshot.runs()));
                if (bytes.length > MOST_RECORD) {
                    throw WorkRefusal.recordUnavailable(project, "The work record is full.");
                }
                return bytes;
            } catch (final JacksonException failed) {
                throw unavailable(project);
            }
        }

        private void validate(final Document doc) {
            if (doc.version() != 1
                    || !project.equals(doc.project())
                    || doc.generation() < 0
                    || doc.repository().isBlank()) {
                throw unavailable(project);
            }
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
                                    throw unavailable(project);
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
                                                        throw unavailable(project);
                                                    }
                                                });
                                for (final WorkRun.Offer offer : run.offers()) {
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
                                        throw unavailable(project);
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
                throw unavailable(project);
            } finally {
                mutex.unlock();
            }
        }
    }
}
