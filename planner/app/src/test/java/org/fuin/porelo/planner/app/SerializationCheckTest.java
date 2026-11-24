package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the executable's check of its own image: it must write and read back every type the
 * planner binds, so the native executable's answer can be held to the JVM's.
 */
class SerializationCheckTest {

    @Test
    void everyBoundTypeIsWrittenAndReadBackAsItWas() {
        // Harm: a type the check leaves out, so a native image that cannot bind it passes.
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();

        final int exit =
                SerializationCheck.run(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));

        final List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(err.toString(StandardCharsets.UTF_8)).as("no type failed").isEmpty();
        assertThat(exit).as("every type read back").isZero();
        assertThat(lines.stream().map(line -> line.substring(0, line.indexOf(' '))).toList())
                .as("one line per JSON type, then the copy")
                .containsExactlyElementsOf(
                        java.util.stream.Stream.concat(
                                        java.util.stream.Stream.concat(
                                                BoundTypes.GITHUB.stream(),
                                                BoundTypes.OWN.stream()),
                                        java.util.stream.Stream.of(
                                                org.fuin.porelo.planner.store.cache.BoardCopy
                                                        .class))
                                .map(Class::getSimpleName)
                                .toList());
        assertThat(lines.getLast())
                .as("the copy read back holds every record Fory registers")
                .isEqualTo("BoardCopy " + BoundTypes.COPY.size() + " records");
    }

    @Test
    void theExecutableRunsTheCheckBeforeAnythingElse() {
        // Harm: a check that starts the server, so the native test waits on a planner instead.
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final java.util.OptionalInt exit =
                PlannerApplication.work(
                        new String[] {SerializationCheck.NAME},
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertThat(exit).as("answered without the server").hasValue(0);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("the check's lines")
                .contains(" values");
    }
}
