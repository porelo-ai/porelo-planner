package org.fuin.porelo.planner.store;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * How far a whole read of a project's board has come, so a window waiting for the board sees the
 * planner at work.
 *
 * @param since when the read began
 * @param read the items read so far
 * @param total the items the last read held, or null when none was read before
 * @param lastRead the tracker reference of the last item read, or null before the first
 * @param from the place read, named for a person, such as {@code GitHub}
 */
public record ReadProgress(
        Instant since, int read, @Nullable Integer total, @Nullable String lastRead, String from) {}
