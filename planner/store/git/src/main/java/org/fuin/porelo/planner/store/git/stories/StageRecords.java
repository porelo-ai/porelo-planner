package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An issue's stage records, one JSON object a line in {@code stats/<issue>.jsonl}: read and checked
 * as the build tools' {@code stage} command writes them, a person's move on the board added as a
 * closed line of its own, and the minutes a size took as their median.
 */
public final class StageRecords {

    /** The one stage of a person: a state set on the board. */
    private static final String MOVE = "move";

    /** The one actor who moves, and who has no other stage. */
    private static final String PERSON = "person";

    /** The stages an agent times, and a person's move on the board. */
    private static final Set<String> STAGES =
            Set.of(
                    "analyze",
                    "estimate",
                    "plan",
                    "implement",
                    "test",
                    "review",
                    "rework",
                    "handover",
                    "wait",
                    MOVE);

    private static final Set<String> MEASURED =
            Set.of("plan", "implement", "test", "review", "rework", "handover");

    private static final Set<String> SIZES = Set.of("XS", "S", "M", "L", "XL");

    private static final Set<String> REASONS = Set.of("operator", "build", "agent", "vm");

    private static final Set<String> RESULTS = Set.of("done", "returned", "refused", "abandoned");

    /** The token counts an agent's session gives; a move has none. */
    private static final List<String> TOKENS =
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

    /** Every field of a record, in the order the {@code stage} command writes them. */
    private static final List<String> FIELDS =
            List.of(
                    "issue",
                    "stage",
                    "n",
                    "actor",
                    "model",
                    "effort",
                    "size",
                    "status_before",
                    "status_after",
                    "start",
                    "end",
                    "wall_min",
                    "vm_min",
                    "estimate_min",
                    "result",
                    "wait_reason",
                    "note");

    private static final String ISSUE = "[A-Z]{1,3}\\d{2,3}";

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StageRecords() {}

    /**
     * Answers whether an issue's id can name a stage record file; a sub-issue's cannot.
     *
     * @param issue the issue's id
     * @return whether the id names a record file
     */
    public static boolean recorded(final String issue) {
        return issue.matches(ISSUE);
    }

    /**
     * Answers the path of an issue's stage records in its repository.
     *
     * @param issue the issue's id
     * @return {@code stats/<issue>.jsonl}
     */
    public static String path(final String issue) {
        return "stats/" + issue + ".jsonl";
    }

    /**
     * Reads a stage record file, checking every record and that each names the file's issue.
     *
     * @param contents the file's text
     * @param filename the file's name, {@code <issue>.jsonl}
     * @param closed whether every record must be closed
     * @param workflow the states a record may name
     * @return the records, in the file's order
     * @throws IllegalArgumentException naming the first thing found wrong
     */
    public static List<Map<String, @Nullable Object>> read(
            final String contents,
            final String filename,
            final boolean closed,
            final Workflow workflow) {
        require(filename.matches(ISSUE + "\\.jsonl"), "invalid stats filename");
        require(!contents.isEmpty(), "empty stats file");
        require(contents.endsWith("\n"), "stats file has an incomplete final line");
        final List<Map<String, @Nullable Object>> records = new ArrayList<>();
        for (final String line : contents.substring(0, contents.length() - 1).split("\n", -1)) {
            final Map<String, @Nullable Object> record = parse(line);
            require(
                    !closed || record.get("end") != null,
                    "stats file contains a stage without end");
            validate(record, record.get("end") != null, workflow);
            require(
                    filename.equals(string(record, "issue") + ".jsonl"),
                    "record issue disagrees with filename");
            records.add(record);
        }
        return records;
    }

    /**
     * Adds a person's move on the board to an issue's stage records: one closed line, its start and
     * end the moment of the move, numbered after the issue's earlier moves.
     *
     * @param existing the record file's text, or {@code null} when the issue has none yet
     * @param issue the issue's id
     * @param size the issue's size, or {@code null} when it has none
     * @param before the state it leaves
     * @param after the state it is moved to
     * @param at when it is moved
     * @param workflow the states a record may name
     * @return the record file's new text
     * @throws IllegalArgumentException when the existing records or the move cannot be recorded,
     *     naming why
     */
    public static String move(
            final @Nullable String existing,
            final String issue,
            final @Nullable String size,
            final String before,
            final String after,
            final Instant at,
            final Workflow workflow) {
        final String filename = issue + ".jsonl";
        require(issue.matches(ISSUE), "invalid record issue");
        long ordinal = 1;
        if (existing != null) {
            for (final Map<String, @Nullable Object> record :
                    read(existing, filename, true, workflow)) {
                if (MOVE.equals(record.get("stage"))) {
                    ordinal = Math.max(ordinal, integer(record.get("n"), "n") + 1);
                }
            }
        }
        final Map<String, @Nullable Object> record = new LinkedHashMap<>();
        for (final String field : FIELDS) {
            record.put(field, null);
        }
        TOKENS.forEach(field -> record.put(field, null));
        record.put("issue", issue);
        record.put("stage", MOVE);
        record.put("n", ordinal);
        record.put("actor", PERSON);
        record.put("size", size);
        record.put("status_before", before);
        record.put("status_after", after);
        record.put("start", at.toString());
        record.put("end", at.toString());
        record.put("result", "done");
        validate(record, true, workflow);
        final String line;
        try {
            line = JSON.writeValueAsString(record);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("a record of plain values is always written", e);
        }
        return (existing == null ? "" : existing) + line + "\n";
    }

