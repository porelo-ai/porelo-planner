package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.StageEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A stage written as its comment, read back, and the sums an issue's stage comments add up to. */
class StageCommentTest {

    private static final Instant END = Instant.parse("2026-11-20T09:44:45Z");

    @Test
    void aStageWrittenWithItsBaseIsReadBackWithIt() {
        // Harm: a stage handed in again cannot tell whether its figures were added, as the
        // fields they were added to are lost in the comment.
        final StageEntry entry = stage("task-1:3", "implement", "agent", 30);
        final StageComment.Base base = new StageComment.Base(10, 0.5, 300, 2);

        final StageComment.Read read =
                StageComment.read(new CommentView("IC_1", StageComment.write(entry, base), END))
                        .orElseThrow();

        assertThat(read).isEqualTo(new StageComment.Stage(entry, END, base));
    }

    @Test
    void aStageCommentWhoseBaseIsNotFourNumbersIsBroken() {
        // Harm: a base edited by hand taken for the fields a stage was added to.
        final String written =
                StageComment.write(
                        stage("task-1:3", "implement", "agent", 30),
                        new StageComment.Base(10, 0.5, 300, 2));

        assertThat(
                        StageComment.read(
                                new CommentView(
                                        "IC_1",
                                        written.replace("\"stages\" : 2.0", "\"stages\" : \"two\""),
                                        END)))
                .as("a base with text for a figure")
                .containsInstanceOf(StageComment.Broken.class);
    }

    @Test
    void aStageWrittenAndReadBackIsTheSameStageEndingWhenTheCommentWasMade() {
        // Harm: the course of an issue read back other than the planner wrote it, so the
        // velocity counts other minutes.
        final StageEntry entry = stage("task-1:3", "implement", "agent", 30);

        final StageComment.Read read =
                StageComment.read(new CommentView("IC_1", StageComment.write(entry, null), END))
                        .orElseThrow();

        assertThat(read)
                .as("the stage, its end the comment's createdAt")
                .isEqualTo(new StageComment.Stage(entry, END, null));
        assertThat(((StageComment.Stage) read).start())
                .as("its start its end less its minutes")
                .isEqualTo(END.minusSeconds(30 * 60));
        assertThat(StageComment.write(entry, null))
                .as("the marker as the first line, then the words, then the JSON block")
                .startsWith(StageComment.MARKER + "\nimplement: done\n```json\n{");
    }

    @Test
    void aCommentWithoutTheMarkerAsItsFirstLineIsNoStageComment() {
        // Harm: a person's own comment counted, or named as broken, in an issue's sums.
        final String written =
                StageComment.write(stage("task-1:3", "implement", "agent", 30), null);

        assertThat(StageComment.read(new CommentView("IC_1", "Looks fine to me.", END)))
                .as("a person's comment")
                .isEmpty();
        assertThat(StageComment.read(new CommentView("IC_1", "Quoted:\n" + written, END)))
                .as("a stage comment quoted below a line of its own")
                .isEmpty();
    }

    @Test
    void aHandEditedStageCommentIsAFaultLeftOutOfTheSumsAndNeverEchoed() {
        // Harm: a hand edit on GitHub crashing the board, turning the sums negative or infinite,
        // or its text leaking into a fault.
        final String written =
                StageComment.write(stage("task-1:3", "implement", "agent", 30), null);
        final Map<String, String> edits = new LinkedHashMap<>();
        edits.put(
                "negative minutes",
                written.replaceFirst("\"wall_min\" : [0-9.]+", "\"wall_min\" : -30.0"));
        edits.put(
                "an unknown field",
                written.replace("\"key\" :", "\"leaked_field_name\" : 1,\n  \"key\" :"));
        edits.put("broken JSON", written.replace("\"stage\" :", "\"stage\" leaked-json-text"));
        edits.put(
                "an overflowing token count",
                written.replaceFirst(
                        "\"claude_input_tokens\" : [0-9]+",
                        "\"claude_input_tokens\" : 99999999999999999999999"));
        edits.put("a renamed field", written.replace("\"model\" :", "\"leaked_model_name\" :"));
        edits.put(
                "a token count above what JSON carries exactly",
                written.replaceFirst(
                        "\"claude_input_tokens\" : [0-9]+",
                        "\"claude_input_tokens\" : 9007199254740992"));
        edits.put(
                "an infinite number",
                written.replaceFirst("\"vm_min\" : [0-9.]+", "\"vm_min\" : 1e999"));
        // Blank lines after the block are allowed, so only the bound refuses this one.
        edits.put("a huge comment", written + " \n".repeat(StageComment.LONGEST / 2));
        edits.put("no closing fence", written.replace("}\n```\n", "}\n"));
        final List<CommentView> comments = new ArrayList<>();
        int number = 0;
        for (final Map.Entry<String, String> edit : edits.entrySet()) {
            assertThat(edit.getValue())
                    .as("the edit %s changes the comment", edit.getKey())
                    .isNotEqualTo(written);
            final String id = "IC_" + ++number;
            final CommentView comment = new CommentView(id, edit.getValue(), END);
            comments.add(comment);
            assertThat(StageComment.read(comment))
                    .as("a stage comment with %s", edit.getKey())
                    .get()
                    .isInstanceOfSatisfying(
                            StageComment.Broken.class,
                            broken ->
                                    assertThat(broken.fault())
                                            .as("the fault for %s", edit.getKey())
                                            .contains(id)
                                            .doesNotContain("leaked")
                                            .doesNotContain("task-1:3"));
        }
        comments.add(new CommentView("IC_99", written, END));

        final StageComment.Sums sums = StageComment.sums(comments);

        assertThat(sums.minutes()).as("only the intact stage's minutes").isEqualTo(30);
        assertThat(sums.stages()).as("only the intact stage").isEqualTo(1);
        assertThat(sums.faults()).as("one fault per broken comment").hasSize(edits.size());
    }

