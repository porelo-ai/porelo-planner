package org.fuin.porelo.planner.store.git;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A project's repositories as the store needs them: whether one moved, fetching it, and its files
 * at a commit.
 */
public interface Remote {

    /**
     * Asks a repository's remote for its branch's commit, without fetching anything.
     *
     * @param repository the repository's name
     * @param url its clone URL
     * @param branch its branch, or {@code null} for the remote's default
     * @return the commit id
     */
    String head(String repository, CloneUrl url, @Nullable String branch);

    /**
     * Fetches a repository's branch into the planner's own clone.
     *
     * @param repository the repository's name
     * @param url its clone URL
     * @param branch its branch, or {@code null} for the remote's default
     * @return the commit id fetched
     */
    String fetch(String repository, CloneUrl url, @Nullable String branch);

    /**
     * Answers the commit last fetched into the planner's own clone, without asking the remote.
     *
     * @param repository the repository's name
     * @return the commit id, or empty when nothing was fetched yet
     */
    Optional<String> fetched(String repository);

    /**
     * Reads a file of a fetched commit.
     *
     * @param repository the repository's name
     * @param commit the commit id
     * @param path the file's path in the repository
     * @return its text, or empty when the commit has no such file
     */
    Optional<String> read(String repository, String commit, String path);

    /**
     * Lists the files of a directory of a fetched commit, not those of its subdirectories.
     *
     * @param repository the repository's name
     * @param commit the commit id
     * @param directory the directory's path in the repository
     * @return the files' paths, sorted
     */
    List<String> list(String repository, String commit, String directory);

    /**
     * Lists the subdirectories of a directory of a fetched commit, not those below them.
     *
     * @param repository the repository's name
     * @param commit the commit id
     * @param directory the directory's path in the repository
     * @return the subdirectories' paths, sorted
     */
    List<String> directories(String repository, String commit, String directory);
}
