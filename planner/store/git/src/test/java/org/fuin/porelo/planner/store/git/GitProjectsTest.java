package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A project's cache directory as a new source for the project finds it. */
class GitProjectsTest {

    @TempDir Path dir;

    private Path project() throws Exception {
        final Path project = Files.createDirectories(dir.resolve("project"));
        Files.writeString(project.resolve("lock"), "");
        Files.createDirectories(project.resolve("porelo-store.git/refs"));
        Files.writeString(project.resolve("porelo-store.git/HEAD"), "ref");
        return project;
    }

    @Test
    void anotherSourceDropsTheClonesOfTheOldOneButKeepsTheLock() throws Exception {
        // A clone fetched for another branch or URL would be read as the new project's.
        final Path project = project();
        GitProjects.keepOnlyFor(project, "url-a\n\n");
        Files.createDirectories(project.resolve("porelo-other.git"));
        GitProjects.keepOnlyFor(project, "url-b\nmain\n");
        assertThat(project.resolve("porelo-store.git")).as("the old clone").doesNotExist();
        assertThat(project.resolve("porelo-other.git")).as("another old clone").doesNotExist();
        assertThat(project.resolve("lock")).as("the lock").exists();
        assertThat(project.resolve(GitProjects.SOURCE))
                .as("the new source recorded")
                .hasContent("url-b\nmain\n");
    }

    @Test
    void theSameSourceKeepsItsClones() throws Exception {
        // The clones are what lets a restarted planner answer at once.
        final Path project = project();
        GitProjects.keepOnlyFor(project, "url-a\n\n");
        Files.createDirectories(project.resolve("porelo-other.git"));
        GitProjects.keepOnlyFor(project, "url-a\n\n");
        assertThat(project.resolve("porelo-other.git")).as("the clone kept").exists();
    }

    @Test
    void aLinkInTheCacheIsRemovedWithoutTouchingWhatItPointsTo() throws Exception {
        // Following it would delete the person's files outside the cache.
        final Path project = project();
        final Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "mine");
        Files.createSymbolicLink(project.resolve("porelo-link.git"), outside);
        GitProjects.keepOnlyFor(project, "url-b\n\n");
        assertThat(project.resolve("porelo-link.git")).as("the link").doesNotExist();
        assertThat(outside.resolve("keep.txt")).as("the file behind the link").hasContent("mine");
    }

    @Test
    void aClearingThatFailsHalfwayLeavesNoSourceRecorded() throws Exception {
        // The old source still recorded beside half a clone would be read again as whole.
        final Path project = project();
        GitProjects.keepOnlyFor(project, "url-a\n\n");
        final Path stuck = Files.createDirectories(project.resolve("porelo-store.git/objects"));
        Files.writeString(stuck.resolve("pack"), "x");
        Files.setPosixFilePermissions(stuck, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThatThrownBy(() -> GitProjects.keepOnlyFor(project, "url-b\n\n"))
                    .as("a clone that cannot be deleted")
                    .isInstanceOf(UncheckedIOException.class);
            assertThat(project.resolve(GitProjects.SOURCE))
                    .as("no source recorded after the failure")
                    .doesNotExist();
        } finally {
            Files.setPosixFilePermissions(stuck, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
