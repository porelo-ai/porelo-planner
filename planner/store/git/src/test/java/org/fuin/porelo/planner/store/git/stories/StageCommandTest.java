package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StageCommandTest {
    @TempDir Path directory;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private String time = "2026-01-01T10:00:00Z";

    @BeforeEach
    void preparesAnIsolatedRepository() throws Exception {
        git("init", "-q", "-b", "main");
        write(
                "issues/EX12-Sample.md",
                "# Sample\n\n**Status:** Implementing.\n**Size:** M - sample.\n");
        write("issues/workflow.yml", Files.readString(Path.of("src/test/resources/workflow.yml")));
        write("stats/seed.csv", "size,minutes\nXS,8\nS,27\nM,53\nL,80\n");
    }

    // Prevents a converted issue's stage from being recorded with a state or size it does not have.
    @Test
    void readsTheStateAndSizeOfAConvertedIssueFromItsHead() throws Exception {
        write("issues/EX12-Sample.md", head("Implementing", "S") + "\n**What must be true.** X.\n");
        assertThat(run("begin", "EX12", "implement")).as(errors()).isZero();
        assertThat(record().get("status_before")).isEqualTo("Implementing");
        assertThat(record().get("size")).as("the head's size, not a line's").isEqualTo("S");
        time = "2026-01-01T10:04:00Z";
        write("issues/EX12-Sample.md", head("Review", "S"));
        assertThat(run("end", "EX12", "implement")).as(errors()).isZero();
        assertThat(record().get("status_after")).isEqualTo("Review");

        write("issues/EX12-Sample.md", head("Accepted", "-"));
        assertThat(run("begin", "EX12", "analyze")).as(errors()).isZero();
        assertThat(record().get("size")).as("a dash is no size yet").isNull();
        assertThat(run("end", "EX12", "analyze")).as(errors()).isZero();

        write("issues/EX12-Sample.md", head("Implementing", "-"));
        assertThat(run("begin", "EX12", "implement"))
                .as("measured work without a size is refused, as without a Size line")
                .isEqualTo(2);
        write("issues/EX12-Sample.md", head("Implementing", "XXL"));
        assertThat(run("begin", "EX12", "implement")).as("a head the schema refuses").isEqualTo(2);
        write(
                "issues/EX12-Sample.md",
                "# EX12 — Sample\n\n```json\n{\"state\": \"Implementing\"\n```\n");
        assertThat(run("begin", "EX12", "implement")).as("a head that is no JSON").isEqualTo(2);
        assertThat(errors()).contains("issue head");
        write(
                "issues/EX12-Sample.md",
                "# EX12 — Sample\n\n```JSON\n{}\n```\n\n**Status:** Implementing.\n**Size:** M.\n");
        assertThat(run("begin", "EX12", "implement"))
                .as("a fence that is no head fence is no way back to the prose lines")
                .isEqualTo(2);
    }

    private static String head(String state, String size) {
        return "# EX12 — Sample\n\n```json\n{\"$schema\": \"issue-1.json\", \"id\": \"EX12\","
                + " \"title\": \"Sample\", \"state\": \""
                + state
                + "\", \"size\": \""
                + size
                + "\", \"priority\": \"Medium\", \"rank\": 1, \"blockedBy\": [], \"covers\":"
                + " \"A sample.\"}\n```\n";
    }

    // Prevents a newly configured state from making accounting impossible.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "Reading",
                "In analysis",
                "Under estimation",
                "Reworking",
                "Finishing",
                "Review (local)"
            })
    void recordsConfiguredStatesWithoutARelease(String state) throws Exception {
        final Path workflow = directory.resolve("issues/workflow.yml");
        if (!Workflow.read(workflow).states().contains(state))
            Files.writeString(
                    workflow,
                    Files.readString(workflow)
                            .replace(
                                    "states:\n",
                                    "states:\n  - {name: '" + state + "', closed: false}\n"));
        write("issues/EX12-Sample.md", "**Status:** " + state + ".\n**Size:** M - sample.\n");
        assertThat(run("begin", "EX12", "review")).as(errors()).isZero();
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "review", "--status-after", state)).as(errors()).isZero();
        assertThat(record().get("status_before")).isEqualTo(state);
        assertThat(record().get("status_after")).isEqualTo(state);
    }

    // Prevents an unmeasured prefix from being recorded instead of the full configured state.
    // Rejected effort must not bias either estimate statistic.
    @ParameterizedTest
    @ValueSource(strings = {"median", "p80"})
    void rejectedWorkDoesNotCountAsACompletedSample(String statistic) throws Exception {
        write("stats/EX13.jsonl", measured("EX13", "implement", "agent", 1000, "Rejected"));
        write("stats/EX14.jsonl", measured("EX14", "implement", "agent", 1200, "Rejected"));
        assertThat(run(statistic, "M")).as(errors()).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8).strip()).isEqualTo("53");
    }

    @Test
    void doesNotMistakeAPrefixForTheState() throws Exception {
        write(
                "issues/workflow.yml",
                "states:\n  - {name: Read, closed: false}\n  - {name: Read work, closed: false, measured: true}\nphases: []\n");
        write("issues/EX12-Sample.md", "**Status:** Read work.\n**Size:** M - sample.\n");
        assertThat(run("begin", "EX12", "review")).as(errors()).isZero();
        assertThat(record().get("status_before")).isEqualTo("Read work");
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "review")).as(errors()).isZero();
        for (String unknown : List.of("Read elsewhere", "Read.work", "Read;work")) {
            write("issues/EX12-Sample.md", "**Status:** " + unknown + ".\n**Size:** M - sample.\n");
            assertThat(run("begin", "EX12", "review"))
                    .as("unknown state %s cannot pass as a known prefix", unknown)
                    .isEqualTo(2);
        }
    }

    @Test
    void refusesASecondOwnStageWithoutLosingTheFirst() throws Exception {
        assertThat(run("begin", "EX12", "implement")).as(errors()).isZero();
        assertThat(run("begin", "EX12", "review"))
                .as("overlapping own work is double counted")
                .isEqualTo(2);
        assertThat(errors()).as("the refusal explains the open stage").contains("open");
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "implement")).as(errors()).isZero();
        assertThat(records()).as("the first stage survives").hasSize(1);
        assertThat(record().get("stage"))
                .as("the refused begin cannot replace work")
                .isEqualTo("implement");
    }

    @Test
    void closesExactlyOneStageWithOnlyTheClaudeWindowTokens() throws Exception {
        final Path log = fixture("claude.jsonl", "opaque.data");
        assertThat(run("begin", "EX12", "implement", "--session", log.toString()))
                .as(errors())
                .isZero();
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "implement")).as(errors()).isZero();
        assertThat(records()).as("closing retains one JSON line").hasSize(1);
        final Map<?, ?> record = record();
        assertThat(record.get("claude_input_tokens"))
                .as("outside-window usage is excluded")
                .isEqualTo(17.0);
        assertThat(record.get("claude_output_tokens")).as("both responses count").isEqualTo(7.0);
        assertThat(record.get("claude_cache_read_tokens"))
                .as("cache reads are separate")
                .isEqualTo(28.0);
        assertThat(record.get("claude_cache_write_tokens"))
                .as("cache writes are separate")
                .isEqualTo(3.0);
        assertThat(record.get("claude_messages"))
                .as("only assistant usage entries count")
                .isEqualTo(2.0);
        assertThat(record.get("wall_min"))
                .as("the injected clock measures elapsed minutes")
                .isEqualTo(4.0);
        assertThat(record.get("end")).as("end closes the public record").isEqualTo(time);
        assertThat(records().getFirst())
                .as("records contain no session path")
                .doesNotContain(log.toString());
        assertThat(run("end", "EX12", "implement")).as("retry cannot double count").isEqualTo(2);
        assertThat(records()).as("repeated end leaves one line").hasSize(1);
    }

    @Test
    void countsTheCodexSessionOnceBesideAnOpenOwnStage() throws Exception {
        final Path log = directory.resolve("opaque.data");
        assertThat(run("begin", "EX12", "implement")).as(errors()).isZero();
        assertThat(
                        run(
                                "begin",
                                "EX12",
                                "review",
                                "--actor",
                                "subagent",
                                "--session",
                                log.toString(),
                                "--model",
                                "codex"))
                .as(errors())
                .isZero();
        fixture("codex.jsonl", "opaque.data");
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "review", "--actor", "subagent", "--session", log.toString()))
                .as(errors())
                .isZero();
        final Map<?, ?> record = (Map<?, ?>) CommitFixtures.parse(records().get(1));
        assertThat(record.get("actor"))
                .as("subagents do not consume the own stage")
                .isEqualTo("subagent");
        assertThat(record.get("codex_input_tokens"))
                .as("cumulative totals must not be summed twice")
                .isEqualTo(160.0);
        assertThat(record.get("codex_cached_tokens"))
                .as("cached input is preserved")
                .isEqualTo(50.0);
        assertThat(record.get("codex_output_tokens")).as("output is preserved").isEqualTo(35.0);
        assertThat(record.get("codex_reasoning_tokens"))
                .as("reasoning is preserved")
                .isEqualTo(15.0);
        assertThat(run("end", "EX12", "implement"))
                .as("own stage remains independently open")
                .isZero();
        assertThat(records()).as("both actors are recorded separately").hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent", "subagent"})
    void detectsAgyUsageByContent(String actor) throws Exception {
        final Path log = fixture("agy.json", "opaque.data");
        assertThat(
                        run(
                                "begin",
                                "EX12",
                                "review",
                                "--actor",
                                actor,
                                "--session",
                                log.toString(),
                                "--model",
                                "agy"))
                .as(errors())
                .isZero();
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "review", "--actor", actor, "--session", log.toString()))
                .as(errors())
                .isZero();
        assertThat(record().get("agy_input_tokens")).as("agy input is not guessed").isEqualTo(12.0);
        assertThat(record().get("agy_cache_read_tokens"))
                .as("agy cache reads are preserved")
                .isEqualTo(5.0);
        assertThat(record().get("agy_output_tokens")).as("agy output is preserved").isEqualTo(8.0);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"usage\":{\"input\":12,\"output\":8}}",
                "{\"usage\":{\"input\":12,\"cacheRead\":5,\"output\":8,\"input_tokens\":12}}"
            })
    void refusesIncompleteOrMixedAgyUsageWithoutInventingTokens(String content) throws Exception {
        final Path log = write("incomplete.data", content);
        assertThat(
                        run(
                                "begin",
                                "EX12",
                                "review",
                                "--actor",
                                "subagent",
                                "--session",
                                log.toString(),
                                "--model",
                                "agy"))
                .as(errors())
                .isZero();
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "review", "--actor", "subagent", "--session", log.toString()))
                .as("ambiguous or absent usage must not be replaced with guessed counts")
                .isEqualTo(2);
        assertThat(record().get("end")).as("invalid usage leaves the public stage open").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../EX12", "ex12", "EX1", "EX1234", "ABCD12"})
    void refusesAnInvalidIssueBeforeWritingAnything(String issue) throws Exception {
        assertThat(run("begin", issue, "implement"))
                .as("untrusted issue must not become a path")
                .isEqualTo(2);
        assertThat(Files.exists(directory.resolve(".git/porelo-stage")))
                .as("invalid input creates no state")
                .isFalse();
        assertThat(Files.exists(directory.resolve("stats/EX12.jsonl")))
                .as("invalid input creates no stats")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "two words", "../vm", "VM"})
    void refusesAnInvalidWaitReasonBeforeWritingAnything(String reason) throws Exception {
        assertThat(run("begin", "EX12", "wait", "--wait", reason))
                .as("invalid reasons must not enter records")
                .isEqualTo(2);
        assertThat(Files.exists(directory.resolve(".git/porelo-stage")))
                .as("invalid input creates no state")
                .isFalse();
    }

    @Test
    void leavesUnknownTokensNullAndNumbersRepeatedStages() throws Exception {
        for (int n = 1; n <= 2; n++) {
            assertThat(run("begin", "EX12", "review")).as(errors()).isZero();
            assertThat(run("end", "EX12", "review")).as(errors()).isZero();
            assertThat(record().get("n")).as("repeated work is counted").isEqualTo((double) n);
            assertThat(record().get("claude_input_tokens"))
                    .as("missing sessions never invent usage")
                    .isNull();
            assertThat(record().get("note"))
                    .as("missing usage is explicit")
                    .isEqualTo("no-session");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"done", "abandoned", "refused"})
    void refusesMalformedUsageWithoutClosingOrWritingTheStage(String result) throws Exception {
        final Path log =
                write(
                        "bad.data",
                        "{\"type\":\"assistant\",\"timestamp\":\"2026-01-01T10:01:00Z\",\"message\":{\"id\":\"msg_invalid\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":-1,\"output_tokens\":0,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}}\n");
        assertThat(run("begin", "EX12", "test", "--session", log.toString())).as(errors()).isZero();
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "test", "--result", result))
                .as("corrupt usage fails closed")
                .isEqualTo(2);
        assertThat(record().get("end")).as("no false measurements are published").isNull();
    }

    @Test
    void takesTheMedianOfTheSeedAndFinishedOwnWorkOnly() throws Exception {
        write(
                "stats/EX13.jsonl",
                measured("EX13", "plan", "agent", 10, "Implementing")
                        + measured("EX13", "implement", "agent", 30, "Implementing")
                        + measured("EX13", "wait", "agent", 100, "Implementing")
                        + measured("EX13", "review", "subagent", 100, "Implementing")
                        + measured("EX13", "handover", "agent", 5, "Finished"));
        write(
                "stats/EX14.jsonl",
                measured("EX14", "analyze", "agent", 200, "Implementing")
                        + measured("EX14", "implement", "agent", 50, "Finished"));
        write("stats/EX15.jsonl", measured("EX15", "implement", "agent", 1000, "Review"));
        assertThat(run("median", "M")).as(errors()).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8).strip())
                .as(
                        "median of 45, 50, 53 excludes waits, analysis, subagents and unfinished"
                                + " issues")
                .isEqualTo("50");
    }

    @Test
    void acceptsAPersonsMoveOnTheBoardAndLeavesItOutOfTheMedian() throws Exception {
        write(
                "stats/EX13.jsonl",
                move("EX13", "Backlog", "Implementing")
                        + measured("EX13", "plan", "agent", 10, "Implementing")
                        + measured("EX13", "implement", "agent", 30, "Implementing")
                        + measured("EX13", "handover", "agent", 5, "Finishing")
                        + move("EX13", "Finishing", "Finished"));
        write("stats/EX14.jsonl", measured("EX14", "implement", "agent", 50, "Finished"));
        assertThat(run("median", "M")).as(errors()).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8).strip())
                .as("median of 45, 50, 53: the moves add no minutes and no sample")
                .isEqualTo("50");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "a person in a measured stage",
                "an agent's move",
                "a move with minutes",
                "a move with zero minutes"
            })
    void refusesAMoveLineOutsideItsOneShape(String fault) throws Exception {
        final var record = moveRecord("EX13", "Backlog", "Implementing");
        switch (fault) {
            case "a person in a measured stage" -> record.put("stage", "implement");
            case "an agent's move" -> record.put("actor", "agent");
            case "a move with minutes" -> record.put("wall_min", 3);
            default -> {
                record.put("wall_min", 0);
                record.put("vm_min", 0);
            }
        }
        write("stats/EX13.jsonl", CommitFixtures.json(record) + "\n");
        assertThat(run("median", "M")).as(fault + " is refused").isEqualTo(2);
    }

    @Test
    void refusesToBeginAMove() {
        assertThat(run("begin", "EX12", "move"))
                .as("a move is the planner's line, never a stage an agent opens")
                .isEqualTo(2);
    }

    @Test
    void readsTheOwnSessionFromTheEnvironmentWithoutRecordingItsPath() throws Exception {
        final Path log = fixture("claude.jsonl", "environment.data");
        final Map<String, String> environment = Map.of("PORELO_STAGE_CLAUDE_LOG", log.toString());
        assertThat(runAt(directory, environment, "begin", "EX12", "test")).as(errors()).isZero();
        time = "2026-01-01T10:04:00Z";
        assertThat(runAt(directory, Map.of(), "end", "EX12", "test"))
                .as("end retains begin's source even when the environment changes")
                .isZero();
        assertThat(record().get("claude_input_tokens"))
                .as("the supplied environment is honored")
                .isEqualTo(17.0);
        assertThat(records().getFirst())
                .as("the source stays private")
                .doesNotContain(log.toString());
    }

    @Test
    void keepsTwoSubagentSessionsSeparateAndRefusesReusingAnOpenSession() throws Exception {
        final Path first = fixture("codex.jsonl", "first.data");
        final Path second = fixture("agy.json", "second.data");
        assertThat(
                        run(
                                "begin",
                                "EX12",
                                "review",
                                "--actor",
                                "subagent",
                                "--session",
                                first.toString(),
                                "--model",
                                "codex"))
                .as(errors())
                .isZero();
        assertThat(
                        run(
                                "begin",
                                "EX12",
                                "review",
                                "--actor",
                                "subagent",
                                "--session",
                                first.toString(),
                                "--model",
                                "codex"))
                .as("one open stage per session prevents duplicate accounting")
                .isEqualTo(2);
        assertThat(
                        run(
                                "begin",
                                "EX12",
                                "review",
                                "--actor",
                                "subagent",
                                "--session",
                                second.toString(),
                                "--model",
                                "agy"))
                .as("another subagent may run concurrently")
                .isZero();
        time = "2026-01-01T10:04:00Z";
        assertThat(
                        run(
                                "end",
                                "EX12",
                                "review",
                                "--actor",
                                "subagent",
                                "--session",
                                second.toString()))
                .as(errors())
                .isZero();
        assertThat(record().get("n")).as("open sessions reserve distinct ordinals").isEqualTo(2.0);
        assertThat(record().get("codex_input_tokens"))
                .as("the other session is not mixed in")
                .isNull();
        assertThat(
                        run(
                                "end",
                                "EX12",
                                "review",
                                "--actor",
                                "subagent",
                                "--session",
                                first.toString()))
                .as(errors())
                .isZero();
        final Map<?, ?> firstRecord = (Map<?, ?>) CommitFixtures.parse(records().getFirst());
        assertThat(firstRecord.get("n")).as("each session retains its own ordinal").isEqualTo(1.0);
        assertThat(firstRecord.get("agy_input_tokens"))
                .as("the other session is not mixed in")
                .isNull();
    }

    @Test
    void storesWorktreeStateInItsGitDirectoryAndStatsInItsOwnTree() throws Exception {
        git("add", "issues", "stats/seed.csv");
        git(
                "-c",
                "user.name=Example",
                "-c",
                "user.email=example@example.org",
                "commit",
                "-q",
                "-m",
                "Prepare work");
        final Path linked = directory.resolve("linked");
        git("worktree", "add", "--detach", linked.toString(), "HEAD");
        assertThat(runAt(linked, Map.of(), "begin", "EX12", "implement")).as(errors()).isZero();
        assertThat(Files.exists(linked.resolve("stats/EX12.jsonl")))
                .as("open stages are visible in the linked tree")
                .isTrue();
        assertThat(
                        Files.isRegularFile(
                                directory.resolve(".git/worktrees/linked/porelo-stage/agent.json")))
                .as("a linked worktree's .git is a file, not the storage directory")
                .isTrue();
        time = "2026-01-01T10:04:00Z";
        assertThat(runAt(linked, Map.of(), "end", "EX12", "implement")).as(errors()).isZero();
        assertThat(Files.readAllLines(linked.resolve("stats/EX12.jsonl")))
                .as("the linked tree receives the closed stage")
                .hasSize(1);
        assertThat(Files.exists(directory.resolve("stats/EX12.jsonl")))
                .as("another worktree's measurements are untouched")
                .isFalse();
    }

    @Test
    void recordsTheEstimateAndTheExplicitResultAndStatus() throws Exception {
        assertThat(run("begin", "EX12", "estimate", "--model", "example-model", "--effort", "high"))
                .as(errors())
                .isZero();
        time = "2026-01-01T10:04:00Z";
        assertThat(
                        run(
                                "end",
                                "EX12",
                                "estimate",
                                "--result",
                                "returned",
                                "--status-after",
                                "Review"))
                .as(errors())
                .isZero();
        assertThat(record().get("estimate_min"))
                .as("an estimate is measured from its size seed")
                .isEqualTo(53.0);
        assertThat(record().get("result"))
                .as("returns are retained rather than reported as done")
                .isEqualTo("returned");
        assertThat(record().get("status_before"))
                .as("begin's status is preserved")
                .isEqualTo("Implementing");
        assertThat(record().get("status_after"))
                .as("end records the supplied state")
                .isEqualTo("Review");
    }

    @Test
    void closesWithAnExplicitStatusAfterTheIssueFileWasRemoved() throws Exception {
        assertThat(run("begin", "EX12", "handover")).as(errors()).isZero();
        Files.delete(directory.resolve("issues/EX12-Sample.md"));
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "handover", "--status-after", "Finished"))
                .as("finishing an issue removes its file but must not strand its stage")
                .isZero();
        assertThat(record().get("status_after"))
                .as("finished work remains available to the median")
                .isEqualTo("Finished");
    }

    @Test
    void refusesAConflictingRecordWithoutAStartInsteadOfCrashing() throws Exception {
        assertThat(run("begin", "EX12", "implement")).as(errors()).isZero();
        final String malformed =
                "{\"n\":1,\"stage\":\"implement\",\"actor\":\"agent\",\"end\":\"2026-01-01T10:00:00Z\"}\n";
        write("stats/EX12.jsonl", malformed);
        time = "2026-01-01T10:04:00Z";
        assertThat(run("end", "EX12", "implement"))
                .as("conflicting stored data must refuse rather than crash or duplicate work")
                .isEqualTo(2);
        assertThat(Files.readString(directory.resolve("stats/EX12.jsonl")))
                .as("the conflicting record is not overwritten")
                .isEqualTo(malformed);
        assertThat(Files.isRegularFile(directory.resolve(".git/porelo-stage/agent.json")))
                .as("failed end preserves the open stage for repair")
                .isTrue();
    }

    // These regressions prevent lost work, incorrect estimates, and leaking private paths.
    @Test
    void publishesOpenRecordsAndReplacesOnlyTheMatchingRecord() throws Exception {
        assertThat(run("begin", "EX12", "plan")).as(errors()).isZero();
        assertThat(record().get("end")).as("commit checks can see unfinished work").isNull();
        assertThat(run("end", "EX12", "plan")).as(errors()).isZero();
        final String closed = records().getFirst();
        write("stats/EX12.jsonl", closed + "\r\n");
        assertThat(run("begin", "EX12", "implement")).as(errors()).isZero();
        assertThat(records()).as("begin appends an open record").hasSize(2);
        assertThat(record().get("end")).as("the latest stage is open").isNull();
        assertThat(run("end", "EX12", "implement")).as(errors()).isZero();
        assertThat(records()).as("end replaces rather than appending").hasSize(2);
        assertThat(records().getFirst()).as("unrelated lines stay byte for byte").isEqualTo(closed);
        assertThat(Files.readString(directory.resolve("stats/EX12.jsonl")))
                .as("neighboring line endings are preserved")
                .startsWith(closed + "\r\n");
        assertThat(record().get("end")).as("the matching stage closes").isEqualTo(time);
    }

    @Test
    void commitChecksRefuseWorkOpenedAfterAnEarlierClosedStage() throws Exception {
        assertThat(run("begin", "EX12", "plan")).as(errors()).isZero();
        assertThat(run("end", "EX12", "plan")).as(errors()).isZero();
        assertThat(run("begin", "EX12", "implement")).as(errors()).isZero();
        git("add", "issues", "stats");
        git(
                "-c",
                "user.name=Example",
                "-c",
                "user.email=example@example.org",
                "commit",
                "-q",
                "-m",
                "Record work");
        final int result =
                new CommitRules(
                                new PrintStream(out),
                                new PrintStream(err),
                                CommitRules.Git.in(
                                        directory,
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")),
                                () -> java.util.Set.of("EX"))
                        .check("HEAD", false);
        assertThat(result)
                .as("the commit's public open record blocks handover")
                .isEqualTo(CommitRules.REFUSED);
        assertThat(errors())
                .as("the refusal identifies the missing end")
                .contains("stats/EX12.jsonl", "end");
    }

    @Test
    void refusesRecordsSeparatedWithoutNewlines() throws Exception {
        assertThat(run("begin", "EX12", "plan")).as(errors()).isZero();
        final String malformed =
                Files.readString(directory.resolve("stats/EX12.jsonl")).stripTrailing()
                        + "\r"
                        + CommitFixtures.closed("EX12");
        write("stats/EX12.jsonl", malformed);
        assertThat(run("end", "EX12", "plan"))
                .as("invalid delimiters cannot erase a neighboring record")
                .isEqualTo(2);
        assertThat(Files.readString(directory.resolve("stats/EX12.jsonl")))
                .as("invalid stats are left for repair")
                .isEqualTo(malformed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Idea", "Accepted"})
    void permitsUnsizedAnalysisAndEstimationThenUsesEachBeginsSize(String status) throws Exception {
        write("issues/EX12-Sample.md", "**Status:** " + status + ".\n");
        for (final String stage : List.of("analyze", "estimate")) {
            assertThat(run("begin", "EX12", stage)).as(errors()).isZero();
            assertThat(run("end", "EX12", stage)).as(errors()).isZero();
            assertThat(record().get("size")).as("early work can precede sizing").isNull();
            assertThat(record().get("estimate_min")).as("no size means no size estimate").isNull();
        }
        write("issues/EX12-Sample.md", "**Status:** Analyzed.\n**Size:** S - sample.\n");
        assertThat(run("begin", "EX12", "estimate")).as(errors()).isZero();
        write("issues/EX12-Sample.md", "**Status:** Estimated.\n**Size:** M - sample.\n");
        assertThat(run("end", "EX12", "estimate")).as(errors()).isZero();
        assertThat(record().get("size")).as("end preserves begin's size").isEqualTo("S");
        assertThat(record().get("estimate_min")).as("estimate uses its own S size").isEqualTo(27.0);
        assertThat(run("begin", "EX12", "plan")).as(errors()).isZero();
        assertThat(record().get("size")).as("later work reads the current size").isEqualTo("M");
    }

    @ParameterizedTest
    @ValueSource(strings = {"median", "p80"})
    void groupsFinishedWorkByTheMeasuredStagesSize(String statistic) throws Exception {
        final var analysis =
                CommitFixtures.record("EX13", "analyze", "agent", 200, "Analyzed", null);
        analysis.put("status_before", "Accepted");
        write(
                "stats/EX13.jsonl",
                CommitFixtures.json(analysis)
                        + "\n"
                        + CommitFixtures.json(
                                CommitFixtures.record(
                                        "EX13", "plan", "agent", 10, "Implementing", "S"))
                        + "\n"
                        + CommitFixtures.json(
                                CommitFixtures.record(
                                        "EX13", "handover", "agent", 30, "Finished", "M"))
                        + "\n");
        assertThat(run(statistic, "M")).as(errors()).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8).strip())
                .as("one M sample of 30 plus seed 53; S work and analysis are excluded")
                .isEqualTo(statistic.equals("median") ? "41.5" : "53");
    }

    @Test
    void findsIssuesInSubgroups() throws Exception {
        Files.createDirectories(directory.resolve("issues/core"));
        Files.move(
                directory.resolve("issues/EX12-Sample.md"),
                directory.resolve("issues/core/EX12-Sample.md"));
        assertThat(run("begin", "EX12", "implement")).as(errors()).isZero();
        assertThat(run("end", "EX12", "implement")).as(errors()).isZero();
        assertThat(record().get("issue"))
                .as("nested issues retain their identity")
                .isEqualTo("EX12");
    }

    @Test
    void requiresTheFutureSubagentSessionAtEnd() throws Exception {
        final Path log = directory.resolve("future.data");
        assertThat(subagent("begin", log)).as(errors()).isZero();
        assertThat(subagent("end", log)).as("missing files cannot fabricate usage").isEqualTo(2);
        fixture("agy.json", "future.data");
        assertThat(subagent("end", log)).as(errors()).isZero();
        assertThat(record().get("agy_input_tokens"))
                .as("retry reads the created session")
                .isEqualTo(12.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abandoned", "refused"})
    void closesFailedSessionsWithoutUsageAndAllowsTheNextStage(String result) throws Exception {
        for (final String content :
                List.of(
                        "",
                        "{\"type\":\"session_meta\",\"payload\":{\"id\":\"example\"}}\n",
                        "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":null}}\n",
                        "{\"response\":\"\",\"usage\":null}\n",
                        "{\"response\":\"\",\"usage\":{}}\n",
                        "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_cancelled\",\"role\":\"assistant\",\"content\":[]},\"timestamp\":\"2026-01-01T10:00:00Z\"}\n")) {
            final Path log = write("failed.data", content);
            assertThat(subagent("begin", log)).as(errors()).isZero();
            assertThat(
                            run(
                                    "end",
                                    "EX12",
                                    "review",
                                    "--actor",
                                    "subagent",
                                    "--session",
                                    log.toString(),
                                    "--result",
                                    result))
                    .as(errors())
                    .isZero();
            final Map<?, ?> record = record();
            assertThat(record.get("note")).as("zero usage is explicit").isEqualTo("no-usage");
            for (final String field :
                    List.of(
                            "claude_input_tokens",
                            "claude_output_tokens",
                            "claude_cache_read_tokens",
                            "claude_cache_write_tokens",
                            "claude_messages",
                            "codex_input_tokens",
                            "codex_cached_tokens",
                            "codex_output_tokens",
                            "codex_reasoning_tokens",
                            "agy_input_tokens",
                            "agy_cache_read_tokens",
                            "agy_output_tokens")) {
                assertThat(record.get(field)).as("no usage: %s", field).isEqualTo(0.0);
            }
        }
    }

    @Test
    void closesOwnSessionBeforeItHasAssistantUsage() throws Exception {
        final Path log =
                write(
                        "own.data",
                        "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Example\"}}\n");
        assertThat(run("begin", "EX12", "implement", "--session", log.toString()))
                .as(errors())
                .isZero();
        assertThat(run("end", "EX12", "implement", "--result", "abandoned")).as(errors()).isZero();
        assertThat(record().get("note")).as("missing usage is explicit").isEqualTo("no-usage");
        assertThat(run("begin", "EX12", "implement")).as("no stranded own stage").isZero();
    }

    @Test
    void countsSplitClaudeMessagesOnceUsingTheirLastUsage() throws Exception {
        final Path log = fixture("claude-subagent.jsonl", "split.data");
        assertThat(subagent("begin", log)).as(errors()).isZero();
        time = "2026-01-01T10:04:00Z";
        assertThat(subagent("end", log)).as(errors()).isZero();
        assertThat(record().get("claude_input_tokens"))
                .as("split content blocks share a message")
                .isEqualTo(17.0);
        assertThat(record().get("claude_output_tokens"))
                .as("the last usage supersedes partial usage")
                .isEqualTo(7.0);
        assertThat(record().get("claude_messages"))
                .as("two message IDs in the window")
                .isEqualTo(2.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent", "subagent"})
    void subtractsTheCodexTotalCapturedAtEachBeginRegardlessOfActor(String actor) throws Exception {
        final Path log = fixture("codex.jsonl", "continued.data");
        for (int n = 1; n <= 2; n++) {
            assertThat(
                            run(
                                    "begin",
                                    "EX12",
                                    "implement",
                                    "--actor",
                                    actor,
                                    "--model",
                                    "example-model",
                                    "--session",
                                    log.toString()))
                    .as(errors())
                    .isZero();
            final String total =
                    "{\"timestamp\":\"2026-01-01T10:01:00Z\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":"
                            + (160 + n * 20)
                            + ",\"cached_input_tokens\":"
                            + (50 + n * 2)
                            + ",\"output_tokens\":"
                            + (35 + n * 4)
                            + ",\"reasoning_output_tokens\":"
                            + (15 + n)
                            + "}}}}\n";
            Files.writeString(log, total, java.nio.file.StandardOpenOption.APPEND);
            assertThat(
                            run(
                                    "end",
                                    "EX12",
                                    "implement",
                                    "--actor",
                                    actor,
                                    "--session",
                                    log.toString()))
                    .as(errors())
                    .isZero();
            assertThat(record().get("codex_input_tokens"))
                    .as("only new input counts")
                    .isEqualTo(20.0);
            assertThat(record().get("codex_cached_tokens"))
                    .as("only new cache reads count")
                    .isEqualTo(2.0);
            assertThat(record().get("codex_output_tokens"))
                    .as("only new output counts")
                    .isEqualTo(4.0);
            assertThat(record().get("codex_reasoning_tokens"))
                    .as("only new reasoning counts")
                    .isEqualTo(1.0);
        }
    }

    @Test
    void writesTheOrdinalAsAnInteger() throws Exception {
        assertThat(run("begin", "EX12", "implement")).as(errors()).isZero();
        assertThat(run("end", "EX12", "implement")).as(errors()).isZero();
        assertThat(records().getFirst())
                .as("the public ordinal is an integer")
                .contains("\"n\":1,")
                .doesNotContain("\"n\":1.0");
    }

    @Test
    void hidesPrivatePathsInFilesystemErrors() throws Exception {
        Files.delete(directory.resolve("stats/seed.csv"));
        assertThat(run("median", "M")).as("unreadable seed fails closed").isEqualTo(2);
        assertThat(errors())
                .as("filesystem diagnostics do not disclose paths")
                .doesNotContain(directory.toString())
                .contains("stage:");
        assertThat(run("begin", "EX12", "implement", "--session", directory.toString()))
                .as("a directory is not a session")
                .isEqualTo(2);
        assertThat(errors())
                .as("session diagnostics stay private")
                .doesNotContain(directory.toString());
    }

    @Test
    void hidesInvalidSessionPaths() {
        assertThat(run("begin", "EX12", "implement", "--session", directory + "/private-account\0"))
                .as("an invalid path is refused")
                .isEqualTo(2);
        assertThat(errors())
                .as("invalid-path diagnostics do not echo input")
                .doesNotContain(directory.toString(), "private-account");
    }

    @ParameterizedTest
    @ValueSource(strings = {"median", "p80"})
    void refusesIncompleteStatsWhenComputingStatistics(String statistic) throws Exception {
        final var record = CommitFixtures.record("EX13", "implement", "agent", 4, "Finished", "M");
        record.remove("n");
        write("stats/EX13.jsonl", CommitFixtures.json(record) + "\n");
        assertThat(run(statistic, "M"))
                .as("invalid historical records cannot bias estimates")
                .isEqualTo(2);
    }

    @Test
    void refusesAResultOnBegin() {
        assertThat(run("begin", "EX12", "implement", "--result", "done"))
                .as("results describe a closed stage")
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stats", "issues", ".git"})
    void refusesRepositoryStorageSymlinks(String name) throws Exception {
        final Path original = directory.resolve(name);
        final Path moved = directory.resolve("original-" + name.replace(".", ""));
        Files.move(original, moved);
        Files.createSymbolicLink(original, moved);
        assertThat(run("begin", "EX12", "implement"))
                .as("repository-owned storage cannot redirect writes")
                .isEqualTo(2);
        assertThat(Files.exists(moved.resolve("porelo-stage")))
                .as("unsafe Git storage is untouched")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "--model",
                "--effort",
                "--session",
                "--actor",
                "--result",
                "--status-after",
                "--wait"
            })
    void refusesOptionValuesThatStartWithAHyphen(String option) throws Exception {
        assertThat(run("begin", "EX12", "implement", option, "-private-account"))
                .as("an option cannot be consumed as a value")
                .isEqualTo(2);
        assertThat(errors())
                .as("the rejected value stays private")
                .doesNotContain("private-account");
    }

    private int subagent(String action, Path session) {
        return run(
                action,
                "EX12",
                "review",
                "--actor",
                "subagent",
                "--session",
                session.toString(),
                "--model",
                "example-model");
    }

    private static String move(String issue, String before, String after) {
        return CommitFixtures.json(moveRecord(issue, before, after)) + "\n";
    }

    private static Map<String, @Nullable Object> moveRecord(
            String issue, String before, String after) {
        final var record = CommitFixtures.record(issue, "move", "person", 0, after, null);
        record.put("status_before", before);
        record.put("end", record.get("start"));
        record.put("wall_min", null);
        record.put("vm_min", null);
        record.put("note", null);
        return record;
    }

    private static String measured(
            String issue, String stage, String actor, int minutes, String status) {
        return CommitFixtures.json(CommitFixtures.record(issue, stage, actor, minutes, status, "M"))
                + "\n";
    }

    // Prevents the caller's environment from moving the private stage state out of the repository.
    @Test
    void refusesAGitDirectoryTheEnvironmentNamesElsewhere() throws Exception {
        final Path elsewhere = Files.createDirectory(directory.resolve("elsewhere"));
        final Path other = elsewhere.resolve("repository");
        Files.createDirectories(other);
        gitIn(other, "init", "-q", "-b", "main");

        final int code =
                runAt(
                        directory,
                        Map.of("GIT_DIR", other.resolve(".git").toString()),
                        "begin",
                        "EX12",
                        "implement");

        assertThat(code).as("the repository's own git directory is used: %s", errors()).isZero();
        assertThat(other.resolve(".git/porelo-stage"))
                .as("nothing written elsewhere")
                .doesNotExist();
        assertThat(directory.resolve(".git/porelo-stage/agent.json"))
                .as("the state where the repository keeps it")
                .exists();
    }

    // Prevents a crafted .git file from placing the private stage state in any writable directory.
    @Test
    void refusesAGitFileThatNamesADirectoryElsewhere() throws Exception {
        final Path elsewhere = Files.createDirectories(directory.resolve("elsewhere/storage"));
        gitIn(elsewhere, "init", "-q", "--bare");
        final Path root = Files.createDirectories(directory.resolve("crafted"));
        Files.writeString(root.resolve(".git"), "gitdir: " + elsewhere + "\n");
        Files.createDirectories(root.resolve("issues"));
        Files.copy(
                directory.resolve("issues/EX12-Sample.md"), root.resolve("issues/EX12-Sample.md"));
        Files.copy(directory.resolve("issues/workflow.yml"), root.resolve("issues/workflow.yml"));

        assertThat(runAt(root, Map.of(), "begin", "EX12", "implement"))
                .as("a git directory that is not the repository's own")
                .isEqualTo(2);
        assertThat(elsewhere.resolve("porelo-stage")).as("nothing written there").doesNotExist();
    }

    // Prevents a crafted .git file from placing the private stage state in another repository's
    // worktree storage, which would lock that repository's own stage commands out.
    @Test
    void takesOnlyALinkedWorktreeOfThisRepository() throws Exception {
        git(
                "-c",
                "user.name=Example",
                "-c",
                "user.email=example@example.org",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "Start");
        final Path linked = directory.resolve("linked");
        git("worktree", "add", "-q", linked.toString());
        Files.createDirectories(linked.resolve("issues"));
        Files.copy(
                directory.resolve("issues/EX12-Sample.md"),
                linked.resolve("issues/EX12-Sample.md"));
        Files.copy(directory.resolve("issues/workflow.yml"), linked.resolve("issues/workflow.yml"));
        final Path storage = directory.resolve(".git/worktrees/linked");
        final Path crafted = Files.createDirectories(directory.resolve("crafted"));
        Files.writeString(crafted.resolve(".git"), "gitdir: " + storage + "\n");
        Files.createDirectories(crafted.resolve("issues"));
        Files.copy(
                directory.resolve("issues/EX12-Sample.md"),
                crafted.resolve("issues/EX12-Sample.md"));
        Files.copy(
                directory.resolve("issues/workflow.yml"), crafted.resolve("issues/workflow.yml"));

        assertThat(runAt(crafted, Map.of(), "begin", "EX12", "implement"))
                .as("another checkout's worktree storage")
                .isEqualTo(2);
        assertThat(storage.resolve("porelo-stage")).as("nothing written there").doesNotExist();
        assertThat(runAt(linked, Map.of(), "begin", "EX12", "implement"))
                .as("the linked worktree itself: %s", errors())
                .isZero();
        assertThat(storage.resolve("porelo-stage/agent.json"))
                .as("its state in its own worktree storage")
                .exists();
    }

    // Prevents a session file that is a link from being read in place of the agent's own log.
    @Test
    void refusesASessionThatIsASymbolicLink() throws Exception {
        final Path target = fixture("claude.jsonl", "claude-target.jsonl");
        final Path link = Files.createSymbolicLink(directory.resolve("session.jsonl"), target);

        assertThat(run("begin", "EX12", "implement", "--session", link.toString()))
                .as("a linked session")
                .isEqualTo(2);
        assertThat(errors()).contains("session is not a readable regular file");
    }

    // Prevents a session larger than its bound from being read whole into memory.
    @Test
    void refusesASessionOverItsBound() throws Exception {
        final Path session = directory.resolve("large.jsonl");
        final byte[] blanks = new byte[1024 * 1024];
        java.util.Arrays.fill(blanks, (byte) ' ');
        try (var file = Files.newOutputStream(session)) {
            for (long written = 0;
                    written <= StageCommand.SESSION_LIMIT;
                    written += blanks.length) {
                file.write(blanks);
            }
        }

        assertThat(run("begin", "EX12", "implement", "--session", session.toString()))
                .as("a session over 64 MB")
                .isEqualTo(2);
        assertThat(errors()).contains("larger than 64 MB");
    }

    // Prevents a session swapped for a FIFO or a link after begin from hanging end or being read.
    @ParameterizedTest
    @ValueSource(strings = {"fifo", "link"})
    void checksTheSessionAgainAtEnd(String swap) throws Exception {
        final Path session = fixture("claude.jsonl", "own.jsonl");
        assertThat(run("begin", "EX12", "implement", "--session", session.toString()))
                .as(errors())
                .isZero();
        Files.delete(session);
        switch (swap) {
            case "fifo" -> {
                final Process mkfifo = new ProcessBuilder("mkfifo", session.toString()).start();
                assertThat(mkfifo.waitFor()).as("mkfifo").isZero();
            }
            default -> Files.createSymbolicLink(session, fixture("claude.jsonl", "other.jsonl"));
        }
        time = "2026-01-01T10:04:00Z";

        final var ended =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> run("end", "EX12", "implement"));

        assertThat(ended.get(20, java.util.concurrent.TimeUnit.SECONDS))
                .as("refused within its bound: %s", errors())
                .isEqualTo(2);
        assertThat(record().get("end")).as("the stage stays open").isNull();
    }

    // Prevents a begin that broke off before its record from locking the agent out of every stage.
    @Test
    void clearsAPrivateStateWhoseRecordIsMissing() throws Exception {
        final Path state = directory.resolve(".git/porelo-stage/agent.json");
        Files.createDirectories(state.getParent());
        Files.writeString(
                state,
                "{\"issue\":\"EX12\",\"stage\":\"implement\",\"n\":1,\"session\":null,"
                        + "\"codex_begin\":null}");

        assertThat(run("begin", "EX12", "implement")).as(errors()).isZero();
        assertThat(records()).as("one record, opened now").hasSize(1);
    }

    private void gitIn(Path where, String... arguments) throws Exception {
        final var command = new java.util.ArrayList<>(List.of("git", "-C", where.toString()));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }

    private int run(String... arguments) {
        return runAt(directory, Map.of(), arguments);
    }

    private int runAt(Path root, Map<String, String> environment, String... arguments) {
        final Map<String, String> isolated = new java.util.HashMap<>(environment);
        isolated.put("GIT_CONFIG_GLOBAL", "/dev/null");
        isolated.put("GIT_CONFIG_NOSYSTEM", "1");
        return new StageCommand(
                        root,
                        Clock.fixed(Instant.parse(time), ZoneOffset.UTC),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        isolated)
                .run(List.of(arguments));
    }

    private Path fixture(String resource, String name) throws Exception {
        final Path path = directory.resolve(name);
        try (var input = StageCommandTest.class.getResourceAsStream("/stages/" + resource)) {
            if (input == null) {
                throw new IOException("Missing fixture " + resource);
            }
            Files.copy(input, path);
        }
        return path;
    }

    private Path write(String name, String text) throws IOException {
        final Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text);
    }

    private List<String> records() throws IOException {
        return Files.readAllLines(directory.resolve("stats/EX12.jsonl"));
    }

    private Map<?, ?> record() throws IOException {
        return (Map<?, ?>) CommitFixtures.parse(records().getLast());
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private void git(String... arguments) throws Exception {
        final var command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}
