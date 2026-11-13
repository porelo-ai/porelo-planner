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

    /**
     * Refuses a Start or a Stop naming a run, or a reason of it, that is not the one standing.
     *
     * @param story the checked story
     * @param reason fixed words saying what changed
     * @return the refusal
     */
    public static WorkRefusal runChanged(final String story, final String reason) {
        return new WorkRefusal("RunChanged", reason, Map.of("story", story, "reason", reason));
    }

    /** Refuses a discussion of an idea the project's rules offer no agent for. */
    public static WorkRefusal noDiscussion(final String reason) {
        return new WorkRefusal("NoDiscussion", reason, Map.of("reason", reason));
    }

    /** Refuses a choice against checked workflow states. */
    public static WorkRefusal notAllowed(final String from, final String to, final String reason) {
        return new WorkRefusal(
                "WorkNotAllowed", reason, Map.of("from", from, "to", to, "reason", reason));
    }

    /** Refuses an offer not issued by this project's current run. */
    public static WorkRefusal taskNotAnswered(final String task, final String offer) {
        return new WorkRefusal(
                "TaskNotAnswered",
                "No current offer answers this task.",
                Map.of("task", task, "offer", offer));
    }

    /** Refuses a report contradicting its established task binding. */
    public static WorkRefusal eventConflict(
            final String task, final String event, final String reason) {
        return new WorkRefusal(
                "TaskEventConflict",
                reason,
                Map.of("task", task, "event", event, "reason", reason));
    }

    /** Refuses a reading choice whose stop has already been cleared. */
    public static WorkRefusal readingNotStopped(final String issue) {
        return new WorkRefusal(
                "ReadingNotStopped", "The reading is not stopped.", Map.of("issue", issue));
    }

    /** Refuses a choice based on another completed round. */
    public static WorkRefusal readingRoundChanged(final String issue, final int rounds) {
        return new WorkRefusal(
                "ReadingRoundChanged",
                "The completed reading changed.",
                Map.of("issue", issue, "rounds", rounds));
    }

    /**
     * Refuses an unavailable record, saying why without an absolute path or the record's content.
     */
    public static WorkRefusal recordUnavailable(final String project, final String reason) {
        return new WorkRefusal(
                "WorkRecordUnavailable", reason, Map.of("project", project, "reason", reason));
    }

    /**
     * Refuses an idea, listing its faults in fixed words and, for an offer saved before, the
     * repositories already pushed.
     *
     * @param detail the refusal's words
     * @param faults the faults, each by its field
     * @param pushed the repositories already pushed, or null when no save of the offer began
     * @return the refusal
     */
    public static WorkRefusal ideaRefused(
            final String detail,
            final List<String> faults,
            final @org.jspecify.annotations.Nullable List<String> pushed) {
        return new WorkRefusal(
                NewIdeas.REFUSED,
                detail,
                pushed == null
                        ? Map.of("faults", List.copyOf(faults))
                        : Map.of(
                                "faults", List.copyOf(faults), "pushed", String.join(",", pushed)));
    }

    /** Refuses unreadable input without echoing its contents. */
    public static WorkRefusal invalidSettings() {
        return invalidSettings("The work request is invalid.");
    }

    /**
     * Refuses a malformed request, saying in fixed words which part is malformed.
     *
     * @param detail the fixed words, never the refused value
     * @return the refusal
     */
    public static WorkRefusal invalidSettings(final String detail) {
        return new WorkRefusal("InvalidSettings", detail, Map.of("detail", detail));
    }
}
