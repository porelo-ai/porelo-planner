package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.core.HandedResults;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.NewIdeas;
import org.fuin.porelo.planner.core.NewIssues;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.ReadingCycle;
import org.fuin.porelo.planner.core.ReportTaking;
import org.fuin.porelo.planner.core.WorkEnds;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.core.Workers;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TopicLabels;
import org.fuin.porelo.planner.store.Version;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict, bounded work bodies; the window's facts are choices to validate, never prompt input. */
public final class WorkRequests {

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    private WorkRequests() {}

    /**
     * A target state for a recomputed work proposal.
     *
     * @param to the working state
     * @param issues the story's issues the work is for, or null for the story's own
     */
    public record Proposal(
            String to, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> issues) {}

    /** The exact work body emitted by the window, with absent overrides preserved. */
    public record Request(
            String project,
            String story,
            String to,
            String mode,
            Basis seen,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<Repository> repositories,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String comment,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Number readingRounds,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @Nullable List<IssueLimit> issueReadingRounds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> issues,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String replaces,
            @JsonInclude(JsonInclude.Include.NON_NULL) WorkRun.@Nullable PassOver passOver) {}

    /**
     * A stop's body, which may name the run the person saw.
     *
     * @param run the run, or null for whichever stands
     */
    public record StopRequest(@JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String run) {}

    /**
     * A repository's work as the window sends it back, read by hand so an unknown role or choice is
     * refused by the core after the check of the versions seen.
     *
     * @param repository the repository
     * @param issues the proposal's issues in order
     * @param main the main's identity, brief and choice
     * @param subAgents each helper's facts and choice
     */
    public record Repository(
            String repository, List<String> issues, Main main, List<Assignment> subAgents) {}

    /**
     * The main as the window sends it back, with the effort chosen, null for none.
     *
     * @param name its name
     * @param role its role, the work rules' role of the name sent back; the rest of the role sent
     *     back is the planner's own and is never taken
     * @param agent its agent
     * @param model its model
     * @param brief its brief
     * @param effort the effort chosen, or null
     * @param chosenBy where the rule's choice came from, never taken from the window
     * @param offered the agents the rule offers, never taken from the window
     */
    public record Main(
            String name,
            Role role,
            String agent,
            String model,
            String brief,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String effort,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String chosenBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<Offer> offered) {}

    /**
     * An agent the rule offers, with its model, as a window may send it back.
     *
     * @param agent the agent
     * @param model its model
     */
    public record Offer(String agent, String model) {}

    /**
     * A helper as the window sends it back, with its KEEP, CHANGE or DROP choice.
     *
     * @param id its id
     * @param issue its issue
     * @param name its name
     * @param kind its kind, the planner's word for a helper
     * @param agent its agent
     * @param model its model
     * @param workingState its working state
     * @param brief its brief
     * @param choice the choice
     * @param effort the effort chosen, or null
     * @param chosenBy where the rule's choice came from, never taken from the window
     */
    public record Assignment(
            String id,
            String issue,
            String name,
            String kind,
            String agent,
            String model,
            String workingState,
            String brief,
            String choice,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String effort,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String chosenBy) {}

