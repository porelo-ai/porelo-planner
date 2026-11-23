package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * The state an open story's issues give it, the one rule every store and the stories index derive
 * it by, read from the workflow's order and its measured states alone:
 *
 * <ul>
 *   <li>F, the first measured open state, where building starts (Implementing);
 *   <li>B, the open state just before F (Backlog);
 *   <li>R, the review state: the first measured open state from F on that is directly followed by
 *       an open state that is not measured (Review, before Rework);
 *   <li>W, every measured open state from F on but R and the last open state, where a story is
 *       being built (Implementing, Reading, Reworking, before or after Review alike);
 *   <li>R or later: every open state from R on that is not in W (Review, Rework, Finishing), and
 *       the closed states.
 * </ul>
 *
 * <p>Issues in a rejecting state, or in a state the workflow does not have, are left out. With
 * every issue left in R or later, the story waits in R. Otherwise, with an issue left in W, it
 * stands in the furthest W state among them. Otherwise each issue in R or later counts as B, and
 * the story stands in the furthest state so counted. A blocked issue counts by its state.
 */
public final class StoryState {

    /** The board's fault when the workflow gives no story its state from its issues. */
    public static final String NO_RULE =
            "The workflow gives no story its state from its issues: it needs a measured open state"
                    + " with an open state before it, and a measured open state directly followed"
                    + " by an open state that is not measured.";

    private final List<String> states;
    private final Set<String> closed;
    private final Set<String> rejecting;
    private final int backlog;
    private final int review;
    private final Set<Integer> built;

    private StoryState(
            final Workflow workflow,
            final Collection<String> rejecting,
            final int backlog,
            final int review,
            final Set<Integer> built) {
        this.states = workflow.states();
        this.closed = Set.copyOf(workflow.closed());
        this.rejecting = Set.copyOf(rejecting);
        this.backlog = backlog;
        this.review = review;
        this.built = Set.copyOf(built);
    }

    /**
     * The rule for a workflow, or none when F, B or R cannot be found in it; the board then names
     * {@link #NO_RULE} as its fault and derives no state, never a guess.
     *
     * @param workflow the workflow, its order and its measured states
     * @param rejecting its closed states that leave an issue unplanned
     * @return the rule, or empty
     */
    public static Optional<StoryState> of(
            final Workflow workflow, final Collection<String> rejecting) {
        final List<String> states = workflow.states();
        final List<String> closed = workflow.closed();
        final List<String> measured = workflow.measured();
        int first = -1;
        for (int at = 0; at < states.size() && first < 0; at++) {
            if (!closed.contains(states.get(at)) && measured.contains(states.get(at))) first = at;
        }
        if (first <= 0 || closed.contains(states.get(first - 1))) return Optional.empty();
        int review = -1;
        int lastOpen = -1;
        for (int at = first; at < states.size(); at++) {
            final boolean open = !closed.contains(states.get(at));
            if (open) lastOpen = at;
            if (review < 0
                    && open
                    && measured.contains(states.get(at))
                    && at + 1 < states.size()
                    && !closed.contains(states.get(at + 1))
                    && !measured.contains(states.get(at + 1))) {
                review = at;
            }
        }
        if (review < 0) return Optional.empty();
        final Set<Integer> built = new HashSet<>();
        for (int at = first; at < states.size(); at++) {
            if (!closed.contains(states.get(at))
                    && measured.contains(states.get(at))
                    && at != review
                    && at != lastOpen) {
                built.add(at);
            }
        }
        return Optional.of(new StoryState(workflow, rejecting, first - 1, review, built));
    }

    /**
     * @return R, the state a story waits in for the person's review
     */
    public String review() {
        return states.get(review);
    }

    /**
     * Derives an open story's state.
     *
     * @param issueStates the states of the story's issues
     * @return the story's state, or null when no issue is left, which the store handles as a story
     *     without issues
     */
    public @Nullable String state(final List<String> issueStates) {
        final List<Integer> left = left(issueStates);
        if (left.isEmpty()) return null;
        if (left.stream().allMatch(this::reviewOrLater)) return states.get(review);
        final List<Integer> building = left.stream().filter(this::building).toList();
        if (!building.isEmpty()) return states.get(furthestBuilt(building));
        return states.get(
                left.stream().mapToInt(at -> reviewOrLater(at) ? backlog : at).max().orElseThrow());
    }

    /**
     * The story's warning naming each issue left that stands before the state the rule gave it; an
     * issue in R or later, counted as B, stands behind no story.
     *
     * @param storyState the state {@link #state} gave the story
     * @param ids the story's issues
     * @param issueStates their states, one for each of {@code ids}
     * @return the warning, or empty when no issue stands behind the story
     */
    public Optional<Warning> behind(
            final String storyState, final List<String> ids, final List<String> issueStates) {
        final int story = states.indexOf(storyState);
        final List<String> behind = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            final int at = place(issueStates.get(i));
            if (at >= 0 && !reviewOrLater(at) && at < story && !behind.contains(ids.get(i)))
                behind.add(ids.get(i));
        }
        return behind.isEmpty()
                ? Optional.empty()
                : Optional.of(Warning.issueBehindStory(behind, storyState));
    }

    /**
     * The W state a story being built stands in: the furthest among its issues in W, so one issue
     * in Reworking makes the story Reworking, however many are still Implementing.
     */
    private static int furthestBuilt(final List<Integer> building) {
        return building.stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    private List<Integer> left(final List<String> issueStates) {
        return issueStates.stream().map(this::place).filter(at -> at >= 0).toList();
    }

    /** The issue state's place in the workflow, or -1 when it is unknown or rejecting. */
    private int place(final String state) {
        return rejecting.contains(state) ? -1 : states.indexOf(state);
    }

    private boolean reviewOrLater(final int at) {
        return closed.contains(states.get(at)) || (at >= review && !built.contains(at));
    }

    private boolean building(final int at) {
        return built.contains(at);
    }
}
