package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where the window finds the planner: {@code porelo-planner/planner.json} in the account's runtime
 * directory on Linux, in {@code %LOCALAPPDATA%} on Windows, with the port and the token, readable
 * by the account alone.
 *
 * <p>One planner per account: it holds a lock on {@code planner.lock} beside the file for as long
 * as it runs, which the kernel lets go when it dies, so a file found while holding the lock is a
 * dead planner's.
 */
public final class AddressFile implements AutoCloseable {

    /** The directory under the runtime directory. */
    static final String DIRECTORY = "porelo-planner";

    /** The file the window reads. */
    static final String FILE = "planner.json";

    private static final String LOCK = "planner.lock";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The lock files this process holds. */
    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    private final Path directory;

    private final Path lockFile;

    private final FileChannel lockChannel;

    private final FileLock lock;

    private final PrivateFiles files;

    /** The factors of the planner whose file this is, which its refused file steps follow. */
    private final LimitFactors limits;

    /** Deletes the file at the process's end, before Spring waits for the calls in flight. */
    private final Thread atEnd;

    /**
     * Set once {@link #close()} began, so the end's deletion never takes a later planner's file.
     */
    private boolean closed;

    private AddressFile(
            final Path directory,
            final Path lockFile,
            final FileChannel lockChannel,
            final FileLock lock,
            final PrivateFiles files,
            final LimitFactors limits) {
        this.files = files;
        this.limits = limits;
        this.directory = directory;
        this.lockFile = lockFile;
        this.lockChannel = lockChannel;
        this.lock = lock;
        // A window gives its planner a few seconds after SIGTERM, then kills it; the file goes
        // first, so a killed planner leaves none. Spring's own hook runs beside this one.
        this.atEnd = Thread.ofPlatform().name("planner-address-end").unstarted(this::deleteAtEnd);
        try {
            Runtime.getRuntime().addShutdownHook(atEnd);
        } catch (final IllegalStateException ending) {
            // The process ends already; close deletes the file.
        }
    }

    private synchronized void deleteAtEnd() {
        if (closed) {
            return;
        }
        try {
            SharedFiles.whileShared(() -> Files.deleteIfExists(directory.resolve(FILE)), limits);
        } catch (final IOException | RuntimeException e) {
            // Close tries again, if Spring gets to it.
        }
    }

    /**
     * Checks the runtime directory and takes this account's one planner lock.
     *
     * @param runtimeDirectory The account's runtime directory, {@code XDG_RUNTIME_DIR}; may be
     *     empty when it is not set, and is then {@code %LOCALAPPDATA%} on Windows.
     * @return The address file, not yet written.
     * @throws IllegalStateException When the directory is not the account's alone, or a planner
     *     already runs.
     */
    public static AddressFile open(final String runtimeDirectory) {
        return open(runtimeDirectory, Path.of("/run/user"), PrivateFiles.of(System.getenv()));
    }

    /**
     * Checks the runtime directory and takes the lock with this platform's files.
     *
     * @param runtimeDirectory The account's runtime directory; may be empty when it is not set.
     * @param runUser Where the system makes each account's runtime directory, named by its uid.
     * @return The address file, not yet written.
     */
    static AddressFile open(final String runtimeDirectory, final Path runUser) {
        return open(runtimeDirectory, runUser, PrivateFiles.of(System.getenv()));
    }

    /**
     * Refuses the account's own runtime directory, so a planner started for tests is never found by
     * the person's own window.
     *
     * @param runtimeDirectory The runtime directory given; may be empty when it is not set.
     * @param runUser Where the system makes each account's runtime directory, named by its uid.
     * @throws IllegalStateException when none is given or it is the account's own
     */
    static void requireApartFromTheAccount(final String runtimeDirectory, final Path runUser) {
        requireApartFromTheAccount(
                runtimeDirectory, runUser, PrivateFiles.of(System.getenv()), System.getenv());
    }

