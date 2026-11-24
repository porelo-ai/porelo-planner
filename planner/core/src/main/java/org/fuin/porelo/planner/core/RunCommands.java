package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Writes;

/** Builds the command group one save of a run, a correction or a reading choice writes. */
final class RunCommands {

    private RunCommands() {}

    /**
     * One write command per item the writes change, the issues first and the stories after them,
     * each carrying the versions the save was judged on. The read set the judgment used is checked
     * with the first command alone, right before anything is written; a later command of the group
     * checks its own item, which the commands before it never change.
     *
     * @param id the group's id, which the run's pending mark names too
     * @param writes the writes, not none
     * @param basis the versions the save was judged on
     * @param recheck the rule read again with the first command
     * @return the group
     */
    static CommandGroup group(
            final String id, final Writes writes, final Basis basis, final Recheck recheck) {
        final Set<String> order = new LinkedHashSet<>();
        order.addAll(writes.issues().keySet());
        order.addAll(writes.workers().keySet());
        order.addAll(writes.issueReasons().keySet());
        order.addAll(writes.stories().keySet());
        order.addAll(writes.priorities().keySet());
        order.addAll(writes.storyReasons().keySet());
        final List<Command> commands = new ArrayList<>();
        for (final String target : order) {
            commands.add(
                    new Command.Write(
                            id + "/" + commands.size(),
                            target,
                            writes.only(target),
                            basis,
                            commands.isEmpty() ? recheck : Recheck.none()));
        }
        return new CommandGroup(id, commands);
    }
}
