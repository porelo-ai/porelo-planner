package org.fuin.porelo.planner.store.git.stories;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The checked reads of a JSON value the stage command and its token reader share. */
final class StageValues {

    private StageValues() {}

    static Map<String, @Nullable Object> object(final @Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        final Map<String, @Nullable Object> result = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("expected a JSON object key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    static String string(final Map<String, @Nullable Object> record, final String key) {
        final Object value = record.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("missing or invalid " + key);
        }
        return text;
    }

    static double number(final @Nullable Object value, final String key) {
        if (!(value instanceof Number numeric)) {
            throw new IllegalArgumentException("missing or invalid " + key);
        }
        final double number = numeric.doubleValue();
        require(Double.isFinite(number) && number >= 0, "invalid " + key);
        return number;
    }

    static long integer(final @Nullable Object value, final String key) {
        final double number = number(value, key);
        require(
                number == Math.rint(number) && number <= 9007199254740991L,
                "invalid integer " + key);
        return (long) number;
    }

    static void require(final boolean condition, final String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
