package org.fuin.porelo.planner.core;

import java.util.List;

/** The reports Porelo keeps of a task's name, however they are reached. */
public interface TaskReports {

    /**
     * Answers the reports of a task's name numbered after a number, oldest first, as many as one
     * answer of Porelo's carries.
     *
     * @param container the task's container name, as its record names it
     * @param after the last number taken, 0 for all
     * @return one page of them
     * @throws TaskRecords.Unanswered when Porelo cannot answer them, or answers what cannot be
     *     trusted, naming why
     * @throws IllegalArgumentException when the name is no task's name or the number is below 0, a
     *     caller's error and not Porelo's
     */
    Page after(String container, long after);

    /**
     * One answer: the reports, the number of the name's last report, and whether some after the
     * reports answered were left out.
     *
     * @param reports the reports, their numbers rising
     * @param last the number of the name's last report, 0 before its first
     * @param more whether some were left out
     */
    record Page(List<TaskReport> reports, long last, boolean more) {

        /**
         * Refuses a page that contradicts itself: numbers that do not rise, a report past the last,
         * more left out with none answered or with the last answered, or none left out without the
         * last answered.
         *
         * @throws IllegalArgumentException naming what is contradictory
         */
        public Page {
            reports = List.copyOf(reports);
            if (last < 0) {
                throw new IllegalArgumentException("last is below 0");
            }
            long seen = 0;
            for (final TaskReport report : reports) {
                if (report.seq() <= seen) {
                    throw new IllegalArgumentException("the reports' numbers do not rise");
                }
                if (report.seq() > last) {
                    throw new IllegalArgumentException("a report is numbered past last");
                }
                seen = report.seq();
            }
            if (more && (reports.isEmpty() || seen >= last)) {
                throw new IllegalArgumentException(
                        "more is true, yet the page holds no report or holds the last");
            }
            if (!more && !reports.isEmpty() && seen != last) {
                throw new IllegalArgumentException(
                        "more is false, yet the page does not end with the last report");
            }
        }

        /**
         * The number to read on from.
         *
         * @return the last report's number on this page
         * @throws java.util.NoSuchElementException on an empty page
         */
        public long through() {
            return reports.getLast().seq();
        }
    }
}
