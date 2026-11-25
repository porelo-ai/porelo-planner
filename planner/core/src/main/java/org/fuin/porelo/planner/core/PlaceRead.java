package org.fuin.porelo.planner.core;

import java.util.Objects;

/**
 * What was read of an issue's kept place from Porelo: a task's record, what is open there, or why
 * Porelo gave no answer that can be trusted.
 */
public sealed interface PlaceRead permits PlaceRead.Recorded, PlaceRead.Opened, PlaceRead.Unread {

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
