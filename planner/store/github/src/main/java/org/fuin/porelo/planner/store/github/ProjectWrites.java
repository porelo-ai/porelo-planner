package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssueComment;
import org.fuin.porelo.forge.github.model.IssueLabelsInner;
import org.fuin.porelo.forge.github.model.IssuesCreateRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateCommentRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequest;
import org.jspecify.annotations.Nullable;

/**
 * The calls a write to a GitHub project is made of. A save writes values only; replacing a field's
 * options is a call of its own, which only the step that writes every value back makes.
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
     * An issue GitHub made, as it answered it.
     *
     * @param nodeId its node id, which the project adds it by
     * @param databaseId its id in REST, which a parent takes it as a sub-issue by
     * @param number its number in its repository
     * @param labels the names of the labels GitHub gave it
     */
    record Created(String nodeId, long databaseId, int number, List<String> labels) {

        /** Copies the labels. */
        public Created {
            labels = List.copyOf(labels);
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
     * Adds issues to the project in one mutation.
     *
     * @param token the person's token
     * @param projectId the project's node id
     * @param issueIds the issues' node ids, at most {@link GitHubGraphQl#MAX_BATCH}
     * @return each issue's outcome, in their order, a saved one holding its new item's node id
     */
    Batch<String> addProjectItems(String token, String projectId, List<String> issueIds);

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
     * Replaces a single-select field's options. GitHub gives every option a new id, a kept name
     * too, and clears every item's value of the field, so only {@link StatusOptionsWriter} calls
     * it, and writes every value back right after.
     *
     * @param token the person's token
     * @param fieldId the field's node id
     * @param options the options, in their order
     * @return the options as GitHub holds them now, with their new ids
     */
    GitHubGraphQl.FieldOptions setFieldOptions(
            String token, String fieldId, List<GitHubGraphQl.NewOption> options);

    /**
     * Makes an issue. GitHub drops the labels in silence for an account without push access, so the
     * caller reads them in the answer.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param title the issue's title
     * @param body the issue's body
     * @param labels the names of the labels it is to carry
     * @return the issue as GitHub made it
     */
    Created createIssue(
            String token,
            String owner,
            String repository,
            String title,
            String body,
            List<String> labels);

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
            public Batch<String> addProjectItems(
                    final String token, final String projectId, final List<String> issueIds) {
                return graphQl.addProjectItems(token, projectId, issueIds);
            }

            @Override
            public Batch<String> setFieldValues(
                    final String token,
                    final String projectId,
                    final List<GitHubGraphQl.FieldValue> values) {
                return graphQl.setFieldValues(token, projectId, values);
            }

            @Override
            public GitHubGraphQl.FieldOptions setFieldOptions(
                    final String token,
                    final String fieldId,
                    final List<GitHubGraphQl.NewOption> options) {
                return graphQl.setFieldOptions(token, fieldId, options);
            }

            @Override
            public Created createIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final String title,
                    final String body,
                    final List<String> labels) {
                return created(
                        rest.createIssue(
                                token,
                                owner,
                                repository,
                                new IssuesCreateRequest(
                                        title,
                                        body,
                                        null,
                                        labels.isEmpty() ? null : labels,
                                        null,
                                        null,
                                        null,
                                        null)));
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

    /**
     * An issue as GitHub answered it, refused when the answer lacks what a write goes on with.
     *
     * @param issue GitHub's answer
     * @return the issue
     * @throws IllegalStateException when the answer names no node id, id or number
     */
    private static Created created(final Issue issue) {
        final String nodeId = issue.nodeId();
        final Long id = issue.id();
        final Integer number = issue.number();
        if (nodeId == null || id == null || number == null) {
            throw new IllegalStateException("GitHub answered an issue without its ids");
        }
        final List<IssueLabelsInner> labels = issue.labels();
        return new Created(
                nodeId,
                id,
                number,
                labels == null
                        ? List.of()
                        : labels.stream()
                                .map(IssueLabelsInner::name)
                                .filter(java.util.Objects::nonNull)
                                .toList());
    }
}