    /** The pattern of a run's id, as the planner draws it. */
    private static final java.util.regex.Pattern RUN =
            java.util.regex.Pattern.compile(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** An explicitly supplied reading bound, kept numeric until core validation. */
    public record IssueLimit(String issue, Number rounds) {}

    /**
     * The checked lifecycle request; ended is present only for ENDED, landed only for LANDED.
     *
     * @param offer the offer
     * @param event the event
     * @param container the task's container, absent for REFUSED
     * @param machine the machine's name as the person sees it
     * @param ended Porelo's end record
     * @param landed where a build's work landed after its end
     * @param refusal what Porelo refused a Start with, present only for REFUSED, which names no
     *     container
     */
    public record TaskEventRequest(
            String offer,
            String event,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String container,
            String machine,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable TaskEnded ended,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<NextStep.Landing> landed,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable TaskRefusal refusal) {}

    /**
     * Porelo's refusal of a Start, as the window reports it.
     *
     * @param name Porelo's name for the refusal
     * @param parameters the refusal's parameters
     * @param detail Porelo's words
     */
    public record TaskRefusal(
            String name, java.util.Map<String, String> parameters, String detail) {}

    /**
     * Porelo's ended record with no outcome invented by the window.
     *
     * @param at when the task ended
     * @param finished whether the agent finished
     * @param why why it ended
     * @param answer the agent's answer
     * @param unpushed the commits not pushed
     * @param landed where the work landed, copied from Porelo's task row; absent while it waits at
     *     the gate
     * @param outcome the outcome the task declared, copied from Porelo's end record; absent when it
     *     declared none
     */
    public record TaskEnded(
            String at,
            boolean finished,
            String why,
            String answer,
            int unpushed,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<NextStep.Landing> landed,
            @JsonInclude(JsonInclude.Include.NON_NULL) WorkEnds.@Nullable Declared outcome) {}

    /**
     * One choice against a completed, stopped reading.
     *
     * @param story the story
     * @param round the round the person saw
     * @param choice the choice
     * @param comment the person's words
     * @param seen the versions the person saw
     * @param mode a rework's mode, given with agent and model or not at all
     * @param agent a rework's agent
     * @param model a rework's model
     */
    public record ReadingRequest(
            String story,
            int round,
            String choice,
            String comment,
            Basis seen,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String mode,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String agent,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String model) {

        /**
         * The rework's settings.
         *
         * @return the settings, or null for the Reworking rule's first mode and model
         */
        NextStep.@Nullable Settings settings() {
            return mode == null || agent == null || model == null
                    ? null
                    : new NextStep.Settings(mode, agent, model);
        }
    }

    /**
     * A new issue's body as the window sends it, each member a text where it is given.
     *
     * @param repository the repository's name
     * @param title the title
     * @param mustBeTrue what must be true
     * @param size the size
     * @param worker who works it
     * @param seen the versions the person saw, or null when the body names none
     * @param task the Porelo task that proposed it, or null
     * @param priority its priority, or null
     * @param story the story it is hung into, or null
     * @param state its initial state, or null
     * @param confirm whether the warnings of the move to state are confirmed
     * @param labels its topic labels, in the order of {@link TopicLabels#NAMES}; none when left out
     */
    public record IssueRequest(
            @Nullable String repository,
            @Nullable String title,
            @Nullable String mustBeTrue,
            @Nullable String size,
            @Nullable String worker,
            @Nullable Basis seen,
            @Nullable String task,
            @Nullable String priority,
            @Nullable String story,
            @Nullable String state,
            boolean confirm,
            List<String> labels) {}

    private static final Set<String> ISSUE_MEMBERS =
            Set.of(
                    "repository",
                    "title",
                    "mustBeTrue",
                    "size",
                    "worker",
                    "seen",
                    "task",
                    "priority",
                    "story",
                    "state",
                    "confirm",
                    "labels");

    private static final String NO_IDEA = "The report's text is no idea.";

    /**
     * Reads the text of a task's idea report strictly: one JSON object in the idea format, read as
     * {@link #idea(JsonNode, List)} reads it; a refusal names the field, never repeats the text.
     *
     * @param text the report's text, checked as a report's text before
     * @return the idea and the faults of its shape
     * @throws WorkRefusal {@code IdeaRefused} for a text that is no JSON object
     */
    static ReportTaking.Read ideaText(final String text) {
        final JsonNode idea;
        try {
            idea = JSON.readTree(text);
        } catch (final JacksonException invalid) {
            // The parser's words quote the text, so they are never passed on.
            throw noIdea("text: The text must be one JSON object in the idea format.");
        }
        if (idea == null || !idea.isObject()) {
            throw noIdea("text: The text must be one JSON object in the idea format.");
        }
        final List<String> shape = new ArrayList<>();
        final NewIdeas.Idea read = idea(idea, shape);
        return new ReportTaking.Read(read, shape);
    }

    /**
     * Reads an idea's story object as {@link #idea(JsonNode, List)} reads it.
     *
     * @param story the story's member, of any type
     * @param shape takes the faults of the story's shape, each by its field
     * @return the story, or null when the member is no object
     */
    static NewIdeas.@Nullable Story story(final JsonNode story, final List<String> shape) {
        if (!story.isObject()) {
            return null;
        }
        known(story, Set.of("title", "gain", "test", "priority"), "story", shape);
        return new NewIdeas.Story(
                textOrNull(story, "title"),
                textOrNull(story, "gain"),
                textOrNull(story, "test"),
                textOrNull(story, "priority"));
    }

    /**
     * Reads an idea object: a member of the wrong type arrives as null or an empty text, and a
     * member the idea format does not name as a fault of its shape, so the core names every fault
     * by its field and nothing sent is repeated.
     *
     * @param idea the idea object
     * @param shape takes the faults of the idea's shape, each by its field
     * @return the idea
     */
    static NewIdeas.Idea idea(final JsonNode idea, final List<String> shape) {
        known(idea, Set.of("story", "issues"), "idea", shape);
        final NewIdeas.Story readStory = story(idea.path("story"), shape);
        final List<NewIdeas.@Nullable Issue> issues = new ArrayList<>();
        final JsonNode items = idea.path("issues");
        if (items.isArray()) {
            for (final JsonNode item : items) {
                if (!item.isObject()) {
                    issues.add(null);
                    continue;
                }
                known(
                        item,
                        Set.of("repository", "title", "mustBeTrue", "size", "worker"),
                        "issues[" + issues.size() + "]",
                        shape);
                final JsonNode size = item.get("size");
                issues.add(
                        new NewIdeas.Issue(
                                textOrNull(item, "repository"),
                                textOrNull(item, "title"),
                                textOrNull(item, "mustBeTrue"),
                                size == null ? null : size.isString() ? size.asString() : "",
                                textOrNull(item, "worker")));
            }
        }
        return new NewIdeas.Idea(readStory, issues);
    }

    /**
     * A stage's hand-in as the description names it: the offer and either the result file or why it
     * is missing.
     *
     * @param offer the offer of the stage's task
     * @param result the result file, or null when missing
     * @param missing why the result is missing, or null when it is handed in
     */
    public record StageResultRequest(
            String offer,
            @JsonInclude(JsonInclude.Include.NON_NULL) HandedResults.@Nullable File result,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String missing) {}

    /**
     * A stage's hand-in as read: its offer and what the core checks.
     *
     * @param offer the offer
     * @param handIn the result or why it is missing, with the faults of its shape
     */
    record ResultHandIn(String offer, HandedResults.HandIn handIn) {}

    /** The most bytes of a stage's hand-in: a result file of 1048576 bytes and its envelope. */
    static final int MOST_RESULT = AccessFilter.MOST_RESULT;

    private static final String NO_RESULT = "body: The body is no hand-in of a stage's result.";

    /**
     * Reads a stage's hand-in strictly: at most {@value #MOST_RESULT} bytes, one JSON object with
     * an offer and exactly one of a result object and a missing line; a member of the result of the
     * wrong type arrives as an empty text or zero, and a member the format does not name as a fault
     * of its shape, so the core names every fault by its field and nothing sent is repeated.
     *
     * @param request the request
     * @return the hand-in
     * @throws WorkRefusal {@code ResultRefused} for a body that is no hand-in
     */
    static ResultHandIn stageResult(final HttpServletRequest request) {
        final JsonNode body;
        try {
            body = read(request, MOST_RESULT);
        } catch (final WorkRefusal invalid) {
            throw WorkRefusal.resultRefused(NOT_HANDED, List.of(NO_RESULT));
        }
        if (!Set.of("offer", "result", "missing").containsAll(body.propertyNames())
                || !body.has("offer")) {
            throw WorkRefusal.resultRefused(
                    NOT_HANDED,
                    List.of(
                            "body: The body must hold an offer and a result or why it is missing."));
        }
        final JsonNode offer = body.get("offer");
        if (!offer.isString()) {
            throw WorkRefusal.resultRefused(
                    NOT_HANDED, List.of("offer: The offer must be a lowercase UUID."));
        }
        HandedResults.offer(offer.asString());
        // An envelope that is no hand-in is refused before the offer's outcome is recorded.
        if (body.has("result") == body.has("missing")) {
            throw WorkRefusal.resultRefused(
                    NOT_HANDED,
                    List.of("body: The body must hold either a result or why it is missing."));
        }
        if (body.has("result") && !body.get("result").isObject()) {
            throw WorkRefusal.resultRefused(
                    NOT_HANDED, List.of("result: The result must be an object."));
        }
        final List<String> shape = new ArrayList<>();
        HandedResults.File file = null;
        if (body.has("result")) {
            file = resultFile(body.get("result"), shape);
        }
        String missing = null;
        if (body.has("missing")) {
            final JsonNode reason = body.get("missing");
            missing = reason.isString() ? reason.asString() : "";
        }
        return new ResultHandIn(offer.asString(), new HandedResults.HandIn(file, missing, shape));
    }

    private static final String NOT_HANDED = "The hand-in is refused.";

    private static HandedResults.File resultFile(final JsonNode result, final List<String> shape) {
        if (!Set.of("format", "kind", "issues").containsAll(result.propertyNames())) {
            shape.add("result: It holds a member the result format does not name.");
        }
        List<HandedResults.@Nullable Entry> entries = null;
        final JsonNode items = result.get("issues");
        if (items != null && items.isArray()) {
            entries = new ArrayList<>();
            for (final JsonNode item : items) {
                entries.add(
                        item.isObject()
                                ? resultEntry(item, "issues[" + entries.size() + "]", shape)
                                : null);
            }
        }
        return new HandedResults.File(anyText(result, "format"), anyText(result, "kind"), entries);
    }

    private static HandedResults.Entry resultEntry(
            final JsonNode item, final String at, final List<String> shape) {
        if (!Set.of(
                        "id",
                        "plan",
                        "size",
                        "sizeWhy",
                        "estimateMinutes",
                        "toBeChecked",
                        "findings",
                        "ready",
                        "lacks",
                        "why")
                .containsAll(item.propertyNames())) {
            shape.add(at + ": It holds a member the result format does not name.");
        }
        final JsonNode ready = item.get("ready");
        if (ready != null && !ready.isBoolean()) {
            // Said once here: the entry is taken as not ready, so the check names no second fault
            // of ready; the shape fault refuses the result.
            shape.add(at + ".ready: It must be true or false.");
        }
        final JsonNode minutes = item.get("estimateMinutes");
        return new HandedResults.Entry(
                anyText(item, "id"),
                anyText(item, "plan"),
                anyText(item, "size"),
                anyText(item, "sizeWhy"),
                minutes == null
                        ? null
                        : minutes.canConvertToLong() && minutes.isIntegralNumber()
                                ? minutes.asLong()
                                : 0L,
                lines(item, "toBeChecked", at, shape),
                lines(item, "findings", at, shape),
                ready == null ? null : ready.isBoolean() ? ready.asBoolean() : Boolean.FALSE,
                lines(item, "lacks", at, shape),
                anyText(item, "why"));
    }

    /** A text member as sent, an empty text for any other value, or null when absent. */
    private static @Nullable String anyText(final JsonNode node, final String name) {
        final JsonNode value = node.get(name);
        if (value == null) {
            return null;
        }
        return value.isString() ? value.asString() : "";
    }

    /** A list of texts, each other value an empty text, or null when absent. */
    private static @Nullable List<String> lines(
            final JsonNode node, final String name, final String at, final List<String> shape) {
        final JsonNode value = node.get(name);
        if (value == null) {
            return null;
        }
        if (!value.isArray()) {
            shape.add(at + "." + name + ": It must be a list of one-line texts.");
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        value.forEach(item -> lines.add(item.isString() ? item.asString() : ""));
        return lines;
    }

    private static WorkRefusal noIdea(final String fault) {
        return WorkRefusal.ideaRefused(NO_IDEA, List.of(fault), null);
    }

    // A member's name was sent, so the fault names only where it stands.
    private static void known(
            final JsonNode node,
            final Set<String> names,
            final String field,
            final List<String> shape) {
        if (!names.containsAll(node.propertyNames())) {
            shape.add(field + ": It holds a member the idea format does not name.");
        }
    }

    private static @Nullable String textOrNull(final JsonNode node, final String name) {
        final JsonNode value = node.get(name);
        return value != null && value.isString() ? value.asString() : null;
    }

    /**
     * An issue's new worker as the window sends it.
     *
     * @param worker who is to work it
     * @param seen the versions the person saw
     */
    public record WorkerRequest(Worker worker, Basis seen) {}

    private static final Set<String> WORKER_MEMBERS = Set.of("worker", "seen");

    /**
     * A story's new priority as the window sends it.
     *
     * @param priority the priority it is to have, one of the five
     * @param seen the versions the person saw
     */
    public record PriorityRequest(String priority, Basis seen) {}

    private static final Set<String> PRIORITY_MEMBERS = Set.of("priority", "seen");

    /** The most versions one write's {@code seen} may name, as the description bounds it. */
    static final int MOST_SEEN = 2000;

    /** An item's version as the description gives it. */
    private static final java.util.regex.Pattern VERSION =
            java.util.regex.Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /**
     * Reads the versions a person saw: one JSON object of at most {@value #MOST_SEEN} members, each
     * named by an issue's or a story's id and holding a version; an empty object is a write without
     * targets.
     *
     * @param node the member, or null when it is missing
     * @return the versions, or null for a member that is none or names too many
     */
    static @Nullable Basis seen(final @Nullable JsonNode node) {
        if (node == null || !node.isObject() || node.size() > MOST_SEEN) {
            return null;
        }
        final Map<String, Version> items = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, JsonNode> entry : node.properties()) {
            final JsonNode version = entry.getValue();
            if (!itemId(entry.getKey())
                    || !version.isString()
                    || !VERSION.matcher(version.asString()).matches()) {
                return null;
            }
            items.put(entry.getKey(), new Version(version.asString()));
        }
        return new Basis(items);
    }

    /**
     * Reads the versions a person saw from a body bound as a map, of at most {@value #MOST_SEEN}
     * entries.
     *
     * @param seen the member, or null when it is missing
     * @return the versions, or null for a member that is none or names too many
     */
    static @Nullable Basis seen(final @Nullable Map<String, String> seen) {
        if (seen == null || seen.size() > MOST_SEEN) {
            return null;
        }
        final Map<String, Version> items = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : seen.entrySet()) {
            final String version = entry.getValue();
            if (!itemId(entry.getKey()) || version == null || !VERSION.matcher(version).matches()) {
                return null;
            }
            items.put(entry.getKey(), new Version(version));
        }
        return new Basis(items);
    }

