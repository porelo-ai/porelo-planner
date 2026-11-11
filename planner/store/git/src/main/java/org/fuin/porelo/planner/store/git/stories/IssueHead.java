package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An issue's data: the JSON head fenced {@code ```json} right below the heading of its file,
 * checked by {@code issue-1.json} and the repository's workflow. The index is generated from the
 * heads.
 *
 * @param id the issue's id, a sub-issue's with its dot
 * @param title the title its heading repeats
 * @param state a state of the workflow
 * @param size {@code XS} to {@code XL}, or a dash
 * @param priority Jira's priority, {@code Highest} to {@code Lowest}
 * @param rank the order within one priority of one state
 * @param blockedBy the ids of the same repository it waits on
 * @param covers what its index row says it covers
 * @param parent a sub-issue's parent, else null
 * @param role a sub-issue's role, else null
 * @param severity a defect's severity, else null
 * @param kind what the work is, else null
 * @param openQuestions the questions waiting on the operator, else null
 */
public record IssueHead(
        String id,
        String title,
        String state,
        String size,
        String priority,
        int rank,
        List<String> blockedBy,
        String covers,
        @Nullable String parent,
        @Nullable String role,
        @Nullable String severity,
        @Nullable String kind,
        @Nullable Integer openQuestions) {

    /** The fence that opens a head. */
    static final String FENCE = "```json";

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Copies the waits to keep the head immutable. */
    public IssueHead {
        blockedBy = List.copyOf(blockedBy);
    }

    /**
     * A file's head with where it stands.
     *
     * @param head the head
     * @param heading the file's first line
     * @param lines the file's lines, LF-separated
     * @param end the line after the closing fence
     */
    record Located(IssueHead head, String heading, List<String> lines, int end) {}

    /**
     * Reads the head of an issue's text.
     *
     * @param text the issue file's text
     * @param workflow the repository's states
     * @return the head, or null when the text has none
     * @throws IllegalArgumentException when the text has a head the schema or the workflow refuses,
     *     naming each fault
     */
    public static @Nullable IssueHead read(String text, Workflow workflow) {
        if (!present(text)) return null;
        final List<String> faults = new ArrayList<>();
        final Located located = locate(text, workflow, faults);
        if (located == null) throw new IllegalArgumentException("issue head: " + faults);
        return located.head();
    }

    /**
     * Whether a text has a head: its first line a heading and its next line that is not blank a
     * fence. Any fence there counts, so a malformed one is refused rather than read as no head.
     *
     * @param text the issue file's text
     * @return whether a head stands below the heading
     */
    public static boolean present(String text) {
        final List<String> lines = List.of(text.replace("\r\n", "\n").split("\n", -1));
        if (!lines.getFirst().startsWith("# ")) return false;
        final int open = open(lines);
        return open < lines.size() && lines.get(open).startsWith("```");
    }

    private static int open(List<String> lines) {
        int open = 1;
        while (open < lines.size() && lines.get(open).isBlank()) open++;
        return open;
    }

    /**
     * The ids a head names as data, its waits and its parent, read whether or not the head holds to
     * the rest of the schema: a check of citations takes them as data, not as citations.
     *
     * @param text the issue file's text
     * @return the ids in the head's order, none when the text has no head
     * @throws IllegalArgumentException when the text has a head whose waits or parent cannot be
     *     read, naming why
     */
    public static List<String> references(String text) {
        if (!present(text)) return List.of();
        final List<String> lines = List.of(text.replace("\r\n", "\n").split("\n", -1));
        final int end = lines(text);
        if (end == 0)
            throw new IllegalArgumentException(
                    "no " + FENCE + " head closed by ``` right below the heading");
        final JsonNode data;
        try {
            data = JSON.readTree(String.join("\n", lines.subList(open(lines) + 1, end - 1)));
        } catch (JacksonException ex) {
            throw new IllegalArgumentException(
                    "the head is no JSON: " + ex.getOriginalMessage(), ex);
        }
        if (data == null || !data.isObject())
            throw new IllegalArgumentException("the head is no JSON object");
        final List<String> ids = new ArrayList<>();
        final JsonNode waits = data.get("blockedBy");
        if (waits != null) {
            if (!waits.isArray())
                throw new IllegalArgumentException("/blockedBy: the waits are no list");
            for (final JsonNode wait : waits) {
                if (!wait.isTextual())
                    throw new IllegalArgumentException("/blockedBy: a wait is no string: " + wait);
                ids.add(wait.asText());
            }
        }
        final JsonNode parent = data.get("parent");
        if (parent != null) {
            if (!parent.isTextual())
                throw new IllegalArgumentException("/parent: the parent is no string: " + parent);
            ids.add(parent.asText());
        }
        return ids;
    }

    /**
     * How many lines the heading and the head take, the closing fence included.
     *
     * @param text the issue file's text
     * @return the count, or 0 when the text has no head that opens with {@code ```json} and is
     *     closed by its fence
     */
    public static int lines(String text) {
        if (!present(text)) return 0;
        final List<String> lines = List.of(text.replace("\r\n", "\n").split("\n", -1));
        final int open = open(lines);
        if (!lines.get(open).equals(FENCE)) return 0;
        for (int i = open + 1; i < lines.size(); i++) if (lines.get(i).equals("```")) return i + 1;
        return 0;
    }

    /**
     * Reads and checks a head, adding each fault without the file's name.
     *
     * @return the head, or null when it could not be read or a fault was found
     */
    static @Nullable Located locate(String text, Workflow workflow, List<String> faults) {
        final List<String> lines = List.of(text.replace("\r\n", "\n").split("\n", -1));
        int open = 1;
        while (open < lines.size() && lines.get(open).isBlank()) open++;
        int close = open + 1;
        // A JSON string holds no raw line break, so no line of the head is three backticks alone.
        while (close < lines.size() && !lines.get(close).equals("```")) close++;
        if (!lines.getFirst().startsWith("# ")
                || open >= lines.size()
                || !lines.get(open).equals(FENCE)
                || close >= lines.size()) {
            faults.add("no " + FENCE + " head closed by ``` right below the heading");
            return null;
        }
        final JsonNode data;
        try {
            data = JSON.readTree(String.join("\n", lines.subList(open + 1, close)));
        } catch (JacksonException ex) {
            faults.add("the head is no JSON: " + ex.getOriginalMessage());
            return null;
        }
        if (data == null) {
            faults.add("the head is empty");
            return null;
        }
        final List<String> errors = IssueSchema.faults(data);
        if (data.path("state").isTextual()) {
            try {
                workflow.order(data.path("state").asText());
            } catch (IllegalArgumentException ex) {
                errors.add("/state: " + ex.getMessage());
            }
        }
        faults.addAll(errors);
        if (!errors.isEmpty()) return null;
        final List<String> waits = new ArrayList<>();
        data.get("blockedBy").forEach(id -> waits.add(id.asText()));
        final IssueHead head =
                new IssueHead(
                        data.get("id").asText(),
                        data.get("title").asText(),
                        data.get("state").asText(),
                        data.get("size").asText(),
                        data.get("priority").asText(),
                        data.get("rank").intValue(),
                        waits,
                        data.get("covers").asText(),
                        text(data, "parent"),
                        text(data, "role"),
                        text(data, "severity"),
                        text(data, "kind"),
                        data.has("openQuestions") ? data.get("openQuestions").intValue() : null);
        return new Located(head, lines.getFirst(), lines, close + 1);
    }

    private static @Nullable String text(JsonNode data, String field) {
        return data.has(field) ? data.get(field).asText() : null;
    }

    /**
     * The heading the head gives.
     *
     * @return {@code # <id> — <title>}
     */
    public String heading() {
        return "# " + id + " — " + title;
    }

    /**
     * Writes a new issue's file: its heading, its head and the text below them.
     *
     * @param body the text below the head, ending with a line break
     * @return the file's text
     */
    public String file(String body) {
        return heading() + "\n\n" + FENCE + "\n" + json() + "\n```\n\n" + body;
    }

    /**
     * Writes the head as a file holds it: one field a line in the schema's order, the waits on one
     * line.
     *
     * @return the JSON, without fences
     */
    String json() {
        final List<String> fields = new ArrayList<>();
        fields.add(field("$schema", quoted(IssueSchema.NAME)));
        fields.add(field("id", quoted(id)));
        fields.add(field("title", quoted(title)));
        fields.add(field("state", quoted(state)));
        fields.add(field("size", quoted(size)));
        fields.add(field("priority", quoted(priority)));
        fields.add(field("rank", Integer.toString(rank)));
        fields.add(
                field(
                        "blockedBy",
                        "["
                                + String.join(
                                        ", ", blockedBy.stream().map(IssueHead::quoted).toList())
                                + "]"));
        fields.add(field("covers", quoted(covers)));
        if (parent != null) fields.add(field("parent", quoted(parent)));
        if (role != null) fields.add(field("role", quoted(role)));
        if (severity != null) fields.add(field("severity", quoted(severity)));
        if (kind != null) fields.add(field("kind", quoted(kind)));
        if (openQuestions != null)
            fields.add(field("openQuestions", Integer.toString(openQuestions)));
        return "{\n" + String.join(",\n", fields) + "\n}";
    }

    private static String field(String name, String value) {
        return "  \"" + name + "\": " + value;
    }

    private static String quoted(String text) {
        try {
            return JSON.writeValueAsString(text);
        } catch (JacksonException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
