package org.fuin.porelo.planner.store.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.fuin.porelo.planner.store.Writes;

/**
 * The comment that keeps a person's reason with the item a move closes: a marker naming the closed
 * state and a short hash of the text on its first line, so the same reason is written once only and
 * a new one after a reopen again, then the state and the reason as a code span, so no text from
 * outside mentions a person, references an issue or shows a link, an image or HTML.
 */
final class ClosingComment {

    private static final String START = "<!-- porelo:closing ";

    private ClosingComment() {}

    /**
     * Answers the comment for a reason, checking its text again where it leaves the planner.
     *
     * @param reason the reason and the state it closes into
     * @return the comment's text
     * @throws IllegalArgumentException for a text that is no reason
     */
    static String write(final Writes.Reason reason) {
        final String refused = Writes.Reason.refusal(reason.text());
        if (refused != null) {
            throw new IllegalArgumentException(refused);
        }
        return marker(reason) + "\n" + reason.state() + ": " + codeSpan(reason.text());
    }

    /**
     * Answers whether a comment is the one that keeps this reason.
     *
     * @param body the comment's text
     * @param reason the reason and its closed state
     * @return whether its first line is that reason's marker
     */
    static boolean keeps(final String body, final Writes.Reason reason) {
        final String marker = marker(reason);
        return body.equals(marker)
                || body.startsWith(marker + "\n")
                || body.startsWith(marker + "\r\n");
    }

    private static String marker(final Writes.Reason reason) {
        return START + reason.state() + " " + hash(reason.text()) + " -->";
    }

    /** The first twelve hexadecimal characters of the text's SHA-256. */
    private static String hash(final String text) {
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
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
