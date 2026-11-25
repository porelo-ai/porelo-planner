package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PoreloProgramCheckedTest {

    @Test
    void theVariablesProgramIsAnsweredOnlyOnceItsCheckPasses() {
        // Harm: Porelo asked through a program anyone could have swapped, or through none, so a
        // read of where work waits runs what it must not, or silently reads nothing.
        assertThat(
                        PoreloProgram.checked(
                                Map.of(LentLogin.VARIABLE, "/opt/porelo/bin/porelo"), path -> path))
                .as("a program its check passes")
                .isEqualTo(Path.of("/opt/porelo/bin/porelo"));
        assertThatThrownBy(() -> PoreloProgram.checked(Map.of(), path -> path))
                .as("no variable")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LentLogin.VARIABLE);
        assertThatThrownBy(
                        () ->
                                PoreloProgram.checked(
                                        Map.of(LentLogin.VARIABLE, "/tmp/porelo"),
                                        path -> {
                                            throw new IllegalStateException(
                                                    path + " is reached through a link");
                                        }))
                .as("a program its check refuses")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Porelo's executable is refused");
    }
}
