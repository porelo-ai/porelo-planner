package org.fuin.porelo.planner.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * A story the planner has checked, to be made in the project's own repository over issues that
 * stand on the board; no new issue is made, and each keeps its own fields.
 *
 * @param title the story's title, one line
 * @param gain what a user gains, one line
 * @param test the test the person runs at its end, one line
 * @param priority the story's priority, {@code Highest} to {@code Lowest}
 * @param existing the issues' ids, in the story's order, each named once
 */
public record StoryFromIssues(
        String title, String gain, String test, String priority, List<String> existing) {

    /** The most issues one story is made over. */
    public static final int MOST_ISSUES = 100;

    /** Checks the ids and copies them to keep the story immutable. */
    public StoryFromIssues {
        existing = List.copyOf(existing);
        existing.forEach(Ids::issue);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("A story from issues names one issue at least");
        }
        if (existing.size() > MOST_ISSUES) {
            throw new IllegalArgumentException(
                    "A story from issues names at most " + MOST_ISSUES + " issues");
        }
        if (Set.copyOf(existing).size() != existing.size()) {
            throw new IllegalArgumentException("A story from issues names each issue once");
        }
    }

    /**
     * Answers the mark the story's body carries, which tells the story an earlier try of this very
     * request made from any other: a person's own story, or one of another request.
     *
     * @return a hidden HTML comment
     */
    public String marker() {
        return marker(title, gain, test, existing);
    }

    /**
     * Answers the mark a story made by a request carries: a digest of its title, what a user gains,
     * its test, each stripped, and the issues it names in their order.
     *
     * @param title the story's title
     * @param gain what a user gains
     * @param test its test
     * @param existing the issues' ids, in the story's order
     * @return a hidden HTML comment, one line
     */
    public static String marker(
            final String title, final String gain, final String test, final List<String> existing) {
        final StringBuilder text = new StringBuilder();
        for (final String part : List.of(title.strip(), gain.strip(), test.strip())) {
            text.append(part.length()).append(':').append(part).append('\n');
        }
        existing.forEach(id -> text.append(id.length()).append(':').append(id).append('\n'));
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JVM has no SHA-256", e);
        }
        return "<!-- porelo-planner story from issues "
                + HexFormat.of()
                        .formatHex(digest.digest(text.toString().getBytes(StandardCharsets.UTF_8)))
                + " -->";
    }
}
