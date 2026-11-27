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
     * Fetches a repository's branch to read the project's file as a project is opened, leaving the
     * commit the last check fetched as it was, so a board read from the clones reads one moment.
     *
     * @param repository the repository's name
     * @param url its clone URL
     * @param branch its branch, or {@code null} for the remote's default
     * @return the commit id fetched
     */
    default String fetchToOpen(
            final String repository, final CloneUrl url, final @Nullable String branch) {
        return fetch(repository, url, branch);
    }

    /**
     * Answers the commit last fetched into the planner's own clone, without asking the remote.
     *
     * @param repository the repository's name
     * @return the commit id, or empty when nothing was fetched yet
     */
    Optional<String> fetched(String repository);

    /**
     * Fetches the remote's default branch into the planner's own clone, beside the branch the store
     * reads and without moving it: the work rules, and so what a task may do, count only from the
     * default branch, where only a person lets a change in.
     *
     * @param repository the repository's name
     * @param url its clone URL
     * @return the default branch's commit id
     * @throws GitFailure when the remote names no default branch or the fetch fails
     */
    default String fetchDefault(final String repository, final CloneUrl url) {
        throw new UnsupportedOperationException("This remote reads no default branch");
    }

    /**
     * Answers the default branch's commit last fetched into the planner's own clone by {@link
     * #fetchDefault}, without asking the remote.
     *
     * @param repository the repository's name
     * @return the commit id, or empty when the default branch was not fetched yet
     */
    default Optional<String> fetchedDefault(final String repository) {
        return Optional.empty();
    }

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
     * Answers the object id of a plain planning file of a fetched commit, from the listing the
     * commit's files are read with, so no Git process runs per file. Equal ids mean equal bytes.
     *
     * @param repository the repository's name
     * @param commit the commit id
     * @param path the file's path in the repository
     * @return its blob's id, or empty when the commit has no such plain file or the remote cannot
     *     tell, as one that reads nothing from Git
     */
    default Optional<String> object(String repository, String commit, String path) {
        return Optional.empty();
    }

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
