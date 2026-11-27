package org.fuin.porelo.planner.app;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.git.LogText;
import org.fuin.porelo.planner.store.git.PoreloProgram;
import org.fuin.porelo.planner.store.local.ItemsDirectories;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers each refusal as {@code application/problem+json}: its name in the type, its words in the
 * detail and what it names in {@code parameters}.
 */
@RestControllerAdvice
// A handler's ProblemDetail return type does not name the typed parameters Jackson writes, and
// a batch's refused item is read into them too.
@RegisterReflectionForBinding({
    org.fuin.porelo.planner.app.answers.ChangedItem.class,
    org.fuin.porelo.planner.app.answers.ProblemParameters.class
})
public class Problems {

    private static final Logger LOG = LoggerFactory.getLogger(Problems.class);

    /** The prefix of every problem type; the window reads the name after it. */
    static final String TYPE = "urn:porelo-planner:";

    /** Reads a refusal's parameters into the record the description names; none is left out. */
    private static final JsonMapper PARAMETERS =
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    /** The most causes the line names, outermost first. */
    private static final int CAUSES = 4;

    /** The most frames of the innermost cause the line names. */
    private static final int FRAMES = 8;

    private final Projects projects;

    private final TaskHelds helds;

    /**
     * Makes the answers, which know no task's held items.
     *
     * @param projects the projects the window has given, whose boards an ItemChanged refusal
     *     answers its items from
     */
    public Problems(final Projects projects) {
        this(projects, new TaskHelds());
    }

    /**
     * Makes the answers.
     *
     * @param projects the projects the window has given, whose boards an ItemChanged refusal
     *     answers its items from
     * @param helds the held items of the tasks, as the place watch read them
     */
    @Autowired
    public Problems(final Projects projects, final TaskHelds helds) {
        this.projects = projects;
        this.helds = helds;
    }

    /**
     * Answers a refusal of the planner's own.
     *
     * @param refusal the refusal
     * @return the problem
     */
    @ExceptionHandler(Refusal.class)
    public ResponseEntity<ProblemDetail> refused(final Refusal refusal) {
        return problem(
                refusal.name(),
                refusal.status(),
                String.valueOf(refusal.getMessage()),
                refusal.parameters());
    }

    /**
     * Answers a batch of moves refused by its checks.
     *
     * @param refused the refusal
     * @return the problem
     */
    @ExceptionHandler(Moves.Refused.class)
    public ResponseEntity<ProblemDetail> refused(final Moves.Refused refused) {
        return problem(
                refused.name(),
                status(refused),
                String.valueOf(refused.getMessage()),
                refused.parameters());
    }

