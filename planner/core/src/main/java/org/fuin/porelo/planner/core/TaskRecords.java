package org.fuin.porelo.planner.core;

import java.time.Instant;
import java.util.List;

/** Porelo's own records of a project's tasks, however they are reached. */
public interface TaskRecords {

    /**
     * Answers the records of a project's tasks that ended at or after an instant, and of every task
     * still running, the oldest start first.
     *
     * @param project the project's name
     * @param since the earliest end of a task answered
     * @return every such record, none left out
     * @throws Unanswered when Porelo cannot answer them all, naming why
     */
    List<TaskRecord> since(String project, Instant since);

    /** Porelo could not answer the records, or answered what cannot be trusted. */
    final class Unanswered extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * @param reason why, in words a person can act on
         */
        public Unanswered(final String reason) {
            super(reason);
        }

        /**
         * @param reason why, in words a person can act on
         * @param cause what failed
         */
        public Unanswered(final String reason, final Throwable cause) {
            super(reason, cause);
        }
    }
}
