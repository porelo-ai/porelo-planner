package org.fuin.porelo.planner.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * The work tools' fixture: the project {@code porelo-sample} and the repository {@code
 * porelo-alpha} beside it, their indexes, story texts and release notes as the build tools wrote
 * them.
 */
final class WorkFixture {

    /** The fixture as kept, read only. */
    static final Path KEPT = Path.of("src/test/resources/work").toAbsolutePath();

    /** The release notes the build tools cut from the fixture: {@code 1.2.0}, after 2026-10-31. */
    static final String NOTES =
            """
            ## 1.2.0

            The stories tested after 2026-10-31:

            - [ST1](https://github.com/porelo-ai/porelo-sample/blob/main/stories/ST1-The-First-Release.md) The First Release (2026-11-02)
            """;

    private WorkFixture() {}

    /**
     * Copies the fixture.
     *
     * @param dir an empty directory
     * @return the copy's root, holding both checkouts
     * @throws IOException when it cannot be copied
     */
    static Path copy(final Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(KEPT)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final Path copy = dir.resolve(KEPT.relativize(file).toString());
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }
        return dir;
    }

    /** What a command answered: its exit code and its two streams. */
    record Answer(int code, String out, String err) {}

    /**
     * Runs a work tool in this JVM.
     *
     * @param args the command line, its first argument {@code issues} or {@code stories}
     * @return what it answered
     */
    static Answer planner(final String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int code =
                WorkCommands.run(
                        Arrays.asList(args),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Answer(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
