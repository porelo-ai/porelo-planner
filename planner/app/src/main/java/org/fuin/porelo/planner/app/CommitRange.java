package org.fuin.porelo.planner.app;

import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Decides from a workflow's event which commits {@code check-commits} reads, and whether they come
 * from outside.
 *
 * <p>A push reads from the commit it started from to the one that ran: a tag reads its own commit
 * alone; a start the checkout does not hold is refused on {@code main}, which only moves forward,
 * and elsewhere reads from {@code origin/main}. A pull request reads from its base to its head, and
 * comes from outside unless its head lives in this repository and it is opened by an owner or
 * member, by GitHub's Actions bot, or on a server other than github.com, where only write access
 * pushes a branch of the repository. Nothing a commit's message says earns an exemption here.
 *
 * @param range the range for {@code git log}
 * @param outside whether the commits come from outside
 */
record CommitRange(String range, boolean outside) {

    /** The authors' associations that make a pull request of this repository one from inside. */
    static final Set<String> INSIDE = Set.of("OWNER", "MEMBER");

    /** The bot whose pull requests the repository's own workflow opens. */
    static final String ACTIONS_BOT = "github-actions[bot]";

    /** GitHub's own server; on any other a pull request of the repository is from inside. */
    static final String GITHUB = "https://github.com";

    /** A full commit hash, SHA-1 or SHA-256: never an option, never a revision expression. */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /**
     * Decides the range and the origin.
     *
     * @param event the event
     * @param holds whether the checkout holds a commit, asked of a push's start only
     * @return the range and whether it comes from outside
     * @throws IllegalArgumentException with the reason, as the workflow's error says it
     */
    static CommitRange decide(final GitHubEvent event, final Predicate<String> holds) {
        final String sha = hash(event.sha(), "the commit that ran");
        return switch (event.name()) {
            case "push" -> push(event, sha, holds);
            case "pull_request" -> pull(event);
            default ->
                    throw new IllegalArgumentException(
                            "a "
                                    + event.name()
                                    + " event has no commits of its own; check-commits --event"
                                    + " takes a push or a pull request");
        };
    }

    /**
     * A push's range: a tag's own commit, else from its start, or from {@code origin/main} off main
     * when the checkout lacks the start.
     *
     * @param event the push
     * @param sha the commit that ran, a full hash
     * @param holds whether the checkout holds a commit
     * @return the range, from inside
     */
    private static CommitRange push(
            final GitHubEvent event, final String sha, final Predicate<String> holds) {
        final String before = event.before();
        if (before == null || before.isEmpty()) {
            throw new IllegalArgumentException("the push names no commit it started from");
        }
        if ("tag".equals(event.refType())) {
            return new CommitRange(sha + "~1.." + sha, false);
        }
        final String start = hash(before, "the push's start");
        if (holds.test(start)) {
            return new CommitRange(start + ".." + sha, false);
        }
        if ("refs/heads/main".equals(event.ref())) {
            throw new IllegalArgumentException(
                    "main moved from " + start + ", a commit this checkout does not hold");
        }
        return new CommitRange("origin/main.." + sha, false);
    }

    /**
     * A pull request's range from its base to its head, and whether it comes from outside.
     *
     * @param event the pull request
     * @return the range and its origin
     */
    private static CommitRange pull(final GitHubEvent event) {
        final GitHubEvent.PullRequest pull = event.pullRequest();
        if (pull == null) {
            throw new IllegalArgumentException("the pull request names no fields");
        }
        final String base = hash(pull.base(), "the pull request's base");
        final String head = hash(pull.head(), "the pull request's head");
        final boolean own = pull.headRepository().equals(event.repository());
        final boolean inside =
                own
                        && (INSIDE.contains(pull.association())
                                || ACTIONS_BOT.equals(pull.author())
                                || !GITHUB.equals(event.server()));
        return new CommitRange(base + ".." + head, !inside);
    }

    /**
     * A field that must be a full commit hash.
     *
     * @param value the field's value
     * @param what the field, for the refusal
     * @return the value
     * @throws IllegalArgumentException when it is no full hash
     */
    private static String hash(final String value, final String what) {
        if (!HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " is no full commit hash: " + value);
        }
        return value;
    }
}
