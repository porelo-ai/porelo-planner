package org.fuin.porelo.planner.core;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where a run works, as Porelo's {@code Start} takes its {@code place} and its task records give it
 * back: exactly one of a push waiting at the gate, a pull request at the forge or the task's own
 * pull request.
 *
 * @param gatePush the push at the gate, or null
 * @param pullRequest the branch a pull request at the forge comes from, or null
 * @param ownPullRequest true for the task's own pull request, null otherwise
 */
public record RunPlace(
        @Nullable GatePush gatePush,
        @Nullable String pullRequest,
        @Nullable Boolean ownPullRequest) {

    /** A task's name as Start takes it, Porelo's task label. */
    static final Pattern TASK = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    /** A repository's name. */
    private static final Pattern REPOSITORY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    /**
     * A Git branch: 1 to 255 code points, no "..", no control character, space or any of ~^:?*[\,
     * not starting with - or /, not ending with / or .lock.
     */
    static final Pattern BRANCH =
            Pattern.compile(
                    "(?![-/])(?!.*\\.\\.)(?!.*/$)(?!.*\\.lock$)"
                            + "[^\\u0000-\\u0020\\u007F-\\u009F\\u2028\\u2029~^:?*\\[\\\\]{1,255}",
                    Pattern.DOTALL);

    /**
     * Refuses a place with none or several members, a blank branch and a false own pull request.
     */
    public RunPlace {
        final int set =
                (gatePush == null ? 0 : 1)
                        + (pullRequest == null ? 0 : 1)
                        + (ownPullRequest == null ? 0 : 1);
        if (set != 1) {
            throw new IllegalArgumentException(
                    "A run's place names " + set + " places, never exactly one");
        }
        if (pullRequest != null && !BRANCH.matcher(pullRequest).matches()) {
            throw new IllegalArgumentException("A pull request's branch is no branch name");
        }
        if (Boolean.FALSE.equals(ownPullRequest)) {
            throw new IllegalArgumentException("A task's own pull request is given only as true");
        }
    }

    /**
     * Answers the push at the gate of a task.
     *
     * @param repository the project's repository, or null for the task's own
     * @param name the task's name within the project, as Start took it
     * @return the place
     */
    public static RunPlace gate(final @Nullable String repository, final String name) {
        return new RunPlace(new GatePush(repository, name), null, null);
    }

    /**
     * A push waiting at Porelo's gate.
     *
     * @param repository the project's repository, or null for the task's own
     * @param name the task's name within the project, which is the push's name
     */
    public record GatePush(@Nullable String repository, String name) {

        /** Refuses a blank or unsound name or repository. */
        public GatePush {
            if (!TASK.matcher(name).matches()) {
                throw new IllegalArgumentException("A gate push names no task");
            }
            if (repository != null && !REPOSITORY.matcher(repository).matches()) {
                throw new IllegalArgumentException("A gate push names no repository");
            }
        }
    }
}
