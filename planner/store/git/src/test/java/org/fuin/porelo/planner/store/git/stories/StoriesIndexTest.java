package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StoriesIndexTest {
    @TempDir Path work;
    private static final IssueSource SOURCE = repository -> "# Issues\n";

    private Path fixture(String name) throws IOException {
        final Path root = Files.createDirectories(work.resolve(name));
        final Path dir = Files.createDirectory(root.resolve("stories"));
        Files.writeString(
                root.resolve("project.yml"),
                "project:\n  central: git@github.com:porelo-ai/porelo-project.git\nrepositories: {}\n");
        Files.writeString(
                dir.resolve("ST1.md"),
                """
                # ST1 — A title

                ## Data

                ```json
                {"id":"ST1","title":"A title","state":"Idea","priority":"High","gain":"A gain.","test":"A test.","issues":[],"tested":null}
                ```
                """);
        Files.copy(Path.of("src/test/resources/workflow.yml"), dir.resolve("workflow.yml"));
        return dir;
    }

    // Prevents schema migration from obscuring story changes by rewriting unrelated JSON bytes.
    @Test
    void fixtureMigrationOnlyPrependsSchemaAndRemovesLinkedStates() throws Exception {
        final Path fixtures = Path.of("src/test/resources/checkouts");
        final Path copied = work.resolve("checkouts");
        try (var files = Files.walk(fixtures)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final Path target = copied.resolve(fixtures.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        final Path project = copied.resolve("porelo-project");
        final Path stories = project.resolve("stories");
        final var json = new com.fasterxml.jackson.databind.ObjectMapper();
        final java.util.Map<Path, java.util.List<String>> expected =
                new java.util.LinkedHashMap<>();
        int empty = 0;
        int one = 0;
        int many = 0;
        try (var files = Files.list(stories)) {
            for (final Path file :
                    files.filter(
                                    path ->
                                            path.getFileName().toString().startsWith("ST")
                                                    && path.toString().endsWith(".md"))
                            .sorted()
                            .toList()) {
                final java.util.List<String> lines = jsonLines(file);
                final var data = json.readTree(String.join("\n", lines));
                final java.util.List<String> migrated = new java.util.ArrayList<>(lines);
                if (!data.get("issues").isEmpty()) {
                    assertThat(migrated.removeIf(line -> line.startsWith("  \"state\": ")))
                            .as("one stated-state line in %s", file)
                            .isTrue();
                }
                migrated.add(1, "  \"$schema\": \"story-2.json\",");
                expected.put(file, migrated);
                if (data.get("issues").isEmpty()) empty++;
                else if (data.get("issues").size() == 1) one++;
                else many++;
            }
        }
        assertThat(expected).hasSize(294);
        assertThat(java.util.List.of(empty, one, many))
                .as("independently counted empty, single-item, and longer arrays")
                .containsExactly(44, 96, 154);
        final var first = StoriesIndex.write(stories, IssueSource.local(project));
        assertThat(first.faults()).isEmpty();
        for (final var entry : expected.entrySet()) {
            assertThat(jsonLines(entry.getKey()))
                    .as(
                            "only the schema line is added and the linked state line removed: %s",
                            entry.getKey())
                    .containsExactlyElementsOf(entry.getValue());
        }
        final java.util.Map<Path, byte[]> once = new java.util.LinkedHashMap<>();
        for (final Path file : expected.keySet()) once.put(file, Files.readAllBytes(file));
        final var second = StoriesIndex.write(stories, IssueSource.local(project));
        assertThat(second.faults()).isEmpty();
        assertThat(second.written())
                .as("already-migrated stories and index are untouched")
                .isEmpty();
        for (final var entry : once.entrySet())
            assertThat(Files.readAllBytes(entry.getKey()))
                    .as("story-2 bytes remain identical: %s", entry.getKey())
                    .isEqualTo(entry.getValue());
    }

    private static java.util.List<String> jsonLines(Path file) throws IOException {
        final String text = Files.readString(file);
        final int start = text.lastIndexOf("```json\n") + "```json\n".length();
        return java.util.List.of(text.substring(start, text.lastIndexOf("```")).split("\n", -1));
    }

    // Prevents a writer from truncating content held by an existing reader or hard link.
    @Test
    void replacesChangedFilesWithoutChangingTheirPreviousInodes() throws IOException {
        final Path dir = fixture("atomic");
        assertThat(StoriesIndex.write(dir, SOURCE).faults()).as("fixture is valid").isEmpty();
        final Path story = dir.resolve("ST1.md");
        Files.writeString(
                story,
                Files.readString(story)
                        .replace("\"gain\":\"A gain.\"", "\"gain\":\"A changed gain.\""));
        final Path oldStory = Files.createLink(work.resolve("old-story"), story);
        final Path index = dir.resolve("README.md");
        final Path oldIndex = Files.createLink(work.resolve("old-index"), index);
        final byte[] storyBefore = Files.readAllBytes(story);
        final byte[] indexBefore = Files.readAllBytes(index);
        final var result = StoriesIndex.write(dir, SOURCE);
        assertThat(result.written())
                .as("only both changed files are published")
                .containsExactly(story, index);
        assertThat(Files.readAllBytes(oldStory))
                .as("the previous story inode is intact")
                .isEqualTo(storyBefore);
        assertThat(Files.readAllBytes(oldIndex))
                .as("the previous index inode is intact")
                .isEqualTo(indexBefore);
        assertThat(Files.isSameFile(oldStory, story))
                .as("the story path now names a replacement inode")
                .isFalse();
        assertThat(Files.isSameFile(oldIndex, index))
                .as("the index path now names a replacement inode")
                .isFalse();
        assertThat(StoriesIndex.check(dir, SOURCE).faults())
                .as("both published files are complete")
                .isEmpty();
        try (var files = Files.list(dir)) {
            assertThat(files.map(file -> file.getFileName().toString()).sorted().toList())
                    .as("no temporary files remain")
                    .containsExactly("README.md", "ST1.md", "workflow.yml");
        }
    }

    // Prevents a no-op regeneration from touching outputs or falsely reporting changed files.
    @Test
    void leavesUnchangedFilesAndTheirTimestampsAlone() throws IOException {
        final Path dir = fixture("unchanged");
        StoriesIndex.write(dir, SOURCE);
        final FileTime sentinel = FileTime.fromMillis(1234000L);
        for (final String name : new String[] {"README.md", "ST1.md"})
            Files.setLastModifiedTime(dir.resolve(name), sentinel);
        assertThat(StoriesIndex.write(dir, SOURCE).written())
                .as("an unchanged tree writes nothing")
                .isEmpty();
        for (final String name : new String[] {"README.md", "ST1.md"})
            assertThat(Files.getLastModifiedTime(dir.resolve(name)))
                    .as("unchanged timestamp for %s", name)
                    .isEqualTo(sentinel);
    }

    // Prevents generated files from escaping the requested directory through any symlink.
    @ParameterizedTest
    @CsvSource({
        "write,story",
        "check,story",
        "write,index",
        "check,index",
        "write,directory",
        "check,directory"
    })
    void refusesSymlinksBeforeChangingAnyFile(String operation, String kind) throws IOException {
        Path dir = fixture(operation + kind);
        final Path story = dir.resolve("ST1.md");
        final byte[] before = Files.readAllBytes(story);
        final Path external = work.resolve(operation + kind + "-outside");
        Files.write(external, before);
        final Path link;
        if (kind.equals("directory")) {
            link = dir.resolveSibling("alias");
            Files.createSymbolicLink(link, dir);
            dir = link;
        } else {
            link = dir.resolve(kind.equals("story") ? "ST1.md" : "README.md");
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, external);
        }
        final Path checked = dir;
        assertThatThrownBy(
                        () -> {
                            if (operation.equals("write")) StoriesIndex.write(checked, SOURCE);
                            else StoriesIndex.check(checked, SOURCE);
                        })
                .as("%s refuses a symlinked %s", operation, kind)
                .isInstanceOf(IOException.class)
                .hasMessageContaining(link.toString());
        assertThat(Files.readAllBytes(external))
                .as("the external target was not changed")
                .isEqualTo(before);
        assertThat(Files.readAllBytes(story))
                .as("no story changed before refusal")
                .isEqualTo(before);
    }

    // Prevents a story whose heading names another title from reaching the index unnoticed.
    @Test
    void aHeadingTheHeadDoesNotGiveIsAFaultBeforeTheFileName() throws IOException {
        final Path dir = fixture("heading");
        Files.delete(dir.resolve("ST1.md"));
        Files.writeString(
                dir.resolve("ST9.md"),
                """
                # ST1 — Another title

                ## Data

                ```json
                {"id":"ST1","title":"A title","state":"Idea","priority":"High","gain":"A gain.","test":"A test.","issues":[],"tested":null}
                ```
                """);

        assertThat(StoriesIndex.check(dir, SOURCE).faults())
                .as("the heading's fault, then the file name's, both naming the file")
                .containsExactly(
                        "ST9.md: the heading is not '# ST1 — A title'",
                        "ST9.md: the file is not named after its id ST1");
    }
}