    private static HttpStatus status(final Moves.Refused refused) {
        return switch (refused.name()) {
            case "NoSuchStory", "NoSuchIssue", "NotUnlisted" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }

    /**
     * Answers a refusal of one item of a batch as the generated problem record, exactly as the call
     * alone would answer it: the same name, status, words and parameters.
     *
     * @param refusal the item's refusal, a move's or a work refusal
     * @return the problem
     */
    static org.fuin.porelo.planner.app.answers.Problem answer(final RuntimeException refusal) {
        final String name;
        final HttpStatus status;
        final Map<String, ?> parameters;
        if (refusal instanceof Moves.Refused refused) {
            name = refused.name();
            status = status(refused);
            parameters = refused.parameters();
        } else if (refusal instanceof WorkRefusal work) {
            final Refusal alone = Refusal.work(work);
            name = alone.name();
            status = alone.status();
            parameters = alone.parameters();
        } else {
            throw refusal;
        }
        return new org.fuin.porelo.planner.app.answers.Problem(
                TYPE + name,
                name,
                status.value(),
                String.valueOf(refusal.getMessage()),
                PARAMETERS.convertValue(
                        parameters, org.fuin.porelo.planner.app.answers.ProblemParameters.class));
    }

    /**
     * Answers an unreadable body without exposing parser text or unchecked path subjects.
     *
     * @param unreadable what Spring could not read
     * @param request the request, whose path names the project
     * @return the problem
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(
            final HttpMessageNotReadableException unreadable, final WebRequest request) {
        final Object variables =
                request.getAttribute(
                        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                        RequestAttributes.SCOPE_REQUEST);
        final Object project = variables instanceof Map<?, ?> map ? map.get("project") : null;
        final Map<String, String> parameters;
        if (project instanceof String name) {
            try {
                parameters = Map.of("project", BoardController.name(name));
            } catch (final Refusal invalid) {
                return refused(invalid);
            }
        } else {
            parameters = Map.of();
        }
        final Object pattern =
                request.getAttribute(
                        HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        RequestAttributes.SCOPE_REQUEST);
        if ("/projects/{project}/moves".equals(pattern)
                || "/projects/{project}/corrections".equals(pattern)
                || "/projects/{project}/corrections/preview".equals(pattern)) {
            // Fixed words: the parser's text would repeat whatever the body held.
            final String reason =
                    "/projects/{project}/moves".equals(pattern)
                            ? "The body is no batch of moves."
                            : "The body is no correction.";
            final Map<String, String> fields = new java.util.LinkedHashMap<>();
            fields.put("item", "");
            fields.put("from", "");
            fields.put("to", "");
            fields.put("reason", reason);
            return problem("MoveNotAllowed", HttpStatus.UNPROCESSABLE_CONTENT, reason, fields);
        }
        if ("/log/level".equals(pattern)) {
            return problem(
                    "InvalidLevel",
                    HttpStatus.BAD_REQUEST,
                    "The body is no level of ERROR, WARN, INFO and DEBUG.",
                    Map.of());
        }
        if (!"/projects/{project}".equals(pattern)) {
            return refused(WorkRefusal.invalidSettings());
        }
        return problem(
                "NotACloneUrl",
                HttpStatus.BAD_REQUEST,
                "The body is no project source with a repository and a branch",
                parameters);
    }

    /** Answers a Spring-free work refusal with typed parameters, including task arrays. */
    @ExceptionHandler(WorkRefusal.class)
    public ResponseEntity<ProblemDetail> refused(final WorkRefusal refusal) {
        return refused(Refusal.work(refusal));
    }

    /**
     * Answers a fault no other handler names: one ERROR line in the planner's log names the call,
     * the cause chain with each message scrubbed and where the innermost cause was thrown, and the
     * caller gets fixed words alone. A fault Spring answers itself is handed back to it.
     *
     * @param fault the fault
     * @param request the request, whose method and path pattern the line names
     * @return the problem, or none when the answer has begun already, as a stream's has
     */
    @ExceptionHandler(RuntimeException.class)
    public @Nullable ResponseEntity<ProblemDetail> unexpected(
            final RuntimeException fault, final WebRequest request) {
        if (fault instanceof ErrorResponse || fault instanceof TypeMismatchException) {
            // Spring's own answer, such as 400 for a parameter of the wrong type.
            throw fault;
        }
        logUnexpected(fault, request);
        final jakarta.servlet.http.HttpServletResponse response =
                request instanceof ServletWebRequest servlet ? servlet.getResponse() : null;
        if (response != null && response.isCommitted()) {
            // A stream's answer has begun; no problem can follow it.
            return null;
        }
        return problem(
                "Unexpected",
                HttpStatus.INTERNAL_SERVER_ERROR,
                "The planner failed on this call; its log names the cause.",
                Map.of());
    }

    /**
     * Logs one line of separate values, never the fault itself, whose trace would print each
     * message unscrubbed and span lines.
     */
    private static void logUnexpected(final Throwable fault, final WebRequest request) {
        final List<Throwable> chain = new java.util.ArrayList<>();
        final java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable each = fault; each != null && seen.add(each); each = each.getCause()) {
            chain.add(each);
        }
        final List<Object> values = new java.util.ArrayList<>();
        values.add(
                LogText.of(
                        request instanceof ServletWebRequest servlet
                                ? servlet.getHttpMethod().name()
                                : "?"));
        final Object pattern =
                request.getAttribute(
                        HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        RequestAttributes.SCOPE_REQUEST);
        // The pattern, never the raw path, which may carry what the call sent.
        values.add(LogText.of(pattern instanceof String text ? text : "?"));
        final StringBuilder format = new StringBuilder("A call to {} {} failed unexpectedly: {}");
        for (int i = 0; i < Math.min(chain.size(), CAUSES); i++) {
            if (i > 0) {
                format.append("; caused by {}");
            }
            final Throwable cause = chain.get(i);
            values.add(
                    LogText.of(
                            simpleName(cause.getClass().getName())
                                    + ": "
                                    + PoreloProgram.scrubbed(cause.getMessage())));
        }
        final StackTraceElement[] frames = chain.getLast().getStackTrace();
        if (frames.length == 0) {
            // A trace left out, as some throwables do to save its cost, says so.
            format.append("; no frames");
            LOG.error(format.toString(), values.toArray());
            return;
        }
        format.append("; at {}");
        values.add(
                LogText.of(
                        java.util.Arrays.stream(frames)
                                .limit(FRAMES)
                                .map(
                                        frame ->
                                                simpleName(frame.getClassName())
                                                        + "."
                                                        + frame.getMethodName()
                                                        + ":"
                                                        + frame.getLineNumber())
                                .collect(java.util.stream.Collectors.joining(", "))));
        LOG.error(format.toString(), values.toArray());
    }

