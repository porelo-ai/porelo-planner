package org.fuin.porelo.planner.store;

import java.util.HashSet;
import java.util.List;

/**
 * The commands one save makes, in the order they are written: issues first, a story's close after
 * them. A command refused stops the commands after it.
 *
 * @param id the group's own id
 * @param commands the commands, at least one, each with an id of its own
 * @param story the story whose run saves the group, empty for a window's save
 */
public record CommandGroup(String id, List<Command> commands, String story) {

    /**
     * A window's save.
     *
     * @param id the group's own id
     * @param commands the commands
     */
    public CommandGroup(final String id, final List<Command> commands) {
        this(id, commands, "");
    }

    /** Refuses a blank id, an empty group, a command id named twice and a story id malformed. */
    public CommandGroup {
        if (!story.isEmpty()) {
            Ids.story(story);
        }
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
