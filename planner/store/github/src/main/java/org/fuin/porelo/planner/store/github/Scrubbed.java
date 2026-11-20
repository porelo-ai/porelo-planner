package org.fuin.porelo.planner.store.github;

import java.util.Collection;

/** Takes secrets out of a text before it leaves the store as a message or a fault. */
public final class Scrubbed {

    /** What stands where a secret stood. */
    static final String MARK = "[redacted]";

    /** Shorter secrets are not searched for, since they would mark ordinary words. */
    private static final int SHORTEST = 8;

    private Scrubbed() {}

    /**
     * Replaces every occurrence of each secret.
     *
     * @param text the text
     * @param secrets the secrets that may stand in it
     * @return the text without them
     */
    public static String text(final String text, final Collection<String> secrets) {
        String clean = text;
        for (final String secret : secrets) {
            if (secret.length() >= SHORTEST) {
                clean = clean.replace(secret, MARK);
            }
        }
        return clean;
    }
}
