package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.app.answers.Board;
import org.fuin.porelo.planner.app.answers.BoardDelta;
import org.fuin.porelo.planner.app.answers.Issue;
import org.fuin.porelo.planner.app.answers.Story;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** One connection's last sent answer and the differences taken against it. */
final class BoardChanges {

    private @Nullable Board last;

    private long frame;

    /** Starts a connection without a sent baseline. */
    BoardChanges() {}

    /**
     * Sends the whole first answer or the difference from the last answer sent successfully.
     *
     * @param emitter the connection
     * @param board the current complete answer
     * @return whether an event was sent; identical answers leave the frame unchanged
     * @throws IOException if the connection cannot receive the event
     */
    boolean send(final SseEmitter emitter, final Board board) throws IOException {
        final @Nullable Board before = last;
        if (board.equals(before)) {
            return false;
        }
        final Object data;
        final String event;
        if (before == null) {
            data = board;
            event = "board";
        } else {
            data = difference(before, board, frame);
            event = "change";
        }
        emitter.send(
                SseEmitter.event()
                        .name(event)
                        .id(board.revision())
                        .data(data, MediaType.APPLICATION_JSON));
        if (before != null) {
            frame++;
        }
        last = board;
        return true;
    }

    private static BoardDelta difference(final Board before, final Board board, final long after) {
        final Rows<Story> stories = rows(before.stories(), board.stories(), Story::id);
        final Rows<Issue> issues = rows(before.noStory(), board.noStory(), Issue::id);
        return new BoardDelta(
                after + 1,
                after,
                before.revision(),
                board.revision(),
                stories.changed(),
                stories.removed(),
                issues.changed(),
                issues.removed(),
                stories.order(),
                issues.order(),
                changed(before.states(), board.states()),
                changed(before.phases(), board.phases()),
                changed(before.work(), board.work()),
                changed(before.faults(), board.faults()),
                changed(before.reasons(), board.reasons()),
                changed(before.repositories(), board.repositories()),
                changed(before.repositoryWarnings(), board.repositoryWarnings()),
                changed(before.unlisted(), board.unlisted()),
                changed(before.open(), board.open()),
                changed(before.rules(), board.rules()),
                changed(before.heldSaves(), board.heldSaves()),
                changed(before.tracker(), board.tracker()));
    }

    /** Equality is over whole groups because warned ids can occur more than once. */
    private static <T> Rows<T> rows(
            final List<T> before, final List<T> current, final Function<T, String> id) {
        final Map<String, List<T>> old = grouped(before, id);
        final Map<String, List<T>> now = grouped(current, id);
        final Set<String> changed =
                now.keySet().stream()
                        .filter(key -> !Objects.equals(now.get(key), old.get(key)))
                        .collect(Collectors.toSet());
        return new Rows<>(
                current.stream().filter(row -> changed.contains(id.apply(row))).toList(),
                old.keySet().stream().filter(key -> !now.containsKey(key)).toList(),
                changed(before.stream().map(id).toList(), current.stream().map(id).toList()));
    }

    private static <T> Map<String, List<T>> grouped(
            final List<T> rows, final Function<T, String> id) {
        final Map<String, List<T>> groups = new LinkedHashMap<>();
        for (final T row : rows) {
            groups.computeIfAbsent(id.apply(row), key -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    private static <T> @Nullable T changed(final @Nullable T before, final @Nullable T current) {
        return Objects.equals(before, current) ? null : current;
    }

    /** A collection's replacements and removals, and its order only when changed. */
    private record Rows<T>(List<T> changed, List<String> removed, @Nullable List<String> order) {}
}