    /** A class's name without its package, an inner or a hidden class's name kept whole. */
    private static String simpleName(final String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /**
     * Answers a refusal of the store.
     *
     * @param refusal the store's refusal, whose words are already free of secrets and local paths
     * @param request the request, whose path names the project an ItemChanged refusal reads
     * @return the problem
     */
    @ExceptionHandler(StoreException.class)
    public ResponseEntity<ProblemDetail> refused(
            final StoreException refusal, final WebRequest request) {
        final String detail = String.valueOf(refusal.getMessage());
        final String subject = refusal.subject();
        warnOfTheStore(refusal, subject, detail);
        return switch (refusal) {
            case StoreException.NoSuchStory any ->
                    problem("NoSuchStory", HttpStatus.NOT_FOUND, detail, Map.of("story", subject));
            case StoreException.NoSuchIssue any ->
                    problem("NoSuchIssue", HttpStatus.NOT_FOUND, detail, Map.of("issue", subject));
            case StoreException.WorkflowMissing any ->
                    problem(
                            "WorkflowMissing",
                            HttpStatus.BAD_GATEWAY,
                            detail,
                            Map.of("repository", subject));
            case StoreException.CredentialRefused any ->
                    problem(
                            "CredentialRefused",
                            HttpStatus.BAD_GATEWAY,
                            detail,
                            Map.of("repository", subject));
            case StoreException.NoForgeAccess refusedAccess ->
                    problem(
                            "NoForgeAccess",
                            HttpStatus.BAD_GATEWAY,
                            detail,
                            forgeAccess(subject, refusedAccess.right()));
            case StoreException.Unreachable any ->
                    problem(
                            "Unreachable",
                            HttpStatus.GATEWAY_TIMEOUT,
                            detail,
                            Map.of("repository", subject));
            case StoreException.Unreadable any ->
                    problem(
                            "Unreadable",
                            HttpStatus.BAD_GATEWAY,
                            detail,
                            Map.of("repository", subject));
            case StoreException.ItemsDirectoryRefused refused ->
                    problem(
                            "ItemsDirectoryRefused",
                            HttpStatus.CONFLICT,
                            detail,
                            itemsDirectory(refused));
            case StoreException.StaleRevision any ->
                    // No call is checked against a revision; one that still reaches a caller is a
                    // save that kept losing to another, which a retry answers.
                    problem(
                            "Busy",
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "The project is busy with another save; try again.",
                            Map.of("repository", ""));
            case StoreException.Changed changed ->
                    problem(
                            "ItemChanged",
                            HttpStatus.CONFLICT,
                            "An item the write changes changed since it was read.",
                            Map.of("changed", changed.ids(), "items", items(changed, request)));
            case StoreException.BasisIncomplete incomplete ->
                    problem(
                            "BasisIncomplete",
                            HttpStatus.CONFLICT,
                            "The write names no version for an item it changes.",
                            Map.of("missing", incomplete.missing()));
            case StoreException.NotSavable any ->
                    problem(
                            "MoveNotAllowed",
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            detail,
                            Map.of("item", subject, "from", "", "to", "", "reason", detail));
            case StoreException.InStory held ->
                    problem(
                            "IssueInStory",
                            HttpStatus.CONFLICT,
                            "The issue belongs to another story; an issue has one story.",
                            Map.of("issue", subject, "story", held.story(), "field", held.field()));
            case StoreException.Busy any ->
                    problem(
                            "Busy",
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "The project is busy with another save; try again.",
                            Map.of("repository", subject));
            case StoreException.PartlySaved partly ->
                    problem(
                            "PartlySaved",
                            HttpStatus.BAD_GATEWAY,
                            detail,
                            partly.issues().isEmpty()
                                    ? Map.of(
                                            "pushed",
                                            String.join(",", partly.pushed()),
                                            "failed",
                                            subject)
                                    : Map.of(
                                            "pushed",
                                            String.join(",", partly.pushed()),
                                            "failed",
                                            subject,
                                            "issues",
                                            String.join(",", partly.issues())));
            case StoreException.LabelsNotKept dropped ->
                    problem(
                            "LabelsNotKept",
                            HttpStatus.BAD_GATEWAY,
                            detail,
                            Map.of("repository", subject, "item", dropped.item()));
        };
    }

    /**
     * The changed targets as the project's board holds them now; a call whose project cannot be
     * named answers each as gone.
     */
    private List<org.fuin.porelo.planner.app.answers.ChangedItem> items(
            final StoreException.Changed changed, final WebRequest request) {
        final Object variables =
                request.getAttribute(
                        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                        RequestAttributes.SCOPE_REQUEST);
        final Object project = variables instanceof Map<?, ?> map ? map.get("project") : null;
        if (project instanceof String name) {
            try {
                return BoardController.changed(
                        helds.in(BoardController.name(name)),
                        projects.work(BoardController.name(name)),
                        changed.ids());
            } catch (final RuntimeException unreadable) {
                // The refusal stands; only the items it would show cannot be read now.
                LOG.info(
                        "The changed items could not be read: {}", unreadable.getClass().getName());
            }
        }
        return changed.ids().stream()
                .map(
                        id ->
                                new org.fuin.porelo.planner.app.answers.ChangedItem(
                                        id, true, null, null, null))
                .toList();
    }

    /** A store that could not be read or saved to is worth a warning, with its own words. */
    private static void warnOfTheStore(
            final StoreException refusal, final String subject, final String detail) {
        switch (refusal) {
            case StoreException.WorkflowMissing any -> warn("has no workflow", subject, detail);
            case StoreException.CredentialRefused any -> warn("refused the login", subject, detail);
            case StoreException.NoForgeAccess any ->
                    warn("refused the forge access", subject, detail);
            case StoreException.Unreachable any -> warn("could not be reached", subject, detail);
            case StoreException.Unreadable any -> warn("could not be read", subject, detail);
            case StoreException.PartlySaved any -> warn("was not pushed", subject, detail);
            default -> {
                // A refusal of the window's call, logged by its kind alone.
            }
        }
    }

    private static void warn(final String what, final String subject, final String detail) {
        LOG.warn("Repository {} {}: {}", LogText.of(subject), what, LogText.of(detail));
    }

    private static ResponseEntity<ProblemDetail> problem(
            final String name,
            final HttpStatus status,
            final String detail,
            final Map<String, ?> parameters) {
        // Its kind alone: the detail may repeat what the call carried.
        LOG.info("Refused a call: {} ({})", name, status.value());
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE + name));
        problem.setTitle(name);
        // Refused path text must not enter Spring's default instance URI.
        problem.setInstance(URI.create("/"));
        problem.setProperty("parameters", parameters);
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * The directory an ItemsDirectoryRefused refusal names, absolute: the person's own choice,
     * named to them over loopback to a signed request; none where it names none or a text outside
     * the rule a directory follows, so no refusal carries what a body or a file put there.
     *
     * @param refused the refusal
     * @return the parameters
     */
    static Map<String, Object> itemsDirectory(final StoreException.ItemsDirectoryRefused refused) {
        final String directory = refused.directory();
        return directory == null || ItemsDirectories.shapeFault(directory) != null
                ? Map.of()
                : Map.of("directory", directory);
    }

    private static Map<String, Object> forgeAccess(
            final String address, final @Nullable String right) {
        return right == null
                ? Map.of("address", address)
                : Map.of("address", address, "right", right);
    }
}
