package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads the JSON head of a Markdown file: its first line is the heading, and the file ends in a
 * section {@code ## Data} holding the head fenced {@code ```json}, checked by a JSON schema. The
 * text a person reads comes first. A story's file has one; an issue's will have one too, with a
 * schema of its own.
 */
final class Heads {

    /**
     * A file's head.
     *
     * @param file the file
     * @param heading its first line
     * @param data the head
     * @param lines the file's lines
     * @param jsonStart first line inside the JSON fence
     * @param jsonEnd closing fence line, excluded from the JSON
     */
    record Head(
            Path file,
            String heading,
            JsonNode data,
            List<String> lines,
            int jsonStart,
            int jsonEnd) {}

    /** The section the head stands in, the file's last. */
    static final String DATA = "## Data";

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Heads() {}

    /**
     * A text's head, without the file it stands in.
     *
     * @param heading its first line
     * @param data the head
     * @param lines the text's lines
     * @param jsonStart first line inside the JSON fence
     * @param jsonEnd closing fence line, excluded from the JSON
     */
    record Parsed(String heading, JsonNode data, List<String> lines, int jsonStart, int jsonEnd) {}

    /**
     * Reads a file's head and checks it against the schema and its heading against the head.
     *
     * @param file the file
     * @param faults where each fault is added, naming the file and the field
     * @return the head, or {@code null} when it could not be read or failed the schema
     * @throws IOException when the file cannot be read
     */
    static @Nullable Head read(Path file, List<String> faults, Workflow workflow)
            throws IOException {
        final String name = String.valueOf(file.getFileName());
        final List<String> found = new ArrayList<>();
        final Parsed parsed =
                parse(Files.readString(file, StandardCharsets.UTF_8), found, workflow);
        for (final String fault : found) faults.add(name + ": " + fault);
        return parsed == null
                ? null
                : new Head(
                        file,
                        parsed.heading(),
                        parsed.data(),
                        parsed.lines(),
                        parsed.jsonStart(),
                        parsed.jsonEnd());
    }

    /**
     * Reads a story's head from its text and checks it against the schema and its heading against
     * the head; the one parser of story heads.
     *
     * @param text the story file's text
     * @param faults where each fault is added, naming the field but no file
     * @return the head, or {@code null} when it could not be read or failed the schema; a head
     *     whose heading differs is returned with that fault added
     */
    static @Nullable Parsed parse(String text, List<String> faults, Workflow workflow) {
        final List<String> lines = List.of(text.split("\n", -1));
        if (!lines.getFirst().startsWith("# ")) {
            faults.add("the first line is no heading");
            return null;
        }
        int end = lines.size();
        while (end > 1 && lines.get(end - 1).isBlank()) {
            end--;
        }
        final int section = lines.lastIndexOf(DATA);
        int open = section + 1;
        while (section > 0 && open < end && lines.get(open).isBlank()) {
            open++;
        }
        if (section <= 0
                || open >= end
                || !lines.get(open).equals("```json")
                || !lines.get(end - 1).equals("```")
                || end - 1 <= open) {
            faults.add("no ```json head under " + DATA + " at the end");
            return null;
        }
        // A JSON string holds no raw line break, so no line of the head is three backticks alone.
        final int close = end - 1 - (open + 1);
        final JsonNode data;
        try {
            data = JSON.readTree(String.join("\n", lines.subList(open + 1, open + 1 + close)));
        } catch (JacksonException ex) {
            faults.add("the head is no JSON: " + ex.getOriginalMessage());
            return null;
        }
        final List<String> errors = StorySchema.faults(data);
        if (data.path("state").isTextual()
                && !workflow.states().contains(data.path("state").asText())) {
            try {
                workflow.order(data.path("state").asText());
            } catch (IllegalArgumentException ex) {
                errors.add("/state: " + ex.getMessage());
            }
        }
        faults.addAll(errors);
        if (!errors.isEmpty()) return null;
        final String heading = "# " + data.get("id").asText() + " — " + data.get("title").asText();
        if (!lines.getFirst().equals(heading)) {
            faults.add("the heading is not '" + heading + "'");
        }
        return new Parsed(lines.getFirst(), data, lines, open + 1, end - 1);
    }

    /** Inserts the schema marker without serializing or rearranging the existing JSON. */
    static String storyTwo(Head head) throws IOException {
        String json = String.join("\n", head.lines().subList(head.jsonStart(), head.jsonEnd()));
        if (!head.data().get("issues").isEmpty()) json = withoutState(json);
        final int brace = json.indexOf('{');
        final int newline = json.indexOf('\n', brace + 1);
        final String marker = "  \"$schema\": \"story-2.json\",";
        if (newline >= 0 && json.substring(brace + 1, newline).isBlank()) {
            final String ending = json.charAt(newline - 1) == '\r' ? "\r\n" : "\n";
            return json.substring(0, newline + 1) + marker + ending + json.substring(newline + 1);
        }
        return json.substring(0, brace + 1) + "\n" + marker + "\n" + json.substring(brace + 1);
    }

    private static String withoutState(String json) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                final int start = Math.toIntExact(parser.currentTokenLocation().getCharOffset());
                final String name = parser.currentName();
                parser.nextToken();
                parser.skipChildren();
                parser.finishToken();
                if ("state".equals(name)) {
                    return removeField(
                            json, start, Math.toIntExact(parser.currentLocation().getCharOffset()));
                }
            }
        }
        throw new IOException("legacy story head has no state field");
    }

    private static String removeField(String json, int start, int end) {
        int next = end;
        while (next < json.length() && Character.isWhitespace(json.charAt(next))) next++;
        if (next < json.length() && json.charAt(next) == ',') {
            end = next + 1;
        } else {
            // A final field owns the preceding separator instead of a following one.
            int previous = start - 1;
            while (previous >= 0 && Character.isWhitespace(json.charAt(previous))) previous--;
            if (previous >= 0 && json.charAt(previous) == ',')
                return json.substring(0, previous) + json.substring(end);
        }
        final int lineStart = json.lastIndexOf('\n', start) + 1;
        final int newline = json.indexOf('\n', end);
        if (newline >= 0
                && json.substring(lineStart, start).isBlank()
                && json.substring(end, newline).isBlank())
            return json.substring(0, lineStart) + json.substring(newline + 1);
        return json.substring(0, start) + json.substring(end);
    }
}
