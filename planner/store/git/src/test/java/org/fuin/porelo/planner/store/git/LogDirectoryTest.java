package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogDirectoryTest {

    private static final PrivateFiles FILES = new PosixPrivateFiles();

    @TempDir Path dir;

    private Path state() throws Exception {
        final Path state = dir.resolve("state");
        Files.createDirectory(
                state,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        return state;
    }

    /** The porelo/ another part of Porelo may have made, readable to others. */
    private static void sharedPorelo(final Path state) throws Exception {
        Files.createDirectory(state.resolve("porelo"));
        Files.setPosixFilePermissions(
                state.resolve("porelo"), PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static Path logs(final Path state) {
        return FILES.directories(
                FILES.base(PrivateFiles.Kind.LOGS, Map.of("XDG_STATE_HOME", state.toString())),
                true,
                PrivateFiles.Kind.LOGS,
                FILES.steps(PrivateFiles.Kind.LOGS));
    }

    @Test
    void theLogLiesInADirectoryOfThePersonsAloneBesideTheWindowsLog() throws Exception {
        // Another account reading the log reads the person's projects and repositories.
        final Path state = state();
        final Path logs = logs(state);
        assertThat(logs).as("the log directory").isEqualTo(state.resolve("porelo/logs"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(logs)))
                .as("only the person reads it")
                .isEqualTo("rwx------");
    }

    @Test
    void aSharedPoreloDirectoryOthersMayOnlyReadIsTaken() throws Exception {
        // The window may make porelo/ readable to others; refusing it would leave the planner
        // without a log.
        final Path state = state();
        sharedPorelo(state);
        assertThat(logs(state))
                .as("the log directory under a porelo/ of mode rwxr-xr-x")
                .isEqualTo(state.resolve("porelo/logs"));
    }

    @Test
    void aLogDirectoryAnotherAccountCanWriteIsRefused() throws Exception {
        // Another account writing the log directory could replace the log or plant a link.
        final Path state = state();
        sharedPorelo(state);
        Files.createDirectory(state.resolve("porelo/logs"));
        Files.setPosixFilePermissions(
                state.resolve("porelo/logs"), PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThatThrownBy(() -> logs(state))
                .as("a log directory of mode rwxrwxrwx")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(state.resolve("porelo/logs").toString());
    }

    @Test
    void aLogDirectoryOthersCanReadIsRefusedAndNotLoosenedOrRepaired() throws Exception {
        // The planner never changes a log directory the window made, nor takes one others read.
        final Path state = state();
        sharedPorelo(state);
        Files.createDirectory(state.resolve("porelo/logs"));
        Files.setPosixFilePermissions(
                state.resolve("porelo/logs"), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> logs(state))
                .as("a log directory of mode rwxr-xr-x")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(state.resolve("porelo/logs").toString());
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(state.resolve("porelo/logs"))))
                .as("the directory as it was")
                .isEqualTo("rwxr-xr-x");
    }

    @Test
    void aSharedPoreloDirectoryTheGroupCanWriteIsRefused() throws Exception {
        // A group member could rename porelo/logs away and put a directory of their own there.
        final Path state = state();
        Files.createDirectory(state.resolve("porelo"));
        Files.setPosixFilePermissions(
                state.resolve("porelo"), PosixFilePermissions.fromString("rwxrwxr-x"));
        assertThatThrownBy(() -> logs(state))
                .as("a porelo/ of mode rwxrwxr-x")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(state.resolve("porelo").toString());
    }

    @Test
    void aSharedPoreloDirectoryThatIsALinkIsRefused() throws Exception {
        // A link lets whoever can change its target choose where the log goes.
        final Path state = state();
        final Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        Files.createSymbolicLink(state.resolve("porelo"), elsewhere);
        assertThatThrownBy(() -> logs(state))
                .as("a porelo/ that is a link")
                .isInstanceOf(IllegalStateException.class);
        assertThat(elsewhere.resolve("logs")).as("nothing made behind the link").doesNotExist();
    }
}
