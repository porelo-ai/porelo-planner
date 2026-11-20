package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Build's check whether a job fetched something its restored Maven cache lacked. */
class MavenCacheSaveTest {

    private static final Path FETCHED =
            Path.of("../../.github/actions/maven-cache-save/fetched.sh");

    /** A date Maven gives a fetched file from the server's Last-Modified, long before any run. */
    private static final FileTime SERVERS_DATE =
            FileTime.from(Instant.parse("2025-02-25T00:00:00Z"));

    @TempDir Path dir;

    // Prevents a job that fetched what the cache lacked from saving nothing, so every Build asks
    // Maven Central again.
    @Test
    void anArtifactFetchedAfterTheRestoreIsSeenThoughItsDateIsTheServers() throws Exception {
        final Path repository = restored();
        final Path marker = marker();
        final Path jar = artifact(repository, "org/example/lib/1.0/lib-1.0.jar");

        assertThat(fetched(repository, marker))
                .as("a jar Maven wrote after the restore, dated by the server")
                .isEqualTo("fetched org/example/lib/1.0/lib-1.0.jar");
        assertThat(Files.getLastModifiedTime(jar)).as("the jar's own date").isEqualTo(SERVERS_DATE);
    }

    // Prevents a job that fetched nothing from saving another full copy that crowds the other
    // caches out.
    @Test
    void aRepositoryOnlyRestoredHasNothingFetched() throws Exception {
        final Path repository = restored();

        assertThat(fetched(repository, marker()))
                .as("only what the restore wrote")
                .isEqualTo("nothing");
    }

    // Prevents a save when the restore never ran in the job, such as after a failed checkout.
    @Test
    void withoutTheMarkerTheCacheCountsAsNotRestored() throws Exception {
        final Path repository = restored();

        assertThat(fetched(repository, dir.resolve("no-marker")))
                .as("no marker touched after a restore")
                .isEqualTo("unrestored");
    }

    private Path restored() throws IOException, InterruptedException {
        final Path repository = dir.resolve("repository");
        artifact(repository, "org/example/old/1.0/old-1.0.pom");
        // A file system's clock may count in coarse steps; the marker must lie after the restore.
        Thread.sleep(50);
        return repository;
    }

    private Path marker() throws IOException, InterruptedException {
        final Path marker = dir.resolve("maven-cache-restored");
        Files.writeString(marker, "");
        Thread.sleep(50);
        return marker;
    }

    private static Path artifact(final Path repository, final String path) throws IOException {
        final Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, SERVERS_DATE);
        return file;
    }

    private static String fetched(final Path repository, final Path marker) throws Exception {
        final Process process =
                new ProcessBuilder(
                                "bash",
                                FETCHED.toString(),
                                repository.toString(),
                                marker.toString())
                        .redirectErrorStream(true)
                        .start();
        process.getOutputStream().close();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the check ended").isTrue();
        final String said =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.exitValue()).as("the check's exit code, %s", said).isZero();
        return said;
    }
}
