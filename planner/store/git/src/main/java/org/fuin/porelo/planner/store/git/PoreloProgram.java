package org.fuin.porelo.planner.store.git;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/**
 * Porelo's executable, as {@code PORELO_EXECUTABLE} names it, passed by the same check the login
 * the planner lends passes it by, for the planner's own reads of Porelo.
 */
public final class PoreloProgram {

    private PoreloProgram() {}

    /**
     * The checked executable.
     *
     * @param environment the planner's environment
     * @param limits the factors
     * @return its path, or null without the variable
     * @throws IllegalStateException when the check refuses it or cannot run, its words scrubbed
     */
    public static @Nullable Path of(
            final Map<String, String> environment, final LimitFactors limits) {
        return of(environment, PoreloExecutable.of(environment, LentLogin.WINDOWS, limits));
    }

    static @Nullable Path of(final Map<String, String> environment, final PoreloExecutable check) {
        final String value = LentLogin.variable(environment);
        if (value == null) {
            return null;
        }
        try {
            return Path.of(check.checked(value));
        } catch (final IllegalStateException | IllegalArgumentException | UncheckedIOException e) {
            throw new IllegalStateException(
                    "Porelo's executable is refused: " + GitRunner.scrubbed(e.getMessage()), e);
        }
    }
}
