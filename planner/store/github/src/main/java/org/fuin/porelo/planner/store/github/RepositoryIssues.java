package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.Conditional;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.Page;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssueType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The open issues of the project's repositories, read page by page from each repository's issue
 * list, every page sent with the tag of its last answer so an unchanged page costs no rate limit,
 * and held per repository: a repository whose list cannot be read, or is not reached within the
 * read's budget, keeps the list last read of it.
 */
public final class RepositoryIssues {

    private static final Logger LOG = LoggerFactory.getLogger(RepositoryIssues.class);

    /** The most issues a page carries, as the list is asked for them. */
    static final int PAGE_SIZE = 100;

    /** The most pages one read of every repository's list takes, unchanged pages included. */
    static final int MOST_PAGES = ItemsBoardReader.MOST_PAGES;

    /** How long one read of every repository's list may take, apart from the project's read. */
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

    /** A page as last answered: its tag, the entries it carried, and its issues. */
    private record Kept(@Nullable String etag, int count, List<Snapshot.Unlisted> issues) {}

    /** A repository's pages as last read, and its issues; no pages after a copy was loaded. */
    private record Held(List<Kept> pages, List<Snapshot.Unlisted> issues) {}

    private final Map<String, Held> held = new HashMap<>();

    /** Makes the list with nothing held. */
    RepositoryIssues() {
        // Each repository is held from its first read on.
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
     * page asks for; once the pages or the time are spent, the repository being read and those
     * after it keep what was held of them, named in one fault.
     *
     * @param repositories the project's repositories, in their order
     * @param fetch asks GitHub for a page
     * @param clock tells the time the deadline is measured by
     * @return the read
     */
    synchronized Read read(
            final List<String> repositories, final Fetch fetch, final InstantSource clock) {
        final Instant until = clock.instant().plus(DEADLINE);
        final List<String> faults = new ArrayList<>();
        final List<String> notRead = new ArrayList<>();
        int requests = 0;
        int unchanged = 0;
        for (final String repository : repositories) {
            if (!notRead.isEmpty()) {
                notRead.add(repository);
                continue;
            }
            final Held before = held.get(repository);
            final List<Kept> pages = new ArrayList<>();
            boolean whole = false;
            for (int page = 1; ; page++) {
                if (requests >= MOST_PAGES || clock.instant().isAfter(until)) {
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
                } catch (final GitHubException e) {
                    if (!refusedFor(e)) {
                        throw e;
                    }
                    LOG.warn(
                            "GitHub refused the issue list of {} (HTTP {}); the list last read of"
                                    + " it is kept",
                            repository,
                            e.status());
                    faults.add(refusal(repository, e.status()));
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
        if (!notRead.isEmpty()) {
            faults.add(
                    "The issues outside the project were not all read within "
                            + DEADLINE.toSeconds()
                            + " s and "
                            + MOST_PAGES
                            + " pages; the list last read is shown for "
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
        final List<Snapshot.Unlisted> issues = new ArrayList<>();
        for (final Issue issue : page.items()) {
            // GitHub's issue list carries pull requests too.
            if (issue.pullRequest() != null) {
                continue;
            }
            // The forge client refuses an issue without its node id, number or title.
            final IssueType type = issue.type();
            issues.add(
                    new Snapshot.Unlisted(
                            issue.nodeId(),
                            repository,
                            issue.number(),
                            issue.title(),
                            type == null ? null : type.name()));
        }
        return new Kept(answer.etag(), page.items().size(), issues);
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

    private static String refusal(final String repository, final int status) {
        return "GitHub refused the list of the issues of "
                + repository
                + " (HTTP "
                + status
                + "); the list last read of it is shown";
    }
}
