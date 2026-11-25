package org.fuin.porelo.planner.store.github;

import org.fuin.porelo.planner.store.Writes;

/**
 * The comment that keeps why a readiness check found an issue not ready, with the issue it moved
 * back: a marker of its own naming the state it moved back to and a short hash of the text on its
 * first line, so the same reason is written once only and a new one after a later check again, then
 * the state and the reason as a code span, so no text from outside mentions a person, references an
 * issue or shows a link, an image or HTML.
 */
final class ReadinessComment {

    private static final String START = "<!-- porelo:readiness ";

    private ReadinessComment() {}

    /**
     * Answers the comment for a reason, checking its text again where it leaves the planner.
     *
     * @param reason the reason and the state the issue moved back to
     * @return the comment's text
     * @throws IllegalArgumentException for a text that is no reason
     */
    static String write(final Writes.Reason reason) {
        final String refused = Writes.Reason.refusal(reason.text());
        if (refused != null) {
            throw new IllegalArgumentException(refused);
        }
        return marker(reason)
                + "\n"
                + reason.state()
                + ": "
                + ClosingComment.codeSpan(reason.text());
    }

    /**
     * Answers whether a comment is the one that keeps this reason.
     *
     * @param body the comment's text
     * @param reason the reason and the state the issue moved back to
     * @return whether its first line is that reason's marker
     */
    static boolean keeps(final String body, final Writes.Reason reason) {
        final String marker = marker(reason);
        return body.equals(marker)
                || body.startsWith(marker + "\n")
                || body.startsWith(marker + "\r\n");
    }

    private static String marker(final Writes.Reason reason) {
        return START + reason.state() + " " + ClosingComment.hash(reason.text()) + " -->";
    }
}
