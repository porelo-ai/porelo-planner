package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The planner's private directories made and checked on real Windows files, by the real PowerShell.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class WindowsFilesOnWindowsTest {

    private static final String USERS = "S-1-5-32-545";

    @TempDir Path temp;

    private Path base;

    private WindowsPrivateFiles files;

    @BeforeEach
    void start() throws IOException {
        // The temporary directory may be named through an 8.3 short name; the check wants it plain.
        base = temp.toRealPath();
        files = WindowsPrivateFiles.of(System.getenv());
    }

    private Path made() {
        return files.directories(
                base, false, PrivateFiles.Kind.STATE, List.of("porelo-planner", "state"));
    }

    @Test
    void thePlannersDirectoriesAreMadeTheAccountsAloneAndPassAgain() {
        final Path state = made();

        assertThat(state).as("the deepest directory is made").isDirectory();
        assertThat(made()).as("the same directories pass when they already stand").isEqualTo(state);
    }

    @Test
    void aDirectoryUsersMayReadIsRefused() throws Exception {
        final Path state = made();
        run("icacls", state.getParent().toString(), "/grant", "*" + USERS + ":(R)");

        assertThatThrownBy(this::made)
                .as("an entry for Users would let every account of the machine read the token")
                .hasMessageContaining("lets " + USERS + " in");
    }

    @Test
    void aJunctionInPlaceOfThePlannersDirectoryIsRefused() throws Exception {
        final Path elsewhere = Files.createDirectory(base.resolve("elsewhere"));
        run(
                "cmd",
                "/c",
                "mklink",
                "/J",
                base.resolve("porelo-planner").toString(),
                elsewhere.toString());

        assertThatThrownBy(this::made)
                .as("a junction would send the planner's files where another account put it")
                .hasMessageContaining("link or junction");
    }

    private static void run(final String... command) throws Exception {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + out);
        }
    }
}
