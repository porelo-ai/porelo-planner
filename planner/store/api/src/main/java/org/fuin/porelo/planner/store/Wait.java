package org.fuin.porelo.planner.store;

import java.net.URI;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An issue an issue of the board waits on that the board does not hold, such as one of a repository
 * outside the project; the board's own blockers are named in {@link Issue#blockedBy()}.
 *
 * @param reference the issue as {@code owner/repository#number}, or null when the tracker names a
 *     blocker it does not let the person read
 * @param done whether the issue waited on is done, or null when it could not be read
 * @param link where a person reads it on the forge, or null when not known
 */
public record Wait(@Nullable String reference, @Nullable Boolean done, @Nullable URI link) {

    /** An owner's and a repository's name as a forge allows them in an address. */
    private static final Pattern REFERENCE =
            Pattern.compile("[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}#[1-9][0-9]{0,9}");

    /** Checks the reference's shape and that an unnamed wait is never taken as done. */
    public Wait {
        if (reference != null && !REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("A wait names no issue as owner/repository#number");
        }
        if (reference == null && (done != null || link != null)) {
            throw new IllegalArgumentException("A wait on an issue not named is never known done");
        }
    }

    /**
     * A wait on an issue the tracker names but does not let the person read.
     *
     * @return the wait
     */
    public static Wait unreadable() {
        return new Wait(null, null, null);
    }

    /**
     * Whether it holds the issue back: it is not known to be done.
     *
     * @return true unless the issue waited on is done
     */
    public boolean holds() {
        return !Boolean.TRUE.equals(done);
    }

    /**
     * The wait in a person's words, such as {@code owner/repository#7} or {@code something not
     * readable}.
     *
     * @return the words
     */
    public String spoken() {
        return reference == null ? "something not readable" : reference;
    }
}
