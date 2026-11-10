package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
}
