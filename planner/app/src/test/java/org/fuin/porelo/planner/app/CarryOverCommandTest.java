package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The executable's dry run of a carry-over, over the work fixture's two checkouts. */
class CarryOverCommandTest {

    @TempDir Path dir;

    /** One closed stage of an agent and one move of a person, as the stage command writes them. */
    static final String RECORDS =
            "{\"issue\":\"AB02\",\"stage\":\"implement\",\"n\":1,\"actor\":\"agent\",\"model\":\"opus\","
                    + "\"effort\":\"high\",\"size\":\"S\",\"status_before\":\"Backlog\","
                    + "\"status_after\":\"Backlog\",\"start\":\"2026-11-01T10:00:00Z\","
                    + "\"end\":\"2026-11-01T10:12:00Z\",\"wall_min\":12,\"vm_min\":0,"
                    + "\"estimate_min\":null,\"result\":\"done\",\"wait_reason\":null,\"note\":null,"
                    + tokens("7")
                    + "}\n"
                    + "{\"issue\":\"AB02\",\"stage\":\"move\",\"n\":1,\"actor\":\"person\",\"model\":null,"
                    + "\"effort\":null,\"size\":\"S\",\"status_before\":\"Backlog\","
                    + "\"status_after\":\"Backlog\",\"start\":\"2026-11-01T11:00:00Z\","
                    + "\"end\":\"2026-11-01T11:00:00Z\",\"wall_min\":null,\"vm_min\":null,"
                    + "\"estimate_min\":null,\"result\":\"done\",\"wait_reason\":null,\"note\":null,"
                    + tokens("null")
                    + "}\n";

    private static String tokens(final String input) {
        return "\"claude_input_tokens\":"
                + input
                + ",\"claude_output_tokens\":null,\"claude_cache_read_tokens\":null,"
                + "\"claude_cache_write_tokens\":null,\"claude_messages\":null,"
                + "\"codex_input_tokens\":null,\"codex_cached_tokens\":null,"
                + "\"codex_output_tokens\":null,\"codex_reasoning_tokens\":null,"
                + "\"agy_input_tokens\":null,\"agy_cache_read_tokens\":null,"
                + "\"agy_output_tokens\":null";
    }

    private WorkFixture.Answer dryRun(final Path root) {
        return WorkFixture.planner(
                "carry-over",
                "--project",
                root.resolve("porelo-sample").toString(),
                "--to",
                "porelo-ai/7",
                "--dry-run");
    }

    /** Every file under a directory with its bytes, to see that nothing was written. */
    private static Map<String, String> files(final Path root) throws IOException {
        final Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (final Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(
                        root.relativize(file).toString(),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void theDryRunSaysOneLinePerOpenItemAndWritesNothing() throws IOException {
        // Harm: a dry run that changes a checkout is no dry run.
        final Path root = WorkFixture.copy(dir);
        Files.createDirectories(root.resolve("porelo-alpha/stats"));
        Files.writeString(root.resolve("porelo-alpha/stats/AB02.jsonl"), RECORDS);
        final Map<String, String> before = files(root);
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code()).as("the exit code; it said %s", answer.err()).isZero();
        assertThat(answer.out().lines())
                .as("the lines")
                .hasSize(3)
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .startsWith("ST2  porelo-sample  story  ")
                                        .doesNotContain("Id=")
                                        .contains("sub-issues: porelo-alpha#AB02"))
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .startsWith("AB02  porelo-alpha  issue  ")
                                        .doesNotContain("Id=")
                                        .contains("Minutes=12")
                                        .contains("stage comments: 1, moves left out: 1")
                                        .contains("not carried: AB01"));
        assertThat(answer.out().lines().toList().getLast())
                .as("the last line")
                .isEqualTo(
                        "DRY RUN  2 item(s) to porelo-ai/7: 1 story(ies), 1 issue(s); nothing was"
                                + " written");
        assertThat(files(root)).as("the checkouts after the dry run").isEqualTo(before);
    }

