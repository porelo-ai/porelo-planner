package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;

/**
 * The {@link LocalRefs} over the {@code git} command line, in the bare clones {@link GitRemote}
 * keeps in the project's cache directory.
 */
final class GitLocalRefs implements LocalRefs {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    /** The saves not yet pushed; it exists only while one waits. */
    static final String LOCAL = "refs/planner/local";

    /** The remote's branch as a rejected push fetched it. */
    private static final String REMOTE = "refs/planner/remote";

    private final Path project;

    private final Map<String, String> environment;

    private final Duration connectTimeout;

    private final GitRunner runner;

    private final LentLogin login;

    /**
     * Makes the refs, with the login Porelo lends as the environment names it.
     *
     * @param project the project's cache directory, already checked to be the person's alone
     * @param environment the planner's own environment
     * @param connectTimeout how long ssh waits for a host
     * @param runner runs each call
     */
    GitLocalRefs(
            final Path project,
            final Map<String, String> environment,
            final Duration connectTimeout,
            final GitRunner runner) {
        this(project, environment, connectTimeout, runner, LentLogin.of(environment));
    }

    /**
     * Makes the refs.
     *
     * @param project the project's cache directory, already checked to be the person's alone
     * @param environment the planner's own environment
     * @param connectTimeout how long ssh waits for a host
     * @param runner runs each call
     * @param login the login lent to the calls that reach an https central
     */
    GitLocalRefs(
            final Path project,
            final Map<String, String> environment,
            final Duration connectTimeout,
            final GitRunner runner,
            final LentLogin login) {
        this.project = project;
        this.environment = Map.copyOf(environment);
        this.connectTimeout = connectTimeout;
        this.runner = runner;
        this.login = login;
    }

    @Override
    public Optional<String> local(final String repository) {
        final Path clone = clone(repository);
        if (!Files.isDirectory(clone)) {
            return Optional.empty();
        }
        final String out =
                text(runner.find(
                                call(
                                        clone,
                                        List.of(
                                                "rev-parse",
                                                "--verify",
                                                "--quiet",
                                                "--end-of-options",
                                                LOCAL + "^{commit}")),
                                clone))
                        .strip();
        return out.isEmpty() ? Optional.empty() : Optional.of(commit(out));
    }

    @Override
    public void move(final String repository, final String to, final @Nullable String expected) {
        final Path clone = clone(repository);
        runner.run(
                call(
                        clone,
                        List.of(
                                "update-ref",
                                "--no-deref",
                                LOCAL,
                                commit(to),
                                expected == null ? "0".repeat(to.length()) : commit(expected))),
                clone);
    }

    @Override
    public void delete(final String repository, final String expected) {
        final Path clone = clone(repository);
        runner.run(
                call(clone, List.of("update-ref", "--no-deref", "-d", LOCAL, commit(expected))),
                clone);
    }

    @Override
    public String fetchRemote(
            final String repository, final CloneUrl url, final @Nullable String branch) {
        final Path clone = clone(repository);
        if (branch != null && !GitRemote.isBranch(branch)) {
            throw new IllegalArgumentException("Not a branch name the planner takes");
        }
        final String ref = branch == null ? "HEAD" : "refs/heads/" + branch;
        final List<String> line = new ArrayList<>();
        line.add("--git-dir=" + clone);
        line.addAll(
                List.of(
                        "fetch",
                        "--quiet",
                        "--no-tags",
                        "--no-write-fetch-head",
                        "--depth=1",
                        "--",
                        url.value(),
                        "+" + ref + ":" + REMOTE));
        runner.run(GitCall.reaching(environment, connectTimeout, login, url, line), clone);
        return commit(
                text(runner.run(
                                call(
                                        clone,
                                        List.of(
                                                "rev-parse",
                                                "--verify",
                                                "--end-of-options",
                                                REMOTE + "^{commit}")),
                                clone))
                        .strip());
    }

    @Override
    public List<String> changed(final String repository, final String from, final String to) {
        return paths(repository, from, to, List.of());
    }

    @Override
    public List<String> added(final String repository, final String from, final String to) {
        return paths(repository, from, to, List.of("--diff-filter=A"));
    }

    private List<String> paths(
            final String repository,
            final String from,
            final String to,
            final List<String> filter) {
        final Path clone = clone(repository);
        final List<String> line =
                new ArrayList<>(List.of("diff-tree", "-r", "-z", "--no-renames", "--name-only"));
        line.addAll(filter);
        line.addAll(List.of("--end-of-options", commit(from), commit(to)));
        final String out = text(runner.run(call(clone, line), clone));
        final List<String> paths = new ArrayList<>();
        for (final String path : out.split("\0")) {
            if (!path.isEmpty()) {
                paths.add(path);
            }
        }
        paths.sort(null);
        return List.copyOf(paths);
    }

