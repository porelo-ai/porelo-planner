package org.fuin.porelo.planner.store.git;

import org.fuin.porelo.planner.store.git.stories.Workflow;
import org.jspecify.annotations.Nullable;

/** Reads what one repository adds to a board, from a commit already fetched. */
@FunctionalInterface
public interface PartReader {

    /**
     * Reads a repository at a commit.
     *
     * @param repository the repository
     * @param commit the fetched commit
     * @param project the project's workflow, read from its own repository, which every other
     *     repository's issues are checked against; {@code null} for the project's own repository,
     *     which holds the workflow and the stories and checks its issues against that workflow
     * @return its part
     * @throws GitFailure when Git fails
     * @throws IllegalArgumentException when what it holds is no store
     */
    Part read(Repository repository, String commit, @Nullable Workflow project);
}
