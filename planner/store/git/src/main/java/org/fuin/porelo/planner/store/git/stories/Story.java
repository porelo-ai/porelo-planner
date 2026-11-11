package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A story as its file holds it: the heading, the text a person reads, and the JSON head under
 * {@code ## Data} at its end, checked by the story schema and the workflow as the stories index
 * checks it.
 *
 * @param id the story's id, {@code ST} and its number
 * @param title the title its heading repeats
 * @param state the state its head states, or null when a story-2 head leaves it to its issues
 * @param priority Jira's priority, {@code Highest} to {@code Lowest}
 * @param issues the issues it needs, each {@code <repository>#<number>}, in the head's order
 * @param body the text between the heading and {@code ## Data}, its leading and trailing blank
 *     lines dropped and every other line kept as it stands, indentation included
 */
public record Story(
        String id,
        String title,
        @Nullable String state,
        String priority,
        List<String> issues,
        String body) {

    /**
     * Refuses a missing field and copies the issues to keep the story immutable.
     *
     * @throws NullPointerException when a field but the state is null, or an issue is
     */
    public Story {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(priority, "priority");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        Objects.requireNonNull(body, "body");
    }

    /**
     * Reads a story from its file's text, with the parser the stories index uses.
     *
     * @param text the story file's text
     * @param workflow the states a story may be in
     * @return the story
     * @throws IllegalArgumentException when the index would refuse the story's head or heading: its
     *     message is each fault as the index reports it, without the file's name, joined by {@code
     *     "; "}
     */
    public static Story read(String text, Workflow workflow) {
        final List<String> faults = new ArrayList<>();
        final Heads.Parsed parsed = Heads.parse(text, faults, workflow);
        if (parsed == null || !faults.isEmpty())
            throw new IllegalArgumentException(String.join("; ", faults));
        final JsonNode data = parsed.data();
        final List<String> issues = new ArrayList<>();
        data.get("issues").forEach(issue -> issues.add(issue.asText()));
        final List<String> lines = parsed.lines();
        int first = 1;
        int end = lines.lastIndexOf(Heads.DATA);
        while (first < end && lines.get(first).isBlank()) first++;
        while (end > first && lines.get(end - 1).isBlank()) end--;
        return new Story(
                data.get("id").asText(),
                data.get("title").asText(),
                data.has("state") ? data.get("state").asText() : null,
                data.get("priority").asText(),
                issues,
                String.join("\n", lines.subList(first, end)));
    }
}
