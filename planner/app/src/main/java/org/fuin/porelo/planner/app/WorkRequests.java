package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.core.HandedIdeas;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.NewIdeas;
import org.fuin.porelo.planner.core.NewIssues;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.ReadingCycle;
import org.fuin.porelo.planner.core.WorkEnds;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.core.Workers;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Story;
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
            String revision,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @Nullable List<WorkAnswers.Repository> repositories,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String comment,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Number readingRounds,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @Nullable List<IssueLimit> issueReadingRounds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> issues) {}

    /** An explicitly supplied reading bound, kept numeric until core validation. */
    public record IssueLimit(String issue, Number rounds) {}

    /**
     * The checked lifecycle request; ended is present only for ENDED, landed only for LANDED.
     *
     * @param offer the offer
     * @param event the event
     * @param container the task's container
     * @param machine the machine's name as the person sees it
     * @param ended Porelo's end record
     * @param landed where a build's work landed after its end
     */
    public record TaskEventRequest(
            String offer,
            String event,
            String container,
            String machine,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable TaskEnded ended,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<NextStep.Landing> landed) {}

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
     * @param revision the board's revision the person saw
     * @param mode a rework's mode, given with agent and model or not at all
     * @param agent a rework's agent
     * @param model a rework's model
     */
    public record ReadingRequest(
            String story,
            int round,
            String choice,
            String comment,
            String revision,
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
     * @param revision the board's revision the person saw
     * @param task the Porelo task that proposed it, or null
     */
    public record IssueRequest(
            @Nullable String repository,
            @Nullable String title,
            @Nullable String mustBeTrue,
            @Nullable String size,
            @Nullable String worker,
            @Nullable String revision,
            @Nullable String task) {}

    private static final Set<String> ISSUE_MEMBERS =
            Set.of("repository", "title", "mustBeTrue", "size", "worker", "revision", "task");

    /**
     * The idea a discussion wrote, as the window hands it in.
     *
     * @param offer the discussion's offer
     * @param idea the idea, each member null where it is missing or no text
     */
    public record IdeaRequest(String offer, NewIdeas.Idea idea) {}

    /**
     * A hand-in as read: the request and the faults of the idea's shape, each by its field.
     *
     * @param request the request
     * @param shape the members the idea format does not name, by where they stand
     */
    record HandIn(IdeaRequest request, List<String> shape) {}

    private static final String NO_HAND_IN = "The body is no hand-in of an idea.";

    /**
     * Reads a hand-in strictly: at most 65536 bytes, one JSON object with exactly an offer and an
     * idea object; a refusal never repeats what was sent.
     *
     * @param request the request
     * @return the hand-in
     * @throws WorkRefusal {@code IdeaRefused} for a body that is no hand-in
     */
    static HandIn handIn(final HttpServletRequest request) {
        final JsonNode body;
        try {
            body = read(request);
        } catch (final WorkRefusal invalid) {
            throw noHandIn("body: " + NO_HAND_IN);
        }
        if (!Set.of("offer", "idea").equals(Set.copyOf(body.propertyNames()))) {
            throw noHandIn("body: The body must hold exactly an offer and an idea.");
        }
        final JsonNode offer = body.get("offer");
        if (!offer.isString()) {
            throw noHandIn("offer: The offer must be a lowercase UUID.");
        }
        HandedIdeas.offer(offer.asString());
        final JsonNode idea = body.get("idea");
        if (!idea.isObject()) {
            throw noHandIn("idea: The idea must be an object with a story and issues.");
        }
        final List<String> shape = new ArrayList<>();
        known(idea, Set.of("story", "issues"), "idea", shape);
        final JsonNode story = idea.path("story");
        NewIdeas.Story readStory = null;
        if (story.isObject()) {
            known(story, Set.of("title", "gain", "test", "priority"), "story", shape);
            readStory =
                    new NewIdeas.Story(
                            textOrNull(story, "title"),
                            textOrNull(story, "gain"),
                            textOrNull(story, "test"),
                            textOrNull(story, "priority"));
        }
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
        return new HandIn(
                new IdeaRequest(offer.asString(), new NewIdeas.Idea(readStory, issues)), shape);
    }

    private static WorkRefusal noHandIn(final String fault) {
        return WorkRefusal.ideaRefused(NO_HAND_IN, List.of(fault), null);
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
     * @param revision the board's revision the person saw
     */
    public record WorkerRequest(Worker worker, String revision) {}

    private static final Set<String> WORKER_MEMBERS = Set.of("worker", "revision");

    /**
     * A story's new priority as the window sends it.
     *
     * @param priority the priority it is to have, one of the five
     * @param revision the board's revision the person saw
     */
    public record PriorityRequest(String priority, String revision) {}

    private static final Set<String> PRIORITY_MEMBERS = Set.of("priority", "revision");

    /**
     * Reads a new issue's body strictly: at most 65536 bytes, one JSON object, no other member,
     * each member a text; a refusal never repeats what was sent.
     *
     * @param request the request
     * @return the body's members
     * @throws Moves.Refused {@code IssueNotAllowed} for a body that is no new issue
     */
    static IssueRequest issue(final HttpServletRequest request) {
        final JsonNode body;
        try {
            body = read(request);
        } catch (final WorkRefusal invalid) {
            throw NewIssues.refused("", "The body is no new issue.");
        }
        if (!ISSUE_MEMBERS.containsAll(body.propertyNames())) {
            throw NewIssues.refused("", "The body is no new issue.");
        }
        return new IssueRequest(
                member(body, "repository"),
                member(body, "title"),
                member(body, "mustBeTrue"),
                member(body, "size"),
                member(body, "worker"),
                member(body, "revision"),
                member(body, "task"));
    }

    /**
     * Reads an issue's new worker strictly: at most 65536 bytes, one JSON object with exactly a
     * worker the contract names and a revision; a refusal never repeats what was sent.
     *
     * @param request the request
     * @param issue the issue's checked id, which a refusal names
     * @return the worker and the revision
     * @throws Moves.Refused {@code MoveNotAllowed} for a body that is no worker change
     */
    static WorkerRequest worker(final HttpServletRequest request, final String issue) {
        final JsonNode body;
        try {
            body = read(request);
        } catch (final WorkRefusal invalid) {
            throw Workers.refused(issue, "The body is no worker change.");
        }
        if (!WORKER_MEMBERS.equals(Set.copyOf(body.propertyNames()))
                || !body.get("revision").isString()) {
            throw Workers.refused(issue, "The body is no worker change.");
        }
        final JsonNode worker = body.get("worker");
        for (final Worker known : Worker.values()) {
            if (worker.isString() && known.name().equals(worker.asString())) {
                return new WorkerRequest(known, body.get("revision").asString());
            }
        }
        throw Workers.refused(issue, "The worker must be AGENT or MANUAL.");
    }

    /**
     * Reads a story's new priority strictly: at most 65536 bytes, one JSON object with exactly one
     * of the five priorities and a revision; a refusal never repeats what was sent.
     *
     * @param request the request
     * @param story the story's checked id, which a refusal names
     * @return the priority and the revision
     * @throws Refusal {@code PriorityNotAllowed} for a body that is no priority change
     */
    static PriorityRequest priority(final HttpServletRequest request, final String story) {
        final JsonNode body;
        try {
            body = read(request);
        } catch (final WorkRefusal invalid) {
            throw priorityRefused(story, "The body is no priority change.");
        }
        if (!PRIORITY_MEMBERS.equals(Set.copyOf(body.propertyNames()))
                || !body.get("revision").isString()) {
            throw priorityRefused(story, "The body is no priority change.");
        }
        final JsonNode priority = body.get("priority");
        if (!priority.isString() || !Story.PRIORITIES.contains(priority.asString())) {
            throw priorityRefused(
                    story, "The priority must be Highest, High, Medium, Low or Lowest.");
        }
        return new PriorityRequest(priority.asString(), body.get("revision").asString());
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
        try {
            if (request.getContentLengthLong() > most) {
                throw WorkRefusal.invalidSettings();
            }
            final byte[] bytes = request.getInputStream().readNBytes(most + 1);
            if (bytes.length > most) {
                throw WorkRefusal.invalidSettings();
            }
            final JsonNode body = JSON.readTree(bytes);
            if (body == null || !body.isObject()) {
                throw WorkRefusal.invalidSettings();
            }
            return body;
        } catch (final IOException | JacksonException invalid) {
            throw WorkRefusal.invalidSettings();
        }
    }

    static void empty(final HttpServletRequest request) {
        try {
            if (request.getInputStream().readNBytes(1).length != 0) {
                throw WorkRefusal.invalidSettings();
            }
        } catch (final IOException invalid) {
            throw WorkRefusal.invalidSettings();
        }
    }

    static WorkRun.Report event(final JsonNode body) {
        fields(body, Set.of("offer", "event", "container", "machine", "ended", "landed"));
        final String machine = text(body, "machine");
        WorkRun.checkedMachine(machine);
        final WorkRun.Event event;
        try {
            event = WorkRun.Event.valueOf(text(body, "event"));
        } catch (final IllegalArgumentException invalid) {
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
                text(body, "container"),
                machine,
                ended,
                landings(body));
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
                Set.of(
                        "story",
                        "round",
                        "choice",
                        "comment",
                        "revision",
                        "mode",
                        "agent",
                        "model"));
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
                text(body, "revision"),
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

    static Request request(final JsonNode body, final String from, final String to) {
        fields(
                body,
                Set.of(
                        "project",
                        "story",
                        "to",
                        "mode",
                        "revision",
                        "repositories",
                        "comment",
                        "readingRounds",
                        "issueReadingRounds",
                        "issues"));
        final List<WorkAnswers.Repository> repositories;
        if (body.has("repositories")) {
            repositories = new ArrayList<>();
            for (final JsonNode repository : array(body, "repositories")) {
                repositories.add(repository(repository));
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
                text(body, "revision"),
                repositories,
                body.has("comment") ? text(body, "comment") : null,
                body.has("readingRounds") ? rounds(body.path("readingRounds"), from, to) : null,
                overrides,
                chosen(body));
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
        final List<WorkAnswers.Repository> repositories = request.repositories();
        final List<IssueLimit> overrides = request.issueReadingRounds();
        return new WorkOrder.Decision(
                request.mode(),
                request.revision(),
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

    private static WorkAnswers.Repository repository(final JsonNode node) {
        fields(node, Set.of("repository", "issues", "main", "subAgents"));
        final List<String> issues = new ArrayList<>();
        for (final JsonNode issue : array(node, "issues")) {
            if (!issue.isString()) {
                throw WorkRefusal.invalidSettings();
            }
            issues.add(issue.asString());
        }
        final JsonNode main = node.path("main");
        fields(main, Set.of("name", "role", "agent", "model", "brief", "effort", "chosenBy"));
        final WorkAnswers.Worker worker =
                new WorkAnswers.Worker(
                        text(main, "name"),
                        text(main, "role"),
                        text(main, "agent"),
                        text(main, "model"),
                        text(main, "brief"),
                        effort(main),
                        chosenBy(main));
        final List<WorkAnswers.Assignment> helpers = new ArrayList<>();
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
                    new WorkAnswers.Assignment(
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
        return new WorkAnswers.Repository(text(node, "repository"), issues, worker, helpers);
    }

    private static WorkProposal.Repository coreRepository(final WorkAnswers.Repository repository) {
        final WorkAnswers.Worker main = repository.main();
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
        // Any number is judged by the core after the revision, so a stale call is told as stale.
        if (!node.isNumber()) {
            throw WorkRefusal.invalidSettings();
        }
        return node.isIntegralNumber() ? node.bigIntegerValue() : node.decimalValue();
    }
}
