package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.fuin.porelo.planner.store.Worker;
import org.junit.jupiter.api.Test;

/** A head's state edited in place, and nowhere else in the file. */
class HeadEditTest {

    private static final String ISSUE =
            "# XY07 — Served\n\n```json\n{\n  \"$schema\": \"issue-1.json\",\n"
                    + "  \"id\": \"XY07\",\n  \"title\": \"Served\",\n"
                    + "  \"state\": \"Implementing\",\n  \"size\": \"S\",\n"
                    + "  \"priority\": \"Medium\",\n  \"rank\": 1,\n"
                    + "  \"blockedBy\": [],\n  \"covers\": \"Something.\"\n}\n```\n\n"
                    + "**What must be true.** It is served.\n";

    private static final String STORY =
            "# ST7 — Served\n\nA story served to the window.\n\n## Data\n\n```json\n{\n"
                    + "  \"$schema\": \"story-2.json\",\n  \"id\": \"ST7\",\n"
                    + "  \"title\": \"Served\",\n  \"gain\": \"Something.\",\n"
                    + "  \"test\": \"A test.\",\n  \"priority\": \"High\",\n"
                    + "  \"issues\": [\"porelo-store#XY07\"],\n  \"tested\": null\n}\n```\n";

    @Test
    void anIssuesStateIsReplacedInItsHeadAndTheRestOfTheFileStaysByteForByte() {
        final String edited = HeadEdit.issueState(ISSUE, "Review");
        assertThat(edited)
                .as("only the state line differs")
                .isEqualTo(ISSUE.replace("\"state\": \"Implementing\"", "\"state\": \"Review\""));
    }

    @Test
    void aStateInAFenceOfTheBodyIsNeverTouched() {
        // An edit outside the head would change the issue's text, not its state.
        final String body = ISSUE + "\n```json\n{\n  \"state\": \"Implementing\"\n}\n```\n";
        final String edited = HeadEdit.issueState(body, "Review");
        assertThat(edited).endsWith("```json\n{\n  \"state\": \"Implementing\"\n}\n```\n");
        assertThat(edited).contains("  \"state\": \"Review\",\n  \"size\"");
    }

