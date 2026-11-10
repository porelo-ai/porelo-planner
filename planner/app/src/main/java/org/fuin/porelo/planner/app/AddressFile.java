package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Where the window finds the planner: {@code porelo-planner/planner.json} in the account's runtime
 * directory, with the port and the token, readable by the account alone.
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

    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.fromString("rwx------");

    private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
            PosixFilePermissions.fromString("rw-------");

    /** The lock files this process holds. */
    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    private final Path directory;

    private final Path lockFile;

    private final FileChannel lockChannel;

    private final FileLock lock;

    private AddressFile(
            final Path directory,
            final Path lockFile,
            final FileChannel lockChannel,
            final FileLock lock) {
        this.directory = directory;
        this.lockFile = lockFile;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    /**
     * Checks the runtime directory and takes this account's one planner lock.
     *
     * @param runtimeDirectory The account's runtime directory, {@code XDG_RUNTIME_DIR}; may be
     *     empty when it is not set.
     * @return The address file, not yet written.
     * @throws IllegalStateException When the directory is not the account's alone, or a planner
     *     already runs.
     */
    public static AddressFile open(final String runtimeDirectory) {
        if (runtimeDirectory.isBlank()) {
            throw new IllegalStateException(
                    "XDG_RUNTIME_DIR is not set, so there is no place only this account can read");
        }
        final Path runtime = Path.of(runtimeDirectory);
        if (!runtime.isAbsolute()) {
            throw new IllegalStateException(
                    "XDG_RUNTIME_DIR is '" + runtimeDirectory + "', not an absolute path");
        }
        try {
            final int uid = ownUid();
            requireOwnersAlone(runtime, uid);
            final Path directory = runtime.resolve(DIRECTORY);
            createOwnerOnlyDirectory(directory);
            requireOwnersAlone(directory, uid);
            final Path lockFile = directory.resolve(LOCK);
            // A second channel on the lock file, even one closed at once, would let go of this
            // process's lock; so a start here is refused before it opens one.
            if (!HELD.add(lockFile)) {
                throw alreadyRuns(directory);
            }
            try {
                final FileChannel channel = openLock(lockFile, uid);
                final FileLock lock = lockOrClose(channel);
                if (lock == null) {
                    throw alreadyRuns(directory);
                }
                // Held, so a file here is a dead planner's; gone before a window reads its port.
                Files.deleteIfExists(directory.resolve(FILE));
                return new AddressFile(directory, lockFile, channel, lock);
            } catch (IOException | RuntimeException e) {
                HELD.remove(lockFile);
                throw e;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "the planner's runtime directory " + runtime + " cannot be used: " + e, e);
        }
    }

    /**
     * Writes the port and the token for the window, replacing a dead planner's file in one step.
     *
     * @param port The port the planner listens on.
     * @param token The token every call must carry.
     */
    public void publish(final int port, final AccessToken token) {
        final String json = "{\"port\":" + port + ",\"token\":\"" + token.text() + "\"}\n";
        Path temporary = null;
        try {
            temporary =
                    Files.createTempFile(
                            directory,
                            "planner-",
                            ".tmp",
                            PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE));
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    directory.resolve(FILE),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "the address file " + directory.resolve(FILE) + " cannot be written: " + e, e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /** The file the window reads. */
    Path path() {
        return directory.resolve(FILE);
    }

    /** Deletes the address file while the lock is still held, then lets the lock go. */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(directory.resolve(FILE));
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

    private static int ownUid() throws IOException {
        return (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
    }

    private static void requireOwnersAlone(final Path path, final int uid) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(path + " is not a directory, or is a link");
        }
        final int owner = (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        if (owner != uid) {
            throw new IllegalStateException(
                    path + " belongs to account " + owner + ", not to this one (" + uid + ")");
        }
        final Set<PosixFilePermission> mode =
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!mode.equals(OWNER_ONLY_DIRECTORY)) {
            throw new IllegalStateException(
                    path + " has mode " + PosixFilePermissions.toString(mode) + ", not rwx------");
        }
    }

    private static IllegalStateException alreadyRuns(final Path directory) {
        return new IllegalStateException(
                "a planner already runs for this account; its address is in "
                        + directory.resolve(FILE));
    }

    /** Makes the directory owner-only unless it exists; one made by a racing start is checked. */
    private static void createOwnerOnlyDirectory(final Path directory) throws IOException {
        try {
            Files.createDirectory(
                    directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY));
        } catch (FileAlreadyExistsException exists) {
            return;
        }
        // The umask may have taken bits the attribute asked for; owner-only is set again exactly.
        Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
    }

    private static FileChannel openLock(final Path file, final int uid) throws IOException {
        final FileChannel channel =
                FileChannel.open(
                        file,
                        Set.of(
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE));
        try {
            final int owner =
                    (Integer) Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (owner != uid) {
                throw new IllegalStateException(file + " belongs to another account");
            }
            return channel;
        } catch (IOException | RuntimeException e) {
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