    /** Whether a text is an issue's or a story's id of either store's form. */
    private static boolean itemId(final String id) {
        try {
            Ids.issue(id);
            return true;
        } catch (final IllegalArgumentException notAnIssue) {
            try {
                Ids.story(id);
                return true;
            } catch (final IllegalArgumentException notAStory) {
                return false;
            }
        }
    }

    /** Reads the versions a work body names, refused as an invalid setting when they are none. */
    private static Basis seenOrRefused(final JsonNode body) {
        final Basis seen = seen(body.get("seen"));
        if (seen == null) {
            throw WorkRefusal.invalidSettings();
        }
        return seen;
    }

    /**
     * Reads a new issue's body strictly: at most 1048576 bytes, one JSON object, no other member,
     * each member a text; a refusal never repeats what was sent.
     *
     * @param request the request
     * @return the body's members
     * @throws Moves.Refused {@code IssueNotAllowed} for a body that is no new issue
     */
    static IssueRequest issue(final HttpServletRequest request) {
        final JsonNode body;
        try {
            body = read(request, AccessFilter.MOST_LARGE_BODY);
        } catch (final WorkRefusal invalid) {
            throw NewIssues.refused("", "The body is no new issue.");
        }
        if (!ISSUE_MEMBERS.containsAll(body.propertyNames())) {
            throw NewIssues.refused("", "The body is no new issue.");
        }
        if (body.has("confirm") && !body.get("confirm").isBoolean()) {
            throw NewIssues.refused("confirm", "The member confirm is no boolean.");
        }
        return new IssueRequest(
                member(body, "repository"),
                member(body, "title"),
                member(body, "mustBeTrue"),
                member(body, "size"),
                member(body, "worker"),
                body.has("seen") ? seenOrIssueRefused(body) : null,
                member(body, "task"),
                member(body, "priority"),
                member(body, "story"),
                member(body, "state"),
                body.has("confirm") && body.get("confirm").asBoolean(),
                newLabels(body));
    }

