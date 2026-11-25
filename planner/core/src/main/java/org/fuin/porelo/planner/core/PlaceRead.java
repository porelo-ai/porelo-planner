package org.fuin.porelo.planner.core;

import java.util.Objects;

/** What was read of an issue's kept place from Porelo: a task's record or what is open there. */
public sealed interface PlaceRead permits PlaceRead.Recorded, PlaceRead.Opened {

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
}
