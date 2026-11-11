package org.fuin.porelo.planner.app;

import java.net.URI;
import java.util.Map;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.git.LogText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Answers each refusal as {@code application/problem+json}: its name in the type, its words in the
 * detail and what it names in {@code parameters}.
 */
@RestControllerAdvice
public class Problems {

    private static final Logger LOG = LoggerFactory.getLogger(Problems.class);

    /** The prefix of every problem type; the window reads the name after it. */
    static final String TYPE = "urn:porelo-planner:";

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
        final HttpStatus status =
                switch (refused.name()) {
                    case "NoSuchStory", "NoSuchIssue" -> HttpStatus.NOT_FOUND;
                    case "StoryStateMismatch" -> HttpStatus.CONFLICT;
                    default -> HttpStatus.UNPROCESSABLE_CONTENT;
                };
        return problem(
                refused.name(), status, String.valueOf(refused.getMessage()), refused.parameters());
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
        if ("/projects/{project}/moves".equals(pattern)) {
            // Fixed words: the parser's text would repeat whatever the body held.
            final String reason = "The body is no batch of moves.";
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
     * Answers a refusal of the store.
     *
     * @param refusal the store's refusal, whose words are already free of secrets and local paths
     * @return the problem
     */
    @ExceptionHandler(StoreException.class)
    public ResponseEntity<ProblemDetail> refused(final StoreException refusal) {
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
            case StoreException.StaleRevision stale ->
                    problem(
                            "StaleRevision",
                            HttpStatus.CONFLICT,
                            "The board moved since that revision.",
                            Map.of("revision", stale.current()));
            case StoreException.NotSavable any ->
                    problem(
                            "MoveNotAllowed",
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            detail,
                            Map.of("item", subject, "from", "", "to", "", "reason", detail));
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
                            Map.of("pushed", String.join(",", partly.pushed()), "failed", subject));
        };
    }

    /** A store that could not be read or saved to is worth a warning, with its own words. */
    private static void warnOfTheStore(
            final StoreException refusal, final String subject, final String detail) {
        switch (refusal) {
            case StoreException.WorkflowMissing any -> warn("has no workflow", subject, detail);
            case StoreException.CredentialRefused any -> warn("refused the login", subject, detail);
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
}
