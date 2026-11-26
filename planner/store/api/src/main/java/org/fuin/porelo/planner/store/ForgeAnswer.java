package org.fuin.porelo.planner.store;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A forge's own answer to a refused call, as the person may read it: its status, its words as the
 * forge wrote them with every secret taken out and every control character escaped, at most {@value
 * #MOST_BYTES} bytes, and the id the forge gave the request.
 *
 * @param status the HTTP status, 0 for none
 * @param text the forge's words, cleaned; empty when it gave none
 * @param requestId the forge's id of the request, or null when it gave none of a request id's form
 */
public record ForgeAnswer(int status, String text, @Nullable String requestId) {

    /** The most bytes of the text, in UTF-8. */
    public static final int MOST_BYTES = 4096;

    /** What stands where a secret stood. */
    public static final String MARK = "[redacted]";

    /** What ends a text cut at the bound. */
    private static final String CUT = "…";

    /** A request id as forges write one: GitHub's is hex fields joined by colons. */
    public static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:._-]{0,127}");

    /**
     * A GitHub token by its own form, taken out even when the token the call carried is unknown,
     * and wherever it stands: glued to a word or an underscore before it, too.
     */
    private static final Pattern TOKEN =
            Pattern.compile("(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,})");

    /** Shorter secrets are not searched for, since they would mark ordinary words. */
    private static final int SHORTEST = 8;

    /** Checks the answer as one {@link #of} gives. */
    public ForgeAnswer {
        if (status < 0 || status > 999) {
            throw new IllegalArgumentException("A status is 0 to 999.");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MOST_BYTES
                || text.codePoints().anyMatch(ForgeAnswer::control)) {
            throw new IllegalArgumentException("The text is not bounded and escaped.");
        }
        if (requestId != null && !REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("The request id is not of its form.");
        }
    }

    /**
     * The answer from a forge's words.
     *
     * @param status the HTTP status, 0 for none
     * @param message the forge's message, or null for none
     * @param details its further words, each after the message
     * @param requestId the request's id, or null for none; one not of a request id's form is left
     *     out
     * @param secrets the secrets that may stand in the words, such as the token the call carried
     * @return the answer
     */
    public static ForgeAnswer of(
            final int status,
            final @Nullable String message,
            final List<String> details,
            final @Nullable String requestId,
            final Collection<String> secrets) {
        final StringBuilder words = new StringBuilder(message == null ? "" : message);
        for (final String detail : details) {
            if (!detail.isBlank()) {
                words.append(words.isEmpty() ? "" : "; ").append(detail);
            }
        }
        String text = words.toString();
        for (final String secret : secrets) {
            if (secret.length() >= SHORTEST) {
                text = text.replace(secret, MARK);
            }
        }
        text = TOKEN.matcher(text).replaceAll(MARK);
        return new ForgeAnswer(
                status < 0 || status > 999 ? 0 : status,
                bounded(escaped(text)),
                requestId != null && REQUEST_ID.matcher(requestId).matches() ? requestId : null);
    }

    /**
     * Whether a character could turn or hide what the person reads, or break the text's writing: a
     * control or format character (such as a bidi override or a zero-width one), a line or
     * paragraph separator, or a lone surrogate.
     */
    private static boolean control(final int c) {
        final int type = Character.getType(c);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE;
    }

    /**
     * Each such character as {@code \n}, {@code \t}, {@code \r} or {@code \\uXXXX}, one per UTF-16
     * unit.
     */
    private static String escaped(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        text.codePoints()
                .forEach(
                        c -> {
                            if (!control(c)) {
                                out.appendCodePoint(c);
                            } else if (c == '\n') {
                                out.append("\\n");
                            } else if (c == '\t') {
                                out.append("\\t");
                            } else if (c == '\r') {
                                out.append("\\r");
                            } else {
                                for (final char unit : Character.toChars(c)) {
                                    out.append(String.format("\\u%04x", (int) unit));
                                }
                            }
                        });
        return out.toString();
    }

    /** The text cut at a whole character so that it and its mark fit the bound. */
    private static String bounded(final String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= MOST_BYTES) {
            return text;
        }
        final int room = MOST_BYTES - CUT.getBytes(StandardCharsets.UTF_8).length;
        final StringBuilder kept = new StringBuilder();
        int bytes = 0;
        for (final int c : text.codePoints().toArray()) {
            final int size =
                    new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > room) {
                break;
            }
            kept.appendCodePoint(c);
            bytes += size;
        }
        return kept.append(CUT).toString();
    }
}
