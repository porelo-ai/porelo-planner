package org.fuin.porelo.planner.cleanup;

import java.time.Instant;
import java.util.Objects;

/**
 * One file of the listing, by the first folder it stands in under the prefix.
 *
 * @param folder the first folder below {@code porelo-files/porelo-planner/}, percent-decoded, which
 *     for a build is its version; anything else for a file that is no build's
 * @param time when the file was last changed
 */
public record Build(String folder, Instant time) {

    /**
     * Refuses a missing part.
     *
     * @throws NullPointerException when a part is null
     */
    public Build {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(time, "time");
    }
}
