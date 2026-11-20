package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.git.PrivateFiles;
import org.fuin.porelo.planner.store.github.CarryOver;
import org.fuin.porelo.planner.store.github.CarryOverMap;
import org.fuin.porelo.planner.store.github.ProjectRef;
import org.jspecify.annotations.Nullable;

/**
 * The carry-over's map in its own file, the account's alone, held under a lock while a run uses it
 * and replaced whole on every save, so a stop leaves the last save readable.
 */
final class CarryOverState implements CarryOver.Journal, AutoCloseable {

    /** The directory below the planner's state directory that holds the maps. */
    static final String DIRECTORY = "carry-over";

    /** The largest map read. */
    static final int MOST_BYTES = 16 << 20;

    private final Path file;

    private final PrivateFiles files;

    private final FileChannel channel;

    private final FileLock lock;

    private CarryOverState(
            final Path file,
            final PrivateFiles files,
            final FileChannel channel,
            final FileLock lock) {
        this.file = file;
        this.files = files;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Answers where a GitHub project's map is kept when no file is named: under the planner's state
     * directory, {@code carry-over/<owner>-<number>.json}.
     *
     * @param files the platform's private files
     * @param environment the planner's environment
     * @param target the GitHub project
     * @return the file
     * @throws IllegalStateException when the directory cannot be made the account's alone
     */
    static Path defaultFile(
            final PrivateFiles files,
            final Map<String, String> environment,
            final ProjectRef target) {
        final List<String> steps = new ArrayList<>(files.steps(PrivateFiles.Kind.STATE));
        steps.add(DIRECTORY);
        return files.directories(
                        files.base(PrivateFiles.Kind.STATE, environment),
                        true,
                        PrivateFiles.Kind.STATE,
                        steps)
                .resolve(target.owner() + "-" + target.number() + ".json");
    }

    /**
     * Takes the lock of a map's file, beside it.
     *
     * @param file the map's file, in a directory that stands
     * @param files the platform's private files
     * @return the held state
     * @throws IOException when the directory does not stand or another account could read or change
     *     it, the lock is another account's or a link, or another run holds it
     */
    static CarryOverState open(final Path file, final PrivateFiles files) throws IOException {
        final Path absolute = file.toAbsolutePath().normalize();
        final Path directory = absolute.getParent();
        if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the map's directory does not stand");
        }
        try {
            // Held to the rule of the default place: another account can neither read the map
            // nor swap it.
            files.directories(directory, false, PrivateFiles.Kind.RUNTIME, List.of());
        } catch (final IllegalStateException | UncheckedIOException e) {
            throw new IOException(String.valueOf(e.getMessage()), e);
        }
        final Path lockFile = directory.resolve(absolute.getFileName() + ".lock");
        try {
            Files.createFile(lockFile, files.ownerOnlyFile());
        } catch (final FileAlreadyExistsException exists) {
            // The lock's name stays from run to run.
        }
        own(files, lockFile);
        final FileChannel channel =
                FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        final FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (final IOException | OverlappingFileLockException e) {
            channel.close();
            throw new IOException("another carry-over holds the map", e);
        }
        if (lock == null) {
            channel.close();
            throw new IOException("another carry-over holds the map");
        }
        return new CarryOverState(absolute, files, channel, lock);
    }

    /**
     * Answers the map's file.
     *
     * @return its path
     */
    Path file() {
        return file;
    }

    /**
     * Reads the map an earlier run left.
     *
     * @return the map, or null when there is none
     * @throws IOException when it is not the account's alone or larger than a map can be
     * @throws IllegalArgumentException when it is no map
     */
    @Nullable CarryOverMap read() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        own(files, file);
        final byte[] bytes;
        try (InputStream in =
                Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MOST_BYTES + 1);
        }
        if (bytes.length > MOST_BYTES) {
            throw new IOException("the map is larger than " + MOST_BYTES + " bytes");
        }
        return CarryOverMap.read(bytes);
    }

    @Override
    public void save(final CarryOverMap map) {
        final Path written = file.resolveSibling(file.getFileName() + ".new");
        try {
            Files.deleteIfExists(written);
            Files.createFile(written, files.ownerOnlyFile());
            own(files, written);
            Files.write(
                    written,
                    map.json(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.SYNC,
                    LinkOption.NOFOLLOW_LINKS);
            Files.move(
                    written,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            throw new UncheckedIOException("the map could not be saved", e);
        }
    }

    /**
     * Removes the map once the run ended whole.
     *
     * @throws IOException when it cannot be removed
     */
    void remove() throws IOException {
        Files.deleteIfExists(file);
    }

    @Override
    public void close() throws IOException {
        try (channel) {
            lock.release();
        }
    }

    private static void own(final PrivateFiles files, final Path path) throws IOException {
        try {
            files.requireOwnFile(path, true);
        } catch (final IllegalStateException | UncheckedIOException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
    }
}
