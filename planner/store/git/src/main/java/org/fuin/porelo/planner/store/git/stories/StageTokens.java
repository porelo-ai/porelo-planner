package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads measured usage without copying session content or paths into stage records. A session is
 * read line by line and summed as it is read, never held whole, and only up to the size it had when
 * opened.
 */
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

    private static final String EMPTY_LINE = "session contains an empty JSON line";

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
        final Map<String, @Nullable Object> usage = tally(file).codexTotal();
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
        try {
            final Tally entries = tally(file);
            final boolean claude = entries.claude;
            final boolean codex = entries.codex;
            final Map<String, @Nullable Object> first = entries.first;
            final boolean agy = entries.count == 1 && first != null && first.get("usage") != null;
            if (allowNoUsage && entries.noUsage()) {
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
                claude(entries.messages(), actor.equals("agent"), start, end, record);
            } else if (codex) {
                final Map<String, @Nullable Object> total = entries.codexTotal();
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
                        StageValues.object(first == null ? null : first.get("usage"));
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

    /**
     * Reads a session line by line: one JSON object a line, as Claude and Codex write them, or one
     * object over several lines, as agy may write its result, but never both. A blank line counts
     * only around a file's one object.
     */
    private static Tally tally(final Path file) throws IOException {
        final Tally tally = new Tally();
        boolean blank = false;
        try (BufferedReader lines = new BufferedReader(SessionFile.open(file))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.isBlank()) {
                    blank = true;
                    continue;
                }
                final Object value;
                try {
                    value = parse(line);
                } catch (final InvalidJson ex) {
                    if (tally.count == 0) {
                        // The first line is no JSON of its own: the file may be one object.
                        return whole(file, blank);
                    }
                    StageValues.require(!blank, EMPTY_LINE);
                    throw ex;
                }
                StageValues.require(!blank || tally.count == 0, EMPTY_LINE);
                tally.add(StageValues.object(value));
            }
        } catch (final IOException ex) {
            // Filesystem exceptions contain the private source path and must not escape into logs.
            throw new IOException("could not read the session file");
        }
        StageValues.require(!blank || tally.count < 2, EMPTY_LINE);
        return tally;
    }

    /** Reads a session that is one JSON value over several lines, as a stream. */
    private static Tally whole(final Path file, final boolean blank) throws IOException {
        final Object value;
        try (BufferedReader in = new BufferedReader(SessionFile.open(file))) {
            value = JSON.readValue(in, Object.class);
        } catch (final JsonProcessingException ex) {
            StageValues.require(!blank, EMPTY_LINE);
            throw new InvalidJson(ex);
        }
        final Tally tally = new Tally();
        tally.add(StageValues.object(value));
        return tally;
    }

    /**
     * What a session's entries add up to, gathered one entry at a time. A fault in an entry is kept
     * and thrown only when its part is asked for, as reading the entries whole would.
     */
    private static final class Tally {
        private int count;
        private @Nullable Map<String, @Nullable Object> first;
        private boolean claude;
        private boolean codex;
        private boolean usage;
        private @Nullable RuntimeException usageFault;
        private final Map<String, Map<String, @Nullable Object>> messages = new LinkedHashMap<>();
        private @Nullable RuntimeException messageFault;
        private @Nullable Map<String, @Nullable Object> codexLast;
        private @Nullable RuntimeException codexFault;

        void add(final Map<String, @Nullable Object> entry) {
            count++;
            first = count == 1 ? entry : null;
            if ("assistant".equals(entry.get("type"))) {
                claude = true;
                if (messageFault == null) {
                    try {
                        message(entry);
                    } catch (final RuntimeException ex) {
                        messageFault = ex;
                    }
                }
            }
            if (StageTokens.codex(entry)) {
                codex = true;
                if (codexFault == null) {
                    try {
                        codexLast = codexStep(codexLast, entry);
                    } catch (final RuntimeException ex) {
                        codexFault = ex;
                    }
                }
            }
            if (!usage && usageFault == null) {
                try {
                    usage = hasUsage(entry);
                } catch (final RuntimeException ex) {
                    usageFault = ex;
                }
            }
        }

        /** Keeps of an assistant's entry only its time and usage, under its message's id. */
        private void message(final Map<String, @Nullable Object> entry) {
            final Map<String, @Nullable Object> message = StageValues.object(entry.get("message"));
            final String id = StageValues.string(message, "id");
            StageValues.require(!id.isBlank(), "Claude message has no id");
            final Map<String, @Nullable Object> kept = new LinkedHashMap<>();
            kept.put("usage", message.get("usage"));
            final Map<String, @Nullable Object> reduced = new LinkedHashMap<>();
            reduced.put("timestamp", entry.get("timestamp"));
            reduced.put("message", kept);
            messages.put(id, reduced);
        }

        boolean noUsage() {
            if (usageFault != null) {
                throw usageFault;
            }
            return !usage;
        }

        Collection<Map<String, @Nullable Object>> messages() {
            if (messageFault != null) {
                throw messageFault;
            }
            return messages.values();
        }

        @Nullable Map<String, @Nullable Object> codexTotal() {
            if (codexFault != null) {
                throw codexFault;
            }
            return codexLast;
        }
    }

    private static void claude(
            Collection<Map<String, @Nullable Object>> messages,
            boolean window,
            Instant start,
            Instant end,
            Map<String, @Nullable Object> record) {
        final long[] totals = new long[5];
        for (final Map<String, @Nullable Object> entry : messages) {
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

    /**
     * The cumulative usage after one more Codex entry, held to never decrease.
     *
     * @return the entry's usage, or the last one when it carries none
     */
    private static @Nullable Map<String, @Nullable Object> codexStep(
            @Nullable Map<String, @Nullable Object> last, Map<String, @Nullable Object> entry) {
        final Map<String, @Nullable Object> payload = StageValues.object(entry.get("payload"));
        // Rate-limit-only events carry no usage; they do not replace the last measured total.
        if (payload.get("info") == null) {
            return last;
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
        return usage;
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
