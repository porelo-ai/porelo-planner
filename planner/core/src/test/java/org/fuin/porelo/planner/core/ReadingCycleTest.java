package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.fuin.porelo.planner.core.ReadingCycle.Choice;
import org.fuin.porelo.planner.core.ReadingCycle.Counted;
import org.fuin.porelo.planner.core.ReadingCycle.Next;
import org.fuin.porelo.planner.core.ReadingCycle.Rounds;
import org.fuin.porelo.planner.core.WorkEnds.Finding;
import org.fuin.porelo.planner.core.WorkEnds.Read;
import org.fuin.porelo.planner.core.WorkEnds.Severity;
import org.junit.jupiter.api.Test;

/** An issue's readings counted against its limit, and the operator's three choices at it. */
class ReadingCycleTest {

    private static final Read BLOCKING =
            new Read(List.of(new Finding(1, Severity.BLOCKING, null, null, "broken")));

    private static final Read NOTES =
            new Read(List.of(new Finding(1, Severity.NOTE, null, null, "a word")));

    @Test
    void blockingFindingsReworkWhileReadingsAreLeftAndStopAtTheLimit() {
        // Work going on past the limit would spend the person's agents without their word.
        final Counted first = ReadingCycle.count(Rounds.start(2), BLOCKING);
        assertThat(first.next()).as("a reading left").isEqualTo(Next.REWORK);
        assertThat(first.rounds().rounds()).as("one counted").isEqualTo(1);

        final Counted second = ReadingCycle.count(first.rounds(), BLOCKING);
        assertThat(second.next()).as("the last allowed reading stops").isEqualTo(Next.STOP);
        assertThat(second.rounds()).as("stopped at two of two").isEqualTo(new Rounds(2, 2, true));
        assertThatThrownBy(() -> ReadingCycle.count(second.rounds(), NOTES))
                .as("no reading ends while the operator decides")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void notesOrNoFindingsReachReviewEvenOnTheLastReading() {
        // A clean last reading held for the operator would stop work that is done.
        final Rounds last = new Rounds(1, 2, false);
        assertThat(ReadingCycle.count(last, NOTES).next()).as("notes").isEqualTo(Next.REVIEW);
        assertThat(ReadingCycle.count(last, new Read(List.of())).next())
                .as("nothing found")
                .isEqualTo(Next.REVIEW);
    }

    @Test
    void theStartingLimitIsBounded() {
        // An unbounded limit would let a story's work read and rework without end.
        assertThat(Rounds.start(ReadingCycle.MOST_START).roundsMost()).as("the most").isEqualTo(10);
        assertThatThrownBy(() -> Rounds.start(ReadingCycle.MOST_START + 1)).as("one more");
        assertThatThrownBy(() -> Rounds.start(0)).as("none");
    }

    @Test
    void theThreeChoicesAtTheLimit() {
        // A choice counted as a reading, or one granting nothing, would stop the issue again at
        // once.
        final Rounds stopped = new Rounds(2, 2, true);
        final Counted review = ReadingCycle.choose(stopped, 2, Choice.REVIEW_ANYWAY, "");
        assertThat(review.next()).as("review anyway").isEqualTo(Next.REVIEW);
        assertThat(review.rounds()).as("nothing counted").isEqualTo(new Rounds(2, 2, false));

        final Counted more = ReadingCycle.choose(stopped, 2, Choice.ONE_MORE_ROUND, "");
        assertThat(more.next()).as("one more reading").isEqualTo(Next.READ);
        assertThat(more.rounds())
                .as("one more allowed, none counted yet")
                .isEqualTo(new Rounds(2, 3, false));

        final Counted rework = ReadingCycle.choose(stopped, 2, Choice.REWORK, "Fix the parser.");
        assertThat(rework.next()).as("rework").isEqualTo(Next.REWORK);
        assertThat(rework.rounds()).as("one more allowed").isEqualTo(new Rounds(2, 3, false));
    }

    @Test
    void aChoiceOnAReadingNotStoppedOrOnAnOldRoundOrAReworkWithoutWordsIsRefused() {
        // A stale or doubled choice would grant readings twice or rework without the person's
        // words.
        assertThatThrownBy(
                        () ->
                                ReadingCycle.choose(
                                        new Rounds(1, 2, false), 1, Choice.REVIEW_ANYWAY, ""))
                .as("not stopped")
                .hasMessage("ReadingNotStopped");
        assertThatThrownBy(
                        () ->
                                ReadingCycle.choose(
                                        new Rounds(3, 3, true), 2, Choice.ONE_MORE_ROUND, ""))
                .as("an old round")
                .hasMessage("ReadingRoundChanged");
        assertThatThrownBy(
                        () -> ReadingCycle.choose(new Rounds(2, 2, true), 2, Choice.REWORK, "  "))
                .as("no words")
                .hasMessage("ReworkNeedsWords");
        final Rounds most = new Rounds(Integer.MAX_VALUE, Integer.MAX_VALUE, true);
        assertThatThrownBy(
                        () ->
                                ReadingCycle.choose(
                                        most, Integer.MAX_VALUE, Choice.ONE_MORE_ROUND, ""))
                .as("no grant past the largest count")
                .hasMessage("ReadingRoundChanged");
    }
}
