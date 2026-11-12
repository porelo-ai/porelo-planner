package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.core.TaskRecord;
import org.fuin.porelo.planner.core.TaskRecords;
import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;

/**
 * Porelo's own task records, read by running its executable's {@code task records} command and
 * checking every field of its answer before any of it is used.
 */
public final class PoreloTaskRecords implements TaskRecords {

    /** The most of an answer read; a longer one is refused as cut off. */
    static final int ANSWER_LIMIT = 16 * 1024 * 1024;

    /** The most of Porelo's error output kept. */
    static final int ERROR_LIMIT = 64 * 1024;

    /** The names of the planner's environment a child gets, so no secret of it reaches Porelo. */
    static final List<String> PASSED =
            List.of("PATH", "HOME", "LANG", "XDG_STATE_HOME", "XDG_RUNTIME_DIR");

    private static final Duration LIMIT = Duration.ofSeconds(60);

    private static final Pattern ISSUE = Pattern.compile("[A-Z]{1,4}[0-9]{1,6}");

    private static final Set<String> ENDS = Set.of("done", "failed", "stopped", "removed");

    private static final Set<String> ASKED = Set.of("ASKED", "CANNOT_SAY", "DID_NOT_ASK");

    private static final Set<String> KINDS =
            Set.of("shell", "edit", "read", "sub-agent", "search", "web", "other");

    private static final Set<String> SOURCES = Set.of("stream", "duration", "arrival");

    private static final List<String> TOKEN_KINDS =
            List.of("input", "cacheRead", "cacheWrite", "output", "reasoning");

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** What a run of Porelo's executable answered. */
    record Result(int code, byte[] out, boolean cutOff, String err) {}

    /** Runs a command; throws {@link TaskRecords.Unanswered} when it cannot run it to its end. */
    @FunctionalInterface
    interface Runner {
        /**
         * @param command the executable and each argument, one element each
         * @param environment the whole environment the child gets
         * @return what it answered
         */
        Result run(List<String> command, Map<String, String> environment);
    }

    private final Path executable;

    private final Runner runner;

    private final Map<String, String> environment;

    /**
     * Runs Porelo's executable as a process of its own.
     *
     * @param executable the absolute path of Porelo's executable
     */
    public PoreloTaskRecords(final Path executable) {
        this(executable, new ProcessRunner(LIMIT), System.getenv());
    }

    PoreloTaskRecords(
            final Path executable, final Runner runner, final Map<String, String> source) {
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Porelo's executable must be given by an absolute path");
        }
        this.executable = executable;
        this.runner = runner;
        final Map<String, String> passed = new LinkedHashMap<>();
        for (final String name : PASSED) {
            final String value = source.get(name);
            if (value != null) {
                passed.put(name, value);
            }
        }
        this.environment = Map.copyOf(passed);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when the project is no repository name, a caller's error and
     *     not Porelo's
     */
    @Override
    public List<TaskRecord> since(final String project, final Instant since) {
        Ids.name(project);
        final Result result =
                runner.run(
                        List.of(
                                executable.toString(),
                                "task",
                                "records",
                                "-p",
                                project,
                                "--since",
                                since.toString(),
                                "--json"),
                        environment);
        if (result.code() == 74) {
            throw new Unanswered("Porelo cannot read its task records");
        }
        if (result.code() == 64) {
            throw new Unanswered("Porelo refused the time " + since + " as a start of its records");
        }
        if (result.code() != 0) {
            // Its error output may echo a command or a token, so none of it is shown.
            throw new Unanswered("Porelo's task records failed with exit code " + result.code());
        }
        if (result.cutOff()) {
            throw new Unanswered(
                    "Porelo's task records were cut off after " + ANSWER_LIMIT + " bytes");
        }
        return parse(result.out(), project, since);
    }

