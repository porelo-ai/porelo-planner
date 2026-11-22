package org.fuin.porelo.planner.store.github;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.Page;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssueComment;
import org.fuin.porelo.forge.github.model.IssueLabelsInner;
import org.fuin.porelo.forge.github.model.IssuesCreateRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateCommentRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequest;
import org.fuin.porelo.forge.github.model.PrivateUser;
import org.fuin.porelo.forge.github.model.PublicUser;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.fuin.porelo.forge.github.model.UsersGetAuthenticated200Response;
import org.jspecify.annotations.Nullable;

/**
 * The calls a write to a GitHub project is made of. A save writes values only, never a field's
 * options.
 */
public interface ProjectWrites {

    /** The most comments one page of an issue's comments carries. */
    int COMMENTS_PER_PAGE = 100;

    /** The most issues one page of a repository's issues carries. */
    int ISSUES_PER_PAGE = 100;

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
     * An issue the person made, as a look for one GitHub may have made without answering finds it.
     *
     * @param issue the issue
     * @param title its title
     * @param body its body, empty when it has none
     * @param underParent whether GitHub names a parent issue it is a sub-issue of
     */
    record Found(Created issue, String title, String body, boolean underParent) {

        /**
         * An issue found under no parent.
         *
         * @param issue the issue
         * @param title its title
         * @param body its body, empty when it has none
         */
        public Found(final Created issue, final String title, final String body) {
            this(issue, title, body, false);
        }
    }

    /**
     * A page of the issues the person made in a repository.
     *
     * @param issues the issues on the page
     * @param more whether a later page follows
     */
    record FoundPage(List<Found> issues, boolean more) {

        /** Copies the issues. */
        public FoundPage {
            issues = List.copyOf(issues);
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
     * Makes an issue a sub-issue of another, after the sub-issues it has.
     *
     * @param token the person's token
     * @param owner the owner of both repositories
     * @param repository the parent's repository
     * @param number the parent's number
     * @param subIssueId the sub-issue's id in REST, {@link Created#databaseId}
     */
    void addSubIssue(String token, String owner, String repository, int number, long subIssueId);

    /**
     * Takes a sub-issue from its parent; it stays an issue of its own.
     *
     * @param token the person's token
     * @param owner the owner of both repositories
     * @param repository the parent's repository
     * @param number the parent's number
     * @param subIssueId the sub-issue's id in REST
     */
    void removeSubIssue(String token, String owner, String repository, int number, long subIssueId);

    /**
     * Moves a sub-issue among its parent's sub-issues, right after one sibling or right before
     * another.
     *
     * @param token the person's token
     * @param owner the owner of both repositories
     * @param repository the parent's repository
     * @param number the parent's number
     * @param subIssueId the sub-issue's id in REST
     * @param afterId the sibling it follows, in REST, or null
     * @param beforeId the sibling it precedes, in REST, or null when it follows one
     */
    void reprioritizeSubIssue(
            String token,
            String owner,
            String repository,
            int number,
            long subIssueId,
            @Nullable Long afterId,
            @Nullable Long beforeId);

    /**
     * Makes an issue wait on another.
     *
     * @param token the person's token
     * @param owner the owner of both repositories
     * @param repository the waiting issue's repository
     * @param number the waiting issue's number
     * @param blockerId the blocker's id in REST
     */
    void addBlockedBy(String token, String owner, String repository, int number, long blockerId);

    /**
     * Ends an issue's wait on another.
     *
     * @param token the person's token
     * @param owner the owner of both repositories
     * @param repository the waiting issue's repository
     * @param number the waiting issue's number
     * @param blockerId the blocker's id in REST
     */
    void removeBlockedBy(String token, String owner, String repository, int number, long blockerId);

    /**
     * Records the one existing issue selected to finish a cut-off creation, without writing it.
     *
     * @param nodeId the selected issue's node id
     */
    default void issueTaken(final String nodeId) {}

    /**
     * Reads a page of the open issues the token's owner made in a repository at or after a moment,
     * pull requests left out; an issue the person closed is never taken for one made.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param since the earliest moment an issue was made at
     * @param page the page, from 1
     * @return the page
     */
    FoundPage issuesCreatedSince(
            String token, String owner, String repository, Instant since, int page);

    /**
     * Writes an issue's title, body or open state in one update; what is null is not sent, so
     * GitHub leaves it, labels, assignees and milestone too.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param number the issue's number
     * @param title the new title, or null to leave it
     * @param body the new body, or null to leave it
     * @param opening the new open state, or null to leave it
     */
    void updateIssue(
            String token,
            String owner,
            String repository,
            int number,
            @Nullable String title,
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
            public void addSubIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long subIssueId) {
                rest.addSubIssue(token, owner, repository, number, subIssueId, false);
            }

            @Override
            public FoundPage issuesCreatedSince(
                    final String token,
                    final String owner,
                    final String repository,
                    final Instant since,
                    final int page) {
                final String login = login(rest.user(token));
                // GitHub lists by the last change, so an issue made since was changed since too.
                final Page<Issue> issues =
                        rest.issues(
                                token,
                                owner,
                                repository,
                                // A duplicate the person closed is no longer the one made.
                                "open",
                                null,
                                since,
                                page,
                                ISSUES_PER_PAGE);
                final List<Found> found = new ArrayList<>();
                for (final Issue issue : issues.items()) {
                    final SimpleUser author = issue.user();
                    final OffsetDateTime made = issue.createdAt();
                    if (issue.pullRequest() == null
                            && author != null
                            && login.equalsIgnoreCase(author.login())
                            && made != null
                            && !made.toInstant().isBefore(since)) {
                        found.add(
                                new Found(
                                        created(issue),
                                        Objects.requireNonNullElse(issue.title(), ""),
                                        Objects.requireNonNullElse(issue.body(), ""),
                                        issue.parentIssueUrl() != null));
                    }
                }
                return new FoundPage(found, issues.more());
            }

            @Override
            public void removeSubIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long subIssueId) {
                rest.removeSubIssue(token, owner, repository, number, subIssueId);
            }

            @Override
            public void reprioritizeSubIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long subIssueId,
                    final @Nullable Long afterId,
                    final @Nullable Long beforeId) {
                rest.reprioritizeSubIssue(
                        token, owner, repository, number, subIssueId, afterId, beforeId);
            }

            @Override
            public void addBlockedBy(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long blockerId) {
                rest.addBlockedBy(token, owner, repository, number, blockerId);
            }

            @Override
            public void removeBlockedBy(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long blockerId) {
                rest.removeBlockedBy(token, owner, repository, number, blockerId);
            }

            @Override
            public void updateIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final @Nullable String title,
                    final @Nullable String body,
                    final @Nullable Opening opening) {
                rest.updateIssue(
                        token,
                        owner,
                        repository,
                        number,
                        new IssuesUpdateRequest(
                                title,
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
     * The login of the account GitHub answered for the token.
     *
     * @param user GitHub's answer
     * @return the login
     * @throws IllegalStateException when the answer names none
     */
    private static String login(final UsersGetAuthenticated200Response user) {
        final String login =
                switch (user) {
                    case PrivateUser own -> own.login();
                    case PublicUser shown -> shown.login();
                    default -> null;
                };
        if (login == null || login.isEmpty()) {
            throw new IllegalStateException("GitHub named no account for the token");
        }
        return login;
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
                                .filter(Objects::nonNull)
                                .toList());
    }
}
