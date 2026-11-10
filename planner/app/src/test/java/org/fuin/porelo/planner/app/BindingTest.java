package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @Test
    void thePlannerRefusesToStartWithoutALoopbackAddress() {
        // Without an address the server would listen on every interface, reachable from the
        // network; the check must run before it binds. Its cases are in LoopbackBindingTest.
        final String address = "";
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