    private static String escaped(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        text.codePoints()
                .forEach(
                        c -> {
                            if (Character.isISOControl(c)) {
                                out.append(String.format(Locale.ROOT, "\\u%04x", c));
                            } else {
                                out.appendCodePoint(c);
                            }
                        });
        return out.toString();
    }

    private static List<TaskRecord> parse(
            final byte[] answer, final String project, final Instant since) {
        final JsonNode root;
        try {
            root = JSON.readTree(answer);
        } catch (final IOException ex) {
            // The parser's message quotes the answer, which may hold a masked command.
            throw new Unanswered("Porelo's task records are no single JSON document");
        }
        if (root == null || !root.isArray()) {
            throw new Unanswered("Porelo's task records are no JSON array");
        }
        final List<TaskRecord> records = new ArrayList<>(root.size());
        for (int i = 0; i < root.size(); i++) {
            records.add(new Fields(i, root.get(i)).record(project, since));
        }
        return records;
    }

    /** Reads one record, naming it and the field in every refusal, never a value. */
    private static final class Fields {

        private final int index;

        private final JsonNode record;

        private @Nullable String task;

        Fields(final int index, final JsonNode record) {
            this.index = index;
            this.record = record;
        }

        TaskRecord record(final String project, final Instant since) {
            if (!record.isObject()) {
                throw refused("", "is no object");
            }
            task = text(record, "task", "task");
            final String named = text(record, "project", "project");
            if (!named.equals(project)) {
                throw refused("project", "names another project than " + project);
            }
            final String repository = textOrNull(record, "repository", "repository");
            final String issue = work();
            textOrNull(record, "agent", "agent");
            textOrNull(record, "model", "model");
            text(record, "modelFrom", "modelFrom");
            textOrNull(record, "effort", "effort");
            final Instant startedAt = instant(record, "startedAt", "startedAt");
            final Instant endedAt = instantOrNull(record, "endedAt", "endedAt");
            if (endedAt != null && endedAt.isBefore(since)) {
                throw refused("endedAt", "is before " + since + ", the time asked");
            }
            final String end = oneOfOrNull(record, "end", "end", ENDS);
            totals();
            oneOf(record, "toolCallsAsked", "toolCallsAsked", ASKED);
            final List<TaskRecord.Call> calls = calls();
            try {
                return new TaskRecord(
                        task,
                        named,
                        repository,
                        issue,
                        startedAt,
                        endedAt,
                        end == null ? null : TaskRecord.End.valueOf(end.toUpperCase(Locale.ROOT)),
                        calls);
            } catch (final IllegalArgumentException ex) {
                throw new Unanswered(
                        where() + " cannot be a task's record: " + ex.getMessage(), ex);
            }
        }

        private @Nullable String work() {
            final JsonNode work = present(record, "work", "work");
            if (work.isNull()) {
                return null;
            }
            if (!work.isObject()) {
                throw refused("work", "is neither null nor an object");
            }
            final String issue = text(work, "issue", "work.issue");
            if (!ISSUE.matcher(issue).matches()) {
                throw refused("work.issue", "is no issue id (" + ISSUE.pattern() + ")");
            }
            final String phase = text(work, "phase", "work.phase");
            final int colon = phase.indexOf(':');
            if (phase.length() > 64
                    || colon < 0
                    || phase.substring(0, colon).isBlank()
                    || phase.substring(colon + 1).isBlank()) {
                throw refused(
                        "work.phase",
                        "is no phase (two non-blank halves around a colon, at most 64 characters)");
            }
            return issue;
        }

        private void totals() {
            final JsonNode totals = present(record, "totals", "totals");
            if (!totals.isObject()) {
                throw refused("totals", "is no object");
            }
            count(totals, "requests", "totals.requests");
            for (final String kind : TOKEN_KINDS) {
                final String path = "totals." + kind;
                final JsonNode tokens = present(totals, kind, path);
                if (!tokens.isObject()) {
                    throw refused(path, "is no object");
                }
                if (!present(tokens, "tokens", path + ".tokens").isNull()) {
                    count(tokens, "tokens", path + ".tokens");
                }
                count(tokens, "missing", path + ".missing");
            }
        }

