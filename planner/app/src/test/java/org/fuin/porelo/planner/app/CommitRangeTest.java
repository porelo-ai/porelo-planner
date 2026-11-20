package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which commits a workflow's event names for the commit check, and whether they come from outside.
 */
class CommitRangeTest {

    private static final String BEFORE = "a".repeat(40);

    private static final String SHA = "b".repeat(40);

    private static final String BASE = "c".repeat(40);

    private static final String HEAD = "d".repeat(40);

    private static final String REPOSITORY = "example/sample";

    private static final Predicate<String> HELD = hash -> true;

    private static final Predicate<String> NOT_HELD = hash -> false;

    private static GitHubEvent push(final String ref, final String refType, final String before) {
        return new GitHubEvent(
                "push", ref, refType, SHA, REPOSITORY, "https://github.com", before, null);
    }

    private static GitHubEvent pull(
            final String headRepository,
            final String association,
            final String author,
            final String server) {
        return new GitHubEvent(
                "pull_request",
                "refs/pull/1/merge",
                "branch",
                SHA,
                REPOSITORY,
                server,
                null,
                new GitHubEvent.PullRequest(BASE, HEAD, headRepository, association, author));
    }

    // Prevents a pull request from outside from being taken as from inside, so a commit without its
    // author's sign-off passes; and one from inside from being held to the sign-off.
    @ParameterizedTest(name = "{0} by {1} as {2} on {3}: from outside {4}")
    @CsvSource({
        "example/sample, OWNER,       jane,                https://github.com,    false",
        "example/sample, MEMBER,      jane,                https://github.com,    false",
        "example/sample, CONTRIBUTOR, github-actions[bot], https://github.com,    false",
        "example/sample, '',          jane,                https://forge.example, false",
        "example/sample, CONTRIBUTOR, jane,                https://github.com,    true",
        "example/sample, NONE,        jane,                https://github.com,    true",
        "example/sample, '',          jane,                https://github.com,    true",
        "someone/sample, OWNER,       jane,                https://github.com,    true",
        "someone/sample, MEMBER,      github-actions[bot], https://forge.example, true",
        "someone/sample, '',          jane,                https://forge.example, true"
    })
    void decidesWhetherAPullRequestComesFromOutside(
            final String headRepository,
            final String association,
            final String author,
            final String server,
            final boolean outside) {
        assertThat(CommitRange.decide(pull(headRepository, association, author, server), HELD))
                .as("the range from its base to its head, and its origin")
                .isEqualTo(new CommitRange(BASE + ".." + HEAD, outside));
    }

    // Prevents a push from reading more or less than it brought.
    @Test
    void readsAPushFromItsStart() {
        assertThat(CommitRange.decide(push("refs/heads/main", "branch", BEFORE), HELD))
                .as("from the start to the commit that ran, from inside")
                .isEqualTo(new CommitRange(BEFORE + ".." + SHA, false));
    }

    // Prevents a tag's push from reading every commit since the branch it was cut from.
    @Test
    void readsATagsOwnCommitAlone() {
        assertThat(CommitRange.decide(push("refs/tags/v1", "tag", "0".repeat(40)), NOT_HELD))
                .as("the tag's own commit alone")
                .isEqualTo(new CommitRange(SHA + "~1.." + SHA, false));
    }

    // Prevents a forced push of a branch from having no range at all.
    @Test
    void readsABranchWhoseStartTheCheckoutLacksFromMain() {
        assertThat(CommitRange.decide(push("refs/heads/task", "branch", BEFORE), NOT_HELD))
                .as("from origin/main to the commit that ran")
                .isEqualTo(new CommitRange("origin/main.." + SHA, false));
    }

    // Prevents main's history from being rewritten past the check unseen.
    @Test
    void refusesMainMovedFromACommitTheCheckoutLacks() {
        assertThatThrownBy(
                        () ->
                                CommitRange.decide(
                                        push("refs/heads/main", "branch", BEFORE), NOT_HELD))
                .as("main only moves forward")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("main moved from " + BEFORE + ", a commit this checkout does not hold");
    }

    // Prevents a push that names no start from being read as an empty range.
    @Test
    void refusesAPushWithoutItsStart() {
        assertThatThrownBy(() -> CommitRange.decide(push("refs/heads/main", "branch", ""), HELD))
                .as("no start, no range")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the push names no commit it started from");
    }

    // Prevents a field of the event from reaching git as an option or a revision expression.
    @Test
    void refusesAStartThatIsNoFullHash() {
        assertThatThrownBy(
                        () ->
                                CommitRange.decide(
                                        push("refs/heads/task", "branch", "--output=x"), HELD))
                .as("an option is no start")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the push's start is no full commit hash: --output=x");
    }

    // Prevents a scheduled run from checking a range nobody named.
    @Test
    void refusesAnEventThatIsNeitherAPushNorAPullRequest() {
        assertThatThrownBy(
                        () ->
                                CommitRange.decide(
                                        new GitHubEvent(
                                                "schedule",
                                                "refs/heads/main",
                                                "branch",
                                                SHA,
                                                REPOSITORY,
                                                "https://github.com",
                                                null,
                                                null),
                                        HELD))
                .as("a schedule names no commits")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("a schedule event has no commits of its own");
    }
}
