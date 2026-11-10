package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitRunnerTest {

    private static final GitRunner RUNNER = new GitRunner(Duration.ofSeconds(2), 1024);

    private static GitCall shell(final String script, final String... arguments) {
        final List<String> line = new ArrayList<>(List.of("/bin/sh", "-c", script));
        line.addAll(List.of(arguments));
        return new GitCall(line, Map.of("PATH", "/usr/bin:/bin", "MARK", "set"));
    }

    @Test
    void aCallThatHangsIsKilledWithItsChildrenAndReadsAsUnreachable(@TempDir final Path dir)
            throws Exception {
        // A remote that never answers would hold the board and leave a process behind on every
        // check.
        final Path pid = dir.resolve("pid");
        final long start = System.nanoTime();
        assertThatThrownBy(() -> RUNNER.run(shell("sleep 30 & echo $! > " + pid + "; wait"), null))
                .as("a call over its limit")
                .isInstanceOf(GitFailure.class)
                .satisfies(
                        e ->
                                assertThat(((GitFailure) e).kind())
                                        .isEqualTo(GitFailure.Kind.UNREACHABLE));
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("the call ends at its limit, not at the child's end")
                .isLessThan(Duration.ofSeconds(10));
        final long child = Long.parseLong(Files.readString(pid).strip());
        Thread.sleep(200);
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false))
                .as("the call's child is gone too")
                .isFalse();
    }

    @Test
    void aCallReadsNoInputSoItCannotWaitOnOne() {
        // A prompt that slipped through would read from the planner's own input and hang.
        final byte[] out = RUNNER.run(shell("read answer; echo \"got:$answer\""), null);
        assertThat(new String(out, StandardCharsets.UTF_8))
                .as("the read ends at once with nothing")
                .isEqualTo("got:\n");
    }

    @Test
    void aCallThatPrintsWithoutEndIsStopped() {
        // A hostile remote's endless answer would fill the planner's memory.
        assertThatThrownBy(() -> RUNNER.run(shell("yes"), null))
                .as("output over the bound")
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("more than 1024 bytes");
    }

    @Test
    void aCallSeesOnlyTheEnvironmentItWasGiven() {
        final String out = new String(RUNNER.run(shell("env"), null), StandardCharsets.UTF_8);
        assertThat(out)
                .as("the planner's own environment does not leak into the call")
                .contains("MARK=set")
                .doesNotContain("HOME=");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "fatal: could not read Username for 'https://git.example.org': terminal prompts disabled",
                "fatal: could not read Password for 'https://person@git.example.org': terminal prompts disabled",
                "fatal: Authentication failed for 'https://git.example.org/r.git/'",
                "git@git.example.org: Permission denied (publickey).",
                "Host key verification failed.",
                "fatal: unable to get password from user",
                "remote: Invalid username or password.",
                "fatal: unable to access 'https://git.example.org/r.git/': The requested URL returned error: 403",
                "fatal: unable to access 'https://git.example.org/r.git/': The requested URL returned error: 401"
            })
    void aRefusedLoginIsToldFromOtherFailures(final String message) {
        // The window asks the person for a login only when that is what is missing.
        assertThatThrownBy(
                        () ->
                                RUNNER.run(
                                        shell("printf '%s\\n' \"$0\" >&2; exit 128", message),
                                        null))
                .as("Git's answer '%s'", message)
                .satisfies(
                        e -> assertThat(((GitFailure) e).kind()).isEqualTo(GitFailure.Kind.LOGIN));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "fatal: couldn't find remote ref main",
                "fatal: unable to access 'https://git.example.org/r.git/': Could not resolve host: git.example.org",
                "fatal: repository 'https://git.example.org/r.git/' not found"
            })
    void anotherFailureIsNotTakenForARefusedLogin(final String message) {
        assertThatThrownBy(
                        () ->
                                RUNNER.run(
                                        shell("printf '%s\\n' \"$0\" >&2; exit 128", message),
                                        null))
                .as("Git's answer '%s'", message)
                .satisfies(
                        e -> assertThat(((GitFailure) e).kind()).isEqualTo(GitFailure.Kind.FAILED));
    }

    @Test
    void aSecretGitEchoesNeverReachesTheFailure() {
        // The failure's text goes to the window and to the log.
        assertThatThrownBy(
                        () ->
                                RUNNER.run(
                                        shell(
                                                "echo \"fatal: unable to access"
                                                        + " 'https://person:s3cr3t@git.example.org/r.git/':"
                                                        + " token ghp_ABCdef123 Authorization: Bearer"
                                                        + " abc.def\" >&2; exit 128"),
                                        null))
                .as("a failure quoting a URL with a password, a token and a header")
                .hasMessageNotContaining("s3cr3t")
                .hasMessageNotContaining("ABCdef123")
                .hasMessageNotContaining("abc.def")
                .hasMessageContaining("https://***git.example.org");
    }
}
