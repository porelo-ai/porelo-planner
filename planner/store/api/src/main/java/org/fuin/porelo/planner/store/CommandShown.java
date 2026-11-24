package org.fuin.porelo.planner.store;

import java.util.Objects;
import java.util.Set;

/**
 * Whether a board shows a command written: only the parts a board shows count, so a command with a
 * part it cannot show, such as a reason comment, a rank or labels, is never told written by it.
 */
public final class CommandShown {

    private CommandShown() {}

    /**
     * Tells whether the board can show every part the command writes.
     *
     * @param command the command
     * @return false for a reason, an estimate, a rank, text parts, labels, a comment or a create
     */
    public static boolean showable(final Command command) {
        return switch (command) {
            case Command.Write write ->
                    write.writes().issueReasons().isEmpty()
                            && write.writes().storyReasons().isEmpty();
            case Command.SetFields set ->
                    !set.fields().named().contains(Fields.Field.ESTIMATE)
                            && !set.fields().named().contains(Fields.Field.RANK);
            case Command.SetText set ->
                    set.edit().labels() == null
                            && set.edit().written().isEmpty()
                            && set.edit().removed().isEmpty();
            case Command.SetBlockers set -> true;
            case Command.SetStoryIssues set -> true;
            default -> false;
        };
    }

    /**
     * Tells whether the board shows every part the command writes, as it writes it.
     *
     * @param command the command
     * @param board the board
     * @return true only for a showable command whose every part stands on the board
     */
    public static boolean shows(final Command command, final Board board) {
        if (!showable(command)) {
            return false;
        }
        return switch (command) {
            case Command.Write write -> {
                final Writes writes = write.writes();
                yield writes.issues().entrySet().stream()
                                .allMatch(
                                        e ->
                                                board.issue(e.getKey())
                                                        .map(i -> i.state().equals(e.getValue()))
                                                        .orElse(false))
                        && writes.stories().entrySet().stream()
                                .allMatch(
                                        e ->
                                                board.story(e.getKey())
                                                        .map(s -> s.state().equals(e.getValue()))
                                                        .orElse(false))
                        && writes.workers().entrySet().stream()
                                .allMatch(
                                        e ->
                                                board.issue(e.getKey())
                                                        .map(i -> i.worker() == e.getValue())
                                                        .orElse(false))
                        && writes.priorities().entrySet().stream()
                                .allMatch(
                                        e ->
                                                board.story(e.getKey())
                                                        .map(s -> s.priority().equals(e.getValue()))
                                                        .orElse(false));
            }
            case Command.SetFields set -> {
                final Issue issue = board.issue(set.issue()).orElse(null);
                final Set<Fields.Field> named = set.fields().named();
                yield issue != null
                        && (!named.contains(Fields.Field.SIZE)
                                || Objects.equals(issue.size(), set.fields().size()))
                        && (!named.contains(Fields.Field.PRIORITY)
                                || Objects.equals(issue.priority(), set.fields().priority()));
            }
            case Command.SetText set -> {
                final String title = set.edit().title();
                final String shown =
                        set.edit().kind() == TextEdit.Kind.STORY
                                ? board.story(set.item()).map(Story::title).orElse(null)
                                : board.issue(set.item()).map(Issue::title).orElse(null);
                yield title != null && title.equals(shown);
            }
            case Command.SetBlockers set ->
                    board.issue(set.issue())
                            .map(i -> Set.copyOf(i.blockedBy()).equals(Set.copyOf(set.blockedBy())))
                            .orElse(false);
            case Command.SetStoryIssues set ->
                    board.story(set.story())
                            .map(s -> s.issues().equals(set.issues()))
                            .orElse(false);
            default -> false;
        };
    }
}
