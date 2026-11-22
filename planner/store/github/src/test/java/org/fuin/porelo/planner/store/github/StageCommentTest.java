package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.planner.store.StageEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A stage written as its comment, read back, and which stages add to an issue's fields. */
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
    void aHandEditedStageCommentIsReadAsBrokenAndNeverEchoed() {
        // Harm: a hand edit on GitHub crashing a read of the course of an issue, taken as a stage
        // with negative or infinite figures, or its text leaking into a fault.
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
        int number = 0;
        for (final Map.Entry<String, String> edit : edits.entrySet()) {
            assertThat(edit.getValue())
                    .as("the edit %s changes the comment", edit.getKey())
                    .isNotEqualTo(written);
            final String id = "IC_" + ++number;
            final CommentView comment = new CommentView(id, edit.getValue(), END);
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
        assertThat(StageComment.read(new CommentView("IC_99", written, END)))
                .as("the intact stage")
                .get()
                .isInstanceOf(StageComment.Stage.class);
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
    void onlyAnAgentsMeasuredStagesAddToTheFields() {
        // Harm: waits, a person's moves or an estimate's own time counted as work, so the
        // velocity of a size is wrong.
        assertThat(StageEntry.MEASURED)
                .as("the measured stages")
                .containsExactlyInAnyOrder(
                        "plan", "implement", "test", "review", "rework", "handover");
        assertThat(StageComment.summed(stage("task-1:6", "implement", "agent", 30)))
                .as("an agent's implementation")
                .isTrue();
        assertThat(StageComment.summed(stage("task-1:5", "review", "agent", 11)))
                .as("an agent's review")
                .isTrue();
        assertThat(StageComment.summed(stage("task-1:1", "estimate", "agent", 7)))
                .as("an estimate")
                .isFalse();
        assertThat(StageComment.summed(stage("task-1:2", "wait", "agent", 120)))
                .as("a wait")
                .isFalse();
        assertThat(StageComment.summed(stage("task-1:3", "move", "person", 1)))
                .as("a person's move")
                .isFalse();
        assertThat(StageComment.summed(stage("task-1:4", "implement", "subagent", 13)))
                .as("a sub-agent's implementation")
                .isFalse();
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