    /**
     * Computes what a size took, as {@code stage median} and {@code stage p80} compute it: the
     * seed's minutes and, per finished issue, the sum of its agent's measured stages of that size.
     * A person's move adds no minutes, but the last entry of an issue, a move among them, says
     * whether it ended: an issue a person moves to a closed state counts, one moved back does not.
     *
     * @param seed the text of {@code stats/seed.csv}
     * @param files each record file's text, by its name below {@code stats/}
     * @param size the size
     * @param p80 the nearest-rank 80th percentile instead of the median
     * @param workflow the states a record may name
     * @return the minutes
     * @throws IllegalArgumentException when the seed or a record file cannot be read, naming why
     */
    public static double statistic(
            final String seed,
            final Map<String, String> files,
            final String size,
            final boolean p80,
            final Workflow workflow) {
        require(SIZES.contains(size), "unknown size");
        final List<String> lines = seed.lines().toList();
        require(
                !lines.isEmpty() && lines.getFirst().equals("size,minutes"),
                "seed header must be size,minutes");
        final Map<String, Double> seeds = new LinkedHashMap<>();
        for (final String line : lines.subList(1, lines.size())) {
            final String[] cells = line.split(",", -1);
            require(cells.length == 2 && SIZES.contains(cells[0]), "invalid seed row");
            final double minutes;
            try {
                minutes = Double.parseDouble(cells[1]);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException("invalid seed row", e);
            }
            require(
                    Double.isFinite(minutes)
                            && minutes >= 0
                            && seeds.putIfAbsent(cells[0], minutes) == null,
                    "invalid or duplicate seed size");
        }
        final Double seedMinutes = seeds.get(size);
        if (seedMinutes == null) {
            throw new IllegalArgumentException("no seed for size " + size);
        }
        final List<Double> samples = new ArrayList<>();
        samples.add(seedMinutes);
        for (final Map.Entry<String, String> file : files.entrySet()) {
            if (!file.getKey().endsWith(".jsonl")) {
                continue;
            }
            final List<Map<String, @Nullable Object>> all =
                    read(file.getValue(), file.getKey(), false, workflow);
            // A person's move adds no minutes, but the last entry, a move too, says whether it
            // ended.
            final List<Map<String, @Nullable Object>> records =
                    all.stream().filter(record -> !moved(record)).toList();
            if (records.isEmpty()) {
                continue;
            }
            double total = 0;
            boolean measured = false;
            boolean closed = true;
            for (final Map<String, @Nullable Object> record : records) {
                if (record.get("end") == null) {
                    closed = false;
                    continue;
                }
                if ("agent".equals(record.get("actor"))
                        && MEASURED.contains(string(record, "stage"))
                        && size.equals(record.get("size"))) {
                    total += number(record.get("wall_min"), "wall_min");
                    measured = true;
                }
            }
            require(Double.isFinite(total), "stage minutes overflow");
            if (closed && measured) {
                final String last = string(all.getLast(), "status_after");
                if (workflow.closed(last) && !workflow.rejected(last)) {
                    samples.add(total);
                }
            }
        }
        samples.sort(Double::compare);
        if (p80) {
            return samples.get((int) Math.ceil(samples.size() * 0.8) - 1);
        }
        final int middle = samples.size() / 2;
        return samples.size() % 2 == 1
                ? samples.get(middle)
                : samples.get(middle - 1) / 2 + samples.get(middle) / 2;
    }

    /** A person's line or a move, which no agent's time is in. */
    private static boolean moved(final Map<String, @Nullable Object> record) {
        return PERSON.equals(record.get("actor")) || MOVE.equals(record.get("stage"));
    }

