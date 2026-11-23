package org.fuin.porelo.planner.store;

import java.net.URI;
import java.util.List;

/**
 * A project whose stories and issues a tracker keeps, as its own repository's files name it: the
 * workflow and the work rules stay files there, read by the store that knows Git and handed in.
 *
 * @param forge the forge's origin, {@code https://<host>}, taken from the own repository's address
 *     the window gave, never from the project's files
 * @param owner the owner both the own repository and the tracker's project belong to
 * @param kind the tracker's kind, as {@code project.yml} names it
 * @param project the tracker's project within the owner, as {@code project.yml} names it
 * @param repositories the project's repositories, its own first, each with its folder
 * @param workflow the project's workflow
 * @param work the project's work rules
 * @param filesCommit the own repository's commit the files were read from
 * @param storyStates derives a story's state from its issues' under the workflow
 */
public record TrackedProject(
        @org.jspecify.annotations.Nullable URI forge,
        String owner,
        String kind,
        String project,
        List<ProjectRepository> repositories,
        Workflow workflow,
        Work work,
        String filesCommit,
        StoryStates storyStates) {

    /** Derives a story's state from its issues' states, as the project's workflow says. */
    @FunctionalInterface
    public interface StoryStates {

        /**
         * Derives the state.
         *
         * @param issueStates the states of the story's issues
         * @return the story's state, or null when none follows
         */
        @org.jspecify.annotations.Nullable
        String storyState(List<String> issueStates);

        /**
         * The story's warning naming each of its issues that stands before the state derived for
         * it.
         *
         * @param storyState the state {@link #storyState} gave the story
         * @param ids the story's issues
         * @param issueStates their states, one for each of {@code ids}
         * @return the warning, or empty when none stands behind it or the rule names none
         */
        default java.util.Optional<org.fuin.porelo.planner.store.Warning> behind(
                final String storyState, final List<String> ids, final List<String> issueStates) {
            return java.util.Optional.empty();
        }

        /**
         * The board's fault when the workflow gives no story its state from its issues; a store
         * then names it among the board's faults and places no story by a guess.
         *
         * @return the fault, or empty when the workflow gives the rule
         */
        default java.util.Optional<String> fault() {
            return java.util.Optional.empty();
        }
    }

    /** Checks the origin and copies the list. */
    public TrackedProject {
        if (kind.equals("local")) {
            if (forge != null || !owner.isEmpty() || !project.isEmpty()) {
                throw new IllegalArgumentException("A local tracker names no forge or project");
            }
        } else {
            TokenSource.checkedOrigin(java.util.Objects.requireNonNull(forge));
        }
        repositories = List.copyOf(repositories);
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("A project holds its own repository");
        }
    }
}
