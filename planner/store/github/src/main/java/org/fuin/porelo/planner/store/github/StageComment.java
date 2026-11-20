package org.fuin.porelo.planner.store.github;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.StageEntry;
import org.jspecify.annotations.Nullable;

/**
 * The comment that holds one stage of an issue: the marker line, one line of words for a person,
 * and the stage's figures as a JSON block; and the sums an issue's stage comments add up to.
 */
public final class StageComment {

    /** The first line of every stage comment, and of no other. */
    public static final String MARKER = "<!-- porelo:stage -->";

    /** The longest stage comment read; a longer one was not written by the planner. */
    public static final int LONGEST = 16_384;

    /** The token kinds that count as new tokens: input, output and cache write. */
    public static final Set<String> NEW_TOKENS =
            Set.of(
                    "claude_input_tokens",
                    "claude_output_tokens",
                    "claude_cache_write_tokens",
                    "codex_input_tokens",
                    "codex_output_tokens",
                    "agy_input_tokens",
                    "agy_output_tokens");

    /** The one actor whose stages are summed. */
    private static final String AGENT = "agent";

    /** The JSON fields, in the order they are written. */
    private static final List<String> FIELDS =
            List.of(
                    "key",
                    "stage",
                    "result",
                    "actor",
                    "model",
                    "effort",
                    "wall_min",
                    "vm_min",
                    "tokens");

    private static final int MOST_TOKEN_KINDS = 32;

    /** The largest token count JSON carries exactly. */
    private static final long MOST_TOKENS = 9_007_199_254_740_991L;

