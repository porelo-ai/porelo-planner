package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One durable issue row, including the index that owns its anchor and the numbers of its own
 * repository it is blocked by.
 *
 * @param number the issue number, including its prefix and leading zeros
 * @param state the index section containing the row
 * @param size the size, or a dash for closed rows
 * @param title the row's title
 * @param path the repository-relative index path owning the anchor
 * @param source the index's descriptive source location
 * @param blockedBy issue numbers from the blocked-by column
 * @param location original row location and first-cell citation data, excluded from metadata JSON
 */
public record IssueIndex(
        String number,
        String state,
        String size,
        String title,
        String path,
        String source,
        List<String> blockedBy,
        @JsonIgnore Location location) {
    /**
     * Original citation data read by the shared index parser.
     *
     * @param anchor the row's explicit anchor, null when absent
     * @param file the optional issue-file link from its first cell
     * @param line the one-based source line, or zero for a constructed row
     * @param numberEnd character offset just after the first-cell number and its optional link
     */
    public record Location(
            @Nullable String anchor, @Nullable String file, int line, int numberEnd) {}

    /** Constructs metadata without a source row, retaining the original constructor contract. */
    public IssueIndex(
            String number,
            String state,
            String size,
            String title,
            String path,
            String source,
            List<String> blockedBy) {
        this(number, state, size, title, path, source, blockedBy, new Location(null, null, 0, 0));
    }

    /** Copies the dependency list to keep this row immutable. */
    public IssueIndex {
        blockedBy = List.copyOf(blockedBy);
    }

    /**
     * Links to this row's durable anchor on the repository's main branch.
     *
     * @param repository the repository name
     * @return the row URL
     */
    String link(String repository) {
        return "https://github.com/porelo-ai/"
                + repository
                + "/blob/main/"
                + path
                + "#"
                + number.toLowerCase(Locale.ROOT);
    }
}
