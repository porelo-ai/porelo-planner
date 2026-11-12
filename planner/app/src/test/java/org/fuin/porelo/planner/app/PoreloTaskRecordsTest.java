package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.fuin.porelo.planner.app.PoreloTaskRecords.ProcessRunner;
import org.fuin.porelo.planner.app.PoreloTaskRecords.Result;
import org.fuin.porelo.planner.app.PoreloTaskRecords.Runner;
import org.fuin.porelo.planner.core.TaskRecord;
import org.fuin.porelo.planner.core.TaskRecords.Unanswered;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Porelo's task records, read from its executable's answer and checked field by field. */
class PoreloTaskRecordsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Path PORELO = Path.of("/opt/porelo/bin/porelo");

    private static final Instant SINCE = Instant.parse("2026-11-01T00:00:00Z");

    private static final String SECRET = "curl -H 'Authorization: token ghp_SECRET123'";

    @TempDir Path dir;

    /** The answer the fake runner gives, and what it was asked. */
    private Result answer = new Result(0, "[]".getBytes(StandardCharsets.UTF_8), false, "");

    private final List<List<String>> commands = new ArrayList<>();

    private final List<Map<String, String>> environments = new ArrayList<>();

    private final Runner fake =
            (command, environment) -> {
                commands.add(command);
                environments.add(environment);
                return answer;
            };

    // Prevents the velocity from reading a task's times, end or issues other than Porelo recorded.
    @Test
    void aWholeRecordInPorelosFormIsReadWithEveryValue() throws Exception {
        answer(JSON.createArrayNode().add(record()));

        final List<TaskRecord> records = records().since("porelo-planner", SINCE);

        assertThat(records)
                .as("the one record, mapped")
                .containsExactly(
                        new TaskRecord(
                                "t-1",
                                "porelo-planner",
                                "porelo-planner",
                                "EX12",
                                Instant.parse("2026-11-02T10:00:00Z"),
                                Instant.parse("2026-11-02T12:00:00.123456789Z"),
                                TaskRecord.End.FAILED,
                                List.of(
                                        new TaskRecord.Call(
                                                null,
                                                Instant.parse("2026-11-02T10:05:00Z"),
                                                Instant.parse("2026-11-02T10:06:00.5Z")),
                                        new TaskRecord.Call(
                                                "EX13",
                                                Instant.parse("2026-11-02T11:00:00Z"),
                                                null))));
    }

    // Prevents a secret of the planner's environment, or a shell, from reaching Porelo.
    @Test
    void porelosIsAskedWithEachArgumentItsOwnAndOnlyThePassedEnvironment() {
        records().since("porelo-planner", SINCE);

        assertThat(commands)
                .as("one run, each argument its own element")
                .containsExactly(
                        List.of(
                                PORELO.toString(),
                                "task",
                                "records",
                                "-p",
                                "porelo-planner",
                                "--since",
                                "2026-11-01T00:00:00Z",
                                "--json"));
        assertThat(environments.getFirst())
                .as("only PATH, HOME, LANG and the XDG directories")
                .isEqualTo(Map.of("PATH", "/usr/bin", "HOME", "/home/user"));
    }

    // Prevents a velocity from counting tasks Porelo never answered.
    @Test
    void anEmptyAnswerGivesNoRecords() {
        assertThat(records().since("porelo-planner", SINCE)).as("no record").isEmpty();
    }

    // Prevents a project name from being read by Porelo as an option.
    @Test
    void aProjectThatIsNoRepositoryNameIsRefusedBeforePoreloRuns() {
        assertThatThrownBy(() -> records().since("--all", SINCE))
                .as("a dash-led project")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).as("Porelo not run").isEmpty();
    }

    // Prevents an executable from being found through the working directory.
    @Test
    void aRelativeExecutableIsRefused() {
        assertThatThrownBy(
                        () ->
                                new PoreloTaskRecords(
                                        Path.of("porelo"), fake, Map.of("PATH", "/usr/bin")))
                .as("a relative path")
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> fields() {
        final List<String> paths =
                new ArrayList<>(
                        List.of(
                                "task",
                                "project",
                                "repository",
                                "work",
                                "work.issue",
                                "work.phase",
                                "agent",
                                "model",
                                "modelFrom",
                                "effort",
                                "startedAt",
                                "endedAt",
                                "end",
                                "totals",
                                "totals.requests",
                                "toolCallsAsked",
                                "toolCalls"));
        for (final String kind :
                List.of("input", "cacheRead", "cacheWrite", "output", "reasoning")) {
            paths.addAll(
                    List.of(
                            "totals." + kind,
                            "totals." + kind + ".tokens",
                            "totals." + kind + ".missing"));
        }
        for (final String field :
                List.of(
                        "task",
                        "tool",
                        "kind",
                        "start",
                        "startFrom",
                        "end",
                        "endFrom",
                        "command",
                        "background",
                        "issue")) {
            paths.add("toolCalls[0]." + field);
        }
        return paths.stream();
    }

    // Prevents a record Porelo wrote in another form from being read as if it held the agreed one.
    @ParameterizedTest
    @MethodSource("fields")
    void aMissingFieldIsRefusedWithItsName(final String path) throws Exception {
        final ObjectNode record = record();
        ((ObjectNode) parent(record, path)).remove(last(path));
        answer(JSON.createArrayNode().add(record));

        assertThat(refusal())
                .as("the record and the field named")
                .contains("record 0")
                .contains("field " + path + " is missing");
    }

    static Stream<Arguments> mistyped() {
        return Stream.of(
                Arguments.of("task", NODES.numberNode(7)),
                Arguments.of("project", NODES.nullNode()),
                Arguments.of("repository", NODES.numberNode(7)),
                Arguments.of("work", NODES.textNode("EX12")),
                Arguments.of("work.issue", NODES.textNode("pl61")),
                Arguments.of("work.issue", NODES.nullNode()),
                Arguments.of("work.phase", NODES.textNode("build")),
                Arguments.of("work.phase", NODES.textNode(" :implement")),
                Arguments.of("work.phase", NODES.textNode("build: ")),
                Arguments.of("work.phase", NODES.textNode("build:" + "x".repeat(59))),
                Arguments.of("agent", NODES.booleanNode(true)),
                Arguments.of("model", NODES.numberNode(7)),
                Arguments.of("modelFrom", NODES.nullNode()),
                Arguments.of("effort", NODES.numberNode(3)),
                Arguments.of("startedAt", NODES.nullNode()),
                Arguments.of("startedAt", NODES.textNode("2026-11-02T10:00:00+01:00")),
                Arguments.of("startedAt", NODES.textNode("yesterdayZ")),
                Arguments.of("endedAt", NODES.textNode("2026-11-02T12:00:00+00:00")),
                Arguments.of("end", NODES.textNode("finished")),
                Arguments.of("totals", NODES.arrayNode()),
                Arguments.of("totals.requests", NODES.textNode("7")),
                Arguments.of("totals.requests", NODES.numberNode(-1)),
                Arguments.of("totals.requests", NODES.numberNode(1.5)),
                Arguments.of("totals.requests", NODES.nullNode()),
                Arguments.of("totals.input", NODES.nullNode()),
                Arguments.of("totals.cacheRead.tokens", NODES.numberNode(-1)),
                Arguments.of("totals.reasoning.missing", NODES.nullNode()),
                Arguments.of("toolCallsAsked", NODES.textNode("YES")),
                Arguments.of("toolCalls", NODES.objectNode()),
                Arguments.of("toolCalls[0]", NODES.textNode("call")),
                Arguments.of("toolCalls[0].task", NODES.nullNode()),
                Arguments.of("toolCalls[0].tool", NODES.numberNode(7)),
                Arguments.of("toolCalls[0].kind", NODES.textNode("browser")),
                Arguments.of("toolCalls[0].start", NODES.textNode("2026-11-02T10:05:00+01:00")),
                Arguments.of("toolCalls[0].startFrom", NODES.textNode("guess")),
                Arguments.of("toolCalls[0].startFrom", NODES.nullNode()),
                Arguments.of("toolCalls[0].end", NODES.numberNode(7)),
                Arguments.of("toolCalls[0].endFrom", NODES.textNode("guess")),
                Arguments.of("toolCalls[0].command", NODES.numberNode(7)),
                Arguments.of("toolCalls[0].background", NODES.textNode("true")),
                Arguments.of("toolCalls[0].issue", NODES.textNode("EX-12")));
    }

    // Prevents a value of the wrong type or shape from being read as a time, an end or an issue.
    @ParameterizedTest
    @MethodSource("mistyped")
    void aMistypedFieldIsRefusedWithItsName(final String path, final JsonNode value)
            throws Exception {
        final ObjectNode record = record();
        set(parent(record, path), last(path), value);
        answer(JSON.createArrayNode().add(record));

        assertThat(refusal())
                .as("the record, its task and the field named")
                .contains("task".equals(path) ? "record 0 " : "record 0 (task t-1)")
                .contains("field " + path + " ");
    }

    // Prevents another project's tasks from being counted as this project's work.
    @Test
    void aRecordOfAnotherProjectFailsTheWholeAnswer() throws Exception {
        final ObjectNode other = record();
        other.put("project", "porelo-window");
        answer(JSON.createArrayNode().add(record()).add(other));

        assertThat(refusal())
                .as("the second record's project")
                .contains("record 1", "field project");
    }

    // Prevents a call's end from being taken without Porelo saying how it measured it.
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void aCallsEndAndItsSourceComeTogether(final int call) throws Exception {
        final ObjectNode record = record();
        final ObjectNode node = (ObjectNode) record.get("toolCalls").get(call);
        if (call == 0) {
            node.putNull("endFrom");
        } else {
            node.put("endFrom", "stream");
        }
        answer(JSON.createArrayNode().add(record));

        assertThat(refusal())
                .as("the call's endFrom")
                .contains("field toolCalls[" + call + "].endFrom");
    }

    // Prevents a shell command, which may hold a secret, from reaching a message or a log.
    @Test
    void aCommandOnACallThatIsNoShellCallIsRefusedWithoutItsValue() throws Exception {
        final ObjectNode record = record();
        ((ObjectNode) record.get("toolCalls").get(1)).put("command", SECRET);
        answer(JSON.createArrayNode().add(record));

        assertThat(refusal())
                .as("named, never shown")
                .contains("field toolCalls[1].command")
                .doesNotContain("ghp_SECRET123");
    }

    // Prevents an end time without how the task ended from reaching the velocity.
    @Test
    void aRecordTheCoreRefusesFailsTheAnswer() throws Exception {
        final ObjectNode record = record();
        record.putNull("end");
        answer(JSON.createArrayNode().add(record));

        assertThat(refusal()).as("the record named").contains("record 0 (task t-1)");
    }

    // Prevents a doubled key, of which a reader may take either value, from being trusted.
    @Test
    void aDuplicateKeyFailsTheAnswerWithoutShowingIt() throws Exception {
        final String text = JSON.writeValueAsString(JSON.createArrayNode().add(record()));
        answer(text.replace("\"command\":", "\"command\":\"" + SECRET + "\",\"command\":"));

        assertThat(refusal())
                .as("no single JSON document, the command not shown")
                .contains("no single JSON document")
                .doesNotContain("ghp_SECRET123");
    }

    // Prevents a second answer appended to the first from going unread or unnoticed.
    @Test
    void twoArraysInOneAnswerFailIt() {
        answer("[][]");

        assertThat(refusal()).as("trailing tokens").contains("no single JSON document");
    }

    // Prevents an answer that is no array from being read as no records.
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", ""})
    void anAnswerThatIsNoArrayFails(final String text) {
        answer(text);

        assertThat(refusal()).as("no array").contains("Porelo's task records are no");
    }

    // Prevents unreadable records from being taken as no records.
    @Test
    void exit74SaysPoreloCannotReadItsRecords() {
        answer = new Result(74, "[]".getBytes(StandardCharsets.UTF_8), false, "");

        assertThat(refusal()).as("the reason").isEqualTo("Porelo cannot read its task records");
    }

    // Prevents a refused time from being taken as no records.
    @Test
    void exit64SaysPoreloRefusedTheTime() {
        answer = new Result(64, "[]".getBytes(StandardCharsets.UTF_8), false, "");

        assertThat(refusal()).as("the reason").contains("refused the time");
    }

    // Prevents an unknown failure from being taken as no records, or what Porelo wrote on its
    // error output, a command or a token among it, from reaching a message or a log.
    @Test
    void anotherExitCodeFailsWithItsCodeAndNothingOfItsErrorOutput() {
        answer =
                new Result(
                        3,
                        "[]".getBytes(StandardCharsets.UTF_8),
                        false,
                        SECRET + "\u001b[31m here\nsecond line\n");

        assertThat(refusal())
                .as("the code only")
                .contains("exit code 3")
                .doesNotContain("ghp_SECRET123")
                .doesNotContain("\u001b")
                .doesNotContain("second line");
    }

    // Prevents Porelo's naming an effort from failing every answer and so the velocity.
    @Test
    void anEffortNamedIsRead() throws Exception {
        final ObjectNode record = record();
        record.put("effort", "high");
        answer(JSON.createArrayNode().add(record));

        assertThat(records().since("porelo-planner", SINCE)).as("read").hasSize(1);
    }

    // Prevents a call that is no shell call and says so with a null command from failing every
    // answer.
    @Test
    void aNullCommandOnACallThatIsNoShellCallIsRead() throws Exception {
        final ObjectNode record = record();
        ((ObjectNode) record.get("toolCalls").get(1)).putNull("command");
        answer(JSON.createArrayNode().add(record));

        assertThat(records().since("porelo-planner", SINCE)).as("read").hasSize(1);
    }

    // Prevents a task that ended before the time asked from being counted as if it were asked
    // for, or a second time by a caller that reads the earlier time apart.
    @Test
    void aTaskThatEndedBeforeTheTimeAskedFailsTheAnswer() throws Exception {
        final ObjectNode record = record();
        record.put("startedAt", "2026-10-30T10:00:00Z");
        record.put("endedAt", "2026-10-31T23:59:59Z");
        answer(JSON.createArrayNode().add(record));

        assertThat(refusal())
                .as("the record and field named")
                .contains("record 0 (task t-1)")
                .contains("field endedAt ");
    }

    // Prevents a cut-off answer from being read as fewer records.
    @Test
    void aCutOffAnswerFails() {
        answer = new Result(0, "[]".getBytes(StandardCharsets.UTF_8), true, "");

        assertThat(refusal()).as("the reason").contains("cut off");
    }

    // Prevents the arguments, the environment or the exit code of a real process from differing
    // from what the fakes stand in for.
    @Test
    void aRealProcessGetsTheArgumentsOnlyThePassedEnvironmentAndItsExitCodeIsRead()
            throws Exception {
        final Path porelo =
                script(
                        "printf '%s\\n' \"$@\" > '"
                                + dir.resolve("args")
                                + "'\n/usr/bin/env > '"
                                + dir.resolve("env")
                                + "'\nprintf '[]'\n");
        final Map<String, String> source =
                Map.of(
                        "PATH", "/usr/bin:/bin",
                        "HOME", dir.toString(),
                        "LANG", "C.UTF-8",
                        "XDG_STATE_HOME", dir.resolve("state").toString(),
                        "GITHUB_TOKEN", "ghp_SECRET123",
                        "SSH_AUTH_SOCK", "/run/agent.sock");
        final PoreloTaskRecords records =
                new PoreloTaskRecords(porelo, new ProcessRunner(Duration.ofSeconds(30)), source);

        assertThat(records.since("porelo-planner", SINCE)).as("no record").isEmpty();
        assertThat(Files.readAllLines(dir.resolve("args")))
                .as("each argument as given")
                .containsExactly(
                        "task",
                        "records",
                        "-p",
                        "porelo-planner",
                        "--since",
                        SINCE.toString(),
                        "--json");
        final Set<String> names =
                Files.readAllLines(dir.resolve("env")).stream()
                        .map(line -> line.substring(0, line.indexOf('=')))
                        .filter(name -> !Set.of("PWD", "OLDPWD", "SHLVL", "_").contains(name))
                        .collect(Collectors.toSet());
        assertThat(names)
                .as("only the passed names, besides what the shell sets itself")
                .isEqualTo(Set.of("PATH", "HOME", "LANG", "XDG_STATE_HOME"));

        final Path failing = script("echo 'it broke' >&2\nexit 3\n");
        assertThatThrownBy(
                        () ->
                                new PoreloTaskRecords(
                                                failing,
                                                new ProcessRunner(Duration.ofSeconds(30)),
                                                source)
                                        .since("porelo-planner", SINCE))
                .as("the process's own exit code")
                .isInstanceOf(Unanswered.class)
                .hasMessageContaining("exit code 3")
                .hasMessageNotContaining("it broke");
    }

    // Prevents a hanging Porelo, or a child it left running, from holding the planner forever.
    @Test
    void aProcessPastTheLimitIsKilledWithItsChildren() throws Exception {
        final Path pid = dir.resolve("pid");
        final Path porelo = script("/bin/sleep 60 &\necho $! > '" + pid + "'\nwait\n");
        final long start = System.nanoTime();

        final Unanswered refused =
                catchThrowableOfType(
                        Unanswered.class,
                        () ->
                                new PoreloTaskRecords(
                                                porelo,
                                                new ProcessRunner(Duration.ofSeconds(1)),
                                                Map.of("PATH", "/usr/bin:/bin"))
                                        .since("porelo-planner", SINCE));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.getMessage()).as("the reason").contains("no answer within 1 s");
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("the limit, not the child's sleep")
                .isLessThan(Duration.ofSeconds(20));
        final long child = Long.parseLong(Files.readString(pid).strip());
        final boolean gone =
                ProcessHandle.of(child)
                        .map(
                                handle -> {
                                    try {
                                        handle.onExit().get(10, TimeUnit.SECONDS);
                                        return true;
                                    } catch (final Exception ex) {
                                        return false;
                                    }
                                })
                        .orElse(true);
        assertThat(gone).as("the child killed too").isTrue();
    }

    // Prevents an answer past the limit from filling the planner's memory or being read in part.
    @Test
    void aRealAnswerPastTheLimitIsCutOff() throws Exception {
        final Path porelo =
                script("/usr/bin/head -c " + (PoreloTaskRecords.ANSWER_LIMIT + 1) + " /dev/zero\n");

        assertThatThrownBy(
                        () ->
                                new PoreloTaskRecords(
                                                porelo,
                                                new ProcessRunner(Duration.ofSeconds(30)),
                                                Map.of())
                                        .since("porelo-planner", SINCE))
                .as("cut off")
                .isInstanceOf(Unanswered.class)
                .hasMessageContaining("cut off");
    }

    // Prevents a missing executable from being taken as no records.
    @Test
    void aMissingExecutableFailsNamingIt() {
        final Path missing = dir.resolve("porelo");

        assertThatThrownBy(
                        () ->
                                new PoreloTaskRecords(
                                                missing,
                                                new ProcessRunner(Duration.ofSeconds(30)),
                                                Map.of())
                                        .since("porelo-planner", SINCE))
                .as("not started")
                .isInstanceOf(Unanswered.class)
                .hasMessageContaining("cannot be started at " + missing);
    }

    private PoreloTaskRecords records() {
        return new PoreloTaskRecords(
                PORELO,
                fake,
                Map.of(
                        "PATH", "/usr/bin",
                        "HOME", "/home/user",
                        "GITHUB_TOKEN", "ghp_SECRET123",
                        "SSH_AUTH_SOCK", "/run/agent.sock"));
    }

    private String refusal() {
        final Unanswered refused =
                catchThrowableOfType(
                        Unanswered.class, () -> records().since("porelo-planner", SINCE));
        assertThat(refused).as("the answer refused").isNotNull();
        return refused.getMessage();
    }

    private void answer(final JsonNode json) throws Exception {
        answer(JSON.writeValueAsString(json));
    }

    private void answer(final String text) {
        answer = new Result(0, text.getBytes(StandardCharsets.UTF_8), false, "");
    }

    private Path script(final String body) throws Exception {
        final Path script = Files.createTempFile(dir, "porelo", ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    /** One record as Porelo writes it, a shell call and a sub-agent's running call. */
    private static ObjectNode record() throws Exception {
        return (ObjectNode)
                JSON.readTree(
                        """
                        {
                          "task": "t-1",
                          "project": "porelo-planner",
                          "repository": "porelo-planner",
                          "work": {"issue": "EX12", "phase": "build:implement"},
                          "agent": "claude",
                          "model": "claude-opus",
                          "modelFrom": "stream",
                          "effort": null,
                          "startedAt": "2026-11-02T10:00:00Z",
                          "endedAt": "2026-11-02T12:00:00.123456789Z",
                          "end": "failed",
                          "totals": {
                            "requests": 12,
                            "input": {"tokens": 1000, "missing": 0},
                            "cacheRead": {"tokens": null, "missing": 12},
                            "cacheWrite": {"tokens": 0, "missing": 0},
                            "output": {"tokens": 300, "missing": 1},
                            "reasoning": {"tokens": null, "missing": 12}
                          },
                          "toolCallsAsked": "ASKED",
                          "unknownExtra": {"ignored": true},
                          "toolCalls": [
                            {"task": "t-1", "tool": "Bash", "kind": "shell",
                             "start": "2026-11-02T10:05:00Z", "startFrom": "stream",
                             "end": "2026-11-02T10:06:00.5Z", "endFrom": "duration",
                             "command": "*** masked ***", "background": false, "issue": null},
                            {"task": "t-1", "tool": "Agent", "kind": "sub-agent",
                             "start": "2026-11-02T11:00:00Z", "startFrom": "arrival",
                             "end": null, "endFrom": null, "background": true, "issue": "EX13"}
                          ]
                        }
                        """);
    }

    private static JsonNode parent(final ObjectNode record, final String path) {
        JsonNode node = record;
        final String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            node = step(node, parts[i]);
        }
        final String lastPart = parts[parts.length - 1];
        return lastPart.endsWith("]")
                ? node.get(lastPart.substring(0, lastPart.indexOf('[')))
                : node;
    }

    private static JsonNode step(final JsonNode node, final String part) {
        final int bracket = part.indexOf('[');
        if (bracket < 0) {
            return node.get(part);
        }
        return node.get(part.substring(0, bracket))
                .get(Integer.parseInt(part.substring(bracket + 1, part.length() - 1)));
    }

    private static String last(final String path) {
        final String part = path.substring(path.lastIndexOf('.') + 1);
        final int bracket = part.indexOf('[');
        return bracket < 0 ? part : part.substring(bracket + 1, part.length() - 1);
    }

    private static void set(final JsonNode parent, final String name, final JsonNode value) {
        if (parent instanceof ArrayNode array) {
            array.set(Integer.parseInt(name), value);
        } else {
            ((ObjectNode) parent).set(name, value);
        }
    }
}
