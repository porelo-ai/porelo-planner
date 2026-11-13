package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.planner.app.WorkFixture.planner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.porelo.planner.app.WorkFixture.Answer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * This repository's own {@code issues/} directory and index, checked by the planner's issue check
 * when the {@code documents} profile runs.
 */
@Tag("documents")
class RepositoryIssuesTest {

    @TempDir Path dir;

    // Prevents an issue head or index in this repository that disagrees with the issue rules from
    // passing the documents check.
    @Test
    void theRepositorysIssuesAndIndexPassThePlannersCheck() {
        final Path issues = issues();

        final Answer answer = planner("issues", "check", issues.toString());

        assertThat(answer.code())
                .as("issues check %s:\n%s%s", issues, answer.err(), answer.out())
                .isZero();
        assertThat(answer.err()).as("faults reported on standard error").isEmpty();
    }

    // Prevents the documents check from passing when an issue's row is missing from the index.
    @Test
    void thePlannersCheckRefusesTheRepositorysIssuesWhenAnIndexRowIsMissing() throws IOException {
        final Path source = issues();
        final Path copy = Files.createDirectories(dir.resolve("issues"));
        try (Stream<Path> files = Files.walk(source)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final Path target = copy.resolve(source.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        final Path index = copy.resolve("README.md");
        final List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        final String removed =
                lines.stream()
                        .filter(line -> line.startsWith("| <a id=\"pl"))
                        .findFirst()
                        .orElseThrow();
        final List<String> broken = lines.stream().filter(line -> !line.equals(removed)).toList();
        assertThat(broken).as("the index with one issue row removed").hasSize(lines.size() - 1);
        Files.writeString(index, String.join("\n", broken) + "\n", StandardCharsets.UTF_8);

        final Answer answer = planner("issues", "check", copy.toString());

        assertThat(answer.code()).as("a refusal, %s", answer.err()).isEqualTo(WorkCommands.REFUSED);
        assertThat(answer.err())
                .as("the fault names the copy's index")
                .contains(index + ":")
                .contains("never edited by hand");
        assertThat(answer.out()).as("no OK on a refusal").isEmpty();
    }

    private static Path issues() {
        final Path module = Path.of("").toAbsolutePath().normalize();
        final Path parent = module.getParent();
        assertThat(parent).as("the parent directory of %s", module).isNotNull();
        final Path root = parent.getParent();
        assertThat(root).as("the repository root above %s", module).isNotNull();
        final Path issues = root.resolve("issues");
        assertThat(issues.resolve("README.md"))
                .as("this repository's issues index in %s", issues)
                .isRegularFile();
        return issues;
    }
}
