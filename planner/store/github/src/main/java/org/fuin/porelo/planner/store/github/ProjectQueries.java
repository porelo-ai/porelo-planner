package org.fuin.porelo.planner.store.github;

import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.jspecify.annotations.Nullable;

/**
 * The two reads of a GitHub project the board is made from, and nothing that writes: a board read
 * holds no way to change the person's project.
 */
public interface ProjectQueries {

    /**
     * Reads one page of the project's fields.
     *
     * @param token the person's token
     * @param owner the organization that owns the project
     * @param number the project's number
     * @param after the cursor of the page before, or null for the first
     * @return the page
     */
    GitHubGraphQl.ProjectFields projectFields(
            String token, String owner, int number, @Nullable String after);

    /**
     * Reads one page of the project's items, each with its issue and field values.
     *
     * @param token the person's token
     * @param owner the organization that owns the project
     * @param number the project's number
     * @param after the cursor of the page before, or null for the first
     * @return the page
     */
    GitHubGraphQl.ProjectItems projectItems(
            String token, String owner, int number, @Nullable String after);

    /**
     * Reads through the forge client's GraphQL calls.
     *
     * @param client the client
     * @return its two reads
     */
    static ProjectQueries over(final GitHubGraphQl client) {
        return new ProjectQueries() {
            @Override
            public GitHubGraphQl.ProjectFields projectFields(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                return client.projectFields(token, owner, number, after);
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                return client.projectItems(token, owner, number, after);
            }
        };
    }
}
