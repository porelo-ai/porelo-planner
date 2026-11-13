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
import org.junit.jupiter.params.provider.CsvSource;
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
    void aCallFedAnInputGetsAllOfItAndAnswersWhileItReads() {
        // A lost input answers other files; an input written before the answer is read hangs on a
        // full pipe.
        // More than a pipe holds, even grown to the kernel's usual 1 MiB maximum.
        final byte[] input = new byte[8 << 20];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (i * 31 + i / 7);
        }
        final GitRunner runner = new GitRunner(Duration.ofSeconds(10), 16 << 20);
        assertThat(runner.run(shell("cat"), null, input))
                .as("the whole input, byte for byte, through a call printing as it reads")
                .isEqualTo(input);
        assertThat(new String(RUNNER.run(shell("cat"), null, new byte[0]), StandardCharsets.UTF_8))
                .as("an empty input ends at once")
                .isEmpty();
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

    // Prevents a credential in any clone URL shape Git echoes from reaching the window or the log.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "me:s3cret@git.example.org:acme/x.git | s3cret",
                "https://me:pa@ssword@git.example.org/acme/x.git | ssword",
                "https://me:pa/ssword@git.example.org/acme/x.git | ssword",
                "https://ghp_x@git.example.org/acme/x.git | ghp_x",
                "https://my-token@git.example.org/acme/x.git | my-token",
                "https://git.example.org/acme/x.git?access_token=s3cret | s3cret"
            })
    void aCredentialInAnyUrlShapeGitEchoesNeverReachesTheFailure(
            final String url, final String secret) {
        assertThatThrownBy(
                        () ->
                                RUNNER.run(
                                        shell(
                                                "echo \"fatal: unable to access '$1': refused\" >&2;"
                                                        + " exit 128",
                                                "sh",
                                                url),
                                        null))
                .as("a failure quoting %s", url)
                .hasMessageContaining("git.example.org")
                .hasMessageNotContaining(secret);
    }

    @Test
    void aLocalPathGitEchoesNeverReachesTheFailure() {
        // The failure's text goes to the window, which has no business with the person's files.
        assertThatThrownBy(
                        () ->
                                RUNNER.run(
                                        shell(
                                                "echo \"fatal: '/home/person/.cache/porelo-planner/p/r.git'"
                                                        + " does not appear to be a git repository;"
                                                        + " see https://git.example.org/a/b and"
                                                        + " stories/README.md\" >&2; exit 128"),
                                        null))
                .as("a failure quoting a clone's directory")
                .hasMessageNotContaining("/home/person")
                .hasMessageNotContaining("porelo-planner/p")
                .hasMessageContaining("'<path>'")
                .hasMessageContaining("https://git.example.org/a/b")
                .hasMessageContaining("stories/README.md");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "C:\\Users\\person\\AppData\\Local\\Temp\\p\\r.git",
                "c:/Users/person/AppData/Local/Temp/p/r.git",
                "/c/Users/person/AppData/Local/Temp/p/r.git",
                "\\\\host\\share\\person\\r.git",
                "//host/share/person/r.git",
                "C:\\Users\\Jane person\\AppData\\r.git"
            })
    void aWindowsPathInAnyFormNeverReachesTheFailure(final String path) {
        // Git for Windows and Java name a directory by its drive or share, account name included.
        final String scrubbed =
                GitRunner.scrubbed(
                        "fatal: '"
                                + path
                                + "' does not appear to be a git repository; see"
                                + " https://git.example.org/a/b and stories/README.md");

        assertThat(scrubbed)
                .as("a failure quoting %s", path)
                .doesNotContain("person")
                .doesNotContain("host\\share")
                .contains("'<path>'")
                .contains("https://git.example.org/a/b")
                .contains("stories/README.md");
    }

    @Test
    void aPasswordWithASlashAndPathsAfterAColonOrATildeNeverReachTheFailure() {
        // Git echoes what an insteadOf or a helper gave it, password and home directory included.
        assertThatThrownBy(
                        () ->
                                RUNNER.run(
                                        shell(
                                                "echo \"fatal: unable to access"
                                                        + " 'https://alice:s3cr3t+/more@git.example.org/a.git/';"
                                                        + " fatal:/home/person/.gitconfig;"
                                                        + " file:///home/person/repo;"
                                                        + " ~/.ssh/id_ed25519;"
                                                        + " git@github.com:porelo-ai/porelo.git\" >&2;"
                                                        + " exit 128"),
                                        null))
                .as("a failure quoting a URL whose password holds a slash, and three local paths")
                .hasMessageNotContaining("s3cr3t")
                .hasMessageNotContaining("alice")
                .hasMessageNotContaining("/home/person")
                .hasMessageNotContaining("id_ed25519")
                .hasMessageContaining("git@github.com:porelo-ai/porelo.git");
    }

    // Prevents a password holding a quote from reaching the window or the log.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "fatal: unable to access 'https://alice:pa'ss@git.example.org/a.git/': refused | pa'ss",
                "fatal: could not read from 'alice:pa'ss@git.example.org:a/b.git': refused | pa'ss"
            })
    void aPasswordHoldingAQuoteNeverReachesTheFailure(final String text, final String secret) {
        assertThat(GitRunner.scrubbed(text))
                .as("a failure quoting %s", text)
                .doesNotContain(secret)
                .doesNotContain("alice")
                .contains("git.example.org")
                .endsWith(": refused");
    }

    @Test
    void anAuthorizationHeaderGitEchoesLosesAllItsCredentialsToTheLineEnd() {
        // A Digest header carries its credential in its last field, not its first.
        final String scrubbed =
                GitRunner.scrubbed(
                        "> Authorization: Digest username=\"alice\", realm=\"forge\", nonce=\"n1\","
                                + " uri=\"/r\", response=\"0123abcd\"\n"
                                + "> Proxy-Authorization: Basic YWxpY2U6cHc=\n"
                                + "> authorization: tok123\n"
                                + "fatal: the next line");

        assertThat(scrubbed)
                .as("a trace with a Digest, a proxy's Basic and a header without a scheme")
                .doesNotContain("alice")
                .doesNotContain("n1")
                .doesNotContain("0123abcd")
                .doesNotContain("YWxpY2U6cHc=")
                .doesNotContain("tok123")
                .contains("Authorization: Digest ***")
                .contains("Proxy-Authorization: Basic ***")
                .endsWith("fatal: the next line");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "C:\\Users\\Jane person\\AppData\\r.git",
                "\\\\host\\share\\Jane person\\r.git",
                "//host/share/Jane person/r.git"
            })
    void anUnquotedWindowsPathWithASpaceIsTakenToTheLineEnd(final String path) {
        // Java names a file unquoted, so a space in the account's name does not end the path.
        final String scrubbed =
                GitRunner.scrubbed(
                        "could not open " + path + " (Access is denied)\nfatal: the next line");

        assertThat(scrubbed)
                .as("a failure naming %s unquoted", path)
                .doesNotContain("person")
                .isEqualTo("could not open <path>\nfatal: the next line");
    }
}
