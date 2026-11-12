package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.fuin.porelo.planner.app.WorkFixture.Answer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The planner's {@code check-commits}, run as a build runs it: as its own process at a repository's
 * root, beside the build tools' command it replaces while they still carry it.
 */
class CommitCommandTest {

    private static final String PLANNER = "org.fuin.porelo.planner.app.PlannerApplication";

    private static final String BUILD_TOOLS = "org.fuin.porelo.release.Main";

    @TempDir Path dir;

    // Prevents a repository that moves to the planner's check from passing what the build tools
    // refused, or refusing what they passed.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Describe the work | HEAD",
                "Describe the work and keep on describing it until the subject runs over its length"
                        + " | HEAD",
                "Describe EX12 | HEAD",
                "Describe the work\n\nA prose body below the subject. | HEAD",
                "Describe the work | HEAD --outside",
                "Describe the work | HEAD^..HEAD",
                "Describe the work |",
                "Describe the work | HEAD HEAD",
                "Describe the work | HEAD --all",
                "Describe the work | --all",
                "Describe the work | -n1",
                "Describe the work | nothing-of-that-name"
            })
    void thePlannerAnswersAsTheBuildToolsDo(final String scenario) throws Exception {
        final String[] parts = scenario.split(" \\|", 2);
        repository(parts[0]);
        final List<String> args = new ArrayList<>(List.of("check-commits"));
        for (final String arg : parts[1].strip().split(" ")) if (!arg.isEmpty()) args.add(arg);

        final Answer expected = run(BUILD_TOOLS, args, List.of());
        final Answer answer = run(PLANNER, args, List.of());

        assertThat(expected.code() + expected.out() + expected.err())
                .as("the build tools answered at all")
                .isNotBlank();
        assertThat(answer.code()).as("the exit code, %s", answer.err()).isEqualTo(expected.code());
        assertThat(answer.out()).as("what is said on standard output").isEqualTo(expected.out());
        assertThat(answer.err()).as("what is said on standard error").isEqualTo(expected.err());
    }

    // Prevents a commit's message from writing terminal control sequences into a build's log.
    @Test
    void aMessagesControlCharactersAreSaidEscaped() throws Exception {
        repository("Describe the work\n\nA prose line \u001b[2Jhidden");

        final Answer answer = run(PLANNER, List.of("check-commits", "HEAD"), List.of());

        assertThat(answer.code()).as("refused for its prose: %s", answer.err()).isEqualTo(1);
        assertThat(answer.err())
                .as("the escape character said as a sequence, never written")
                .contains("\\u001b[2Jhidden")
                .doesNotContain("\u001b");
    }

    // A read-only check must not leave a build directory or temporary files in its caller's
    // checkout or system.
    @Test
    void leavesNoFileBehind() throws Exception {
        repository("Describe the work");
        final Path temporary = Files.createDirectory(dir.resolve("temporary"));

        final Answer answer =
                run(
                        PLANNER,
                        List.of("check-commits", "HEAD"),
                        List.of("-Djava.io.tmpdir=" + temporary));

        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(dir.resolve("repository/target")).as("no build directory").doesNotExist();
        try (Stream<Path> files = Files.list(temporary)) {
            assertThat(files.toList()).as("no temporary file left").isEmpty();
        }
    }

    /** A repository of one issue, EX12, and one commit with the message given. */
    private void repository(final String message) throws Exception {
        final Path root = Files.createDirectories(dir.resolve("repository"));
        Files.createDirectories(root.resolve("issues"));
        Files.writeString(root.resolve("issues/EX12-Sample.md"), "**Status:** Idea.\n");
        git(root, "init", "-q", "-b", "main");
        git(root, "add", "issues");
        git(root, "commit", "-q", "-m", "Start the sample");
        Files.writeString(root.resolve("issues/EX12-Sample.md"), "**Status:** Accepted.\n");
        git(root, "add", "issues");
        git(root, "commit", "-q", "-m", message);
    }

    private static void git(final Path root, final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        isolated(builder);
        final Process git = builder.start();
        final String said = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as(said).isZero();
    }

    private static void isolated(final ProcessBuilder builder) {
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_AUTHOR_NAME", "Jane Doe");
        builder.environment().put("GIT_AUTHOR_EMAIL", "jane@example.org");
        builder.environment().put("GIT_COMMITTER_NAME", "Jane Doe");
        builder.environment().put("GIT_COMMITTER_EMAIL", "jane@example.org");
    }

    private Answer run(final String main, final List<String> args, final List<String> options)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add("-XX:-UsePerfData");
        command.addAll(options);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(main);
        command.addAll(args);
        final String name = main.equals(PLANNER) ? "planner" : "tools";
        final Path out = dir.resolve(name + ".out");
        final Path err = dir.resolve(name + ".err");
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(dir.resolve("repository").toFile())
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile());
        isolated(builder);
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS))
                    .as("%s answers within two minutes", name)
                    .isTrue();
            return new Answer(
                    process.exitValue(),
                    Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }
}
