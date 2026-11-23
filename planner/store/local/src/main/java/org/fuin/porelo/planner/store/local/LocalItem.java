package org.fuin.porelo.planner.store.local;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.github.FieldNames;
import org.fuin.porelo.planner.store.github.IssueMapping;
import org.fuin.porelo.planner.store.github.ItemView;
import org.jspecify.annotations.Nullable;

/**
 * One item file: a JSON head of the form {@code local-item-1}, the body's sections as a GitHub
 * issue holds them, and the item's stages in a section of their own after a fixed line, which the
 * body's sections never read or edit.
 */
final class LocalItem {

    /** The head's form, named in the head and by its schema. */
    static final String FORMAT = "local-item-1";

    /** The line the stages' section starts with; a body may not hold it. */
    static final String STAGES_LINE = "<!-- porelo:local-stages -->";

    private static final String STAGES = "\n" + STAGES_LINE + "\n## Stages\n\n";

    private static final String OPENING = "```json\n";

    private static final String CLOSING = "\n```\n";

    /** Reads JSON strictly: a key twice or anything after the value is refused. */
    static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** A fixed line feed, so a file is the same on every system. */
    private static final ObjectWriter PRETTY =
            JSON.writer(
                    new DefaultPrettyPrinter()
                            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                            .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    /** The head's keys beside the fields. */
    static final Set<String> STRUCTURE =
            Set.of(
                    "format",
                    "kind",
                    "title",
                    "repository",
                    "number",
                    "parent",
                    "issues",
                    "blockedBy");

    /**
     * The fields a head holds: the GitHub project's fields and Priority, which GitHub keeps in the
     * organization's issue field and a head beside the others.
     */
    static final Set<String> FIELDS = headFields();

    private static final Set<String> CHOICES =
            Set.of(
                    FieldNames.STATUS,
                    FieldNames.PRIORITY,
                    FieldNames.SIZE,
                    FieldNames.RISK,
                    FieldNames.WORKER);

    private static Set<String> headFields() {
        final Set<String> fields = new java.util.LinkedHashSet<>(FieldNames.ALL.keySet());
        fields.add(FieldNames.PRIORITY);
        return java.util.Collections.unmodifiableSet(fields);
    }

    private final ObjectNode head;

    private String body;

    private final List<LocalStage.Entry> stages;

    private final List<Warning> warnings;

    /** The stages' section as it was read, kept to be written back when it cannot be read. */
    private final @Nullable String unreadStages;

    private LocalItem(
            final ObjectNode head,
            final String body,
            final List<LocalStage.Entry> stages,
            final List<Warning> warnings,
            final @Nullable String unreadStages) {
        this.head = head;
        this.body = body;
        this.stages = new ArrayList<>(stages);
        this.warnings = List.copyOf(warnings);
        this.unreadStages = unreadStages;
    }

    /**
     * Reads an item file's text.
     *
     * @param text the file's text, its line ends line feeds
     * @param repository the repository its name gives
     * @param number the number its name gives
     * @return the item
     * @throws IllegalArgumentException in fixed words, which never repeat the text, when the file
     *     names no item or another than its name does
     */
    static LocalItem read(final String text, final String repository, final int number) {
        if (!text.startsWith(OPENING)) {
            throw new IllegalArgumentException("it does not start with a JSON head");
        }
        final int close = text.indexOf(CLOSING, OPENING.length() - 1);
        if (close < 0) {
            throw new IllegalArgumentException("its JSON head is not closed");
        }
        final JsonNode read;
        try {
            read = JSON.readTree(text.substring(OPENING.length(), close));
        } catch (final IOException e) {
            throw new IllegalArgumentException("its JSON head cannot be read");
        }
        if (read == null || !read.isObject()) {
            throw new IllegalArgumentException("its JSON head is no object");
        }
        final ObjectNode head = (ObjectNode) read;
        if (!head.path("repository").isTextual()
                || !head.path("repository").asText().equals(repository)
                || !head.path("number").isIntegralNumber()
                || !head.path("number").canConvertToInt()
                || head.path("number").asInt() != number) {
            throw new IllegalArgumentException(
                    "its head names another repository or number than its name");
        }
        final List<Warning> warnings = new ArrayList<>();
        if (!Set.of("issue", "story").contains(head.path("kind").asText())) {
            warnings.add(
                    new Warning(
                            "LocalKindUnknown",
                            "Its head names no kind of story or issue; it is shown as an issue.",
                            Set.of(Warning.Concern.KIND)));
        }
        if (!shaped(head)) {
            warnings.add(
                    new Warning(
                            "LocalHeadUnclear",
                            "Its head holds a key or a value that " + FORMAT + " does not know.",
                            Set.of(Warning.Concern.TEXT)));
        }
        final String rest = text.substring(close + CLOSING.length());
        final int at = rest.indexOf(STAGES);
        final String body = at < 0 ? rest : rest.substring(0, at);
        final List<LocalStage.Entry> stages = new ArrayList<>();
        String unread = null;
        if (at >= 0) {
            try {
                stages.addAll(stages(rest.substring(at + STAGES.length())));
            } catch (final IOException | IllegalArgumentException | DateTimeException e) {
                unread = rest.substring(at);
                warnings.add(
                        new Warning(
                                "LocalStagesUnclear",
                                "Its stages cannot be read; no stage is saved over them.",
                                Set.of(Warning.Concern.FIGURES)));
            }
        }
        return new LocalItem(head, body, stages, warnings, unread);
    }

    /** The stages' section: one JSON array of entries, each key once, oldest first. */
    private static List<LocalStage.Entry> stages(final String section) throws IOException {
        if (!section.startsWith(OPENING) || !section.endsWith(CLOSING)) {
            throw new IllegalArgumentException("the stages are not one JSON block");
        }
        final JsonNode array =
                JSON.readTree(
                        section.substring(OPENING.length(), section.length() - CLOSING.length()));
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("the stages are no array");
        }
        final List<LocalStage.Entry> read = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        Instant previous = Instant.MIN;
        for (final JsonNode node : array) {
            final LocalStage.Entry entry = LocalStage.read(node);
            if (!keys.add(entry.stage().key()) || entry.end().isBefore(previous)) {
                throw new IllegalArgumentException("the stages repeat a key or are out of order");
            }
            read.add(entry);
            previous = entry.end();
        }
        return read;
    }

