package org.fuin.porelo.planner.store.git;

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
 * The {@link Remote} over the {@code git} command line: one bare clone per repository in the
 * project's cache directory, fetched without its URL ever written into the clone, and every file
 * read from a commit's tree.
 */
public final class GitRemote implements Remote {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    /** The local ref a fetch writes; the remote's own names never reach the clone. */
    private static final String FETCHED = "refs/planner/fetched";

    private final Path project;

    private final Map<String, String> environment;

    private final Duration connectTimeout;

    private final GitRunner runner;

    private final int fileBytes;

    /**
     * Makes a remote.
     *
     * @param project the project's cache directory, already checked to be the person's alone
     * @param environment the planner's own environment
     * @param connectTimeout how long ssh waits for a host
     * @param runner runs each call
     * @param fileBytes the largest file read
     */
    public GitRemote(
            final Path project,
            final Map<String, String> environment,
            final Duration connectTimeout,
            final GitRunner runner,
            final int fileBytes) {
        this.project = project;
        this.environment = Map.copyOf(environment);
        this.connectTimeout = connectTimeout;
        this.runner = runner;
        this.fileBytes = fileBytes;
    }

    @Override
    public String head(final String repository, final CloneUrl url, final @Nullable String branch) {
        Ids.name(repository);
        final String ref = branch == null ? "HEAD" : "refs/heads/" + branch(branch);
        final String out = text(run(null, List.of("ls-remote", "--", url.value(), ref)));
        for (final String line : out.split("\n")) {
            final String[] parts = line.split("\t", 2);
            if (parts.length == 2 && parts[1].equals(ref)) {
                return commit(parts[0]);
            }
        }
        throw new GitFailure(
                GitFailure.Kind.FAILED, "the remote of " + repository + " has no " + ref);
    }

    @Override
    public String fetch(
            final String repository, final CloneUrl url, final @Nullable String branch) {
        final Path clone = clone(repository);
        if (!Files.isDirectory(clone)) {
            run(null, List.of("init", "--bare", "--quiet", "--", clone.toString()));
        }
        final String ref = branch == null ? "HEAD" : "refs/heads/" + branch(branch);
        run(
                clone,
                List.of(
                        "fetch",
                        "--quiet",
                        "--no-tags",
                        "--no-write-fetch-head",
                        "--depth=1",
                        "--",
                        url.value(),
                        "+" + ref + ":" + FETCHED));
        return commit(
                text(run(
                                clone,
                                List.of(
                                        "rev-parse",
                                        "--verify",
                                        "--end-of-options",
                                        FETCHED + "^{commit}")))
                        .strip());
    }

    @Override
    public Optional<String> fetched(final String repository) {
        final Path clone = clone(repository);
        if (!Files.isDirectory(clone)) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    commit(
                            text(run(
                                            clone,
                                            List.of(
                                                    "rev-parse",
                                                    "--verify",
                                                    "--end-of-options",
                                                    FETCHED + "^{commit}")))
                                    .strip()));
        } catch (final GitFailure e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> read(final String repository, final String commit, final String path) {
        final Path clone = clone(repository);
        final String entry =
                text(
                        run(
                                clone,
                                List.of(
                                        "ls-tree",
                                        "-l",
                                        "-z",
                                        "--end-of-options",
                                        commit(commit),
                                        "--",
                                        RepoPath.checked(path))));
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        // "<mode> <type> <object> <size>\t<path>\0"
        final String[] head = entry.substring(0, entry.indexOf('\t')).trim().split("\\s+");
        if (!head[0].equals("100644") && !head[0].equals("100755")) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED,
                    repository + ": " + path + " is not a plain file (mode " + head[0] + ")");
        }
        if (Long.parseLong(head[3]) > fileBytes) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED,
                    repository + ": " + path + " is larger than " + fileBytes + " bytes");
        }
        return Optional.of(text(run(clone, List.of("cat-file", "blob", commit(head[2])))));
    }

    @Override
    public List<String> list(final String repository, final String commit, final String directory) {
        return entries(repository, commit, directory, "blob");
    }

    @Override
    public List<String> directories(
            final String repository, final String commit, final String directory) {
        return entries(repository, commit, directory, "tree");
    }

    private List<String> entries(
            final String repository,
            final String commit,
            final String directory,
            final String type) {
        final Path clone = clone(repository);
        final String out =
                text(
                        run(
                                clone,
                                List.of(
                                        "ls-tree",
                                        "-z",
                                        "--end-of-options",
                                        commit(commit),
                                        "--",
                                        RepoPath.checked(directory) + "/")));
        final List<String> found = new ArrayList<>();
        for (final String entry : out.split("\0")) {
            if (entry.isEmpty()) {
                continue;
            }
            final int tab = entry.indexOf('\t');
            final String[] head = entry.substring(0, tab).split(" ");
            if (head[1].equals(type)) {
                found.add(entry.substring(tab + 1));
            }
        }
        found.sort(null);
        return List.copyOf(found);
    }

    private Path clone(final String repository) {
        return project.resolve(Ids.name(repository) + ".git");
    }

    private byte[] run(final @Nullable Path clone, final List<String> arguments) {
        final List<String> line = new ArrayList<>();
        if (clone != null) {
            line.add("--git-dir=" + clone);
        }
        line.addAll(arguments);
        return runner.run(
                GitCall.of(environment, connectTimeout, line), clone == null ? project : clone);
    }

    private static String commit(final String id) {
        if (!COMMIT.matcher(id).matches()) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED, "not a commit id: " + id.length() + " characters");
        }
        return id;
    }

    private static String branch(final String branch) {
        if (!Branch.ok(branch)) {
            throw new IllegalArgumentException("Not a branch name the planner takes");
        }
        return branch;
    }

    private static String text(final byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A branch name: what {@code git check-ref-format --branch} takes, kept to a plain subset. */
    private static final class Branch {
        private static final Pattern BRANCH =
                Pattern.compile(
                        "[A-Za-z0-9_][A-Za-z0-9._-]{0,99}(/[A-Za-z0-9_][A-Za-z0-9._-]{0,99}){0,4}");

        static boolean ok(final String branch) {
            return BRANCH.matcher(branch).matches()
                    && !branch.contains("..")
                    && !branch.endsWith(".lock")
                    && !branch.endsWith(".");
        }
    }
}
