package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Version;
import org.junit.jupiter.api.Test;

class ItemVersionsTest {

    private static final int PAGE = 100;

    private static final ItemView HELD =
            new ItemView(
                    "PVTI_1",
                    "I_1",
                    "example-project",
                    1,
                    "One",
                    "",
                    true,
                    null,
                    Map.of(),
                    List.of(),
                    null,
                    List.of(),
                    List.of(),
                    URI.create("https://github.com/example-org/example-project/issues/1"),
                    Instant.parse("2026-11-20T10:00:00Z"),
                    false);

    /** The first page of a list GitHub cuts at its page size, with the count it gives. */
    private static GitHubGraphQl.IssueRefs firstPage(final String prefix, final int total) {
        return new GitHubGraphQl.IssueRefs(
                IntStream.rangeClosed(1, PAGE)
                        .mapToObj(
                                n ->
                                        new GitHubGraphQl.IssueRef(
                                                prefix + n, n, n, "example-org/example-lib"))
                        .toList(),
                new GitHubGraphQl.PageInfo(total > PAGE, total > PAGE ? "next" : null),
                total);
    }

    private static List<String> labels() {
        return IntStream.rangeClosed(1, PAGE).mapToObj(n -> "label-" + n).toList();
    }

    /**
     * The version of the item as a read by its id gives it: the first page of each list with
     * GitHub's counts, a hundred labels on the page and no field value on it.
     */
    private static Version version(
            final int subIssues, final int blockers, final int labels, final int values) {
        final boolean moreLabels = labels > PAGE;
        final boolean moreValues = values > 0;
        final GitHubGraphQl.BoardIssue issue =
                new GitHubGraphQl.BoardIssue(
                        "I_1",
                        1L,
                        1,
                        "One",
                        "",
                        "OPEN",
                        null,
                        Instant.parse("2026-11-20T10:00:00Z"),
                        "example-org/example-project",
                        labels(),
                        null,
                        firstPage("S_", subIssues),
                        firstPage("B_", blockers),
                        new GitHubGraphQl.PageInfo(moreLabels, moreLabels ? "next" : null),
                        null,
                        null,
                        List.of(),
                        labels);
        return ItemVersions.of(
                new GitHubGraphQl.IssueOnBoard(
                        issue,
                        "PVTI_1",
                        List.of(),
                        new GitHubGraphQl.PageInfo(moreValues, moreValues ? "next" : null),
                        values),
                Map.of(),
                HELD);
    }

    @Test
    void anEntryAddedPastAListsFirstPageChangesTheItemsVersion() {
        // Harm: a sub-issue, blocker, label or field value added past the first hundred leaves the
        // item's version as it was, so a write made against the board read before it is taken as
        // made against the item as it stands.
        final Version before = version(101, 101, 100, 0);

        assertThat(version(101, 101, 100, 0)).as("the same item read again").isEqualTo(before);
        assertThat(version(102, 101, 100, 0))
                .as("a 102nd sub-issue, the first hundred the same")
                .isNotEqualTo(before);
        assertThat(version(101, 102, 100, 0))
                .as("a 102nd blocker, the first hundred the same")
                .isNotEqualTo(before);
        assertThat(version(101, 101, 101, 0))
                .as("a 101st label, the first hundred the same")
                .isNotEqualTo(before);
        assertThat(version(101, 101, 100, 1))
                .as("a field value past the first page, the first page the same")
                .isNotEqualTo(before);
    }

    @Test
    void anUnchangedItemWithAPriorityKeepsTheVersionTheBoardGaveIt() {
        // Harm: the read right before a write leaves out the priority the issue's own field
        // holds, so every write to an item with a priority is refused as changed since it was read.
        final GitHubGraphQl.IssueRefs none =
                new GitHubGraphQl.IssueRefs(List.of(), new GitHubGraphQl.PageInfo(false, null), 0);
        final GitHubGraphQl.BoardIssue issue =
                new GitHubGraphQl.BoardIssue(
                        "I_1",
                        1L,
                        1,
                        "One",
                        "",
                        "OPEN",
                        null,
                        Instant.parse("2026-11-20T10:00:00Z"),
                        "example-org/example-project",
                        List.of(),
                        null,
                        none,
                        none,
                        new GitHubGraphQl.PageInfo(false, null),
                        null,
                        null,
                        List.of(new GitHubGraphQl.IssueFieldOption(FieldNames.PRIORITY, "High")),
                        0);
        final ItemView held =
                new ItemView(
                        "PVTI_1",
                        "I_1",
                        "example-project",
                        1,
                        "One",
                        "",
                        true,
                        null,
                        // The fields as the board read maps them, the issue's priority among them.
                        ItemsBoardReader.values(List.of(), Map.of(), issue),
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        URI.create("https://github.com/example-org/example-project/issues/1"),
                        Instant.parse("2026-11-20T10:00:00Z"),
                        false);

        final Version live =
                ItemVersions.of(
                        new GitHubGraphQl.IssueOnBoard(
                                issue,
                                "PVTI_1",
                                List.of(),
                                new GitHubGraphQl.PageInfo(false, null),
                                0),
                        Map.of(),
                        held);

        assertThat(held.fields()).containsEntry(FieldNames.PRIORITY, "High");
        assertThat(live).isEqualTo(ItemVersions.of(held));
    }

    @Test
    void everyLabelOrFieldValueAddedPastThePageChangesTheItemsVersion() {
        // Harm: a second label or field value added past the page read leaves the item's version
        // as it was once one lies there, so a write made against the board read before it is
        // taken as made against the item as it stands.
        final Version before = version(101, 101, 101, 1);

        assertThat(version(101, 101, 101, 1)).as("the same item read again").isEqualTo(before);
        assertThat(version(101, 101, 102, 1))
                .as("a 102nd label, the first hundred the same")
                .isNotEqualTo(before);
        assertThat(version(101, 101, 101, 2))
                .as("a second field value past the page, the page the same")
                .isNotEqualTo(before);
    }
}
