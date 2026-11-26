package org.fuin.porelo.planner.store.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.files.HostSystem;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.jspecify.annotations.Nullable;

/**
 * The directory the person chose for each local project's stories and issues, kept in the planner's
 * own state on this computer and never in the project's files: {@code
 * $XDG_STATE_HOME/porelo-planner/<project>/items.json} on Linux, {@code
 * %LOCALAPPDATA%\porelo-planner\state\<project>\items.json} on Windows. No file means the default
 * directory. A choice is written to a new file first and moved into place, so a reader sees the one
 * before or the one after, never a part.
 */
public final class ItemsDirectories {

    /** The file a project's choice is kept in, inside its state directory. */
    static final String FILE = "items.json";

    /** The longest directory taken, in bytes of UTF-8. */
    public static final int MOST_PATH_BYTES = 4095;

    /** The largest kept choice read, in bytes: a path with every character escaped, and more. */
    static final int MOST_BYTES = 2 * MOST_PATH_BYTES + 64;

    /** Why a directory is refused: it does not exist. */
    public static final String MISSING = "The directory does not exist.";

    /** Why a directory is refused: it is a file or something else. */
    public static final String NOT_A_DIRECTORY = "The path is not a directory.";

    /** Why a directory is refused: it, or a directory above it, is a link. */
    public static final String LINK = "The path is or passes through a symbolic link.";

    /** Why a directory is refused: another account owns it or may write it or one above it. */
    public static final String NOT_OWN = "The directory is not this account's alone.";

    /** Why a directory is refused: the text is no absolute path in normal form. */
    public static final String NOT_NORMAL = "The path is not an absolute path in normal form.";

    /** Why a directory is refused: the text is too long. */
    public static final String TOO_LONG =
            "The path is longer than " + MOST_PATH_BYTES + " bytes in UTF-8.";

    /** Why a project's reads are refused: its kept choice cannot be read. */
    public static final String NOT_WRITTEN_HERE =
            "The kept choice of directory is not one the planner wrote.";

    private static final String DIRECTORY = "directory";

    private final PrivateFiles files;

    private final Map<String, String> environment;

    /**
     * Keeps the choices in the planner's state directory.
     *
     * @param files the account's checks of directories and files
     * @param environment the planner's environment, which names the state directory
     */
    public ItemsDirectories(final PrivateFiles files, final Map<String, String> environment) {
        this.files = files;
        this.environment = Map.copyOf(environment);
    }

    /** A project's kept choice. */
    public sealed interface Kept {}

    /** No directory was chosen: the default is used. */
    public record Default() implements Kept {}

    /**
     * A directory was chosen.
     *
     * @param directory the directory, absolute and in its plain form
     */
    public record Chosen(Path directory) implements Kept {}

    /**
     * The kept choice cannot be read, so no directory is used, the default neither; the window is
     * told {@link #NOT_WRITTEN_HERE}.
     *
     * @param reason why, in fixed words for the log
     */
    public record Unusable(String reason) implements Kept {}

