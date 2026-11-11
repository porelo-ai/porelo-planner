package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.fuin.porelo.planner.app.WorkFixture.Answer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The planner's work tools and the build tools' commands they replace, run on the same fixture: the
 * same lines, the same exit code and the same files written. Kept only while the build tools still
 * carry the commands.
 */
class WorkCommandsMatchBuildToolsTest {

    @TempDir Path dir;

    // Prevents a repository that moves to the planner's check from passing what the build tools
    // refused, or refusing what they passed.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "issues check porelo-alpha/issues",
                "issues check porelo-alpha/issues | edited index",
                "issues index porelo-alpha/issues | stale index",
                "issues check porelo-alpha/missing",
                "stories check porelo-sample/stories",
                "stories check porelo-sample/stories | missing issue",
                "stories check porelo-sample/stories | stale story",
                "stories index porelo-sample/stories | stale story",
                "stories cut 1.2.0 porelo-sample/stories --after 2026-10-31",
                "stories cut 1.2.0 porelo-sample/stories --after 2026-10-31 | stale story"
            })
    void thePlannerAnswersAsTheBuildToolsDo(final String scenario) throws Exception {
        final String[] parts = scenario.split(" \\| ");
        final Path tools = prepared(dir.resolve("tools"), parts);
        final Path planner = prepared(dir.resolve("planner"), parts);

        final Answer expected = buildTools(tools, arguments(tools, parts[0]));
        final Answer answer = WorkFixture.planner(arguments(planner, parts[0]));

        assertThat(expected.code() + expected.out() + expected.err())
                .as("the build tools answered at all")
                .isNotBlank();
        assertThat(answer.code()).as("the exit code, %s", answer.err()).isEqualTo(expected.code());
        assertThat(answer.out().replace(planner.toString(), "<root>"))
                .as("what is said on standard output")
                .isEqualTo(expected.out().replace(tools.toString(), "<root>"));
        assertThat(answer.err().replace(planner.toString(), "<root>"))
                .as("what is said on standard error")
                .isEqualTo(expected.err().replace(tools.toString(), "<root>"));
        assertThat(files(planner)).as("the files written").isEqualTo(files(tools));
    }

    private static Path prepared(final Path root, final String[] parts) throws IOException {
        WorkFixture.copy(root);
        if (parts.length < 2) return root;
        switch (parts[1]) {
            case "edited index" ->
                    replace(
                            root.resolve("porelo-alpha/issues/README.md"),
                            "The second step follows the first",
                            "Edited by hand");
            case "stale index" ->
                    Files.writeString(
                            root.resolve("porelo-alpha/issues/README.md"),
                            "# Issues\n\nThe issues of porelo-alpha.\n");
            case "missing issue" ->
                    replace(
                            root.resolve("porelo-sample/stories/ST2-The-Second-Release.md"),
                            "porelo-alpha#AB02",
                            "porelo-alpha#AB09");
            case "stale story" ->
                    replace(
                            root.resolve("porelo-sample/stories/ST1-The-First-Release.md"),
                            "\"gain\": \"A person gains the first release.\"",
                            "\"gain\": \"A person gains more than the text says.\"");
            default -> throw new IllegalArgumentException(parts[1]);
        }
        return root;
    }

    private static void replace(final Path file, final String text, final String by)
            throws IOException {
        final String before = Files.readString(file);
        assertThat(before).as("the fixture holds %s", text).contains(text);
        Files.writeString(file, before.replace(text, by));
    }

    private static String[] arguments(final Path root, final String command) {
        final List<String> args = new ArrayList<>();
        for (final String arg : command.split(" "))
            args.add(arg.startsWith("porelo-") ? root.resolve(arg).toString() : arg);
        return args.toArray(String[]::new);
    }

    private Answer buildTools(final Path root, final String[] args) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.fuin.porelo.release.Main");
        command.addAll(List.of(args));
        final Path out = dir.resolve(root.getFileName() + ".out");
        final Path err = dir.resolve(root.getFileName() + ".err");
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile());
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS))
                    .as("the build tools answer within two minutes")
                    .isTrue();
            return new Answer(
                    process.exitValue(),
                    Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    private static Map<String, String> files(final Path root) throws IOException {
        final Map<String, String> files = new TreeMap<>();
        try (Stream<Path> all = Files.walk(root)) {
            for (final Path file : all.filter(Files::isRegularFile).toList())
                files.put(root.relativize(file).toString(), Files.readString(file));
        }
        return files;
    }
}
