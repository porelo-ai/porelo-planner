package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reads measured usage without copying session content or paths into stage records. */
final class StageTokens {
    static final List<String> FIELDS =
            List.of(
                    "claude_input_tokens",
                    "claude_output_tokens",
                    "claude_cache_read_tokens",
                    "claude_cache_write_tokens",
                    "claude_messages",
                    "codex_input_tokens",
                    "codex_cached_tokens",
                    "codex_output_tokens",
                    "codex_reasoning_tokens",
                    "agy_input_tokens",
                    "agy_cache_read_tokens",
                    "agy_output_tokens");
    private static final List<String> CLAUDE =
            List.of(
                    "input_tokens",
                    "output_tokens",
                    "cache_read_input_tokens",
                    "cache_creation_input_tokens");
    private static final List<String> CODEX =
            List.of(
                    "input_tokens",
                    "cached_input_tokens",
                    "output_tokens",
                    "reasoning_output_tokens");
    private static final List<String> AGY = List.of("input", "cacheRead", "output");
    private static final List<String> AGY_TOKEN_FIELDS =
            List.of("input_tokens", "cache_read_input_tokens", "output_tokens");

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** A session's text that is no JSON, told apart from a value of the wrong shape. */
    static final class InvalidJson extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidJson(final JsonProcessingException cause) {
            super(cause);
        }
    }

    private StageTokens() {}

    private static @Nullable Object parse(final String text) {
        try {
            return JSON.readValue(text, Object.class);
        } catch (final JsonProcessingException ex) {
            throw new InvalidJson(ex);
        }
    }

    static void empty(Map<String, @Nullable Object> record) {
        FIELDS.forEach(field -> record.put(field, null));
    }

    static Map<String, @Nullable Object> baseline(Path file) throws IOException {
        if (Files.notExists(file)) {
            return Map.of();
        }
        final List<Map<String, @Nullable Object>> entries = entries(Files.readString(file));
        final Map<String, @Nullable Object> usage = codexTotal(entries);
        return usage == null ? Map.of() : usage;
    }

    static void read(
            Path file,
            String actor,
            Instant start,
            Instant end,
            @Nullable Object baseline,
            boolean allowNoUsage,
            Map<String, @Nullable Object> record)
            throws IOException {
        final String content;
        try {
            content = Files.readString(file);
        } catch (IOException ex) {
            // Filesystem exceptions contain the private source path and must not escape into logs.
            throw new IOException("could not read the session file");
        }
        try {
            final List<Map<String, @Nullable Object>> entries = entries(content);
            final boolean claude =
                    entries.stream().anyMatch(entry -> "assistant".equals(entry.get("type")));
            final boolean codex = entries.stream().anyMatch(StageTokens::codex);
            final boolean agy = entries.size() == 1 && entries.getFirst().get("usage") != null;
            if (allowNoUsage && entries.stream().noneMatch(StageTokens::hasUsage)) {
                StageValues.require(
                        baseline == null || StageValues.object(baseline).isEmpty(),
                        "Codex session lost its baseline usage");
                noUsage(record);
                return;
            }
            StageValues.require(
                    (claude ? 1 : 0) + (codex ? 1 : 0) + (agy ? 1 : 0) == 1,
                    "unknown or mixed session format");
            if (claude) {
                claude(entries, actor.equals("agent"), start, end, record);
            } else if (codex) {
                final Map<String, @Nullable Object> total = codexTotal(entries);
                if (total == null) {
                    throw new IllegalArgumentException(
                            "Codex session contains no measured token usage");
                }
                final Map<String, @Nullable Object> before =
                        baseline == null ? Map.of() : StageValues.object(baseline);
                for (int i = 0; i < CODEX.size(); i++) {
                    final String key = CODEX.get(i);
                    final long begin =
                            before.isEmpty() ? 0 : StageValues.integer(before.get(key), key);
                    final long value = StageValues.integer(total.get(key), key);
                    StageValues.require(
                            value >= begin, "Codex cumulative usage decreased since begin");
                    record.put(FIELDS.get(i + 5), value - begin);
                }
            } else {
                final Map<String, @Nullable Object> usage =
                        StageValues.object(entries.getFirst().get("usage"));
                final boolean plain = AGY.stream().anyMatch(usage::containsKey);
                final boolean tokens = AGY_TOKEN_FIELDS.stream().anyMatch(usage::containsKey);
                StageValues.require(plain != tokens, "agy usage fields are missing or mixed");
                copy(usage, plain ? AGY : AGY_TOKEN_FIELDS, FIELDS.subList(9, 12), record);
            }
        } catch (InvalidJson ex) {
            throw new IllegalArgumentException("session contains invalid JSON");
        }
    }

    private static boolean hasUsage(Map<String, @Nullable Object> entry) {
        final Object usage;
        if ("assistant".equals(entry.get("type"))) {
            usage = StageValues.object(entry.get("message")).get("usage");
        } else if (codex(entry)) {
            return StageValues.object(entry.get("payload")).get("info") != null;
        } else {
            usage = entry.get("usage");
        }
        return usage != null && !(usage instanceof Map<?, ?> map && map.isEmpty());
    }

    private static List<Map<String, @Nullable Object>> entries(String content) {
        if (content.isBlank()) {
            return List.of();
        }
        // agy's result may be pretty-printed; rollout and Claude logs have one object per line.
        try {
            return List.of(StageValues.object(parse(content)));
        } catch (InvalidJson ex) {
            final List<Map<String, @Nullable Object>> result = new ArrayList<>();
            for (final String line : content.lines().toList()) {
                StageValues.require(!line.isBlank(), "session contains an empty JSON line");
                result.add(StageValues.object(parse(line)));
            }
            return result;
        }
    }

    private static void claude(
            List<Map<String, @Nullable Object>> entries,
            boolean window,
            Instant start,
            Instant end,
            Map<String, @Nullable Object> record) {
        final long[] totals = new long[5];
        final Map<String, Map<String, @Nullable Object>> messages = new LinkedHashMap<>();
        for (final Map<String, @Nullable Object> entry : entries) {
            if (!"assistant".equals(entry.get("type"))) {
                continue;
            }
            final String id = StageValues.string(StageValues.object(entry.get("message")), "id");
            StageValues.require(!id.isBlank(), "Claude message has no id");
            messages.put(id, entry);
        }
        for (final Map<String, @Nullable Object> entry : messages.values()) {
            final Instant timestamp = Instant.parse(StageValues.string(entry, "timestamp"));
            if (window && (timestamp.isBefore(start) || !timestamp.isBefore(end))) {
                continue;
            }
            final Map<String, @Nullable Object> usage =
                    StageValues.object(StageValues.object(entry.get("message")).get("usage"));
            for (int i = 0; i < CLAUDE.size(); i++) {
                totals[i] =
                        add(
                                totals[i],
                                StageValues.integer(usage.get(CLAUDE.get(i)), CLAUDE.get(i)));
            }
            totals[4] = add(totals[4], 1);
        }
        for (int i = 0; i < totals.length; i++) {
            record.put(FIELDS.get(i), totals[i]);
        }
    }

    private static boolean codex(Map<String, @Nullable Object> entry) {
        return "event_msg".equals(entry.get("type"))
                && entry.get("payload") instanceof Map<?, ?> payload
                && "token_count".equals(payload.get("type"));
    }

    private static @Nullable Map<String, @Nullable Object> codexTotal(
            List<Map<String, @Nullable Object>> entries) {
        Map<String, @Nullable Object> last = null;
        for (final Map<String, @Nullable Object> entry : entries) {
            if (!codex(entry)) {
                continue;
            }
            final Map<String, @Nullable Object> payload = StageValues.object(entry.get("payload"));
            // Rate-limit-only events carry no usage; they do not replace the last measured total.
            if (payload.get("info") == null) {
                continue;
            }
            final Map<String, @Nullable Object> usage =
                    StageValues.object(
                            StageValues.object(payload.get("info")).get("total_token_usage"));
            for (final String key : CODEX) {
                StageValues.integer(usage.get(key), key);
            }
            if (last != null) {
                for (final String key : CODEX) {
                    StageValues.require(
                            StageValues.integer(usage.get(key), key)
                                    >= StageValues.integer(last.get(key), key),
                            "Codex cumulative usage decreased");
                }
            }
            last = usage;
        }
        return last;
    }

    private static void noUsage(Map<String, @Nullable Object> record) {
        FIELDS.forEach(field -> record.put(field, 0));
        record.put("note", "no-usage");
    }

    private static void copy(
            Map<String, @Nullable Object> usage,
            List<String> source,
            List<String> target,
            Map<String, @Nullable Object> record) {
        for (int i = 0; i < source.size(); i++) {
            record.put(target.get(i), StageValues.integer(usage.get(source.get(i)), source.get(i)));
        }
    }

    private static long add(long total, long value) {
        StageValues.require(total <= 9007199254740991L - value, "session token total overflow");
        return total + value;
    }
}
