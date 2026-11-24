package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.fuin.porelo.planner.app.WorkFixture.Answer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The planner's {@code check-commits}, run as a build runs it: as its own process at a repository's
 * root, beside the build tools' command it replaces while they still carry it.
 */
class CommitCommandTest {

    private static final String PLANNER = "org.fuin.porelo.planner.app.PlannerApplication";

    private static final String BUILD_TOOLS = "org.fuin.porelo.release.Main";

    /** An issue number by its shape alone, of a prefix no issue file of the fixture has. */
    private static final String SHAPED = "HO12"; // not-a-citation

    /** An issue in GitHub's reference form, as the issue trailer names one. */
    private static final String TRACKED = "porelo-ai/porelo-planner#39"; // not-a-citation

    /** A number GitHub links to an issue of the same repository. */
    private static final String BARE = "#12"; // not-a-citation

    /** An issue in GitHub's reference form under another owner, which GitHub links elsewhere. */
    private static final String OTHER_OWNER = "octo/cat#3"; // not-a-citation

    @TempDir Path dir;

    // Prevents a repository that moves to the planner's check from passing what the build tools
    // refused, or refusing what they passed.
    @ParameterizedTest(name = "{0}, tracked={1}")
    @MethodSource("commitScenarios")
    void thePlannerAnswersAsTheBuildToolsDo(final String scenario, final boolean tracked)
            throws Exception {
        final String[] parts = scenario.split(" \\|", 2);
        repository(parts[0], tracked);
        final List<String> args = new ArrayList<>(List.of("check-commits"));
        for (final String arg : parts[1].strip().split(" ")) if (!arg.isEmpty()) args.add(arg);

        final Answer expected = run(BUILD_TOOLS, args, List.of(), Map.of());
        same(expected, run(PLANNER, args, List.of(), Map.of()));
        if (parts[1].strip().equals("HEAD")) {
            final Map<String, Integer> outcomes =
                    Map.of(
                            "Describe the work\n\nPorelo-Issue: " + TRACKED,
                            0,
                            "Describe the work, fixes " + BARE,
                            1,
                            "Describe " + BARE,
                            1,
                            "Describe the work\n\nSigned-off-by: The Operator <operator@example.org>",
                            0);
            if (outcomes.containsKey(parts[0])) {
                assertThat(expected.code())
                        .as("the scenario's intended outcome: %s", expected)
                        .isEqualTo(outcomes.get(parts[0]));
            }
        }
    }

    private static Stream<Arguments> commitScenarios() {
        return Stream.of(
                        "Describe the work | HEAD",
                        "Describe the work and keep on describing it until the subject runs over its length"
                                + " | HEAD",
                        "Describe EX12 | HEAD",
                        "Describe " + BARE + " | HEAD",
                        "Describe " + SHAPED + " | HEAD",
                        "Describe the work\n\nPorelo-Issue: " + TRACKED + " | HEAD",
                        "Describe the work\n\nPorelo-Issue: " + BARE + " | HEAD",
                        "Describe the work, fixes " + BARE + " | HEAD",
                        "Describe the entity &" + BARE + "; | HEAD",
                        "Describe the path a/" + BARE + " | HEAD",
                        "Describe the word foo" + BARE + " | HEAD",
                        "Describe the cost $" + BARE + " | HEAD",
                        "Describe the work beside " + OTHER_OWNER + " | HEAD",
                        "Describe the work beside porelo-ai/x" + BARE + " | HEAD",
                        "Describe S256 beside " + TRACKED + " | HEAD",
                        "Describe the work\n\nAssisted-by: x, closes " + TRACKED + " | HEAD",
                        "Describe the work\n\nSigned-off-by: The Operator <operator@example.org> | HEAD",
                        "Describe the work\n\nA prose body below the subject. | HEAD",
                        "Describe the work | HEAD --outside",
                        "Describe the work | HEAD^..HEAD",
                        "Describe the work |",
                        "Describe the work | HEAD HEAD",
                        "Describe the work | HEAD --all",
                        "Describe the work | --all",
                        "Describe the work | -n1",
                        "Describe the work | nothing-of-that-name",
                        "Describe the work | --event HEAD",
                        "Describe the work | --event --outside",
                        "Describe the work | --event --all")
                .flatMap(
                        scenario ->
                                Stream.of(false, true)
                                        .map(tracked -> Arguments.of(scenario, tracked)));
    }

