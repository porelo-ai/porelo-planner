package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.IssueComment;
import org.fuin.porelo.forge.github.model.IssuesUpdateCommentRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequest;
import org.jspecify.annotations.Nullable;

/**
 * The calls a write to a GitHub project is made of, and nothing that changes the project's fields
 * or their options: a save can write values, never replace a field's options and so clear them.
 */
public interface ProjectWrites {

    /** The most comments one page of an issue's comments carries. */
    int COMMENTS_PER_PAGE = 100;

    /**
     * What an issue's state is set to.
     *
     * @param open whether it is opened or closed
     * @param notPlanned whether a closed issue is closed as not planned rather than completed
     */
    record Opening(boolean open, boolean notPlanned) {}

    /**
     * A page of an issue's comments.
     *
     * @param bodies each comment's text, oldest first
     * @param more whether a later page follows
     */
    record CommentsPage(List<String> bodies, boolean more) {

        /** Copies the texts. */
        public CommentsPage {
            bodies = List.copyOf(bodies);
        }
    }

    /**
     * Reads issues by their node ids, each with its item and field values in the project.
     *
     * @param token the person's token
     * @param issueIds the issues' node ids, at most {@link GitHubGraphQl#MAX_ISSUES}
     * @param projectId the project's node id
     * @return the issues, null where GitHub knows none
     */
    GitHubGraphQl.IssueBoard issueBoard(String token, List<String> issueIds, String projectId);

    /**
     * Sets or clears field values in one mutation.
     *
     * @param token the person's token
     * @param projectId the project's node id
     * @param values at most {@link GitHubGraphQl#MAX_BATCH} values
     * @return each value's outcome, in their order
     */
    Batch<String> setFieldValues(
            String token, String projectId, List<GitHubGraphQl.FieldValue> values);

    /**
     * Writes an issue's body or its open state.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param number the issue's number
     * @param body the new body, or null to leave it
     * @param opening the new open state, or null to leave it
     */
    void updateIssue(
            String token,
            String owner,
            String repository,
            int number,
            @Nullable String body,
            @Nullable Opening opening);

    /**
     * Reads a page of an issue's comments.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param number the issue's number
     * @param page the page, from 1
     * @return the page
     */
    CommentsPage issueComments(String token, String owner, String repository, int number, int page);

    /**
     * Writes a comment under the token's owner.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param number the issue's number
     * @param body the comment's text
     */
    void createIssueComment(String token, String owner, String repository, int number, String body);

    /**
     * Writes through the forge client's GraphQL and REST calls.
     *
     * @param graphQl the GraphQL client
     * @param rest the REST client
     * @return the writes
     */
    static ProjectWrites over(final GitHubGraphQl graphQl, final GitHubClient rest) {
        return new ProjectWrites() {
            @Override
            public GitHubGraphQl.IssueBoard issueBoard(
                    final String token, final List<String> issueIds, final String projectId) {
                return graphQl.issueBoard(token, issueIds, projectId);
            }

            @Override
            public Batch<String> setFieldValues(
                    final String token,
                    final String projectId,
                    final List<GitHubGraphQl.FieldValue> values) {
                return graphQl.setFieldValues(token, projectId, values);
            }

            @Override
            public void updateIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final @Nullable String body,
                    final @Nullable Opening opening) {
                rest.updateIssue(
                        token,
                        owner,
                        repository,
                        number,
                        new IssuesUpdateRequest(
                                null,
                                body,
                                opening == null
                                        ? null
                                        : opening.open()
                                                ? IssuesUpdateRequest.StateEnum.OPEN
                                                : IssuesUpdateRequest.StateEnum.CLOSED,
                                opening == null || opening.open()
                                        ? null
                                        : opening.notPlanned()
                                                ? IssuesUpdateRequest.StateReasonEnum.NOT_PLANNED
                                                : IssuesUpdateRequest.StateReasonEnum.COMPLETED,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
            }

            @Override
            public CommentsPage issueComments(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final int page) {
                final var comments =
                        rest.issueComments(
                                token, owner, repository, number, null, page, COMMENTS_PER_PAGE);
                return new CommentsPage(
                        comments.items().stream()
                                .map(IssueComment::body)
                                .map(body -> body == null ? "" : body)
                                .toList(),
                        comments.more());
            }

            @Override
            public void createIssueComment(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final String body) {
                rest.createIssueComment(
                        token, owner, repository, number, new IssuesUpdateCommentRequest(body));
            }
        };
    }
}
