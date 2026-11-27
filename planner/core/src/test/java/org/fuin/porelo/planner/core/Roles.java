package org.fuin.porelo.planner.core;

import java.util.List;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/** The roles the tests of core give their rules, as a project's work rules would define them. */
final class Roles {

    /** Builds and pushes its own work. */
    static final Role AUTHOR =
            new Role(
                    "author",
                    Role.End.PUSHES_WORK,
                    Role.Workspace.OWN,
                    false,
                    true,
                    Role.WriteAs.AUTHOR,
                    List.of(Role.Right.PUSH, Role.Right.REPLY));

    /** Reads a build's landed work and reports findings. */
    static final Role READER =
            new Role(
                    "reader",
                    Role.End.REPORTS_FINDINGS,
                    Role.Workspace.READ_ONLY,
                    true,
                    true,
                    Role.WriteAs.AUTHOR,
                    List.of(Role.Right.REPORT));

    /** Gives points where another task's work waits. */
    static final Role REVIEWER =
            new Role(
                    "reviewer",
                    Role.End.GIVES_POINTS,
                    Role.Workspace.FOREIGN,
                    false,
                    false,
                    Role.WriteAs.REVIEW,
                    List.of(Role.Right.REPLY, Role.Right.OPEN_THREADS));

    /** A project's own role: reports findings about another task's work, opening threads there. */
    static final Role TESTER =
            new Role(
                    "tester",
                    Role.End.REPORTS_FINDINGS,
                    Role.Workspace.FOREIGN,
                    false,
                    false,
                    Role.WriteAs.REVIEW,
                    List.of(Role.Right.OPEN_THREADS, Role.Right.REPORT));

    /** A project's own role: gives points at another task's waiting push and may reject it. */
    static final Role SCREENER =
            new Role(
                    "screener",
                    Role.End.GIVES_POINTS,
                    Role.Workspace.FOREIGN,
                    false,
                    false,
                    Role.WriteAs.REVIEW,
                    List.of(Role.Right.REPLY, Role.Right.OPEN_THREADS, Role.Right.REJECT));

    /** Hands back a result file. */
    static final Role ANALYST =
            new Role(
                    "analyst",
                    Role.End.HANDS_BACK_RESULT,
                    Role.Workspace.READ_ONLY,
                    false,
                    true,
                    Role.WriteAs.AUTHOR,
                    List.of(Role.Right.REPORT));

    private Roles() {}

    /** Answers the task as one of the reviewer's role at the place given. */
    static WorkOrder.Task reviewing(final WorkOrder.Task task, final RunPlace place) {
        return new WorkOrder.Task(
                task.repository(),
                task.issues(),
                REVIEWER,
                task.agent(),
                task.model(),
                task.effort(),
                task.mode(),
                task.prompt(),
                task.readLanded(),
                task.basis(),
                task.used(),
                task.result(),
                task.given(),
                task.work(),
                false,
                false,
                place);
    }

    /**
     * Answers how a task stands at its place in one word, for an assertion to read: REVIEWER at
     * another task's work, AUTHOR for one the planner offers again by itself, null for any other.
     */
    static String runRole(final WorkOrder.Task task) {
        if (task.atAnothersWork()) {
            return "REVIEWER";
        }
        return task.again() ? "AUTHOR" : "null";
    }

    /**
     * Answers what a task does in one word, for an assertion to read: MAIN for the task that leads
     * its repository's work, READER for one that reads, DISCUSSION for the one without a role and
     * BUILDER for any other that builds.
     */
    static String duty(final WorkOrder.Task task) {
        if (task.discussion()) {
            return "DISCUSSION";
        }
        if (task.reading()) {
            return "READER";
        }
        return task.lead() ? "MAIN" : "BUILDER";
    }

    /**
     * Answers the role a rule of the shape given takes: one that hands back a result where the rule
     * names one, a reader where findings lead somewhere, an author otherwise.
     */
    static Role forRule(final @Nullable String blocked, final WorkRule.@Nullable Result result) {
        if (result != null) {
            return ANALYST;
        }
        return blocked == null ? AUTHOR : READER;
    }
}
