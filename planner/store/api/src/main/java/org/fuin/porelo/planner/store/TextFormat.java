package org.fuin.porelo.planner.store;

import java.util.regex.Pattern;

/**
 * The format a store writes its texts in, an open name: Porelo knows {@link #MARKDOWN} and {@link
 * #PLAIN}, and another store may name its own, which a reader that does not know it shows as plain
 * text.
 *
 * @param name the format's name, in the contract's shape
 */
public record TextFormat(String name) {

    /** The shape the window's contract gives a format's name. */
    public static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    /** Markdown, as the Git store writes it. */
    public static final TextFormat MARKDOWN = new TextFormat("MARKDOWN");

    /** Plain text, shown as it is. */
    public static final TextFormat PLAIN = new TextFormat("PLAIN");

    /** Refuses a name outside the contract's shape. */
    public TextFormat {
        // A refused name is never shown: it may carry a line break that forges a log line.
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Not a text format (" + NAME.pattern() + "), " + name.length() + " characters");
        }
    }
}
