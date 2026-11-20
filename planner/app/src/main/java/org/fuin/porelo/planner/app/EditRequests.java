package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.core.NewIdeas;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.TextEdit;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Reads the bodies of the edits a person keeps the board with strictly, each into its record; a
 * refusal names the member it is about in fixed words and never repeats what was sent.
 */
final class EditRequests {

    private EditRequests() {}

    /**
     * A new story with its issues, as the description names it.
     *
     * @param idea the idea, each member null where it is missing or no text
     * @param revision the revision the caller read
     */
    record StoryRequest(NewIdeas.Idea idea, String revision) {}

    /**
     * A new story as read: the request and the faults of the idea's shape, each by its field.
     *
     * @param request the request
     * @param shape the members the idea format does not name, by where they stand
     */
    record NewStory(StoryRequest request, List<String> shape) {}

    /**
     * An issue's fields as read.
     *
     * @param fields the fields to set
     * @param revision the revision the caller read
     */
    record FieldsEdit(Fields fields, String revision) {}

    /**
     * A story's whole issue list, as the description names it.
     *
     * @param issues the issues' checked ids, in the story's order
     * @param revision the revision the caller read
     */
    record StoryIssuesRequest(List<String> issues, String revision) {}

    /**
     * An issue's whole list of blockers, as the description names it.
     *
     * @param blockedBy the blockers' checked ids
     * @param revision the revision the caller read
     */
    record BlockedByRequest(List<String> blockedBy, String revision) {}

    /**
     * A comment, as the description names it.
     *
     * @param text the person's text
     */
    record CommentRequest(String text) {}

    /**
     * A story's or an issue's title and named parts as read.
     *
     * @param edit the change
     * @param revision the revision the caller read
     */
    record TextRequest(TextEdit edit, String revision) {}

    /** The members of an issue's text edit, each naming a part, in the description's order. */
    static final Map<String, TextEdit.Part> ISSUE_PARTS = parts(TextEdit.Kind.ISSUE);

    /** The members of a story's text edit, each naming a part, in the description's order. */
    static final Map<String, TextEdit.Part> STORY_PARTS = parts(TextEdit.Kind.STORY);

    private static final String NO_STORY = "The body is no new story.";

    /** A revision as the description's {@code Revision} gives it. */
    private static final Pattern REVISION = Pattern.compile("[!-~]{1,200}");

    /**
     * Reads a new story: at most 65536 bytes, one JSON object with exactly an idea object and a
     * revision.
     *
     * @param request the request
     * @return the story and the faults of its idea's shape
     * @throws WorkRefusal {@code IdeaRefused} for a body that is no new story
     */
    static NewStory story(final HttpServletRequest request) {
        final JsonNode body;
        try {
            body = WorkRequests.read(request);
        } catch (final WorkRefusal invalid) {
            throw noStory("body: " + NO_STORY);
        }
        if (!Set.of("idea", "revision").equals(Set.copyOf(body.propertyNames()))) {
            throw noStory("body: The body must hold exactly an idea and a revision.");
        }
        if (!isRevision(body.get("revision"))) {
            throw noStory("revision: The revision must be a text.");
        }
        final JsonNode idea = body.get("idea");
        if (!idea.isObject()) {
            throw noStory("idea: The idea must be an object with a story and issues.");
        }
        final List<String> shape = new ArrayList<>();
        return new NewStory(
                new StoryRequest(WorkRequests.idea(idea, shape), body.get("revision").asString()),
                shape);
    }

    /**
     * Reads an issue's fields: at most 65536 bytes, one JSON object with a revision and any of
     * size, estimateMinutes, priority and rank, each of its kind and within its bounds.
     *
     * @param request the request
     * @param issue the issue's checked id, which a refusal names
     * @return the fields and the revision
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code EditNotAllowed} for a body that is
     *     no change of fields
     */
    static FieldsEdit fields(final HttpServletRequest request, final String issue) {
        final JsonNode body = object(request, issue, WorkRequests::read);
        members(body, issue, Set.of("size", "estimateMinutes", "priority", "rank", "revision"));
        final String revision = revision(body, issue);
        final Set<Fields.Field> named = EnumSet.noneOf(Fields.Field.class);
        final String size = text(body, "size", issue, named, Fields.Field.SIZE);
        final Integer estimate =
                number(body, "estimateMinutes", issue, named, Fields.Field.ESTIMATE);
        final String priority = text(body, "priority", issue, named, Fields.Field.PRIORITY);
        final Integer rank = number(body, "rank", issue, named, Fields.Field.RANK);
        try {
            return new FieldsEdit(new Fields(named, size, estimate, priority, rank), revision);
        } catch (final IllegalArgumentException outOfBounds) {
            // Fields' words are fixed and never repeat a value.
            throw Edits.refused(issue, "", String.valueOf(outOfBounds.getMessage()));
        }
    }

