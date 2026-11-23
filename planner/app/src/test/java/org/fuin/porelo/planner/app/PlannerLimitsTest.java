package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The factors a planner was started with, reaching the limits of its own beans and none other's.
 */
class PlannerLimitsTest {

    private static final LimitFactors SLOW = LimitFactors.parse("retries=0.5,services=2.0");

    private Path first;

    private Path second;

    @BeforeEach
    void runtimeDirectories() throws IOException {
        first = RuntimeDirectories.create();
        second = RuntimeDirectories.create();
    }

    @AfterEach
    void deleteRuntimeDirectories() throws IOException {
        RuntimeDirectories.delete(first);
        RuntimeDirectories.delete(second);
    }

    @Test
    void theFactorsOfTheCommandLineReachThePlannersLimits() {
        // Harm: a running planner whose factors never left its command line, so it keeps every
        // limit at its default though the person set another.
        try (ConfigurableApplicationContext planner = start(SLOW, first)) {
            assertThat(planner.getBean(LimitFactors.class))
                    .as("the factors the beans are given")
                    .isEqualTo(SLOW);
            assertThat(planner.getBean(AddressFile.class).limits())
                    .as("the factors of the address file's steps")
                    .isEqualTo(SLOW);
            assertThat(planner.getBean(WorkRecord.class).limits())
                    .as("the factors of the work record's replacement")
                    .isEqualTo(SLOW);
        }
    }

    @Test
    void aSecondStartInOneProcessLeavesTheFirstItsFactors() {
        // Harm: a planner started beside, or after, another in one process taking that one's
        // factors for its own file steps, so a locked file is tried for hours or not at all.
        try (ConfigurableApplicationContext slow = start(SLOW, first)) {
            try (ConfigurableApplicationContext plain = start(LimitFactors.DEFAULT, second)) {
                assertThat(slow.getBean(AddressFile.class).limits())
                        .as("the first planner's factors, after the second started")
                        .isEqualTo(SLOW);
                assertThat(plain.getBean(AddressFile.class).limits())
                        .as("the second planner's own factors")
                        .isEqualTo(LimitFactors.DEFAULT);
                assertThat(plain.getBean(WorkRecord.class).limits())
                        .as("the second planner's work record")
                        .isEqualTo(LimitFactors.DEFAULT);
            }
        }
    }

    private static ConfigurableApplicationContext start(
            final LimitFactors limits, final Path runtime) {
        return PlannerApplication.run(
                limits,
                "--mock",
                "--server.port=0",
                "--planner.runtime-directory=" + runtime,
                "--planner.log.base=" + runtime);
    }
}