        private List<TaskRecord.Call> calls() {
            final JsonNode calls = present(record, "toolCalls", "toolCalls");
            if (!calls.isArray()) {
                throw refused("toolCalls", "is no array");
            }
            final List<TaskRecord.Call> result = new ArrayList<>(calls.size());
            for (int i = 0; i < calls.size(); i++) {
                result.add(call(calls.get(i), "toolCalls[" + i + "]"));
            }
            return result;
        }

        private TaskRecord.Call call(final JsonNode call, final String path) {
            if (!call.isObject()) {
                throw refused(path, "is no object");
            }
            text(call, "task", path + ".task");
            text(call, "tool", path + ".tool");
            final String kind = oneOf(call, "kind", path + ".kind", KINDS);
            final Instant start = instant(call, "start", path + ".start");
            oneOf(call, "startFrom", path + ".startFrom", SOURCES);
            final Instant end = instantOrNull(call, "end", path + ".end");
            final String endFrom = oneOfOrNull(call, "endFrom", path + ".endFrom", SOURCES);
            if ((end == null) != (endFrom == null)) {
                throw refused(
                        path + ".endFrom", "is null where end is not, or the other way round");
            }
            if ("shell".equals(kind)) {
                text(call, "command", path + ".command");
            } else if (call.has("command") && !call.get("command").isNull()) {
                throw refused(path + ".command", "is given for a call that is no shell call");
            }
            if (!present(call, "background", path + ".background").isBoolean()) {
                throw refused(path + ".background", "is no boolean");
            }
            final String issue = textOrNull(call, "issue", path + ".issue");
            if (issue != null && !ISSUE.matcher(issue).matches()) {
                throw refused(path + ".issue", "is no issue id (" + ISSUE.pattern() + ")");
            }
            try {
                return new TaskRecord.Call(issue, start, end);
            } catch (final IllegalArgumentException ex) {
                throw refused(path, "cannot be a call: " + ex.getMessage());
            }
        }

        private JsonNode present(final JsonNode object, final String name, final String path) {
            final JsonNode value = object.get(name);
            if (value == null) {
                throw refused(path, "is missing");
            }
            return value;
        }

        private String text(final JsonNode object, final String name, final String path) {
            final JsonNode value = present(object, name, path);
            if (!value.isTextual()) {
                throw refused(path, "is no string");
            }
            return value.textValue();
        }

        private @Nullable String textOrNull(
                final JsonNode object, final String name, final String path) {
            final JsonNode value = present(object, name, path);
            if (value.isNull()) {
                return null;
            }
            if (!value.isTextual()) {
                throw refused(path, "is neither null nor a string");
            }
            return value.textValue();
        }

        private String oneOf(
                final JsonNode object,
                final String name,
                final String path,
                final Set<String> allowed) {
            final String value = text(object, name, path);
            if (!allowed.contains(value)) {
                throw refused(path, "is none of " + allowed);
            }
            return value;
        }

        private @Nullable String oneOfOrNull(
                final JsonNode object,
                final String name,
                final String path,
                final Set<String> allowed) {
            final String value = textOrNull(object, name, path);
            if (value != null && !allowed.contains(value)) {
                throw refused(path, "is neither null nor one of " + allowed);
            }
            return value;
        }

        private Instant instant(final JsonNode object, final String name, final String path) {
            return utc(text(object, name, path), path);
        }

        private @Nullable Instant instantOrNull(
                final JsonNode object, final String name, final String path) {
            final String value = textOrNull(object, name, path);
            return value == null ? null : utc(value, path);
        }