    @Test
    void aTokenSumThatWouldOverflowIsAFaultAndNeverANegativeSum() {
        // Harm: stages whose tokens add up past what a long holds turning an issue's token sum
        // negative, or crashing the board.
        final Map<String, Long> tokens = new LinkedHashMap<>();
        StageComment.NEW_TOKENS.forEach(kind -> tokens.put(kind, 9_007_199_254_740_991L));
        final List<CommentView> comments = new ArrayList<>();
        for (int number = 1; number <= 200; number++) {
            comments.add(
                    new CommentView(
                            "IC_" + number,
                            StageComment.write(
                                    new StageEntry(
                                            "task-1:" + number,
                                            "implement",
                                            "done",
                                            "agent",
                                            "claude-opus",
                                            "high",
                                            1,
                                            0,
                                            tokens),
                                    null),
                            END));
        }

        final StageComment.Sums sums = StageComment.sums(comments);

        assertThat(sums.tokens()).as("the token sum").isNotNegative();
        assertThat(sums.faults())
                .as("the stages that would make it overflow")
                .isNotEmpty()
                .allMatch(fault -> fault.contains("makes the token sum overflow"));
        assertThat(sums.stages() + sums.faults().size())
                .as("every stage summed or named")
                .isEqualTo(200);
    }

    @Test
    void aCommentIdThatIsNoGitHubIdIsNotNamedInAFault() {
        // Harm: a fake or forged id carrying text into a fault the window shows.
        final StageComment.Read read =
                StageComment.read(
                                new CommentView(
                                        "leaked id <script>", StageComment.MARKER + "\nx", END))
                        .orElseThrow();

        assertThat(((StageComment.Broken) read).fault())
                .as("the fault for a comment with an odd id")
                .startsWith("A stage comment")
                .doesNotContain("leaked");
    }

    @Test
    void twoCommentsWithOneKeyAreSummedOnceTheFirstCounting() {
        // Harm: a retried stage counted twice, doubling an issue's minutes in the velocity.
        final StageEntry first = stage("task-1:3", "implement", "agent", 30);
        final StageEntry retry = stage("task-1:3", "implement", "agent", 45);

        final StageComment.Sums sums =
                StageComment.sums(
                        List.of(
                                new CommentView("IC_1", StageComment.write(first, null), END),
                                new CommentView("IC_2", StageComment.write(retry, null), END)));

        assertThat(sums.minutes()).as("the first comment's minutes alone").isEqualTo(30);
        assertThat(sums.stages()).as("one stage").isEqualTo(1);
        assertThat(sums.tokens()).as("its new tokens once").isEqualTo(100 + 20 + 5);
    }

    @Test
    void onlyAnAgentsMeasuredStagesAreSummed() {
        // Harm: waits, a person's moves or an estimate's own time counted as work, so the
        // velocity of a size is wrong.
        final StageComment.Sums sums =
                StageComment.sums(
                        List.of(
                                comment(stage("task-1:1", "estimate", "agent", 7)),
                                comment(stage("task-1:2", "wait", "agent", 120)),
                                comment(stage("task-1:3", "move", "person", 1)),
                                comment(stage("task-1:4", "implement", "subagent", 13)),
                                comment(stage("task-1:5", "review", "agent", 11)),
                                comment(stage("task-1:6", "implement", "agent", 30))));

        assertThat(StageEntry.MEASURED)
                .as("the measured stages")
                .containsExactlyInAnyOrder(
                        "plan", "implement", "test", "review", "rework", "handover");
        assertThat(sums.minutes()).as("the review's and the implementation's").isEqualTo(41);
        assertThat(sums.vmMinutes()).as("their machine minutes").isEqualTo(4);
        assertThat(sums.stages()).as("two stages").isEqualTo(2);
        assertThat(sums.tokens()).as("their new tokens").isEqualTo(2 * (100 + 20 + 5));
        assertThat(sums.faults()).as("no fault").isEmpty();
    }

    private static CommentView comment(final StageEntry entry) {
        return new CommentView(
                "IC_" + entry.key().hashCode(), StageComment.write(entry, null), END);
    }

    private static StageEntry stage(
            final String key, final String kind, final String actor, final double minutes) {
        final @Nullable String model = "person".equals(actor) ? null : "claude-opus";
        final Map<String, Long> tokens = new LinkedHashMap<>();
        if (model != null) {
            tokens.put("claude_input_tokens", 100L);
            tokens.put("claude_output_tokens", 20L);
            tokens.put("claude_cache_read_tokens", 9_000L);
            tokens.put("claude_cache_write_tokens", 5L);
        }
        return new StageEntry(
                key, kind, "done", actor, model, model == null ? null : "high", minutes, 2, tokens);
    }
}
