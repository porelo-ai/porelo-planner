package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Version;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** The versions a write's answer names, read from the board its save left. */
class WrittenVersionsTest {

    private static Story story(final String id, final List<String> issues, final String tag) {
        return new Story(id, "A story", "Idea", "High", null, issues, "", null)
                .withVersion(Version.of("story", id, tag));
    }

    private static Issue issue(final String id, final String tag) {
        return new Issue(id, "sample", "An issue", "Idea", null, List.of(), null, null, "")
                .withVersion(Version.of("issue", id, tag));
    }

    private static Board board(final List<Story> stories, final List<Issue> issues) {
        return new Board(
                new Revision("r"),
                new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                stories,
                issues,
                new Work(Map.of(), List.of()),
                ProjectRepository.named(List.of("sample")),
                List.of());
    }

    @Test
    void namesTheWrittenIssueAndTheStoryThatHoldsIt() {
        final Board before =
                board(
                        List.of(story("ST1", List.of("AB1"), "1"), story("ST2", List.of(), "1")),
                        List.of(issue("AB1", "1"), issue("AB2", "1")));
        final Board after =
                board(
                        List.of(story("ST1", List.of("AB1"), "2"), story("ST2", List.of(), "1")),
                        List.of(issue("AB1", "2"), issue("AB2", "1")));

        final Map<String, String> versions =
                WrittenVersions.of(before, after, Set.of("AB1"), Basis.of(before));

        assertThat(versions)
                .containsOnlyKeys("AB1", "ST1")
                .containsEntry("AB1", Version.of("issue", "AB1", "2").value())
                .containsEntry("ST1", Version.of("story", "ST1", "2").value());
    }

    @Test
    void namesTheStoryAnIssueLeftAndTheOneItJoined() {
        final Board before =
                board(
                        List.of(story("ST1", List.of("AB1"), "1"), story("ST2", List.of(), "1")),
                        List.of(issue("AB1", "1")));
        final Board after =
                board(
                        List.of(story("ST1", List.of(), "2"), story("ST2", List.of("AB1"), "2")),
                        List.of(issue("AB1", "1")));

        assertThat(WrittenVersions.of(before, after, Set.of("ST2", "AB1"), Basis.of(before)))
                .containsOnlyKeys("AB1", "ST1", "ST2")
                .containsEntry("ST1", Version.of("story", "ST1", "2").value());
    }

    @Test
    void namesAnItemTheWriteMadeAndLeavesOutOneTheBoardNoLongerHolds() {
        final Board before = board(List.of(), List.of(issue("AB1", "1")));
        final Board after =
                board(List.of(story("ST9", List.of("AB1"), "1")), List.of(issue("AB1", "1")));

        assertThat(WrittenVersions.of(before, after, Set.of("ST9", "AB1", "AB7"), Basis.of(before)))
                .containsOnlyKeys("AB1", "ST9");
    }

    @Test
    void leavesOutAStoryThatHoldsNoWrittenIssue() {
        final Board board =
                board(
                        List.of(
                                story("ST1", List.of("AB1"), "1"),
                                story("ST2", List.of("AB2"), "1")),
                        List.of(issue("AB1", "1"), issue("AB2", "1")));

        assertThat(WrittenVersions.of(board, board, Set.of("AB2"), Basis.of(board)))
                .containsOnlyKeys("AB2", "ST2");
    }

    @Test
    void leavesOutAHoldingStoryTheCallerNeverSaw() {
        final Board before =
                board(List.of(story("ST1", List.of("AB1"), "1")), List.of(issue("AB1", "1")));
        final Board after =
                board(List.of(story("ST1", List.of("AB1"), "2")), List.of(issue("AB1", "2")));

        assertThat(WrittenVersions.of(before, after, Set.of("AB1"), Basis.none()))
                .as("the story is not in the versions seen")
                .containsOnlyKeys("AB1");
    }

    @Test
    void leavesOutAHoldingStoryTheCallerSawAtAnotherVersion() {
        final Board before =
                board(List.of(story("ST1", List.of("AB1"), "2")), List.of(issue("AB1", "1")));
        final Board after =
                board(List.of(story("ST1", List.of("AB1"), "3")), List.of(issue("AB1", "2")));
        final Basis older = Basis.none().with("ST1", Version.of("story", "ST1", "1"));

        assertThat(WrittenVersions.of(before, after, Set.of("AB1"), older))
                .as("another writer changed the story after the caller saw it")
                .containsOnlyKeys("AB1");
    }

    @Test
    void namesAHoldingStoryTheCallerSawAsItStoodOnTheBoardJudged() {
        final Board before =
                board(List.of(story("ST1", List.of("AB1"), "2")), List.of(issue("AB1", "1")));
        final Board after =
                board(List.of(story("ST1", List.of("AB1"), "3")), List.of(issue("AB1", "2")));
        final Basis seen = Basis.none().with("ST1", Version.of("story", "ST1", "2"));

        assertThat(WrittenVersions.of(before, after, Set.of("AB1"), seen))
                .containsOnlyKeys("AB1", "ST1")
                .containsEntry("ST1", Version.of("story", "ST1", "3").value());
    }

    @Test
    void namesAWrittenStoryWhateverTheCallerSaw() {
        final Board before =
                board(List.of(story("ST1", List.of("AB1"), "1")), List.of(issue("AB1", "1")));
        final Board after =
                board(List.of(story("ST1", List.of("AB1"), "2")), List.of(issue("AB1", "1")));

        assertThat(WrittenVersions.of(before, after, Set.of("ST1"), Basis.none()))
                .containsOnlyKeys("ST1");
    }
}
