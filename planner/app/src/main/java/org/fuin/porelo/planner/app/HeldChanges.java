package org.fuin.porelo.planner.app;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.planner.app.answers.HeldChange;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TopicLabels;
import org.jspecify.annotations.Nullable;

/**
 * What a held command would change, field by field, beside the value the board held at its last
 * read: each field by the name its item carries it under on the board, a value the board does not
 * hold by its own name and without a value now.
 */
final class HeldChanges {

    /** The board's names of the fields a command sets, as the outbox names them. */
    private static final Map<String, String> FIELDS =
            Map.of(
                    "SIZE", "size",
                    "PRIORITY", "priority",
                    "ESTIMATE", "estimateMinutes",
                    "RANK", "rank");

    private final Board board;

    private final @Nullable String readAt;

    private final List<HeldChange> changes = new ArrayList<>();

    private HeldChanges(final Board board, final Optional<Instant> readAt) {
        this.board = board;
        this.readAt = readAt.map(Instant::toString).orElse(null);
    }

    /**
     * The fields a command would change, in its changes' order.
     *
     * @param told the command with its changes
     * @param board the board held now
     * @param readAt when that board was read, or empty when the store tells no time
     * @return the changes; empty for a command that changes no field of an item
     */
    static List<HeldChange> of(
            final CommandOutbox.Told told, final Board board, final Optional<Instant> readAt) {
        final HeldChanges of = new HeldChanges(board, readAt);
        of.add(told);
        return List.copyOf(of.changes);
    }

    private void add(final CommandOutbox.Told told) {
        final CommandOutbox.Changes data = told.changes();
        switch (told.kind()) {
            case "write" -> {
                data.issueStates().forEach((id, to) -> held(id, "state", to, issueState(id)));
                data.storyStates().forEach((id, to) -> held(id, "state", to, storyState(id)));
                data.workers().forEach((id, to) -> held(id, "worker", to, worker(id)));
                data.priorities().forEach((id, to) -> held(id, "priority", to, priority(id)));
                data.issueReasons().forEach((id, reason) -> unheld(id, "reason", reason.text()));
                data.storyReasons().forEach((id, reason) -> unheld(id, "reason", reason.text()));
            }
            case "fields" -> {
                final String id = told.target();
                final Issue issue = issue(id);
                data.values()
                        .forEach(
                                (name, to) -> {
                                    final String field = FIELDS.get(name);
                                    if (field == null) {
                                        return;
                                    }
                                    switch (field) {
                                        case "size" ->
                                                held(
                                                        id,
                                                        field,
                                                        to,
                                                        issue == null
                                                                ? null
                                                                : Now.of(issue.size()));
                                        case "priority" ->
                                                held(
                                                        id,
                                                        field,
                                                        to,
                                                        issue == null
                                                                ? null
                                                                : Now.of(issue.priority()));
                                        default -> unheld(id, field, to);
                                    }
                                });
            }
            case "text" -> {
                final String id = told.target();
                final String title = data.values().get("title");
                if (title != null) {
                    held(id, "title", title, title(id));
                }
                data.parts().forEach((part, to) -> unheld(id, camel(part), to));
                data.removed().forEach(part -> unheld(id, camel(part), ""));
                if (data.labelsSet()) {
                    // The write sets the topic labels alone and keeps every other label.
                    held(
                            id,
                            "labels",
                            String.join(", ", TopicLabels.topicOf(data.labels())),
                            labels(id));
                }
            }
            case "blockers" -> {
                final Issue issue = issue(told.target());
                held(
                        told.target(),
                        "blockedBy",
                        String.join(", ", data.ids()),
                        issue == null ? null : Now.of(String.join(", ", issue.blockedBy())));
            }
            case "storyIssues" -> {
                final Story story = story(told.target());
                held(
                        told.target(),
                        "issues",
                        String.join(", ", data.ids()),
                        story == null || !story.issuesWhole()
                                ? null
                                : Now.of(String.join(", ", story.issues())));
            }
            default -> {
                // A comment, a new item or a repository's labels change no field of an item.
            }
        }
    }

    /** A field the board holds: its value now, when its item was read. */
    private void held(
            final String target, final String field, final String saved, final @Nullable Now now) {
        if (now == null || readAt == null) {
            unheld(target, field, saved);
            return;
        }
        changes.add(
                new HeldChange(
                        target,
                        field,
                        now.value(),
                        now.value() == null ? Boolean.TRUE : null,
                        readAt,
                        saved.isEmpty() ? null : saved));
    }

    /** A field whose value now is not known. */
    private void unheld(final String target, final String field, final String saved) {
        changes.add(
                new HeldChange(target, field, null, null, null, saved.isEmpty() ? null : saved));
    }

    /**
     * A value the board held at its read.
     *
     * @param value the value, or null when it was read empty
     */
    private record Now(@Nullable String value) {

        static Now of(final @Nullable String value) {
            return new Now(value == null || value.isEmpty() ? null : value);
        }
    }

    private @Nullable Now issueState(final String id) {
        final Issue issue = issue(id);
        return issue == null ? null : Now.of(issue.state());
    }

    private @Nullable Now storyState(final String id) {
        final Story story = story(id);
        return story == null ? null : Now.of(story.state());
    }

    private @Nullable Now worker(final String id) {
        final Issue issue = issue(id);
        return issue == null ? null : Now.of(issue.worker().name());
    }

    private @Nullable Now priority(final String id) {
        final Story story = story(id);
        if (story != null) {
            return Now.of(story.priority());
        }
        final Issue issue = issue(id);
        return issue == null ? null : Now.of(issue.priority());
    }

    private @Nullable Now title(final String id) {
        final Story story = story(id);
        if (story != null) {
            return Now.of(story.title());
        }
        final Issue issue = issue(id);
        return issue == null ? null : Now.of(issue.title());
    }

    /** An item's topic labels: the others are kept by a write of labels, so never shown. */
    private @Nullable Now labels(final String id) {
        final Story story = story(id);
        if (story != null) {
            return Now.of(String.join(", ", TopicLabels.topicOf(story.labels())));
        }
        final Issue issue = issue(id);
        return issue == null
                ? null
                : Now.of(String.join(", ", TopicLabels.topicOf(issue.labels())));
    }

    private @Nullable Issue issue(final String id) {
        return board.issues().stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
    }

    private @Nullable Story story(final String id) {
        return board.stories().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** A text part's name as a field's: {@code WHAT_MUST_BE_TRUE} as {@code whatMustBeTrue}. */
    static String camel(final String part) {
        final StringBuilder name = new StringBuilder();
        boolean upper = false;
        for (final char c : part.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                name.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return name.toString();
    }
}
