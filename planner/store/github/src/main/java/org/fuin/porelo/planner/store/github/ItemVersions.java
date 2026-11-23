package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
}
