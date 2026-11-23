package org.fuin.porelo.planner.store.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("linux")
@EnabledOnOs(OS.LINUX)
class ExistingDirectoryTest {
    @TempDir Path temp;
    private final PrivateFiles files = PrivateFiles.of(java.util.Map.of());

    // Harm: Opening a chosen directory must not create a missing destination for lost items.
    @Test
    void aMissingDirectoryIsRefusedWithoutMakingIt() {
        final Path missing = temp.resolve("missing");
        assertThatThrownBy(() -> files.requireExistingDirectory(missing))
                .as("an existing directory is required")
                .isInstanceOf(RuntimeException.class);
        assertThat(missing).as("the chosen destination was not made").doesNotExist();
    }

    // Harm: Another account could read the board or substitute files through an open directory.
    @Test
    void onlyAnOwnersPrivateDirectoryPasses() throws Exception {
        final Path own =
                Files.createDirectory(
                        temp.resolve("items"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        files.requireExistingDirectory(own);
        Files.setPosixFilePermissions(own, PosixFilePermissions.fromString("rwxr-x---"));
        assertThatThrownBy(() -> files.requireExistingDirectory(own))
                .as("read access for a group is refused")
                .isInstanceOf(IllegalStateException.class);
    }

    // Harm: A link in any ancestor could redirect writes to another directory.
    @Test
    void aLinkAboveTheDirectoryIsRefused() throws Exception {
        final Path own = Files.createDirectory(temp.resolve("own"));
        Files.setPosixFilePermissions(own, PosixFilePermissions.fromString("rwx------"));
        final Path link = Files.createSymbolicLink(temp.resolve("link"), own);
        assertThatThrownBy(() -> files.requireExistingDirectory(link))
                .as("the destination is never followed through a link")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> files.requireExistingDirectory(Path.of("items")))
                .as("relative destinations depend on the working directory")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aLinkInAnAncestorIsRefused() throws Exception {
        // Harm: a link above the chosen directory sends every save to wherever the link points.
        final Path real = Files.createDirectory(temp.resolve("real"));
        Files.setPosixFilePermissions(real, PosixFilePermissions.fromString("rwxr-xr-x"));
        final Path own =
                Files.createDirectory(
                        real.resolve("items"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        final Path alias = Files.createSymbolicLink(temp.resolve("alias"), real);

        files.requireExistingDirectory(own);
        assertThatThrownBy(() -> files.requireExistingDirectory(alias.resolve("items")))
                .as("the same directory reached through a linked ancestor")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anItemFileAPersonWroteIsReadButNoLinkOrSecondName() throws Exception {
        // Harm: a file the person wrote by hand is refused for its mode, or a placed link reads
        // another of the account's files onto the board.
        final Path own =
                Files.createDirectory(
                        temp.resolve("items"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        final Path written = Files.writeString(own.resolve("engine-1.md"), "by hand");
        Files.setPosixFilePermissions(written, PosixFilePermissions.fromString("rw-r--r--"));
        final Path secret = Files.writeString(temp.resolve("secret"), "a secret");

        files.requireItemFile(written);
        assertThatThrownBy(
                        () ->
                                files.requireItemFile(
                                        Files.createSymbolicLink(
                                                own.resolve("engine-2.md"), secret)))
                .as("a symbolic link")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                files.requireItemFile(
                                        Files.createLink(own.resolve("engine-3.md"), secret)))
                .as("a second name of another file")
                .isInstanceOf(IllegalStateException.class);
    }
}
