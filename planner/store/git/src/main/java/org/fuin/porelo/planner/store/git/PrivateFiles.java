package org.fuin.porelo.planner.store.git;

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

    /** The planner's kinds of private directories. */
    enum Kind {
        /** The address file and its lock, which the window reads. */
        RUNTIME,
        /** The local work record. */
        STATE,
        /** The clones. */
        CACHE,
        /** The log, in the directory the window keeps its own log in. */
        LOGS
    }
}