    /**
     * Refuses the account's own runtime directory on the platform the files are of.
     *
     * @param runtimeDirectory The runtime directory given; may be empty when it is not set.
     * @param runUser Where the system makes each account's runtime directory on Linux.
     * @param files The platform's private files.
     * @param environment The planner's environment, for {@code LOCALAPPDATA} on Windows.
     * @throws IllegalStateException when none is given or it is the account's own
     */
    static void requireApartFromTheAccount(
            final String runtimeDirectory,
            final Path runUser,
            final PrivateFiles files,
            final Map<String, String> environment) {
        final Path own;
        if (files.windows()) {
            own = files.base(PrivateFiles.Kind.RUNTIME, environment);
        } else {
            try {
                own = runUser.resolve(String.valueOf(ownUid()));
            } catch (IOException e) {
                throw new IllegalStateException("this account's uid cannot be read: " + e, e);
            }
        }
        if (runtimeDirectory.isBlank() || same(Path.of(runtimeDirectory), own)) {
            throw new IllegalStateException(
                    "--mock needs a runtime directory of its own: XDG_RUNTIME_DIR is '"
                            + runtimeDirectory
                            + "', not one apart from "
                            + own);
        }
    }

    /**
     * Checks the runtime directory and takes the lock, falling back to the account's own directory
     * under {@code runUser} when none is set.
     *
     * @param runtimeDirectory The account's runtime directory; may be empty when it is not set.
     * @param runUser Where the system makes each account's runtime directory, named by its uid.
     * @param files The platform's private files.
     * @return The address file, not yet written.
     */
    static AddressFile open(
            final String runtimeDirectory, final Path runUser, final PrivateFiles files) {
        return open(runtimeDirectory, runUser, files, LimitFactors.DEFAULT);
    }

    /**
     * Checks the runtime directory and takes the lock as above, a refused file step tried as often
     * as the factor {@code retries} of {@code limits} says.
     *
     * @param runtimeDirectory The account's runtime directory; may be empty when it is not set.
     * @param runUser Where the system makes each account's runtime directory, named by its uid.
     * @param files The platform's private files.
     * @param limits The factors of the planner that opens it.
     * @return The address file, not yet written.
     */
    static AddressFile open(
            final String runtimeDirectory,
            final Path runUser,
            final PrivateFiles files,
            final LimitFactors limits) {
        final Path runtime;
        if (!runtimeDirectory.isBlank()) {
            runtime = Path.of(runtimeDirectory);
        } else if (files.windows()) {
            runtime = files.base(PrivateFiles.Kind.RUNTIME, System.getenv());
        } else {
            // The window reads the same place when XDG_RUNTIME_DIR is unset; the checks below
            // still hold for it.
            try {
                runtime = runUser.resolve(String.valueOf(ownUid()));
            } catch (IOException e) {
                throw new IllegalStateException("this account's uid cannot be read: " + e, e);
            }
        }
        if (!runtime.isAbsolute()) {
            throw new IllegalStateException(
                    "XDG_RUNTIME_DIR is '" + runtimeDirectory + "', not an absolute path");
        }
        try {
            final Path directory =
                    files.directories(
                            runtime,
                            false,
                            PrivateFiles.Kind.RUNTIME,
                            files.steps(PrivateFiles.Kind.RUNTIME));
            final Path lockFile = directory.resolve(LOCK);
            // A second channel on the lock file, even one closed at once, would let go of this
            // process's lock; so a start here is refused before it opens one.
            if (!HELD.add(lockFile)) {
                throw alreadyRuns(directory);
            }
            try {
                final FileChannel channel = openLock(lockFile, files);
                final FileLock lock = lockOrClose(channel);
                if (lock == null) {
                    throw alreadyRuns(directory);
                }
                // Held, so a file here is a dead planner's; gone before a window reads its port.
                SharedFiles.whileShared(
                        () -> Files.deleteIfExists(directory.resolve(FILE)), limits);
                return new AddressFile(directory, lockFile, channel, lock, files, limits);
            } catch (IOException | RuntimeException e) {
                HELD.remove(lockFile);
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "the planner's runtime directory " + runtime + " cannot be used: " + e, e);
        }
    }