    // Prevents a repository that moves to the planner's check from taking commits from outside as
    // from inside, or reading another range than the build tools read for the same event.
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "a push on main | 0",
                "a push on main from a start the clone lacks | 1",
                "a push on a branch from a start the clone lacks | 0",
                "a tag | 0",
                "a pull request from inside | 0",
                "a pull request from outside | 1",
                "a schedule | 1",
                "no event path | 2",
                "a missing event file | 2",
                "an event file that is no JSON | 2"
            })
    void thePlannerReadsTheEventAsTheBuildToolsDo(final String scenario) throws Exception {
        final String[] parts = scenario.split(" \\| ", 2);
        repository("Describe the work");
        final Path root = dir.resolve("repository");
        final String base = revision(root, "HEAD~1");
        final String head = revision(root, "HEAD");
        final String lacked = "e".repeat(40);
        git(root, "update-ref", "refs/remotes/origin/main", base);
        final Path file = dir.resolve("event.json");
        final Map<String, String> environment = new HashMap<>();
        environment.put("GITHUB_EVENT_PATH", file.toString());
        environment.put("GITHUB_SHA", head);
        environment.put("GITHUB_REPOSITORY", "example/sample");
        environment.put("GITHUB_SERVER_URL", "https://github.com");
        environment.put("GITHUB_EVENT_NAME", "push");
        environment.put("GITHUB_REF", "refs/heads/main");
        environment.put("GITHUB_REF_TYPE", "branch");
        switch (parts[0]) {
            case "a push on main" -> Files.writeString(file, push(base));
            case "a push on main from a start the clone lacks" ->
                    Files.writeString(file, push(lacked));
            case "a push on a branch from a start the clone lacks" -> {
                environment.put("GITHUB_REF", "refs/heads/task");
                Files.writeString(file, push(lacked));
            }
            case "a tag" -> {
                environment.put("GITHUB_REF", "refs/tags/v1");
                environment.put("GITHUB_REF_TYPE", "tag");
                Files.writeString(file, push("0".repeat(40)));
            }
            case "a pull request from inside" -> {
                pullRequest(environment);
                Files.writeString(file, pull(base, head, "example/sample", "MEMBER"));
            }
            case "a pull request from outside" -> {
                pullRequest(environment);
                Files.writeString(file, pull(base, head, "someone/sample", "CONTRIBUTOR"));
            }
            case "a schedule" -> {
                environment.put("GITHUB_EVENT_NAME", "schedule");
                Files.writeString(file, "{\"schedule\": \"0 3 * * *\"}");
            }
            case "no event path" -> {
                environment.remove("GITHUB_EVENT_PATH");
                Files.writeString(file, push(base));
            }
            case "a missing event file" -> {}
            case "an event file that is no JSON" -> Files.writeString(file, "{\"before\": ");
            default -> throw new IllegalArgumentException("no such scenario: " + parts[0]);
        }
        final List<String> args = List.of("check-commits", "--event");

        final Answer expected = run(BUILD_TOOLS, args, List.of(), environment);
        same(expected, run(PLANNER, args, List.of(), environment));
        assertThat(expected.code())
                .as("the build tools' exit code, which the scenario is made for: %s", expected)
                .isEqualTo(Integer.parseInt(parts[1]));
    }

    /** A push's event file from its start. */
    private static String push(final String before) {
        return "{\"ref\": \"refs/heads/main\", \"before\": \"" + before + "\"}";
    }

    /** A pull request's event file, by an author of the association given. */
    private static String pull(
            final String base, final String head, final String repository, final String role) {
        return "{\"pull_request\": {\"base\": {\"sha\": \""
                + base
                + "\"}, \"head\": {\"sha\": \""
                + head
                + "\", \"repo\": {\"full_name\": \""
                + repository
                + "\"}}, \"author_association\": \""
                + role
                + "\", \"user\": {\"login\": \"jane\"}}}";
    }

    /** The runner's variables of a pull request, which runs on its merge ref. */
    private static void pullRequest(final Map<String, String> environment) {
        environment.put("GITHUB_EVENT_NAME", "pull_request");
        environment.put("GITHUB_REF", "refs/pull/1/merge");
    }

    /** Holds the planner's answer to the build tools'. */
    private static void same(final Answer expected, final Answer answer) {
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

        final Answer answer = run(PLANNER, List.of("check-commits", "HEAD"), List.of(), Map.of());

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
                        List.of("-Djava.io.tmpdir=" + temporary),
                        Map.of());

        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(dir.resolve("repository/target")).as("no build directory").doesNotExist();
        try (Stream<Path> files = Files.list(temporary)) {
            assertThat(files.toList()).as("no temporary file left").isEmpty();
        }
    }

    /** A repository of one issue, EX12, and one commit with the message given. */
    private void repository(final String message) throws Exception {
        repository(message, false);
    }

    /** A tracker keeps its issues outside the checkout; only the project configuration names it. */
    private void repository(final String message, final boolean tracked) throws Exception {
        final Path root = Files.createDirectories(dir.resolve("repository"));
        final String changed;
        if (tracked) {
            Files.writeString(
                    root.resolve("project.yml"),
                    """
                    project:
                        name: sample
                        central: https://github.com/example/sample.git
                    repositories:
                        sample:
                            central: https://github.com/example/sample.git
                    tracker:
                        kind: github
                        project: example/1
                    """);
            changed = "sample.txt";
        } else {
            Files.createDirectories(root.resolve("issues"));
            changed = "issues/EX12-Sample.md";
        }
        Files.writeString(root.resolve(changed), "**Status:** Idea.\n");
        git(root, "init", "-q", "-b", "main");
        if (tracked) git(root, "add", "project.yml");
        git(root, "add", changed);
        git(root, "commit", "-q", "-m", "Start the sample");
        Files.writeString(root.resolve(changed), "**Status:** Accepted.\n");
        git(root, "add", changed);
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

    /** A revision's full hash. */
    private static String revision(final Path root, final String name) throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--verify", name);
        isolated(builder);
        final Process git = builder.start();
        final String said =
                new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(git.waitFor()).as("git rev-parse %s: %s", name, said).isZero();
        return said;
    }

    private static void isolated(final ProcessBuilder builder) {
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_AUTHOR_NAME", "Jane Doe");
        builder.environment().put("GIT_AUTHOR_EMAIL", "jane@example.org");
        builder.environment().put("GIT_COMMITTER_NAME", "Jane Doe");
        builder.environment().put("GIT_COMMITTER_EMAIL", "jane@example.org");
    }

    /**
     * Runs a command at the repository's root, with none of the runner's variables but those given,
     * so a test run in a workflow reads no event of its own.
     */
    private Answer run(
            final String main,
            final List<String> args,
            final List<String> options,
            final Map<String, String> environment)
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
        builder.environment().keySet().removeIf(variable -> variable.startsWith("GITHUB_"));
        builder.environment().putAll(environment);
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
