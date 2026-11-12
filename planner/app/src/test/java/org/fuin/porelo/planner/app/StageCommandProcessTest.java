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
 * The planner's {@code stage} and {@code check-pushes}, run as an agent and a build run them: as
 * their own process at a repository's root, beside the build tools' commands they replace while
 * those still carry them.
 */
class StageCommandProcessTest {

    private static final String PLANNER = "org.fuin.porelo.planner.app.PlannerApplication";

    private static final String BUILD_TOOLS = "org.fuin.porelo.release.Main";

    /**
     * An instant as a record writes it, which differs between two runs a moment apart, as do the
     * minutes measured between two.
     */
    private static final String INSTANT = "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z";

    @TempDir Path dir;

    // Prevents an agent's stage, recorded by the planner, from reading other than the build tools'
    // record, or a refusal from being answered with another exit code.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "stage begin EX12 implement",
                "stage begin EX12 implement ; stage end EX12 implement --status-after Review",
                "stage begin EX12 implement ; stage begin EX12 plan",
                "stage end EX12 implement",
                "stage begin EX12 move",
                "stage begin EX12 implement --actor person",
                "stage begin EX99 implement",
                "stage median M",
                "stage p80 S",
                "stage median XXL",
                "stage",
                "check-pushes",
                "check-pushes --since 0",
                "check-pushes --since 24"
            })
    void thePlannerAnswersAsTheBuildToolsDo(final String scenario) throws Exception {
        final Path tools = repository(dir.resolve("tools"));
        final Path planner = repository(dir.resolve("planner"));

        for (final String step : scenario.split(" ; ")) {
            final List<String> args = List.of(step.split(" "));
            final Answer expected = run(BUILD_TOOLS, tools, args);
            final Answer answer = run(PLANNER, planner, args);

            assertThat(expected.code() + expected.out() + expected.err())
                    .as("the build tools answered %s at all", step)
                    .isNotBlank();
            assertThat(answer.code())
                    .as("the exit code of %s, %s", step, answer.err())
                    .isEqualTo(expected.code());
            assertThat(answer.out()).as("what %s says", step).isEqualTo(expected.out());
            assertThat(answer.err())
                    .as("what %s says on standard error", step)
                    .isEqualTo(expected.err());
        }
        assertThat(records(planner)).as("the records written").isEqualTo(records(tools));
    }

    /** A repository with one issue, EX12, a workflow and a seed, as an agent's checkout holds. */
    private static Path repository(final Path root) throws Exception {
        Files.createDirectories(root.resolve("issues"));
        Files.createDirectories(root.resolve("stats"));
        Files.writeString(
                root.resolve("issues/EX12-Sample.md"),
                "# Sample\n\n**Status:** Implementing.\n**Size:** M - sample.\n");
        Files.copy(
                Path.of("../store/git/src/test/resources/workflow.yml"),
                root.resolve("issues/workflow.yml"));
        Files.writeString(root.resolve("stats/seed.csv"), "size,minutes\nXS,8\nS,27\nM,53\nL,80\n");
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

    private static String records(final Path root) throws IOException {
        final Path file = root.resolve("stats/EX12.jsonl");
        return Files.exists(file)
                ? Files.readString(file)
                        .replaceAll(INSTANT, "<instant>")
                        .replaceAll("\"wall_min\":[0-9.E-]+", "\"wall_min\":<minutes>")
                : "(none)";
    }

    private static ProcessBuilder isolated(final ProcessBuilder builder) {
        final Map<String, String> env = builder.environment();
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.remove("PORELO_STAGE_CLAUDE_LOG");
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
