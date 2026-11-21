package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.jspecify.annotations.Nullable;

/**
 * The reads of a GitHub project the board is made from, and nothing that writes: a board read holds
 * no way to change the person's project.
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
     * Reads one page of the project's items of the size given, for a page GitHub refused as too
     * costly at the full size.
     *
     * @param token the person's token
     * @param owner the organization that owns the project
     * @param number the project's number
     * @param after the cursor of the page before, or null for the first
     * @param first the items asked for, 1 to 100
     * @return the page
     */
    default GitHubGraphQl.ProjectItems projectItems(
            final String token,
            final String owner,
            final int number,
            final @Nullable String after,
            final int first) {
        if (first != 100) {
            throw new UnsupportedOperationException("This source reads only pages of 100 items");
        }
        return projectItems(token, owner, number, after);
    }

    /**
     * Reads issues by their node ids, each with its item and field values in the project.
     *
     * @param token the person's token
     * @param issueIds the issues' node ids, distinct, at most {@link GitHubGraphQl#MAX_ISSUES}
     * @param projectId the project's node id
     * @return the issues, null where GitHub knows none
     */
    GitHubGraphQl.IssueBoard issueBoard(String token, List<String> issueIds, String projectId);

    /**
     * Reads through the forge client's GraphQL calls.
     *
     * @param client the client
     * @return its reads
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

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after,
                    final int first) {
                return client.projectItems(token, owner, number, after, first);
            }

            @Override
            public GitHubGraphQl.IssueBoard issueBoard(
                    final String token, final List<String> issueIds, final String projectId) {
                return client.issueBoard(token, issueIds, projectId);
            }
        };
    }
}
