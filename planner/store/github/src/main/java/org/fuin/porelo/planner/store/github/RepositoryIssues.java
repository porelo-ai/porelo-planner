package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.forge.github.Conditional;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.Page;
import org.fuin.porelo.forge.github.model.AuthorAssociation;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssueType;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The open issues of the project's repositories, read page by page from each repository's issue
 * list, every page sent with the tag of its last answer so an unchanged page costs no rate limit,
 * and held per repository: a repository whose list cannot be read, or is cut short by GitHub
 * leaving a page without an answer for the silence limit, keeps the list last read of it.
 */
public final class RepositoryIssues {

    private static final Logger LOG = LoggerFactory.getLogger(RepositoryIssues.class);

    /** The most issues a page carries, as the list is asked for them. */
    static final int PAGE_SIZE = 100;

    /**
     * How long GitHub may leave a page of a repository's list without an answer, apart from the
     * project's read, before the factor {@code services}; each answer starts it again, so lists
     * that keep coming are read to their end.
     */
    static final Duration DEADLINE = Duration.ofSeconds(20);

    /** One page of a repository's open issues as GitHub answers it. */
    @FunctionalInterface
    public interface Pages {

        /**
         * Asks for a page of a repository's open issues, newest update first.
         *
         * @param token the person's token
         * @param owner the repository's owner
         * @param repository the repository's name
         * @param page the page, from 1
         * @param etag the tag of the last answer for this page, or null for a first ask
         * @return the answer, unchanged on a 304
         */
        Conditional<Page<Issue>> page(
                String token, String owner, String repository, int page, @Nullable String etag);

        /**
         * Asks through the forge client's REST calls.
         *
         * @param client the client
         * @return the pages
         */
        static Pages over(final GitHubClient client) {
            return (token, owner, repository, page, etag) ->
                    client.issuesIfChanged(
                            token, owner, repository, "open", null, null, page, PAGE_SIZE, etag);
        }
    }

    /** One page of one repository, asked of GitHub with the read's token and owner. */
    @FunctionalInterface
    interface Fetch {

        /**
         * Asks for a page.
         *
         * @param repository the project's repository
         * @param page the page, from 1
         * @param etag the tag of the last answer for this page, or null
         * @return the answer
         * @throws GitHubException when GitHub refuses the list of that repository
         */
        Conditional<Page<Issue>> page(String repository, int page, @Nullable String etag);
    }

    /**
     * What one read gave.
     *
     * @param issues every repository's open issues as last read, in the repositories' order
     * @param faults each repository whose list could not be read, and one for the budget spent
     * @param requests the pages asked for
     * @param unchanged those GitHub answered as unchanged
     */
    record Read(List<Snapshot.Unlisted> issues, List<String> faults, int requests, int unchanged) {

        /** Copies the lists. */
        Read {
            issues = List.copyOf(issues);
            faults = List.copyOf(faults);
        }
    }

    /**
     * A page as last answered: its tag, the node ids of the entries it carried, pull requests among
     * them, and its issues.
     */
    private record Kept(
            @Nullable String etag, List<String> entries, List<Snapshot.Unlisted> issues) {

        /** How many entries the page carried. */
        int count() {
            return entries.size();
        }
    }

    /** A repository's pages as last read, and its issues; no pages after a copy was loaded. */
    private record Held(List<Kept> pages, List<Snapshot.Unlisted> issues) {}

    private final Map<String, Held> held = new HashMap<>();

    /** How long one read of every repository's list may take. */
    private final Duration deadline;

    /**
     * The repository the last read did not reach, where the next starts; null after a whole one.
     */
    private @Nullable String nextFirst;

    /**
     * The first words of the fault of a read GitHub left without an answer for its silence limit,
     * before the limit and the repositories it names; the board's revision takes them alone, so a
     * slow GitHub or another factor changes no revision.
     */
    static final String CUT_SHORT = "The issues outside the project were not all read";

    /**
     * The first words of the fault of a list whose next page GitHub answered with none but entries
     * read before.
     */
    static final String SAME_PAGE = "GitHub answered the same page again for the issues of ";