    /** Whether every key is one of the form's and every value of its kind. */
    private static boolean shaped(final ObjectNode head) {
        if (!FORMAT.equals(head.path("format").asText()) || !head.path("title").isTextual()) {
            return false;
        }
        for (final Map.Entry<String, JsonNode> field : head.properties()) {
            final String name = field.getKey();
            final JsonNode value = field.getValue();
            final boolean fits;
            if (CHOICES.contains(name)) {
                fits = value.isTextual();
            } else if (FieldNames.ALL.containsKey(name)) {
                fits = value.isNumber();
            } else if (name.equals("parent")) {
                fits = value.isNull() || value.isTextual();
            } else if (name.equals("issues") || name.equals("blockedBy")) {
                boolean texts = value.isArray();
                for (final JsonNode link : value) {
                    texts &= link.isTextual();
                }
                fits = texts;
            } else {
                fits = STRUCTURE.contains(name);
            }
            if (!fits) {
                return false;
            }
        }
        return true;
    }

    /**
     * A new item, with no fields, parent, issues or blockers yet.
     *
     * @param repository its repository
     * @param number its number
     * @param story whether it is a story
     * @param title its title
     * @param body its body
     * @return the item
     */
    static LocalItem fresh(
            final String repository,
            final int number,
            final boolean story,
            final String title,
            final String body) {
        final ObjectNode head = JSON.createObjectNode();
        head.put("format", FORMAT);
        head.put("kind", story ? "story" : "issue");
        head.put("title", title);
        head.put("repository", repository);
        head.put("number", number);
        head.putNull("parent");
        head.putArray("issues");
        head.putArray("blockedBy");
        return new LocalItem(head, body, List.of(), List.of(), null);
    }

    /**
     * A copy to change, the one read staying as it was.
     *
     * @return the copy
     */
    LocalItem copy() {
        return new LocalItem(head.deepCopy(), body, stages, warnings, unreadStages);
    }

    String id() {
        return repository() + "#" + number();
    }

    String repository() {
        return head.path("repository").asText();
    }

    int number() {
        return head.path("number").asInt();
    }

    String fileName() {
        return repository() + "-" + number() + ".md";
    }

    boolean story() {
        return "story".equals(head.path("kind").asText());
    }

    /** The title, or the id for a head that names none. */
    String title() {
        final JsonNode title = head.path("title");
        return title.isTextual() && !title.asText().isBlank() ? title.asText() : id();
    }

    void title(final String title) {
        head.put("title", title);
    }

    String body() {
        return body;
    }

    void body(final String text) {
        body = text;
    }

    List<Warning> warnings() {
        return warnings;
    }

    List<LocalStage.Entry> stages() {
        return List.copyOf(stages);
    }

    /**
     * The parent the head names, or null for none; a value of another kind names no item.
     *
     * @return the parent's id
     */
    @Nullable String parent() {
        final JsonNode parent = head.get("parent");
        if (parent == null || parent.isNull()) {
            return null;
        }
        return parent.isTextual() ? parent.asText() : "";
    }

