package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

class BindingTest {

    private Path runtime;

    @BeforeEach
    void makeRuntimeDirectory() throws IOException {
        runtime = RuntimeDirectories.create();
    }

    @AfterEach
    void deleteRuntimeDirectory() throws IOException {
        RuntimeDirectories.delete(runtime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0.0.0.0", "::", "192.0.2.1", "localhost"})
    void thePlannerRefusesToStartOnAnythingButALoopbackAddress(final String address) {
        // Listening on another address lets other computers on the network reach the planner.
        assertThatThrownBy(
                        () ->
                                new SpringApplicationBuilder(PlannerApplication.class)
                                        .run(
                                                "--server.address=" + address,
                                                "--planner.runtime-directory=" + runtime)
                                        .close())
                .as("a start with server.address '%s'", address)
                .hasStackTraceContaining("server.address is '" + address + "'");
    }
}
