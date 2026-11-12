package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The build resource this build wrote, held to the checkout it was built from. */
class BuildResourceTest {

    @Test
    void theBuildResourceNamesTheCommitOfTheCheckout() throws Exception {
        // A window naming another build than the one running.
        final PlannerBuild build =
                PlannerBuild.read(BuildResourceTest.class.getClassLoader(), PlannerBuild.RESOURCE);
        final Path root = Path.of("").toAbsolutePath().getParent().getParent();
        if (Files.exists(root.resolve(".git"))) {
            assertThat(build.commit())
                    .as("the commit in the build resource against the checkout's HEAD in %s", root)
                    .isEqualTo(head(root));
        } else {
            assertThat(build.commit()).as("the commit of a build without a checkout").isNull();
        }
    }

    private static String head(final Path root) throws IOException, InterruptedException {
        final ProcessBuilder builder =
                new ProcessBuilder("git", "rev-parse", "HEAD")
                        .directory(root.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        final Process git = builder.start();
        git.getOutputStream().close();
        final String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor(10, TimeUnit.SECONDS)).as("git rev-parse ended").isTrue();
        assertThat(git.exitValue()).as("git rev-parse's exit code; it wrote %s", out).isZero();
        return out.strip();
    }
}
