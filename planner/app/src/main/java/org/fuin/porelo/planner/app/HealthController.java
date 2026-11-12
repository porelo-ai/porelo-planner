package org.fuin.porelo.planner.app;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Says that the planner is up, so its starter knows when to call it, and which planner it is, so
 * the window can name it.
 */
@RestController
@ImportRuntimeHints(HealthController.BuildHints.class)
public class HealthController {

    /** Takes the build resource into a native image, which holds no resource it is not told of. */
    static final class BuildHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(final RuntimeHints hints, final @Nullable ClassLoader loader) {
            hints.resources().registerPattern(PlannerBuild.RESOURCE);
        }
    }

    /** The build the planner runs, read once at the start, which fails when it cannot be read. */
    @Configuration(proxyBeanMethods = false)
    static class BuildConfiguration {

        /**
         * The build the planner runs.
         *
         * @return what the build resource states
         */
        @Bean
        PlannerBuild plannerBuild() {
            return PlannerBuild.read(
                    HealthController.class.getClassLoader(), PlannerBuild.RESOURCE);
        }
    }

    /**
     * The health answer.
     *
     * @param status always {@code UP}
     * @param version the planner's package version
     * @param commit the commit it was built from, or {@code null} for a build without a checkout
     */
    public record Health(String status, String version, @Nullable String commit) {}

    private final Health health;

    /**
     * Answers for the given build.
     *
     * @param build the build the planner runs
     */
    public HealthController(final PlannerBuild build) {
        this.health = new Health("UP", build.version(), build.commit());
    }

    /**
     * Answers that the planner is up, and its version and commit.
     *
     * @return the health answer
     */
    @GetMapping("/health")
    public Health health() {
        return health;
    }
}
