package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class NativeStoriesTest {
    @TempDir Path work;

    // Prevents missing reachability metadata from changing or breaking a caller's generated files.
    @Test
    @EnabledIfSystemProperty(named = "stories.native", matches = "true")
    void writesExactlyWhatTheJvmWrites() throws Exception {
        final Path fixture = Path.of("src/test/resources/checkouts").toAbsolutePath();
        final Path jvm = work.resolve("jvm");
        final Path nativeOutput = work.resolve("native");
        NativeStoriesMain.main(new String[] {fixture.toString(), jvm.toString()});
        final Path log = work.resolve("native.log");
        final Process process =
                new ProcessBuilder(
                                System.getProperty("stories.native.executable"),
                                "-XX:MissingRegistrationReportingMode=Exit",
                                fixture.toString(),
                                nativeOutput.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("fixture executable exits within a minute")
                    .isTrue();
            assertThat(process.exitValue())
                    .as("native fixture output: %s", Files.readString(log))
                    .isZero();
        } finally {
            process.destroyForcibly();
        }
        final Path stories = jvm.resolve("porelo-project/stories");
        final Path nativeStories = nativeOutput.resolve("porelo-project/stories");
        try (var files = Files.list(stories);
                var nativeFiles = Files.list(nativeStories)) {
            final var expected = files.map(path -> path.getFileName().toString()).sorted().toList();
            assertThat(expected)
                    .as("the fixture exercises real story and index outputs")
                    .isNotEmpty();
            assertThat(nativeFiles.map(path -> path.getFileName().toString()).sorted().toList())
                    .as("the native executable writes exactly the same tree")
                    .isEqualTo(expected);
            for (final String name : expected)
                assertThat(Files.readAllBytes(nativeStories.resolve(name)))
                        .as("native versus JVM: %s", name)
                        .isEqualTo(Files.readAllBytes(stories.resolve(name)));
        }
    }
}
