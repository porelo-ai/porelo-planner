package org.fuin.porelo.planner.store.github;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * What one command writes on one project item, each target as the item shows it once written, and
 * the places its judgment read there; a target left null is not written.
 *
 * @param id the item's id on the board, named in a refusal
 * @param story whether the item is a story, which has no {@code Status} and is only closed or
 *     reopened
 * @param state the state written, or null
 * @param reason the reason kept with a close as a comment, only with a closed {@code state}
 * @param worker the {@code Worker} field's option written, or null
 * @param priority the priority written, one the board names, or null
 * @param topics the topic labels the item carries once written, or null when the command writes
 *     none; its other labels stay
 * @param title the title written, or null
 * @param written the body's parts written, by part
 * @param removed the body's parts removed
 * @param reads the places on the item the command's judgment read besides those it writes
 */
record MergeTargets(
        String id,
        boolean story,
        @Nullable State state,
        Writes.@Nullable Reason reason,
        @Nullable String worker,
        @Nullable String priority,
        @Nullable List<String> topics,
        @Nullable String title,
        Map<TextEdit.Part, String> written,
        Set<TextEdit.Part> removed,
        Set<Place> reads) {

    /**
     * The places a task's start or end read on its own item: its state and worker, blockers,
     * parent, sub-issues, size and risk, and its title and whole body, which the next task takes
     * over.
     */
    static final Set<Place> RUN_READS =
            Set.of(
                    Place.STATE,
                    Place.WORKER,
                    Place.BLOCKERS,
                    Place.PARENT,
                    Place.SUB_ISSUES,
                    Place.field(FieldNames.SIZE),
                    Place.field(FieldNames.RISK),
                    Place.TITLE,
                    Place.WHOLE_BODY);

    /**
     * A state as the item shows it.
     *
     * @param status the {@code Status} option of an open issue; null for a closed state and for a
     *     story
     * @param open whether the item is open
     * @param notPlanned whether a closed item closed as not planned
     */
    record State(@Nullable String status, boolean open, boolean notPlanned) {

        /** Checks that only an open state names a status and only a closed one is not planned. */
        State {
            if (!open && status != null || open && notPlanned) {
                throw new IllegalArgumentException("A state is open with a status or closed.");
            }
        }
    }

    /**
     * Checks that a reason goes with a close, that an issue's open state names its status and a
     * story's none, and copies the collections.
     */
    MergeTargets {
        if (reason != null && (state == null || state.open())) {
            throw new IllegalArgumentException("The reason of " + id + " goes with no close.");
        }
        if (state != null && state.open() && story != (state.status() == null)) {
            throw new IllegalArgumentException("The open state of " + id + " is named wrongly.");
        }
        topics = topics == null ? null : List.copyOf(topics);
        written = Collections.unmodifiableMap(new LinkedHashMap<>(written));
        removed =
                removed.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(removed));
        reads = Collections.unmodifiableSet(new TreeSet<>(reads));
    }
}
