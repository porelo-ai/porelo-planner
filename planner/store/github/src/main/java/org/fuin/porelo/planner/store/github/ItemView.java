package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One item of the GitHub project as the store reads it: an issue with its fields, before it is
 * mapped to a story or an issue of the board.
 *
 * @param itemId the project item's node id, which a field value is written to
 * @param issueId the issue's node id, which a comment, a body or a state is written to
 * @param repository the name of the repository that holds the issue
 * @param number the issue's number in its repository
 * @param title its title
 * @param body its body
 * @param open whether GitHub has it open
 * @param stateReason why GitHub closed it ({@code completed}, {@code not_planned}), or null while
 *     open
 * @param fields the project's field values by field name, each as text: an option's name, a text, a
 *     number
 * @param labels its labels' names
 * @param parentIssueId the node id of its GitHub parent issue, or null
 * @param subIssueIds the node ids of its GitHub sub-issues, in GitHub's order
 * @param blockedByIds the node ids of the issues it is blocked by
 * @param comments its comments, oldest first, or null when they were not read, as the board read
 *     reads none
 * @param link where a person reads it
 * @param updatedAt when GitHub last changed it
 * @param archived whether the item is archived in the project
 * @param blockers the issues it is blocked by, each with its repository and number, in the order of
 *     {@code blockedByIds}; empty when the read named only their ids
 * @param unseenBlockers how many more issues GitHub counts among its blockers than it names, as it
 *     leaves out one the person's login cannot read
 */
public record ItemView(
        String itemId,
        String issueId,
        String repository,
        int number,
        String title,
        String body,
        boolean open,
        @Nullable String stateReason,
        Map<String, String> fields,
        List<String> labels,
        @Nullable String parentIssueId,
        List<String> subIssueIds,
        List<String> blockedByIds,
        @Nullable List<CommentView> comments,
        URI link,
        Instant updatedAt,
        boolean archived,
        List<Blocker> blockers,
        int unseenBlockers) {

    /**
     * An issue that blocks the item's, as GitHub names it.
     *
     * @param issueId its node id
     * @param repository its repository with its owner, as GitHub names it
     * @param number its number in its repository
     */
    public record Blocker(String issueId, String repository, int number) {}

    /** Copies the map and the lists, keeping their order. */
    public ItemView {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        labels = List.copyOf(labels);
        subIssueIds = List.copyOf(subIssueIds);
        blockedByIds = List.copyOf(blockedByIds);
        comments = comments == null ? null : List.copyOf(comments);
        blockers = List.copyOf(blockers);
        if (unseenBlockers < 0) {
            throw new IllegalArgumentException("An item counts no blockers below none");
        }
    }

    /**
     * An item whose blockers are known by their ids alone.
     *
     * @param itemId the project item's node id
     * @param issueId the issue's node id
     * @param repository the name of the repository that holds the issue
     * @param number the issue's number in its repository
     * @param title its title
     * @param body its body
     * @param open whether GitHub has it open
     * @param stateReason why GitHub closed it, or null while open
     * @param fields the project's field values by field name
     * @param labels its labels' names
     * @param parentIssueId the node id of its GitHub parent issue, or null
     * @param subIssueIds the node ids of its GitHub sub-issues
     * @param blockedByIds the node ids of the issues it is blocked by
     * @param comments its comments, or null when they were not read
     * @param link where a person reads it
     * @param updatedAt when GitHub last changed it
     * @param archived whether the item is archived in the project
     */
    public ItemView(
            final String itemId,
            final String issueId,
            final String repository,
            final int number,
            final String title,
            final String body,
            final boolean open,
            final @Nullable String stateReason,
            final Map<String, String> fields,
            final List<String> labels,
            final @Nullable String parentIssueId,
            final List<String> subIssueIds,
            final List<String> blockedByIds,
            final @Nullable List<CommentView> comments,
            final URI link,
            final Instant updatedAt,
            final boolean archived) {
        this(
                itemId,
                issueId,
                repository,
                number,
                title,
                body,
                open,
                stateReason,
                fields,
                labels,
                parentIssueId,
                subIssueIds,
                blockedByIds,
                comments,
                link,
                updatedAt,
                archived,
                List.of(),
                0);
    }
}
