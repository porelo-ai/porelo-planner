package org.fuin.porelo.planner.store.git.stories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A native-image fixture driver, compiled only with test sources. */
public final class NativeStoriesMain {
    private NativeStoriesMain() {}

    /** Copies a fixture checkout tree to an output directory and regenerates its stories. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2)
            throw new IllegalArgumentException("expected <fixture-checkouts> <output-dir>");
        final Path fixture = Path.of(args[0]);
        final Path output = Path.of(args[1]);
        if (Files.exists(output)) throw new IOException("output already exists: " + output);
        try (var files = Files.walk(fixture)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final Path copy = output.resolve(fixture.relativize(file));
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }
        final Path project = output.resolve("porelo-project");
        final StoriesIndex.Result result =
                StoriesIndex.write(project.resolve("stories"), IssueSource.local(project));
        if (!result.faults().isEmpty()) throw new IOException(String.join("\n", result.faults()));
    }
}
