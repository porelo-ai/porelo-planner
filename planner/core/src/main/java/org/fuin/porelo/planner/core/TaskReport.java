package org.fuin.porelo.planner.core;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * One report a task handed back to whoever started it, as Porelo numbered and kept it: a one-line
 * kind the planner's brief asks for and a text. Both are outside input, checked here as Porelo
 * keeps them and closer still, so no hidden or reordering character reaches a story or a log.
 *
 * @param seq its number in the sequence of the task's name, 1 or more
 * @param kind one line of 1 to {@value #MOST_KIND} code points
 * @param text 1 to {@value #MOST_TEXT} code points, line breaks kept
 * @param at when Porelo kept it, a UTC instant ending in Z
 */
public record TaskReport(long seq, String kind, String text, String at) {

    /** The most code points of a kind. */
    public static final int MOST_KIND = 64;

    /** The most code points of a text. */
    public static final int MOST_TEXT = 65_536;

    /** The kind of a report that holds an idea in the planner's idea format. */
    public static final String IDEA = "idea";

    /**
     * Refuses a report Porelo would not have kept, naming the field and never its value.
     *
     * @throws IllegalArgumentException naming the field at fault
     */
    public TaskReport {
        if (seq < 1) {
            throw new IllegalArgumentException("seq is below 1");
        }
        if (!soundKind(kind)) {
            throw new IllegalArgumentException(
                    "kind is no one line of 1 to " + MOST_KIND + " plain characters");
        }
        if (!soundText(text)) {
            throw new IllegalArgumentException(
                    "text is no text of 1 to "
                            + MOST_TEXT
                            + " characters without a control, a separator or a bidi control");
        }
        if (!at.endsWith("Z")) {
            throw new IllegalArgumentException("at is no UTC instant ending in Z");
        }
        try {
            Instant.parse(at);
        } catch (final DateTimeParseException e) {
            throw new IllegalArgumentException("at is no UTC instant ending in Z", e);
        }
    }

    /**
     * Whether a kind is one line: 1 to {@value #MOST_KIND} code points, whole, with no control, no
     * format character and no line or paragraph separator.
     *
     * @param kind the kind
     * @return whether it is sound
     */
    public static boolean soundKind(final String kind) {
        return !kind.isEmpty()
                && whole(kind)
                && kind.codePointCount(0, kind.length()) <= MOST_KIND
                && kind.codePoints()
                        .noneMatch(
                                c ->
                                        Character.isISOControl(c)
                                                || Character.getType(c) == Character.FORMAT
                                                || c == 0x2028
                                                || c == 0x2029);
    }

    /**
     * Whether a text is sound: 1 to {@value #MOST_TEXT} code points, whole, with no control but a
     * line break, no line or paragraph separator and no bidi embedding, override or isolate.
     *
     * @param text the text
     * @return whether it is sound
     */
    public static boolean soundText(final String text) {
        return !text.isEmpty()
                && whole(text)
                && text.codePointCount(0, text.length()) <= MOST_TEXT
                && text.codePoints()
                        .noneMatch(
                                c ->
                                        Character.isISOControl(c) && c != '\n' && c != '\r'
                                                || c == 0x2028
                                                || c == 0x2029
                                                || c >= 0x202A && c <= 0x202E
                                                || c >= 0x2066 && c <= 0x2069);
    }

    /** Whether a text holds no half of a surrogate pair standing alone. */
    private static boolean whole(final String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
