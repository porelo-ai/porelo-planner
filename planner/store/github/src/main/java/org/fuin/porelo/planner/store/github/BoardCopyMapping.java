package org.fuin.porelo.planner.store.github;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.fuin.porelo.planner.store.cache.TrackedUnlisted;
import org.fuin.porelo.planner.store.cache.TrackedWait;
import org.fuin.porelo.planner.store.cache.TrackerBoard;
import org.jspecify.annotations.Nullable;

/**
 * Maps GitHub's project items to the board copy's own records and back, part for part, so an item
 * loaded from the copy is the item GitHub gave.
 */
final class BoardCopyMapping {

    private BoardCopyMapping() {}

    /**
     * The project a configuration reads, as the copies name it.
     *
     * @param config what the project is read with
     * @return the project
     * @throws IllegalArgumentException when its host or owner is no name a file may carry
     */
    static BoardCopy.Project project(final ProjectConfig config) {
        final String host = config.forge().getHost();
        if (host == null) {
            throw new IllegalArgumentException("The forge names no host");
        }
        return new BoardCopy.Project(host, config.project().owner(), config.project().number());
    }

    /**
     * The copy of a snapshot read from GitHub's item list: every item as GitHub gave it, and the
     * project's fields and their faults, so {@link ItemsBoardReader#fromCopy} gives the snapshot's
     * board and revision again.
     *
     * @param snapshot the snapshot
     * @param cursor where the tracker's look for changes stands
     * @param held the planner's own saves the tracker's list may not show yet
     * @param listedAt each item's own last change as the tracker's list last showed it, by the
     *     items' ids
     * @param takenAt when the board was held
     * @return the copy
     * @throws IllegalArgumentException for a snapshot not read from GitHub's item list, whose board
     *     could not be mapped again from its items
     */
    static BoardCopy toCopy(
            final Snapshot snapshot,
            final ChangeCursor cursor,
            final List<TrackedItem> held,
            final Map<String, Instant> listedAt,
            final Instant takenAt) {
        final ProjectConfig read = snapshot.config();
        if (read.projectId().isEmpty()) {
            throw new IllegalArgumentException("The snapshot was not read from GitHub's item list");
        }
        return new BoardCopy(
                project(read),
                read.commit(),
                read.names(),
                takenAt,
                new TrackerBoard(
                        read.projectId(),
                        read.fieldIds(),
                        snapshot.fieldNames(),
                        read.optionIds(),
                        snapshot.fieldFaults(),
                        snapshot.restFieldIds()),
                snapshot.raw().values().stream()
                        .map(item -> tracked(item, listedAt.get(item.id())))
                        .toList(),
                cursor,
                held,
                snapshot.waits().entrySet().stream()
                        .map(
                                wait ->
                                        new TrackedWait(
                                                wait.getKey(),
                                                wait.getValue().repository(),
                                                wait.getValue().number(),
                                                wait.getValue().closed()))
                        .toList(),
                snapshot.unlisted().stream()
                        .map(
                                issue ->
                                        new TrackedUnlisted(
                                                issue.nodeId(),
                                                issue.repository(),
                                                issue.number(),
                                                issue.title(),
                                                issue.type(),
                                                issue.author(),
                                                issue.authorAssociation()))
                        .toList());
    }

    /**
     * An item as GitHub gave it, in the copy's terms.
     *
     * @param item the item
     * @return the copy's item
     */
    static TrackedItem tracked(final GitHubGraphQl.ProjectItem item) {
        return tracked(item, null);
    }

