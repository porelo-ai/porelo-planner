package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards the issue metadata contract using fingerprints produced before workflow extraction. */
class IssueIndexFixturesTest {
    @Test
    void everyFixtureKeepsItsRowsAndMetadata() throws Exception {
        final Path root = Path.of("src/test/resources/checkouts");
        final ObjectMapper json = new ObjectMapper();
        final var expected =
                json.readTree(
                        Files.readString(
                                Path.of("src/test/resources/issue-index-fingerprints.json")));
        final List<Path> files;
        try (var paths = Files.walk(root)) {
            files =
                    paths.filter(
                                    path ->
                                            path.toString().endsWith("/README.md")
                                                    && root.relativize(path)
                                                            .toString()
                                                            .contains("/issues/"))
                            .sorted()
                            .toList();
        }
        assertThat(files).as("the independently recorded reference set").hasSize(20);
        assertThat(files.stream().map(path -> root.relativize(path).toString()).toList())
                .containsExactlyElementsOf(() -> expected.fieldNames());
        int rowsCount = 0;
        for (final Path file : files) {
            final String key = root.relativize(file).toString();
            final String path = key.substring(key.indexOf("issues/"));
            final String page = Files.readString(file);
            final String metadata =
                    json.writeValueAsString(
                            Map.of("path", path, "source", key, "length", page.length()));
            final Workflow workflow =
                    Workflow.read(
                            root.resolve(
                                    key.substring(0, key.indexOf("issues/"))
                                            + "issues/workflow.yml"));
            final var rows =
                    IssueIndexes.read(
                            "<!-- porelo-issue-index " + metadata + " -->\n" + page,
                            key.split("/")[0],
                            workflow);
            rowsCount += rows.size();
            assertThat(rows)
                    .as("row count of %s", key)
                    .hasSize(expected.get(key).get("rows").asInt());
            final String digest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(json.writeValueAsBytes(rows)));
            assertThat(digest)
                    .as("every field of every original row in %s", key)
                    .isEqualTo(expected.get(key).get("sha256").asText());
        }
        assertThat(rowsCount).isEqualTo(1474);
    }
}