    /**
     * A new issue's topic labels: an array of the topic labels, each once; a refusal never repeats
     * what was sent.
     */
    private static List<String> newLabels(final JsonNode body) {
        if (!body.has("labels")) {
            return List.of();
        }
        final JsonNode value = body.get("labels");
        final List<String> labels = new ArrayList<>();
        boolean texts = value.isArray();
        if (texts) {
            for (final JsonNode label : value) {
                texts &= label.isString();
                labels.add(label.isString() ? label.asString() : "");
            }
        }
        if (!texts || TopicLabels.refused(labels) != null) {
            throw NewIssues.refused("labels", "The labels must be topic labels, each named once.");
        }
        return TopicLabels.topic(labels);
    }

    private static Basis seenOrIssueRefused(final JsonNode body) {
        final Basis seen = seen(body.get("seen"));
        if (seen == null) {
            throw NewIssues.refused(
                    "seen", "The member seen holds no versions by item id, or more than 2000.");
        }
        return seen;
    }

    /**
     * Reads an issue's new worker strictly: at most 65536 bytes, one JSON object with exactly a
     * worker the contract names and the versions seen; a refusal never repeats what was sent.
     *
     * @param request the request
     * @param issue the issue's checked id, which a refusal names
     * @return the worker and the versions seen
     * @throws Moves.Refused {@code MoveNotAllowed} for a body that is no worker change
     */
    static WorkerRequest worker(final HttpServletRequest request, final String issue) {
        final JsonNode body;
        try {
            body = read(request);
        } catch (final WorkRefusal invalid) {
            throw Workers.refused(issue, "The body is no worker change.");
        }
        final Basis seen = seen(body.get("seen"));
        if (!WORKER_MEMBERS.equals(Set.copyOf(body.propertyNames())) || seen == null) {
            throw Workers.refused(issue, "The body is no worker change.");
        }
        final JsonNode worker = body.get("worker");
        for (final Worker known : Worker.values()) {
            if (worker.isString() && known.name().equals(worker.asString())) {
                return new WorkerRequest(known, seen);
            }
        }
        throw Workers.refused(issue, "The worker must be AGENT or MANUAL.");
    }