    void parent(final @Nullable String id) {
        if (id == null) {
            head.putNull("parent");
        } else {
            head.put("parent", id);
        }
    }

    /**
     * A list of ids the head names; an entry of another kind names no item.
     *
     * @param field {@code issues} or {@code blockedBy}
     * @return the ids, in their order
     */
    List<String> links(final String field) {
        final JsonNode array = head.path(field);
        if (!array.isArray()) {
            return List.of();
        }
        final List<String> links = new ArrayList<>();
        array.forEach(node -> links.add(node.isTextual() ? node.asText() : ""));
        return links;
    }

    void links(final String field, final List<String> ids) {
        final ArrayNode array = head.putArray(field);
        ids.forEach(array::add);
    }

    /**
     * Sets a choice field, or removes it for null.
     *
     * @param field the field's name
     * @param value the option's name, or null
     */
    void choice(final String field, final @Nullable String value) {
        if (value == null) {
            head.remove(field);
        } else {
            head.put(field, value);
        }
    }

    /**
     * Sets a number field, a whole number written without a fraction, or removes it for null.
     *
     * @param field the field's name
     * @param value the number, or null
     */
    void number(final String field, final @Nullable Double value) {
        if (value == null) {
            head.remove(field);
        } else if (value == Math.rint(value) && Math.abs(value) < 9.007199254740991E15) {
            head.put(field, value.longValue());
        } else {
            head.put(field, value);
        }
    }

    /**
     * The fields the head holds, by name, each as text, as a GitHub item gives them.
     *
     * @return the fields
     */
    Map<String, String> fields() {
        final Map<String, String> fields = new LinkedHashMap<>();
        for (final String name : FIELDS) {
            final JsonNode value = head.get(name);
            if (value != null && !value.isNull()) {
                fields.put(name, value.isValueNode() ? value.asText() : value.toString());
            }
        }
        return fields;
    }

    /**
     * The item as the GitHub store's mapping reads one: its id stands for its node id, it is open
     * unless its state is closed, a closed state after the first reads as closed not planned, as
     * the GitHub store writes one, and its link names it alone.
     *
     * @param workflow the project's workflow
     * @param issues the issues a story lists
     * @return the view
     */
    ItemView view(final Workflow workflow, final List<String> issues) {
        final String status = fields().getOrDefault(FieldNames.STATUS, "");
        final int closed = workflow.closed().indexOf(status);
        return new ItemView(
                        id(),
                        id(),
                        repository(),
                        number(),
                        title(),
                        body,
                        closed < 0,
                        closed < 0 ? null : closed == 0 ? "COMPLETED" : "NOT_PLANNED",
                        fields(),
                        List.of(),
                        parent(),
                        issues,
                        links("blockedBy"),
                        URI.create("local:" + repository() + "-" + number()),
                        Instant.EPOCH,
                        false,
                        List.of())
                .typed(story() ? IssueMapping.STORY_TYPE : null);
    }

    /**
     * Whether a stage of the key is kept.
     *
     * @param key the stage's key
     * @return true when one is
     */
    boolean hasStage(final String key) {
        return stages.stream().anyMatch(stage -> stage.stage().key().equals(key));
    }

    /**
     * Whether its stages could be read, so another may be added.
     *
     * @return true when they could
     */
    boolean stagesWhole() {
        return unreadStages == null;
    }

    /**
     * Adds a stage, keeping them oldest first.
     *
     * @param entry the stage
     * @throws IllegalStateException when its stages could not be read
     */
    void append(final LocalStage.Entry entry) {
        if (unreadStages != null) {
            throw new IllegalStateException("No stage is added to stages that cannot be read");
        }
        stages.add(entry);
        stages.sort(Comparator.comparing(LocalStage.Entry::end));
    }

    /**
     * The file's text.
     *
     * @return the text
     * @throws IllegalArgumentException when the body holds the stages' line
     */
    String text() {
        if (body.contains(STAGES_LINE)) {
            throw new IllegalArgumentException("its text holds the line its stages start with");
        }
        final StringBuilder text = new StringBuilder();
        text.append(OPENING).append(pretty(head)).append(CLOSING).append(body);
        if (unreadStages != null) {
            // Kept as read, so a slip by hand can be mended and no stage is lost.
            text.append(unreadStages);
        } else if (!stages.isEmpty()) {
            final ArrayNode array = JSON.createArrayNode();
            stages.forEach(stage -> array.add(LocalStage.write(stage)));
            text.append(STAGES).append(OPENING).append(pretty(array)).append(CLOSING);
        }
        return text.toString();
    }

    private static String pretty(final JsonNode node) {
        try {
            return PRETTY.writeValueAsString(node);
        } catch (final IOException e) {
            throw new IllegalStateException("A tree of plain values is always written", e);
        }
    }
}
