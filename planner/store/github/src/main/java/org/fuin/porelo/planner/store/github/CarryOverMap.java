package org.fuin.porelo.planner.store.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;

/**
 * A carry-over's own map of old id to new tracker reference, with how far each item came, kept in a
 * file of the run's own while it copies and never on GitHub. It names the project's checkout and
 * the GitHub project it was made for, so a run again with it goes on only for those.
 */
public final class CarryOverMap {

    /** The form of the file this class writes. */
    static final int VERSION = 1;

    /** An old id, a story's or an issue's in the Markdown store's form. */
    private static final Pattern OLD_ID =
            Pattern.compile("ST[0-9]{1,5}|[A-Z]{1,4}[0-9]{1,5}(\\.[0-9]{1,3})?");

    /** A node id or a project item's id as GitHub gives them. */
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_=-]{1,200}");

    /** A digest of a file, as hex. */
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How far an item came, in the order a run takes the steps. */
    public enum Progress {
        /** About to be made, at {@link Entry#since}; it may stand on GitHub without its number. */
        PENDING,
        /** Made on GitHub. */
        CREATED,
        /** Added to the project. */
        ON_PROJECT,
        /** Its field values written. */
        FIELDS_SET,
        /** Its sub-issues, dependencies, body and stage comments written. */
        PASS2_DONE;

        /** The name the file holds. */
        String written() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One item's place.
     *
     * @param repository its repository
     * @param since when its making began, which a run again searches from while it is pending
     * @param progress how far it came
     * @param number its number on GitHub, 0 while pending
     * @param nodeId its node id, empty while pending
     * @param databaseId its database id, 0 while pending
     * @param itemId its project item's id, empty until it is on the project
     */
    public record Entry(
            String repository,
            Instant since,
            Progress progress,
            int number,
            String nodeId,
            long databaseId,
            String itemId) {

        /** Refuses an entry whose values do not fit its progress. */
        public Entry {
            Ids.name(repository);
            final boolean made = progress != Progress.PENDING;
            if (made != (number > 0) || made != !nodeId.isEmpty() || made != (databaseId > 0)) {
                throw new IllegalArgumentException("An entry is made exactly when it has a number");
            }
            if (made && !NODE_ID.matcher(nodeId).matches()) {
                throw new IllegalArgumentException("An entry's node id is not GitHub's form");
            }
            final boolean onProject = progress.compareTo(Progress.ON_PROJECT) >= 0;
            if (onProject != !itemId.isEmpty()
                    || (onProject && !NODE_ID.matcher(itemId).matches())) {
                throw new IllegalArgumentException(
                        "An entry has a project item exactly when it is on the project");
            }
        }

        /**
         * An item about to be made.
         *
         * @param repository its repository
         * @param since when its making began
         * @return the entry
         */
        public static Entry pending(final String repository, final Instant since) {
            return new Entry(repository, since, Progress.PENDING, 0, "", 0, "");
        }

        /**
         * Answers the entry one step on.
         *
         * @param progress the step reached
         * @return the entry
         */
        public Entry at(final Progress progress) {
            return new Entry(repository, since, progress, number, nodeId, databaseId, itemId);
        }

        /**
         * Answers its tracker reference.
         *
         * @return {@code <repository>#<number>}
         * @throws IllegalStateException while it is pending
         */
        public String reference() {
            if (progress == Progress.PENDING) {
                throw new IllegalStateException("A pending item has no reference yet");
            }
            return repository + "#" + number;
        }
    }

    private final String root;

    private final String target;

    private final Instant began;

    private @Nullable String projectFile;

    private final Map<String, Entry> entries;

    private CarryOverMap(
            final String root,
            final String target,
            final Instant began,
            final @Nullable String projectFile,
            final Map<String, Entry> entries) {
        if (root.isBlank()) {
            throw new IllegalArgumentException("A map names the project's checkout");
        }
        ProjectRef.parse(target);
        if (projectFile != null && !DIGEST.matcher(projectFile).matches()) {
            throw new IllegalArgumentException("A map's digest of project.yml is no SHA-256");
        }
        this.root = root;
        this.target = target;
        this.began = began;
        this.projectFile = projectFile;
        this.entries = new LinkedHashMap<>(entries);
    }

    /**
     * Begins an empty map.
     *
     * @param root the real path of the project's own checkout
     * @param target the GitHub project, {@code <owner>/<number>}
     * @param began when the run began
     * @return the map
     */
    public static CarryOverMap begin(final String root, final String target, final Instant began) {
        return new CarryOverMap(root, target, began, null, Map.of());
    }

    /**
     * Answers the real path of the project's own checkout it was made for.
     *
     * @return the path
     */
    public String root() {
        return root;
    }

    /**
     * Answers the GitHub project it was made for.
     *
     * @return {@code <owner>/<number>}
     */
    public String target() {
        return target;
    }

    /**
     * Answers when its first run began.
     *
     * @return the time
     */
    public Instant began() {
        return began;
    }

    /**
     * Answers the SHA-256 of {@code project.yml} as the run is about to write it, once it is.
     *
     * @return the digest as hex, or null before
     */
    public @Nullable String projectFile() {
        return projectFile;
    }

    /**
     * Records the SHA-256 of {@code project.yml} as the run is about to write it, so a run again
     * knows the change in the checkout as its own.
     *
     * @param digest the digest as hex
     */
    public void projectFile(final String digest) {
        if (!DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("A digest of project.yml is a SHA-256 in hex");
        }
        this.projectFile = digest;
    }

    /**
     * Answers every entry, by old id, in the order they were made.
     *
     * @return the entries
     */
    public Map<String, Entry> entries() {
        return Collections.unmodifiableMap(entries);
    }

    /**
     * Answers one item's entry.
     *
     * @param id its old id
     * @return the entry, or null when none was begun
     */
    public @Nullable Entry entry(final String id) {
        return entries.get(id);
    }

    /**
     * Sets one item's entry.
     *
     * @param id its old id
     * @param entry the entry
     */
    public void put(final String id, final Entry entry) {
        if (!OLD_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not an old id, " + id.length() + " characters");
        }
        entries.put(id, entry);
    }

    /**
     * Writes the map as the file holds it.
     *
     * @return the file's bytes
     */
    public byte[] json() {
        final ObjectNode node = JSON.createObjectNode();
        node.put("version", VERSION);
        node.put("root", root);
        node.put("target", target);
        node.put("began", began.toString());
        if (projectFile != null) {
            node.put("projectFile", projectFile);
        }
        final ObjectNode items = node.putObject("items");
        entries.forEach(
                (id, entry) -> {
                    final ObjectNode item = items.putObject(id);
                    item.put("repository", entry.repository());
                    item.put("since", entry.since().toString());
                    item.put("progress", entry.progress().written());
                    item.put("number", entry.number());
                    item.put("nodeId", entry.nodeId());
                    item.put("databaseId", entry.databaseId());
                    item.put("itemId", entry.itemId());
                });
        try {
            return JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(node)
                    .concat("\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("A tree of plain values is always written", e);
        }
    }

    /**
     * Reads a map from its file's bytes.
     *
     * @param bytes the bytes
     * @return the map
     * @throws IllegalArgumentException when they are no map of this form, without repeating them
     */
    public static CarryOverMap read(final byte[] bytes) {
        final JsonNode node;
        try {
            node = JSON.readTree(bytes);
        } catch (final java.io.IOException e) {
            // A parser's words can quote the file, so they are not passed on.
            throw new IllegalArgumentException("The carry-over's map is no JSON");
        }
        if (node == null
                || !node.isObject()
                || !Set.of("version", "root", "target", "began", "projectFile", "items")
                        .containsAll(fieldNames(node))
                || node.path("version").asInt(-1) != VERSION
                || !node.path("version").isInt()) {
            throw new IllegalArgumentException("The carry-over's map is not of the form written");
        }
        try {
            final JsonNode digest = node.get("projectFile");
            final CarryOverMap map =
                    new CarryOverMap(
                            text(node, "root"),
                            text(node, "target"),
                            Instant.parse(text(node, "began")),
                            digest == null ? null : text(node, "projectFile"),
                            Map.of());
            final JsonNode items = node.path("items");
            if (!items.isObject()) {
                throw new IllegalArgumentException("no items");
            }
            final Iterator<Map.Entry<String, JsonNode>> each = items.properties().iterator();
            while (each.hasNext()) {
                final Map.Entry<String, JsonNode> item = each.next();
                final JsonNode value = item.getValue();
                if (!value.isObject()
                        || !Set.of(
                                        "repository",
                                        "since",
                                        "progress",
                                        "number",
                                        "nodeId",
                                        "databaseId",
                                        "itemId")
                                .equals(Set.copyOf(fieldNames(value)))
                        || !value.path("number").isInt()
                        || !value.path("databaseId").isIntegralNumber()) {
                    throw new IllegalArgumentException("an item of another form");
                }
                map.put(
                        item.getKey(),
                        new Entry(
                                text(value, "repository"),
                                Instant.parse(text(value, "since")),
                                progress(text(value, "progress")),
                                value.path("number").intValue(),
                                text(value, "nodeId"),
                                value.path("databaseId").longValue(),
                                text(value, "itemId")));
            }
            return map;
        } catch (final IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "The carry-over's map is not of the form written", e);
        }
    }

    private static java.util.List<String> fieldNames(final JsonNode node) {
        final java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String text(final JsonNode node, final String name) {
        final JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(name + " is no text");
        }
        return value.textValue();
    }

    private static Progress progress(final String written) {
        for (final Progress progress : Progress.values()) {
            if (progress.written().equals(written)) {
                return progress;
            }
        }
        throw new IllegalArgumentException("no progress of that name");
    }
}