    /**
     * Reads a story's new priority strictly: at most 65536 bytes, one JSON object with exactly one
     * of the five priorities and the versions seen; a refusal never repeats what was sent.
     *
     * @param request the request
     * @param story the story's checked id, which a refusal names
     * @return the priority and the versions seen
     * @throws Refusal {@code PriorityNotAllowed} for a body that is no priority change
     */
    static PriorityRequest priority(final HttpServletRequest request, final String story) {
        final JsonNode body;
        try {
            body = read(request);
        } catch (final WorkRefusal invalid) {
            throw priorityRefused(story, "The body is no priority change.");
        }
        final Basis seen = seen(body.get("seen"));
        if (!PRIORITY_MEMBERS.equals(Set.copyOf(body.propertyNames())) || seen == null) {
            throw priorityRefused(story, "The body is no priority change.");
        }
        final JsonNode priority = body.get("priority");
        if (!priority.isString() || !Story.PRIORITIES.contains(priority.asString())) {
            throw priorityRefused(
                    story, "The priority must be Highest, High, Medium, Low or Lowest.");
        }
        return new PriorityRequest(priority.asString(), seen);
    }

    private static Refusal priorityRefused(final String story, final String reason) {
        return new Refusal(
                "PriorityNotAllowed", HttpStatus.BAD_REQUEST, reason, Map.of("story", story));
    }

    private static @Nullable String member(final JsonNode body, final String name) {
        if (!body.has(name)) {
            return null;
        }
        final JsonNode value = body.get(name);
        if (value == null || !value.isString()) {
            throw NewIssues.refused(name, "The member " + name + " must be a text.");
        }
        return value.asString();
    }

    static JsonNode read(final HttpServletRequest request) {
        return read(request, 65536);
    }

    static JsonNode read(final HttpServletRequest request, final int most) {
        return object(readBytes(request, most));
    }

    static byte[] readBytes(final HttpServletRequest request, final int most) {
        try {
            if (request.getContentLengthLong() > most) {
                throw WorkRefusal.invalidSettings();
            }
            final byte[] bytes = request.getInputStream().readNBytes(most + 1);
            if (bytes.length > most) {
                throw WorkRefusal.invalidSettings();
            }
            return bytes;
        } catch (final IOException invalid) {
            throw WorkRefusal.invalidSettings();
        }
    }

    static JsonNode object(final byte[] bytes) {
        try {
            final JsonNode body = JSON.readTree(bytes);
            if (body == null || !body.isObject()) {
                throw WorkRefusal.invalidSettings();
            }
            return body;
        } catch (final JacksonException invalid) {
            throw WorkRefusal.invalidSettings();
        }
    }

