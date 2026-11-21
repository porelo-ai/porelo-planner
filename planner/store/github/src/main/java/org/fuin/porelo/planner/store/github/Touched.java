package org.fuin.porelo.planner.store.github;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.jspecify.annotations.Nullable;

/**
 * What the writes of one save touched on GitHub, so the store reads again only those issues and not
 * the whole project. The store's lock covers every write, so one save records at a time.
 */
public final class Touched {

    /**
     * The issues and items one save touched, each as GitHub's calls named it.
     *
     * @param issues the issues' node ids
     * @param items the project items' node ids
     * @param numbered the issues by repository and number, {@code <repository>#<number>}, the
     *     repository lowercased
     * @param databaseIds the issues' database ids
     */
    public record Ids(
            Set<String> issues, Set<String> items, Set<String> numbered, Set<Long> databaseIds) {

        /** Copies the sets. */
        public Ids {
            issues = Set.copyOf(issues);
            items = Set.copyOf(items);
            numbered = Set.copyOf(numbered);
            databaseIds = Set.copyOf(databaseIds);
        }
    }

    private final Set<String> issues = new LinkedHashSet<>();

    private final Set<String> items = new LinkedHashSet<>();

    private final Set<String> numbered = new LinkedHashSet<>();

    private final Set<Long> databaseIds = new LinkedHashSet<>();

    /** Forgets what an earlier save touched. */
    synchronized void clear() {
        issues.clear();
        items.clear();
        numbered.clear();
        databaseIds.clear();
    }

    /**
     * Answers what was touched since the last clear.
     *
     * @return the ids
     */
    synchronized Ids ids() {
        return new Ids(issues, items, numbered, databaseIds);
    }

    private synchronized void issue(final String nodeId) {
        issues.add(nodeId);
    }

    private synchronized void item(final String itemId) {
        items.add(itemId);
    }

    private synchronized void numbered(final String repository, final int number) {
        numbered.add(repository.toLowerCase(Locale.ROOT) + "#" + number);
    }

    private synchronized void databaseId(final long id) {
        databaseIds.add(id);
    }

    /**
     * Wraps GitHub's calls so every write names what it touches here.
     *
     * @param writes the calls
     * @return the calls, recording
     */
    public ProjectWrites recording(final ProjectWrites writes) {
        return new ProjectWrites() {
            @Override
            public GitHubGraphQl.IssueBoard issueBoard(
                    final String token, final List<String> issueIds, final String projectId) {
                return writes.issueBoard(token, issueIds, projectId);
            }

            @Override
            public Batch<String> addProjectItems(
                    final String token, final String projectId, final List<String> issueIds) {
                issueIds.forEach(Touched.this::issue);
                return writes.addProjectItems(token, projectId, issueIds);
            }

            @Override
            public Batch<String> setFieldValues(
                    final String token,
                    final String projectId,
                    final List<GitHubGraphQl.FieldValue> values) {
                values.forEach(value -> item(value.itemId()));
                return writes.setFieldValues(token, projectId, values);
            }

            @Override
            public GitHubGraphQl.FieldOptions setFieldOptions(
                    final String token,
                    final String fieldId,
                    final List<GitHubGraphQl.NewOption> options) {
                return writes.setFieldOptions(token, fieldId, options);
            }

            @Override
            public Created createIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final String title,
                    final String body,
                    final List<String> labels) {
                final Created created =
                        writes.createIssue(token, owner, repository, title, body, labels);
                issue(created.nodeId());
                return created;
            }

            @Override
            public void addSubIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long subIssueId) {
                numbered(repository, number);
                databaseId(subIssueId);
                writes.addSubIssue(token, owner, repository, number, subIssueId);
            }

            @Override
            public void removeSubIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long subIssueId) {
                numbered(repository, number);
                databaseId(subIssueId);
                writes.removeSubIssue(token, owner, repository, number, subIssueId);
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
                numbered(repository, number);
                writes.reprioritizeSubIssue(
                        token, owner, repository, number, subIssueId, afterId, beforeId);
            }

            @Override
            public void addBlockedBy(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long blockerId) {
                numbered(repository, number);
                writes.addBlockedBy(token, owner, repository, number, blockerId);
            }

            @Override
            public void removeBlockedBy(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final long blockerId) {
                numbered(repository, number);
                writes.removeBlockedBy(token, owner, repository, number, blockerId);
            }

            @Override
            public FoundPage issuesCreatedSince(
                    final String token,
                    final String owner,
                    final String repository,
                    final Instant since,
                    final int page) {
                final FoundPage found =
                        writes.issuesCreatedSince(token, owner, repository, since, page);
                // An issue a save made before it failed is taken up as this save's.
                found.issues().forEach(f -> issue(f.issue().nodeId()));
                return found;
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
                numbered(repository, number);
                writes.updateIssue(token, owner, repository, number, title, body, opening);
            }

            @Override
            public CommentsPage issueComments(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final int page) {
                return writes.issueComments(token, owner, repository, number, page);
            }

            @Override
            public void createIssueComment(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final String body) {
                numbered(repository, number);
                writes.createIssueComment(token, owner, repository, number, body);
            }
        };
    }
}