    /**
     * An item as GitHub gave it, in the copy's terms, with its own last change as the tracker's
     * list showed it.
     *
     * @param item the item
     * @param listedAt the item's own last change as the list showed it, or null when unknown
     * @return the copy's item
     */
    static TrackedItem tracked(
            final GitHubGraphQl.ProjectItem item, final @Nullable Instant listedAt) {
        final GitHubGraphQl.BoardIssue issue = item.issue();
        return new TrackedItem(
                item.id(),
                item.archived(),
                issue == null ? null : issue(issue),
                item.values().stream()
                        .map(
                                value ->
                                        new TrackedItem.Value(
                                                value.fieldId(),
                                                value.optionId(),
                                                value.name(),
                                                value.text(),
                                                value.number()))
                        .toList(),
                // GitHub's board read gives no time of the item's own; the REST list does.
                listedAt,
                // Labels or values past the read's page are not held: the item is not whole.
                Boolean.TRUE.equals(item.valuesPage().hasNextPage())
                        || (issue != null
                                && Boolean.TRUE.equals(issue.labelsPage().hasNextPage())));
    }

    /**
     * An item of the copy, as GitHub gave it.
     *
     * @param item the copy's item
     * @return the item
     */
    static GitHubGraphQl.ProjectItem item(final TrackedItem item) {
        final TrackedItem.Issue issue = item.issue();
        return new GitHubGraphQl.ProjectItem(
                item.id(),
                item.archived(),
                issue == null ? null : issue(issue, item.partial()),
                item.values().stream()
                        .map(
                                value ->
                                        new GitHubGraphQl.ItemValue(
                                                value.fieldId(),
                                                value.optionId(),
                                                value.name(),
                                                value.text(),
                                                value.number()))
                        .toList(),
                // Where a partial list goes on is not kept; it stays marked as not whole.
                new GitHubGraphQl.PageInfo(item.partial(), null));
    }

    private static TrackedItem.Issue issue(final GitHubGraphQl.BoardIssue issue) {
        return new TrackedItem.Issue(
                issue.id(),
                issue.databaseId(),
                issue.number(),
                issue.title(),
                issue.body(),
                issue.state(),
                issue.stateReason(),
                issue.updatedAt(),
                issue.repository(),
                issue.labels(),
                issue.parentId(),
                links(issue.subIssues()),
                links(issue.blockedBy()),
                issue.issueType(),
                issue.closedAt(),
                issue.fieldOptions().stream()
                        .map(chosen -> new TrackedItem.Choice(chosen.field(), chosen.option()))
                        .toList());
    }

    private static GitHubGraphQl.BoardIssue issue(
            final TrackedItem.Issue issue, final boolean partial) {
        return new GitHubGraphQl.BoardIssue(
                issue.id(),
                issue.key(),
                issue.number(),
                issue.title(),
                issue.body(),
                issue.state(),
                issue.stateReason(),
                issue.updatedAt(),
                issue.repository(),
                issue.labels(),
                issue.parentId(),
                refs(issue.subIssues()),
                refs(issue.blockedBy()),
                new GitHubGraphQl.PageInfo(partial, null),
                issue.type(),
                // A story read from the copy keeps the day its test passed.
                issue.closedAt(),
                issue.choices().stream()
                        .map(
                                chosen ->
                                        new GitHubGraphQl.IssueFieldOption(
                                                chosen.field(), chosen.option()))
                        .toList());
    }

    private static TrackedItem.Links links(final GitHubGraphQl.IssueRefs refs) {
        final GitHubGraphQl.PageInfo page = refs.pageInfo();
        return new TrackedItem.Links(
                refs.nodes().stream()
                        .map(
                                ref ->
                                        new TrackedItem.Link(
                                                ref.id(),
                                                ref.databaseId(),
                                                ref.number(),
                                                ref.repository()))
                        .toList(),
                // The board reads a missing answer as no more, as GitHub's own read does.
                Boolean.TRUE.equals(page.hasNextPage()),
                page.endCursor(),
                // GitHub's count, so a blocker it does not name is held as one not readable.
                refs.totalCount());
    }

    private static GitHubGraphQl.IssueRefs refs(final TrackedItem.Links links) {
        final @Nullable String after = links.after();
        return new GitHubGraphQl.IssueRefs(
                links.links().stream()
                        .map(
                                link ->
                                        new GitHubGraphQl.IssueRef(
                                                link.id(),
                                                link.key(),
                                                link.number(),
                                                link.repository()))
                        .toList(),
                new GitHubGraphQl.PageInfo(links.more(), after),
                links.total());
    }
}
