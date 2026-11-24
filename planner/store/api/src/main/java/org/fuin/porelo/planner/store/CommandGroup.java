package org.fuin.porelo.planner.store;

import java.util.HashSet;
import java.util.List;

/**
 * The commands one save makes, in the order they are written: issues first, a story's close after
 * them. A command refused stops the commands after it.
 *
 * @param id the group's own id
 * @param commands the commands, at least one, each with an id of its own
 */
public record CommandGroup(String id, List<Command> commands) {

    /** Refuses a blank id, an empty group and a command id named twice. */
    public CommandGroup {
        if (id.isBlank()) {
            throw new IllegalArgumentException("A command group is named by an id.");
        }
        commands = List.copyOf(commands);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("A command group holds a command.");
        }
        final var ids = new HashSet<String>();
        for (final Command command : commands) {
            if (!ids.add(command.id())) {
                throw new IllegalArgumentException("A command id is named twice in a group.");
            }
        }
    }
}
