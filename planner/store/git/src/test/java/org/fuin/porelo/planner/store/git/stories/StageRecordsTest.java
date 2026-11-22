package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Reads, adds to and counts an issue's stage records as the build tools' {@code stage} command
 * writes and counts them, a person's move on the board among them.
 */
class StageRecordsTest {

    private static final String SEED = "size,minutes\nXS,8\nS,27\nM,53\nL,80\n";

    private static final Instant AT = Instant.parse("2026-01-02T09:30:00.123456789Z");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Workflow workflow() throws Exception {
        return Workflow.read(Path.of("src/test/resources/workflow.yml"));
    }

    /** A complete closed record, independent of the reader's own field list. */
    private static Map<String, @Nullable Object> record(
            final String issue,
            final String stage,
            final String actor,
            final int minutes,
            final String status,
            final @Nullable String size) {
        final Map<String, @Nullable Object> record = new LinkedHashMap<>();
        record.put("issue", issue);
        record.put("stage", stage);
        record.put("n", 1);
        record.put("actor", actor);
        record.put("model", null);
        record.put("effort", null);
        record.put("size", size);
        record.put("status_before", "Implementing");
        record.put("status_after", status);
        record.put("start", "2026-01-01T10:00:00Z");
        record.put(
                "end", Instant.parse("2026-01-01T10:00:00Z").plusSeconds(minutes * 60L).toString());
        record.put("wall_min", minutes);
        record.put("vm_min", 0);
        record.put("estimate_min", "estimate".equals(stage) && size != null ? 53 : null);
        record.put("result", "done");
        record.put("wait_reason", "wait".equals(stage) ? "build" : null);
        record.put("note", "no-session");
        for (final String tokens :
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
            record.put(tokens, null);
        }
        return record;
    }

    private static String line(final Map<String, @Nullable Object> record) throws Exception {
        return JSON.writeValueAsString(record) + "\n";
    }

    private static String measured(
            final String issue,
            final String stage,
            final String actor,
            final int minutes,
            final String status)
            throws Exception {
        return line(record(issue, stage, actor, minutes, status, "M"));
    }

    private static Map<String, @Nullable Object> moveRecord() throws Exception {
        final String text =
                StageRecords.move(null, "EX12", "M", "Review", "Finished", AT, workflow());
        return new LinkedHashMap<>(StageRecords.read(text, "EX12.jsonl", true, workflow()).get(0));
    }

    // Prevents a person's move from being recorded with values the commit check or a reader
    // cannot take back.
    @Test
    void aMoveIsOneClosedLineOfAPersonThatTheReaderTakesBack() throws Exception {
        final String text =
                StageRecords.move(null, "EX12", "M", "Review", "Finished", AT, workflow());

        assertThat(text).as("one line, ended by a newline").endsWith("}\n").hasLineCount(1);
        final Map<String, @Nullable Object> record =
                StageRecords.read(text, "EX12.jsonl", true, workflow()).get(0);
        assertThat(record.keySet())
                .as("the fields in the order the stage command writes them, and no session")
                .containsExactlyElementsOf(
                        record("EX12", "move", "person", 0, "Done", null).keySet());
        assertThat(record)
                .as("a person's move, closed the moment it was made, without time or tokens")
                .containsEntry("stage", "move")
                .containsEntry("actor", "person")
                .containsEntry("n", 1)
                .containsEntry("size", "M")
                .containsEntry("status_before", "Review")
                .containsEntry("status_after", "Finished")
                .containsEntry("start", AT.toString())
                .containsEntry("end", AT.toString())
                .containsEntry("wall_min", null)
                .containsEntry("vm_min", null)
                .containsEntry("estimate_min", null)
                .containsEntry("result", "done")
                .containsEntry("note", null)
                .containsEntry("claude_input_tokens", null);
    }

    // Prevents an issue without a size from blocking a person's move.
    @Test
    void aMoveOfAnIssueWithoutASizeRecordsNone() throws Exception {
        final String text =
                StageRecords.move(null, "EX12", null, "Idea", "Accepted", AT, workflow());

        assertThat(StageRecords.read(text, "EX12.jsonl", true, workflow()).get(0))
                .as("no size")
                .containsEntry("size", null);
    }

    // Prevents a move from rewriting an agent's measured line or numbering two moves alike.
    @Test
    void aMoveIsAppendedKeepingEveryLineAndNumberedAfterTheIssuesMovesAlone() throws Exception {
        final String agent = measured("EX12", "implement", "agent", 4, "Review");
        final String once =
                StageRecords.move(agent, "EX12", "M", "Review", "Rework", AT, workflow());
        final String twice =
                StageRecords.move(once, "EX12", "M", "Rework", "Review", AT, workflow());

        assertThat(twice).as("the earlier lines stand as they were").startsWith(once);
        assertThat(
                        StageRecords.read(twice, "EX12.jsonl", true, workflow()).stream()
                                .map(record -> record.get("n"))
                                .toList())
                .as("moves numbered among themselves, after the agent's own")
                .containsExactly(1, 1, 2);
    }

