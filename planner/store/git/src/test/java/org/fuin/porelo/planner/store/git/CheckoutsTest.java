package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A project's checkouts read from disk, never beyond them. */
@Tag("linux")
class CheckoutsTest {

    @TempDir Path dir;

    private Checkouts checkouts(final Path tree) {
        return new Checkouts(
                Map.of("porelo-alpha", tree),
                Map.of(),
                new GitRunner(Duration.ofSeconds(10), 1 << 20),
                1 << 20);
    }

    @Test
    void aFileOfTheCheckoutIsReadAsItStands() throws IOException {
        final Path tree = Files.createDirectories(dir.resolve("porelo-alpha/issues"));
        Files.writeString(tree.resolve("README.md"), "# Issues\n");
        assertThat(checkouts(tree.getParent()).read("porelo-alpha", "checkout", "issues/README.md"))
                .as("the file read")
                .contains("# Issues\n");
    }

    @Test
    void aFileReachedThroughALinkedDirectoryOutsideTheCheckoutIsRefused() throws IOException {
        // Harm: a link in a checkout reads a file the person never meant for the project.
        final Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("README.md"), "# Elsewhere\n");
        final Path tree = Files.createDirectories(dir.resolve("porelo-alpha"));
        Files.createSymbolicLink(tree.resolve("issues"), outside);
        assertThatThrownBy(
                        () -> checkouts(tree).read("porelo-alpha", "checkout", "issues/README.md"))
                .as("the read through the link")
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("outside the checkout");
    }

    @Test
    void aLinkedFileIsRefused() throws IOException {
        // Harm: a linked file reads whatever it points at, inside the checkout or not.
        final Path outside = Files.writeString(dir.resolve("secret.md"), "# Elsewhere\n");
        final Path tree = Files.createDirectories(dir.resolve("porelo-alpha/issues"));
        Files.createSymbolicLink(tree.resolve("README.md"), outside);
        assertThatThrownBy(
                        () ->
                                checkouts(tree.getParent())
                                        .read("porelo-alpha", "checkout", "issues/README.md"))
                .as("the read of the link")
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("not a plain file");
    }
}
