package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Story;
import org.jspecify.annotations.Nullable;

/**
 * The issues of a story that do not fit the state given to it: one rule for the board's answer and
 * for the refusal of a move, so the two never disagree.
 *
 * <p>Only the issues the board holds are read; one it does not hold is neither derived over nor a
 * misfit.
 */
public final class Misfits {

    private Misfits() {}

    /**
     * Answers the issues of a story that do not fit its state, as the board shows it.
     *
     * @param board the board holding the story's issues
     * @param story the story
     * @param derive derives a story's state from its issues' states
     * @return the misfit issues' ids in the story's order; empty when its state is not given; null
     *     when the rule cannot answer for it, so one story never makes a board unreadable
     */
    public static @Nullable List<String> of(
            final Board board, final Story story, final Moves.StoryState derive) {
        if (!story.stateGiven()) {
            return List.of();
        }
        final Map<String, String> states = new HashMap<>();
        for (final String id : story.issues()) {
            board.issue(id).map(Issue::state).ifPresent(state -> states.put(id, state));
        }
        try {
            return of(story.issues(), story.state(), states, derive);
        } catch (final RuntimeException e) {
            // A store without a read board, or a state its rule does not know: unknown, not a
            // failure.
            return null;
        }
    }

    /**
     * Answers the issues that do not fit a given state: none when the issues give that state
     * themselves, else each whose own state is another.
     *
     * @param issues the story's issues, in its order
     * @param given the state given to the story
     * @param states the states of the issues the board holds, by id
     * @param derive derives a story's state from its issues' states
     * @return the misfit issues' ids in the story's order
     */
    public static List<String> of(
            final List<String> issues,
            final String given,
            final Map<String, String> states,
            final Moves.StoryState derive) {
        final List<String> known = known(issues, states);
        if (known.isEmpty() || given.equals(derived(issues, states, derive))) {
            return List.of();
        }
        return known.stream().filter(id -> !given.equals(states.get(id))).toList();
    }

    /**
     * Derives a story's state from the states of those of its issues the board holds.
     *
     * @param issues the story's issues, in its order
     * @param states the states of the issues the board holds, by id
     * @param derive derives a story's state from its issues' states
     * @return the derived state, or null when none follows
     */
    static @Nullable String derived(
            final List<String> issues,
            final Map<String, String> states,
            final Moves.StoryState derive) {
        final List<String> known = new ArrayList<>();
        for (final String id : issues) {
            final String state = states.get(id);
            if (state != null) {
                known.add(state);
            }
        }
        return derive.of(known);
    }

    /**
     * Answers the state a story's issues give it, for a story whose state was given: null when its
     * state is not given, when the board does not hold every issue its head names, or when the
     * store's rule cannot be applied, so nothing is offered from part of its issues.
     *
     * @param board the board
     * @param story the story
     * @param derive derives a story's state from its issues' states
     * @return the state its issues give, or null
     */
    public static @Nullable String derivedState(
            final Board board, final Story story, final Moves.StoryState derive) {
        if (!story.stateGiven()) {
            return null;
        }
        final Map<String, String> states = new HashMap<>();
        for (final String id : story.issues()) {
            board.issue(id).map(Issue::state).ifPresent(state -> states.put(id, state));
        }
        return derivedOf(story, states, derive);
    }

    /** The state the story's issues give, or null unless every issue it names is held. */
    static @Nullable String derivedOf(
            final Story story, final Map<String, String> states, final Moves.StoryState derive) {
        if (!story.issuesWhole()
                || story.issues().isEmpty()
                || known(story.issues(), states).size() != story.issues().size()) {
            return null;
        }
        try {
            return derived(story.issues(), states, derive);
        } catch (final RuntimeException e) {
            // A store without a read board, or a state its rule does not know: not known.
            return null;
        }
    }

    /**
     * Answers those of a story's issues the board holds.
     *
     * @param issues the story's issues, in its order
     * @param states the states of the issues the board holds, by id
     * @return the issues held, in the story's order
     */
    static List<String> known(final List<String> issues, final Map<String, String> states) {
        return issues.stream().filter(states::containsKey).toList();
    }
}
