package org.fuin.porelo.planner.store.git;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The refs of the planner's own clones that hold saves not yet pushed, and the Git work a push that
 * the remote rejected needs; nothing here reaches a remote but {@link #fetchRemote}.
 */
interface LocalRefs {

    /**
     * Answers the save not yet pushed, the last of them.
     *
     * @param repository the repository's name
     * @return its commit, or empty when every save is pushed
     */
    Optional<String> local(String repository);

    /**
     * Moves the ref of saves not yet pushed, only while it stands where expected.
     *
     * @param repository the repository's name
     * @param to the new commit
     * @param expected where it must stand, or {@code null} where it must not exist yet
     * @throws GitFailure when it stood elsewhere
     */
    void move(String repository, String to, @Nullable String expected);

    /**
     * Removes the ref of saves not yet pushed, only while it stands where expected.
     *
     * @param repository the repository's name
     * @param expected where it must stand
     * @throws GitFailure when it stood elsewhere
     */
    void delete(String repository, String expected);

    /**
     * Fetches the remote's branch into a ref of its own, leaving the commit last fetched for the
     * board alone.
     *
     * @param repository the repository's name
     * @param url its clone URL
     * @param branch its branch, or {@code null} for the remote's default
     * @return the remote's commit
     */
    String fetchRemote(String repository, CloneUrl url, @Nullable String branch);

    /**
     * Answers the paths that differ between two commits.
     *
     * @param repository the repository's name
     * @param from the one commit
     * @param to the other
     * @return the paths, sorted
     */
    List<String> changed(String repository, String from, String to);

    /**
     * Answers the paths that one commit holds and another does not.
     *
     * @param repository the repository's name
     * @param from the commit without them
     * @param to the commit with them
     * @return the paths, sorted
     */
    List<String> added(String repository, String from, String to);

    /**
     * Answers the commits after a base up to a tip, the oldest first.
     *
     * @param repository the repository's name
     * @param base the base, not among them
     * @param tip the tip
     * @return the commits
     */
    List<String> commits(String repository, String base, String tip);

    /**
     * Makes a commit's own changes again on another commit: each path the commit changed takes the
     * commit's version, every other path the new parent's.
     *
     * @param repository the repository's name
     * @param commit the commit, whose parent is the one its changes are told against
     * @param onto the new parent
     * @return the new commit, with the commit's message
     */
    String replay(String repository, String commit, String onto);

    /**
     * Answers refs for a store that never saves: none holds a save.
     *
     * @return the refs
     */
    static LocalRefs none() {
        return new LocalRefs() {
            @Override
            public Optional<String> local(final String repository) {
                return Optional.empty();
            }

            @Override
            public void move(
                    final String repository, final String to, final @Nullable String expected) {
                throw new UnsupportedOperationException("This store saves nothing");
            }

            @Override
            public void delete(final String repository, final String expected) {
                throw new UnsupportedOperationException("This store saves nothing");
            }

            @Override
            public String fetchRemote(
                    final String repository, final CloneUrl url, final @Nullable String branch) {
                throw new UnsupportedOperationException("This store saves nothing");
            }

            @Override
            public List<String> changed(
                    final String repository, final String from, final String to) {
                throw new UnsupportedOperationException("This store saves nothing");
            }

            @Override
            public List<String> added(final String repository, final String from, final String to) {
                throw new UnsupportedOperationException("This store saves nothing");
            }

            @Override
            public List<String> commits(
                    final String repository, final String base, final String tip) {
                throw new UnsupportedOperationException("This store saves nothing");
            }

            @Override
            public String replay(final String repository, final String commit, final String onto) {
                throw new UnsupportedOperationException("This store saves nothing");
            }
        };
    }
}
