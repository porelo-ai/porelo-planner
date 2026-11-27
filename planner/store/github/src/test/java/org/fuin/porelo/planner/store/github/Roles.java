package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.fuin.porelo.planner.store.Role;

/** The roles the tests' work rules offer, as a project would define them in its work rules. */
final class Roles {

    /** A task that builds in its own work and pushes it. */
    static final Role AUTHOR =
            new Role(
                    "author",
                    Role.End.PUSHES_WORK,
                    Role.Workspace.OWN,
                    false,
                    true,
                    Role.WriteAs.AUTHOR,
                    List.of(Role.Right.PUSH, Role.Right.REPLY));

    private Roles() {}
}
