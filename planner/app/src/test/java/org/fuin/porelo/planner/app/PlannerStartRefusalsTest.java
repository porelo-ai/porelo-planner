package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A duration property of zero or less, refused before the planner serves anything. */
class PlannerStartRefusalsTest {

    private Path runtime;

    @BeforeEach
    void runtimeDirectory() throws IOException {
        runtime = RuntimeDirectories.create();
    }

    @AfterEach
    void deleteRuntimeDirectory() throws IOException {
        RuntimeDirectories.delete(runtime);
    }

    @Test
    void aKeepAliveOfZeroIsRefusedAtStart() {
        // Harm: a stream that sends keep-alive comments without a pause, spinning its thread and
        // flooding the window, from a planner that started as if all were well.
        assertThatThrownBy(() -> start("--planner.keep-alive=0s").close())
                .as("a start with a keep-alive of zero")
                .rootCause()
                .hasMessage("planner.keep-alive must be longer than zero, not PT0S");
    }

    @Test
    void aReadSilenceBelowZeroIsRefusedAtAMockStartToo() {
        // Harm: the window's tests against the mock taking a property a real start refuses, so
        // the refusal is first met by the person.
        assertThatThrownBy(() -> start("--planner.github.read-silence=-5s").close())
                .as("a mock start with a read silence below zero")
                .rootCause()
                .hasMessage("planner.github.read-silence must be longer than zero, not PT-5S");
    }

    private AutoCloseable start(final String property) {
        return PlannerApplication.run(
                "--mock",
                "--server.port=0",
                "--planner.runtime-directory=" + runtime,
                "--planner.log.base=" + runtime,
                property);
    }
}