    @Test
    void anIssueWhoseFileDoesNotOpenWithItsHeadIsRefused() {
        // A fence further down is the body's, and editing it would save a state nobody reads.
        assertThatThrownBy(
                        () ->
                                HeadEdit.issueState(
                                        "# XY07 — Served\n\nSome text first.\n\n"
                                                + ISSUE.substring(ISSUE.indexOf("```json")),
                                        "Review"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("head");
        assertThatThrownBy(() -> HeadEdit.issueState("# XY07 — Served\n\nNo head.\n", "Review"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHeadWithoutExactlyOneStateIsRefused() {
        final String twice =
                ISSUE.replace(
                        "  \"size\": \"S\",\n", "  \"state\": \"Backlog\",\n  \"size\": \"S\",\n");
        assertThatThrownBy(() -> HeadEdit.issueState(twice, "Review"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
        final String none = ISSUE.replace("  \"state\": \"Implementing\",\n", "");
        assertThatThrownBy(() -> HeadEdit.issueState(none, "Review"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStateThatIsNoPlainNameIsRefused() {
        // A quote or a line break in the value would write a head that no longer parses.
        assertThatThrownBy(() -> HeadEdit.issueState(ISSUE, "Re\"view"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HeadEdit.issueState(ISSUE, "Re\nview"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStorysStateIsInsertedUnderItsDataReplacedAndRemoved() {
        final String stated = HeadEdit.storyState(STORY, "Rejected");
        assertThat(stated)
                .as("inserted inside the head, before the issues")
                .contains("  \"priority\": \"High\",\n  \"state\": \"Rejected\",\n  \"issues\"");
        assertThat(HeadEdit.storyState(stated, "Review")).contains("  \"state\": \"Review\",\n");
        assertThat(HeadEdit.storyState(stated, null)).as("removed again").isEqualTo(STORY);
    }

    @Test
    void aStoryWithoutItsDataSectionIsRefused() {
        assertThatThrownBy(() -> HeadEdit.storyState(STORY.replace("## Data\n\n", ""), "Rejected"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIssuesWorkerIsInsertedLastAndReplacedLeavingTheRestOfTheFileByteForByte() {
        // A worker written elsewhere, or a body touched, would change more than who works it.
        final String manual = HeadEdit.issueWorker(ISSUE, Worker.MANUAL);
        assertThat(manual)
                .as("inserted after covers, the last member gaining its comma")
                .isEqualTo(
                        ISSUE.replace(
                                "  \"covers\": \"Something.\"\n}",
                                "  \"covers\": \"Something.\",\n  \"worker\": \"Manual\"\n}"));
        assertThat(HeadEdit.issueWorker(manual, Worker.AGENT))
                .as("replaced in its place")
                .isEqualTo(manual.replace("\"Manual\"", "\"Agent\""));
    }

    @Test
    void anIssuesWorkerIsInsertedBeforeItsRiskWhichStaysLast() {
        // A worker written after the risk would differ from the head the build tools write, and
        // their index check would rewrite the issue a person set.
        final String risked =
                ISSUE.replace(
                        "  \"covers\": \"Something.\"\n}",
                        "  \"covers\": \"Something.\",\n  \"risk\": \"harm\"\n}");
        assertThat(HeadEdit.issueWorker(risked, Worker.MANUAL))
                .as("inserted after covers and before the risk, which keeps its place")
                .isEqualTo(
                        ISSUE.replace(
                                "  \"covers\": \"Something.\"\n}",
                                "  \"covers\": \"Something.\",\n  \"worker\": \"Manual\",\n"
                                        + "  \"risk\": \"harm\"\n}"));
    }

    @Test
    void aHeadWithTwoWorkersIsRefused() {
        // Which of two a reader takes is not certain, so setting one would save nothing sure.
        final String twice =
                HeadEdit.issueWorker(ISSUE, Worker.MANUAL)
                        .replace(
                                "  \"size\": \"S\",\n",
                                "  \"worker\": \"Manual\",\n  \"size\": \"S\",\n");
        assertThatThrownBy(() -> HeadEdit.issueWorker(twice, Worker.AGENT))
                .as("two worker members")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker");
    }

    @Test
    void aStorysPriorityIsReplacedInItsHeadAndEveryOtherByteStays() {
        // A head written wrong breaks every reader of the story and of the stories index.
        final String edited = HeadEdit.storyPriority(STORY, "Lowest");
        assertThat(edited)
                .as("only the priority line differs")
                .isEqualTo(STORY.replace("\"priority\": \"High\"", "\"priority\": \"Lowest\""));
    }

    @Test
    void aPriorityInAnExampleAboveTheStorysOwnHeadIsNeverTouched() {
        // An edit of the body's example would change the person's text and leave the priority.
        final String shown =
                STORY.replace(
                        "A story served to the window.\n",
                        "A head reads:\n\n## Data\n\n```json\n{\n  \"priority\": \"Low\",\n"
                                + "  \"issues\": []\n}\n```\n");
        final String edited = HeadEdit.storyPriority(shown, "Highest");
        assertThat(edited)
                .as("the example keeps its priority, the head takes the new one")
                .isEqualTo(shown.replace("\"priority\": \"High\"", "\"priority\": \"Highest\""));
    }

    @Test
    void aStoryHeadWithoutExactlyOnePriorityIsRefused() {
        // Inserting one or guessing between two would save a priority no reader is sure of.
        final String none = STORY.replace("  \"priority\": \"High\",\n", "");
        assertThatThrownBy(() -> HeadEdit.storyPriority(none, "Low"))
                .as("no priority line")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priority");
        final String twice =
                STORY.replace(
                        "  \"priority\": \"High\",\n",
                        "  \"priority\": \"High\",\n  \"priority\": \"Low\",\n");
        assertThatThrownBy(() -> HeadEdit.storyPriority(twice, "Low"))
                .as("two priority lines")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priority");
    }

    @Test
    void aPriorityOutsideTheFiveIsRefused() {
        // A sixth value, or one with a quote, would write a head the stories index refuses.
        for (final String value : List.of("Urgent", "high", "Hi\"gh", "High\n", "")) {
            assertThatThrownBy(() -> HeadEdit.storyPriority(STORY, value))
                    .as("the priority %s", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> HeadEdit.storyPriority(STORY.replace("## Data\n\n", ""), "Low"))
                .as("a story without its data section")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
