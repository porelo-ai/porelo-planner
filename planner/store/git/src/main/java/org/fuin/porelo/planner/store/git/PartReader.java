package org.fuin.porelo.planner.store.git;

/** Reads what one repository adds to a board, from a commit already fetched. */
@FunctionalInterface
public interface PartReader {

    /**
     * Reads a repository at a commit.
     *
     * @param repository the repository
     * @param commit the fetched commit
     * @param own whether it is the project's own repository, which holds the workflow and the
     *     stories
     * @return its part
     * @throws GitFailure when Git fails
     * @throws IllegalArgumentException when what it holds is no store
     */
    Part read(Repository repository, String commit, boolean own);
}
