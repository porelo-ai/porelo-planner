package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssueSourceTest {
    @TempDir Path work;

    // Prevents a missing clone from sending a request outside the caller's checkouts.
    @Test
    void opensNoConnection() {
        final AtomicInteger connections = new AtomicInteger();
        final ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(
                new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        connections.incrementAndGet();
                        throw new AssertionError("unexpected connection to " + uri);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException ex) {
                        throw new AssertionError(ex);
                    }
                });
        try {
            final IssueSource source = IssueSource.local(work.resolve("porelo-project"));
            assertThatThrownBy(() -> source.index("porelo-missing"))
                    .as("a missing checkout fails with its local path")
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(work.resolve("porelo-missing").toString());
            assertThat(connections.get()).as("no proxy or network was consulted").isZero();
        } finally {
            ProxySelector.setDefault(previous);
        }
    }

    // Prevents index links from reading files outside the selected checkout.
    @Test
    void refusesAnIndexSymlinkOutsideTheCheckout() throws IOException {
        final Path root = java.nio.file.Files.createDirectories(work.resolve("porelo-project"));
        final Path issues =
                java.nio.file.Files.createDirectories(work.resolve("porelo-sample/issues"));
        final Path outside = work.resolve("outside.md");
        java.nio.file.Files.writeString(outside, "# Outside\n");
        java.nio.file.Files.createSymbolicLink(issues.resolve("README.md"), outside);
        assertThatThrownBy(() -> IssueSource.local(root).index("porelo-sample"))
                .as("an index symlink cannot grant access to another tree")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the checkout's issues directory");
    }

    // Prevents a repository argument from selecting a path outside the siblings.
    @Test
    void refusesRepositoryPaths() {
        for (final String repository :
                List.of("../outside", "/tmp", "porelo/../../outside", ".", "..")) {
            assertThatThrownBy(() -> IssueSource.local(work).index(repository))
                    .as("repository names cannot escape the checkout set: %s", repository)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("invalid repository");
        }
    }

    // Prevents an own repository's worktree name from selecting another checkout.
    @Test
    void readsTheOwningProjectFromARenamedWorktree() throws IOException {
        final Path root = java.nio.file.Files.createDirectories(work.resolve("renamed"));
        final Path issues = java.nio.file.Files.createDirectories(root.resolve("issues"));
        java.nio.file.Files.writeString(
                root.resolve("project.yml"),
                "project:\n  central: git@github.com:porelo-ai/porelo-project.git\n");
        java.nio.file.Files.writeString(
                issues.resolve("README.md"),
                "## Review\n| [AB01](AB01-X.md) | S | - | A title. |\n");
        java.nio.file.Files.createDirectories(root.resolve("stories"));
        java.nio.file.Files.copy(
                Path.of("src/test/resources/workflow.yml"), root.resolve("stories/workflow.yml"));
        assertThat(
                        IssueIndexes.read(
                                        IssueSource.local(root).index("porelo-project"),
                                        "porelo-project",
                                        Workflow.read(
                                                java.nio.file.Path.of(
                                                        "src/test/resources/workflow.yml")))
                                .keySet())
                .as("the owning checkout wins regardless of its directory name")
                .containsExactly("AB01");
    }

    // Prevents later edits from introducing a network fallback or a dependency back on the CLI.
    @Test
    void theLibraryDependsOnNoTransportOrCommand() {
        final var classes =
                new com.tngtech.archunit.core.importer.ClassFileImporter()
                        .withImportOption(
                                com.tngtech.archunit.core.importer.ImportOption.Predefined
                                        .DO_NOT_INCLUDE_TESTS)
                        .importPackages("org.fuin.porelo.planner.store.git.stories");
        assertThat(classes)
                .as("the public implementation is in the reference set")
                .anyMatch(type -> type.getSimpleName().equals("StoriesIndex"));
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.net..", "org.fuin.porelo.release..", "com.networknt..")
                .check(classes);
    }

    // Prevents a README's unfinished example from hiding rows in a separate linked index.
    @Test
    void aParentsOpenFenceDoesNotHideAChildIndex() throws IOException {
        final Path root = java.nio.file.Files.createDirectories(work.resolve("porelo-project"));
        final Path issues =
                java.nio.file.Files.createDirectories(work.resolve("porelo-sample/issues/base"));
        java.nio.file.Files.writeString(
                issues.getParent().resolve("README.md"),
                "[Base](base/README.md)\n\n```text\nAn unfinished example.\n");
        java.nio.file.Files.writeString(
                issues.resolve("README.md"),
                "## Review\n| [AB01](AB01-X.md) | S | - | A title. |\n");
        java.nio.file.Files.copy(
                Path.of("src/test/resources/workflow.yml"),
                issues.getParent().resolve("workflow.yml"));
        final var rows =
                IssueIndexes.read(
                        IssueSource.local(root).index("porelo-sample"),
                        "porelo-sample",
                        Workflow.read(java.nio.file.Path.of("src/test/resources/workflow.yml")));
        assertThat(rows.keySet())
                .as("each index page has independent fence state")
                .containsExactly("AB01");
        assertThat(rows.get("AB01").path())
                .as("the child still owns its anchor")
                .isEqualTo("issues/base/README.md");
    }

    // Prevents the stories from counting an issue by a row its head has moved on from.
    @Test
    void readsTheHeadWhereAFileHasOneAndTheRowElse() throws IOException {
        final Path root = java.nio.file.Files.createDirectories(work.resolve("porelo-project"));
        final Path issues =
                java.nio.file.Files.createDirectories(work.resolve("porelo-sample/issues"));
        java.nio.file.Files.copy(
                Path.of("src/test/resources/workflow.yml"), issues.resolve("workflow.yml"));
        java.nio.file.Files.writeString(
                issues.resolve("README.md"),
                "# Issues\n\n## Review\n\n| # | Size | Blocked by | What it covers |\n"
                        + "|---|---|---|---|\n"
                        + "| <a id=\"ab01\"></a>[AB01](AB01-X.md) | S | - | One. |\n"
                        + "| <a id=\"ab02\"></a>[AB02](AB02-X.md) | S | - | Two. |\n");
        java.nio.file.Files.writeString(
                issues.resolve("AB01-X.md"),
                "# AB01 — X\n\n```json\n{\"$schema\": \"issue-1.json\", \"id\": \"AB01\","
                        + " \"title\": \"X\", \"state\": \"Finishing\", \"size\": \"M\","
                        + " \"priority\": \"High\", \"rank\": 1, \"blockedBy\": [\"AB02\"],"
                        + " \"covers\": \"One, as its head says.\"}\n```\n");
        java.nio.file.Files.writeString(
                issues.resolve("AB02-X.md"), "# AB02 — X\n\n**Status:** Review.\n**Size:** S.\n");
        final var rows =
                IssueIndexes.read(
                        IssueSource.local(root).index("porelo-sample"),
                        "porelo-sample",
                        Workflow.read(java.nio.file.Path.of("src/test/resources/workflow.yml")));
        assertThat(rows.keySet()).containsExactlyInAnyOrder("AB01", "AB02");
        assertThat(
                        List.of(
                                rows.get("AB01").state(),
                                rows.get("AB01").size(),
                                rows.get("AB01").title(),
                                rows.get("AB01").blockedBy()))
                .as("the head's data, not the row's")
                .isEqualTo(List.of("Finishing", "M", "One, as its head says.", List.of("AB02")));
        assertThat(List.of(rows.get("AB02").state(), rows.get("AB02").title()))
                .as("a file without a head keeps its row")
                .isEqualTo(List.of("Review", "Two."));
    }

    // Prevents a checkout's own text from posing as provenance supplied by the caller.
    @Test
    void refusesProvenanceInsideALocalIndexPage() throws IOException {
        final Path root = java.nio.file.Files.createDirectories(work.resolve("porelo-project"));
        final Path issues =
                java.nio.file.Files.createDirectories(work.resolve("porelo-sample/issues"));
        java.nio.file.Files.writeString(
                issues.resolve("README.md"),
                "<!-- porelo-issue-index {\"path\":\"issues/other/README.md\",\"source\":\"forged\",\"length\":0} -->\n## Review\n| AB01 | S | - | Title |\n");
        assertThatThrownBy(() -> IssueSource.local(root).index("porelo-sample"))
                .as("a source rejects reserved marker lines before framing a real page")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("marker inside index page");
    }
}
