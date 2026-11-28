package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Worker;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads a board change as {@code openapi/planner.yaml} describes it, strictly: a member it does not
 * take, a bound broken, a key named twice or the same as an id of the board, or an issue changed
 * twice refuses the whole body as {@code EditNotAllowed} before anything is judged or written.
 */
final class BoardChangeRequests {

    /** The most new issues, and the most changed issues, one change holds. */
    static final int MOST_ITEMS = 100;

    /** The most blockers one item names. */
    static final int MOST_BLOCKERS = 100;

    private static final int LONGEST_TITLE = 256;

    private static final int LONGEST_REFERENCE = 200;

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final Set<String> CHANGE_MEMBERS = Set.of("creates", "updates");

    private static final Set<String> NEW_MEMBERS =
            Set.of(
                    "key",
                    "repository",
                    "title",
                    "parts",
                    "size",
                    "state",
                    "priority",
                    "worker",
                    "story",
                    "blockedBy");

    private static final Set<String> UPDATE_MEMBERS =
            Set.of(
                    "issue",
                    "title",
                    "parts",
                    "size",
                    "estimateMinutes",
                    "state",
                    "priority",
                    "worker",
                    "story",
                    "blockedBy");

    private BoardChangeRequests() {}

    /**
     * A board change: new issues, then changes of existing issues.
     *
     * @param creates the new issues, in the order sent
     * @param updates the changes of existing issues, in the order sent
     */
    record Change(
            @JsonInclude(JsonInclude.Include.NON_NULL) List<NewItem> creates,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Update> updates) {}

    /**
     * One new issue.
     *
     * @param key the caller's key of it
     * @param repository the repository it is made in
     * @param title its title
     * @param parts its text by parts, What must be true among them
     * @param size its size, or null for none
     * @param state the state it is made in, or null for the workflow's first
     * @param priority its priority, or null for none
     * @param worker who works it, or null for the planner's default
     * @param story the story it is hung into, an id or key, or null for none
     * @param blockedBy its blockers, each an id or key
     */
    record NewItem(
            String key,
            String repository,
            String title,
            Map<TextEdit.Part, String> parts,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String size,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String state,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String priority,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Worker worker,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String story,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> blockedBy) {}

    /**
     * A size an update names: a size, or null to remove it.
     *
     * @param value the size, or null
     */
    record Size(@Nullable String value) {}

    /**
     * The changes of one existing issue; a member left out stays as it is.
     *
     * @param issue the issue's id
     * @param title its new title, or null to keep it
     * @param parts the parts written and removed, or null to keep its text
     * @param size its new size, or null to keep it
     * @param estimateMinutes its new estimate in minutes, or null to keep it
     * @param state the state it moves to, or null to keep it
     * @param priority its new priority, or null to keep it
     * @param worker who works it from now, or null to keep it
     * @param story the story it is hung into, an id or key, or null to keep it
     * @param blockedBy its whole new list of blockers, each an id or key, or null to keep it
     */
    record Update(
            String issue,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String title,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable TextEdit parts,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Size size,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Estimate estimateMinutes,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String state,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String priority,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Worker worker,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String story,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> blockedBy) {}

    /**
     * An estimate an update names: minutes within the bounds setIssueFields takes, or null to clear
     * it.
     *
     * @param minutes the estimate in minutes, or null
     */
    record Estimate(@Nullable Integer minutes) {}

