package org.fuin.porelo.planner.store.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Remote} over the {@code git} command line: one bare clone per repository in the
 * project's cache directory, fetched without its URL ever written into the clone, and every file
 * read from a commit's tree. The planning files of a repository's latest commits are read with two
 * calls each and kept in memory.
 */
public final class GitRemote implements Remote {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    /** The local ref a fetch writes; the remote's own names never reach the clone. */
    private static final String FETCHED = "refs/planner/fetched";

    /** How many commits' planning files are kept per repository: the board's and a save's. */
    private static final int KEPT_COMMITS = 2;

    private final Path project;

    private final Map<String, String> environment;

    private final Duration connectTimeout;

    private final GitRunner runner;

    private final int fileBytes;

    private final LentLogin login;

    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

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
        this(project, environment, connectTimeout, runner, fileBytes, LentLogin.of(environment));
    }

    /**
     * Makes a remote with the login Porelo lends, already checked.
     *
     * @param project the project's cache directory, already checked to be the person's alone
     * @param environment the planner's own environment
     * @param connectTimeout how long ssh waits for a host
     * @param runner runs each call
     * @param fileBytes the largest file read
     * @param login the login lent to the calls that reach an https central
     */
    GitRemote(
            final Path project,
            final Map<String, String> environment,
            final Duration connectTimeout,
            final GitRunner runner,
            final int fileBytes,
            final LentLogin login) {
        this.project = project;
        this.environment = Map.copyOf(environment);
        this.connectTimeout = connectTimeout;
        this.runner = runner;
        this.fileBytes = fileBytes;
        this.login = login;
    }

    @Override
    public String head(final String repository, final CloneUrl url, final @Nullable String branch) {
        Ids.name(repository);
        final String ref = branch == null ? "HEAD" : "refs/heads/" + branch(branch);
        final String out = text(reach(null, url, List.of("ls-remote", "--", url.value(), ref)));
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
        reach(
                clone,
                url,
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
        final String checked = RepoPath.checked(path);
        if (CommitFiles.file(checked)) {
            final CommitFiles files = files(repository, commit(commit));
            final CommitFiles.Entry entry = files.entry(checked);
            if (entry == null) {
                return Optional.empty();
            }
            refused(repository, path, entry);
            final byte[] bytes = files.bytes(entry.object());
            return Optional.of(text(bytes != null ? bytes : blob(repository, entry.object())));
        }
        final Path clone = clone(repository);
        final byte[] listed =
                run(
                        clone,
                        List.of(
                                "ls-tree",
                                "-l",
                                "-z",
                                "--end-of-options",
                                commit(commit),
                                "--",
                                checked));
        if (listed.length == 0) {
            return Optional.empty();
        }
        final CommitFiles.Entry entry = CommitFiles.entries(listed).get(checked);
        if (entry == null) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED, repository + ": git ls-tree answered otherwise");
        }
        refused(repository, path, entry);
        return Optional.of(text(blob(repository, entry.object())));
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

    @Override
    public List<Found> grep(
            final String repository,
            final String commit,
            final java.util.Collection<String> words) {
        if (words.isEmpty()) {
            return List.of();
        }
        final List<String> arguments =
                new ArrayList<>(List.of("grep", "-I", "-n", "-w", "-F", "--null", "--no-color"));
        for (final String word : words) {
            arguments.add("-e");
            arguments.add(Ids.issue(word));
        }
        final String tree = commit(commit);
        arguments.add(tree);
        arguments.add("--");
        final Path clone = clone(repository);
        final List<String> line = new ArrayList<>();
        line.add("--git-dir=" + clone);
        line.addAll(arguments);
        final String out = text(runner.find(GitCall.of(environment, connectTimeout, line), clone));
        final List<Found> found = new ArrayList<>();
        // "<commit>:<path>\0<line>\0<text>\n"
        for (final String entry : out.split("\n")) {
            if (entry.isEmpty()) {
                continue;
            }
            final String[] parts = entry.split("\0", 3);
            if (parts.length != 3 || !parts[0].startsWith(tree + ":")) {
                throw new GitFailure(
                        GitFailure.Kind.FAILED, repository + ": git grep answered otherwise");
            }
            final int number;
            try {
                number = Integer.parseInt(parts[1]);
            } catch (final NumberFormatException e) {
                throw new GitFailure(
                        GitFailure.Kind.FAILED, repository + ": git grep answered otherwise");
            }
            found.add(new Found(parts[0].substring(tree.length() + 1), number, parts[2]));
        }
        return List.copyOf(found);
    }

    @Override
    public String commit(
            final String repository,
            final String parent,
            final Path root,
            final List<String> paths,
            final String subject) {
        final Path clone = clone(repository);
        final String base = commit(parent);
        final List<String> written = new ArrayList<>();
        final List<String> files = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        for (final String path : paths) {
            final String checked = RepoPath.checked(path);
            final Path file = root.resolve(checked);
            if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                removed.add(checked);
            } else if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                written.add(checked);
                files.add(file.toString());
            } else {
                throw new GitFailure(
                        GitFailure.Kind.FAILED, repository + ": " + path + " is no plain file");
            }
        }
        final Path index = root.resolve(".index-" + Ids.name(repository));
        runIndexed(clone, index, List.of("read-tree", "--end-of-options", base));
        if (!written.isEmpty()) {
            final List<String> blobs = blobs(repository, clone, files);
            final Map<String, String> modes = modes(clone, base, written);
            final List<String> update = new ArrayList<>(List.of("update-index", "--add"));
            for (int i = 0; i < written.size(); i++) {
                final String path = written.get(i);
                // A changed file keeps its mode; a new one is a plain file.
                final String mode = "100755".equals(modes.get(path)) ? "100755" : "100644";
                update.add("--cacheinfo");
                update.add(mode + "," + blobs.get(i) + "," + path);
            }
            runIndexed(clone, index, update);
        }
        if (!removed.isEmpty()) {
            // The save's own directory, where the files are gone, stands in for a work tree.
            final List<String> remove =
                    new ArrayList<>(
                            List.of("--work-tree=" + root, "update-index", "--force-remove", "--"));
            remove.addAll(removed);
            runIndexed(clone, index, remove);
        }
        final String tree = text(runIndexed(clone, index, List.of("write-tree"))).strip();
        try {
            return commit(
                    text(run(
                                    clone,
                                    List.of(
                                            "commit-tree",
                                            "-p",
                                            base,
                                            "-m",
                                            subject,
                                            "--end-of-options",
                                            tree)))
                            .strip());
        } catch (final GitFailure failure) {
            throw identityMissing(clone).orElse(failure);
        }
    }

    /** Git's own words on a missing name or email tell the person nothing they can act on. */
    private Optional<GitFailure> identityMissing(final Path clone) {
        for (final String ident : List.of("GIT_AUTHOR_IDENT", "GIT_COMMITTER_IDENT")) {
            try {
                run(clone, List.of("var", ident));
            } catch (final GitFailure missing) {
                return Optional.of(
                        new GitFailure(
                                GitFailure.Kind.FAILED,
                                "Git has no name and email to commit with on this account; set"
                                        + " user.name and user.email in its Git configuration"));
            }
        }
        return Optional.empty();
    }

    /** Writes each file's blob in one call, which answers one id per line in their order. */
    private List<String> blobs(
            final String repository, final Path clone, final List<String> files) {
        final List<String> line =
                new ArrayList<>(List.of("hash-object", "-w", "--no-filters", "--"));
        line.addAll(files);
        final List<String> blobs = text(run(clone, line)).lines().toList();
        if (blobs.size() != files.size()
                || !blobs.stream().allMatch(blob -> COMMIT.matcher(blob).matches())) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED, repository + ": git hash-object answered otherwise");
        }
        return blobs;
    }

    /** The mode each of the paths has in a commit, none for a path it lacks. */
    private Map<String, String> modes(
            final Path clone, final String commit, final List<String> paths) {
        final List<String> line =
                new ArrayList<>(List.of("ls-tree", "-z", "--end-of-options", commit, "--"));
        line.addAll(paths);
        final Map<String, String> modes = new HashMap<>();
        // "<mode> <type> <object>\t<path>\0"
        for (final String entry : text(run(clone, line)).split("\0")) {
            final int tab = entry.indexOf('\t');
            final int space = entry.indexOf(' ');
            if (tab > 0 && space > 0 && space < tab) {
                modes.put(entry.substring(tab + 1), entry.substring(0, space));
            }
        }
        return modes;
    }

    @Override
    public void push(
            final String repository,
            final CloneUrl url,
            final @Nullable String branch,
            final String commit,
            final String expected) {
        final String ref =
                "refs/heads/" + (branch == null ? defaultBranch(repository, url) : branch(branch));
        reach(
                clone(repository),
                url,
                List.of(
                        "push",
                        "--quiet",
                        "--no-verify",
                        "--force-with-lease=" + ref + ":" + commit(expected),
                        "--",
                        url.value(),
                        commit(commit) + ":" + ref));
    }

    @Override
    public void advance(final String repository, final String commit) {
        run(clone(repository), List.of("update-ref", FETCHED, commit(commit)));
    }

    @Override
    public Path scratch() {
        try {
            return Files.createTempDirectory(project, "save-");
        } catch (final java.io.IOException e) {
            throw new java.io.UncheckedIOException("no directory for the save", e);
        }
    }

    /** The branch a remote's HEAD names, which a push needs by name. */
    private String defaultBranch(final String repository, final CloneUrl url) {
        final String out =
                text(reach(null, url, List.of("ls-remote", "--symref", "--", url.value(), "HEAD")));
        for (final String line : out.split("\n")) {
            if (line.startsWith("ref: refs/heads/") && line.endsWith("\tHEAD")) {
                return branch(line.substring(16, line.length() - 5));
            }
        }
        throw new GitFailure(
                GitFailure.Kind.FAILED, "the remote of " + repository + " names no default branch");
    }

    private byte[] runIndexed(final Path clone, final Path index, final List<String> arguments) {
        final List<String> line = new ArrayList<>();
        line.add("--git-dir=" + clone);
        line.addAll(arguments);
        return runner.run(GitCall.withIndex(environment, connectTimeout, index, line), clone);
    }

    private List<String> entries(
            final String repository,
            final String commit,
            final String directory,
            final String type) {
        final String checked = RepoPath.checked(directory);
        if (CommitFiles.directory(checked)) {
            return files(repository, commit(commit)).under(checked, type);
        }
        final String out =
                text(
                        run(
                                clone(repository),
                                List.of(
                                        "ls-tree",
                                        "-z",
                                        "--end-of-options",
                                        commit(commit),
                                        "--",
                                        checked + "/")));
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

    /**
     * Refuses what is no plain file, as a link reaching out of the repository, or a too large one.
     */
    private void refused(
            final String repository, final String path, final CommitFiles.Entry entry) {
        if (!entry.plain()) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED,
                    repository + ": " + path + " is not a plain file (mode " + entry.mode() + ")");
        }
        if (entry.size() > fileBytes) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED,
                    repository + ": " + path + " is larger than " + fileBytes + " bytes");
        }
    }

    private byte[] blob(final String repository, final String object) {
        return run(clone(repository), List.of("cat-file", "blob", commit(object)));
    }

    /** A commit's planning files, from memory once one listing and one batch read them. */
    private CommitFiles files(final String repository, final String commit) {
        return kept.computeIfAbsent(Ids.name(repository), name -> new Kept())
                .files(commit, () -> load(repository, commit));
    }

    private CommitFiles load(final String repository, final String commit) {
        final Path clone = clone(repository);
        final List<String> line =
                new ArrayList<>(
                        List.of(
                                "ls-tree",
                                "-r",
                                "-t",
                                "-l",
                                "-z",
                                "--end-of-options",
                                commit,
                                "--"));
        line.addAll(CommitFiles.PLANNING);
        final Map<String, CommitFiles.Entry> entries = CommitFiles.entries(run(clone, line));
        final Map<String, Long> wanted = CommitFiles.wanted(entries, fileBytes);
        if (wanted.isEmpty()) {
            return CommitFiles.of(entries, Map.of());
        }
        if (CommitFiles.printed(wanted) > runner.outputBytes()) {
            // More than one call may print: each file is read alone when it is asked for.
            return CommitFiles.of(entries, null);
        }
        final byte[] out =
                runner.run(
                        GitCall.of(
                                environment,
                                connectTimeout,
                                List.of("--git-dir=" + clone, "cat-file", "--batch")),
                        clone,
                        CommitFiles.input(wanted.keySet()));
        return CommitFiles.of(entries, CommitFiles.blobs(out, wanted));
    }

    /** The planning files of a repository's latest commits; a commit's files never change. */
    private static final class Kept {

        private final Map<String, CommitFiles> commits =
                new LinkedHashMap<>(4, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            final Map.Entry<String, CommitFiles> eldest) {
                        return size() > KEPT_COMMITS;
                    }
                };

        synchronized CommitFiles files(final String commit, final Supplier<CommitFiles> load) {
            final CommitFiles known = commits.get(commit);
            if (known != null) {
                return known;
            }
            final CommitFiles loaded = load.get();
            commits.put(commit, loaded);
            return loaded;
        }
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

    /** Runs a call that reaches a central, with the login lent for it. */
    private byte[] reach(
            final @Nullable Path clone, final CloneUrl url, final List<String> arguments) {
        final List<String> line = new ArrayList<>();
        if (clone != null) {
            line.add("--git-dir=" + clone);
        }
        line.addAll(arguments);
        return runner.run(
                GitCall.reaching(environment, connectTimeout, login, url, line),
                clone == null ? project : clone);
    }

    private static String commit(final String id) {
        if (!COMMIT.matcher(id).matches()) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED, "not a commit id: " + id.length() + " characters");
        }
        return id;
    }

    /**
     * Answers whether a text is a branch name the planner takes.
     *
     * @param branch the text
     * @return whether it is one
     */
    static boolean isBranch(final String branch) {
        return Branch.ok(branch);
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