    /**
     * Reads a project's kept choice.
     *
     * @param project the project's name
     * @return the choice
     */
    public Kept kept(final String project) {
        final Path file;
        try {
            file = directory(project).resolve(FILE);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return new Default();
            }
            files.requireOwnFile(file, true);
        } catch (final IllegalStateException | UncheckedIOException e) {
            return new Unusable(
                    "the planner's state directory or its kept choice of directory is not this"
                            + " account's alone");
        }
        final byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MOST_BYTES + 1);
        } catch (final NoSuchFileException e) {
            return new Default();
        } catch (final IOException e) {
            return new Unusable("the kept choice of directory cannot be read");
        }
        final JsonNode node;
        try {
            node = bytes.length > MOST_BYTES ? null : LocalItem.JSON.readTree(bytes);
        } catch (final IOException e) {
            return new Unusable("the kept choice of directory is not one the planner wrote");
        }
        if (node == null
                || !node.isObject()
                || node.size() != 1
                || !node.path(DIRECTORY).isTextual()) {
            return new Unusable("the kept choice of directory is not one the planner wrote");
        }
        final Path directory = shaped(node.path(DIRECTORY).asText());
        return directory == null
                ? new Unusable("the kept choice of directory is not one the planner wrote")
                : new Chosen(directory);
    }

    /**
     * Keeps a project's choice, replacing the one before at once, or drops it for the default.
     *
     * @param project the project's name
     * @param directory the directory, checked by {@link #checked}, or null for the default
     * @throws UncheckedIOException when it cannot be written
     * @throws IllegalStateException when the state directory is not the account's alone
     */
    public synchronized void keep(final String project, final @Nullable Path directory) {
        final Path state = directory(project);
        final Path file = state.resolve(FILE);
        try {
            if (directory == null) {
                Files.deleteIfExists(file);
                return;
            }
            final Path written = state.resolve(FILE + "." + UUID.randomUUID() + ".new");
            try {
                Files.createFile(written, files.ownerOnlyFile());
                Files.write(
                        written,
                        LocalItem.JSON.writeValueAsBytes(Map.of(DIRECTORY, directory.toString())),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(
                        written,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(written);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("The choice of directory cannot be kept", e);
        }
    }

    /**
     * Checks a directory the person chose: a text of the shape {@link #shapeFault} takes, that
     * exists, is a directory, is reached through no link and is the account's alone. It is never
     * made.
     *
     * @param text the directory as the window sent it
     * @return the directory
     * @throws StoreException.ItemsDirectoryRefused naming it when it is such a path but cannot be
     *     used, or naming none when it is no such path
     */
    public Path checked(final String text) {
        final String fault = shapeFault(text);
        if (fault != null) {
            throw new StoreException.ItemsDirectoryRefused(null, fault);
        }
        final Path directory = Path.of(text);
        check(files, directory);
        return directory;
    }

    /**
     * Checks a directory as {@link #checked} does, at every read of a store over it.
     *
     * @param files the account's checks
     * @param directory the directory
     * @throws StoreException.ItemsDirectoryRefused naming it when it cannot be used
     */
    static void check(final PrivateFiles files, final Path directory) {
        final String fault = fault(files, directory);
        if (fault != null) {
            throw new StoreException.ItemsDirectoryRefused(directory.toString(), fault);
        }
    }

    /**
     * Why a directory cannot be used, in one of the fixed phrases, or null when it can: each step
     * from the root is looked at without following a link, then the account's checks run.
     */
    private static @Nullable String fault(final PrivateFiles files, final Path directory) {
        final Path root = directory.getRoot();
        if (root == null) {
            return NOT_NORMAL;
        }
        Path walked = root;
        for (final Path step : directory) {
            walked = walked.resolve(step);
            final BasicFileAttributes attributes;
            try {
                attributes =
                        Files.readAttributes(
                                walked, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (final NoSuchFileException e) {
                return MISSING;
            } catch (final IOException e) {
                return NOT_OWN;
            }
            // A Windows junction is answered as neither a link nor a directory.
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                return LINK;
            }
            if (!attributes.isDirectory()) {
                return NOT_A_DIRECTORY;
            }
        }
        try {
            files.requireExistingDirectory(directory);
        } catch (final IllegalStateException | UncheckedIOException e) {
            // The checks' words name paths, which may name the person's account.
            return NOT_OWN;
        }
        return null;
    }

    /** The text as a directory when it has the shape of one, else null. */
    static @Nullable Path shaped(final String text) {
        return shapeFault(text) == null ? Path.of(text) : null;
    }

    /**
     * Why a text is no directory of the planner's rule, in one of the fixed phrases, or null when
     * it is one: an absolute path in normal form of at most {@link #MOST_PATH_BYTES} bytes in
     * UTF-8, without a control or bidirectional character or an unpaired surrogate, without white
     * space at either end, and on Windows a drive path.
     *
     * @param text the text
     * @return the phrase, or null
     */
    public static @Nullable String shapeFault(final String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MOST_PATH_BYTES) {
            return TOO_LONG;
        }
        // White space at the start leaves no absolute path, here or as a drive path on Windows.
        if (text.isEmpty()
                || !plain(text)
                || blank(text.codePointBefore(text.length()))
                || (HostSystem.WINDOWS && !DRIVE.matcher(text).matches())) {
            return NOT_NORMAL;
        }
        final Path directory;
        try {
            directory = Path.of(text);
        } catch (final InvalidPathException e) {
            return NOT_NORMAL;
        }
        return directory.isAbsolute()
                        && directory.equals(directory.normalize())
                        && directory.toString().equals(text)
                ? null
                : NOT_NORMAL;
    }

    /** A drive path on Windows: a letter, a colon and a backslash. */
    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:\\\\.*", Pattern.DOTALL);

    private static boolean plain(final String text) {
        return text.codePoints()
                .noneMatch(
                        c ->
                                c < 0x20
                                        || (c >= 0x7f && c <= 0x9f)
                                        || c == 0x2028
                                        || c == 0x2029
                                        || (c >= 0x202a && c <= 0x202e)
                                        || (c >= 0x2066 && c <= 0x2069)
                                        // An unpaired surrogate is answered as its own code point.
                                        || (c >= 0xd800 && c <= 0xdfff));
    }

    private static boolean blank(final int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** The project's state directory, made or checked as the account's alone. */
    private Path directory(final String project) {
        final List<String> steps = new ArrayList<>(files.steps(PrivateFiles.Kind.STATE));
        steps.add(Ids.name(project));
        return files.directories(
                files.base(PrivateFiles.Kind.STATE, environment),
                true,
                PrivateFiles.Kind.STATE,
                steps);
    }
}
