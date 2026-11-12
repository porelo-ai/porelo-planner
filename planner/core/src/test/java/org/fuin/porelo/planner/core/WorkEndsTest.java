package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.porelo.planner.core.WorkEnds.Done;
import org.fuin.porelo.planner.core.WorkEnds.Finding;
import org.fuin.porelo.planner.core.WorkEnds.NeedsOperator;
import org.fuin.porelo.planner.core.WorkEnds.Outcome;
import org.fuin.porelo.planner.core.WorkEnds.Read;
import org.fuin.porelo.planner.core.WorkEnds.Reason;
import org.fuin.porelo.planner.core.WorkEnds.Severity;
import org.junit.jupiter.api.Test;

/** A task's end judged once: a reader's findings read strictly, every other end told as it is. */
class WorkEndsTest {

    @Test
    void anUnfinishedOrUnpushedTaskNeedsTheOperatorAndItsWordsNeverBecomeFindings() {
        // Text of a failed reader taken as findings would pass or rework an issue nobody read.
        final String answer = "I stopped.\nFindings:\n1. note | - | - | Looks fine.";
        assertThat(WorkEnds.judge("READER", Outcome.of(false, 0), answer))
                .as("not finished")
                .isEqualTo(new NeedsOperator(Reason.FAILED));
        assertThat(WorkEnds.judge("READER", Outcome.of(true, 2), answer))
                .as("finished with commits left unpushed")
                .isEqualTo(new NeedsOperator(Reason.UNPUSHED));
        assertThat(WorkEnds.judge("BUILDER", Outcome.of(true, 1), ""))
                .as("a builder whose work did not arrive")
                .isEqualTo(new NeedsOperator(Reason.UNPUSHED));
        assertThat(WorkEnds.judge("BUILDER", Outcome.of(true, 0), ""))
                .as("finished and pushed")
                .isEqualTo(new Done());
    }

    @Test
    void aBuildersSuccessIsDoneWhateverItsAnswerSays() {
        // A builder writing a findings block must not decide its own reading.
        assertThat(WorkEnds.judge("BUILDER", Outcome.DONE, "Findings:\n"))
                .as("a builder's end")
                .isEqualTo(new Done());
    }

    @Test
    void aReadersBlockGivesItsFindingsAndAPipeInTheTextStaysText() {
        // A finding cut at a pipe would lose what the reader said.
        final Read read =
                (Read)
                        WorkEnds.judge(
                                "READER",
                                Outcome.DONE,
                                "I read it.\n\nFindings:\n"
                                        + "1. blocking | core/A.java | 12 | a | b stays\n"
                                        + "2. note | - | - | wording\n\n");
        assertThat(read.findings())
                .as("both findings, the text whole")
                .containsExactly(
                        new Finding(1, Severity.BLOCKING, "core/A.java", 12, "a | b stays"),
                        new Finding(2, Severity.NOTE, null, null, "wording"));
        assertThat(read.blocking()).as("one blocks").isTrue();
    }

    @Test
    void anEmptyBlockIsACleanReading() {
        // A clean reading taken as malformed would hold every issue under Needs you.
        final Read read = (Read) WorkEnds.judge("READER", Outcome.DONE, "All good.\nFindings:\n");
        assertThat(read.findings()).as("no findings").isEmpty();
        assertThat(read.blocking()).as("nothing blocks").isFalse();
    }

    @Test
    void aBlockThatSaysNoneIsACleanReading() {
        // A reader that writes "(none)" for a clean reading, as a real reader did, would stop for
        // the person.
        for (final String answer :
                List.of(
                        "Read it.\n\nFindings: (none)",
                        "Findings: none\n",
                        "Findings:\n(none)\n",
                        "Findings:\nNone")) {
            final WorkEnds.Judgment judged = WorkEnds.judge("READER", Outcome.DONE, answer);
            assertThat(judged).as("a reading: %s", answer).isInstanceOf(Read.class);
            assertThat(((Read) judged).findings()).as("no findings: %s", answer).isEmpty();
        }
    }

    @Test
    void anAnswerWithoutOneWellFormedBlockCountsNoReading() {
        // A malformed reading passed on would reach Review without a reading anyone could check.
        for (final String answer :
                List.of(
                        "No block at all.",
                        "Findings: (none)\n1. blocking | - | - | none beside a finding",
                        "Findings:\n(none)\n1. blocking | - | - | none beside a finding",
                        "Findings:\n1. blocking | - | - | a finding\n(none)",
                        "Findings: (none)\nFindings:\n",
                        "Findings: two words",
                        "Findings:\nFindings:\n",
                        "Findings:\n1. note | - | - | a\nFindings:\n",
                        "Findings:\n2. note | - | - | skips one",
                        "Findings:\n1. note | - | - | a\n\n2. note | - | - | gap",
                        "Findings:\n1. minor | - | - | unknown severity",
                        "Findings:\n1. note | - | 3 | a line without a file",
                        "Findings:\n1. note | a.txt | 0 | line zero",
                        "Findings:\n1. note | a.txt | 99999999999 | too far",
                        "Findings:\n1. note | - | - |   ",
                        "Findings:\n1. note | a\u0007b | - | control in the file",
                        "Findings:\n1. note | /etc/passwd | - | a root",
                        "Findings:\n1. note | \\share\\x | - | a root",
                        "Findings:\n1. note | a/../b | - | a step up",
                        "Findings:\n1. note | a\\..\\b | - | a step up",
                        "Findings:\n1. note | - | -",
                        "Findings:\n1. note | " + "f".repeat(513) + " | - | long file",
                        "Findings:\n1. note | - | - | " + "x".repeat(4001),
                        "x".repeat(64 * 1024) + "\nFindings:\n")) {
            assertThat(WorkEnds.judge("READER", Outcome.DONE, answer))
                    .as("refused: %s", answer.length() > 80 ? answer.substring(0, 80) : answer)
                    .isEqualTo(new NeedsOperator(Reason.INVALID_FINDINGS));
        }
    }

    @Test
    void theMostFindingsAreReadAndOneMoreIsRefused() {
        // An unbounded block would grow the record and the board past any limit.
        final StringBuilder answer = new StringBuilder("Findings:\n");
        for (int i = 1; i <= WorkEnds.MOST_FINDINGS; i++) {
            answer.append(i).append(". note | - | - | n\n");
        }
        assertThat(WorkEnds.judge("READER", Outcome.DONE, answer.toString()))
                .as("the most")
                .isInstanceOf(Read.class);
        answer.append(WorkEnds.MOST_FINDINGS + 1).append(". note | - | - | n\n");
        assertThat(WorkEnds.judge("READER", Outcome.DONE, answer.toString()))
                .as("one more")
                .isEqualTo(new NeedsOperator(Reason.INVALID_FINDINGS));
    }
}
