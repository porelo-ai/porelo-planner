package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Version;

/**
 * The version of a project item as GitHub holds it: its own stored values, never when GitHub last
 * touched it, so a comment or another item's change leaves it equal. The board read and the live
 * re-read before a write both give it from the same {@link ItemView}, so they agree.
 */
final class ItemVersions {

    private ItemVersions() {}

    /**
     * The item's version over its project item id, its field values, its parent, sub-issues and
     * blockers with the count of blockers not seen, its labels, its issue type, whether it is open
     * and why it closed, whether it is archived, its title and its body.
     *
     * @param item the item as GitHub gave it
     * @return its version
     */
    static Version of(final ItemView item) {
        final List<String> fields = new ArrayList<>();
        for (final Map.Entry<String, String> field : new TreeMap<>(item.fields()).entrySet()) {
            fields.add(field.getKey());
            fields.add(field.getValue());
        }
        return Version.of(
                "github-item",
                item.itemId(),
                fields,
                item.parentIssueId(),
                item.subIssueIds(),
                item.blockedByIds().stream().sorted().toList(),
                item.unseenBlockers(),
                item.labels().stream().sorted().toList(),
                item.issueType(),
                item.open(),
                item.stateReason(),
                item.archived(),
                item.title(),
                item.body());
    }

    /**
     * The version of an item as GitHub gives it in a read by the issue's id right before a write,
     * mapped as the board read maps it. That read does not tell whether the item is archived, so
     * the held item's archived state is taken.
     *
     * @param now the item as the read gave it, with its issue
     * @param fieldNames the project's field names by their ids
     * @param held the item as the board holds it
     * @return its version, equal to the board's version of it while the item is unchanged
     */
    static Version of(
            final GitHubGraphQl.IssueOnBoard now,
            final Map<String, String> fieldNames,
            final ItemView held) {
        final GitHubGraphQl.BoardIssue issue = now.issue();
        final String reason = issue.stateReason();
        return of(
                new ItemView(
                        Objects.requireNonNullElse(now.itemId(), ""),
                        issue.id(),
                        held.repository(),
                        issue.number(),
                        issue.title(),
                        Objects.requireNonNullElse(issue.body(), ""),
                        "OPEN".equals(issue.state()),
                        reason == null ? null : reason.toLowerCase(Locale.ROOT),
                        ItemsBoardReader.values(now.values(), fieldNames),
                        issue.labels(),
                        issue.parentId(),
                        issue.subIssues().nodes().stream().map(GitHubGraphQl.IssueRef::id).toList(),
                        issue.blockedBy().nodes().stream().map(GitHubGraphQl.IssueRef::id).toList(),
                        held.link(),
                        issue.updatedAt(),
                        held.archived(),
                        List.of(),
                        ItemsBoardReader.unseen(issue.blockedBy()),
                        issue.issueType(),
                        issue.closedAt()));
    }
}
