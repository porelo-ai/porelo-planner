package org.fuin.porelo.planner.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One stage of an issue, as its stage comment holds it; its end is the comment's {@code createdAt}
 * and its start that end less its minutes, so neither is kept here.
 *
 * @param key what makes the stage one: the task and the stage's ordinal, so a retry writes it once
 * @param stage the kind of stage, such as {@code implement} or {@code wait}
 * @param result how it ended, such as {@code done}
 * @param actor who worked it, {@code agent} or {@code person}
 * @param model the model, or null for a person
 * @param effort the model's effort, or null
 * @param minutes its wall minutes
 * @param vmMinutes the minutes it waited on a machine
 * @param tokens the tokens it spent, by model and kind, such as {@code claude_input_tokens}
 */
public record StageEntry(
        String key,
        String stage,
        String result,
        String actor,
        @Nullable String model,
        @Nullable String effort,
        double minutes,
        double vmMinutes,
        Map<String, Long> tokens) {

    /** The most minutes one stage may take, as a stage record allows. */
    public static final double MOST_MINUTES = 100_000;

    /**
     * The stages whose agent's minutes and tokens make up an issue's sums; others are shown only.
     */
    public static final Set<String> MEASURED =
            Set.of("plan", "implement", "test", "review", "rework", "handover");

    /**
     * Refuses a blank key or kind, and a negative, non-finite or overlong number, which would spoil
     * the sums.
     */
    public StageEntry {
        for (final String value : new String[] {key, stage, result, actor}) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("A stage names its key, kind, result and actor");
            }
        }
        minutes = bounded(minutes, "minutes");
        vmMinutes = bounded(vmMinutes, "VM minutes");
        tokens = Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
        tokens.forEach(
                (kind, count) -> {
                    if (count < 0) {
                        throw new IllegalArgumentException(
                                "A stage's " + kind + " tokens are negative");
                    }
                });
    }

    private static double bounded(final double value, final String what) {
        if (!Double.isFinite(value) || value < 0 || value > MOST_MINUTES) {
            throw new IllegalArgumentException(
                    "A stage's " + what + " are not between 0 and " + MOST_MINUTES);
        }
        return value;
    }
}
