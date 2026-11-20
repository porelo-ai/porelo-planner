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
 * @param comments its comments, oldest first
 * @param link where a person reads it
 * @param updatedAt when GitHub last changed it
 * @param archived whether the item is archived in the project
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
        List<CommentView> comments,
        URI link,
        Instant updatedAt,
        boolean archived) {

    /** Copies the map and the lists, keeping their order. */
    public ItemView {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        labels = List.copyOf(labels);
        subIssueIds = List.copyOf(subIssueIds);
        blockedByIds = List.copyOf(blockedByIds);
        comments = List.copyOf(comments);
    }
}
