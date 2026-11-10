package org.fuin.porelo.planner.core;

import java.util.List;
import java.util.Map;

/** A Spring-free refusal whose parameters contain only checked subjects and fixed reasons. */
public final class WorkRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final String name;
    private final Map<String, Object> parameters;

    private WorkRefusal(
            final String name, final String detail, final Map<String, Object> parameters) {
        super(detail);
        this.name = name;
        this.parameters = Map.copyOf(parameters);
    }

    /** Answers the refusal name used by the window. */
    public String name() {
        return name;
    }

    /** Answers the typed subjects of the refusal. */
    public Map<String, Object> parameters() {
        return parameters;
    }

    /** Refuses a story absent from the board. */
    public static WorkRefusal noSuchStory(final String story) {
        return new WorkRefusal(
                "NoSuchStory", "The board has no such story.", Map.of("story", story));
    }

    /** Refuses an outdated decision, naming the current revision. */
    public static WorkRefusal staleRevision(final String revision) {
        return new WorkRefusal(
                "StaleRevision", "The board has changed.", Map.of("revision", revision));
    }

    /** Refuses duplicate work only from explicit active task records. */
    public static WorkRefusal workRunning(final String story, final List<String> tasks) {
        return new WorkRefusal(
                "WorkRunning",
                "The story already has active work.",
                Map.of("story", story, "tasks", List.copyOf(tasks)));
    }

    /** Refuses a choice against checked workflow states. */
    public static WorkRefusal notAllowed(final String from, final String to, final String reason) {
        return new WorkRefusal(
                "WorkNotAllowed", reason, Map.of("from", from, "to", to, "reason", reason));
    }

    /** Refuses unreadable input without echoing its contents. */
    public static WorkRefusal invalidSettings() {
        final String detail = "The work request is invalid.";
        return new WorkRefusal("InvalidSettings", detail, Map.of("detail", detail));
    }
}
