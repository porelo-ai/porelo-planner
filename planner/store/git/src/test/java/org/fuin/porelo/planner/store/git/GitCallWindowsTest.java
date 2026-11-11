package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitCallWindowsTest {

    private static final Map<String, String> PERSON =
            Map.ofEntries(
                    Map.entry("LOCALAPPDATA", "C:\\Users\\person\\AppData\\Local"),
                    Map.entry("git_dir", "C:\\Users\\person\\other\\.git"),
                    Map.entry("Git_Exec_Path", "C:\\evil"),
                    Map.entry("ssh_askpass", "C:\\evil\\askpass.exe"),
                    Map.entry("gcm_interactive", "always"),
                    Map.entry("GIT_CONFIG_GLOBAL", "C:\\Users\\person\\.gitconfig"));

    private static GitCall call(final boolean windows) {
        return GitCall.of(
                PERSON, Duration.ofSeconds(15), List.of("ls-remote", "--", "https://x/y"), windows);
    }

    @Test
    void onWindowsNoHookOrAskpassLiesWhereAnotherAccountCouldPutOne() {
        // Git for Windows takes /dev/null and /bin/false as C:\dev\null and C:\bin\false, where any
        // account may make a folder, so its hook or program would run as the person.
        final String none = "C:/Users/person/AppData/Local/porelo-planner/none";
        assertThat(call(true).arguments())
                .as(
                        "hooks are looked for in the planner's own directory, which only this account can fill")
                .contains("core.hooksPath=" + none)
                .doesNotContain("core.hooksPath=/dev/null");
        assertThat(call(true).environment())
                .as("the askpass program is a name in that directory that is never made")
                .containsEntry("GIT_ASKPASS", none);
    }

    @Test
    void aGitVariableInAnyCaseIsRemovedSinceWindowsReadsItInAnyCase() {
        assertThat(call(true).environment())
                .as("a lower or mixed case GIT_ or SSH_ASKPASS variable cannot redirect Git")
                .doesNotContainKeys("git_dir", "Git_Exec_Path", "ssh_askpass", "gcm_interactive")
                .containsEntry("GCM_INTERACTIVE", "never")
                .containsEntry("GIT_CONFIG_GLOBAL", "C:\\Users\\person\\.gitconfig");
    }
}
