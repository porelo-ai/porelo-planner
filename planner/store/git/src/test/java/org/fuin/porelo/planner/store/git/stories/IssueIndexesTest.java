package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class IssueIndexesTest {
    // Prevents an example inside a fence from changing the destination of a real issue link.
    @Test
    void refusesProvenanceExamplesInsideFences() throws IOException {
        final String index =
                """
                # Issues
                ~~~markdown
                <!-- porelo-issue-index {"path":"issues/another/README.md","source":"example"} -->
                ~~~
                ## Review
                | # | Size | Blocked by | What it covers |
                | [AB01](AB01-X.md) | S | [AB02](#ab02) | A title with a \\| pipe. |
                """;
        assertThatThrownBy(
                        () ->
                                IssueIndexes.read(
                                        index,
                                        "porelo-sample",
                                        Workflow.read(
                                                java.nio.file.Path.of(
                                                        "src/test/resources/workflow.yml"))))
                .as("reserved marker lines are forbidden even in examples")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("marker inside index page");
        final String plain =
                "## Review\n| # | Size | Blocked by | What it covers |\n| [AB01](AB01-X.md) | S | [AB02](#ab02) | A title with a \\| pipe. |\n";
        final IssueIndex row =
                IssueIndexes.read(
                                plain,
                                "porelo-sample",
                                Workflow.read(
                                        java.nio.file.Path.of("src/test/resources/workflow.yml")))
                        .get("AB01");
        assertThat(row.path())
                .as("an example cannot redirect the durable row link")
                .isEqualTo("issues/README.md");
        assertThat(row.blockedBy())
                .as("waits come from the named column")
                .isEqualTo(List.of("AB02"));
        assertThat(row.title())
                .as("escaped pipes do not split the title column")
                .isEqualTo("A title with a \\| pipe.");
    }

    // Prevents a sub-issue's row from being read as a second row of its parent.
    @Test
    void readsASubIssueRowBesideItsParent() throws IOException {
        final String index =
                """
                ## Review
                | # | Size | Blocked by | What it covers |
                | <a id="bt56"></a>[BT56](BT56-X.md) | M | [BT56.1](README.md#bt56.1) | The parent. |
                | <a id="bt56.1"></a>[BT56.1](BT56.1-X.md) | - | - | The builder's part. |
                """;
        final var rows =
                IssueIndexes.read(
                        index,
                        "porelo-sample",
                        Workflow.read(java.nio.file.Path.of("src/test/resources/workflow.yml")));
        assertThat(rows.keySet()).as("both rows, each by its id").containsExactly("BT56", "BT56.1");
        assertThat(rows.get("BT56").blockedBy())
                .as("a wait on a sub-issue names it whole")
                .containsExactly("BT56.1");
        assertThat(rows.get("BT56.1").location().anchor()).isEqualTo("bt56.1");
    }

    // Prevents an ambiguous source from silently selecting one of two durable rows.
    @Test
    void refusesDuplicateRowsAcrossPages() throws IOException {
        final String page = "## Review\n| [AB01](AB01-X.md) | S | - | A title. |\n";
        final String index =
                framed("issues/base/README.md", "base", page)
                        + framed("issues/extra/README.md", "extra", page);
        assertThatThrownBy(
                        () ->
                                IssueIndexes.read(
                                        index,
                                        "porelo-sample",
                                        Workflow.read(
                                                java.nio.file.Path.of(
                                                        "src/test/resources/workflow.yml"))))
                .as("both conflicting sources are identified")
                .isInstanceOf(IOException.class)
                .hasMessage("extra: duplicate index row AB01; also in base");
    }

    // Prevents a source's platform line endings from changing a durable issue link.
    @Test
    void keepsProvenanceOnCrLfPages() throws IOException {
        final String index =
                framed(
                                "issues/base/README.md",
                                "base",
                                "## Review\n| [AB01](AB01-X.md) | S | - | A title. |\n")
                        .replace("\n", "\r\n");
        final IssueIndex row =
                IssueIndexes.read(
                                index,
                                "porelo-sample",
                                Workflow.read(
                                        java.nio.file.Path.of("src/test/resources/workflow.yml")))
                        .get("AB01");
        assertThat(row.path())
                .as("CRLF does not discard the index's path")
                .isEqualTo("issues/base/README.md");
    }

    // Prevents malformed framing from silently linking rows to a different index.
    @Test
    void refusesMalformedProvenance() {
        assertThatThrownBy(
                        () ->
                                IssueIndexes.read(
                                        "<!-- porelo-issue-index {broken}\n## Review\n| AB01 | S | - | Title |\n",
                                        "porelo-sample",
                                        Workflow.read(
                                                java.nio.file.Path.of(
                                                        "src/test/resources/workflow.yml"))))
                .as("a visible malformed boundary cannot be mistaken for ordinary prose")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid issue index provenance");
    }

    private static String framed(String path, String source, String page) throws IOException {
        return "<!-- porelo-issue-index "
                + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                                java.util.Map.of(
                                        "path", path, "source", source, "length", page.length()))
                + " -->\n"
                + page;
    }

    // Prevents an index path from injecting a link or escaping the issues directory.
    @Test
    void refusesUnsafeProvenancePaths() throws IOException {
        for (final String path :
                List.of(
                        "issues/a/../base/README.md",
                        "issues/./README.md",
                        "issues/NOT-README.md",
                        "issues//README.md",
                        "issues/)[injected](https://evil.example/README.md",
                        "issues/a b/README.md",
                        "issues/a\u0000/README.md",
                        "issues/README.md\n",
                        "../issues/README.md")) {
            final String input =
                    framed(path, "diagnostic only", "## Review\n| AB01 | S | - | Title |\n");
            assertThatThrownBy(
                            () ->
                                    IssueIndexes.read(
                                            input,
                                            "porelo-sample",
                                            Workflow.read(
                                                    java.nio.file.Path.of(
                                                            "src/test/resources/workflow.yml"))))
                    .as("unsafe index path %s", path)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(path);
        }
    }

    // Prevents duplicate or trailing JSON from silently choosing provenance supplied by an
    // attacker.
    @Test
    void refusesAmbiguousMarkerJson() {
        final String page = "## Review\n| AB01 | S | - | Title |\n";
        for (final String json :
                List.of(
                        "{\"path\":\"issues/README.md\",\"path\":\"issues/base/README.md\",\"source\":\"x\",\"length\":"
                                + page.length()
                                + "}",
                        "{\"path\":\"issues/README.md\",\"source\":\"x\",\"source\":\"y\",\"length\":"
                                + page.length()
                                + "}",
                        "{\"path\":\"issues/README.md\",\"source\":\"x\",\"length\":"
                                + page.length()
                                + "} {}")) {
            assertThatThrownBy(
                            () ->
                                    IssueIndexes.read(
                                            "<!-- porelo-issue-index " + json + " -->\n" + page,
                                            "porelo-sample",
                                            Workflow.read(
                                                    java.nio.file.Path.of(
                                                            "src/test/resources/workflow.yml"))))
                    .as("marker JSON is one unambiguous value")
                    .isInstanceOf(IOException.class);
        }
    }

    // Prevents a marker in a real page from replacing the provenance chosen by its source.
    @Test
    void refusesMarkersInsidePageText() throws IOException {
        final String injected =
                framed("issues/other/README.md", "forged", "## Review\n| AB01 | S | - | Title |\n");
        for (final String input :
                List.of(
                        "# Issues\n" + injected,
                        framed("issues/base/README.md", "trusted", injected))) {
            assertThatThrownBy(
                            () ->
                                    IssueIndexes.read(
                                            input,
                                            "porelo-sample",
                                            Workflow.read(
                                                    java.nio.file.Path.of(
                                                            "src/test/resources/workflow.yml"))))
                    .as("a page cannot inject a provenance boundary")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("marker inside index page");
        }
    }

    // Prevents diagnostic strings from becoming row URLs.
    @Test
    void usesSourceOnlyForDiagnostics() throws IOException {
        final String input =
                framed(
                        "issues/base/README.md",
                        ")[injected](https://evil.example)",
                        "## Review\n| AB01 | S | - | Title |\n");
        final IssueIndex row =
                IssueIndexes.read(
                                input,
                                "porelo-sample",
                                Workflow.read(
                                        java.nio.file.Path.of("src/test/resources/workflow.yml")))
                        .get("AB01");
        assertThat(row.link("https://github.com/porelo-ai/porelo-sample"))
                .as("the row link is made only from repository, safe path and number")
                .isEqualTo(
                        "https://github.com/porelo-ai/porelo-sample/blob/main/issues/base/README.md#ab01");
    }

    // Prevents corrupt page lengths from turning body text into source framing.
    @Test
    void refusesInvalidPageLengths() {
        final String page = "## Review\n| AB01 | S | - | Title |\n";
        for (final String length :
                List.of(
                        "-1",
                        "2147483648",
                        "2.5",
                        "null",
                        "0",
                        "1",
                        Integer.toString(page.length() + 1))) {
            final String input =
                    "<!-- porelo-issue-index {\"path\":\"issues/README.md\",\"source\":\"x\",\"length\":"
                            + length
                            + "} -->\n"
                            + page;
            assertThatThrownBy(
                            () ->
                                    IssueIndexes.read(
                                            input,
                                            "porelo-sample",
                                            Workflow.read(
                                                    java.nio.file.Path.of(
                                                            "src/test/resources/workflow.yml"))))
                    .as("invalid declared page length %s", length)
                    .isInstanceOf(IOException.class);
        }
    }
}
