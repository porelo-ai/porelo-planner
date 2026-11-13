package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.porelo.planner.core.WorkEnds.Declared;
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

    // Harm: a clean reading held in Reading for files the reader only checked out to read.
    @Test
    void aFinishedReaderWithUnpushedFilesIsJudgedByItsFindings() {
        assertThat(WorkEnds.judge("READER", Outcome.of(true, 4), "Read it.\nFindings:"))
                .as("a clean reading, whatever the reader left in its workspace")
                .isEqualTo(new Read(List.of()));
        assertThat(
                        WorkEnds.judge(
                                "READER",
                                Outcome.of(true, 4),
                                "Findings:\n1. blocking | a.txt | 3 | Broken."))
                .as("blocking findings, whatever the reader left in its workspace")
                .isEqualTo(
                        new Read(
                                List.of(new Finding(1, Severity.BLOCKING, "a.txt", 3, "Broken."))));
        assertThat(WorkEnds.judge("BUILDER", Outcome.of(true, 4), ""))
                .as("a builder's unpushed work still needs the person")
                .isEqualTo(new NeedsOperator(Reason.UNPUSHED));
    }

    @Test
    void anUnfinishedTaskOrAnUnpushedBuilderNeedsTheOperatorAndItsWordsNeverBecomeFindings() {
        // Text of a failed reader taken as findings would pass or rework an issue nobody read.
        final String answer = "I stopped.\nFindings:\n1. note | - | - | Looks fine.";
        assertThat(WorkEnds.judge("READER", Outcome.of(false, 0), answer))
                .as("not finished")
                .isEqualTo(new NeedsOperator(Reason.FAILED));
        assertThat(WorkEnds.judge("BUILDER", Outcome.of(true, 1), ""))
                .as("a builder whose work did not arrive")
                .isEqualTo(new NeedsOperator(Reason.UNPUSHED));
        assertThat(WorkEnds.judge("BUILDER", Outcome.of(true, 0), ""))
                .as("finished and pushed")
                .isEqualTo(new Done());
    }

    @Test
    void aDeclaredBlockOrFailureNeedsTheOperatorWhateverTheRunSaysAndADeclaredDoneDecidesNothing() {
        // A blocked or failed task taken as done moves on work that is not there; a declared DONE
        // over a run that did not finish or push would do the same.
        assertThat(Outcome.of(true, 0, Declared.BLOCKED))
                .as("finished and pushed, declared blocked")
                .isEqualTo(Outcome.BLOCKED);
        assertThat(Outcome.of(false, 3, Declared.BLOCKED))
                .as("unfinished and unpushed, declared blocked")
                .isEqualTo(Outcome.BLOCKED);
        assertThat(Outcome.of(true, 0, Declared.FAILED))
                .as("finished and pushed, declared failed")
                .isEqualTo(Outcome.FAILED);
        assertThat(Outcome.of(false, 0, Declared.DONE))
                .as("unfinished, declared done")
                .isEqualTo(Outcome.FAILED);
        assertThat(Outcome.of(true, 2, Declared.DONE))
                .as("unpushed, declared done")
                .isEqualTo(Outcome.UNPUSHED);
        assertThat(Outcome.of(true, 0, Declared.DONE))
                .as("finished and pushed, declared done")
                .isEqualTo(Outcome.DONE);
        assertThat(Outcome.of(true, 0, null))
                .as("finished and pushed, nothing declared")
                .isEqualTo(Outcome.DONE);
        assertThat(WorkEnds.judge("READER", Outcome.BLOCKED, "Findings: none"))
                .as("a reader's clean block from a blocked end")
                .isEqualTo(new NeedsOperator(Reason.BLOCKED));
        assertThat(WorkEnds.judge("BUILDER", Outcome.BLOCKED, ""))
                .as("a blocked builder")
                .isEqualTo(new NeedsOperator(Reason.BLOCKED));
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
    void aLineRangeIsReadAsItsFirstLineWithTheRangeKeptInTheText() {
        // Harm: a reading's blocking findings thrown away whole because one row names a range.
        final Read read =
                (Read)
                        WorkEnds.judge(
                                "READER",
                                Outcome.DONE,
                                "Findings:\n"
                                        + "1. blocking | core/A.java | 34-40 | a leak\n"
                                        + "2. note | b.txt | 7-7 | one line\n"
                                        + "3. note | c.txt | 1-2147483647 | all of it\n");
        assertThat(read.findings())
                .as("the first line as the line, the range in the text")
                .containsExactly(
                        new Finding(1, Severity.BLOCKING, "core/A.java", 34, "lines 34-40: a leak"),
                        new Finding(2, Severity.NOTE, "b.txt", 7, "lines 7-7: one line"),
                        new Finding(3, Severity.NOTE, "c.txt", 1, "lines 1-2147483647: all of it"));
        assertThat(read.blocking()).as("the range's finding still blocks").isTrue();
    }

    @Test
    void aMalformedLineRangeCountsNoReading() {
        // Harm: a malformed block taken in as a reading nobody could check.
        for (final String range :
                List.of(
                        "40-34",
                        "0-3",
                        "3-",
                        "-3",
                        "a-b",
                        "3-0",
                        "1-2147483648",
                        "1--3",
                        "1-2-3",
                        "3 - 4")) {
            assertThat(
                            WorkEnds.judge(
                                    "READER",
                                    Outcome.DONE,
                                    "Findings:\n1. note | a.txt | " + range + " | x\n"))
                    .as("refused: %s", range)
                    .isEqualTo(new NeedsOperator(Reason.INVALID_FINDINGS));
        }
        assertThat(WorkEnds.judge("READER", Outcome.DONE, "Findings:\n1. note | - | 3-4 | x\n"))
                .as("a range without a file is refused like a line without one")
                .isEqualTo(new NeedsOperator(Reason.INVALID_FINDINGS));
    }

    @Test
    void blankLinesBetweenTheHeaderAndTheFirstRowAreSkipped() {
        // Harm: a reader's blocking findings thrown away whole for the blank line Markdown writes
        // under a heading.
        final Read read =
                (Read)
                        WorkEnds.judge(
                                "READER",
                                Outcome.DONE,
                                "Reported as blocking.\n\nFindings:\n\n \n"
                                        + "1. blocking | src/A.java | 26 | a fault\n"
                                        + "2. note | - | - | a verdict\n\n");
        assertThat(read.findings())
                .as("both rows read")
                .containsExactly(
                        new Finding(1, Severity.BLOCKING, "src/A.java", 26, "a fault"),
                        new Finding(2, Severity.NOTE, null, null, "a verdict"));
        final WorkEnds.Judgment none =
                WorkEnds.judge("READER", Outcome.DONE, "Findings:\n\n(none)\n");
        assertThat(none)
                .as("a none under a blank line is a clean reading")
                .isInstanceOf(Read.class);
        assertThat(((Read) none).findings()).as("no findings").isEmpty();
    }

    @Test
    void aFirstRowOnTheHeaderLineIsReadWithTheRowsBelowIt() {
        // Harm: a reader's blocking findings thrown away whole because it began them on the header
        // line.
        final Read read =
                (Read)
                        WorkEnds.judge(
                                "READER",
                                Outcome.DONE,
                                "It is a blocking finding.\n\nFindings: 1. blocking | src/A.java | 26 | a"
                                        + " fault\n2. note | - | - | a verdict\n");
        assertThat(read.findings())
                .as("the header line's row and the one below it")
                .containsExactly(
                        new Finding(1, Severity.BLOCKING, "src/A.java", 26, "a fault"),
                        new Finding(2, Severity.NOTE, null, null, "a verdict"));
        for (final String answer :
                List.of(
                        "Findings: 1. blocking | - | - | a\n\n2. note | - | - | gap",
                        "Findings: 2. note | - | - | starts at two",
                        "Findings: see below\n1. note | - | - | a")) {
            assertThat(WorkEnds.judge("READER", Outcome.DONE, answer))
                    .as("refused: %s", answer)
                    .isEqualTo(new NeedsOperator(Reason.INVALID_FINDINGS));
        }
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
                        "Findings: (none)\n\n1. blocking | - | - | none above a gap",
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
