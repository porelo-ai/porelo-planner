package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** The log file of a started planner, as the window finds it through the address file. */
class PlannerLogTest {

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
    void neitherTheTokenNorAWrongOneReachesTheLogAtDebug() throws Exception {
        // The log is kept for days; a token written there lets anyone who reads it call the
        // planner as its owner.
        final Path log = runtime.resolve("porelo/logs/planner.log");
        final String wrong = "wrong-token-" + "q".repeat(31);
        final RuntimeDirectories.Address address;
        try (ConfigurableApplicationContext planner =
                PlannerApplication.run(
                        "--planner.runtime-directory=" + runtime,
                        "--planner.log.base=" + runtime,
                        "--logging.level.org.fuin.porelo.planner=DEBUG")) {
            address = RuntimeDirectories.read(runtime);
            assertThat(RuntimeDirectories.log(runtime))
                    .as("the log file the address file names")
                    .isEqualTo(log.toString());
            assertThat(health(address.port(), address.token()))
                    .as("an authorized call")
                    .isEqualTo(200);
            assertThat(health(address.port(), wrong)).as("an unauthorized call").isEqualTo(401);
        }
        final List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertThat(lines)
                .as("the log's lines: the start and the refused call at least")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(lines)
                .as("lines holding the token or the wrong one")
                .noneMatch(line -> line.contains(address.token()) || line.contains(wrong));
    }

    @Test
    void theMockModeWritesNoLogAndNamesNone() throws Exception {
        // The mock mode serves the window's tests; a log of its own would mix into the person's.
        try (ConfigurableApplicationContext planner =
                PlannerApplication.run(
                        "--planner.runtime-directory=" + runtime,
                        "--planner.log.base=" + runtime,
                        "--mock")) {
            assertThat(RuntimeDirectories.log(runtime))
                    .as("the log file the mock's address file names")
                    .isNull();
        }
        assertThat(runtime.resolve("porelo")).as("a log directory of the mock").doesNotExist();
    }

    private static int health(final int port, final String token) throws Exception {
        return Signing.sign(port, token, "GET", "/health", null, null).send().statusCode();
    }
}
