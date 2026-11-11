package org.fuin.porelo.planner.store.git;

import org.jspecify.annotations.Nullable;

/**
 * A value from outside - a repository, Git or the window - made fit for one line of the planner's
 * log: no control or format character that could start a line of its own, move the cursor or turn
 * the text around, and no longer than a line should be.
 */
public final class LogText {

    /** The most characters one value keeps in a line. */
    public static final int MOST = 200;

    private LogText() {}

    /**
     * Makes a value fit for one line.
     *
     * @param value the value, as its {@code toString} gives it; {@code null} reads as {@code null}
     * @return the text with each control, format, separator or lone surrogate character replaced by
     *     {@code ?}, cut to {@link #MOST} characters with {@code ...} after it
     */
    public static String of(final @Nullable Object value) {
        final String text = String.valueOf(value);
        final StringBuilder line = new StringBuilder(Math.min(text.length(), MOST + 3));
        int kept = 0;
        for (int i = 0; i < text.length(); ) {
            if (kept == MOST) {
                return line.append("...").toString();
            }
            final int point = text.codePointAt(i);
            i += Character.charCount(point);
            line.appendCodePoint(unsafe(point) ? '?' : point);
            kept++;
        }
        return line.toString();
    }

    private static boolean unsafe(final int point) {
        return switch (Character.getType(point)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR,
                    Character.SURROGATE ->
                    true;
            default -> false;
        };
    }
}
