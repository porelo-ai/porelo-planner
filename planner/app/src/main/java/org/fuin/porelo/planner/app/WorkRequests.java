package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.fuin.porelo.planner.core.ReadingCycle;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.jspecify.annotations.Nullable;
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

    /** A target state for a recomputed work proposal. */
    public record Proposal(String to) {}

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
                    @Nullable List<IssueLimit> issueReadingRounds) {}

    /** An explicitly supplied reading bound, kept numeric until core validation. */
    public record IssueLimit(String issue, Number rounds) {}

    /** The checked lifecycle request; ended is present only for ENDED. */
    public record TaskEventRequest(
            String offer,
            String event,
            String container,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable TaskEnded ended) {}

    /** Porelo's ended record with no outcome invented by the window. */
    public record TaskEnded(String at, boolean finished, String why, String answer, int unpushed) {}

    /** One choice against a completed, stopped reading. */
    public record ReadingRequest(
            String story, int round, String choice, String comment, String revision) {}

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
        fields(body, Set.of("offer", "event", "container", "ended"));
        final WorkRun.Event event;
        try {
            event = WorkRun.Event.valueOf(text(body, "event"));
        } catch (final IllegalArgumentException invalid) {
            throw WorkRefusal.invalidSettings();
        }
        WorkRun.Ended ended = null;
        if (body.has("ended")) {
            final JsonNode end = body.path("ended");
            fields(end, Set.of("at", "finished", "why", "answer", "unpushed"));
            if (!end.path("finished").isBoolean()) {
                throw WorkRefusal.invalidSettings();
            }
            ended =
                    new WorkRun.Ended(
                            text(end, "at"),
                            end.path("finished").asBoolean(),
                            text(end, "why"),
                            text(end, "answer"),
                            integer(end, "unpushed"));
        }
        return new WorkRun.Report(text(body, "offer"), event, text(body, "container"), ended);
    }

    static ReadingRequest reading(final JsonNode body) {
        fields(body, Set.of("story", "round", "choice", "comment", "revision"));
        final String choice = text(body, "choice");
        try {
            ReadingCycle.Choice.valueOf(choice);
        } catch (final IllegalArgumentException invalid) {
            throw WorkRefusal.invalidSettings();
        }
        return new ReadingRequest(
                text(body, "story"),
                integer(body, "round"),
                choice,
                text(body, "comment"),
                text(body, "revision"));
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
        fields(body, Set.of("to"));
        return new Proposal(text(body, "to"));
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
                        "issueReadingRounds"));
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
                overrides);
    }

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
                                .toList());
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
        fields(main, Set.of("name", "role", "agent", "model", "brief"));
        final WorkAnswers.Worker worker =
                new WorkAnswers.Worker(
                        text(main, "name"),
                        text(main, "role"),
                        text(main, "agent"),
                        text(main, "model"),
                        text(main, "brief"));
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
                            "choice"));
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
                            text(sub, "choice")));
        }
        return new WorkAnswers.Repository(text(node, "repository"), issues, worker, helpers);
    }

    private static WorkProposal.Repository coreRepository(final WorkAnswers.Repository repository) {
        final WorkAnswers.Worker main = repository.main();
        return new WorkProposal.Repository(
                repository.repository(),
                repository.issues(),
                new WorkProposal.Main(
                        main.name(), main.role(), main.agent(), main.model(), main.brief()),
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
                                                sub.choice()))
                        .toList());
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