    // Prevents a move from being added under a broken record, hiding the fault.
    @Test
    void aMoveOntoARecordTheReaderRefusesIsRefused() {
        assertThatThrownBy(
                        () ->
                                StageRecords.move(
                                        "{\"issue\":\n",
                                        "EX12",
                                        "M",
                                        "Review",
                                        "Finished",
                                        AT,
                                        workflow()))
                .as("the broken record is named, not appended to")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid JSON in a stage record");
    }

    // Prevents a record file whose name the commit check refuses from being written.
    @ParameterizedTest
    @ValueSource(strings = {"AB01.1", "ABCD01", "AB1"})
    void aMoveOfAnIssueWhoseIdCannotNameARecordIsRefused(final String issue) {
        assertThatThrownBy(
                        () ->
                                StageRecords.move(
                                        null, issue, "M", "Review", "Finished", AT, workflow()))
                .as("refused before anything is written")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid record issue");
    }

    // Prevents a move into a state the workflow does not know from reaching the records.
    @Test
    void aMoveIntoAnUnknownStateIsRefused() {
        assertThatThrownBy(
                        () ->
                                StageRecords.move(
                                        null, "EX12", "M", "Review", "Gone", AT, workflow()))
                .as("the new state is checked against the workflow")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid record status_after in the workflow");
    }

    // Prevents a person's line from passing for an agent's work, or an agent's for a move.
    @ParameterizedTest
    @ValueSource(strings = {"actor=agent", "stage=implement", "actor=subagent"})
    void aPersonOnlyMovesAndOnlyAPersonMoves(final String change) throws Exception {
        final Map<String, @Nullable Object> record = moveRecord();
        final String[] field = change.split("=");
        record.put(field[0], field[1]);
        if (field[1].equals("implement")) {
            record.put("wall_min", 0);
            record.put("vm_min", 0);
        }

        assertThatThrownBy(() -> StageRecords.read(line(record), "EX12.jsonl", true, workflow()))
                .as("the pair is refused when one half is changed")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid record actor for its stage");
    }

