package org.fuin.porelo.planner.app;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The nonces of the signed requests let through, each kept only as long as its time is within the
 * window a signature is taken in, so a caught request cannot be sent again while it would pass.
 */
final class SeenNonces {

    private final long window;

    /** Each nonce's text and the second after which its signature's time is refused anyway. */
    private final Map<String, Long> seen = new HashMap<>();

    /**
     * Makes an empty set.
     *
     * @param window The seconds a signature's time may be off the planner's clock.
     */
    SeenNonces(final long window) {
        this.window = window;
    }

    /**
     * Takes a nonce once, dropping first every nonce whose signature would be refused for its time.
     *
     * @param nonce The nonce's canonical text.
     * @param time The signature's time in seconds.
     * @param now The planner's clock in seconds.
     * @return {@code true} the first time, {@code false} for a nonce already taken.
     */
    synchronized boolean first(final String nonce, final long time, final long now) {
        for (final Iterator<Long> until = seen.values().iterator(); until.hasNext(); ) {
            if (until.next() < now) {
                until.remove();
            }
        }
        return seen.putIfAbsent(nonce, time + window) == null;
    }

    /**
     * How many nonces are kept now.
     *
     * @return Their count.
     */
    synchronized int size() {
        return seen.size();
    }
}
