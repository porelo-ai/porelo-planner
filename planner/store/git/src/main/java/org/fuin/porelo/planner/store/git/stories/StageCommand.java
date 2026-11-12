package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The agents' {@code stage} command: it opens and closes a stage record of an issue in {@code
 * stats/}, keeping the session it reads and its open stage private under the Git directory, and
 * answers a size's median or 80th percentile. Every failure is exit 2 with a fixed line that quotes
 * no session content.
 */
public final class StageCommand {

    /** The exit code of every refusal and every input that cannot be read. */
    public static final int UNANSWERED = 2;

    private static final Set<String> STAGES =
            Set.of(
                    "analyze",
                    "estimate",
                    "plan",
                    "implement",
                    "test",
                    "review",
                    "rework",
                    "handover",
                    "wait");

    private static final Set<String> SIZES = Set.of("XS", "S", "M", "L", "XL");

    private static final Set<String> REASONS = Set.of("operator", "build", "agent", "vm");

    private static final Set<String> RESULTS = Set.of("done", "returned", "refused", "abandoned");

    private static final Set<String> OPTIONS =
            Set.of(
                    "--actor",
                    "--model",
                    "--effort",
                    "--session",
                    "--result",
                    "--status-after",
                    "--wait");

    private static final String USAGE =
            "stage begin|end <issue> <stage> [--actor agent|subagent] [--model MODEL] [--effort"
                    + " EFFORT] [--session FILE] [--result done|returned|refused|abandoned]"
                    + " [--status-after STATE] [--wait operator|build|agent|vm] | stage median|p80"
                    + " <size>";

    /** The largest session file read; a larger one, or one that keeps growing, is refused. */
    static final long SESSION_LIMIT = 64L * 1024 * 1024;

    /** What git must not take from the caller: where its storage is, and the forge's token. */
    private static final Set<String> WITHHELD =
            Set.of("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE", "GITHUB_TOKEN");

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Path root;

    private @Nullable Workflow workflow;

    private final Clock clock;

    private final PrintStream out;

    private final PrintStream err;

    private final Map<String, String> environment;

