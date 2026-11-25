package org.fuin.porelo.planner.core;

import java.util.Objects;

/**
 * What was read of an issue's kept place from Porelo: a task's record, what is open there, or why
 * Porelo gave no answer that can be trusted.
 */
public sealed interface PlaceRead
        permits PlaceRead.Recorded, PlaceRead.Opened, PlaceRead.Forwarded, PlaceRead.Unread {

    /**
     * Porelo's record of the task bound at the place.
     *
     * @param record the record
     */
    record Recorded(TaskRecord record) implements PlaceRead {
        /** Refuses a missing record. */
        public Recorded {
            Objects.requireNonNull(record, "record");
        }
    }

    /**
     * What Porelo answered was open at a place, asked for a task.
     *
     * @param place the place asked about
     * @param task the task asked for, by its name within the project
     * @param answer the answer
     */
    record Opened(RunPlace place, String task, ReviewOpen answer) implements PlaceRead {
        /** Refuses a missing place, task or answer. */
        public Opened {
            Objects.requireNonNull(place, "place");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(answer, "answer");
        }
    }

    /**
     * What Porelo answered at a gate push it forwarded, and what is open at the task's own pull
     * request, which the forwarding opened: the place the author's work goes on at.
     *
     * @param gate the gate push asked about
     * @param task the task asked for, by its name within the project
     * @param atGate the gate's answer, APPROVED with the forwarded commit
     * @param own the answer at the task's own pull request
     */
    record Forwarded(RunPlace gate, String task, ReviewOpen atGate, ReviewOpen own)
            implements PlaceRead {
        /**
         * Refuses a missing part, a place that is no gate push and a gate that forwarded nothing.
         */
        public Forwarded {
            Objects.requireNonNull(gate, "gate");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(atGate, "atGate");
            Objects.requireNonNull(own, "own");
            if (gate.gatePush() == null) {
                throw new IllegalArgumentException("Only a gate push forwards its work");
            }
            if (atGate.decision() != ReviewOpen.Decision.APPROVED
                    || atGate.mergedCommit() == null
                    || !atGate.complete()) {
                throw new IllegalArgumentException("The gate forwarded no commit");
            }
        }
    }

    /**
     * Porelo gave no answer that can be trusted of what is open at a place, asked for a task.
     *
     * @param place the place asked about
     * @param task the task asked for, by its name within the project
     * @param reason why, in words free of secrets and local paths
     */
    record Unread(RunPlace place, String task, String reason) implements PlaceRead {
        /** Refuses a missing place or task and a blank reason. */
        public Unread {
            Objects.requireNonNull(place, "place");
            Objects.requireNonNull(task, "task");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("An unread place says why");
            }
        }
    }
}
