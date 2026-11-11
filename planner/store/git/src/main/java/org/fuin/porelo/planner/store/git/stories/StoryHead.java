package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A new story's head, as {@code story-2.json} holds it: written into a file the stories index then
 * completes, and read back from one.
 *
 * @param id the story's id, {@code ST} and its number
 * @param title its title, one line
 * @param state the state it states, or null when its issues give it
 * @param gain what a user gains, one line
 * @param test the test the person runs at its end, one line
 * @param priority its priority, {@code Highest} to {@code Lowest}
 * @param issues the issues it needs, each {@code <repository>#<id>}
 */
public record StoryHead(
        String id,
        String title,
        @Nullable String state,
        String gain,
        String test,
        String priority,
        List<String> issues) {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern REFERENCE = Pattern.compile(StorySchema.ISSUE);

    /** Copies the issues to keep the head immutable. */
    public StoryHead {
        issues = List.copyOf(issues);
    }

    /**
     * Names an issue as a story's head names it, refusing what the schema would.
     *
     * @param repository the issue's repository
     * @param id the issue's id
     * @return {@code <repository>#<id>}
     * @throws IllegalArgumentException when the schema takes no such reference
     */
    public static String reference(final String repository, final String id) {
        final String reference = repository + "#" + id;
        if (!REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException(
                    "a story cannot name the issue "
                            + reference.replaceAll("\\p{Cntrl}", "?")
                            + ": it takes "
                            + StorySchema.ISSUE);
        }
        return reference;
    }

    /**
     * Writes a new story's file: its heading and its head under {@code ## Data}; the stories index
     * writes the text a person reads between them.
     *
     * @return the file's text
     */
    public String file() {
        final List<String> fields = new ArrayList<>();
        fields.add(field("$schema", quoted("story-2.json")));
        fields.add(field("id", quoted(id)));
        fields.add(field("title", quoted(title)));
        if (state != null) {
            fields.add(field("state", quoted(state)));
        }
        fields.add(field("gain", quoted(gain)));
        fields.add(field("test", quoted(test)));
        fields.add(field("priority", quoted(priority)));
        fields.add(
                field(
                        "issues",
                        "["
                                + String.join(", ", issues.stream().map(StoryHead::quoted).toList())
                                + "]"));
        fields.add(field("tested", "null"));
        return "# "
                + id
                + " — "
                + title
                + "\n\n"
                + Heads.DATA
                + "\n\n```json\n{\n"
                + String.join(",\n", fields)
                + "\n}\n```\n";
    }

    /**
     * Reads a story's head as the stories index reads it.
     *
     * @param text the story file's text
     * @param workflow the states a story may be in
     * @return the head
     * @throws IllegalArgumentException when the index would refuse the head or the heading, naming
     *     each fault
     */
    public static StoryHead read(final String text, final Workflow workflow) {
        final List<String> faults = new ArrayList<>();
        final Heads.Parsed parsed = Heads.parse(text, faults, workflow);
        if (parsed == null || !faults.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", faults));
        }
        final JsonNode data = parsed.data();
        final List<String> issues = new ArrayList<>();
        data.get("issues").forEach(issue -> issues.add(issue.asText()));
        return new StoryHead(
                data.get("id").asText(),
                data.get("title").asText(),
                data.has("state") ? data.get("state").asText() : null,
                data.get("gain").asText(),
                data.get("test").asText(),
                data.get("priority").asText(),
                issues);
    }

    private static String field(final String name, final String value) {
        return "  \"" + name + "\": " + value;
    }

    private static String quoted(final String text) {
        try {
            return JSON.writeValueAsString(text);
        } catch (JacksonException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
