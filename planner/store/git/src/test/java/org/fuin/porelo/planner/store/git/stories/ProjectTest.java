package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectTest {
    @TempDir Path work;

    private static final String PROJECT =
            "project:\n  central: git@github.com:porelo-ai/porelo-project.git\nrepositories: {}\n";

    private Path write(byte[]... parts) throws IOException {
        final Path file = work.resolve("project.yml");
        try (var out = Files.newOutputStream(file)) {
            for (final byte[] part : parts) out.write(part);
        }
        return work.resolve("stories/README.md");
    }

    // Prevents a project.yml saved by an editor that writes a byte order mark from being refused.
    @Test
    void aProjectFileOpeningWithAByteOrderMarkIsRead() throws IOException {
        final Path plan =
                write(
                        new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                        PROJECT.getBytes(StandardCharsets.UTF_8));

        assertThat(Project.read(plan, repository -> Map.of()).own())
                .as("the own repository read past the mark")
                .isEqualTo("porelo-project");
    }

    // Prevents a project.yml that is no UTF-8 from escaping the refusal a malformed project gets.
    @Test
    void aProjectFileThatIsNoUtf8IsRefusedAsInvalidYaml() throws IOException {
        final Path plan =
                write(
                        "project:\n  central: \"git@github.com:porelo-ai/porelo-"
                                .getBytes(StandardCharsets.UTF_8),
                        new byte[] {(byte) 0xFF},
                        ".git\"\nrepositories: {}\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> Project.read(plan, repository -> Map.of()))
                .as("the configuration refusal it got before, naming the file")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(work.resolve("project.yml") + ": invalid YAML: ")
                .hasMessageContaining("Invalid UTF-8");
    }

    // Prevents a new issue from taking another prefix than the first its repository names.
    @Test
    void aRepositorysIssuePrefixesKeepTheOrderProjectYmlGivesThem() {
        final Project project =
                Project.read(
                        "project:\n  central: git@github.com:porelo-ai/porelo-project.git\n"
                                + "repositories:\n  other:\n"
                                + "    central: git@github.com:porelo-ai/porelo-other.git\n"
                                + "    issues: [ZZ, AA, MM, BB, QQ, CC, YY]\n",
                        java.util.Set.of());

        assertThat(project.repositories().get("porelo-other"))
                .as("the prefixes in the file's order, the first one first")
                .containsExactly("ZZ", "AA", "MM", "BB", "QQ", "CC", "YY");
    }
}
