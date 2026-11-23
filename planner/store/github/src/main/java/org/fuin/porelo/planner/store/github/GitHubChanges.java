package org.fuin.porelo.planner.store.github;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.porelo.forge.github.Conditional;
import org.fuin.porelo.forge.github.CursorPage;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.ProjectItemSummary;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells what changed on a GitHub project since a look, over GitHub's REST list of the project's
 * items, each page sent with the entity tag of its last answer: a page GitHub answers 304 is as it
 * was and brings the item ids it held then; a page answered anew is compared item by item with the
 * items the planner holds. GitHub answers a 304 without counting it against the rate limit, so a
 * look at an unchanged board costs one request a page and no GraphQL read.
 *
 * <p>What the list shows of an item is its own and its issue's last change, its state, whether it
 * is archived, the values of the fields named, and its issue's sub-issue and blocker counts. A link
 * swapped for another, or sub-issues put in another order, changes none of them: such a change
 * shows at the item's next change or at a whole read.
 */
public final class GitHubChanges implements TrackerChanges, ItemIssues {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubChanges.class);

    /** The items one page of the list asks for, as many as GitHub gives. */
    static final int PAGE_SIZE = 100;

    /**
     * The most pages of items one look asks; a project beyond them is not looked at but read whole,
     * which reads its list to the end.
     */
    static final int MOST_LOOK_PAGES = 50;

    /** Reads one page of the project's REST item list, sent with a tag. */
    @FunctionalInterface
    public interface ItemPages {

        /**
         * Reads a page.
         *
         * @param token the person's token
         * @param owner the organization that owns the project
         * @param number the project's number
         * @param after the cursor of the page, or null for the first
         * @param perPage the items asked for
         * @param fieldIds the numeric ids of the fields whose values the page carries
         * @param etag the tag of the page's last answer, or null to ask it whole
         * @return GitHub's answer
         */
        Conditional<CursorPage<ProjectItemSummary>> page(
                String token,
                String owner,
                int number,
                @Nullable String after,
                int perPage,
                List<Long> fieldIds,
                @Nullable String etag);

        /**
         * Reads through the forge client.
         *
         * @param client the client
         * @return its item pages
         */
        static ItemPages over(final GitHubClient client) {
            return client::projectItemsIfChanged;
        }
    }

    private final ItemPages pages;

    private final ProjectQueries queries;

    /**
     * The issue each item the copy or the last look holds, so a new item is read again by its
     * issue; kept to the items known or listed at each look.
     */
    private final Map<String, String> issues = new ConcurrentHashMap<>();

    /** What the last look's list showed of each item on a page it answered anew. */
    private volatile Map<String, Listed> listed = Map.of();

    /**
     * Makes the look of one project.
     *
     * @param pages the REST item list
     * @param queries reads an item missing from the list by its issue, to tell whether it is gone
     */
    public GitHubChanges(final ItemPages pages, final ProjectQueries queries) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @Override
    public @Nullable String issueOf(final String itemId) {
        return issues.get(itemId);
    }

    @Override
    public @Nullable Listed listed(final String itemId) {
        return listed.get(itemId);
    }

    /**
     * The fields the planner reads and writes on a board, by their node ids, with the numeric ids
     * the REST list takes them by.
     *
     * @param board the board as read from GitHub
     * @return the fields, or null when one of them on the board has no numeric id kept, so the list
     *     cannot be asked for its values
     */
    static @Nullable Map<String, Long> plannersFields(final Snapshot board) {
        final Map<String, Long> fields = new LinkedHashMap<>();
        for (final Map.Entry<String, String> field : board.config().fieldIds().entrySet()) {
            if (!FieldNames.ALL.containsKey(field.getKey())) {
                continue;
            }
            final Long numeric = board.restFieldIds().get(field.getValue());
            if (numeric == null || numeric <= 0) {
                return null;
            }
            fields.put(field.getValue(), numeric);
        }
        return fields;
    }

    /**
     * {@inheritDoc}
     *
     * <p>GitHub's refusal for a spent rate limit is thrown as it is, so the pace holds; any other
     * failure of GitHub answers that the look cannot tell, and the board is read whole, whose
     * refusal is then the one shown.
     */
    @Override
    public Optional<Changed> since(
            final ProjectConfig config,
            final String token,
            final ChangeCursor cursor,
            final Map<String, TrackedItem> known) {
        return since(config, token, cursor, known, Map.of());
    }

    /**
     * Answers what changed since the cursor, comparing the values of the fields named too; without
     * them a value's change is seen by the item's own time alone.
     *
     * @param config the project as GitHub was read for the board, with its node id
     * @param token the person's token, never logged
     * @param cursor where the last look stands
     * @param known the items the copy holds, by item id
     * @param restFieldIds the numeric ids of the fields whose values are compared, by the fields'
     *     ids, as the board's copy keeps them; null when the planner's fields cannot all be named
     * @return what changed, or empty when GitHub cannot tell, or the fields cannot all be named in
     *     one ask, and the board is read whole
     */
    public Optional<Changed> since(
            final ProjectConfig config,
            final String token,
            final ChangeCursor cursor,
            final Map<String, TrackedItem> known,
            final @Nullable Map<String, Long> restFieldIds) {
        return since(config, token, cursor, known, restFieldIds, listedSoFar -> {});
    }

    /**
     * Answers what changed since the cursor, as {@link #since(ProjectConfig, String, ChangeCursor,
     * Map, Map)} does, telling after each page how many items the list showed so far.
     *
     * @param config the project as GitHub was read for the board, with its node id
     * @param token the person's token, never logged
     * @param cursor where the last look stands
     * @param known the items the copy holds, by item id
     * @param restFieldIds the numeric ids of the fields whose values are compared, or null
     * @param pageRead told the items listed so far after each page, a page answered 304 included
     * @return what changed, or empty when GitHub cannot tell
     */
    public Optional<Changed> since(
            final ProjectConfig config,
            final String token,
            final ChangeCursor cursor,
            final Map<String, TrackedItem> known,
            final @Nullable Map<String, Long> restFieldIds,
            final java.util.function.IntConsumer pageRead) {
        listed = Map.of();
        if (restFieldIds == null || restFieldIds.size() > GitHubClient.MAX_FIELDS) {
            // A value of a field not named would change unseen; only a whole read tells.
            LOG.info(
                    "The planner's fields cannot all be named in one ask; the board is read whole");
            return Optional.empty();
        }
        known.forEach(
                (id, item) -> {
                    final TrackedItem.Issue issue = item.issue();
                    if (issue != null) {
                        issues.put(id, issue.id());
                    }
                });
        try {
            return Optional.ofNullable(look(config, token, cursor, known, restFieldIds, pageRead));
        } catch (final GitHubException e) {
            if (e.rateLimited()) {
                throw e;
            }
            // GitHub's own words may quote what was asked; only the status is named.
            LOG.info(
                    "GitHub's item list did not tell what changed (HTTP {}); the board is read"
                            + " whole",
                    e.status());
            return Optional.empty();
        }
    }

    private @Nullable Changed look(
            final ProjectConfig config,
            final String token,
            final ChangeCursor cursor,
            final Map<String, TrackedItem> known,
            final Map<String, Long> restFieldIds,
            final java.util.function.IntConsumer pageRead) {
        final long started = System.nanoTime();
        final String owner = config.project().owner();
        final int number = config.project().number();
        final Map<Long, String> named = new LinkedHashMap<>();
        restFieldIds.forEach((node, numeric) -> named.putIfAbsent(numeric, node));
        final List<Long> numeric = List.copyOf(named.keySet());
        // A cursor without GitHub's time was not taken from GitHub's answers: its tags are not
        // trusted, and every page is asked whole.
        final List<ChangeCursor.Page> before = cursor.asOf() == null ? List.of() : cursor.pages();
        final List<ChangeCursor.Page> now = new ArrayList<>();
        final Set<String> listed = new LinkedHashSet<>();
        final Set<String> changed = new LinkedHashSet<>();
        final Set<String> added = new LinkedHashSet<>();
        final Map<String, Listed> shown = new LinkedHashMap<>();
        @Nullable Instant asOf = null;
        boolean dated = true;
        boolean shifted = false;
        @Nullable String after = null;
        for (int at = 0; ; at++) {
            if (at >= MOST_LOOK_PAGES) {
                // The look does not stand for such a project; the whole read reads it.
                LOG.info(
                        "GitHub's item list has too many pages for a look, after {} ms",
                        since(started));
                return null;
            }
            final @Nullable String place = after;
            final ChangeCursor.Page was =
                    !shifted && at < before.size() && Objects.equals(before.get(at).after(), place)
                            ? before.get(at)
                            : null;
            // A full last page may have a page after it that its own tag never shows, so it is
            // asked whole and its next link read.
            final boolean wholeLast = was != null && at == before.size() - 1 && was.full();
            final String sent = was == null || wholeLast ? null : was.etag();
            final Conditional<CursorPage<ProjectItemSummary>> answer =
                    pages.page(token, owner, number, place, PAGE_SIZE, numeric, sent);
            final Instant date = answer.date();
            if (date == null) {
                dated = false;
            } else if (asOf == null) {
                asOf = date;
            }
            final CursorPage<ProjectItemSummary> page = answer.value();
            final @Nullable String next;
            if (page == null) {
                // A 304 answers only the tag sent, so the page is as the last look saw it.
                final ChangeCursor.Page same = Objects.requireNonNull(was, "a 304 without a tag");
                listed.addAll(same.itemIds());
                now.add(new ChangeCursor.Page(place, answer.etag(), same.full(), same.itemIds()));
                next = at + 1 < before.size() ? before.get(at + 1).after() : null;
            } else {
                final List<String> ids = new ArrayList<>();
                for (final ProjectItemSummary item : page.items()) {
                    ids.add(item.id());
                    listed.add(item.id());
                    final ProjectItemSummary.IssueSummary issue = item.issue();
                    if (issue != null) {
                        issues.put(item.id(), issue.id());
                    }
                    shown.put(
                            item.id(),
                            new Listed(
                                    item.archived(),
                                    item.updatedAt(),
                                    issue == null ? null : issue.updatedAt()));
                    final TrackedItem held = known.get(item.id());
                    if (held == null) {
                        // A draft or a pull request is no planning item, so none is new.
                        if (issue != null) {
                            added.add(item.id());
                        }
                    } else if (differs(item, held, named)) {
                        changed.add(item.id());
                    }
                }
                if (was != null && !ids.equals(was.itemIds())) {
                    // The items moved between pages, or within them: no later page's tag says
                    // what it holds now.
                    shifted = true;
                }
                now.add(
                        new ChangeCursor.Page(
                                place, answer.etag(), page.items().size() >= PAGE_SIZE, ids));
                next = page.after();
            }
            pageRead.accept(listed.size());
            if (next == null) {
                break;
            }
            after = next;
        }
        final Set<String> gone = new LinkedHashSet<>();
        final Map<String, String> missing = new LinkedHashMap<>();
        for (final Map.Entry<String, TrackedItem> each : known.entrySet()) {
            if (listed.contains(each.getKey())) {
                continue;
            }
            final TrackedItem.Issue issue = each.getValue().issue();
            if (issue == null) {
                gone.add(each.getKey());
            } else {
                missing.put(issue.id(), each.getKey());
            }
        }
        if (!missing.isEmpty()) {
            if (config.projectId().isBlank()) {
                // Without the project's id no missing item can be asked after by its issue.
                LOG.info(
                        "A look cannot ask after missing items without the project's id, after {} ms",
                        since(started));
                return null;
            }
            gone.addAll(goneOf(config, token, missing));
        }
        // Only the items held or listed are asked after again; the others are let go.
        final Set<String> kept = new LinkedHashSet<>(known.keySet());
        kept.addAll(listed);
        issues.keySet().retainAll(kept);
        this.listed = Map.copyOf(shown);
        LOG.info(
                "GitHub's item list showed {} changed, {} new and {} gone items in {} pages, in {}"
                        + " ms",
                changed.size(),
                added.size(),
                gone.size(),
                now.size(),
                since(started));
        return new Changed(changed, added, gone, new ChangeCursor(dated ? asOf : null, now));
    }

    /**
     * Answers which of the items missing from the list GitHub holds no longer: the list may lag an
     * add for a moment, so only the item read by its issue tells.
     *
     * @param missing the items' ids by their issues' node ids
     */
    private Set<String> goneOf(
            final ProjectConfig config, final String token, final Map<String, String> missing) {
        final Set<String> gone = new LinkedHashSet<>();
        final List<String> all = List.copyOf(missing.keySet());
        for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_ISSUES) {
            final List<String> chunk =
                    all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_ISSUES));
            final GitHubGraphQl.IssueBoard answer =
                    queries.issueBoard(token, chunk, config.projectId());
            for (int at = 0; at < chunk.size(); at++) {
                final String itemId = Objects.requireNonNull(missing.get(chunk.get(at)));
                final GitHubGraphQl.IssueOnBoard on =
                        at < answer.issues().size() ? answer.issues().get(at) : null;
                if (on == null || !itemId.equals(on.itemId())) {
                    gone.add(itemId);
                }
            }
        }
        return gone;
    }

    /**
     * Whether the list shows an item otherwise than the planner holds it; an item whose labels,
     * values, sub-issues or blockers the planner holds only in part is never taken as complete and
     * is read again.
     */
    static boolean differs(
            final ProjectItemSummary listed,
            final TrackedItem held,
            final Map<Long, String> named) {
        if (held.partial() || listed.archived() != held.archived()) {
            return true;
        }
        final Instant itemTime = held.updatedAt();
        if (itemTime != null && !itemTime.equals(listed.updatedAt())) {
            return true;
        }
        final ProjectItemSummary.IssueSummary issue = listed.issue();
        final TrackedItem.Issue mine = held.issue();
        if (issue == null || mine == null) {
            return (issue == null) != (mine == null);
        }
        if (!issue.id().equals(mine.id())
                || !issue.updatedAt().equals(mine.updatedAt())
                || !issue.state().equalsIgnoreCase(mine.state())
                || mine.subIssues().more()
                || mine.blockedBy().more()
                || counted(issue.subIssues(), mine.subIssues())
                || counted(issue.blockedBy(), mine.blockedBy())) {
            return true;
        }
        for (final Map.Entry<Long, String> field : named.entrySet()) {
            if (!sameValue(value(listed, field.getKey()), value(held, field.getValue()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean counted(final @Nullable Integer count, final TrackedItem.Links held) {
        return count != null && count != held.total();
    }

    private static ProjectItemSummary.@Nullable ValueSummary value(
            final ProjectItemSummary item, final long fieldId) {
        for (final ProjectItemSummary.ValueSummary value : item.values()) {
            if (value.fieldId() == fieldId) {
                return value;
            }
        }
        return null;
    }

    private static TrackedItem.@Nullable Value value(final TrackedItem item, final String fieldId) {
        for (final TrackedItem.Value value : item.values()) {
            if (value.fieldId().equals(fieldId)) {
                return value;
            }
        }
        return null;
    }

    /** Whether a listed value and a held one are the same; none and an empty one are the same. */
    private static boolean sameValue(
            final ProjectItemSummary.@Nullable ValueSummary listed,
            final TrackedItem.@Nullable Value held) {
        final String option = listed == null ? null : listed.optionId();
        final String text = listed == null ? null : listed.text();
        final Double number = listed == null ? null : listed.number();
        if (held == null) {
            return option == null && text == null && number == null;
        }
        return Objects.equals(option, held.optionId())
                && Objects.equals(text, held.text())
                && Objects.equals(number, held.number());
    }

    private static long since(final long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
