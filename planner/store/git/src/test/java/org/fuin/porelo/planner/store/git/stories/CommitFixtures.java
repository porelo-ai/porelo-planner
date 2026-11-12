package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Complete stage records for the commit rules' tests, independent of the reader's field list. */
final class CommitFixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CommitFixtures() {}

    static Map<String, @Nullable Object> record(
            final String issue,
            final String stage,
            final String actor,
            final int minutes,
            final String status,
            final @Nullable String size) {
        final Map<String, @Nullable Object> record = new LinkedHashMap<>();
        record.put("issue", issue);
        record.put("stage", stage);
        record.put("n", 1);
        record.put("actor", actor);
        record.put("model", null);
        record.put("effort", null);
        record.put("size", size);
        record.put("status_before", "Implementing");
        record.put("status_after", status);
        record.put("start", "2026-01-01T10:00:00Z");
        record.put(
                "end", Instant.parse("2026-01-01T10:00:00Z").plusSeconds(minutes * 60L).toString());
        record.put("wall_min", minutes);
        record.put("vm_min", 0);
        record.put("estimate_min", "estimate".equals(stage) && size != null ? 53 : null);
        record.put("result", "done");
        record.put("wait_reason", "wait".equals(stage) ? "build" : null);
        record.put("note", "no-session");
        for (final String tokens :
                new String[] {
                    "claude_input_tokens", "claude_output_tokens", "claude_cache_read_tokens",
                    "claude_cache_write_tokens", "claude_messages", "codex_input_tokens",
                    "codex_cached_tokens", "codex_output_tokens", "codex_reasoning_tokens",
                    "agy_input_tokens", "agy_cache_read_tokens", "agy_output_tokens"
                }) {
            record.put(tokens, null);
        }
        return record;
    }

    static String closed(final String issue) {
        return json(record(issue, "implement", "agent", 4, "Review", "M")) + "\n";
    }

    /** Reads JSON as the build tools' reader does: every number a {@code Double}. */
    static @Nullable Object parse(final String text) {
        try {
            return doubles(JSON.readValue(text, Object.class));
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @Nullable Object doubles(final @Nullable Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Map<?, ?> map) {
            final Map<Object, @Nullable Object> result = new LinkedHashMap<>();
            map.forEach((key, each) -> result.put(key, doubles(each)));
            return result;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(CommitFixtures::doubles).toList();
        }
        return value;
    }

    static String json(final Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
