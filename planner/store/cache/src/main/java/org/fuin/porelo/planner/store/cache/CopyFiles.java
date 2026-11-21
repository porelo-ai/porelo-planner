package org.fuin.porelo.planner.store.cache;

import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;

/**
 * How the platform keeps a file to the planner's account alone, as the directory the copies lie in
 * was made.
 */
public interface CopyFiles {

    /**
     * The attributes a new file of the planner's own is made with.
     *
     * @return the attributes; none where the directory's own entries are inherited
     */
    FileAttribute<?>[] ownerOnlyFile();

    /**
     * Refuses a file that is a link or another account's.
     *
     * @param file the file
     * @param alone whether it must also be closed to others and have one name only
     * @throws IllegalStateException when it is not the account's own
     */
    void requireOwnFile(Path file, boolean alone);
}
