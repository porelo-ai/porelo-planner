package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalPathsTest {

    private static final Map<String, String> POSIX =
            Map.of("HOME", "/home/alice", "XDG_STATE_HOME", "/home/alice/state");

    @Test
    void aPathUnderTheHomeIsNamedFromTheHome() {
        // Prevents the account's name from reaching an answer through its home path.
        final String shown =
                LocalPaths.of(POSIX, false)
                        .relative("/home/alice/.local is reached through a link, as /home/alice/x");
        assertThat(shown)
                .as("both paths are named from the home")
                .contains("~/.local", "~/x")
                .doesNotContain("/home/alice");
    }

    @Test
    void aPathUnderTheStateDirectoryIsNamedFromItsVariable() {
        // Prevents the account's name from reaching an answer through its state directory.
        final String shown =
                LocalPaths.of(POSIX, false).relative("/home/alice/state/porelo-planner has mode");
        assertThat(shown)
                .as("the deeper base names the path")
                .contains("$XDG_STATE_HOME/porelo-planner")
                .doesNotContain("alice");
    }

    @Test
    void aSiblingWhoseNameStartsLikeTheHomeIsNotTakenForIt() {
        // Prevents another account's path from passing as the home's while keeping its name.
        final String shown =
                LocalPaths.of(POSIX, false).relative("/home/alicebob/x belongs to account 1001");
        assertThat(shown)
                .as("a path outside every base is not named at all")
                .doesNotContain("alicebob")
                .doesNotContain("~");
    }

    @Test
    void aPathWithASpaceUnderNoBaseLeavesNoPartOfItself() {
        // Prevents another account's directory name from reaching an answer through its second
        // word.
        final String shown =
                LocalPaths.of(POSIX, false)
                        .relative(
                                "/srv/jane doe/porelo/x is not a directory, as D:\\Data Dir\\jo\\y");
        assertThat(shown)
                .as("both paths go whole, the reason stays")
                .doesNotContain("doe")
                .doesNotContain("porelo")
                .doesNotContain("Dir")
                .doesNotContain("jo")
                .contains("is not a directory");
    }

    @Test
    void aPathUnderNoBaseIsLeftUnnamedButTheReasonStays() {
        // Prevents a directory above the home from carrying the account's layout into an answer.
        final String shown =
                LocalPaths.of(POSIX, false)
                        .relative("/home is a directory its group or others can write, without");
        assertThat(shown)
                .as("the path goes, what was wrong with it stays")
                .doesNotContain("/home")
                .contains("can write");
    }

    @Test
    void aWindowsPathIsNamedFromTheProfileOrTheLocalApplicationData() {
        // Prevents the account's name from reaching an answer through a Windows path.
        final LocalPaths paths =
                LocalPaths.of(
                        Map.of(
                                "UserProfile",
                                "C:\\Users\\alice",
                                "LOCALAPPDATA",
                                "C:\\Users\\alice\\AppData\\Local"),
                        true);
        final String shown =
                paths.relative(
                        "c:\\users\\alice\\AppData\\Local\\porelo-planner lets S-1-1-0 in;"
                                + " C:\\Users\\alice\\x and D:\\Shared\\alice and \\\\host\\alice");
        assertThat(shown)
                .as("each path is named from its base, any case, or not at all")
                .contains("%LOCALAPPDATA%\\porelo-planner", "~\\x")
                .doesNotContain("alice");
    }

    @Test
    void controlCharactersInAPathAreNotPassedOn() {
        // Prevents a directory's name from forging lines or reordering text in what shows it.
        final String shown =
                LocalPaths.of(POSIX, false).relative("/home/alice/a\nb\u202ec is not a file");
        assertThat(shown)
                .as("control and format characters are replaced")
                .doesNotContain("\n", "\u202e");
    }
}