    /**
     * Reads a story's whole issue list: at most 65536 bytes, one JSON object with exactly a list of
     * issue ids and a revision.
     *
     * @param request the request
     * @param story the story's checked id, which a refusal names
     * @return the list and the revision
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code EditNotAllowed} for a body that is
     *     no issue list
     */
    static StoryIssuesRequest storyIssues(final HttpServletRequest request, final String story) {
        final JsonNode body = object(request, story, WorkRequests::read);
        members(body, story, Set.of("issues", "revision"));
        exactly(body, story, Set.of("issues", "revision"));
        return new StoryIssuesRequest(
                ids(body, "issues", story, Edits.MOST_ISSUES), revision(body, story));
    }

    /**
     * Reads an issue's whole list of blockers: at most 65536 bytes, one JSON object with exactly a
     * list of issue ids and a revision.
     *
     * @param request the request
     * @param issue the issue's checked id, which a refusal names
     * @return the list and the revision
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code EditNotAllowed} for a body that is
     *     no list of blockers
     */
    static BlockedByRequest blockedBy(final HttpServletRequest request, final String issue) {
        final JsonNode body = object(request, issue, WorkRequests::read);
        members(body, issue, Set.of("blockedBy", "revision"));
        exactly(body, issue, Set.of("blockedBy", "revision"));
        return new BlockedByRequest(
                ids(body, "blockedBy", issue, Edits.MOST_BLOCKERS), revision(body, issue));
    }

    /**
     * Reads a comment: at most {@link AccessFilter#MOST_BODY} bytes, one JSON object with exactly a
     * text.
     *
     * @param request the request
     * @param issue the issue's checked id, which a refusal names
     * @return the comment
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code EditNotAllowed} for a body that is
     *     no comment
     */
    static CommentRequest comment(final HttpServletRequest request, final String issue) {
        final JsonNode body =
                object(request, issue, read -> WorkRequests.read(read, AccessFilter.MOST_BODY));
        exactly(body, issue, Set.of("text"));
        final JsonNode text = body.get("text");
        if (!text.isString()) {
            throw Edits.refused(issue, "text", "The text must be a text.");
        }
        return new CommentRequest(text.asString());
    }

    /**
     * Reads a story's or an issue's title and named parts: at most {@link AccessFilter#MOST_BODY}
     * bytes, one JSON object with a revision and any of a title and the kind's parts, each a text,
     * null removing a part that may be removed.
     *
     * @param request the request
     * @param id the item's checked id, which a refusal names
     * @param kind whether a story or an issue is changed
     * @return the change and the revision
     * @throws org.fuin.porelo.planner.core.Moves.Refused {@code EditNotAllowed} for a body that is
     *     no change of that kind's text
     */
    static TextRequest text(
            final HttpServletRequest request, final String id, final TextEdit.Kind kind) {
        final JsonNode body =
                object(request, id, read -> WorkRequests.read(read, AccessFilter.MOST_BODY));
        final Map<String, TextEdit.Part> parts =
                kind == TextEdit.Kind.STORY ? STORY_PARTS : ISSUE_PARTS;
        final Set<String> known = new java.util.HashSet<>(parts.keySet());
        known.add("title");
        known.add("revision");
        members(body, id, known);
        final String revision = revision(body, id);
        @Nullable String title = null;
        if (body.has("title")) {
            final JsonNode value = body.get("title");
            if (!value.isString()) {
                throw Edits.refused(id, "title", "The title must be a text; it is never removed.");
            }
            title = value.asString();
        }
        final Map<TextEdit.Part, String> written = new EnumMap<>(TextEdit.Part.class);
        final Set<TextEdit.Part> removed = EnumSet.noneOf(TextEdit.Part.class);
        for (final Map.Entry<String, TextEdit.Part> part : parts.entrySet()) {
            final String name = part.getKey();
            if (!body.has(name)) {
                continue;
            }
            final JsonNode value = body.get(name);
            if (value.isNull()) {
                if (!part.getValue().removable()) {
                    throw Edits.refused(id, name, "The member " + name + " is never removed.");
                }
                removed.add(part.getValue());
            } else if (value.isString()) {
                written.put(part.getValue(), value.asString());
            } else {
                throw Edits.refused(id, name, "The member " + name + " must be a text or null.");
            }
        }
        return new TextRequest(new TextEdit(kind, title, written, removed), revision);
    }

