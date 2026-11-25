package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.core.PlaceReviews;
import org.fuin.porelo.planner.core.ReviewOpen;
import org.fuin.porelo.planner.core.RunPlace;
import org.fuin.porelo.planner.core.RunRole;
import org.fuin.porelo.planner.core.TaskRecords.Unanswered;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/**
 * What is open at a run's place, read by running Porelo's executable's {@code review open} command
 * and checking every member of its answer before any of it is used.
 */
public final class PoreloReviewOpen implements PlaceReviews {

    /** The most of an answer read; a longer one is refused as cut off. */
    static final int ANSWER_LIMIT = 4 * 1024 * 1024;

    /** How long one run of Porelo may take, before the factor {@code services}. */
    private static final Duration LIMIT = Duration.ofSeconds(60);

    private static final Set<String> MEMBERS =
            Set.of(
                    "head",
                    "decision",
                    "mergedCommit",
                    "mergedHead",
                    "decisionSince",
                    "points",
                    "findings",
                    "held",
                    "complete",
                    "reason",
                    "version");

    private static final Set<String> REFUSALS =
            Set.of("PlaceUnreadable", "UnknownForge", "NoPullRequest");

    private static final Set<String> REASONS =
            Set.of(
                    "NoSuchPush",
                    "NotInProject",
                    "MovedSinceReview",
                    "MissingForgeToken",
                    "ForgeAdapterMissing",
                    "Unreachable");

    /** A name as Porelo's contract gives one, such as why an answer is incomplete. */
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[A-Z][A-Za-z]{0,63}");

    private static final Set<String> POINT = Set.of("thread", "path", "line", "lastBy", "lastAt");

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Path executable;

    private final PoreloTaskRecords.Runner runner;

    private final Map<String, String> environment;

    private final LimitFactors limits;

    /**
     * Runs Porelo's executable as a process of its own, handing it the factors.
     *
     * @param executable the absolute path of Porelo's executable
     * @param limits the factors
     */
    public PoreloReviewOpen(final Path executable, final LimitFactors limits) {
        this(
                executable,
                new PoreloTaskRecords.ProcessRunner(limits.services(LIMIT)),
                System.getenv(),
                limits);
    }

    PoreloReviewOpen(
            final Path executable,
            final PoreloTaskRecords.Runner runner,
            final Map<String, String> source,
            final LimitFactors limits) {
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Porelo's executable must be given by an absolute path");
        }
        this.executable = executable;
        this.runner = runner;
        final Map<String, String> passed = new LinkedHashMap<>();
        for (final String name : PoreloTaskRecords.PASSED) {
            final String value = source.get(name);
            if (value != null) {
                passed.put(name, value);
            }
        }
        this.environment = Map.copyOf(passed);
        this.limits = limits;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when the project is no repository name, or its own pull
     *     request is asked about without the task, a caller's error and not Porelo's
     */
    @Override
    public ReviewOpen open(
            final String project,
            final RunPlace place,
            final @Nullable RunRole role,
            final @Nullable String task) {
        Ids.name(project);
        if (place.ownPullRequest() != null && task == null) {
            throw new IllegalArgumentException("A task's own pull request is read with its task");
        }
        final PoreloTaskRecords.Result result =
                runner.run(command(project, place, role, task), environment);
        if (result.code() == 69 && !result.cutOff()) {
            throw refusal(result.out());
        }
        if (result.code() == 70) {
            throw new Unanswered("Porelo cannot read what is open at the place");
        }
        if (result.code() == 64) {
            throw new Unanswered("Porelo refused the place it was asked about");
        }
        if (result.code() != 0) {
            // Its error output may echo a command or a token, so none of it is shown.
            throw new Unanswered(
                    "Porelo's answer of what is open failed with exit code " + result.code());
        }
        if (result.cutOff() || result.out().length > ANSWER_LIMIT) {
            throw new Unanswered(
                    "Porelo's answer of what is open was cut off after " + ANSWER_LIMIT + " bytes");
        }
        return parse(result.out());
    }

    /** The command asking Porelo; the one place its options are named. */
    List<String> command(
            final String project,
            final RunPlace place,
            final @Nullable RunRole role,
            final @Nullable String task) {
        final List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add(LimitFactors.OPTION);
        command.add(limits.line());
        command.add("review");
        command.add("open");
        command.add("-p");
        command.add(project);
        final RunPlace.GatePush gate = place.gatePush();
        if (gate != null) {
            command.add("--gate-push");
            command.add(gate.name());
            if (gate.repository() != null) {
                command.add("--repository");
                command.add(gate.repository());
            }
        } else if (place.pullRequest() != null) {
            command.add("--pull-request");
            command.add(place.pullRequest());
        } else {
            command.add("--own-pull-request");
        }
        if (role != null) {
            command.add("--role");
            command.add(role.name());
        }
        if (task != null) {
            command.add("--task");
            command.add(task);
        }
        command.add("--json");
        return List.copyOf(command);
    }

