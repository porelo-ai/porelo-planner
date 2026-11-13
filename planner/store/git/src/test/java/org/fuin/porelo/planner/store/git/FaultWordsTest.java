package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How a planning index's fault reads in a refusal, with its pointers kept and nothing local. */
class FaultWordsTest {

    private static final Path SCRATCH = Path.of("/home/person/.cache/porelo-planner/save-1");

    @Test
    void theScratchDirectoryIsTakenOutAfterEitherSeparator() {
        // Fault words keep their slashes, so only this removal keeps the person's home out.
        assertThat(
                        GitStore.faultWords(
                                "issues: missing; run porelo-release issues index "
                                        + SCRATCH
                                        + "/own/issues and "
                                        + SCRATCH
                                        + "\\own\\issues",
                                SCRATCH))
                .as("a fault naming the save's scratch directory")
                .doesNotContain("person")
                .isEqualTo(
                        "issues: missing; run porelo-release issues index own/issues and"
                                + " own\\issues");
    }

    @Test
    void aSecretIsTakenOutAndAPointerStays() {
        // A fault may quote a central as written, password included.
        assertThat(
                        GitStore.faultWords(
                                "AB1-X.md: /size: a dash; central https://person:s3cret@git.example.org/a",
                                SCRATCH))
                .as("a fault with a pointer and a central's password")
                .doesNotContain("s3cret")
                .startsWith("AB1-X.md: /size: a dash; central https://***git.example.org/a");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/home/person/private",
                "~/person/private",
                "C:\\Users\\person\\private",
                "file:///home/person/private"
            })
    void aPropertyNamedLikeALocalPathIsTakenOutAndThePointersStay(final String name) {
        // An unknown property of a head is repeated in the fault, whatever it is named.
        assertThat(
                        GitStore.faultWords(
                                "AB1-X.md: /: property '"
                                        + name
                                        + "' is not defined in the schema; /blockedBy/0: x;"
                                        + " /size: a dash",
                                SCRATCH))
                .as("a fault repeating a property named %s", name)
                .doesNotContain("person")
                .isEqualTo(
                        "AB1-X.md: /: property '<path>' is not defined in the schema;"
                                + " /blockedBy/0: x; /size: a dash");
    }
}
