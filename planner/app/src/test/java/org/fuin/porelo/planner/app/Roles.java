package org.fuin.porelo.planner.app;

import java.util.List;
import org.fuin.porelo.planner.core.RunPlace;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/** The roles the app's tests give their rules, as a project's work rules would define them. */
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
                    Role.WriteAs.REVIEW,
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

    /**
     * Answers the role a rule of the shape given takes: one that hands back a result where the rule
     * names one, a reader where findings lead somewhere, an author otherwise.
     *
     * @param blocked the rule's blocked state, or null
     * @param result the rule's result, or null
     * @return the role
     */
    static Role forRule(final @Nullable String blocked, final WorkRule.@Nullable Result result) {
        if (result != null) {
            return ANALYST;
        }
        return blocked == null ? AUTHOR : READER;
    }

    /**
     * Answers the task with the role given at the place given, standing as it stood.
     *
     * @param task the task
     * @param role the role
     * @param place where it works, or null for its own gate push
     * @return the task
     */
    static WorkOrder.Task as(
            final WorkOrder.Task task, final Role role, final @Nullable RunPlace place) {
        return new WorkOrder.Task(
                task.repository(),
                task.issues(),
                role,
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
                task.lead(),
                task.again(),
                place);
    }
}