        private Instant utc(final String value, final String path) {
            // Instant.parse also takes an offset, which the contract does not allow.
            if (!value.endsWith("Z")) {
                throw refused(path, "is no UTC instant ending in Z");
            }
            try {
                return Instant.parse(value);
            } catch (final DateTimeParseException ex) {
                throw refused(path, "is no UTC instant ending in Z");
            }
        }

        private void count(final JsonNode object, final String name, final String path) {
            final JsonNode value = present(object, name, path);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
                throw refused(path, "is no whole number of at least zero");
            }
        }

        private Unanswered refused(final String path, final String what) {
            return new Unanswered(where() + (path.isEmpty() ? "" : " field " + path) + " " + what);
        }

        private String where() {
            final String name = task;
            final String shown =
                    name == null
                            ? ""
                            : " (task "
                                    + escaped(
                                            name.length() > 64
                                                    ? name.substring(0, 64) + "..."
                                                    : name)
                                    + ")";
            return "Porelo's task record " + index + shown;
        }
    }

    /** Runs a command as a process of its own, without a shell, within a time limit. */
    static final class ProcessRunner implements Runner {

        private final Duration limit;

        /**
         * @param limit how long the process may run before it and its children are killed
         */
        ProcessRunner(final Duration limit) {
            this.limit = limit;
        }

        @Override
        public Result run(final List<String> command, final Map<String, String> environment) {
            final ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().clear();
            builder.environment().putAll(environment);
            builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
            final Process process;
            try {
                process = builder.start();
            } catch (final IOException ex) {
                throw new Unanswered(
                        "Porelo's executable cannot be started at " + command.getFirst(), ex);
            }
            final long deadline = System.nanoTime() + limit.toNanos();
            final CompletableFuture<Capture> out = drain(process.getInputStream(), ANSWER_LIMIT);
            final CompletableFuture<Capture> err = drain(process.getErrorStream(), ERROR_LIMIT);
            try {
                if (!process.waitFor(limit.toNanos(), TimeUnit.NANOSECONDS)) {
                    kill(process);
                    throw new Unanswered(
                            "Porelo's task records gave no answer within "
                                    + limit.toSeconds()
                                    + " s");
                }
                final long left = Math.max(0, deadline - System.nanoTime());
                final Capture answer = out.get(left, TimeUnit.NANOSECONDS);
                final Capture errors =
                        err.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                return new Result(
                        process.exitValue(),
                        answer.bytes(),
                        answer.cutOff(),
                        new String(errors.bytes(), StandardCharsets.UTF_8));
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                kill(process);
                throw new Unanswered("Reading Porelo's task records was interrupted", ex);
            } catch (final TimeoutException ex) {
                kill(process);
                throw new Unanswered(
                        "Porelo's task records gave no answer within " + limit.toSeconds() + " s",
                        ex);
            } catch (final ExecutionException ex) {
                kill(process);
                throw new Unanswered("Porelo's answer could not be read", ex);
            } finally {
                out.cancel(true);
                err.cancel(true);
            }
        }

        private static void kill(final Process process) {
            // Children first, while they can still be found as the process's descendants.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }

        private static CompletableFuture<Capture> drain(final InputStream stream, final int max) {
            final CompletableFuture<Capture> result = new CompletableFuture<>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    result.complete(read(stream, max));
                                } catch (final IOException | RuntimeException ex) {
                                    result.completeExceptionally(ex);
                                }
                            });
            return result;
        }

        private static Capture read(final InputStream stream, final int max) throws IOException {
            try (stream) {
                final ByteArrayOutputStream kept = new ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                boolean cutOff = false;
                int n;
                // Reads past the limit too, discarding it, so a full pipe never blocks the child.
                while ((n = stream.read(buffer)) >= 0) {
                    final int room = max - kept.size();
                    if (n > room) {
                        cutOff = true;
                    }
                    kept.write(buffer, 0, Math.min(n, room));
                }
                return new Capture(kept.toByteArray(), cutOff);
            }
        }

        private record Capture(byte[] bytes, boolean cutOff) {}
    }
}
