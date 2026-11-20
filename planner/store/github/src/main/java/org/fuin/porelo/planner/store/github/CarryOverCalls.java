package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.Page;
import org.fuin.porelo.forge.github.model.Issue;

/**
 * The calls a carry-over makes that no other write does: reading an issue's body, its sub-issues
 * and its blockers, and adding a dependency. Making an issue, finding the ones made, adding them to
 * the project and adding a sub-issue are {@link ProjectWrites}' own.
 */
public interface CarryOverCalls {

    /** The most items one page of a listing asks for. */
    int PER_PAGE = 100;

    /** The most pages one listing reads; a longer one is refused rather than read in part. */
    int MOST_PAGES = 50;

    /**
     * Reads an issue's body.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param number its number
     * @return its body, empty when it has none
     */
    String body(String token, String owner, String repository, int number);

    /**
     * Reads the database ids of an issue's sub-issues.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param number the issue's number
     * @return the ids, in their order
     */
    List<Long> subIssues(String token, String owner, String repository, int number);

    /**
     * Reads the database ids of the issues an issue is blocked by.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param number the issue's number
     * @return the ids
     */
    List<Long> blockedBy(String token, String owner, String repository, int number);

    /**
     * Calls through the forge client's REST calls.
     *
     * @param rest the REST client
     * @return the calls
     */
    static CarryOverCalls over(final GitHubClient rest) {
        return new CarryOverCalls() {
            @Override
            public String body(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number) {
                final String body = rest.issue(token, owner, repository, number).body();
                return body == null ? "" : body;
            }

            @Override
            public List<Long> subIssues(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number) {
                final List<Long> ids = new ArrayList<>();
                for (int page = 1; page <= MOST_PAGES; page++) {
                    final Page<Issue> subs =
                            rest.subIssues(token, owner, repository, number, page, PER_PAGE);
                    subs.items().forEach(issue -> ids.add(databaseId(issue)));
                    if (!subs.more()) {
                        return ids;
                    }
                }
                throw new IllegalStateException(
                        repository + "#" + number + " has more sub-issues than are read");
            }

            @Override
            public List<Long> blockedBy(
                    final String token,
                    final String owner,
                    final String repository,
                    final int number) {
                final List<Long> ids = new ArrayList<>();
                for (int page = 1; page <= MOST_PAGES; page++) {
                    final Page<Issue> blockers =
                            rest.blockedBy(token, owner, repository, number, page, PER_PAGE);
                    blockers.items().forEach(issue -> ids.add(databaseId(issue)));
                    if (!blockers.more()) {
                        return ids;
                    }
                }
                throw new IllegalStateException(
                        repository + "#" + number + " has more blockers than are read");
            }
        };
    }

    private static long databaseId(final Issue issue) {
        final Long id = issue.id();
        if (id == null) {
            throw new IllegalStateException("GitHub answered an issue without its id");
        }
        return id;
    }
}
