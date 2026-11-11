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

    /**
     * A line of a commit's file that holds a word looked for.
     *
     * @param path the file's path in the repository
     * @param line the line's number, from 1
     * @param text the line
     */
    record Found(String path, int line, String text) {}

    /**
     * Finds the lines of a fetched commit's text files that hold one of the words as a whole word,
     * in one search of the whole tree.
     *
     * @param repository the repository's name
     * @param commit the commit id
     * @param words the words, each an issue's id
     * @return each line found, none when nothing holds a word
     */
    List<Found> grep(String repository, String commit, java.util.Collection<String> words);

    /**
     * Makes a commit on a fetched one in the planner's own clone, changing only the files named;
     * nothing leaves the clone.
     *
     * @param repository the repository's name
     * @param parent the commit it follows
     * @param root the directory holding the changed files at their paths
     * @param paths the changed files' paths in the repository; one missing under the root is
     *     deleted
     * @param subject the commit's subject, its whole message
     * @return the new commit's id
     */
    default String commit(
            String repository,
            String parent,
            java.nio.file.Path root,
            List<String> paths,
            String subject) {
        throw new UnsupportedOperationException("This remote saves nothing");
    }

    /**
     * Pushes a commit to a repository's branch, only while the branch still stands where it was
     * fetched.
     *
     * @param repository the repository's name
     * @param url its clone URL
     * @param branch its branch, or {@code null} for the remote's default
     * @param commit the commit pushed
     * @param expected where the branch must stand
     * @throws GitFailure of kind {@code REJECTED} when the branch moved
     */
    default void push(
            String repository,
            CloneUrl url,
            @Nullable String branch,
            String commit,
            String expected) {
        throw new UnsupportedOperationException("This remote saves nothing");
    }

    /**
     * Makes a pushed commit the one last fetched.
     *
     * @param repository the repository's name
     * @param commit the commit
     */
    default void advance(String repository, String commit) {
        throw new UnsupportedOperationException("This remote saves nothing");
    }

    /**
     * Makes a new empty directory for one save, inside the project's cache, which the caller
     * deletes.
     *
     * @return the directory
     */
    default java.nio.file.Path scratch() {
        throw new UnsupportedOperationException("This remote saves nothing");
    }
}