    // Prevents a move from carrying time or tokens an estimate would count.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "wall_min",
                "vm_min",
                "estimate_min",
                "claude_input_tokens",
                "model",
                "note"
            })
    void aMoveWithTimeTokensOrASessionsValuesIsRefused(final String field) throws Exception {
        final Map<String, @Nullable Object> record = moveRecord();
        record.put(field, field.equals("model") || field.equals("note") ? "x" : 1);

        assertThatThrownBy(() -> StageRecords.read(line(record), "EX12.jsonl", true, workflow()))
                .as("a move has none of them")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a move has no " + field);
    }

    // Prevents an open or stretched move, which no person makes, from being read as one.
    @Test
    void aMoveThatIsOpenOrEndsAfterItStartsIsRefused() throws Exception {
        final Map<String, @Nullable Object> open = moveRecord();
        open.put("end", null);
        open.put("status_after", null);
        final Map<String, @Nullable Object> stretched = moveRecord();
        stretched.put("end", AT.plusSeconds(60).toString());

        assertThatThrownBy(() -> StageRecords.read(line(open), "EX12.jsonl", false, workflow()))
                .as("open")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a move is never open");
        assertThatThrownBy(() -> StageRecords.read(line(stretched), "EX12.jsonl", true, workflow()))
                .as("stretched")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a move ends when it starts");
    }

    // Prevents an agent's line as the stage command writes it from being refused by the planner.
    @Test
    void anAgentsClosedLineAsTheStageCommandWritesItIsReadBack() throws Exception {
        final String text =
                "{\"issue\":\"EX12\",\"stage\":\"implement\",\"n\":1,\"actor\":\"agent\","
                        + "\"model\":\"example-model\",\"effort\":null,\"size\":\"M\","
                        + "\"status_before\":\"Implementing\",\"status_after\":\"Review\","
                        + "\"start\":\"2026-01-01T10:00:00Z\",\"end\":\"2026-01-01T10:04:00Z\","
                        + "\"wall_min\":4.0,\"vm_min\":0,\"estimate_min\":null,\"result\":\"done\","
                        + "\"wait_reason\":null,\"note\":null,\"claude_input_tokens\":12,"
                        + "\"claude_output_tokens\":3,\"claude_cache_read_tokens\":0,"
                        + "\"claude_cache_write_tokens\":0,\"claude_messages\":1,"
                        + "\"codex_input_tokens\":null,\"codex_cached_tokens\":null,"
                        + "\"codex_output_tokens\":null,\"codex_reasoning_tokens\":null,"
                        + "\"agy_input_tokens\":null,\"agy_cache_read_tokens\":null,"
                        + "\"agy_output_tokens\":null}\n";

        assertThat(StageRecords.read(text, "EX12.jsonl", true, workflow()))
                .as("one record, its values as written")
                .singleElement()
                .satisfies(
                        record ->
                                assertThat(record)
                                        .as("the agent's stage")
                                        .containsEntry("stage", "implement")
                                        .containsEntry("wall_min", 4.0));
    }

    // Prevents a stored record without its start from being taken as work.
    @Test
    void aRecordMissingFieldsIsRefused() {
        assertThatThrownBy(
                        () ->
                                StageRecords.read(
                                        "{\"n\":1,\"stage\":\"implement\",\"actor\":\"agent\","
                                                + "\"end\":\"2026-01-01T10:00:00Z\"}\n",
                                        "EX12.jsonl",
                                        true,
                                        workflow()))
                .as("refused rather than read in part")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown or missing record fields");
    }

    // Prevents records joined without newlines from being read as one, losing a neighbour.
    @Test
    void recordsSeparatedWithoutNewlinesAreRefused() throws Exception {
        final String text =
                measured("EX12", "plan", "agent", 4, "Implementing").stripTrailing()
                        + "\r"
                        + measured("EX12", "implement", "agent", 4, "Review");

        assertThatThrownBy(() -> StageRecords.read(text, "EX12.jsonl", true, workflow()))
                .as("invalid delimiters")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Prevents a record filed under another issue from counting for the wrong one.
    @Test
    void aRecordNamingAnotherIssueThanItsFileIsRefused() throws Exception {
        assertThatThrownBy(
                        () ->
                                StageRecords.read(
                                        measured("EX13", "plan", "agent", 4, "Review"),
                                        "EX12.jsonl",
                                        true,
                                        workflow()))
                .as("the file names the issue")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("record issue disagrees with filename");
    }

    // Prevents an open stage from passing where the records must be closed.
    @Test
    void anOpenStageIsRefusedWhereEveryRecordMustBeClosed() throws Exception {
        final Map<String, @Nullable Object> open =
                record("EX12", "implement", "agent", 4, "Review", "M");
        open.put("end", null);
        open.put("status_after", null);
        open.put("wall_min", null);
        open.put("vm_min", null);

        assertThat(StageRecords.read(line(open), "EX12.jsonl", false, workflow()))
                .as("read where an open stage may stand")
                .hasSize(1);
        assertThatThrownBy(() -> StageRecords.read(line(open), "EX12.jsonl", true, workflow()))
                .as("refused where every record must be closed")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stats file contains a stage without end");
    }

    // Prevents one issue an agent is working on from taking the whole repository's velocity away.
    @Test
    void anIssueWhoseLastStageIsOpenCountsForNothingAndTheOthersStillDo() throws Exception {
        final Map<String, @Nullable Object> open =
                record("EX14", "implement", "agent", 4, "Review", "M");
        open.put("end", null);
        open.put("status_after", null);
        open.put("wall_min", null);
        open.put("vm_min", null);
        final Map<String, String> files =
                Map.of(
                        "EX13.jsonl",
                        measured("EX13", "implement", "agent", 30, "Finished"),
                        "EX14.jsonl",
                        line(open));

        assertThat(StageRecords.statistic(SEED, files, "M", false, workflow()))
                .as("the median of the seed 53 and the finished 30, the open issue left out")
                .isEqualTo(41.5);
    }

    // Prevents the planner from committing a record its repository's commit check refuses.
    @Test
    void aMoveOntoARecordWithAnOpenStageIsRefused() throws Exception {
        final Map<String, @Nullable Object> open =
                record("EX12", "implement", "agent", 4, "Review", "M");
        open.put("end", null);
        open.put("status_after", null);
        open.put("wall_min", null);
        open.put("vm_min", null);

        assertThatThrownBy(
                        () ->
                                StageRecords.move(
                                        line(open),
                                        "EX12",
                                        "M",
                                        "Implementing",
                                        "Review",
                                        AT,
                                        workflow()))
                .as("refused, never appended after an open stage")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("stats file contains a stage without end");
    }

    // Prevents waits, analysis, subagents and unfinished issues from biasing an estimate.
    @Test
    void theMedianTakesTheSeedAndFinishedOwnWorkOnly() throws Exception {
        final Map<String, String> files =
                Map.of(
                        "EX13.jsonl",
                        measured("EX13", "plan", "agent", 10, "Implementing")
                                + measured("EX13", "implement", "agent", 30, "Implementing")
                                + measured("EX13", "wait", "agent", 100, "Implementing")
                                + measured("EX13", "review", "subagent", 100, "Implementing")
                                + measured("EX13", "handover", "agent", 5, "Finished"),
                        "EX14.jsonl",
                        measured("EX14", "analyze", "agent", 200, "Implementing")
                                + measured("EX14", "implement", "agent", 50, "Finished"),
                        "EX15.jsonl",
                        measured("EX15", "implement", "agent", 1000, "Review"));

        assertThat(StageRecords.statistic(SEED, files, "M", false, workflow()))
                .as(
                        "median of 45, 50, 53 excludes waits, analysis, subagents and unfinished issues")
                .isEqualTo(50.0);
    }

    // Prevents a person's move from adding minutes, or an issue a person finished on the board
    // from being left out of what a size took.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMoveDecidesWhetherAnIssueEndedButAddsNoMinutes(final boolean p80) throws Exception {
        final String finished =
                measured("EX13", "implement", "agent", 30, "Implementing")
                        + measured("EX13", "handover", "agent", 5, "Finished");
        final String reviewed = measured("EX14", "implement", "agent", 1000, "Review");
        final Map<String, String> without = Map.of("EX13.jsonl", finished, "EX14.jsonl", reviewed);
        final Map<String, String> with =
                Map.of(
                        "EX13.jsonl",
                        StageRecords.move(
                                finished, "EX13", "M", "Finished", "Review", AT, workflow()),
                        "EX14.jsonl",
                        StageRecords.move(
                                reviewed, "EX14", "M", "Review", "Finished", AT, workflow()),
                        "EX16.jsonl",
                        StageRecords.move(null, "EX16", "M", "Review", "Finished", AT, workflow()));

        assertThat(StageRecords.statistic(SEED, without, "M", p80, workflow()))
                .as("the seed's 53 and EX13's 35 minutes")
                .isEqualTo(p80 ? 53.0 : 44.0);
        assertThat(StageRecords.statistic(SEED, with, "M", p80, workflow()))
                .as(
                        "EX13 moved back out of Finished, EX14 moved into it with its 1000 minutes,"
                                + " EX16 with no minutes of its own: the seed's 53 and 1000")
                .isEqualTo(p80 ? 1000.0 : 526.5);
    }

    // Prevents rejected effort from biasing either estimate statistic.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedWorkDoesNotCountAsACompletedSample(final boolean p80) throws Exception {
        final Map<String, String> files =
                Map.of(
                        "EX13.jsonl", measured("EX13", "implement", "agent", 1000, "Rejected"),
                        "EX14.jsonl", measured("EX14", "implement", "agent", 1200, "Rejected"));

        assertThat(StageRecords.statistic(SEED, files, "M", p80, workflow()))
                .as("the seed alone")
                .isEqualTo(53.0);
    }

    // Prevents work under one size from counting for another.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finishedWorkIsGroupedByTheMeasuredStagesSize(final boolean p80) throws Exception {
        final Map<String, @Nullable Object> analysis =
                record("EX13", "analyze", "agent", 200, "Analyzed", null);
        analysis.put("status_before", "Accepted");
        final Map<String, String> files =
                Map.of(
                        "EX13.jsonl",
                        line(analysis)
                                + line(record("EX13", "plan", "agent", 10, "Implementing", "S"))
                                + line(record("EX13", "handover", "agent", 30, "Finished", "M")));

        assertThat(StageRecords.statistic(SEED, files, "M", p80, workflow()))
                .as("one M sample of 30 plus seed 53; S work and analysis are excluded")
                .isEqualTo(p80 ? 53.0 : 41.5);
    }

    // Prevents invalid historical records from biasing estimates.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompleteRecordsAreRefusedWhenCountingStatistics(final boolean p80) throws Exception {
        final Map<String, @Nullable Object> record =
                record("EX13", "implement", "agent", 4, "Finished", "M");
        record.remove("n");

        assertThatThrownBy(
                        () ->
                                StageRecords.statistic(
                                        SEED,
                                        Map.of("EX13.jsonl", line(record)),
                                        "M",
                                        p80,
                                        workflow()))
                .as("refused, not counted")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown or missing record fields");
    }

    // Prevents a malformed seed from passing for a velocity.
    @ParameterizedTest
    @ValueSource(strings = {"id,stage\n", "size,minutes\nM,x\n", "size,minutes\nM,1\nM,2\n", ""})
    void aSeedThatCannotBeReadIsRefused(final String seed) {
        assertThatThrownBy(() -> StageRecords.statistic(seed, Map.of(), "M", false, workflow()))
                .as("refused, never taken as no seed")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