    static WorkRun.Report event(final JsonNode body) {
        fields(
                body,
                Set.of("offer", "event", "container", "machine", "ended", "landed", "refusal"));
        final String machine = text(body, "machine");
        WorkRun.checkedMachine(machine);
        final WorkRun.Event event;
        try {
            event = WorkRun.Event.valueOf(text(body, "event"));
        } catch (final IllegalArgumentException invalid) {
            throw WorkRefusal.invalidSettings();
        }
        final boolean refused = event == WorkRun.Event.REFUSED;
        if (refused == body.has("container") || refused != body.has("refusal")) {
            // A refused Start started no container, and every other event names its own.
            throw WorkRefusal.invalidSettings();
        }
        WorkRun.Ended ended = null;
        if (body.has("ended")) {
            final JsonNode end = body.path("ended");
            fields(end, Set.of("at", "finished", "why", "answer", "unpushed", "landed", "outcome"));
            if (!end.path("finished").isBoolean()) {
                throw WorkRefusal.invalidSettings();
            }
            ended =
                    new WorkRun.Ended(
                            text(end, "at"),
                            end.path("finished").asBoolean(),
                            text(end, "why"),
                            text(end, "answer"),
                            integer(end, "unpushed"),
                            landings(end),
                            declared(end));
        }
        if (body.has("landed") && event != WorkRun.Event.LANDED) {
            throw WorkRefusal.invalidSettings();
        }
        return new WorkRun.Report(
                text(body, "offer"),
                event,
                refused ? "" : text(body, "container"),
                machine,
                ended,
                landings(body),
                refused ? startRefusal(body.path("refusal")) : null);
    }

    /** Reads what Porelo refused a Start with, exactly its three names and strings only. */
    private static WorkRun.StartRefusal startRefusal(final JsonNode node) {
        fields(node, Set.of("name", "parameters", "detail"));
        final JsonNode parameters = node.path("parameters");
        if (!parameters.isObject()) {
            throw WorkRefusal.invalidSettings();
        }
        final Map<String, String> given = new java.util.LinkedHashMap<>();
        for (final String key : parameters.propertyNames()) {
            given.put(key, text(parameters, key));
        }
        return new WorkRun.StartRefusal(text(node, "name"), given, text(node, "detail"));
    }

    /** Reads the outcome a task declared, exactly one of its names; none when absent. */
    private static WorkEnds.@Nullable Declared declared(final JsonNode end) {
        if (!end.has("outcome")) {
            return null;
        }
        final String name = text(end, "outcome");
        for (final WorkEnds.Declared declared : WorkEnds.Declared.values()) {
            if (declared.name().equals(name)) {
                return declared;
            }
        }
        throw WorkRefusal.invalidSettings();
    }

    /** Reads where a task's work landed, each entry exactly its four names; none when absent. */
    private static List<NextStep.Landing> landings(final JsonNode node) {
        if (!node.has("landed")) {
            return List.of();
        }
        final List<NextStep.Landing> landed = new ArrayList<>();
        for (final JsonNode entry : array(node, "landed")) {
            fields(entry, Set.of("branch", "ref", "remote", "commit"));
            landed.add(
                    new NextStep.Landing(
                            text(entry, "branch"),
                            text(entry, "ref"),
                            text(entry, "remote"),
                            text(entry, "commit")));
        }
        return landed;
    }

    static ReadingRequest reading(final JsonNode body) {
        fields(
                body,
                Set.of("story", "round", "choice", "comment", "seen", "mode", "agent", "model"));
        final String choice = text(body, "choice");
        try {
            ReadingCycle.Choice.valueOf(choice);
        } catch (final IllegalArgumentException invalid) {
            throw WorkRefusal.invalidSettings();
        }
        // Any one of the settings requires all three, so a part never leaves the rest guessed.
        final boolean settings = body.has("mode") || body.has("agent") || body.has("model");
        return new ReadingRequest(
                text(body, "story"),
                integer(body, "round"),
                choice,
                text(body, "comment"),
                seenOrRefused(body),
                settings ? text(body, "mode") : null,
                settings ? text(body, "agent") : null,
                settings ? text(body, "model") : null);
    }

