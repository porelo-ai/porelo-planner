package org.fuin.porelo.planner.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where a run works, as Porelo's {@code Start} takes its {@code place} and its task records give it
 * back: exactly one of a push waiting at the gate, a pull request at the forge or the task's own
 * pull request.
 *
 * @param gatePush the push at the gate, or null
 * @param pullRequest a pull request at the forge by its https address, or null
 * @param ownPullRequest true for the task's own pull request, null otherwise
 */
public record RunPlace(
        @Nullable GatePush gatePush,
        @Nullable String pullRequest,
        @Nullable Boolean ownPullRequest) {

    /** The longest pull request address Porelo takes. */
    public static final int MOST_ADDRESS = 2048;

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
        if (pullRequest != null && !address(pullRequest)) {
            throw new IllegalArgumentException("A pull request's place is no https address");
        }
        if (Boolean.FALSE.equals(ownPullRequest)) {
            throw new IllegalArgumentException("A task's own pull request is given only as true");
        }
    }

    /**
     * Whether a pull request's address is one Porelo takes: https, a host, no user info, query or
     * fragment, ASCII only and at most {@link #MOST_ADDRESS} characters; which forge the host names
     * is Porelo's to judge at Start.
     */
    private static boolean address(final String address) {
        if (address.isEmpty()
                || address.length() > MOST_ADDRESS
                || !address.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            return false;
        }
        final URI uri;
        try {
            uri = new URI(address);
        } catch (final URISyntaxException notAnAddress) {
            return false;
        }
        final String host = uri.getHost();
        return "https".equals(uri.getScheme())
                && host != null
                && !host.isEmpty()
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
    }

    /**
     * The task's own pull request, the one its gate's forwarding opened.
     *
     * @return the place
     */
    public static RunPlace own() {
        return new RunPlace(null, null, true);
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
