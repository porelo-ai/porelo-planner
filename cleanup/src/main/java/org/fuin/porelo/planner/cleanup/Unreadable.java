package org.fuin.porelo.planner.cleanup;

import java.io.Serial;

/** A listing that cannot be read whole, after which the clean-up chooses nothing. */
public final class Unreadable extends Exception {

    @Serial private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param message what could not be read, and why
     */
    public Unreadable(final String message) {
        super(message);
    }

    /**
     * Constructor.
     *
     * @param message what could not be read
     * @param cause why
     */
    public Unreadable(final String message, final Throwable cause) {
        super(message + ": " + cause.getMessage(), cause);
    }
}
