package org.fuin.porelo.planner.store.files;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Map;

/**
 * The planner's directories and files that only its account may read or change - the address file
 * with its token, the work record, the clones and the log - made and checked the way the platform
 * keeps them to one account: by mode on Linux, by owner and access control list on Windows.
 */
public interface PrivateFiles {

    /**
     * The platform's own.
     *
     * @param environment the planner's environment
     * @return POSIX modes where the file system has them, access control lists otherwise
     */
    static PrivateFiles of(final Map<String, String> environment) {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                ? new PosixPrivateFiles()
                : WindowsPrivateFiles.of(environment);
    }

    /**
     * Whether this is the Windows form, which keeps every kind under {@code %LOCALAPPDATA%}.
     *
     * @return true on Windows
     */
    boolean windows();

    /**
     * The base the planner's directories of a kind lie under, before any is made or checked.
     *
     * @param kind which of them
     * @param environment the planner's environment
     * @return an absolute path
     * @throws IllegalStateException when the environment names no absolute directory for it
     */
    Path base(Kind kind, Map<String, String> environment);

    /**
     * The steps below {@link #base} to the planner's own directory of a kind.
     *
     * @param kind which of them
     * @return the names: below {@code porelo-planner}, or for {@link Kind#LOGS} below {@code
     *     porelo}, which other parts of Porelo share
     */
    List<String> steps(Kind kind);

    /**
     * Checks the way to a base and makes each step below it the account's alone, or checks the one
     * already there; for {@link Kind#LOGS} only the last step must be the account's alone, and a
     * step above it, which other parts of Porelo share, is held only to the rules of a directory on
     * the way.
     *
     * @param base the base, absolute and in its plain form
     * @param create whether a missing base is made; otherwise it must exist
     * @param kind which of them, as each platform holds the base of each kind to its own rule
     * @param steps the names below it, each made if missing
     * @return the deepest directory
     * @throws IllegalStateException when the base, a directory above it or a step could be read,
     *     changed or swapped by another account
     */
    Path directories(Path base, boolean create, Kind kind, List<String> steps);

    /**
     * One call of {@link #directories}, asked for ahead of it.
     *
     * @param base the base
     * @param create whether a missing base is made
     * @param kind which of them
     * @param steps the names below it
     */
    record Request(Path base, boolean create, Kind kind, List<String> steps) {

        /** Keeps its own copy of the steps. */
        public Request {
            steps = List.copyOf(steps);
        }
    }

    /**
     * Checks several calls of {@link #directories} in one go ahead of them, where each check is
     * slow; each call that passed then answers once without checking again, until {@link #forget}.
     * A call that did not pass, or was not asked for, checks on its own as before.
     *
     * @param requests the calls to come
     */
    default void prepare(final List<Request> requests) {
        // A check here is quick, so each call makes its own.
    }

    /** Drops what {@link #prepare} kept, so every later call checks anew. */
    default void forget() {
        // Nothing is kept here.
    }

    /**
     * The attributes a new file of the planner's own is made with, inside a directory of {@link
     * #directories}.
     *
     * @return the attributes; none where the directory's own entries are inherited
     */
    FileAttribute<?>[] ownerOnlyFile();

    /**
     * Refuses a file inside a directory of {@link #directories} that is a link or another
     * account's.
     *
     * @param file the file
     * @param alone whether it must also be closed to others and have one name only
     * @throws IllegalStateException when it is not the account's own
     */
    void requireOwnFile(Path file, boolean alone);

    /**
     * Checks an existing absolute directory and its ancestors without creating anything.
     *
     * @param directory the account's private directory, reached through no link
     * @throws IllegalStateException when it is missing, shared or reached through a link
     */
    default void requireExistingDirectory(final Path directory) {
        throw new IllegalStateException("Existing directory checks are not available");
    }

    /**
     * Checks an item placed by a person, including its owner and private access rights.
     *
     * @param file the regular file in a checked private directory
     */
    default void requireItemFile(final Path file) {
        requireOwnFile(file, true);
    }

    /** The planner's kinds of private directories. */
    enum Kind {
        /** The address file and its lock, which the window reads. */
        RUNTIME,
        /** The local work record. */
        STATE,
        /** The clones. */
        CACHE,
        /** The local planning items. */
        DATA,
        /** The log, in the directory the window keeps its own log in. */
        LOGS
    }
}