    /**
     * Reads a board change of at most {@link AccessFilter#MOST_LARGE_BODY} bytes.
     *
     * @param request the request
     * @param board the board as it stands, read only when the body holds a new issue
     * @return the change
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code EditNotAllowed} for a body that is
     *     no board change, one naming a key twice or a key the board holds as an id, or one
     *     changing an issue twice
     */
    static Change change(final HttpServletRequest request, final Supplier<Board> board) {
        final JsonNode body;
        try {
            body = WorkRequests.read(request, AccessFilter.MOST_LARGE_BODY);
        } catch (final WorkRefusal invalid) {
            throw refused("", "The body is not one JSON object of its size.");
        }
        members(body, CHANGE_MEMBERS);
        final List<NewItem> creates = new ArrayList<>();
        for (final JsonNode item : list(body, "creates", MOST_ITEMS)) {
            creates.add(newItem(item));
        }
        final List<Update> updates = new ArrayList<>();
        for (final JsonNode item : list(body, "updates", MOST_ITEMS)) {
            updates.add(update(item));
        }
        if (creates.isEmpty() && updates.isEmpty()) {
            throw refused("", "The body names no new and no changed issue.");
        }
        final Set<String> keys = new HashSet<>();
        for (final NewItem item : creates) {
            if (!keys.add(item.key())) {
                throw refused("key", "A key is named twice.");
            }
        }
        final Set<String> changed = new HashSet<>();
        for (final Update item : updates) {
            if (!changed.add(item.issue())) {
                // The answer names every item once, so one issue is not changed twice.
                throw refused("issue", "An issue is changed twice.");
            }
        }
        if (!keys.isEmpty()) {
            final Board held = board.get();
            for (final String key : keys) {
                if (held.issue(key).isPresent() || held.story(key).isPresent()) {
                    // A reference names a key before the board's item, so no key shadows an id.
                    throw refused("key", "A key is never an id of the board.");
                }
            }
        }
        return new Change(List.copyOf(creates), List.copyOf(updates));
    }

    private static NewItem newItem(final JsonNode item) {
        object(item);
        members(item, NEW_MEMBERS);
        final String key = required(item, "key");
        if (!KEY.matcher(key).matches()) {
            throw refused("key", "A key is 1 to 64 letters, digits, '_' or '-'.");
        }
        final String repository = required(item, "repository");
        final String title = title(required(item, "title"));
        final JsonNode parts = item.get("parts");
        if (parts == null) {
            throw refused("parts", "A new issue names its parts.");
        }
        final Map<TextEdit.Part, String> written = new EnumMap<>(TextEdit.Part.class);
        final Set<TextEdit.Part> removed = EnumSet.noneOf(TextEdit.Part.class);
        parts(parts, written, removed);
        if (!removed.isEmpty() || !written.containsKey(TextEdit.Part.WHAT_MUST_BE_TRUE)) {
            throw refused("parts", "A new issue's parts are texts, What must be true among them.");
        }
        final @Nullable String size = optional(item, "size");
        if (size != null && !Fields.SIZES.contains(size)) {
            throw refused("size", "The size must be XS, S, M, L or XL.");
        }
        return new NewItem(
                key,
                repository,
                title,
                Map.copyOf(written),
                size,
                state(optional(item, "state")),
                priority(optional(item, "priority")),
                worker(optional(item, "worker")),
                reference(optional(item, "story"), "story"),
                references(item));
    }

    private static Update update(final JsonNode item) {
        object(item);
        members(item, UPDATE_MEMBERS);
        final String issue;
        try {
            issue = Ids.issue(required(item, "issue"));
        } catch (final IllegalArgumentException invalid) {
            throw refused("issue", "The issue must be an issue's id.");
        }
        final @Nullable String title = optional(item, "title");
        @Nullable TextEdit parts = null;
        if (item.has("parts")) {
            final Map<TextEdit.Part, String> written = new EnumMap<>(TextEdit.Part.class);
            final Set<TextEdit.Part> removed = EnumSet.noneOf(TextEdit.Part.class);
            parts(item.get("parts"), written, removed);
            parts = new TextEdit(TextEdit.Kind.ISSUE, null, written, removed);
        }
        @Nullable Size size = null;
        if (item.has("size")) {
            final JsonNode value = item.get("size");
            if (value.isNull()) {
                size = new Size(null);
            } else if (value.isString() && Fields.SIZES.contains(value.asString())) {
                size = new Size(value.asString());
            } else {
                throw refused("size", "The size must be XS, S, M, L, XL or null.");
            }
        }
        return new Update(
                issue,
                title == null ? null : title(title),
                parts,
                size,
                estimate(item),
                state(optional(item, "state")),
                priority(optional(item, "priority")),
                worker(optional(item, "worker")),
                reference(optional(item, "story"), "story"),
                item.has("blockedBy") ? references(item) : null);
    }

