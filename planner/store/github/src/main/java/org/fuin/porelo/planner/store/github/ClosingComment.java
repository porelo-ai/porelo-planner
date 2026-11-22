package org.fuin.porelo.planner.store.github;

import org.fuin.porelo.planner.store.Writes;

/**
 * The comment that keeps a person's reason with the item a move closes: a marker naming the closed
 * state on its first line, so it is written once only, then the state and the reason as a code
 * span, so no text from outside mentions a person, references an issue or shows a link, an image or
 * HTML.
 */
final class ClosingComment {

    private static final String START = "<!-- porelo:closing ";

    private ClosingComment() {}

    /**
     * Answers the comment for a reason.
     *
     * @param reason the reason and the state it closes into
     * @return the comment's text
     */
    static String write(final Writes.Reason reason) {
        return marker(reason.state()) + "\n" + reason.state() + ": " + codeSpan(reason.text());
    }

    /**
     * Answers whether a comment is the one that keeps a reason for a closed state.
     *
     * @param body the comment's text
     * @param state the closed state
     * @return whether its first line is that state's marker
     */
    static boolean keeps(final String body, final String state) {
        final String marker = marker(state);
        return body.equals(marker)
                || body.startsWith(marker + "\n")
                || body.startsWith(marker + "\r\n");
    }

    private static String marker(final String state) {
        return START + state + " -->";
    }

    /**
     * The text as one code span: fenced by one backtick more than its longest run of them, and
     * padded by a space each side, which the span drops again, so a backtick at either end stays.
     */
    static String codeSpan(final String text) {
        int longest = 0;
        int run = 0;
        for (int at = 0; at < text.length(); at++) {
            run = text.charAt(at) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        final String fence = "`".repeat(longest + 1);
        return fence + " " + text + " " + fence;
    }
}
