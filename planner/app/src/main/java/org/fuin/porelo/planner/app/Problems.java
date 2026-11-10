package org.fuin.porelo.planner.app;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.planner.store.StoreException;
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
     * Answers a body that is no project source, since the only body the planner takes is one.
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
        return problem(
                "NotACloneUrl",
                HttpStatus.BAD_REQUEST,
                "The body is no project source with a repository and a branch",
                project == null ? Map.of() : Map.of("project", Objects.toString(project)));
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
        };
    }

    private static ResponseEntity<ProblemDetail> problem(
            final String name,
            final HttpStatus status,
            final String detail,
            final Map<String, String> parameters) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE + name));
        problem.setTitle(name);
        problem.setProperty("parameters", parameters);
        return ResponseEntity.status(status).body(problem);
    }
}
