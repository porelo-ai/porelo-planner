package org.fuin.porelo.planner.core;

import java.util.LinkedHashSet;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;

/** The one definition of the items each write checks against the caller's versions. */
public final class Targets {

    private Targets() {}

    /**
     * Determines the targets using only the held board and the operation's computed effects.
     * Dependencies read to check rules never become targets merely because a rule reads them.
     *
     * @param board the held board
     * @param operation the write and its caller-computed effects
     * @return the immutable set of target ids
     */
    public static Set<String> of(final Board board, final Operation operation) {
        return switch (operation) {
            case Operation.MoveBatch batch -> {
                final Set<String> ids = new LinkedHashSet<>();
                batch.moves().forEach(move -> ids.add(move.id()));
                ids.addAll(batch.carried());
                ids.addAll(batch.commented());
                yield Set.copyOf(ids);
            }
            case Operation.CreateIssue create -> {
                final String story = create.story();
                yield story == null ? Set.of() : Set.of(story);
            }
            case Operation.SetWorker edit -> Set.of(edit.issue());
            case Operation.SetFields edit -> Set.of(edit.issue());
            case Operation.SetBlockedBy edit -> Set.of(edit.issue());
            case Operation.SetIssueText edit -> Set.of(edit.issue());
            case Operation.SetStoryPriority edit -> Set.of(edit.story());
            case Operation.SetStoryText edit -> Set.of(edit.story());
            case Operation.SetStoryIssues edit -> membership(board, edit);
            case Operation.StoryFromIdea ignored -> Set.of();
            case Operation.CreateStoryFromIssues create -> fromIssues(board, create.asked());
            case Operation.AddUnlistedIssue add -> Set.of(add.issue());
            case Operation.OrderStoryWork order -> {
                final Set<String> ids = new LinkedHashSet<>(order.issues());
                ids.add(order.story());
                yield Set.copyOf(ids);
            }
            case Operation.ReadingChoice choice -> {
                final Set<String> ids = new LinkedHashSet<>(choice.reconciled());
                ids.add(choice.issue());
                yield Set.copyOf(ids);
            }
            case Operation.Correct correction -> Set.of(correction.asked().id());
        };
    }

    /**
     * The story, every issue it gains or loses and every other story an issue it gains leaves,
     * since that story's list changes too. For a story the board does not hold as one item, the
     * story and every issue the edit names: {@link Edits#unchanged} then reports missing versions
     * first and refuses the story as changed after.
     */
    private static Set<String> membership(final Board board, final Operation.SetStoryIssues edit) {
        final Story story = board.story(edit.story()).orElse(null);
        if (story == null) {
            final Set<String> ids = new LinkedHashSet<>();
            ids.add(edit.story());
            ids.addAll(edit.issues());
            return Set.copyOf(ids);
        }
        final Set<String> before = Set.copyOf(story.issues());
        final Set<String> after = Set.copyOf(edit.issues());
        final Set<String> ids = new LinkedHashSet<>();
        ids.add(edit.story());
        before.stream().filter(id -> !after.contains(id)).forEach(ids::add);
        after.stream().filter(id -> !before.contains(id)).forEach(ids::add);
        for (final Story other : board.stories()) {
            if (!other.id().equals(edit.story())
                    && other.issues().stream()
                            .anyMatch(id -> after.contains(id) && !before.contains(id))) {
                ids.add(other.id());
            }
        }
        return Set.copyOf(ids);
    }

    private static Set<String> fromIssues(final Board board, final StoryFromIssues asked) {
        final Set<String> ids = new LinkedHashSet<>(asked.existing());
        for (final Story story : board.stories()) {
            if (NewIdeas.madeBefore(story, asked.title(), asked.marker(), asked.existing())) {
                ids.add(story.id());
            }
        }
        return Set.copyOf(ids);
    }
}
