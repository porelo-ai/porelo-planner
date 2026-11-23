package org.fuin.porelo.planner.store.github;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.StageEntry;
import org.jspecify.annotations.Nullable;

/**
 * The comment that holds one stage of an issue: the marker line, one line of words for a person,
 * and its context as a JSON block, with the number fields as they stood before the stage was added
 * to them. The comments are the course of the work a person reads; the board never reads them, and
 * its number fields are what GitHub holds.
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

    /** The one actor whose stages add to an issue's number fields. */
    private static final String AGENT = "agent";

    /** The stage context fields, in the order they are written. */
    private static final List<String> FIELDS =
            List.of("key", "stage", "result", "actor", "model", "effort");

    private static final Set<String> FIGURES = Set.of("wall_min", "vm_min", "tokens");

    /** The JSON field that holds the number fields a summed stage was added to. */
    private static final String BASE = "base";

    /** The number fields a base names, in the order they are written. */
    private static final List<String> BASE_FIELDS =
            List.of("wall_min", "vm_min", "tokens", "stages");

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
     * The issue's number fields as they stood right before a stage was added to them.
     *
     * @param minutes the field Minutes
     * @param vmMinutes the field VM minutes
     * @param tokens the field Tokens
     * @param stages the field Stages
     */
    public record Base(double minutes, double vmMinutes, double tokens, double stages) {

        /** Requires finite figures. */
        public Base {
            if (!Double.isFinite(minutes)
                    || !Double.isFinite(vmMinutes)
                    || !Double.isFinite(tokens)
                    || !Double.isFinite(stages)) {
                throw new IllegalArgumentException("A stage's base is a finite number each");
            }
        }
    }

    /**
     * A stage comment read whole.
     *
     * @param entry the stage context, with its figures ignored
     * @param end when it ended: the comment's {@code createdAt}
     * @param base the number fields the stage was added to, or null when the comment names none, as
     *     one written after its figures does
     */
    public record Stage(StageEntry entry, Instant end, @Nullable Base base) implements Read {}

    /**
     * A comment that starts as a stage comment but cannot be read as one, such as one edited by
     * hand.
     *
     * @param fault what is wrong, in fixed words naming the comment, never its text
     */
    public record Broken(String fault) implements Read {}

    /**
     * Writes a stage as its comment.
     *
     * @param entry the stage
     * @param base the number fields the stage is added to, or null for a stage not summed
     * @return the comment's text
     * @throws IllegalArgumentException when a value cannot be carried by a comment or number field
     */
    public static String write(final StageEntry entry, final @Nullable Base base) {
        checked(entry);
        final ObjectNode node = JSON.createObjectNode();
        node.put("key", entry.key());
        node.put("stage", entry.stage());
        node.put("result", entry.result());
        node.put("actor", entry.actor());
        node.put("model", entry.model());
        node.put("effort", entry.effort());
        if (base != null) {
            final ObjectNode written = node.putObject(BASE);
            written.put("wall_min", base.minutes());
            written.put("vm_min", base.vmMinutes());
            written.put("tokens", base.tokens());
            written.put("stages", base.stages());
        }
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
        final JsonNode written = node.isObject() ? ((ObjectNode) node).remove(BASE) : null;
        final StageEntry entry = entry(node);
        final Base base = written == null ? null : base(written);
        if (entry == null || written != null && base == null) {
            return Optional.of(broken(comment, "holds a field or a value a stage cannot have"));
        }
        return Optional.of(new Stage(entry, comment.createdAt(), base));
    }

    /**
     * Whether a stage adds to an issue's number fields: an agent's, of a measured kind; every stage
     * comment is closed.
     */
    static boolean summed(final StageEntry entry) {
        return AGENT.equals(entry.actor()) && StageEntry.MEASURED.contains(entry.stage());
    }

    /** The new tokens a stage spent: input, output and cache write. */
    static long newTokens(final StageEntry entry) {
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
                        + "; it is not read as a stage");
    }

    /** The stage a JSON block holds, or null when a field or a value is not one a stage has. */
    private static @Nullable StageEntry entry(final JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        final Set<String> names = new HashSet<>();
        node.properties().forEach(field -> names.add(field.getKey()));
        names.removeAll(FIGURES);
        if (!names.equals(new HashSet<>(FIELDS))) {
            return null;
        }
        final String key = text(node.get("key"));
        final String stage = text(node.get("stage"));
        final String result = text(node.get("result"));
        final String actor = text(node.get("actor"));
        final JsonNode model = node.get("model");
        final JsonNode effort = node.get("effort");
        if (key == null
                || stage == null
                || result == null
                || actor == null
                || !model.isNull() && !model.isTextual()
                || !effort.isNull() && !effort.isTextual()) {
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
                            0,
                            0,
                            Map.of());
            checked(entry);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        return entry;
    }

    /** The base a JSON block holds, or null when it holds other fields or values. */
    private static @Nullable Base base(final JsonNode node) {
        if (!node.isObject() || node.size() != BASE_FIELDS.size()) {
            return null;
        }
        final double[] figures = new double[BASE_FIELDS.size()];
        for (int at = 0; at < figures.length; at++) {
            final JsonNode figure = node.get(BASE_FIELDS.get(at));
            if (figure == null || !figure.isNumber() || !Double.isFinite(figure.asDouble())) {
                return null;
            }
            figures[at] = figure.asDouble();
        }
        return new Base(figures[0], figures[1], figures[2], figures[3]);
    }

    private static @Nullable String text(final @Nullable JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** Refuses a value the comment's shape or the number fields cannot carry. */
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
