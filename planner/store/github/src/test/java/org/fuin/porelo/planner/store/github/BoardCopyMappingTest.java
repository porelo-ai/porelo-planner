package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.junit.jupiter.api.Test;

class BoardCopyMappingTest {

    @Test
    void anItemReadFromTheCopyKeepsGitHubsCountsOfItsLabelsAndValues() {
        // Harm: an item read from the copy counts fewer labels or values than GitHub's read of it
        // by its id, so the version a write is checked against differs from the one the board
        // showed, and every write to an item with labels or values past the page is refused.
        final GitHubGraphQl.IssueRefs none =
                new GitHubGraphQl.IssueRefs(List.of(), new GitHubGraphQl.PageInfo(false, null), 0);
        final GitHubGraphQl.ProjectItem read =
                new GitHubGraphQl.ProjectItem(
                        "PVTI_1",
                        false,
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
                                List.of("bug"),
                                null,
                                none,
                                none,
                                new GitHubGraphQl.PageInfo(true, "next"),
                                null,
                                null,
                                List.of(),
                                150),
                        List.of(),
                        new GitHubGraphQl.PageInfo(true, "next"),
                        30);

        final GitHubGraphQl.ProjectItem copied =
                BoardCopyMapping.item(BoardCopyMapping.tracked(read, null));

        assertThat(copied.valueCount()).as("the values GitHub counts").isEqualTo(30);
        assertThat(Objects.requireNonNull(copied.issue()).labelCount())
                .as("the labels GitHub counts")
                .isEqualTo(150);
    }
}
