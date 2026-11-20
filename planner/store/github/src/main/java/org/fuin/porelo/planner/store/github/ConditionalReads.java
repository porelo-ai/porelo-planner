package org.fuin.porelo.planner.store.github;

import org.fuin.porelo.forge.github.Conditional;
import org.fuin.porelo.forge.github.CursorPage;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.Page;
import org.fuin.porelo.forge.github.RateLimit;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.ProjectsV2ItemWithContent;
import org.jspecify.annotations.Nullable;

/**
 * The two reads that ask GitHub whether a project changed, each sent with the entity tag of the
 * last answer, so GitHub answers 304 without counting it against the rate limit; nothing that
 * writes.
 */
public interface ConditionalReads {

    /** The most items one page of the project's items asks for. */
    int PAGE_SIZE = 100;

    /**
     * One answer: whether it changed, the tag to send next time and what it said of the rate limit.
     *
     * @param changed whether GitHub answered with a new value rather than 304
     * @param etag the tag to send next time, or null when GitHub gave none that can be sent
     * @param next the cursor of the next page of a changed answer, or null after the last or on a
     *     304
     * @param remaining the requests left, or null when the answer did not say
     * @param limit the requests allowed in the window, or null when the answer did not say
     * @param full whether a changed page of items held as many items as a page asks for, so a page
     *     after it may come without the page's own tag changing
     */
    record Answer(
            boolean changed,
            @Nullable String etag,
            @Nullable String next,
            @Nullable Long remaining,
            @Nullable Long limit,
            boolean full) {

        /**
         * An answer that is no full page of items.
         *
         * @param changed whether GitHub answered with a new value rather than 304
         * @param etag the tag to send next time, or null when GitHub gave none that can be sent
         * @param next the cursor of the next page of a changed answer, or null
         * @param remaining the requests left, or null when the answer did not say
         * @param limit the requests allowed in the window, or null when the answer did not say
         */
        public Answer(
                final boolean changed,
                final @Nullable String etag,
                final @Nullable String next,
                final @Nullable Long remaining,
                final @Nullable Long limit) {
            this(changed, etag, next, remaining, limit, false);
        }
    }

    /**
     * Asks whether any issue of a repository changed: the first issue by its last update.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param etag the tag of the last answer, or null for a first ask
     * @return the answer
     */
    Answer issues(String token, String owner, String repository, @Nullable String etag);

    /**
     * Asks whether a page of the project's items changed.
     *
     * @param token the person's token
     * @param owner the organization that owns the project
     * @param number the project's number
     * @param after the cursor of the page, or null for the first
     * @param etag the tag of the last answer for this page, or null for a first ask
     * @return the answer
     */
    Answer projectItems(
            String token, String owner, int number, @Nullable String after, @Nullable String etag);

    /**
     * Asks through the forge client's REST calls.
     *
     * @param client the client
     * @return its two conditional reads
     */
    static ConditionalReads over(final GitHubClient client) {
        return new ConditionalReads() {
            @Override
            public Answer issues(
                    final String token,
                    final String owner,
                    final String repository,
                    final @Nullable String etag) {
                // Newest update first, so any change to any issue changes the first page.
                final Conditional<Page<Issue>> answer =
                        client.issuesIfChanged(
                                token, owner, repository, "all", null, null, 1, 1, etag);
                return answer(answer, null, false);
            }

            @Override
            public Answer projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after,
                    final @Nullable String etag) {
                final Conditional<CursorPage<ProjectsV2ItemWithContent>> answer =
                        client.projectItemsIfChanged(token, owner, number, after, PAGE_SIZE, etag);
                final CursorPage<ProjectsV2ItemWithContent> page = answer.value();
                return answer(
                        answer,
                        page == null ? null : page.after(),
                        page != null && page.items().size() >= PAGE_SIZE);
            }
        };
    }

    private static Answer answer(
            final Conditional<?> answer, final @Nullable String next, final boolean full) {
        final RateLimit limit = answer.rateLimit();
        return new Answer(
                answer.changed(),
                answer.etag(),
                next,
                limit == null ? null : limit.remaining(),
                limit == null ? null : limit.limit(),
                full);
    }
}
