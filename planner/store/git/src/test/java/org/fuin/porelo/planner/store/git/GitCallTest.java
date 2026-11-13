package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitCallTest {

    private static final Map<String, String> PERSON =
            Map.ofEntries(
                    Map.entry("HOME", "/home/person"),
                    Map.entry("PATH", "/usr/bin"),
                    Map.entry("SSH_AUTH_SOCK", "/run/user/1000/agent"),
                    Map.entry("DISPLAY", ":0"),
                    Map.entry("SSH_ASKPASS", "/usr/bin/ksshaskpass"),
                    Map.entry("GIT_ASKPASS", "/usr/bin/ksshaskpass"),
                    Map.entry("GIT_TERMINAL_PROMPT", "1"),
                    Map.entry("GIT_SSH", "/tmp/evil-ssh"),
                    Map.entry("GIT_SSH_COMMAND", "/tmp/evil-ssh"),
                    Map.entry("GIT_CONFIG_PARAMETERS", "'core.hooksPath'='/tmp/hooks'"),
                    Map.entry("GIT_CONFIG_COUNT", "1"),
                    Map.entry("GIT_CONFIG_KEY_0", "core.hooksPath"),
                    Map.entry("GIT_CONFIG_VALUE_0", "/tmp/hooks"),
                    Map.entry("GIT_EXEC_PATH", "/tmp/evil"),
                    Map.entry("GIT_DIR", "/home/person/other/.git"),
                    Map.entry("GIT_EXTERNAL_DIFF", "/tmp/evil"),
                    Map.entry("GIT_CONFIG_GLOBAL", "/home/person/.gitconfig-work"),
                    Map.entry("LANG", "de_DE.UTF-8"));

    private static GitCall call() {
        return GitCall.of(
                PERSON, Duration.ofSeconds(15), List.of("ls-remote", "--", "https://x/y"));
    }

    @Test
    void aCallCanNeverPromptForALogin() {
        // A prompt nobody sees hangs the board; a missing login must fail at once.
        assertThat(call().environment())
                .as("every way to a prompt or a dialog is closed")
                .containsEntry("GIT_TERMINAL_PROMPT", "0")
                .containsEntry("GIT_ASKPASS", "/bin/false")
                .containsEntry("SSH_ASKPASS_REQUIRE", "never")
                .containsEntry("GCM_INTERACTIVE", "never")
                .containsEntry("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o ConnectTimeout=15")
                .doesNotContainKey("SSH_ASKPASS");
    }

    @Test
    void theEnvironmentCannotRedirectGitToAnotherProgramRepositoryOrConfiguration() {
        assertThat(call().environment())
                .as("no inherited GIT_ variable but the configuration files survives")
                .doesNotContainKeys(
                        "GIT_SSH",
                        "GIT_CONFIG_PARAMETERS",
                        "GIT_CONFIG_COUNT",
                        "GIT_CONFIG_KEY_0",
                        "GIT_CONFIG_VALUE_0",
                        "GIT_EXEC_PATH",
                        "GIT_DIR",
                        "GIT_EXTERNAL_DIFF")
                .containsEntry("GIT_CONFIG_GLOBAL", "/home/person/.gitconfig-work");
    }

    @Test
    void thePersonsAgentAndHomeStaySoTheirOwnLoginApplies() {
        assertThat(call().environment())
                .as("the person's ssh-agent and configuration are used")
                .containsEntry("SSH_AUTH_SOCK", "/run/user/1000/agent")
                .containsEntry("HOME", "/home/person")
                .containsEntry("LC_ALL", "C");
    }

    @Test
    void hooksFiltersAndOtherProtocolsAreSwitchedOffAheadOfTheCallsArguments() {
        // A hook or a file:// or ext:: remote from the store would run code as the person.
        assertThat(call().arguments())
                .as("the safe configuration comes first, then the call's own arguments")
                .startsWith(
                        "git",
                        "-c",
                        "core.hooksPath=/dev/null",
                        "-c",
                        "core.fsmonitor=false",
                        "-c",
                        "protocol.allow=never",
                        "-c",
                        "protocol.https.allow=always",
                        "-c",
                        "protocol.ssh.allow=always")
                .endsWith("ls-remote", "--", "https://x/y");
    }

    private static final String PORELO = "/opt/porelo/bin/porelo";

    private static final CloneUrl HTTPS = new CloneUrl("https://forge.example.org/team/store.git");

    private static final List<String> FETCH = List.of("fetch", "--", HTTPS.value());

    private static Map<String, String> lending(final String executable) {
        final Map<String, String> person = new HashMap<>(PERSON);
        person.put(LentLogin.VARIABLE, executable);
        return person;
    }

    /** A login lent with a check that takes the path as it is, as one that passed would. */
    private static LentLogin lent(final String executable) {
        return LentLogin.of(lending(executable), value -> value);
    }

    @Test
    void aCallToAnHttpsCentralAsksPorelosHelperAloneWithThePathAndNoRedirect() {
        // Harm: a login lent to an earlier helper that stores it, to another repository of the same
        // host, or to the host a redirect names.
        final GitCall call =
                GitCall.reaching(
                        lending(PORELO), Duration.ofSeconds(15), lent(PORELO), HTTPS, FETCH);
        final String key = "credential.https://forge.example.org/team/store.git";
        final List<String> expected = new ArrayList<>(List.of("git"));
        expected.addAll(GitCall.SAFE_CONFIG);
        expected.addAll(
                List.of(
                        "-c",
                        key + ".helper=",
                        "-c",
                        key
                                + ".helper=!'/opt/porelo/bin/porelo' vault credential --for"
                                + " 'https://forge.example.org/team/store.git'",
                        "-c",
                        key + ".useHttpPath=true",
                        "-c",
                        "http.followRedirects=false"));
        expected.addAll(FETCH);
        assertThat(call.arguments())
                .as(
                        "every helper before cleared, then Porelo's for this address alone, and nothing"
                                + " else on the line")
                .containsExactlyElementsOf(expected);
    }

    @Test
    void aCallCarryingTheHelperSwitchesOffEveryTraceTargetOfThePersonsConfiguration() {
        // Harm: a trace target of the person's configuration writes the helper's exchange to a
        // file.
        assertThat(
                        GitCall.reaching(
                                        lending(PORELO),
                                        Duration.ofSeconds(15),
                                        lent(PORELO),
                                        HTTPS,
                                        FETCH)
                                .environment())
                .as("trace2's targets are read before any -c, so the environment turns them off")
                .containsEntry("GIT_TRACE2", "0")
                .containsEntry("GIT_TRACE2_EVENT", "0")
                .containsEntry("GIT_TRACE2_PERF", "0")
                .doesNotContainKeys(
                        "GIT_CONFIG_PARAMETERS",
                        "GIT_CONFIG_COUNT",
                        "GIT_CONFIG_KEY_0",
                        "GIT_CONFIG_VALUE_0");
    }

    @Test
    void anSshCentralGetsNoHelper() {
        // Harm: a helper line for an address Git reaches over ssh lends nothing but widens what
        // runs.
        final CloneUrl ssh = new CloneUrl("git@forge.example.org:team/store.git");
        final List<String> line = List.of("fetch", "--", ssh.value());
        assertThat(
                        GitCall.reaching(
                                lending(PORELO), Duration.ofSeconds(15), lent(PORELO), ssh, line))
                .as("the call as it is without the variable")
                .isEqualTo(GitCall.of(lending(PORELO), Duration.ofSeconds(15), line));
    }

    @Test
    void withoutTheVariableTheCallIsAsBefore() {
        // Harm: a person's own helper stops working for a planner started without Porelo.
        assertThat(LentLogin.of(PERSON, value -> PORELO))
                .as("no variable, no login lent")
                .isSameAs(LentLogin.NONE);
        assertThat(GitCall.reaching(PERSON, Duration.ofSeconds(15), LentLogin.NONE, HTTPS, FETCH))
                .as("the call as it was")
                .isEqualTo(GitCall.of(PERSON, Duration.ofSeconds(15), FETCH));
    }

    @Test
    void aQuoteInThePathOrTheAddressRefusesTheHelper() {
        // Harm: a quote ends the shell's quoting, and the rest of the line runs as a command.
        assertThatThrownBy(() -> LentLogin.helper("/opt/por'elo/porelo", HTTPS.value()))
                .as("a quote in the path")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LentLogin.helper(PORELO, "https://forge.example.org/a'b"))
                .as("a quote in the address")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                GitCall.reaching(
                                        lending("/opt/por'elo/porelo"),
                                        Duration.ofSeconds(15),
                                        lent("/opt/por'elo/porelo"),
                                        HTTPS,
                                        FETCH))
                .as("a call to an https central with a quote in the checked path")
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("no login is lent");
    }

    @Test
    void anExecutableTheCheckRefusesOrCannotCheckLendsNoLoginAndNoOtherHelperTakesOver() {
        // Harm: with a refused executable a call falls back to the person's own helpers, or runs a
        // program another account placed.
        for (final RuntimeException refusal :
                List.of(
                        new IllegalStateException("/opt/porelo is a directory others can write"),
                        new UncheckedIOException(
                                "/opt/porelo/bin/porelo cannot be checked",
                                new IOException("Permission denied")))) {
            final LentLogin refused =
                    LentLogin.of(
                            lending(PORELO),
                            value -> {
                                throw refusal;
                            });
            assertThatThrownBy(
                            () ->
                                    GitCall.reaching(
                                            lending(PORELO),
                                            Duration.ofSeconds(15),
                                            refused,
                                            HTTPS,
                                            FETCH))
                    .as("an https call after: %s", refusal.getMessage())
                    .isInstanceOf(GitFailure.class)
                    .hasMessageContaining("no login is lent")
                    .hasMessageNotContaining("/opt/porelo");
            final CloneUrl ssh = new CloneUrl("ssh://git@forge.example.org/team/store.git");
            assertThat(
                            GitCall.reaching(
                                    lending(PORELO),
                                    Duration.ofSeconds(15),
                                    refused,
                                    ssh,
                                    List.of("fetch")))
                    .as("an ssh call, which no helper serves")
                    .isEqualTo(
                            GitCall.of(lending(PORELO), Duration.ofSeconds(15), List.of("fetch")));
        }
    }
}