    @Test
    void aMissingCheckoutStopsTheRunBeforeAnyLine() throws IOException {
        // Harm: a project read without one of its repositories is planned as if it were whole.
        final Path root = WorkFixture.copy(dir);
        Files.move(root.resolve("porelo-alpha"), root.resolve("elsewhere"));
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.out()).as("the lines").isEmpty();
        assertThat(answer.err()).as("the fault").contains("no checkout of porelo-alpha");
    }

    @Test
    void anIssueFileTheBoardLeftOutStopsTheRunBeforeAnyLine() throws IOException {
        // Harm: an issue left out of the board is never carried and nobody is told.
        final Path root = WorkFixture.copy(dir);
        final Path file = root.resolve("porelo-alpha/issues/AB02-The-Second-Step.md");
        Files.writeString(
                file,
                Files.readString(file, StandardCharsets.UTF_8).replace("\"rank\"", "\"rang\""));
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.out()).as("the lines").isEmpty();
        assertThat(answer.err()).as("the fault").contains("AB02").contains("no plan is given");
    }

    @Test
    void aStageStillOpenStopsTheRunNamingItsFile() throws IOException {
        // Harm: a stage still running is carried without its end and its minutes are lost.
        final Path root = WorkFixture.copy(dir);
        Files.createDirectories(root.resolve("porelo-alpha/stats"));
        Files.writeString(
                root.resolve("porelo-alpha/stats/AB02.jsonl"),
                RECORDS.lines()
                                .findFirst()
                                .orElseThrow()
                                .replace("\"status_after\":\"Backlog\"", "\"status_after\":null")
                                .replace("\"end\":\"2026-11-01T10:12:00Z\"", "\"end\":null")
                                .replace(
                                        "\"wall_min\":12,\"vm_min\":0",
                                        "\"wall_min\":null,\"vm_min\":null")
                                .replace("\"result\":\"done\"", "\"result\":null")
                        + "\n");
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.out()).as("the lines").isEmpty();
        assertThat(answer.err()).as("the fault").contains("stats/AB02.jsonl");
    }

    @Test
    void aProjectATrackerKeepsAlreadyIsNotCarriedAgain() throws IOException {
        // Harm: a project carried twice doubles every item on GitHub.
        final Path root = WorkFixture.copy(dir);
        Files.writeString(
                root.resolve("porelo-sample/project.yml"),
                "\ntracker:\n    kind: github\n    project: porelo-ai/7\n",
                java.nio.file.StandardOpenOption.APPEND);
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.out()).as("the lines").isEmpty();
        assertThat(answer.err()).as("the fault").contains("names a tracker already");
    }

    /** Runs Git in a directory, without the person's configuration. */
    private static void git(final Path where, final String... arguments)
            throws IOException, InterruptedException {
        final java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(where.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git %s: %s", command, out).isZero();
    }

    @Test
    void aFaultOfTheBoardNoItemDependsOnIsSaidAfterTheLinesAndStopsNothing() throws Exception {
        // Harm: a settled issue's row naming its deleted file stops a carry-over it does not touch.
        final Path root = WorkFixture.copy(dir);
        final Path alpha = root.resolve("porelo-alpha");
        Files.delete(alpha.resolve("issues/AB03-The-Third-Step.md"));
        git(alpha, "init", "-q");
        git(alpha, "add", ".");
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code()).as("the exit code; it said %s", answer.err()).isZero();
        final java.util.List<String> lines = answer.out().lines().toList();
        assertThat(lines.get(2))
                .as("the heading after the item lines")
                .isEqualTo("FAULTS OF THE BOARD THAT CONCERN NO CARRIED ITEM");
        assertThat(lines.get(3))
                .as("the fault")
                .contains("issues/README.md")
                .contains("links to the deleted file of AB03");
        assertThat(lines.getLast()).as("the last line").startsWith("DRY RUN  2 item(s)");
    }

    @Test
    void aSecretInALinkAFaultNamesIsNotSaid() throws IOException {
        // Harm: a token a person pasted into a link is printed to a terminal or a log.
        final Path root = WorkFixture.copy(dir);
        Files.writeString(
                root.resolve("porelo-alpha/issues/AB02-The-Second-Step.md"),
                "\nSee [AB02](https://someone:hunter2@github.com/org/repo).\n",
                java.nio.file.StandardOpenOption.APPEND);
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.err()).as("the fault").contains("AB02").doesNotContain("hunter2");
    }

    @Test
    void stageRecordsBehindALinkedDirectoryAreNeverRead() throws IOException {
        // Harm: a record file outside the checkout is read and carried as the issue's stages.
        final Path root = WorkFixture.copy(dir);
        final Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("AB02.jsonl"), RECORDS);
        Files.createSymbolicLink(root.resolve("porelo-alpha/stats"), outside);
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.out()).as("the lines").isEmpty();
        assertThat(answer.err()).as("the fault").contains("stats");
    }

    @Test
    void aLinkedWorkflowIsNeverRead() throws IOException {
        // Harm: a file outside the checkout is read, and its words are echoed in the fault.
        final Path root = WorkFixture.copy(dir);
        final Path outside = Files.writeString(dir.resolve("outside.yml"), "hiddenword: 1\n");
        final Path workflow = root.resolve("porelo-sample/stories/workflow.yml");
        Files.delete(workflow);
        Files.createSymbolicLink(workflow, outside);
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.err())
                .as("the fault")
                .contains("not a plain file")
                .doesNotContain("hiddenword");
    }

    @Test
    void aProjectNamedThroughALinkFindsItsOtherCheckoutsBesideTheRealOne() throws IOException {
        // Harm: the other repositories are looked for beside the link, so another directory's
        // checkouts are read as the project's.
        final Path root = WorkFixture.copy(dir.resolve("real"));
        final Path link = Files.createDirectories(dir.resolve("links")).resolve("porelo-sample");
        Files.createSymbolicLink(link, root.resolve("porelo-sample"));
        final WorkFixture.Answer answer =
                WorkFixture.planner(
                        "carry-over",
                        "--project",
                        link.toString(),
                        "--to",
                        "porelo-ai/7",
                        "--dry-run");
        assertThat(answer.code()).as("the exit code; it said %s", answer.err()).isZero();
    }

    @Test
    void oneDirectoryIsNeverReadAsTwoRepositories() throws IOException {
        // Harm: a checkout read as both the own repository and another gives each the other's
        // issues.
        final Path root = WorkFixture.copy(dir);
        final WorkFixture.Answer answer =
                WorkFixture.planner(
                        "carry-over",
                        "--project",
                        root.resolve("porelo-alpha").toString(),
                        "--to",
                        "porelo-ai/7",
                        "--dry-run");
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.err()).as("the fault").contains("is the checkout of two repositories");
    }

    @Test
    void anIssueWhosePrefixItsRepositoryDoesNotOwnStopsTheRun() throws IOException {
        // Harm: a checkout of another repository under the expected name has its issues carried
        // as the project's.
        final Path root = WorkFixture.copy(dir);
        final Path issues = root.resolve("porelo-alpha/issues");
        final Path file = issues.resolve("AB02-The-Second-Step.md");
        Files.writeString(
                issues.resolve("XY05-The-Second-Step.md"),
                Files.readString(file, StandardCharsets.UTF_8)
                        .replace("AB02", "XY05")
                        .replace("\"AB01\"", ""));
        Files.delete(file);
        final Path index = issues.resolve("README.md");
        Files.writeString(
                index,
                Files.readString(index, StandardCharsets.UTF_8)
                        .replace(
                                "[AB02](AB02-The-Second-Step.md)",
                                "[XY05](XY05-The-Second-Step.md)")
                        .replace("ab02", "xy05"));
        final WorkFixture.Answer answer = dryRun(root);
        assertThat(answer.code())
                .as("the exit code; it said %s", answer.err())
                .isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.err()).as("the fault").contains("XY05 has a prefix");
    }
}