    private static @Nullable Estimate estimate(final JsonNode item) {
        if (!item.has("estimateMinutes")) {
            return null;
        }
        final JsonNode value = item.get("estimateMinutes");
        if (value.isNull()) {
            return new Estimate(null);
        }
        if (!value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.asInt() < 1
                || value.asInt() > Fields.MOST_ESTIMATE) {
            throw refused(
                    "estimateMinutes",
                    "The estimate must be a whole number of 1 to "
                            + Fields.MOST_ESTIMATE
                            + " minutes, or null.");
        }
        return new Estimate(value.asInt());
    }

    private static void parts(
            final JsonNode parts,
            final Map<TextEdit.Part, String> written,
            final Set<TextEdit.Part> removed) {
        if (!parts.isObject()) {
            throw refused("parts", "The parts must be one JSON object.");
        }
        for (final String name : parts.propertyNames()) {
            final TextEdit.Part part = EditRequests.ISSUE_PARTS.get(name);
            if (part == null) {
                // The unknown member's name is the caller's, so it is not repeated.
                throw refused("parts", "The parts hold a member they do not take.");
            }
            final JsonNode value = parts.get(name);
            if (value.isNull()) {
                if (!part.removable()) {
                    throw refused(name, "The member " + name + " is never removed.");
                }
                removed.add(part);
            } else if (value.isString()) {
                written.put(part, value.asString());
            } else {
                throw refused(name, "The member " + name + " must be a text or null.");
            }
        }
    }

    private static List<JsonNode> list(final JsonNode body, final String name, final int most) {
        final JsonNode list = body.get(name);
        if (list == null) {
            return List.of();
        }
        if (!list.isArray() || list.size() > most) {
            throw refused(name, "The member " + name + " must be a list of at most " + most + ".");
        }
        final List<JsonNode> items = new ArrayList<>();
        list.forEach(items::add);
        return items;
    }

    private static List<String> references(final JsonNode item) {
        final List<String> references = new ArrayList<>();
        for (final JsonNode reference : list(item, "blockedBy", MOST_BLOCKERS)) {
            if (!reference.isString()) {
                throw refused("blockedBy", "Each blocker must be an id or a key.");
            }
            references.add(reference(reference.asString(), "blockedBy"));
        }
        return List.copyOf(references);
    }

    private static @Nullable String reference(final @Nullable String reference, final String name) {
        if (reference != null && (reference.isEmpty() || reference.length() > LONGEST_REFERENCE)) {
            throw refused(name, "A reference is 1 to 200 characters.");
        }
        return reference;
    }

    private static String title(final String title) {
        if (title.isEmpty() || title.length() > LONGEST_TITLE) {
            throw refused("title", "The title is 1 to 256 characters.");
        }
        return title;
    }

    private static @Nullable String state(final @Nullable String state) {
        if (state != null && !BoardController.STATE.matcher(state).matches()) {
            throw refused("state", "The state must be a state's name.");
        }
        return state;
    }

    private static @Nullable String priority(final @Nullable String priority) {
        if (priority != null && !Story.PRIORITIES.contains(priority)) {
            throw refused("priority", "The priority must be Highest to Lowest.");
        }
        return priority;
    }

    private static @Nullable Worker worker(final @Nullable String worker) {
        if (worker == null) {
            return null;
        }
        for (final Worker known : Worker.values()) {
            if (known.name().equals(worker)) {
                return known;
            }
        }
        throw refused("worker", "The worker must be AGENT or MANUAL.");
    }

    private static void object(final JsonNode item) {
        if (!item.isObject()) {
            throw refused("", "Each item must be one JSON object.");
        }
    }

    private static void members(final JsonNode body, final Set<String> known) {
        for (final String name : body.propertyNames()) {
            if (!known.contains(name)) {
                // The unknown member's name is the caller's, so it is not repeated.
                throw refused("", "The body holds a member it does not take.");
            }
        }
    }

    private static String required(final JsonNode item, final String name) {
        final @Nullable String value = optional(item, name);
        if (value == null) {
            throw refused(name, "The item names no " + name + ".");
        }
        return value;
    }

    private static @Nullable String optional(final JsonNode item, final String name) {
        final JsonNode value = item.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isString()) {
            throw refused(name, "The member " + name + " must be a text.");
        }
        return value.asString();
    }

    private static RuntimeException refused(final String field, final String reason) {
        return Edits.refused("", field, reason);
    }
}