    private static Map<String, TextEdit.Part> parts(final TextEdit.Kind kind) {
        final Map<String, TextEdit.Part> parts = new java.util.LinkedHashMap<>();
        for (final TextEdit.Part part : TextEdit.Part.values()) {
            if (part.of() == kind) {
                parts.put(Edits.member(part), part);
            }
        }
        return java.util.Collections.unmodifiableMap(parts);
    }

    private static JsonNode object(
            final HttpServletRequest request,
            final String item,
            final java.util.function.Function<HttpServletRequest, JsonNode> reader) {
        try {
            return reader.apply(request);
        } catch (final WorkRefusal invalid) {
            throw Edits.refused(item, "", "The body is not one JSON object of its size.");
        }
    }

    private static void members(final JsonNode body, final String item, final Set<String> known) {
        for (final String name : body.propertyNames()) {
            if (!known.contains(name)) {
                // The unknown member's name is the caller's, so it is not repeated.
                throw Edits.refused(item, "", "The body holds a member it does not take.");
            }
        }
    }

    private static void exactly(final JsonNode body, final String item, final Set<String> all) {
        for (final String name : all) {
            if (!body.has(name)) {
                throw Edits.refused(item, name, "The body names no " + name + ".");
            }
        }
        members(body, item, all);
    }

    private static String revision(final JsonNode body, final String item) {
        final JsonNode revision = body.get("revision");
        if (revision == null || !isRevision(revision)) {
            throw Edits.refused(item, "revision", "The body names no revision.");
        }
        return revision.asString();
    }

    /** Whether a member is a revision as the description gives it: 1 to 200 printable ASCII. */
    private static boolean isRevision(final JsonNode revision) {
        return revision.isString() && REVISION.matcher(revision.asString()).matches();
    }

    private static @Nullable String text(
            final JsonNode body,
            final String name,
            final String item,
            final Set<Fields.Field> named,
            final Fields.Field field) {
        if (!body.has(name)) {
            return null;
        }
        named.add(field);
        final JsonNode value = body.get(name);
        if (value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw Edits.refused(item, name, "The member " + name + " must be a text or null.");
        }
        return value.asString();
    }

    private static @Nullable Integer number(
            final JsonNode body,
            final String name,
            final String item,
            final Set<Fields.Field> named,
            final Fields.Field field) {
        if (!body.has(name)) {
            return null;
        }
        named.add(field);
        final JsonNode value = body.get(name);
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw Edits.refused(
                    item, name, "The member " + name + " must be a whole number or null.");
        }
        return value.asInt();
    }

    private static List<String> ids(
            final JsonNode body, final String name, final String item, final int most) {
        final JsonNode list = body.get(name);
        if (!list.isArray() || list.size() > most) {
            throw Edits.refused(
                    item,
                    name,
                    "The member " + name + " must be a list of at most " + most + " ids.");
        }
        final List<String> ids = new ArrayList<>();
        for (final JsonNode id : list) {
            try {
                ids.add(Ids.issue(id.isString() ? id.asString() : ""));
            } catch (final IllegalArgumentException invalid) {
                throw Edits.refused(item, name, "Each of " + name + " must be an issue's id.");
            }
        }
        return ids;
    }

    private static WorkRefusal noStory(final String fault) {
        return WorkRefusal.ideaRefused(NO_STORY, List.of(fault), null);
    }
}