    /** The silence limit met while a page was asked, after one failed on the way. */
    static final class OutOfTime extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Makes the failure.
         *
         * @param deadline the silence limit met
         */
        OutOfTime(final Duration deadline) {
            super(
                    "GitHub gave no answer to a page of the open issues' lists within "
                            + Seconds.of(deadline)
                            + " s");
        }
    }

    /** Makes the list with nothing held and the deadline before its factor. */
    RepositoryIssues() {
        this(DEADLINE);
    }

    /**
     * Makes the list with nothing held; each repository is held from its first read on.
     *
     * @param deadline how long one read of every repository's list may take
     */
    RepositoryIssues(final Duration deadline) {
        this.deadline = deadline;
    }

    /**
     * How long one read of every repository's list may take.
     *
     * @return the deadline
     */
    Duration deadline() {
        return deadline;
    }

    /**
     * Holds a copy's lists for the repositories that hold none yet, so a repository the next read
     * cannot list keeps them; their pages are read anew, since no tag was kept.
     *
     * @param issues the lists, every repository's in one
     */
    synchronized void keep(final List<Snapshot.Unlisted> issues) {
        final Map<String, List<Snapshot.Unlisted>> byRepository = new LinkedHashMap<>();
        for (final Snapshot.Unlisted issue : issues) {
            byRepository
                    .computeIfAbsent(issue.repository(), ignored -> new ArrayList<>())
                    .add(issue);
        }
        byRepository.forEach(
                (repository, list) -> held.putIfAbsent(repository, new Held(List.of(), list)));
    }

    /**
     * Reads every repository's list, page after page, until a page carries fewer entries than a
     * page asks for; no count of pages ends a list GitHub still continues. Once GitHub leaves a
     * page without an answer for the silence limit, which each answer starts again, the repository
     * being read and those after it keep what was held of them, named in one fault. A full page
     * that carries no entry this repository's read has not seen, by the entries' node ids, is
     * GitHub answering a page it gave already, which would never end: that repository keeps what
     * was held of it, named in a fault of its own.
     *
     * <p>A read starts at the repository the last one did not reach, so a large repository never
     * keeps those after it unread.
     *
     * @param repositories the project's repositories, in their order
     * @param fetch asks GitHub for a page
     * @param clock tells the time the deadline is measured by
     * @param token the token the pages are asked with, kept out of every word logged
     * @return the read
     */
    synchronized Read read(
            final List<String> repositories,
            final Fetch fetch,
            final InstantSource clock,
            final String token) {
        final Duration silence = deadline;
        Instant until = clock.instant().plus(silence);
        final List<String> faults = new ArrayList<>();
        final List<String> notRead = new ArrayList<>();
        int requests = 0;
        int unchanged = 0;
        final List<String> order = new ArrayList<>(repositories);
        final int from = order.indexOf(nextFirst);
        if (from > 0) {
            java.util.Collections.rotate(order, -from);
        }
        for (final String repository : order) {
            if (!notRead.isEmpty()) {
                notRead.add(repository);
                continue;
            }
            final Held before = held.get(repository);
            final List<Kept> pages = new ArrayList<>();
            final Set<String> seen = new HashSet<>();
            boolean whole = false;
            for (int page = 1; ; page++) {
                if (clock.instant().isAfter(until)) {
                    // A list read in part is never shown: one cut short in silence lacks issues.
                    notRead.add(repository);
                    break;
                }
                final Kept old =
                        before != null && page <= before.pages().size()
                                ? before.pages().get(page - 1)
                                : null;
                final Conditional<Page<Issue>> answer;
                try {
                    answer = fetch.page(repository, page, old == null ? null : old.etag());
                    if (clock.instant().isAfter(until)) {
                        // An answer after the silence limit is not taken, as for the project.
                        notRead.add(repository);
                        break;
                    }
                    until = clock.instant().plus(silence);
                } catch (final OutOfTime e) {
                    // A read sent once more after a failure on the way met the deadline.
                    notRead.add(repository);
                    break;
                } catch (final GitHubException e) {
                    if (!refusedFor(e)) {
                        throw e;
                    }
                    LOG.warn(
                            "GitHub refused the issue list of {} (HTTP {}): {}; the list last read"
                                    + " of it is kept",
                            repository,
                            e.status(),
                            Scrubbed.text(String.valueOf(e.getMessage()), List.of(token)));
                    faults.add(
                            refusal(
                                    repository,
                                    e.status(),
                                    ForgeAccessRefusals.right(e.acceptedPermissions())));
                    break;
                }
                requests++;
                final Kept now = kept(repository, answer, old);
                if (now == null) {
                    faults.add(
                            "GitHub answered a page of the issues of "
                                    + repository
                                    + " in a form that cannot be read; the list last read of it"
                                    + " is kept");
                    break;
                }
                if (!answer.changed()) {
                    unchanged++;
                }
                final boolean fresh = seen.addAll(now.entries());
                if (now.count() >= PAGE_SIZE && !fresh) {
                    faults.add(SAME_PAGE + repository + "; the list last read of it is kept");
                    break;
                }
                pages.add(now);
                // Full is told by the entries GitHub gave, before pull requests are left out.
                if (now.count() < PAGE_SIZE) {
                    whole = true;
                    break;
                }
            }
            if (whole) {
                final Map<String, Snapshot.Unlisted> issues = new LinkedHashMap<>();
                // An issue seen again on a later page, as the order shifts while it is read,
                // counts once.
                pages.forEach(
                        kept -> kept.issues().forEach(i -> issues.putIfAbsent(i.nodeId(), i)));
                held.put(repository, new Held(pages, List.copyOf(issues.values())));
            }
        }
        nextFirst = notRead.isEmpty() ? null : notRead.get(0);
        if (!notRead.isEmpty()) {
            faults.add(
                    CUT_SHORT
                            + ": GitHub gave no answer within "
                            + Seconds.of(silence)
                            + " s; the list last read is shown for "
                            + String.join(", ", notRead));
        }
        final List<Snapshot.Unlisted> issues = new ArrayList<>();
        for (final String repository : repositories) {
            final Held kept = held.get(repository);
            if (kept != null) {
                issues.addAll(kept.issues());
            }
        }
        return new Read(issues, faults, requests, unchanged);
    }

    /**
     * Answers a page as it now stands: the page held for a 304, the page given otherwise; null for
     * an answer that cannot be read.
     */
    private static @Nullable Kept kept(
            final String repository,
            final Conditional<Page<Issue>> answer,
            final @Nullable Kept old) {
        if (!answer.changed()) {
            // A 304 for a page never asked with a tag cannot stand for it.
            return old;
        }
        final Page<Issue> page = answer.value();
        if (page == null || page.items().size() > PAGE_SIZE) {
            return null;
        }
        final List<String> entries = new ArrayList<>();
        final List<Snapshot.Unlisted> issues = new ArrayList<>();
        for (final Issue issue : page.items()) {
            entries.add(issue.nodeId());
            // GitHub's issue list carries pull requests too.
            if (issue.pullRequest() != null) {
                continue;
            }
            // The forge client refuses an issue without its node id, number or title.
            final IssueType type = issue.type();
            final SimpleUser user = issue.user();
            final AuthorAssociation association = issue.authorAssociation();
            issues.add(
                    new Snapshot.Unlisted(
                            issue.nodeId(),
                            repository,
                            issue.number(),
                            issue.title(),
                            type == null ? null : type.name(),
                            author(user == null ? null : user.login()),
                            association(association == null ? null : association.getValue())));
        }
        return new Kept(answer.etag(), List.copyOf(entries), issues);
    }

    /**
     * The login of an issue's author, or null when none of a login's form was given: the window
     * shows it as the account that wrote the issue.
     *
     * @param login the login as given
     * @return the login, or null
     */
    static @Nullable String author(final @Nullable String login) {
        return login != null && UnlistedIssue.isLogin(login) ? login : null;
    }

    /**
     * How an issue's author stands to the repository, or null when none known was given.
     *
     * @param association the association's name as given
     * @return the name, or null
     */
    static @Nullable String association(final @Nullable String association) {
        return association != null && UnlistedIssue.ASSOCIATIONS.contains(association)
                ? association
                : null;
    }

    /**
     * Whether GitHub refused the list of one repository alone: it moved, it is private or gone to
     * the person's token, or it keeps no issues. A rate limit and a refused token hold every read
     * and are answered as for the project.
     */
    static boolean refusedFor(final GitHubException e) {
        return !e.rateLimited()
                && (e.status() == 301
                        || e.status() == 403
                        || e.status() == 404
                        || e.status() == 410);
    }

    private static String refusal(
            final String repository, final int status, final @Nullable String right) {
        return "GitHub refused the list of the issues of "
                + repository
                + " (HTTP "
                + status
                + (right == null ? "" : "; the token needs " + right)
                + "); the list last read of it is shown";
    }
}
