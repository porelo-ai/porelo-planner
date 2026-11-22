package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.fuin.porelo.forge.github.Conditional;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.Page;
import org.fuin.porelo.forge.github.model.AuthorAssociation;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssueLabelsInner;
import org.fuin.porelo.forge.github.model.IssuePullRequest;
import org.fuin.porelo.forge.github.model.IssueType;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.jspecify.annotations.Nullable;

/**
 * GitHub's REST lists of a repository's open issues as a fake answers them: each repository's
 * issues in pages of a hundred, a page answered 304 when it is asked with the tag of its present
 * content, and every ask recorded.
 */
final class OpenIssues implements RepositoryIssues.Pages {

    /** One ask: the repository, the page and the tag it was sent with. */
    record Ask(String repository, int page, @Nullable String etag) {}

    private static final OffsetDateTime UPDATED =
            OffsetDateTime.of(2026, 11, 21, 10, 0, 0, 0, ZoneOffset.UTC);

    private final Map<String, List<Issue>> issues = new LinkedHashMap<>();

    private final Map<String, Function<Integer, RuntimeException>> failures = new HashMap<>();

    final List<Ask> asks = new ArrayList<>();

    /** How far the clock moves at each ask; nothing unless a test says so. */
    Duration perAsk = Duration.ZERO;

    /** The clock the asks move. */
    Instant now = Instant.parse("2026-11-21T12:00:00Z");

    /**
     * Sets a repository's open issues, newest update first, pull requests among them.
     *
     * @param repository the repository
     * @param list its issues
     * @return this fake
     */
    OpenIssues holds(final String repository, final List<Issue> list) {
        issues.put(repository, List.copyOf(list));
        return this;
    }

    /**
     * Has every ask for a repository's list fail.
     *
     * @param repository the repository
     * @param failure the failure, given the page asked for
     * @return this fake
     */
    OpenIssues fails(final String repository, final Function<Integer, RuntimeException> failure) {
        failures.put(repository, failure);
        return this;
    }

    /** Lets a repository's list be read again. */
    void heals(final String repository) {
        failures.remove(repository);
    }

    /** The clock the asks move, for the reader. */
    InstantSource clock() {
        return () -> now;
    }

    /**
     * The asks of one repository.
     *
     * @param repository the repository
     * @return its asks, in order
     */
    List<Ask> asksOf(final String repository) {
        return asks.stream().filter(ask -> ask.repository().equals(repository)).toList();
    }

    /**
     * The pages of the project's own reader, over this fake.
     *
     * @return the fetch
     */
    RepositoryIssues.Fetch fetch() {
        return (repository, page, etag) -> page("a-token", "example-org", repository, page, etag);
    }

    @Override
    public Conditional<Page<Issue>> page(
            final String token,
            final String owner,
            final String repository,
            final int page,
            final @Nullable String etag) {
        asks.add(new Ask(repository, page, etag));
        now = now.plus(perAsk);
        final Function<Integer, RuntimeException> failure = failures.get(repository);
        if (failure != null) {
            throw failure.apply(page);
        }
        final List<Issue> all = issues.getOrDefault(repository, List.of());
        final int from = Math.min(all.size(), (page - 1) * RepositoryIssues.PAGE_SIZE);
        final List<Issue> items =
                all.subList(from, Math.min(all.size(), from + RepositoryIssues.PAGE_SIZE));
        final String tag =
                "W/\""
                        + repository
                        + "-"
                        + page
                        + "-"
                        + Integer.toHexString(
                                Objects.hash(
                                        items.stream().map(Issue::nodeId).toList(),
                                        items.stream().map(Issue::title).toList()))
                        + "\"";
        if (tag.equals(etag)) {
            return new Conditional<>(null, tag, false, null, now);
        }
        return new Conditional<>(
                new Page<>(items, from + items.size() < all.size()), tag, true, null, now);
    }

    /**
     * An open issue as GitHub's REST list gives it.
     *
     * @param repository its repository
     * @param number its number
     * @param title its title
     * @param type the name of its issue type, or null for none
     * @return the issue
     */
    static Issue issue(
            final String repository,
            final int number,
            final String title,
            final @Nullable String type) {
        return issue(repository, number, title, type, false, List.of());
    }

    /**
     * A pull request as GitHub's issue list gives it.
     *
     * @param repository its repository
     * @param number its number
     * @return the pull request
     */
    static Issue pullRequest(final String repository, final int number) {
        return issue(repository, number, "A change", null, true, List.of());
    }

