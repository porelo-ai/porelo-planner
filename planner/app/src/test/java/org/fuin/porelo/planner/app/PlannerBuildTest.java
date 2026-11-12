package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlannerBuildTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @TempDir Path dir;

    @Test
    void theVersionAndCommitAreAnsweredAsTheFileStatesThem() throws IOException {
        // A window showing a planner version that is not the one running.
        final PlannerBuild build = read("version=0.1.0~snapshot.36\ncommit=" + COMMIT + "\n");
        assertThat(build.version())
                .as("the version the file states")
                .isEqualTo("0.1.0~snapshot.36");
        assertThat(build.commit()).as("the commit the file states").isEqualTo(COMMIT);
    }

    @Test
    void anEmptyCommitIsAnsweredAsNone() throws IOException {
        // A build without a checkout answering a made-up commit, or failing to start at all.
        final PlannerBuild build = read("version=1.2.3\ncommit=\n");
        assertThat(build.version()).as("a release's version").isEqualTo("1.2.3");
        assertThat(build.commit()).as("the commit of a build without a checkout").isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "version=${planner.package.version}\ncommit=",
                "version=0.1.0-SNAPSHOT\ncommit=",
                "version=0.1\ncommit=",
                "version=0.1.0~snapshot.\ncommit=",
                "version=\ncommit=",
                "commit=",
                "version=0.1.0\ncommit=${planner.commit}",
                "version=0.1.0\ncommit=0123456789ABCDEF0123456789ABCDEF01234567",
                "version=0.1.0\ncommit=0123456789abcdef0123456789abcdef0123456",
                "version=0.1.0\ncommit=0123456789abcdef0123456789abcdef012345678",
                "version=0.1.0",
            })
    void aMalformedFileFailsTheStart(final String content) throws IOException {
        // A planner answering a made-up version instead of refusing to start.
        assertThatThrownBy(() -> read(content))
                .as("reading a build file of\n%s", content)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingFileFailsTheStart() throws IOException {
        // A planner answering a made-up version when the build wrote no file.
        try (URLClassLoader loader = loader()) {
            assertThatThrownBy(() -> PlannerBuild.read(loader, PlannerBuild.RESOURCE))
                    .as("reading a build file that is not there")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing");
        }
    }

    private PlannerBuild read(final String content) throws IOException {
        Files.writeString(dir.resolve(PlannerBuild.RESOURCE), content, StandardCharsets.UTF_8);
        try (URLClassLoader loader = loader()) {
            return PlannerBuild.read(loader, PlannerBuild.RESOURCE);
        }
    }

    /** A loader that sees only the test's directory, never the build's own resource. */
    private URLClassLoader loader() throws IOException {
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, null);
    }
}
