package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Repositories a test writes, commits and serves as bare clones, one directory each. */
public final class ServedRepositories {

    private final Path dir;

    private final Path served;

    /**
     * Makes the repositories of a test.
     *
     * @param dir the test's own directory
     * @throws IOException when the served directory cannot be made
     */
    public ServedRepositories(final Path dir) throws IOException {
        this.dir = dir;
        this.served = Files.createDirectories(dir.resolve("served"));
    }

    /**
     * Answers the directory of bare repositories, as a server serves them.
     *
     * @return the directory
     */
    public Path served() {
        return served;
    }

    /**
     * Answers a repository's working tree, for a change a map of files cannot make.
     *
     * @param name the repository's name
     * @return its working tree
     */
    public Path work(final String name) {
        return dir.resolve("work-" + name);
    }

    /**
     * Writes a repository's files, commits them and serves the commit as {@code <name>.git}.
     *
     * @param name the repository's name
     * @param files each file's path and text
     * @throws Exception when git fails
     */
    public void repository(final String name, final Map<String, String> files) throws Exception {
        final Path work = work(name);
        if (!Files.exists(work)) {
            Files.createDirectories(work);
            git(work, "init", "--quiet", "--initial-branch=main");
        }
        for (final Map.Entry<String, String> file : files.entrySet()) {
            final Path path = work.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        commit(name);
    }

    /**
     * Commits everything in a repository's working tree and serves the commit.
     *
     * @param name the repository's name
     * @throws Exception when git fails
     */
    public void commit(final String name) throws Exception {
        final Path work = work(name);
        git(work, "add", "-A");
        git(
                work,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "commit",
                "--quiet",
                "-m",
                name);
        final Path bare = served.resolve(name + ".git");
        if (Files.exists(bare)) {
            git(work, "push", "--quiet", "--force", bare.toString(), "main");
        } else {
            git(dir, "clone", "--quiet", "--bare", work.toString(), bare.toString());
        }
    }

    /**
     * Answers the commit a repository serves.
     *
     * @param name the repository's name
     * @return its commit's id
     * @throws Exception when git fails
     */
    public String head(final String name) throws Exception {
        return git(work(name), "rev-parse", "HEAD").strip();
    }

    private static String git(final Path where, final String... arguments) throws Exception {
        final List<String> line = new ArrayList<>(List.of("git"));
        line.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(line).directory(where.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", arguments) + ": " + out);
        }
        return out;
    }
}
