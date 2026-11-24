package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/**
 * One private state file kept durable: a new copy is written to a private sibling and flushed, put
 * in place by an atomic move, and its directory synced, so a crash leaves the file before or the
 * file after, never a torn one; it is read only once checked as the account's own and bounded.
 */
final class DurableFile {

    private final PrivateFiles files;
    private final WorkRecord.Replace replace;
    private final LimitFactors limits;

    /**
     * Keeps files with the platform's private files, an atomic replacing move and the planner's
     * factors.
     */
    DurableFile(final PrivateFiles files, final LimitFactors limits) {
        this(
                files,
                (written, target) ->
                        Files.move(
                                written,
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING),
                limits);
    }

    /** Keeps files with the given private files, replacing a file as told. */
    DurableFile(
            final PrivateFiles files, final WorkRecord.Replace replace, final LimitFactors limits) {
        this.files = files;
        this.replace = replace;
        this.limits = limits;
    }

    /** The file too large to be read. */
    static final class TooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        TooLarge(final Path path, final int most) {
            super("The file " + path.getFileName() + " is larger than " + most + " bytes.");
        }
    }

    /**
     * Puts the bytes in place of the file: flushes a new private sibling, moves it over the file
     * atomically and syncs the directory. The file is unchanged when it throws before the move.
     *
     * @param target the file
     * @param bytes its new content
     * @throws IOException when a step fails; the message names the step
     */
    void write(final Path target, final byte[] bytes) throws IOException {
        final Path dir = Objects.requireNonNull(target.getParent(), "a file in a directory");
        final String name = target.getFileName().toString();
        final String stem = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        Path temporary = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                own(target);
            }
            temporary = dir.resolve(stem + "-" + UUID.randomUUID() + ".tmp");
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
            SharedFiles.whileShared(() -> replace.move(written, target), limits);
            temporary = null;
            // Windows opens no directory as a file; its move is durable once it returns.
            if (!files.windows()) {
                try (FileChannel channel =
                        FileChannel.open(dir, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    channel.force(true);
                }
            }
        } catch (final UnsupportedOperationException failed) {
            throw new IOException(failed.getMessage(), failed);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (final IOException ignored) {
                    // A temporary file is left only after a failed write, whose own reason is the
                    // one the person must see.
                }
            }
        }
    }

    /**
     * Reads the file's bytes once it is checked as the account's own and bounded.
     *
     * @param path the file
     * @param most the most bytes it may hold
     * @return its bytes, or null when there is no file
     * @throws IOException when it is not the account's own, too large or unreadable
     */
    byte @Nullable [] read(final Path path, final int most) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        own(path);
        if (Files.size(path) > most) {
            throw new TooLarge(path, most);
        }
        final byte[] bytes;
        try (var in =
                Channels.newInputStream(
                        FileChannel.open(
                                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            bytes = in.readNBytes(most + 1);
        }
        if (bytes.length > most) {
            throw new TooLarge(path, most);
        }
        return bytes;
    }

    /**
     * Removes the siblings a write left when the planner stopped before its move; the file itself
     * is the one before or the one after, never a sibling.
     *
     * @param target the file
     * @throws IOException when the directory cannot be listed or a sibling not removed
     */
    void removeLeftovers(final Path target) throws IOException {
        final String name = target.getFileName().toString();
        final String stem = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        try (DirectoryStream<Path> left =
                Files.newDirectoryStream(
                        Objects.requireNonNull(target.getParent(), "a file in a directory"),
                        stem + "-*.tmp")) {
            for (final Path each : left) {
                if (Files.isRegularFile(each, LinkOption.NOFOLLOW_LINKS)) {
                    own(each);
                    Files.delete(each);
                }
            }
        }
    }

    /** Refuses a file that is a link, another account's, open to others or linked twice. */
    private void own(final Path path) throws IOException {
        try {
            files.requireOwnFile(path, true);
        } catch (final IllegalStateException | UncheckedIOException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
    }
}