    private static Map<String, @Nullable Object> parse(final String line) {
        final Object value;
        try {
            value = JSON.readValue(line, Object.class);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON in a stage record", e);
        }
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

    /**
     * Checks one record by the format, as {@link #read} checks each line; for the stage command.
     */
    static void validate(
            final Map<String, @Nullable Object> record,
            final boolean closed,
            final Workflow workflow) {
        final Set<String> fields = new HashSet<>(FIELDS);
        fields.addAll(TOKENS);
        require(record.keySet().equals(fields), "unknown or missing record fields");
        require(string(record, "issue").matches(ISSUE), "invalid record issue");
        final String stage = string(record, "stage");
        require(STAGES.contains(stage), "invalid record stage");
        final String actor = string(record, "actor");
        require(Set.of("agent", "subagent", PERSON).contains(actor), "invalid record actor");
        // A person only moves, and only a person moves.
        require(PERSON.equals(actor) == MOVE.equals(stage), "invalid record actor for its stage");
        final Object size = record.get("size");
        require(
                size == null && Set.of("analyze", "estimate", MOVE).contains(stage)
                        || size instanceof String text && SIZES.contains(text),
                "invalid record size");
        require(
                workflow.states().contains(string(record, "status_before")),
                "invalid record status_before in the workflow");
        require(integer(record.get("n"), "n") > 0, "invalid stage ordinal");
        if (MOVE.equals(stage)) {
            validateMove(record, workflow);
            return;
        }
        for (final String key : List.of("model", "effort")) {
            final Object value = record.get(key);
            require(
                    value == null
                            || value instanceof String text
                                    && text.matches("[A-Za-z0-9._][A-Za-z0-9._-]*"),
                    "invalid record " + key);
        }
        final Object reason = record.get("wait_reason");
        require(
                stage.equals("wait")
                        ? reason instanceof String text && REASONS.contains(text)
                        : reason == null,
                "invalid record wait reason");
        final Object note = record.get("note");
        require(
                note == null
                        || note instanceof String text
                                && (Set.of("no-session", "no-usage").contains(text)
                                        || text.matches("[a-z]+")),
                "invalid record note");
        final Object result = record.get("result");
        require(
                result == null && !closed
                        || result instanceof String text && RESULTS.contains(text),
                "invalid record result");
        final Object estimate = record.get("estimate_min");
        require(
                stage.equals("estimate") && size != null ? estimate != null : estimate == null,
                "invalid record estimate_min");
        if (estimate != null) {
            number(estimate, "estimate_min");
        }
        final Instant start = instant(record, "start");
        if (closed) {
            require(
                    workflow.states().contains(string(record, "status_after")),
                    "invalid record status_after in the workflow");
            require(!instant(record, "end").isBefore(start), "invalid record end");
            number(record.get("wall_min"), "wall_min");
            number(record.get("vm_min"), "vm_min");
        } else {
            require(
                    record.get("end") == null
                            && record.get("status_after") == null
                            && record.get("wall_min") == null
                            && record.get("vm_min") == null,
                    "open stage is already closed");
        }
        for (final String key : TOKENS) {
            final Object value = record.get(key);
            if (value != null) {
                integer(value, key);
            }
        }
    }

    /**
     * A move is closed the moment it is made, by a person who has no model, session or tokens, and
     * takes no time an estimate could count.
     */
    private static void validateMove(
            final Map<String, @Nullable Object> record, final Workflow workflow) {
        require(record.get("end") != null, "a move is never open");
        require(
                workflow.states().contains(string(record, "status_after")),
                "invalid record status_after in the workflow");
        require(
                instant(record, "end").equals(instant(record, "start")),
                "a move ends when it starts");
        require("done".equals(record.get("result")), "invalid record result");
        for (final String key :
                List.of(
                        "model",
                        "effort",
                        "wall_min",
                        "vm_min",
                        "estimate_min",
                        "wait_reason",
                        "note")) {
            require(record.get(key) == null, "a move has no " + key);
        }
        for (final String key : TOKENS) {
            require(record.get(key) == null, "a move has no " + key);
        }
    }

    private static Instant instant(final Map<String, @Nullable Object> record, final String key) {
        try {
            return Instant.parse(string(record, key));
        } catch (final DateTimeException e) {
            throw new IllegalArgumentException("invalid record " + key, e);
        }
    }

    private static String string(final Map<String, @Nullable Object> record, final String key) {
        final Object value = record.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("missing or invalid " + key);
        }
        return text;
    }

    private static double number(final @Nullable Object value, final String key) {
        if (!(value instanceof Number numeric)) {
            throw new IllegalArgumentException("missing or invalid " + key);
        }
        final double number = numeric.doubleValue();
        require(Double.isFinite(number) && number >= 0, "invalid " + key);
        return number;
    }

    private static long integer(final @Nullable Object value, final String key) {
        final double number = number(value, key);
        require(
                number == Math.rint(number) && number <= 9007199254740991L,
                "invalid integer " + key);
        return (long) number;
    }

    private static void require(final boolean condition, final String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
