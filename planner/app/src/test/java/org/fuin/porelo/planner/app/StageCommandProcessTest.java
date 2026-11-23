package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planner.app.WorkFixture.Answer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The planner's {@code check-pushes}, run as a build runs it: as its own process at a repository's
 * root, beside the build tools' command it replaces while they still carry it. The build tools
 * carry no {@code stage}, so the planner's {@code stage} is held to its own unit tests alone.
 */
class StageCommandProcessTest {

    private static final String PLANNER = "org.fuin.porelo.planner.app.PlannerApplication";

    private static final String BUILD_TOOLS = "org.fuin.porelo.release.Main";

    @TempDir Path dir;

    // Prevents a build that moves to the planner's check from reading other than the build tools
    // read, or a refusal from being answered with another exit code.
    @ParameterizedTest
    @ValueSource(strings = {"check-pushes", "check-pushes --since 0", "check-pushes --since 24"})
    void thePlannerAnswersAsTheBuildToolsDo(final String scenario) throws Exception {
        final List<String> args = List.of(scenario.split(" "));
        final Answer expected = run(BUILD_TOOLS, repository(dir.resolve("tools")), args);
        final Answer answer = run(PLANNER, repository(dir.resolve("planner")), args);

        assertThat(expected.code() + expected.out() + expected.err())
                .as("the build tools answered %s at all", scenario)
                .isNotBlank();
        assertThat(answer.code())
                .as("the exit code of %s, %s", scenario, answer.err())
                .isEqualTo(expected.code());
        assertThat(answer.out()).as("what %s says", scenario).isEqualTo(expected.out());
        assertThat(answer.err())
                .as("what %s says on standard error", scenario)
                .isEqualTo(expected.err());
    }

    /** A repository with one issue, EX12, and a workflow, as a build's checkout holds. */
    private static Path repository(final Path root) throws Exception {
        Files.createDirectories(root.resolve("issues"));
        Files.writeString(
                root.resolve("issues/EX12-Sample.md"),
                "# Sample\n\n**Status:** Implementing.\n**Size:** M - sample.\n");
        Files.copy(
                Path.of("../store/git/src/test/resources/workflow.yml"),
                root.resolve("issues/workflow.yml"));
        final Process git =
                isolated(
                                new ProcessBuilder(
                                        "git", "-C", root.toString(), "init", "-q", "-b", "main"))
                        .redirectErrorStream(true)
                        .start();
        final String said = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as(said).isZero();
        return root;
    }

    private static ProcessBuilder isolated(final ProcessBuilder builder) {
        final Map<String, String> env = builder.environment();
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.remove("GITHUB_TOKEN");
        env.remove("GITHUB_API_URL");
        env.remove("GITHUB_SERVER_URL");
        env.put("GITHUB_REPOSITORY", "porelo-ai/porelo-example");
        return builder;
    }

    private Answer run(final String main, final Path root, final List<String> args)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add("-XX:-UsePerfData");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(main);
        command.addAll(args);
        final Path out = dir.resolve(root.getFileName() + ".out");
        final Path err = dir.resolve(root.getFileName() + ".err");
        final ProcessBuilder builder =
                isolated(new ProcessBuilder(command))
                        .directory(root.toFile())
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile());
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS))
                    .as("%s answers within two minutes", main)
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
