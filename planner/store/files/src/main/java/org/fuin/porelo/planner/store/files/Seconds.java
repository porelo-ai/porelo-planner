package org.fuin.porelo.planner.store.files;

import java.math.BigDecimal;
import java.time.Duration;

/** A duration told in seconds as a person reads it, its fraction kept: "60", "0.5", "2.2". */
public final class Seconds {

    private Seconds() {}

    /**
     * Tells a duration in seconds, with as many digits after the dot as it needs and none when it
     * is whole.
     *
     * @param duration the duration
     * @return its seconds
     */
    public static String of(final Duration duration) {
        return BigDecimal.valueOf(duration.toNanos(), 9).stripTrailingZeros().toPlainString();
    }
}
