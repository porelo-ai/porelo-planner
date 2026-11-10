package org.fuin.porelo.planner.app;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** A call the planner refuses, with the name the window shows and what it names. */
public final class Refusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String name;

    private final HttpStatus status;

    private final Map<String, String> parameters;

    /**
     * Makes a refusal.
     *
     * @param name its name, such as {@code NoSuchProject}
     * @param status its HTTP status
     * @param detail its words, free of any credential or local path
     * @param parameters what it names: a project, story, issue or repository
     */
    public Refusal(
            final String name,
            final HttpStatus status,
            final String detail,
            final Map<String, String> parameters) {
        super(detail);
        this.name = name;
        this.status = status;
        this.parameters = Map.copyOf(parameters);
    }

    /**
     * Answers the refusal's name.
     *
     * @return its name
     */
    public String name() {
        return name;
    }

    /**
     * Answers its HTTP status.
     *
     * @return the status
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Answers what it names.
     *
     * @return the parameters
     */
    public Map<String, String> parameters() {
        return parameters;
    }

    /**
     * A name, id or branch the planner does not take.
     *
     * @param text why
     * @param parameters what it names: the project, unless its own name is the one refused
     * @return the refusal
     */
    public static Refusal invalidName(final String text, final Map<String, String> parameters) {
        return new Refusal("InvalidName", HttpStatus.BAD_REQUEST, text, parameters);
    }
}
