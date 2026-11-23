package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.cache.BoardCopies;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.TrackerBoard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

/** The sources the planner refuses before it touches Git or the cache. */
class StoreConfigurationTest {

    private final Projects projects =
            StoreConfiguration.fromGit(StoreConfiguration.gitProjects(Map.of()));

    @Test
    void theNativeImageKeepsEveryRecordACopyIsMadeOf() throws NoSuchMethodException {
        // Harm: a copy the native executable cannot read or write, found only on a person's
        // computer, since Fory makes a record the image does not reflect on in the JVM alone.
        final RegisterReflectionForBinding hints =
                StoreConfiguration.class
                        .getDeclaredMethod("gitProjects", java.time.Duration.class)
                        .getAnnotation(RegisterReflectionForBinding.class);

        assertThat(hints).as("the native hints").isNotNull();
        assertThat(Stream.concat(Stream.of(hints.value()), Stream.of(hints.classes())).toList())
                .as("the records the native image reflects on")
                .containsAll(BoardCopies.records());
    }

    @Test
    void aSourceThatIsNoCloneUrlIsRefusedByName() {
        // Git would read a local path or an option where the planner expects a remote.
        assertThatThrownBy(() -> projects.put("alpha", "/home/person/git/p", null))
                .as("a local path")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal -> {
                            assertThat(refusal.name()).as("its name").isEqualTo("NotACloneUrl");
                            assertThat(refusal.parameters())
                                    .as("what it names")
                                    .containsEntry("project", "alpha");
                        });
        assertThatThrownBy(() -> projects.put("alpha", "https://github.com/porelo-ai/My.git", null))
                .as("a URL whose last part is no repository name")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal ->
                                assertThat(refusal.name())
                                        .as("its name")
                                        .isEqualTo("NotACloneUrl"));
    }

    @Test
    void aBranchGitWouldReadAsSomethingElseIsRefusedByName() {
        // A branch like '--upload-pack=x' or '../x' would reach git's command line.
        assertThatThrownBy(
                        () ->
                                projects.put(
                                        "alpha",
                                        "https://github.com/porelo-ai/porelo-project.git",
                                        "--upload-pack=x"))
                .as("a branch that is an option")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal -> {
                            assertThat(refusal.name()).as("its name").isEqualTo("InvalidName");
                            assertThat(refusal.parameters())
                                    .as("what it names")
                                    .containsEntry("project", "alpha");
                        });
    }

    @Test
    void theBoardsCopiesLieInTheCachesOwnDirectoryClosedToOthers(@TempDir final Path home)
            throws IOException {
        // Harm: a board copy another account can read, or one written where it is never found.
        final Path cache = Files.createDirectories(home.resolve("cache"));
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------"));
        final Map<String, String> environment =
                Map.of("HOME", home.toString(), "XDG_CACHE_HOME", cache.toString());
        final BoardCopy.Project project = new BoardCopy.Project("github.com", "example-org", 7);
        final BoardCopy copy =
                new BoardCopy(
                        project,
                        "c0ffee0",
                        List.of("example-project"),
                        Instant.parse("2026-11-21T10:00:00Z"),
                        new TrackerBoard(
                                "PVT_7", Map.of(), Map.of(), Map.of(), List.of(), Map.of()),
                        List.of(),
                        ChangeCursor.NONE,
                        List.of());

        StoreConfiguration.boardCopies(environment).write(copy);

        final Path directory = cache.resolve("porelo-planner").resolve(StoreConfiguration.BOARDS);
        assertThat(Files.getPosixFilePermissions(directory))
                .as("the copies' directory")
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE);
        try (Stream<Path> files = Files.list(directory)) {
            final List<Path> written = files.toList();
            assertThat(written).as("the copies written").hasSize(1);
            assertThat(Files.getPosixFilePermissions(written.get(0)))
                    .as("the copy's file")
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
        assertThat(StoreConfiguration.boardCopies(environment).read(project, "c0ffee0"))
                .as("the copy read back")
                .contains(copy);
    }
}