    /**
     * A stage command for a repository.
     *
     * @param root the repository's root
     * @param clock what "now" is
     * @param out where an answer goes
     * @param err where a refusal goes
     * @param environment where {@code PORELO_STAGE_CLAUDE_LOG} names the agent's own session
     */
    public StageCommand(
            final Path root,
            final Clock clock,
            final PrintStream out,
            final PrintStream err,
            final Map<String, String> environment) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
        this.out = out;
        this.err = err;
        this.environment = Map.copyOf(environment);
    }

    /**
     * Runs a stage operation.
     *
     * @param arguments the arguments after {@code stage}
     * @return 0, or {@value #UNANSWERED} with a reason on any refusal or unreadable input
     */
    public int run(final List<String> arguments) {
        try {
            if (arguments.size() == 2 && Set.of("median", "p80").contains(arguments.getFirst())) {
                out.println(
                        decimal(statistic(arguments.get(1), arguments.getFirst().equals("p80"))));
                return 0;
            }
            final Options options = Options.parse(arguments, OPTIONS);
            if (options == null) {
                throw new IllegalArgumentException(USAGE);
            }
            final Set<String> seen = new HashSet<>();
            for (final String argument : arguments) {
                if (argument.startsWith("--")) {
                    require(seen.add(argument.split("=", 2)[0]), "a stage option was repeated");
                }
            }
            final List<String> positional = options.positional();
            require(positional.size() == 3, USAGE);
            final String action = positional.getFirst();
            require(action.equals("begin") || action.equals("end"), USAGE);
            final String issue = positional.get(1);
            final String stage = positional.get(2);
            require(
                    issue.matches("[A-Z]{1,3}\\d{2,3}"),
                    "issue must match [A-Z]{1,3} followed by two or three digits");
            require(STAGES.contains(stage), "unknown stage");
            final String actor = options.values().getOrDefault("--actor", "agent");
            require(Set.of("agent", "subagent").contains(actor), "actor must be agent or subagent");
            validateOptions(options, stage);
            require(
                    !action.equals("begin") || options.value("--status-after") == null,
                    "status-after is supplied on end");
            require(
                    !action.equals("begin") || options.value("--result") == null,
                    "result is supplied on end");
            require(
                    !action.equals("begin")
                            || !stage.equals("wait")
                            || options.value("--wait") != null,
                    "a wait stage needs --wait");
            final String session = session(options, actor, action.equals("begin"));
            require(!actor.equals("subagent") || session != null, "a subagent needs --session");
            require(
                    !action.equals("begin")
                            || !actor.equals("subagent")
                            || options.value("--model") != null,
                    "a subagent begin needs --model");
            if (action.equals("begin")) {
                issue(issue);
            }
            final Path stateDirectory = gitDirectory().resolve("porelo-stage");
            plain(stateDirectory);
            Files.createDirectories(stateDirectory);
            final Path lockPath = stateDirectory.resolve("lock");
            plain(lockPath);
            // All actors share the lock so concurrent ends cannot interleave JSON lines or
            // ordinals.
            try (FileChannel channel =
                            FileChannel.open(
                                    lockPath,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS);
                    FileLock lock = channel.tryLock()) {
                require(lock != null, "another stage command is running");
                final Path state =
                        stateDirectory.resolve(
                                actor.equals("agent")
                                        ? "agent.json"
                                        : "subagent-"
                                                + digest(session == null ? "" : session)
                                                + ".json");
                plain(state);
                if (action.equals("begin")) {
                    begin(issue, stage, actor, session, options, state);
                } else {
                    end(issue, stage, actor, session, options, state);
                }
            }
            return 0;
        } catch (IOException | UncheckedIOException ex) {
            err.println("stage: could not access stage storage or session file");
            return UNANSWERED;
        } catch (InvalidPathException ex) {
            err.println("stage: invalid session path");
            return UNANSWERED;
        } catch (DateTimeException | NumberFormatException | StageTokens.InvalidJson ex) {
            err.println("stage: invalid JSON, timestamp or number");
            return UNANSWERED;
        } catch (IllegalArgumentException ex) {
            err.println(
                    unreadable(ex)
                            ? "stage: invalid JSON, timestamp or number"
                            : "stage: " + ex.getMessage());
            return UNANSWERED;
        } catch (ArithmeticException | OverlappingFileLockException ex) {
            err.println("stage: " + ex.getMessage());
            return UNANSWERED;
        }
    }

    /**
     * Whether a refusal came from text that is no JSON, time or number, whose words may quote it.
     */
    private static boolean unreadable(final IllegalArgumentException ex) {
        return ex.getCause() instanceof JsonProcessingException
                || ex.getCause() instanceof DateTimeException
                || ex.getCause() instanceof NumberFormatException
                || ex.getCause() instanceof StageTokens.InvalidJson;
    }

    private Workflow workflow() throws IOException {
        if (workflow == null) {
            final Path file = root.resolve("issues/workflow.yml");
            safe(file);
            try {
                workflow = Workflow.read(file);
            } catch (IOException ex) {
                throw new IllegalArgumentException("could not read " + file, ex);
            }
        }
        return workflow;
    }

    private void validateOptions(final Options options, final String stage) throws IOException {
        for (final String value : options.values().values()) {
            require(!value.startsWith("-"), "an option value must not start with a hyphen");
        }
        for (final String key : List.of("--model", "--effort")) {
            final String value = options.value(key);
            require(
                    value == null || value.matches("[A-Za-z0-9._-]+"),
                    key + " must be one identifier");
        }
        final String result = options.value("--result");
        require(result == null || RESULTS.contains(result), "unknown result");
        final String status = options.value("--status-after");
        require(
                status == null || workflow().states().contains(status),
                "unknown status-after in " + root.resolve("issues/workflow.yml"));
        final String reason = options.value("--wait");
        require(
                reason == null || (stage.equals("wait") && REASONS.contains(reason)),
                "wait reason must be operator, build, agent or vm on a wait stage");
        final String session = options.value("--session");
        require(session == null || !session.isBlank(), "session must name a file");
    }

    private @Nullable String session(
            final Options options, final String actor, final boolean beginning) {
        final String given = options.value("--session");
        final String value =
                given != null
                        ? given
                        : beginning && actor.equals("agent")
                                ? environment.get("PORELO_STAGE_CLAUDE_LOG")
                                : null;
        if (value == null) {
            return null;
        }
        require(!value.isBlank(), "session must name a file");
        final Path path = root.resolve(value).normalize();
        if (!(beginning && actor.equals("subagent") && Files.notExists(path))) {
            readable(path);
        }
        return path.toString();
    }

    /**
     * Refuses a session that is no plain file the reader can bound: a symbolic link, a FIFO that
     * would hold the read, or one over {@link #SESSION_LIMIT}.
     */
    private static void readable(final Path session) {
        final long size;
        try {
            size = Files.size(session);
        } catch (IOException ex) {
            throw new IllegalArgumentException("session is not a readable regular file");
        }
        require(
                Files.isRegularFile(session, LinkOption.NOFOLLOW_LINKS)
                        && Files.isReadable(session),
                "session is not a readable regular file");
        require(size <= SESSION_LIMIT, "session is larger than 64 MB");
    }

    private void begin(
            final String issue,
            final String stage,
            final String actor,
            final @Nullable String session,
            final Options options,
            final Path state)
            throws IOException {
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            require(!recorded(state), "a stage is already open for this actor/session");
            // A begin that broke off between its private state and its record left only the state.
            Files.delete(state);
        }
        final CommitRules.Issue metadata = issue(issue);
        require(
                metadata.size() != null || Set.of("analyze", "estimate").contains(stage),
                "this stage needs an issue size");
        final Path output = stats(issue);
        final List<Map<String, @Nullable Object>> previous = records(output);
        if (actor.equals("agent")) {
            requireNoOpenOwnStage();
        }
        int ordinal = 1;
        for (final Map<String, @Nullable Object> record : previous) {
            if (stage.equals(record.get("stage"))) {
                ordinal =
                        Math.max(
                                ordinal,
                                Math.toIntExact(StageValues.integer(record.get("n"), "n")) + 1);
            }
        }
        final String reason = options.value("--wait");
        require(!stage.equals("wait") || reason != null, "a wait stage needs --wait");
        final Map<String, @Nullable Object> record = new LinkedHashMap<>();
        record.put("issue", issue);
        record.put("stage", stage);
        record.put("n", ordinal);
        record.put("actor", actor);
        record.put("model", options.value("--model"));
        record.put("effort", options.value("--effort"));
        record.put("size", metadata.size());
        record.put("status_before", metadata.status());
        record.put("status_after", null);
        record.put("start", clock.instant().toString());
        record.put("end", null);
        record.put("wall_min", null);
        record.put("vm_min", null);
        final String size = metadata.size();
        record.put("estimate_min", stage.equals("estimate") && size != null ? median(size) : null);
        record.put("result", options.value("--result"));
        record.put("wait_reason", reason);
        record.put("note", session == null ? "no-session" : null);
        StageTokens.empty(record);
        StageRecords.validate(record, false, workflow());
        final Map<String, @Nullable Object> saved = new LinkedHashMap<>();
        // Only the reference and session accounting stay private; the record lives in stats.
        saved.put("issue", issue);
        saved.put("stage", stage);
        saved.put("n", ordinal);
        saved.put("session", session);
        saved.put(
                "codex_begin",
                session == null ? null : StageTokens.baseline(root.resolve(session)));
        Files.writeString(
                state,
                write(saved),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(
                    output,
                    write(record) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            Files.delete(state);
            throw ex;
        }
        out.println("stage opened");
    }

    /** Whether the stage a private state names stands in its issue's records. */
    private boolean recorded(final Path state) throws IOException {
        final Map<String, @Nullable Object> saved =
                StageValues.object(parse(Files.readString(state)));
        final String issue = StageValues.string(saved, "issue");
        require(issue.matches("[A-Z]{1,3}\\d{2,3}"), "invalid private stage state");
        final long n = StageValues.integer(saved.get("n"), "n");
        for (final Map<String, @Nullable Object> record : records(stats(issue))) {
            if (saved.get("stage") instanceof String stage
                    && stage.equals(record.get("stage"))
                    && n == StageValues.integer(record.get("n"), "n")) {
                return true;
            }
        }
        return false;
    }

    private void end(
            final String issue,
            final String stage,
            final String actor,
            final @Nullable String session,
            final Options options,
            final Path state)
            throws IOException {
        require(
                Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS),
                "no open stage for this actor/session");
        final Map<String, @Nullable Object> saved =
                StageValues.object(parse(Files.readString(state)));
        require(
                issue.equals(saved.get("issue")) && stage.equals(saved.get("stage")),
                "end does not match the open stage");
        final Path output = stats(issue);
        final String contents = Files.readString(output);
        final List<Map<String, @Nullable Object>> earlier =
                StageRecords.read(contents, output.getFileName().toString(), false, workflow());
        int index = -1;
        for (int i = 0; i < earlier.size(); i++) {
            final Map<String, @Nullable Object> candidate = earlier.get(i);
            if (stage.equals(candidate.get("stage"))
                    && StageValues.integer(saved.get("n"), "n")
                            == StageValues.integer(candidate.get("n"), "n")) {
                require(index == -1, "stage ordinal belongs to multiple records");
                index = i;
            }
        }
        require(index >= 0, "open stage is missing from stats");
        final Map<String, @Nullable Object> record = earlier.get(index);
        require(actor.equals(record.get("actor")), "end does not match the open actor");
        if (record.get("end") != null) {
            Files.delete(state);
            out.println("stage already closed");
            return;
        }
        StageRecords.validate(record, false, workflow());
        for (final String field : List.of("model", "effort", "wait_reason")) {
            final String value =
                    options.value(field.equals("wait_reason") ? "--wait" : "--" + field);
            require(value == null || value.equals(record.get(field)), "end cannot change " + field);
        }
        final Object source = saved.get("session");
        require(source == null || source instanceof String, "invalid private session state");
        require(session == null || session.equals(source), "end names a different session");
        final Instant start = Instant.parse(StageValues.string(record, "start"));
        final Instant end = clock.instant();
        require(!end.isBefore(start), "end precedes start");
        final String statusAfter = options.value("--status-after");
        record.put("status_after", statusAfter == null ? issue(issue).status() : statusAfter);
        record.put(
                "result",
                options.values()
                        .getOrDefault(
                                "--result",
                                record.get("result") instanceof String result ? result : "done"));
        record.put("end", end.toString());
        final double minutes = Duration.between(start, end).toMillis() / 60000.0;
        record.put("wall_min", minutes);
        record.put("vm_min", "vm".equals(record.get("wait_reason")) ? minutes : 0);
        if (source instanceof String path) {
            // The session may have changed since begin: it is held to the same bounds again.
            readable(root.resolve(path));
            StageTokens.read(
                    root.resolve(path),
                    actor,
                    start,
                    end,
                    saved.get("codex_begin"),
                    Set.of("abandoned", "refused").contains(StageValues.string(record, "result")),
                    record);
        }
        StageRecords.validate(record, true, workflow());
        record.put("n", StageValues.integer(record.get("n"), "n"));
        int offset = 0;
        for (int i = 0; i < index; i++) {
            offset = contents.indexOf('\n', offset) + 1;
        }
        final int newline = contents.indexOf('\n', offset);
        replace(
                output,
                contents.substring(0, offset) + write(record) + contents.substring(newline));
        Files.delete(state);
        out.println("stage closed");
    }

    private double median(final String size) throws IOException {
        return statistic(size, false);
    }

    private double statistic(final String size, final boolean p80) throws IOException {
        require(SIZES.contains(size), "unknown size");
        final Path stats = root.resolve("stats");
        final Path seed = stats.resolve("seed.csv");
        safe(stats);
        safe(seed);
        final String seedText = Files.readString(seed);
        final Map<String, String> files = new TreeMap<>();
        try (var paths = Files.list(stats)) {
            for (final Path file :
                    paths.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                safe(file);
                files.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return StageRecords.statistic(seedText, files, size, p80, workflow());
    }

    private CommitRules.Issue issue(final String issue) throws IOException {
        final Path directory = root.resolve("issues");
        safe(directory);
        final List<Path> matches;
        try (var files = Files.walk(directory)) {
            matches =
                    files.filter(
                                    p ->
                                            p.getFileName()
                                                    .toString()
                                                    .matches(Pattern.quote(issue) + "-[^/]+\\.md"))
                            .toList();
        }
        require(matches.size() == 1, "expected exactly one issue file");
        final Path file = matches.getFirst();
        safe(file);
        return CommitRules.issue(Files.readString(file), workflow());
    }

    private Path stats(final String issue) throws IOException {
        final Path directory = root.resolve("stats");
        safe(directory);
        final Path file = directory.resolve(issue + ".jsonl");
        safe(file);
        return file;
    }

    private List<Map<String, @Nullable Object>> records(final Path file) throws IOException {
        safe(file);
        if (!Files.exists(file)) {
            return List.of();
        }
        return StageRecords.read(
                Files.readString(file), file.getFileName().toString(), false, workflow());
    }

    private void requireNoOpenOwnStage() throws IOException {
        final Path directory = root.resolve("stats");
        safe(directory);
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (final Path file :
                    paths.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                for (final Map<String, @Nullable Object> record : records(file)) {
                    require(
                            !"agent".equals(record.get("actor")) || record.get("end") != null,
                            "an own stage is already open in stats");
                }
            }
        }
    }

    private static void replace(final Path file, final String contents) throws IOException {
        final Path temporary = Files.createTempFile(file.getParent(), "stage-", ".tmp");
        try {
            Files.writeString(temporary, contents);
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * The repository's own Git directory: its {@code .git}, or a linked worktree's directory under
     * a {@code .git/worktrees/}, never one the caller's environment or a crafted {@code .git} file
     * points elsewhere.
     */
    private Path gitDirectory() throws IOException {
        safe(root.resolve(".git"));
        final ProcessBuilder builder =
                new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--git-dir")
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
        final Map<String, String> env = builder.environment();
        env.putAll(environment);
        WITHHELD.forEach(env::remove);
        env.put("LC_ALL", "C");
        final Process process = builder.start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git rev-parse did not finish within 10 seconds");
            }
            require(
                    process.exitValue() == 0,
                    "git rev-parse --git-dir failed; run in a repository root");
            final String directory =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                            .strip();
            require(
                    !directory.isEmpty() && !directory.contains("\n"),
                    "git returned no single directory");
            final Path path = root.resolve(directory).normalize();
            require(
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS),
                    "git directory is unavailable");
            require(
                    path.equals(root.resolve(".git")) || linkedWorktree(path),
                    "git directory is neither the repository's .git nor a linked worktree's");
            return path;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading the git directory", ex);
        }
    }

    /**
     * Whether a Git directory is a linked worktree's storage that names this checkout as its own,
     * not another checkout's that a crafted {@code .git} file points to.
     */
    private boolean linkedWorktree(final Path path) throws IOException {
        final Path worktrees = path.getParent();
        if (worktrees == null || !String.valueOf(worktrees.getFileName()).equals("worktrees")) {
            return false;
        }
        final Path git = worktrees.getParent();
        if (git == null || !String.valueOf(git.getFileName()).equals(".git")) {
            return false;
        }
        final Path back = path.resolve("gitdir");
        if (!Files.isRegularFile(back, LinkOption.NOFOLLOW_LINKS) || Files.size(back) > 4096) {
            return false;
        }
        final String named = Files.readString(back).strip();
        try {
            return Path.of(named).toAbsolutePath().normalize().equals(root.resolve(".git"));
        } catch (InvalidPathException ex) {
            return false;
        }
    }

    /** Refuses a symbolic link anywhere below the root in a path the command reads or writes. */
    private void safe(final Path path) throws IOException {
        // A linked worktree legitimately names Git storage outside its own repository root.
        for (@Nullable Path part = path.toAbsolutePath().normalize();
                part != null && part.startsWith(root);
                part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IOException("stage storage must not be a symbolic link");
            }
        }
    }

    /** Refuses a symbolic link in the private stage storage, which may lie outside the root. */
    private void plain(final Path path) throws IOException {
        safe(path);
        if (Files.isSymbolicLink(path)) {
            throw new IOException("stage storage must not be a symbolic link");
        }
    }

    private static @Nullable Object parse(final String text) {
        try {
            return JSON.readValue(text, Object.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid JSON in the private stage state", ex);
        }
    }

    private static String write(final Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("a record of plain values is always written", ex);
        }
    }

    private static void require(final boolean condition, final String reason) {
        StageValues.require(condition, reason);
    }

    private static String decimal(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String digest(final String session) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(session.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** Arguments split into positional ones and options with a value. */
    private record Options(List<String> positional, Map<String, String> values) {

        /** Parses, refusing anything it was not told about rather than ignoring it. */
        static @Nullable Options parse(final List<String> args, final Set<String> valued) {
            final List<String> positional = new ArrayList<>();
            final Map<String, String> values = new HashMap<>();
            for (int i = 0; i < args.size(); i++) {
                final String arg = args.get(i);
                final int equals = arg.indexOf('=');
                final String name =
                        arg.startsWith("--") && equals > 0 ? arg.substring(0, equals) : arg;
                if (valued.contains(name)) {
                    if (equals > 0) {
                        values.put(name, arg.substring(equals + 1));
                    } else if (i + 1 < args.size()) {
                        values.put(name, args.get(++i));
                    } else {
                        return null;
                    }
                } else if (arg.startsWith("--")) {
                    return null;
                } else {
                    positional.add(arg);
                }
            }
            return new Options(positional, values);
        }

        @Nullable String value(final String name) {
            return values.get(name);
        }
    }
}
