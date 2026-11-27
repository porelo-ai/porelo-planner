package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.core.FieldsOfMany;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Story;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads the body of a call that sets the fields of many issues and the priorities of many stories,
 * as {@code openapi/planner.yaml} describes it, into the asks the core judges. A refusal never
 * repeats what was sent.
 */
final class ManyFieldsRequests {

    /** The most issues one body holds. */
    static final int MOST_ISSUES = 200;

    /** The most stories one body holds. */
    static final int MOST_STORIES = 100;

    private ManyFieldsRequests() {}

    /**
     * One issue's fields and story to set, as the description names them.
     *
     * @param issue the issue's checked id
     * @param priority the priority, or null when it is left as it is
     * @param size the size, {@link #CLEAR} to clear it, or null when it is left as it is
     * @param story the story's checked id, or null when the issue's story is left as it is
     */
    record IssueFieldsSet(
            String issue,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String priority,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String size,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String story) {

        /** The size that stands for a size sent as null, which clears it. */
        static final String CLEAR = "";
    }

    /**
     * One story's priority to set, as the description names it.
     *
     * @param story the story's checked id
     * @param priority the priority
     */
    record StoryPrioritySet(String story, String priority) {}

    /**
     * Fields of many issues and priorities of stories, as the description names them.
     *
     * @param issues the issues, in the order sent, or null when none is sent
     * @param stories the stories, in the order sent, or null when none is sent
     */
    record ManyFields(
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<IssueFieldsSet> issues,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<StoryPrioritySet> stories) {

        /**
         * Answers the issues as the core judges them.
         *
         * @return the asks, in the order sent
         */
        List<FieldsOfMany.IssueAsk> issueAsks() {
            final List<FieldsOfMany.IssueAsk> asks = new ArrayList<>();
            final List<IssueFieldsSet> sent = issues == null ? List.of() : issues;
            for (final IssueFieldsSet set : sent) {
                final Set<Fields.Field> named = EnumSet.noneOf(Fields.Field.class);
                if (set.priority() != null) {
                    named.add(Fields.Field.PRIORITY);
                }
                @Nullable String size = set.size();
                if (size != null) {
                    named.add(Fields.Field.SIZE);
                    if (size.equals(IssueFieldsSet.CLEAR)) {
                        size = null;
                    }
                }
                asks.add(
                        new FieldsOfMany.IssueAsk(
                                set.issue(),
                                new Fields(named, size, null, set.priority(), null),
                                set.story()));
            }
            return asks;
        }

        /**
         * Answers the stories as the core judges them.
         *
         * @return the asks, in the order sent
         */
        List<FieldsOfMany.StoryAsk> storyAsks() {
            final List<FieldsOfMany.StoryAsk> asks = new ArrayList<>();
            final List<StoryPrioritySet> sent = stories == null ? List.of() : stories;
            for (final StoryPrioritySet set : sent) {
                asks.add(new FieldsOfMany.StoryAsk(set.story(), set.priority()));
            }
            return asks;
        }
    }

    /**
     * Reads the body: at most 65536 bytes, one JSON object with at most 200 issues and 100 stories,
     * at least one item among them; an issue holds its id and at least one of a priority, a size
     * (or null) and a story; a story holds exactly its id and a priority.
     *
     * @param request the request
     * @param project the project's checked name, which a refused id names
     * @return the body
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code EditNotAllowed} for a body that is
     *     no such object
     * @throws Refusal {@code InvalidName} for an id that is none
     */
    static ManyFields read(final HttpServletRequest request, final String project) {
        final JsonNode body;
        try {
            body = WorkRequests.read(request);
        } catch (final WorkRefusal invalid) {
            throw Edits.refused("", "", "The body is not one JSON object of its size.");
        }
        members(body, "", Set.of("issues", "stories"));
        final List<IssueFieldsSet> issues = new ArrayList<>();
        for (final JsonNode item : list(body, "issues", MOST_ISSUES)) {
            issues.add(issue(item, project));
        }
        final List<StoryPrioritySet> stories = new ArrayList<>();
        for (final JsonNode item : list(body, "stories", MOST_STORIES)) {
            stories.add(story(item, project));
        }
        if (issues.isEmpty() && stories.isEmpty()) {
            throw Edits.refused("", "", "The body names no issue and no story.");
        }
        return new ManyFields(issues, stories);
    }

    private static List<JsonNode> list(final JsonNode body, final String name, final int most) {
        if (!body.has(name)) {
            return List.of();
        }
        final JsonNode list = body.get(name);
        if (!list.isArray() || list.size() > most) {
            throw Edits.refused(
                    "", name, "The member " + name + " must be a list of at most " + most + ".");
        }
        final List<JsonNode> items = new ArrayList<>();
        list.forEach(items::add);
        return items;
    }

    private static IssueFieldsSet issue(final JsonNode item, final String project) {
        members(item, "", Set.of("issue", "priority", "size", "story"));
        final String id = id(item, "issue", project);
        @Nullable String priority = null;
        if (item.has("priority")) {
            priority = priority(item.get("priority"), id);
        }
        @Nullable String size = null;
        if (item.has("size")) {
            final JsonNode given = item.get("size");
            if (given.isNull()) {
                size = IssueFieldsSet.CLEAR;
            } else if (given.isString() && Fields.SIZES.contains(given.asString())) {
                size = given.asString();
            } else {
                throw Edits.refused(id, "size", "The size must be XS, S, M, L, XL or null.");
            }
        }
        @Nullable String story = null;
        if (item.has("story")) {
            story = id(item, "story", project);
        }
        return new IssueFieldsSet(id, priority, size, story);
    }

    private static StoryPrioritySet story(final JsonNode item, final String project) {
        members(item, "", Set.of("story", "priority"));
        final String id = id(item, "story", project);
        if (!item.has("priority")) {
            throw Edits.refused(id, "priority", "The body names no priority.");
        }
        return new StoryPrioritySet(id, priority(item.get("priority"), id));
    }

    private static String priority(final JsonNode given, final String item) {
        if (!given.isString() || !Story.PRIORITIES.contains(given.asString())) {
            throw Edits.refused(
                    item, "priority", "The priority must be Highest, High, Medium, Low or Lowest.");
        }
        return given.asString();
    }

    private static String id(final JsonNode item, final String name, final String project) {
        final JsonNode given = item.get(name);
        if (given == null) {
            throw Edits.refused("", name, "Each item names its " + name + ".");
        }
        try {
            final String text = given.isString() ? given.asString() : "";
            return name.equals("story") ? Ids.story(text) : Ids.issue(text);
        } catch (final IllegalArgumentException invalid) {
            throw Refusal.invalidName(
                    "The " + name + " id is invalid.", Map.of("project", project));
        }
    }

    private static void members(final JsonNode node, final String item, final Set<String> known) {
        if (!node.isObject()) {
            throw Edits.refused(item, "", "The body must hold JSON objects of the described form.");
        }
        for (final String name : node.propertyNames()) {
            if (!known.contains(name)) {
                // The unknown member's name is the caller's, so it is not repeated.
                throw Edits.refused(item, "", "The body holds a member it does not take.");
            }
        }
    }
}