    private static int integer(final JsonNode body, final String name) {
        final JsonNode number = body.path(name);
        if (!number.isIntegralNumber()
                || number.bigIntegerValue().signum() < 0
                || number.bigIntegerValue()
                                .compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE))
                        > 0) {
            throw WorkRefusal.invalidSettings();
        }
        return number.intValue();
    }

    static Proposal proposal(final JsonNode body) {
        fields(body, Set.of("to", "issues"));
        return new Proposal(text(body, "to"), chosen(body));
    }

    /**
     * The words a discussion of an idea starts from, the body's one field.
     *
     * @param body the request's body
     * @return the words, checked further by the discussion
     */
    static String idea(final JsonNode body) {
        fields(body, Set.of("idea"));
        return text(body, "idea");
    }

    static String target(final JsonNode body) {
        return text(body, "to");
    }

    /**
     * Reads a start's body, the role of each repository's main taken from the work rules by the
     * name sent back.
     *
     * @param body the body
     * @param from the story's state
     * @param to the state the work goes toward
     * @param roles the roles the project's work rules define, by name
     * @return the checked request
     */
    static Request request(
            final JsonNode body,
            final String from,
            final String to,
            final Map<String, Role> roles) {
        fields(
                body,
                Set.of(
                        "project",
                        "story",
                        "to",
                        "mode",
                        "seen",
                        "repositories",
                        "comment",
                        "readingRounds",
                        "issueReadingRounds",
                        "issues",
                        "replaces",
                        "passOver"));
        final List<Repository> repositories;
        if (body.has("repositories")) {
            repositories = new ArrayList<>();
            for (final JsonNode repository : array(body, "repositories")) {
                repositories.add(repository(repository, roles));
            }
        } else {
            repositories = null;
        }
        final List<IssueLimit> overrides;
        if (body.has("issueReadingRounds")) {
            overrides = new ArrayList<>();
            for (final JsonNode limit : array(body, "issueReadingRounds")) {
                fields(limit, Set.of("issue", "rounds"));
                overrides.add(
                        new IssueLimit(
                                text(limit, "issue"), rounds(limit.path("rounds"), from, to)));
            }
        } else {
            overrides = null;
        }
        return new Request(
                text(body, "project"),
                text(body, "story"),
                to,
                text(body, "mode"),
                seenOrRefused(body),
                repositories,
                body.has("comment") ? text(body, "comment") : null,
                body.has("readingRounds") ? rounds(body.path("readingRounds"), from, to) : null,
                overrides,
                chosen(body),
                body.has("replaces") ? run(body, "replaces") : null,
                body.has("passOver") ? passOver(body.path("passOver")) : null);
    }

    /** Reads a run's id, refused unless it has the form the planner draws. */
    private static String run(final JsonNode node, final String name) {
        final String run = text(node, name);
        if (!RUN.matcher(run).matches()) {
            throw WorkRefusal.invalidSettings();
        }
        return run;
    }

    /** Reads the reason a Start passes over, each field checked before it is used. */
    private static WorkRun.PassOver passOver(final JsonNode node) {
        if (!node.isObject()
                || !Set.of("issue", "reason", "run").equals(Set.copyOf(node.propertyNames()))) {
            throw WorkRefusal.invalidSettings();
        }
        final String issue = text(node, "issue");
        try {
            Ids.issue(issue);
        } catch (final IllegalArgumentException malformed) {
            throw WorkRefusal.invalidSettings();
        }
        final String reason = text(node, "reason");
        if (!WorkRun.PASSABLE.contains(reason)) {
            throw WorkRefusal.invalidSettings();
        }
        return new WorkRun.PassOver(issue, reason, run(node, "run"));
    }

    /**
     * What a Start names of the run standing.
     *
     * @param request the checked request
     * @return the run it replaces and the reason it passes over
     */
    static WorkRun.Start start(final Request request) {
        return new WorkRun.Start(request.replaces(), request.passOver());
    }

    /**
     * Reads a stop's body: empty, or an object naming at most the run the person saw.
     *
     * @param request the request
     * @return the run named, or null when none is
     */
    static @Nullable String stop(final HttpServletRequest request) {
        final byte[] bytes;
        try {
            bytes = request.getInputStream().readNBytes(65537);
        } catch (final IOException invalid) {
            throw WorkRefusal.invalidSettings();
        }
        if (bytes.length == 0) {
            return null;
        }
        if (bytes.length > 65536) {
            throw WorkRefusal.invalidSettings();
        }
        final JsonNode body;
        try {
            body = JSON.readTree(bytes);
        } catch (final JacksonException invalid) {
            throw WorkRefusal.invalidSettings();
        }
        if (body == null || !body.isObject()) {
            throw WorkRefusal.invalidSettings();
        }
        fields(body, Set.of("run"));
        return body.has("run") ? run(body, "run") : null;
    }

    /**
     * Reads the issues a person chose for the work: when present, one to {@value #MOST_CHOSEN}
     * issue identifiers, each once; null when absent.
     */
    private static @Nullable List<String> chosen(final JsonNode body) {
        if (!body.has("issues")) {
            return null;
        }
        final JsonNode list = array(body, "issues");
        if (list.isEmpty() || list.size() > MOST_CHOSEN) {
            throw WorkRefusal.invalidSettings();
        }
        final List<String> issues = new ArrayList<>();
        for (final JsonNode issue : list) {
            if (!issue.isString()) {
                throw WorkRefusal.invalidSettings();
            }
            try {
                Ids.issue(issue.asString());
            } catch (final IllegalArgumentException malformed) {
                throw WorkRefusal.invalidSettings();
            }
            if (issues.contains(issue.asString())) {
                throw WorkRefusal.invalidSettings();
            }
            issues.add(issue.asString());
        }
        return issues;
    }

    /** The most issues one request may choose. */
    private static final int MOST_CHOSEN = 1000;

    static WorkOrder.Decision decision(final Request request) {
        final List<Repository> repositories = request.repositories();
        final List<IssueLimit> overrides = request.issueReadingRounds();
        return new WorkOrder.Decision(
                request.mode(),
                request.seen(),
                repositories == null
                        ? null
                        : repositories.stream().map(WorkRequests::coreRepository).toList(),
                request.comment(),
                request.readingRounds(),
                overrides == null
                        ? null
                        : overrides.stream()
                                .map(limit -> new WorkOrder.Limit(limit.issue(), limit.rounds()))
                                .toList(),
                request.issues());
    }

    private static Repository repository(final JsonNode node, final Map<String, Role> roles) {
        fields(node, Set.of("repository", "issues", "main", "subAgents"));
        final List<String> issues = new ArrayList<>();
        for (final JsonNode issue : array(node, "issues")) {
            if (!issue.isString()) {
                throw WorkRefusal.invalidSettings();
            }
            issues.add(issue.asString());
        }
        final JsonNode main = node.path("main");
        fields(
                main,
                Set.of("name", "role", "agent", "model", "brief", "effort", "chosenBy", "offered"));
        final Main worker =
                new Main(
                        text(main, "name"),
                        role(main, roles),
                        text(main, "agent"),
                        text(main, "model"),
                        text(main, "brief"),
                        effort(main),
                        chosenBy(main),
                        offered(main));
        final List<Assignment> helpers = new ArrayList<>();
        for (final JsonNode sub : array(node, "subAgents")) {
            fields(
                    sub,
                    Set.of(
                            "id",
                            "issue",
                            "name",
                            "kind",
                            "agent",
                            "model",
                            "workingState",
                            "brief",
                            "choice",
                            "effort",
                            "chosenBy"));
            helpers.add(
                    new Assignment(
                            text(sub, "id"),
                            text(sub, "issue"),
                            text(sub, "name"),
                            text(sub, "kind"),
                            text(sub, "agent"),
                            text(sub, "model"),
                            text(sub, "workingState"),
                            text(sub, "brief"),
                            text(sub, "choice"),
                            effort(sub),
                            chosenBy(sub)));
        }
        return new Repository(text(node, "repository"), issues, worker, helpers);
    }

    private static WorkProposal.Repository coreRepository(final Repository repository) {
        final Main main = repository.main();
        return new WorkProposal.Repository(
                repository.repository(),
                repository.issues(),
                // Where the choice came from is the core's to say; a caller's words are not taken.
                new WorkProposal.Main(
                        main.name(),
                        main.role(),
                        main.agent(),
                        main.model(),
                        main.brief(),
                        main.effort(),
                        ""),
                repository.subAgents().stream()
                        .map(
                                sub ->
                                        new WorkProposal.Assignment(
                                                sub.id(),
                                                sub.issue(),
                                                sub.name(),
                                                sub.kind(),
                                                sub.agent(),
                                                sub.model(),
                                                sub.workingState(),
                                                sub.brief(),
                                                sub.choice(),
                                                sub.effort(),
                                                ""))
                        .toList());
    }

    /**
     * The role of a main sent back: an object whose string name the work rules define, which alone
     * is read; a changed role is the core's to refuse against the one offered.
     */
    private static Role role(final JsonNode main, final Map<String, Role> roles) {
        final JsonNode role = main.path("role");
        if (!role.isObject()) {
            throw WorkRefusal.invalidSettings();
        }
        final Role defined = roles.get(text(role, "name"));
        if (defined == null) {
            throw WorkRefusal.invalidSettings();
        }
        return defined;
    }

    /** An effort sent back: absent or null for none, else a sound name. */
    private static @Nullable String effort(final JsonNode node) {
        final JsonNode value = node.path("effort");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isString() || !WorkRule.sound(value.asString())) {
            throw WorkRefusal.invalidSettings();
        }
        return value.asString();
    }

    /** A chosenBy sent back is checked as a string and then not taken. */
    /** Checks a chosenBy sent back is text; it is the planner's own and is never taken. */
    private static @Nullable String chosenBy(final JsonNode node) {
        final JsonNode value = node.path("chosenBy");
        if (!value.isMissingNode() && !value.isNull() && !value.isString()) {
            throw WorkRefusal.invalidSettings();
        }
        return null;
    }

    /** Checks an offered list sent back is a list; it is the planner's own and is never taken. */
    private static @Nullable List<Offer> offered(final JsonNode node) {
        final JsonNode value = node.path("offered");
        if (!value.isMissingNode() && !value.isNull() && !value.isArray()) {
            throw WorkRefusal.invalidSettings();
        }
        return null;
    }

    private static void fields(final JsonNode node, final Set<String> allowed) {
        if (!node.isObject() || !allowed.containsAll(node.propertyNames())) {
            throw WorkRefusal.invalidSettings();
        }
    }

    private static String text(final JsonNode node, final String name) {
        final JsonNode value = node.path(name);
        if (!value.isString()) {
            throw WorkRefusal.invalidSettings();
        }
        return value.asString();
    }

    private static JsonNode array(final JsonNode node, final String name) {
        final JsonNode value = node.path(name);
        if (!value.isArray()) {
            throw WorkRefusal.invalidSettings();
        }
        return value;
    }

    private static Number rounds(final JsonNode node, final String from, final String to) {
        // Any number is judged by the core after the versions seen, so a changed item is told so.
        if (!node.isNumber()) {
            throw WorkRefusal.invalidSettings();
        }
        return node.isIntegralNumber() ? node.bigIntegerValue() : node.decimalValue();
    }
}
