package org.fuin.porelo.planner.store.github;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssueFieldValue;
import org.fuin.porelo.forge.github.model.IssuesCreateRequestIssueFieldValuesInner;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequestIssueFieldValuesInner;
import org.jspecify.annotations.Nullable;

/**
 * What the writes of each save touched on GitHub, so the store reads again only those issues and
 * not the whole project. Each save records in its own scope on its own thread, so saves made at
 * once never take or miss each other's items.
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

        /**
         * Answers whether nothing was touched.
         *
         * @return whether every set is empty
         */
        public boolean none() {
            return issues.isEmpty()
                    && items.isEmpty()
                    && numbered.isEmpty()
                    && databaseIds.isEmpty();
        }
    }

    /** What one save touched; written by its own thread, read by any. */
    static final class Scope implements AutoCloseable {

        private final Touched owner;

        private final Set<String> issues = new LinkedHashSet<>();

        private final Set<String> items = new LinkedHashSet<>();

        private final Set<String> numbered = new LinkedHashSet<>();

        private final Set<Long> databaseIds = new LinkedHashSet<>();

        /** Whether this handle opened the scope, so only it ends the scope. */
        private final boolean opener;

        private Scope(final Touched owner) {
            this.owner = owner;
            this.opener = true;
        }

        private Scope(final Scope outer) {
            this.owner = outer.owner;
            this.opener = false;
        }

        private synchronized void clear() {
            issues.clear();
            items.clear();
            numbered.clear();
            databaseIds.clear();
        }

        private synchronized Ids ids() {
            return new Ids(issues, items, numbered, databaseIds);
        }

        private synchronized void add(final Ids more) {
            issues.addAll(more.issues());
            items.addAll(more.items());
            numbered.addAll(more.numbered());
            databaseIds.addAll(more.databaseIds());
        }

        /** Ends the save's scope: what its thread writes later is kept by no save. */
        @Override
        public void close() {
            if (opener) {
                owner.current.remove();
                owner.open.remove(this);
            }
        }
    }

    /** The scope of the save the calling thread runs, if any. */
    private final ThreadLocal<@Nullable Scope> current = new ThreadLocal<>();

    /** Every save's scope still open, so a read can tell what saves in flight have sent. */
    private final Set<Scope> open = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** What a write outside any save touched, such as a writer used on its own. */
    private final Scope alone = new Scope(this);

    /**
     * Opens a save's own scope on the calling thread, so saves at once never mix what they touched;
     * a save run inside another shares the outer one.
     *
     * @return the scope, to close when the save ends
     */
    Scope open() {
        final Scope outer = current.get();
        if (outer != null) {
            return new Scope(outer);
        }
        final Scope made = new Scope(this);
        current.set(made);
        open.add(made);
        return made;
    }

    /** The scope of the calling thread's save, or the one of writes outside any save. */
    private Scope here() {
        final Scope scope = current.get();
        return scope != null ? scope : alone;
    }

    /** Forgets what the calling thread's save touched so far. */
    void clear() {
        here().clear();
    }

    /**
     * Answers what the calling thread's save touched since it began.
     *
     * @return the ids
     */
    Ids ids() {
        return here().ids();
    }

    /**
     * Answers what every save still in flight has touched so far: a write is named before it is
     * sent, so an item GitHub may already show a save's change of is among them.
     *
     * @return the ids, joined
     */
    Ids inFlight() {
        final Set<String> issues = new LinkedHashSet<>();
        final Set<String> items = new LinkedHashSet<>();
        final Set<String> numbered = new LinkedHashSet<>();
        final Set<Long> databaseIds = new LinkedHashSet<>();
        for (final Scope scope : open) {
            final Ids one = scope.ids();
            issues.addAll(one.issues());
            items.addAll(one.items());
            numbered.addAll(one.numbered());
            databaseIds.addAll(one.databaseIds());
        }
        return new Ids(issues, items, numbered, databaseIds);
    }

    /**
     * Adds ids touched elsewhere to the calling thread's save, such as by the earlier writes of a
     * group read after once.
     *
     * @param more the ids
     */
    void add(final Ids more) {
        here().add(more);
    }

    private void issue(final String nodeId) {
        add(new Ids(Set.of(nodeId), Set.of(), Set.of(), Set.of()));
    }

    private void item(final String itemId) {
        add(new Ids(Set.of(), Set.of(itemId), Set.of(), Set.of()));
    }

    private void numbered(final String repository, final int number) {
        add(
                new Ids(
                        Set.of(),
                        Set.of(),
                        Set.of(repository.toLowerCase(Locale.ROOT) + "#" + number),
                        Set.of()));
    }

    private void databaseId(final long id) {
        add(new Ids(Set.of(), Set.of(), Set.of(), Set.of(id)));
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
            public Created createIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final String title,
                    final String body,
                    final @Nullable String type,
                    final List<IssuesCreateRequestIssueFieldValuesInner> issueFieldValues,
                    final List<String> labels) {
                final Created created =
                        writes.createIssue(
                                token,
                                owner,
                                repository,
                                title,
                                body,
                                type,
                                issueFieldValues,
                                labels);
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
            public void issueTaken(final String nodeId) {
                issue(nodeId);
                writes.issueTaken(nodeId);
            }

            @Override
            public FoundPage issuesCreatedSince(
                    final String token,
                    final String owner,
                    final String repository,
                    final Instant since,
                    final int page) {
                return writes.issuesCreatedSince(token, owner, repository, since, page);
            }

            @Override
            public List<String> updateIssue(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number,
                    final @Nullable String title,
                    final @Nullable String body,
                    final @Nullable Opening opening,
                    final @Nullable List<IssuesUpdateRequestIssueFieldValuesInner> issueFieldValues,
                    final @Nullable List<String> labels) {
                numbered(repository, number);
                return writes.updateIssue(
                        token,
                        owner,
                        repository,
                        number,
                        title,
                        body,
                        opening,
                        issueFieldValues,
                        labels);
            }

            @Override
            public List<IssueField> issueFields(final String token, final String owner) {
                return writes.issueFields(token, owner);
            }

            @Override
            public List<IssueFieldValue> issueFieldValues(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number) {
                return writes.issueFieldValues(token, owner, repository, number);
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
