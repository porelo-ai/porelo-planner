package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The text of a board's revision, which the window keeps and sends back as a header. */
class RevisionTextTest {

    /** The screen's bound on a revision: printable, no space, at most 200 characters. */
    private static final String SCREENS_BOUND = "[\\x21-\\x7e]{1,200}";

    private static final List<String> TWENTY =
            IntStream.range(0, 20).mapToObj(i -> "repository-" + i).toList();

    private static Map<String, String> commits(final char commit) {
        return TWENTY.stream()
                .collect(
                        Collectors.toMap(
                                Function.identity(), name -> String.valueOf(commit).repeat(40)));
    }

    @Test
    void aProjectWithTwentyRepositoriesHasARevisionTheScreenAccepts() {
        // A revision past the screen's bound ends its watch, and the planning page offers only a
        // retry.
        assertThat(GitStore.revision(TWENTY, commits('a')))
                .as("the revision of twenty repositories")
                .matches(SCREENS_BOUND)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void equalCommitsGiveEqualRevisionsAndAnotherCommitAnother() {
        // A window that took two boards for one would keep showing the older.
        assertThat(GitStore.revision(TWENTY, commits('a')))
                .as("the same commits read twice")
                .isEqualTo(GitStore.revision(TWENTY, commits('a')))
                .as("other commits")
                .isNotEqualTo(GitStore.revision(TWENTY, commits('b')));
    }
}
