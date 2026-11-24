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
     * blockers with the count of blockers not seen and GitHub's counts of both, whether labels or
     * values lie past the page read, how many labels and values GitHub counts past it, its labels,
     * its issue type, whether it is open and why it closed, whether it is archived, its title and
     * its body.
     *
     * <p>Past a list's first page only its length is told: an entry swapped there for another
     * leaves the version equal.
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
                // A list past its first page is told by GitHub's counts and whether more lie past
                // the page, since the ids read are the first page's alone. The blocker count
                // repeats the unseen blockers while the list fits one page; past it they are none.
                item.subIssueCount(),
                item.blockerCount(),
                item.partial(),
                // Labels and values past their first page are told by GitHub's counts alone.
                item.unseenLabels(),
                item.unseenValues(),
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
                        // The priority lies in the issue's own field, as the board read takes it.
                        ItemsBoardReader.values(now.values(), fieldNames, issue),
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
                        issue.closedAt(),
                        issue.subIssues().totalCount(),
                        issue.blockedBy().totalCount(),
                        ItemsBoardReader.partial(now.valuesPage(), issue),
                        ItemView.unseen(issue.labelCount(), issue.labels().size()),
                        ItemView.unseen(now.valueCount(), now.values().size())));
    }
}
