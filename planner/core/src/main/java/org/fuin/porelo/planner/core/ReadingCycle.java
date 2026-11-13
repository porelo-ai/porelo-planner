package org.fuin.porelo.planner.core;

import java.util.List;
import java.util.Objects;

/**
 * Counts an issue's readings against its limit and decides where a reading leads: Review on a
 * reading without blocking findings, and on every reading with blocking findings a stop in Reading
 * under Needs you, where the operator chooses; the limit only names why the reading stopped.
 */
public final class ReadingCycle {

    /** The most readings the operator may grant at the start. */
    public static final int MOST_START = 10;

    private ReadingCycle() {}

    /**
     * An issue's readings.
     *
     * @param rounds the readings counted, each a successfully read reading
     * @param roundsMost the readings allowed
     * @param stopped whether the issue waits in Reading for the operator's choice
     */
    public record Rounds(int rounds, int roundsMost, boolean stopped) {

        /**
         * Checks the counts.
         *
         * @param rounds the readings counted
         * @param roundsMost the readings allowed
         * @param stopped whether the issue waits for the operator
         */
        public Rounds {
            if (rounds < 0 || roundsMost < 1) {
                throw new IllegalArgumentException(
                        "Readings are counted from zero, allowed from one");
            }
        }

        /**
         * The rounds of an issue whose work starts with the given limit.
         *
         * @param roundsMost the readings allowed, one to {@link #MOST_START}
         * @return no reading counted yet
         */
        public static Rounds start(final int roundsMost) {
            if (roundsMost < 1 || roundsMost > MOST_START) {
                throw new IllegalArgumentException(
                        "A reading limit is from 1 to " + MOST_START + ".");
            }
            return new Rounds(0, roundsMost, false);
        }
    }

    /**
     * Names why a reading stopped.
     *
     * @param rounds the issue's rounds after the stopped reading
     * @return {@code ROUND_LIMIT} when the readings allowed are used up, otherwise {@code
     *     BLOCKING_FINDINGS}
     */
    public static String stopReason(final Rounds rounds) {
        return rounds.rounds() >= rounds.roundsMost() ? "ROUND_LIMIT" : "BLOCKING_FINDINGS";
    }

    /** Where a reading leads. */
    public enum Next {
        /** On to Review, the findings going with the issue. */
        REVIEW,
        /** Back to Reworking with the blocking findings, or the operator's words. */
        REWORK,
        /** One more reading of the issue as it stands. */
        READ,
        /** Blocking findings: the issue stays in Reading, nobody at work, under Needs you. */
        STOP
    }

    /**
     * A reading counted and where it leads.
     *
     * @param rounds the issue's rounds after it
     * @param next where it leads
     */
    public record Counted(Rounds rounds, Next next) {}

    /** The operator's choice for a stopped reading. */
    public enum Choice {
        /** Review anyway, the findings going with the issue. */
        REVIEW_ANYWAY,
        /** One more reading. */
        ONE_MORE_ROUND,
        /** Rework with the blocking findings and the operator's words, if any. */
        REWORK
    }

    /**
     * Counts one reading, judged once from its end.
     *
     * @param rounds the issue's rounds before it
     * @param read the reading's findings
     * @return the rounds after it and where it leads
     * @throws IllegalStateException when the issue waits for the operator, so no reading may end
     */
    public static Counted count(final Rounds rounds, final WorkEnds.Read read) {
        if (rounds.stopped()) {
            throw new IllegalStateException("A stopped reading takes no further reading");
        }
        final int now = rounds.rounds() + 1;
        if (!read.blocking()) {
            return new Counted(new Rounds(now, rounds.roundsMost(), false), Next.REVIEW);
        }
        return new Counted(new Rounds(now, Math.max(now, rounds.roundsMost()), true), Next.STOP);
    }

    /**
     * Applies the operator's choice to a stopped reading.
     *
     * @param rounds the issue's rounds
     * @param round the round the operator saw, which must still be the last one counted
     * @param choice the choice
     * @param words the operator's words, required for a rework only without blocking findings
     * @param findings the stopped reading's saved findings, never a request's word for them
     * @param unreadable whether the reading stopped because its findings could not be read
     * @return the rounds after the choice and where the issue goes: Review, or work for one more
     *     reading or a rework; no reading is counted before it ends
     * @throws Refused when the reading is not stopped, the round moved on, a rework follows a
     *     reading that could not be read, or a rework has neither words nor blocking findings to go
     *     with it
     */
    public static Counted choose(
            final Rounds rounds,
            final int round,
            final Choice choice,
            final String words,
            final List<WorkEnds.Finding> findings,
            final boolean unreadable) {
        Objects.requireNonNull(words, "words");
        if (!rounds.stopped()) {
            throw new Refused("ReadingNotStopped");
        }
        if (round != rounds.rounds()) {
            throw new Refused("ReadingRoundChanged");
        }
        if (choice != Choice.REVIEW_ANYWAY && rounds.rounds() == Integer.MAX_VALUE) {
            throw new Refused("ReadingRoundChanged");
        }
        final Rounds granted =
                new Rounds(
                        rounds.rounds(), Math.max(rounds.roundsMost(), rounds.rounds() + 1), false);
        return switch (choice) {
            case REVIEW_ANYWAY ->
                    new Counted(
                            new Rounds(rounds.rounds(), rounds.roundsMost(), false), Next.REVIEW);
            case ONE_MORE_ROUND -> new Counted(granted, Next.READ);
            case REWORK -> {
                if (unreadable) {
                    throw new Refused("ReworkNeedsFindings");
                }
                if (words.isBlank() && !new WorkEnds.Read(findings).blocking()) {
                    throw new Refused("ReworkNeedsWords");
                }
                yield new Counted(granted, Next.REWORK);
            }
        };
    }

    /** A choice the reading's state does not allow, by its problem's name. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Names the refusal.
         *
         * @param name the problem's name
         */
        public Refused(final String name) {
            super(name);
        }
    }
}