    private static final Pattern WORD = Pattern.compile("[a-z][a-z_-]{0,31}");

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._][A-Za-z0-9._-]{0,127}");

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:/-]{1,256}");

    private static final Pattern TOKEN_KIND = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    /** A node id as GitHub gives it; anything else is not named in a fault. */
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_=-]{1,128}");

    private static final String OPENING = "```json";

    private static final String CLOSING = "```";

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StageComment() {}

    /** What a comment holds, once it is known to be a stage comment. */
    public sealed interface Read {}

    /**
     * A stage comment read whole.
     *
     * @param entry the stage
     * @param end when it ended: the comment's {@code createdAt}
     */
    public record Stage(StageEntry entry, Instant end) implements Read {

        /**
         * Answers when the stage started: its end less its minutes.
         *
         * @return its start
         */
        public Instant start() {
            return end.minus(Duration.ofMillis(Math.round(entry.minutes() * 60_000)));
        }
    }

    /**
     * A comment that starts as a stage comment but cannot be read as one, such as one edited by
     * hand.
     *
     * @param fault what is wrong, in fixed words naming the comment, never its text
     */
    public record Broken(String fault) implements Read {}

    /**
     * What an issue's stage comments add up to. The last estimate is no part of it: a stage comment
     * holds a stage's own minutes, not the minutes an estimate gave.
     *
     * @param minutes the summed stages' wall minutes
     * @param vmMinutes the minutes they waited on a machine
     * @param tokens the new tokens they spent: input, output and cache write
     * @param stages how many stages were summed
     * @param faults the broken stage comments, each in fixed words
     */
    public record Sums(
            double minutes, double vmMinutes, long tokens, int stages, List<String> faults) {

        /** Copies the faults. */
        public Sums {
            faults = List.copyOf(faults);
        }
    }

    /**
     * Writes a stage as its comment.
     *
     * @param entry the stage
     * @return the comment's text
     * @throws IllegalArgumentException when a value would not read back as written
     */
    public static String write(final StageEntry entry) {
        checked(entry);
        final ObjectNode node = JSON.createObjectNode();
        node.put("key", entry.key());
        node.put("stage", entry.stage());
        node.put("result", entry.result());
        node.put("actor", entry.actor());
        node.put("model", entry.model());
        node.put("effort", entry.effort());
        node.put("wall_min", entry.minutes());
        node.put("vm_min", entry.vmMinutes());
        final ObjectNode tokens = node.putObject("tokens");
        entry.tokens().forEach(tokens::put);
        final String json;
        try {
            // A fixed line feed, so the comment is the same on every system.
            json =
                    JSON.writer(
                                    new DefaultPrettyPrinter()
                                            .withObjectIndenter(new DefaultIndenter("  ", "\n")))
                            .writeValueAsString(node);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("A tree of plain values is always written", e);
        }
        final String comment =
                MARKER
                        + "\n"
                        + entry.stage()
                        + ": "
                        + entry.result()
                        + "\n"
                        + OPENING
                        + "\n"
                        + json
                        + "\n"
                        + CLOSING
                        + "\n";
        if (comment.length() > LONGEST) {
            throw new IllegalArgumentException(
                    "The stage comment would be longer than " + LONGEST + " characters");
        }
        return comment;
    }

    /**
     * Reads a comment as a stage comment.
     *
     * @param comment the comment
     * @return the stage, or what is broken in it, or empty when it is no stage comment
     */
    public static Optional<Read> read(final CommentView comment) {
        final String body = comment.body();
        if (!body.startsWith(MARKER + "\n") && !body.startsWith(MARKER + "\r\n")) {
            return Optional.empty();
        }
        if (body.length() > LONGEST) {
            return Optional.of(broken(comment, "is longer than " + LONGEST + " characters"));
        }
        final List<String> lines = List.of(body.replace("\r\n", "\n").split("\n", -1));
        int close = -1;
        for (int at = 3; at < lines.size(); at++) {
            if (lines.get(at).equals(CLOSING)) {
                close = at;
                break;
            }
        }
        if (lines.size() < 4
                || !lines.get(2).equals(OPENING)
                || close < 0
                || lines.subList(close + 1, lines.size()).stream().anyMatch(l -> !l.isBlank())) {
            return Optional.of(broken(comment, "does not hold one JSON block after its words"));
        }
        final JsonNode node;
        try {
            node = JSON.readTree(String.join("\n", lines.subList(3, close)));
        } catch (final JsonProcessingException e) {
            return Optional.of(broken(comment, "holds JSON that cannot be read"));
        }
        final StageEntry entry = entry(node);
        if (entry == null) {
            return Optional.of(broken(comment, "holds a field or a value a stage cannot have"));
        }
        return Optional.of(new Stage(entry, comment.createdAt()));
    }

    /**
     * Adds up an issue's stage comments: a closed agent stage of the measured kinds counts, once
     * per key, the first comment of a key the one counted; any other stage is not summed, and a
     * broken stage comment is named as a fault and left out.
     *
     * @param comments the issue's comments, oldest first
     * @return their sums
     */
    public static Sums sums(final List<CommentView> comments) {
        double minutes = 0;
        double vmMinutes = 0;
        long tokens = 0;
        int stages = 0;
        final List<String> faults = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        for (final CommentView comment : comments) {
            final Read read = read(comment).orElse(null);
            if (read instanceof Broken broken) {
                faults.add(broken.fault());
            } else if (read instanceof Stage stage
                    && keys.add(stage.entry().key())
                    && summed(stage.entry())) {
                final long added;
                try {
                    added = Math.addExact(tokens, newTokens(stage.entry()));
                } catch (final ArithmeticException e) {
                    faults.add(broken(comment, "makes the token sum overflow").fault());
                    continue;
                }
                tokens = added;
                minutes += stage.entry().minutes();
                vmMinutes += stage.entry().vmMinutes();
                stages++;
            }
        }
        return new Sums(minutes, vmMinutes, tokens, stages, faults);
    }

    /** Whether a stage counts: an agent's, of a measured kind; every stage comment is closed. */
    private static boolean summed(final StageEntry entry) {
        return AGENT.equals(entry.actor()) && StageEntry.MEASURED.contains(entry.stage());
    }

    private static long newTokens(final StageEntry entry) {
        long sum = 0;
        for (final Map.Entry<String, Long> kind : entry.tokens().entrySet()) {
            if (NEW_TOKENS.contains(kind.getKey())) {
                sum = Math.addExact(sum, kind.getValue());
            }
        }
        return sum;
    }

    private static Broken broken(final CommentView comment, final String what) {
        final String id = comment.id();
        return new Broken(
                (NODE_ID.matcher(id).matches() ? "The stage comment " + id : "A stage comment")
                        + " "
                        + what
                        + "; it is left out of the sums");
    }

    /** The stage a JSON block holds, or null when a field or a value is not one a stage has. */
    private static @Nullable StageEntry entry(final JsonNode node) {
        if (!node.isObject() || node.size() != FIELDS.size()) {
            return null;
        }
        final Set<String> names = new HashSet<>();
        node.properties().forEach(field -> names.add(field.getKey()));
        if (!names.equals(new HashSet<>(FIELDS))) {
            return null;
        }
        final String key = text(node.get("key"));
        final String stage = text(node.get("stage"));
        final String result = text(node.get("result"));
        final String actor = text(node.get("actor"));
        final JsonNode model = node.get("model");
        final JsonNode effort = node.get("effort");
        final JsonNode minutes = node.get("wall_min");
        final JsonNode vmMinutes = node.get("vm_min");
        final Map<String, Long> tokens = tokens(node.get("tokens"));
        if (key == null
                || stage == null
                || result == null
                || actor == null
                || !model.isNull() && !model.isTextual()
                || !effort.isNull() && !effort.isTextual()
                || !minutes.isNumber()
                || !vmMinutes.isNumber()
                || tokens == null) {
            return null;
        }
        final StageEntry entry;
        try {
            entry =
                    new StageEntry(
                            key,
                            stage,
                            result,
                            actor,
                            model.isNull() ? null : model.asText(),
                            effort.isNull() ? null : effort.asText(),
                            minutes.asDouble(),
                            vmMinutes.asDouble(),
                            tokens);
            checked(entry);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        return entry;
    }

    private static @Nullable String text(final @Nullable JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static @Nullable Map<String, Long> tokens(final JsonNode node) {
        if (!node.isObject() || node.size() > MOST_TOKEN_KINDS) {
            return null;
        }
        final Map<String, Long> tokens = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonNode> field : node.properties()) {
            final JsonNode count = field.getValue();
            if (!count.isIntegralNumber() || !count.canConvertToLong()) {
                return null;
            }
            tokens.put(field.getKey(), count.asLong());
        }
        return tokens;
    }

    /** Refuses a value the comment's shape or the sums cannot carry. */
    private static void checked(final StageEntry entry) {
        final @Nullable String model = entry.model();
        final @Nullable String effort = entry.effort();
        final boolean plain =
                KEY.matcher(entry.key()).matches()
                        && WORD.matcher(entry.stage()).matches()
                        && WORD.matcher(entry.result()).matches()
                        && WORD.matcher(entry.actor()).matches()
                        && (model == null || NAME.matcher(model).matches())
                        && (effort == null || NAME.matcher(effort).matches())
                        && entry.tokens().size() <= MOST_TOKEN_KINDS;
        if (!plain) {
            throw new IllegalArgumentException(
                    "A stage names a key, kind, result, actor, model, effort or token kind the"
                            + " stage comment cannot carry");
        }
        entry.tokens()
                .forEach(
                        (kind, count) -> {
                            if (!TOKEN_KIND.matcher(kind).matches() || count > MOST_TOKENS) {
                                throw new IllegalArgumentException(
                                        "A stage names a token kind or count the stage comment"
                                                + " cannot carry");
                            }
                        });
    }
}
