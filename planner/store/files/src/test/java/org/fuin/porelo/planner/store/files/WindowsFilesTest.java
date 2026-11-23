package org.fuin.porelo.planner.store.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Item and lock files opened and judged through their open handle, on real Windows files, by the
 * system's own calls.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class WindowsFilesTest {

    /** Everyone, as icacls takes a SID. */
    private static final String EVERYONE = "*S-1-1-0";

    @TempDir Path dir;

    private final PrivateFiles files = PrivateFiles.of(System.getenv());

    @Test
    void theAccountsOwnFileIsReadToOneBytePastTheBound() throws Exception {
        // Harm: a person's item is refused on its own machine, or a larger one is read whole.
        final Path own = Files.writeString(dir.resolve("engine-1.md"), "by hand");
        assertThat(files.readItemFile(own, 100))
                .as("read as written")
                .isEqualTo("by hand".getBytes(StandardCharsets.UTF_8));
        assertThat(files.readItemFile(own, 2)).as("one byte past the bound").hasSize(3);
        assertThat(files.readItemFile(Files.writeString(dir.resolve("engine-2.md"), ""), 100))
                .as("an empty file")
                .isEmpty();
        assertThatThrownBy(() -> files.readItemFile(dir.resolve("engine-3.md"), 100))
                .as("a file gone")
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void aSecondNameALinkOrAFileOthersMayWriteIsRefused() throws Exception {
        // Harm: a link or a second name reads another of the account's files onto the board, or
        // a file another account may still change is taken as the person's own.
        final Path secret = Files.writeString(dir.resolve("secret"), "a secret");
        assertThatThrownBy(
                        () ->
                                files.readItemFile(
                                        Files.createLink(dir.resolve("engine-1.md"), secret), 100))
                .as("a second name of another file")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has a second name");

        final Path open = Files.writeString(dir.resolve("engine-2.md"), "open to all");
        icacls(open, "/grant", EVERYONE + ":(M)");
        assertThatThrownBy(() -> files.readItemFile(open, 100))
                .as("a file Everyone may change")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lets S-1-1-0 write");

        final Path readable = Files.writeString(dir.resolve("engine-3.md"), "read by all");
        icacls(readable, "/grant", EVERYONE + ":(R)");
        assertThat(files.readItemFile(readable, 100))
                .as("a file others may only read is the person's own")
                .isEqualTo("read by all".getBytes(StandardCharsets.UTF_8));

        final Path link = dir.resolve("engine-4.md");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (final FileSystemException e) {
            Assumptions.abort("this account may not make symbolic links: " + e.getMessage());
        }
        assertThatThrownBy(() -> files.readItemFile(link, 100))
                .as("a symbolic link")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is a link");
    }

    @Test
    void theLockFileIsMadeLockedOnceAndAFileOthersMayWriteIsRefused() throws Exception {
        // Harm: two planners change one project at once, or a lock file another account placed
        // decides when the project may be changed.
        final FileProjectLock first = new FileProjectLock(dir);
        try (ProjectLock.Held held = first.acquire()) {
            final Optional<ProjectLock.Held> second =
                    CompletableFuture.supplyAsync(
                                    () -> new FileProjectLock(dir).acquire(Duration.ofMillis(300)))
                            .get(5, TimeUnit.SECONDS);
            assertThat(second).as("not taken while held").isEmpty();
        }
        final Optional<ProjectLock.Held> again = first.acquire(Duration.ofSeconds(2));
        assertThat(again).as("taken once let go").isPresent();
        again.orElseThrow().close();

        final Path other = Files.createDirectory(dir.resolve("other"));
        final Path lock = Files.writeString(other.resolve("lock"), "");
        icacls(lock, "/grant", EVERYONE + ":(M)");
        assertThatThrownBy(() -> new FileProjectLock(other).acquire(Duration.ofSeconds(1)))
                .as("a lock file Everyone may change")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lets S-1-1-0 write");
    }

    private static void icacls(final Path file, final String... arguments) throws Exception {
        final String[] command = new String[arguments.length + 2];
        command[0] = "icacls";
        command[1] = file.toString();
        System.arraycopy(arguments, 0, command, 2, arguments.length);
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String said =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("icacls: " + said).isZero();
    }
}
