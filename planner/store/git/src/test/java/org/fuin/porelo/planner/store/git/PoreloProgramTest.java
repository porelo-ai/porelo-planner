package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Porelo's executable for the planner's own reads, passed by the login's own check. */
class PoreloProgramTest {

    private final List<String> checked = new ArrayList<>();

    @Test
    void theVariablesPathIsAnsweredOnlyOnceTheCheckPassesIt() {
        // Harm: the planner runs a program another account could swap, unchecked, for its reads.
        final Path path =
                PoreloProgram.of(
                        Map.of(LentLogin.VARIABLE, "/opt/porelo/bin/porelo"),
                        value -> {
                            checked.add(value);
                            return value;
                        });

        assertThat(path).isEqualTo(Path.of("/opt/porelo/bin/porelo"));
        assertThat(checked).as("checked first").containsExactly("/opt/porelo/bin/porelo");
    }

    @Test
    void withoutTheVariableThereIsNone() {
        // Harm: a planner without Porelo said to read reports, or run a program it guessed.
        assertThat(
                        PoreloProgram.of(
                                Map.of("PATH", "/usr/bin"),
                                value -> {
                                    throw new AssertionError("nothing to check");
                                }))
                .isNull();
    }

    @Test
    void aRefusedExecutableFailsLoudWithTheChecksWords() {
        // Harm: a refused program run anyway, or refused in silence so no idea is ever saved.
        final Throwable refused =
                catchThrowable(
                        () ->
                                PoreloProgram.of(
                                        Map.of(LentLogin.VARIABLE, "porelo"),
                                        value -> {
                                            throw new IllegalStateException(
                                                    "the path is not absolute");
                                        }));

        assertThat(refused)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the path is not absolute");
    }
}