    @Override
    public List<String> commits(final String repository, final String base, final String tip) {
        final Path clone = clone(repository);
        final String out =
                text(
                        runner.run(
                                call(
                                        clone,
                                        List.of(
                                                "rev-list",
                                                "--reverse",
                                                "--end-of-options",
                                                commit(tip),
                                                "^" + commit(base))),
                                clone));
        final List<String> commits = new ArrayList<>();
        for (final String line : out.split("\n")) {
            if (!line.isBlank()) {
                commits.add(commit(line.strip()));
            }
        }
        return List.copyOf(commits);
    }

    @Override
    public String replay(final String repository, final String commit, final String onto) {
        final Path clone = clone(repository);
        final String parent =
                commit(
                        text(runner.run(
                                        call(
                                                clone,
                                                List.of(
                                                        "rev-parse",
                                                        "--verify",
                                                        "--end-of-options",
                                                        commit(commit) + "^{commit}^1")),
                                        clone))
                                .strip());
        final List<String> paths = changed(repository, parent, commit);
        final Path work;
        try {
            work = Files.createTempDirectory(project, "replay-");
        } catch (final IOException e) {
            throw new UncheckedIOException("no directory for the replay", e);
        }
        try {
            final Path index = work.resolve("index");
            // An empty directory stands in for a work tree, so a removal finds no file.
            final Path empty = Files.createDirectory(work.resolve("empty"));
            indexed(clone, index, List.of("read-tree", "--end-of-options", commit(onto)));
            final List<String> removed = new ArrayList<>(paths);
            if (!paths.isEmpty()) {
                final List<String> add = new ArrayList<>(List.of("update-index", "--add"));
                final List<String> line =
                        new ArrayList<>(List.of("ls-tree", "-z", "--end-of-options", commit));
                line.add("--");
                line.addAll(paths);
                for (final String entry : text(runner.run(call(clone, line), clone)).split("\0")) {
                    if (entry.isEmpty()) {
                        continue;
                    }
                    // "<mode> <type> <object>\t<path>"
                    final int tab = entry.indexOf('\t');
                    final String[] head = entry.substring(0, tab).split(" ");
                    final String path = entry.substring(tab + 1);
                    add.add("--cacheinfo");
                    add.add(head[0] + "," + head[2] + "," + path);
                    removed.remove(path);
                }
                if (add.size() > 2) {
                    indexed(clone, index, add);
                }
                if (!removed.isEmpty()) {
                    final List<String> remove =
                            new ArrayList<>(
                                    List.of(
                                            "--work-tree=" + empty,
                                            "update-index",
                                            "--force-remove",
                                            "--"));
                    remove.addAll(removed);
                    indexed(clone, index, remove);
                }
            }
            final String tree = text(indexed(clone, index, List.of("write-tree"))).strip();
            final String message =
                    text(runner.run(
                                    call(
                                            clone,
                                            List.of(
                                                    "log",
                                                    "-1",
                                                    "--format=%B",
                                                    "--end-of-options",
                                                    commit(commit))),
                                    clone))
                            .strip();
            return commit(
                    text(runner.run(
                                    call(
                                            clone,
                                            List.of(
                                                    "commit-tree",
                                                    "-p",
                                                    commit(onto),
                                                    "-m",
                                                    message,
                                                    "--end-of-options",
                                                    tree)),
                                    clone))
                            .strip());
        } catch (final IOException e) {
            throw new UncheckedIOException("no directory for the replay", e);
        } finally {
            SaveFiles.delete(work);
        }
    }

    private byte[] indexed(final Path clone, final Path index, final List<String> arguments) {
        final List<String> line = new ArrayList<>();
        line.add("--git-dir=" + clone);
        line.addAll(arguments);
        return runner.run(GitCall.withIndex(environment, connectTimeout, index, line), clone);
    }

    private GitCall call(final Path clone, final List<String> arguments) {
        final List<String> line = new ArrayList<>();
        line.add("--git-dir=" + clone);
        line.addAll(arguments);
        return GitCall.of(environment, connectTimeout, line);
    }

    private Path clone(final String repository) {
        return project.resolve(Ids.name(repository) + ".git");
    }

    private static String commit(final String id) {
        if (!COMMIT.matcher(id).matches()) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED, "not a commit id: " + id.length() + " characters");
        }
        return id;
    }

    private static String text(final byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
