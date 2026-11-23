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

    private static Version version(
            final int subIssues,
            final int blockers,
            final boolean moreLabels,
            final boolean moreValues) {
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
                        List.of());
        return ItemVersions.of(
                new GitHubGraphQl.IssueOnBoard(
                        issue,
                        "PVTI_1",
                        List.of(),
                        new GitHubGraphQl.PageInfo(moreValues, moreValues ? "next" : null)),
                Map.of(),
                HELD);
    }

    @Test
    void anEntryAddedPastAListsFirstPageChangesTheItemsVersion() {
        // Harm: a sub-issue, blocker, label or field value added past the first hundred leaves the
        // item's version as it was, so a write made against the board read before it is taken as
        // made against the item as it stands.
        final Version before = version(101, 101, false, false);

        assertThat(version(101, 101, false, false))
                .as("the same item read again")
                .isEqualTo(before);
        assertThat(version(102, 101, false, false))
                .as("a 102nd sub-issue, the first hundred the same")
                .isNotEqualTo(before);
        assertThat(version(101, 102, false, false))
                .as("a 102nd blocker, the first hundred the same")
                .isNotEqualTo(before);
        assertThat(version(101, 101, true, false))
                .as("a 101st label, the first hundred the same")
                .isNotEqualTo(before);
        assertThat(version(101, 101, false, true))
                .as("a field value past the first page, the first page the same")
                .isNotEqualTo(before);
    }
}
