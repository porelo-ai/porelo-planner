package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A save's commit subject, held to the shared commit rules. */
class SaveFilesTest {

    private static final List<String> PLANNING =
            List.of("issues/CD01-Other.md", "issues/README.md", "stats/CD01.jsonl");

    @Test
    void aCommitOfPlanningFilesAloneSkipsTheBuild() {
        // A planning commit without the marker starts a full build on rented machines.
        for (final String subject :
                List.of(
                        SaveFiles.subject(Map.of("CD01", "Backlog"), Map.of(), 0, false, PLANNING),
                        SaveFiles.subject(
                                Map.of(),
                                Map.of("ST1", "Idea"),
                                0,
                                false,
                                List.of("stories/README.md", "stories/ST1-Served.md")),
                        SaveFiles.subject(
                                Map.of(), Map.of(), 1, false, List.of("issues/CD01-Other.md")),
                        SaveFiles.subject(
                                Map.of(),
                                Map.of(),
                                0,
                                true,
                                List.of("issues/CD03-New.md", "issues/README.md")))) {
            assertThat(subject).as("the subject %s", subject).endsWith(" [skip ci]");
        }
    }

    @Test
    void everySubjectStaysWithinTheLimitTheMarkerCounted() {
        // A subject over 72 characters fails check-commits on the repository it is pushed to.
        final String longest = "A".repeat(31);
        final Map<String, String> issues = Map.of("CD01", longest, "CD02", longest);
        for (final String subject :
                List.of(
                        SaveFiles.subject(issues, Map.of(), 0, false, PLANNING),
                        SaveFiles.subject(issues, Map.of(), 12, false, PLANNING),
                        SaveFiles.subject(Map.of(), Map.of("ST1", longest), 3, false, PLANNING),
                        // 68 characters unmarked, so only the marker pushes it over.
                        SaveFiles.subject(
                                Map.of(), Map.of("ST1", "A".repeat(25)), 1, false, PLANNING))) {
            assertThat(subject.length())
                    .as("the subject %s, the marker counted", subject)
                    .isLessThanOrEqualTo(72);
            assertThat(subject).as("still marked").endsWith(" [skip ci]");
        }
    }

    @Test
    void aCommitWithAFileOutsideThePlanningSkipsNothing() {
        // A commit that changes what a build reads must build.
        assertThat(
                        SaveFiles.subject(
                                Map.of("CD01", "Backlog"),
                                Map.of(),
                                0,
                                false,
                                List.of("issues/CD01-Other.md", "project.yml")))
                .as("a commit touching project.yml")
                .doesNotContain("[skip ci]");
        assertThat(
                        SaveFiles.subject(
                                Map.of("CD01", "Backlog"),
                                Map.of(),
                                0,
                                false,
                                List.of("issuesx/CD01.md")))
                .as("a directory that only starts like one")
                .doesNotContain("[skip ci]");
        for (final List<String> paths :
                List.of(
                        List.<String>of(),
                        List.of("issues/../project.yml"),
                        List.of("stories/./README.md"),
                        List.of("stats//CD01.jsonl"))) {
            assertThat(SaveFiles.subject(Map.of("CD01", "Backlog"), Map.of(), 0, false, paths))
                    .as("a path that only looks like planning, or none at all: %s", paths)
                    .doesNotContain("[skip ci]");
        }
    }

    @Test
    void aPrioritysSubjectSaysItSetsAPrioritySkipsTheBuildAndStaysWithinTheLimit() {
        // A subject saying nothing of the priority hides what the commit changed, one without the
        // marker starts a build, and one over 72 characters fails check-commits.
        final List<String> stories = List.of("stories/README.md", "stories/ST1-Served.md");
        final String longest = "A".repeat(31);
        for (final String subject :
                List.of(
                        SaveFiles.subject(Map.of(), Map.of(), 0, 1, false, stories),
                        SaveFiles.subject(Map.of(), Map.of(), 0, 12, false, stories),
                        SaveFiles.subject(Map.of(), Map.of("ST1", "Review"), 0, 1, false, stories),
                        SaveFiles.subject(
                                Map.of("CD01", longest, "CD02", longest),
                                Map.of("ST1", longest),
                                12,
                                120,
                                false,
                                PLANNING))) {
            assertThat(subject)
                    .as("the subject %s names a priority", subject)
                    .containsIgnoringCase("priorit");
            assertThat(subject.length())
                    .as("the subject %s, the marker counted", subject)
                    .isLessThanOrEqualTo(72);
            assertThat(subject).as("the subject %s is marked", subject).endsWith(" [skip ci]");
        }
    }
}