    /**
     * An open issue or pull request as GitHub's REST list gives it.
     *
     * @param repository its repository
     * @param number its number
     * @param title its title
     * @param type the name of its issue type, or null for none
     * @param pull whether it is a pull request
     * @param labels its labels' names
     * @return the issue
     */
    static Issue issue(
            final String repository,
            final int number,
            final String title,
            final @Nullable String type,
            final boolean pull,
            final List<String> labels) {
        return issue(nodeOf(repository, number), repository, number, title, type, pull, labels);
    }

    /**
     * An open issue as GitHub's REST list gives it, with a node id of its own.
     *
     * @param nodeId its node id
     * @param repository its repository
     * @param number its number
     * @param title its title
     * @param type the name of its issue type, or null for none
     * @param pull whether it is a pull request
     * @param labels its labels' names
     * @return the issue
     */
    static Issue issue(
            final String nodeId,
            final String repository,
            final int number,
            final String title,
            final @Nullable String type,
            final boolean pull,
            final List<String> labels) {
        return issue(nodeId, repository, number, title, type, pull, labels, null, null);
    }

    /**
     * An open issue as GitHub's REST list gives it, with its author.
     *
     * @param repository its repository
     * @param number its number
     * @param login its author's login, or null when GitHub names no author
     * @param association how the author stands to the repository, or null
     * @return the issue
     */
    static Issue authored(
            final String repository,
            final int number,
            final @Nullable String login,
            final @Nullable AuthorAssociation association) {
        return issue(
                nodeOf(repository, number),
                repository,
                number,
                "Made by hand " + number,
                null,
                false,
                List.of(),
                login == null ? null : user(login),
                association);
    }

    private static SimpleUser user(final String login) {
        return new SimpleUser(
                null,
                null,
                login,
                1L,
                "U_1",
                URI.create("https://avatars.example/1"),
                "",
                URI.create("https://api.github.com/users/1"),
                URI.create("https://github.com/1"),
                URI.create("https://api.github.com/users/1/followers"),
                "",
                "",
                "",
                URI.create("https://api.github.com/users/1/subscriptions"),
                URI.create("https://api.github.com/users/1/orgs"),
                URI.create("https://api.github.com/users/1/repos"),
                "",
                URI.create("https://api.github.com/users/1/received_events"),
                "User",
                false,
                null,
                null);
    }

    private static Issue issue(
            final String nodeId,
            final String repository,
            final int number,
            final String title,
            final @Nullable String type,
            final boolean pull,
            final List<String> labels,
            final @Nullable SimpleUser user,
            final @Nullable AuthorAssociation association) {
        final URI api = URI.create("https://api.github.com/repos/example-org/" + repository);
        return new Issue(
                (long) number,
                nodeId,
                URI.create(api + "/issues/" + number),
                api,
                api + "/labels{/name}",
                URI.create(api + "/issues/" + number + "/comments"),
                URI.create(api + "/issues/" + number + "/events"),
                // GitHub's own address is never what the board links to.
                URI.create("https://elsewhere.example/" + number),
                number,
                "open",
                null,
                title,
                null,
                user,
                labels.stream()
                        .map(
                                name ->
                                        new IssueLabelsInner(
                                                null, null, null, name, null, null, null, null,
                                                null))
                        .toList(),
                List.of(),
                null,
                false,
                null,
                0,
                pull
                        ? new IssuePullRequest(
                                null,
                                URI.create("https://github.com/pull"),
                                URI.create("https://github.com/pull.diff"),
                                URI.create("https://github.com/pull.patch"),
                                URI.create("https://api.github.com/pull"))
                        : null,
                null,
                UPDATED,
                UPDATED,
                null,
                null,
                null,
                null,
                null,
                type == null
                        ? null
                        : new IssueType(7L, "IT_" + type, type, null, null, null, null, true),
                null,
                null,
                association,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null);
    }

    /**
     * The node id the fake gives an issue.
     *
     * @param repository its repository
     * @param number its number
     * @return the node id
     */
    static String nodeOf(final String repository, final int number) {
        return "I_" + repository + "_" + number;
    }

    /**
     * A repository's open issues, numbered from the first given, none of them a story.
     *
     * @param repository the repository
     * @param first the first number
     * @param count how many
     * @return the issues
     */
    static List<Issue> numbered(final String repository, final int first, final int count) {
        final List<Issue> list = new ArrayList<>();
        for (int n = first; n < first + count; n++) {
            list.add(issue(repository, n, "Issue " + n, null));
        }
        return list;
    }

    /**
     * A refusal of GitHub's with its status.
     *
     * @param status the status
     * @return the failure
     */
    static GitHubException refused(final int status) {
        return org.fuin.porelo.forge.github.GitHubExceptions.refused(status, "Refused");
    }
}
