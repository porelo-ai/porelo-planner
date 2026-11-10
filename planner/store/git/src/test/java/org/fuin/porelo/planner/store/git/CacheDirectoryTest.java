package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheDirectoryTest {

    @TempDir Path dir;

    private Path cache() throws Exception {
        final Path cache = dir.resolve("cache");
        Files.createDirectory(
                cache,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        return cache;
    }

    @Test
    void aProjectsClonesLieInADirectoryOfThePersonsAlone() throws Exception {
        // Another account reading the clones reads the person's private repositories.
        final Path cache = cache();
        final Path project =
                CacheDirectory.of(Map.of("XDG_CACHE_HOME", cache.toString()), "porelo");
        assertThat(project)
                .as("the project's directory")
                .isEqualTo(cache.resolve("porelo-planner/porelo"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(project)))
                .as("only the person reads it")
                .isEqualTo("rwx------");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(project.getParent())))
                .as("nor the planner's directory")
                .isEqualTo("rwx------");
    }

    @Test
    void aDirectoryOthersCanReadIsRefused() throws Exception {
        final Path cache = cache();
        Files.createDirectory(cache.resolve("porelo-planner"));
        Files.setPosixFilePermissions(
                cache.resolve("porelo-planner"), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(
                        () ->
                                CacheDirectory.of(
                                        Map.of("XDG_CACHE_HOME", cache.toString()), "porelo"))
                .as("a planner directory others can read")
                .hasMessageContaining("not rwx------");
    }

    @Test
    void aLinkOnTheWayIsRefused() throws Exception {
        // A link lets whoever placed it point the clones somewhere of theirs.
        final Path cache = cache();
        final Path elsewhere =
                Files.createDirectory(
                        dir.resolve("elsewhere"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        Files.createSymbolicLink(cache.resolve("porelo-planner"), elsewhere);
        assertThatThrownBy(
                        () ->
                                CacheDirectory.of(
                                        Map.of("XDG_CACHE_HOME", cache.toString()), "porelo"))
                .as("the planner's directory as a link")
                .hasMessageContaining("is not a directory, or is a link");
        final Path linked = dir.resolve("linked");
        Files.createSymbolicLink(linked, elsewhere);
        assertThatThrownBy(
                        () ->
                                CacheDirectory.of(
                                        Map.of("XDG_CACHE_HOME", linked.toString()), "porelo"))
                .as("the cache itself reached through a link")
                .hasMessageContaining("is reached through a link");
    }

    @Test
    void aCacheBelowADirectoryOthersCanWriteIsRefused() throws Exception {
        final Path cache = cache();
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThatThrownBy(
                        () ->
                                CacheDirectory.of(
                                        Map.of("XDG_CACHE_HOME", cache.toString()), "porelo"))
                .as("a cache others could rename away")
                .hasMessageContaining("others can write");
    }

    @Test
    void aProjectNameThatLeavesTheCacheIsRefused() throws Exception {
        final Path cache = cache();
        assertThatThrownBy(
                        () -> CacheDirectory.of(Map.of("XDG_CACHE_HOME", cache.toString()), "../x"))
                .as("a project name with a path in it")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
