package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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

    @Test
    void aLinkedDirectoryIntoTheCheckoutIsRefusedNotListedAsEmpty() throws IOException {
        // Harm: every story behind a linked directory is left out and nobody is told.
        final Path tree = Files.createDirectories(dir.resolve("porelo-alpha"));
        Files.writeString(
                Files.createDirectories(tree.resolve("real-stories")).resolve("ST1.md"), "# ST1\n");
        Files.createSymbolicLink(tree.resolve("stories"), tree.resolve("real-stories"));
        assertThatThrownBy(() -> checkouts(tree).list("porelo-alpha", "checkout", "stories"))
                .as("the listing of the linked directory")
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void aFileBehindALinkedDirectoryIntoTheCheckoutIsRefused() throws IOException {
        // Harm: a file read through a link that a listing does not follow; what it lists is lost.
        final Path tree = Files.createDirectories(dir.resolve("porelo-alpha"));
        Files.writeString(
                Files.createDirectories(tree.resolve("real-stories")).resolve("workflow.yml"),
                "states: []\n");
        Files.createSymbolicLink(tree.resolve("stories"), tree.resolve("real-stories"));
        assertThatThrownBy(
                        () ->
                                checkouts(tree)
                                        .read("porelo-alpha", "checkout", "stories/workflow.yml"))
                .as("the read behind the link")
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("behind a link");
    }

    @Test
    void aLinkedEntryOfADirectoryIsRefused() throws IOException {
        // Harm: a linked group of issues is listed as neither file nor directory and left out.
        final Path tree = Files.createDirectories(dir.resolve("porelo-alpha"));
        Files.createDirectories(tree.resolve("elsewhere"));
        Files.createSymbolicLink(
                Files.createDirectories(tree.resolve("issues")).resolve("group"),
                tree.resolve("elsewhere"));
        assertThatThrownBy(() -> checkouts(tree).directories("porelo-alpha", "checkout", "issues"))
                .as("the listing holding a link")
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("issues/group is a link");
    }

    @Test
    void theListingsNameTheFilesAndTheDirectoriesApart() throws IOException {
        final Path tree = Files.createDirectories(dir.resolve("porelo-alpha/issues/group"));
        Files.writeString(tree.getParent().resolve("AB01-X.md"), "# AB01\n");
        final Checkouts checkouts = checkouts(tree.getParent().getParent());
        assertThat(checkouts.list("porelo-alpha", "checkout", "issues"))
                .as("the files")
                .containsExactly("issues/AB01-X.md");
        assertThat(checkouts.directories("porelo-alpha", "checkout", "issues"))
                .as("the directories")
                .containsExactly("issues/group");
        assertThat(checkouts.list("porelo-alpha", "checkout", "stats"))
                .as("a directory that is not there")
                .isEmpty();
    }

    @Test
    void theSearchFindsATrackedFileOfTheCheckout() throws Exception {
        final Path tree = Files.createDirectories(dir.resolve("porelo-alpha"));
        Files.writeString(tree.resolve("notes.md"), "Built on AB01.\n");
        git(tree, "init", "-q");
        git(tree, "add", "notes.md");
        assertThat(checkouts(tree).grep("porelo-alpha", "checkout", List.of("AB01")))
                .as("what the search found")
                .containsExactly(new Remote.Found("notes.md", 1, "Built on AB01."));
    }

    @Test
    void theSearchNeverReachesARepositoryAboveTheCheckout() throws Exception {
        // Harm: a checkout without its own repository is searched as another repository holds it.
        final Path tree = Files.createDirectories(dir.resolve("porelo-alpha"));
        Files.writeString(tree.resolve("notes.md"), "Built on AB01.\n");
        git(dir, "init", "-q");
        git(dir, "add", "porelo-alpha/notes.md");
        assertThatThrownBy(() -> checkouts(tree).grep("porelo-alpha", "checkout", List.of("AB01")))
                .as("the search of a checkout that is no repository")
                .isInstanceOf(GitFailure.class);
    }

    /** Runs Git in a directory, without the person's configuration. */
    private static void git(final Path where, final String... arguments) throws Exception {
        final List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(where.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as("git %s: %s", command, out).isZero();
    }
}