    /**
     * Writes the port and the token for the window, replacing a dead planner's file in one step.
     *
     * @param port The port the planner listens on.
     * @param token The token every call must carry.
     * @param log The planner's log file, which the window may open; {@code null} when it keeps
     *     none.
     */
    public void publish(final int port, final AccessToken token, final @Nullable Path log) {
        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("port", port);
        fields.put("token", token.text());
        // The window tells a dead planner's file by these: the process and when it started.
        final ProcessHandle self = ProcessHandle.current();
        fields.put("pid", self.pid());
        self.info().startInstant().ifPresent(started -> fields.put("started", started.toString()));
        if (log != null) {
            fields.put("log", log.toAbsolutePath().toString());
        }
        // Written by Jackson, so a path holding a quote or a backslash stays one string.
        final String json = JSON.writeValueAsString(fields) + "\n";
        Path temporary = null;
        try {
            temporary = directory.resolve("planner-" + UUID.randomUUID() + ".tmp");
            // Written through the channel that made it, never opened again by its name.
            try (SeekableByteChannel out =
                    Files.newByteChannel(
                            temporary,
                            Set.of(
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS),
                            files.ownerOnlyFile())) {
                final ByteBuffer bytes = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    out.write(bytes);
                }
            }
            final Path written = temporary;
            SharedFiles.whileShared(
                    () ->
                            Files.move(
                                    written,
                                    directory.resolve(FILE),
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING),
                    limits);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "the address file " + directory.resolve(FILE) + " cannot be written: " + e, e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /** The factors its refused file steps follow. */
    LimitFactors limits() {
        return limits;
    }

    /** The file the window reads. */
    Path path() {
        return directory.resolve(FILE);
    }

    /** Deletes the address file while the lock is still held, then lets the lock go. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(atEnd);
        } catch (final IllegalStateException ending) {
            // The process ends; the hook runs or ran, and found the file closed or deleted it.
        }
        org.slf4j.LoggerFactory.getLogger(AddressFile.class)
                .info("The planner ends; its address file {} is removed", directory.resolve(FILE));
        try {
            SharedFiles.whileShared(() -> Files.deleteIfExists(directory.resolve(FILE)), limits);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "the address file " + directory.resolve(FILE) + " cannot be deleted: " + e, e);
        } finally {
            try {
                lock.release();
                lockChannel.close();
            } catch (IOException e) {
                // The kernel lets the lock go when the process ends, which is about to happen.
            } finally {
                HELD.remove(lockFile);
            }
        }
    }

    /**
     * Whether two paths name one directory: by the file system where both exist, so a short name, a
     * link or another spelling of the account's own directory is told for what it is.
     */
    private static boolean same(final Path given, final Path own) {
        final Path plain = given.toAbsolutePath().normalize();
        if (plain.equals(own)) {
            return true;
        }
        try {
            return Files.exists(plain) && Files.exists(own) && Files.isSameFile(plain, own);
        } catch (IOException e) {
            // Not told apart: refused as the account's own.
            return true;
        }
    }

    private static int ownUid() throws IOException {
        return (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
    }

    private static IllegalStateException alreadyRuns(final Path directory) {
        return new IllegalStateException(
                "a planner already runs for this account; its address is in "
                        + directory.resolve(FILE));
    }

    private static FileChannel openLock(final Path file, final PrivateFiles files)
            throws IOException {
        final FileChannel channel =
                FileChannel.open(
                        file,
                        Set.of(
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS),
                        files.ownerOnlyFile());
        try {
            files.requireOwnFile(file, false);
            return channel;
        } catch (RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** The lock, or {@code null} with the channel closed when another planner holds it. */
    private static @Nullable FileLock lockOrClose(final FileChannel channel) throws IOException {
        try {
            final FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
            }
            return lock;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static void deleteQuietly(final @Nullable Path temporary) {
        if (temporary != null) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                // Owner-only in an owner-only directory, and gone with the runtime directory at
                // logout.
            }
        }
    }
}
