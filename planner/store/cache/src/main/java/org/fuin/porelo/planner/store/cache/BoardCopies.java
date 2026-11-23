package org.fuin.porelo.planner.store.cache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The copies of the boards the planner held, one file per project in a directory only its account
 * may read. A copy that cannot be used is ignored and named in the log, never fatal: the board is
 * then read whole from its tracker. A copy has no size bound: it is read through a stream, never
 * held whole beside what it decodes to, and what its decoding may take is bounded by its file's own
 * size.
 */
public final class BoardCopies {

    private static final Logger LOG = LoggerFactory.getLogger(BoardCopies.class);

    /** What every copy's file begins with, before its encoding's name and the records' format. */
    static final String HEAD = "porelo-board-copy";

    /**
     * The format of the records a copy is written with: a digest of their names, components and
     * types, so a copy written by a planner whose records differ is not read as this one's.
     */
    public static final String FORMAT = format(BoardCopy.class);

    private final Path directory;

    private final CopyFiles files;

    private final CopyEncoding encoding;

    /**
     * Keeps the copies in a directory, in Apache Fory's format.
     *
     * @param directory a directory only the planner's account may read or change
     * @param files how the platform keeps a file to the account
     */
    public BoardCopies(final Path directory, final CopyFiles files) {
        this(directory, files, new ForyEncoding());
    }

    /**
     * Keeps the copies in a directory, in an encoding of their own.
     *
     * @param directory a directory only the planner's account may read or change
     * @param files how the platform keeps a file to the account
     * @param encoding how a copy becomes its file's bytes
     */
    public BoardCopies(final Path directory, final CopyFiles files, final CopyEncoding encoding) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.files = Objects.requireNonNull(files, "files");
        this.encoding = Objects.requireNonNull(encoding, "encoding");
    }

    /**
     * Reads a project's copy, if one is there that this planner can use for the project's files as
     * they stand.
     *
     * @param project the project
     * @param filesCommit the commit the project's files are read from now
     * @return the copy, or empty when none is there or it cannot be used
     */
    public Optional<BoardCopy> read(final BoardCopy.Project project, final String filesCommit) {
        final Path file = directory.resolve(project.file());
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        final BoardCopy copy;
        try {
            files.requireOwnFile(file, true);
            try (SeekableByteChannel channel =
                    Files.newByteChannel(
                            file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                // The size of the file opened, which bounds what its decoding may take.
                final long size = channel.size();
                final InputStream in = Channels.newInputStream(channel);
                final byte[] head = head();
                if (size < head.length || !Arrays.equals(in.readNBytes(head.length), head)) {
                    return ignored(file, "it was written in another format or encoding");
                }
                copy = encoding.decode(in, size - head.length);
            }
        } catch (final IOException | IllegalStateException | UncheckedIOException e) {
            return ignored(file, "it could not be read as the account's own file");
        } catch (final IllegalArgumentException | NullPointerException e) {
            // The encoding's words name only what is wrong, never the file's own text.
            return ignored(
                    file,
                    "it is no copy of a board ("
                            + (e instanceof IllegalArgumentException
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName())
                            + ")");
        }
        if (!copy.project().equals(project)) {
            return ignored(file, "it is the copy of another project");
        }
        if (!copy.filesCommit().equals(filesCommit)) {
            LOG.info(
                    "The board's copy of {} was held with other project files and is read anew",
                    project.file());
            return Optional.empty();
        }
        return Optional.of(copy);
    }

    /**
     * Writes a project's copy in place of the one before, whole or not at all.
     *
     * @param copy the copy
     * @throws UncheckedIOException when it cannot be written
     */
    public void write(final BoardCopy copy) {
        final Path file = directory.resolve(copy.project().file());
        final Path written = file.resolveSibling(file.getFileName() + ".new");
        boolean moved = false;
        try {
            Files.deleteIfExists(written);
            Files.createFile(written, files.ownerOnlyFile());
            files.requireOwnFile(written, true);
            try (OutputStream out =
                    new BufferedOutputStream(
                            Files.newOutputStream(
                                    written,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    LinkOption.NOFOLLOW_LINKS))) {
                out.write(head());
                encoding.encode(copy, out);
            }
            Files.move(
                    written,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } catch (final IOException e) {
            throw new UncheckedIOException("the board's copy could not be written", e);
        } finally {
            if (!moved) {
                removeQuietly(written);
            }
        }
    }

    /**
     * Removes a project's copy, so a board the planner no longer trusts is never shown again.
     *
     * @param project the project
     */
    public void remove(final BoardCopy.Project project) {
        try {
            Files.deleteIfExists(directory.resolve(project.file()));
        } catch (final IOException e) {
            LOG.warn(
                    "The board's copy of {} could not be removed: {}",
                    project.file(),
                    e.getClass().getSimpleName());
        }
    }

    /** Removes a copy half written, so no part of it is left beside the copies. */
    private static void removeQuietly(final Path written) {
        try {
            Files.deleteIfExists(written);
        } catch (final IOException e) {
            LOG.warn("A half written copy {} could not be removed", written.getFileName());
        }
    }

    /** The line a file of this encoding and format begins with. */
    private byte[] head() {
        return (HEAD + " " + encoding.name() + " " + FORMAT + "\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static Optional<BoardCopy> ignored(final Path file, final String why) {
        LOG.warn("The board's copy {} is not used, since {}", file.getFileName(), why);
        return Optional.empty();
    }

    /**
     * Every record a copy is made of, found through the components of {@link BoardCopy}: what its
     * encoding and a native image's reflection must both know.
     *
     * @return the records, the copy's own first
     */
    public static Set<Class<?>> records() {
        final Set<Class<?>> seen = new LinkedHashSet<>();
        describe(BoardCopy.class, new StringBuilder(), seen);
        return Collections.unmodifiableSet(seen);
    }

    /** The digest of a record's tree of components, as {@link #FORMAT} describes it. */
    static String format(final Class<?> root) {
        final StringBuilder description = new StringBuilder();
        describe(root, description, new LinkedHashSet<>());
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(description.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    private static void describe(
            final Class<?> type, final StringBuilder description, final Set<Class<?>> seen) {
        if (!type.isRecord() || !seen.add(type)) {
            return;
        }
        // The simple name: a record moved to another package keeps its format.
        description.append(type.getSimpleName()).append('(');
        for (final RecordComponent component : type.getRecordComponents()) {
            description
                    .append(component.getName())
                    .append(':')
                    .append(component.getGenericType().getTypeName())
                    .append(';');
        }
        description.append(')');
        for (final RecordComponent component : type.getRecordComponents()) {
            describeType(component.getGenericType(), description, seen);
        }
    }

    private static void describeType(
            final Type type, final StringBuilder description, final Set<Class<?>> seen) {
        if (type instanceof Class<?> plain) {
            describe(plain, description, seen);
        } else if (type instanceof ParameterizedType parameterized) {
            for (final Type argument : parameterized.getActualTypeArguments()) {
                describeType(argument, description, seen);
            }
        }
    }
}
