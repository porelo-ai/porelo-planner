package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;

/** The level of the planner's log, as the window's settings set it. */
class LogLevelTest {

    /** One of the planner's own loggers, which the level governs. */
    private static final Logger PLANNERS = LoggerFactory.getLogger(LogLevelTest.class);

    private Path runtime;

    @BeforeEach
    void makeRuntimeDirectory() throws IOException {
        runtime = RuntimeDirectories.create();
    }

    @AfterEach
    void deleteRuntimeDirectory() throws IOException {
        LoggingSystem.get(getClass().getClassLoader())
                .setLogLevel(LogFile.LOGGER, org.springframework.boot.logging.LogLevel.INFO);
        RuntimeDirectories.delete(runtime);
    }

    @Test
    void aLevelSetByTheWindowWritesMoreLinesAtOnceAndHoldsFromTheNextStart() throws Exception {
        // A person who raises the level to find a fault must get the lines, now and after a
        // restart.
        final Path log = runtime.resolve("porelo/logs/planner.log");
        final String before = "before-" + UUID.randomUUID();
        final String after = "after-" + UUID.randomUUID();
        try (ConfigurableApplicationContext planner = start()) {
            final RuntimeDirectories.Address address = RuntimeDirectories.read(runtime);
            assertThat(call(address, "GET", null).body())
                    .as("the level at first")
                    .contains("\"INFO\"");
            PLANNERS.debug(before);
            final HttpResponse<String> set = call(address, "PUT", "{\"level\":\"DEBUG\"}");
            assertThat(set.statusCode()).as("the level set").isEqualTo(200);
            assertThat(set.body()).as("the level now in force").contains("\"DEBUG\"");
            PLANNERS.debug(after);
        }
        assertThat(Files.readString(log, StandardCharsets.UTF_8))
                .as("the log holds the DEBUG line after the level was set, not the one before")
                .contains(after)
                .doesNotContain(before);

        try (ConfigurableApplicationContext again = start()) {
            assertThat(call(RuntimeDirectories.read(runtime), "GET", null).body())
                    .as("the level after a new start over the same state directory")
                    .contains("\"DEBUG\"");
        }
    }

    @Test
    void aLevelNoneOfTheFourIsRefusedAndTheLevelInForceStays() throws Exception {
        // A level the planner does not know must not leave its log silent or flooding.
        try (ConfigurableApplicationContext planner = start()) {
            final RuntimeDirectories.Address address = RuntimeDirectories.read(runtime);
            final HttpResponse<String> refused = call(address, "PUT", "{\"level\":\"TRACE\"}");
            assertThat(refused.statusCode()).as("an unknown level").isEqualTo(400);
            assertThat(refused.body()).as("its name").contains("InvalidLevel");
            final HttpResponse<String> unreadable = call(address, "PUT", "{");
            assertThat(unreadable.statusCode()).as("a body that is no level").isEqualTo(400);
            assertThat(unreadable.body())
                    .as("its name, as the description lists it")
                    .contains("InvalidLevel");
            assertThat(call(address, "GET", null).body())
                    .as("the level in force")
                    .contains("\"INFO\"");
        }
        assertThat(runtime.resolve("state/porelo-planner/" + LogLevel.FILE))
                .as("a level kept for an unknown one")
                .doesNotExist();
    }

    private ConfigurableApplicationContext start() {
        return PlannerApplication.run(
                "--planner.runtime-directory=" + runtime,
                "--planner.log.base=" + runtime,
                "--planner.state.base=" + runtime.resolve("state"));
    }

    private static HttpResponse<String> call(
            final RuntimeDirectories.Address address, final String method, final String body)
            throws Exception {
        return Signing.sign(address, method, "/log/level", "application/json", body).send();
    }
}
