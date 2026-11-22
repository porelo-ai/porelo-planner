package org.fuin.porelo.planner.store.cache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a tracked project's board is written with - its own id and its fields' ids and options, as
 * the tracker named them when the board was read - and what was found wrong with its fields then,
 * which holds while the project's files are the same.
 *
 * @param id the board's own id at the tracker
 * @param fieldIds the fields' ids by name
 * @param fieldNames the names of every field by its id, a second field of one name included, so its
 *     values are read as the tracker's own read of the board reads them
 * @param optionIds each option field's options, their ids by name
 * @param faults what was found wrong with the board's fields, and which of its repositories' issue
 *     lists could not be read, in the planner's words
 * @param restFieldIds the fields' second, numeric ids by their ids, which the tracker's REST item
 *     list takes to name the fields whose values it gives
 */
public record TrackerBoard(
        String id,
        Map<String, String> fieldIds,
        Map<String, String> fieldNames,
        Map<String, Map<String, String>> optionIds,
        List<String> faults,
        Map<String, Long> restFieldIds) {

    /** Requires the id and every name and id in the maps, and copies them, keeping their order. */
    public TrackerBoard {
        Objects.requireNonNull(id, "id");
        fieldIds = copied(fieldIds);
        fieldNames = copied(fieldNames);
        final Map<String, Map<String, String>> options = new LinkedHashMap<>();
        optionIds.forEach(
                (field, byName) ->
                        options.put(
                                Objects.requireNonNull(field, "field"),
                                copied(Objects.requireNonNull(byName, "options"))));
        optionIds = Collections.unmodifiableMap(options);
        faults = List.copyOf(faults);
        final Map<String, Long> numeric = new LinkedHashMap<>();
        restFieldIds.forEach(
                (field, number) ->
                        numeric.put(
                                Objects.requireNonNull(field, "field"),
                                Objects.requireNonNull(number, "number")));
        restFieldIds = Collections.unmodifiableMap(numeric);
    }

    /** Copies a map of names, keeping its order and refusing a missing name or id. */
    private static Map<String, String> copied(final Map<String, String> given) {
        final Map<String, String> copy = new LinkedHashMap<>();
        given.forEach(
                (key, value) ->
                        copy.put(
                                Objects.requireNonNull(key, "key"),
                                Objects.requireNonNull(value, "value")));
        return Collections.unmodifiableMap(copy);
    }
}