    /** Reads a typed refusal, its name and reason from Porelo's contract, nothing else of it. */
    static PlaceReviews.Refused refusal(final byte[] answer) {
        final JsonNode root;
        try {
            root = JSON.readTree(answer);
        } catch (final IOException ex) {
            throw new Unanswered("Porelo's refusal of the place is no single JSON document");
        }
        if (root == null || !root.isObject()) {
            throw new Unanswered("Porelo's refusal of the place is no JSON object");
        }
        final String name = text(root, "refused", "refused");
        if (!REFUSALS.contains(name)) {
            throw refused("refused", "is none of " + REFUSALS);
        }
        final String reason =
                "PlaceUnreadable".equals(name) ? text(root, "reason", "reason") : null;
        if (reason != null && !REASONS.contains(reason)) {
            throw refused("reason", "is none of " + REASONS);
        }
        return new PlaceReviews.Refused(name, reason);
    }

    static ReviewOpen parse(final byte[] answer) {
        final JsonNode root;
        try {
            root = JSON.readTree(answer);
        } catch (final IOException ex) {
            // The parser's message quotes the answer.
            throw new Unanswered("Porelo's answer of what is open is no single JSON document");
        }
        if (root == null || !root.isObject()) {
            throw new Unanswered("Porelo's answer of what is open is no JSON object");
        }
        only(root, MEMBERS, "");
        final List<ReviewOpen.Point> points = new ArrayList<>();
        final JsonNode listed = present(root, "points", "points");
        if (!listed.isArray()) {
            throw refused("points", "is no array");
        }
        for (int i = 0; i < listed.size(); i++) {
            points.add(point(listed.get(i), "points[" + i + "]"));
        }
        final String decision = text(root, "decision", "decision");
        final ReviewOpen.Decision named;
        try {
            named = ReviewOpen.Decision.valueOf(decision);
        } catch (final IllegalArgumentException ex) {
            throw refused("decision", "is none of " + List.of(ReviewOpen.Decision.values()));
        }
        final JsonNode complete = present(root, "complete", "complete");
        if (!complete.isBoolean()) {
            throw refused("complete", "is no boolean");
        }
        final String since = textOrNull(root, "decisionSince", "decisionSince");
        final String reason = textOrNull(root, "reason", "reason");
        if (reason != null && !NAME.matcher(reason).matches()) {
            // Only Porelo's name for why an answer is incomplete is taken, never free text.
            throw refused("reason", "is no name of Porelo's");
        }
        try {
            return new ReviewOpen(
                    textOrNull(root, "head", "head"),
                    named,
                    textOrNull(root, "mergedCommit", "mergedCommit"),
                    textOrNull(root, "mergedHead", "mergedHead"),
                    since == null ? null : utc(since, "decisionSince"),
                    points,
                    count(root, "findings", "findings"),
                    count(root, "held", "held"),
                    complete.booleanValue(),
                    reason,
                    text(root, "version", "version"));
        } catch (final IllegalArgumentException ex) {
            throw new Unanswered(
                    "Porelo's answer of what is open cannot be one: " + ex.getMessage(), ex);
        }
    }

    private static ReviewOpen.Point point(final JsonNode point, final String path) {
        if (!point.isObject()) {
            throw refused(path, "is no object");
        }
        only(point, POINT, path);
        final JsonNode line = point.get("line");
        Integer at = null;
        if (line != null && !line.isNull()) {
            if (!line.isIntegralNumber() || !line.canConvertToInt() || line.intValue() < 1) {
                throw refused(path + ".line", "is no line number");
            }
            at = line.intValue();
        }
        try {
            return new ReviewOpen.Point(
                    text(point, "thread", path + ".thread"),
                    textOrNull(point, "path", path + ".path"),
                    at,
                    text(point, "lastBy", path + ".lastBy"),
                    utc(text(point, "lastAt", path + ".lastAt"), path + ".lastAt"));
        } catch (final IllegalArgumentException ex) {
            throw refused(path, "cannot be an open point: " + ex.getMessage());
        }
    }

    private static void only(final JsonNode object, final Set<String> known, final String path) {
        for (final Iterator<String> names = object.fieldNames(); names.hasNext(); ) {
            if (!known.contains(names.next())) {
                // The member's name comes from outside and is not repeated.
                throw refused(path, "holds a member the contract does not describe");
            }
        }
    }

    private static JsonNode present(final JsonNode object, final String name, final String path) {
        final JsonNode value = object.get(name);
        if (value == null) {
            throw refused(path, "is missing");
        }
        return value;
    }

    private static String text(final JsonNode object, final String name, final String path) {
        final JsonNode value = present(object, name, path);
        if (!value.isTextual()) {
            throw refused(path, "is no string");
        }
        return value.textValue();
    }

    private static @Nullable String textOrNull(
            final JsonNode object, final String name, final String path) {
        final JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw refused(path, "is neither absent nor a string");
        }
        return value.textValue();
    }

    private static int count(final JsonNode object, final String name, final String path) {
        final JsonNode value = present(object, name, path);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw refused(path, "is no whole number of at least zero");
        }
        return value.intValue();
    }

    private static Instant utc(final String value, final String path) {
        // Instant.parse also takes an offset, which the contract does not allow.
        if (!value.endsWith("Z")) {
            throw refused(path, "is no UTC instant ending in Z");
        }
        try {
            return Instant.parse(value);
        } catch (final DateTimeParseException ex) {
            throw refused(path, "is no UTC instant ending in Z");
        }
    }

    private static Unanswered refused(final String path, final String what) {
        return new Unanswered(
                "Porelo's answer of what is open"
                        + (path.isEmpty() ? "" : " field " + path)
                        + " "
                        + what);
    }
}
