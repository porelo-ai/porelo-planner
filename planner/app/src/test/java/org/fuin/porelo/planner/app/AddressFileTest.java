package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddressFileTest {

    private Path runtime;

    @BeforeEach
    void makeRuntimeDirectory() throws IOException {
        runtime = RuntimeDirectories.create();
    }

    @AfterEach
    void deleteRuntimeDirectory() throws IOException {
        runtime.toFile().setWritable(true);
        RuntimeDirectories.delete(runtime);
    }

    @Test
    void theAddressFileIsReadableByTheAccountAloneAndHoldsPortAndToken() throws Exception {
        // A token another account can read lets that account act as the planner's owner.
        final AccessToken token = AccessToken.random();
        try (AddressFile file = AddressFile.open(runtime.toString())) {
            file.publish(4711, token);
            assertThat(mode(file.path())).as("the address file's mode").isEqualTo("rw-------");
            assertThat(mode(file.path().getParent()))
                    .as("the planner's directory's mode")
                    .isEqualTo("rwx------");
            assertThat(RuntimeDirectories.read(runtime))
                    .as("what the window reads")
                    .isEqualTo(new RuntimeDirectories.Address(4711, token.text()));
            assertThat(names(file.path().getParent()))
                    .as("the files beside the address file; no temporary file is left")
                    .containsExactlyInAnyOrder("planner.json", "planner.lock");
        }
    }

    @Test
    void stoppingDeletesTheAddressFile() throws Exception {
        // A file left behind sends the window to a port another program may now hold.
        final AddressFile file = AddressFile.open(runtime.toString());
        file.publish(4711, AccessToken.random());
        file.close();
        assertThat(file.path()).as("the address file after the planner stopped").doesNotExist();
    }

    @Test
    void aDeadPlannersFileIsReplaced() throws Exception {
        // A dead planner's file would hand the window the wrong port and token.
        final Path directory =
                Files.createDirectory(runtime.resolve("porelo-planner"), ownerOnly());
        Files.writeString(directory.resolve("planner.json"), "{\"port\":1,\"token\":\"old\"}\n");
        final AccessToken token = AccessToken.random();
        try (AddressFile file = AddressFile.open(runtime.toString())) {
            file.publish(4712, token);
            assertThat(RuntimeDirectories.read(runtime))
                    .as("the address file after a new start")
                    .isEqualTo(new RuntimeDirectories.Address(4712, token.text()));
        }
    }

    @Test
    void aFailedWriteLeavesNoCopyOfTheToken() throws Exception {
        // Every copy of the token left on disk is one more place it can be read from.
        try (AddressFile file = AddressFile.open(runtime.toString())) {
            Files.createDirectories(file.path().resolve("blocks-the-move"));
            assertThatThrownBy(() -> file.publish(4713, AccessToken.random()))
                    .as("a write over a directory of the address file's name")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(names(file.path().getParent()))
                    .as("the files beside the address file after the failed write")
                    .containsExactlyInAnyOrder("planner.json", "planner.lock");
            Files.delete(file.path().resolve("blocks-the-move"));
        }
    }

    @Test
    void aSecondPlannerRefusesWhileTheFirstRuns() throws Exception {
        // Two planners would overwrite each other's address file and write the store twice.
        try (AddressFile first = AddressFile.open(runtime.toString())) {
            assertThatThrownBy(() -> AddressFile.open(runtime.toString()))
                    .as("a second start while the first holds the lock")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already runs");
        }
        AddressFile.open(runtime.toString()).close();
    }

    @Test
    void anotherProcessIsRefusedEvenAfterARefusedStartInThisOne() throws Exception {
        // Closing any channel on the lock file lets go of the process's lock; a refused second
        // start that closed one would let a third planner in beside the first.
        try (AddressFile first = AddressFile.open(runtime.toString())) {
            assertThatThrownBy(() -> AddressFile.open(runtime.toString()))
                    .as("a second start in this process")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(probe())
                    .as("a start in another process while the first runs")
                    .isEqualTo("refused");
        }
        assertThat(probe())
                .as("a start in another process once the first stopped")
                .isEqualTo("locked");
    }

    @Test
    void aDeadPlannersFileIsGoneAsSoonAsTheLockIsHeld() throws Exception {
        // Until the new planner listens, a window would read the dead one's port, which another
        // program may have taken by then.
        final Path directory =
                Files.createDirectory(runtime.resolve("porelo-planner"), ownerOnly());
        Files.writeString(directory.resolve("planner.json"), "{\"port\":1,\"token\":\"old\"}\n");
        try (AddressFile file = AddressFile.open(runtime.toString())) {
            assertThat(file.path())
                    .as("the dead planner's file before the new one listens")
                    .doesNotExist();
        }
    }

    @Test
    void theTokenNeverShowsInItsText() {
        // A token in a log line or an exception message is readable by whoever reads the log.
        final AccessToken token = AccessToken.random();
        assertThat(token.toString()).as("the token's text form").doesNotContain(token.text());
    }

    @Test
    void aRuntimeDirectoryThatOthersCanReadIsRefused() throws Exception {
        // In a directory others can list or enter, the token could be read by them.
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> AddressFile.open(runtime.toString()))
                .as("a runtime directory of mode rwxr-xr-x")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not rwx------");
    }

    @Test
    void aRuntimeDirectoryReachedThroughALinkIsRefusedInEveryForm() throws Exception {
        // A link anywhere on the way can be turned, between the check and the write, to a
        // directory another account reads.
        final Path real = Files.createDirectory(runtime.resolve("real"), ownerOnly());
        final Path link = Files.createSymbolicLink(runtime.resolve("link"), real);
        Files.createDirectory(real.resolve("inner"), ownerOnly());
        for (final String given :
                new String[] {link + "/.", link + "/inner", real + "/../real", real + "/."}) {
            assertThatThrownBy(() -> AddressFile.open(given))
                    .as("the runtime directory given as %s", given)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aRuntimeDirectoryUnderOneOthersCanWriteIsRefused() throws Exception {
        // Another account that can write the directory above can rename this one aside and put its
        // own in its place between the check and the write.
        final Path shared = Files.createDirectory(runtime.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        final Path inner = Files.createDirectory(shared.resolve("runtime"), ownerOnly());
        try {
            assertThatThrownBy(() -> AddressFile.open(inner.toString()))
                    .as("a runtime directory under one of mode rwxrwxrwx without the sticky bit")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("others can write");
        } finally {
            Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aRuntimeDirectoryThatIsALinkIsRefused() throws Exception {
        // A link can point the file into a directory another account controls.
        final Path link = Files.createSymbolicLink(runtime.resolve("link"), runtime);
        assertThatThrownBy(() -> AddressFile.open(link.toString()))
                .as("a runtime directory that is a link")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is a link");
    }

    @Test
    void aMissingRuntimeDirectoryIsRefused() {
        // Making it would give it the umask's mode, not the system's owner-only one.
        assertThatThrownBy(() -> AddressFile.open(runtime.resolve("missing").toString()))
                .as("a runtime directory that does not exist")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void anUnsetRuntimeDirectoryFallsBackToTheAccountsOwnUnderRunUser() throws Exception {
        // The window reads the address file at /run/user/<uid> when XDG_RUNTIME_DIR is unset;
        // written
        // anywhere else, the window would not find the planner and start a second one.
        final Path account =
                Files.createDirectory(runtime.resolve(String.valueOf(ownUid())), ownerOnly());
        try (AddressFile file = AddressFile.open("", runtime)) {
            assertThat(file.path())
                    .as("the address file with XDG_RUNTIME_DIR unset")
                    .isEqualTo(account.resolve("porelo-planner").resolve("planner.json"));
        }
    }

    @Test
    void anUnsetRuntimeDirectoryWithoutTheAccountsOwnIsRefused() {
        // Without it there is no place only this account can read.
        assertThatThrownBy(() -> AddressFile.open("", runtime))
                .as("an unset runtime directory and no /run/user/<uid>")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void aRelativeRuntimeDirectoryIsRefused() {
        // A relative path depends on where the planner was started.
        assertThatThrownBy(() -> AddressFile.open("relative"))
                .as("a relative runtime directory")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an absolute path");
    }

    @Test
    void aPlannerDirectoryThatOthersCanReadIsRefused() throws Exception {
        // The planner's own directory is where the token is; others must not enter it.
        Files.createDirectory(runtime.resolve("porelo-planner"));
        Files.setPosixFilePermissions(
                runtime.resolve("porelo-planner"), PosixFilePermissions.fromString("rwxr-x---"));
        assertThatThrownBy(() -> AddressFile.open(runtime.toString()))
                .as("a planner directory of mode rwxr-x---")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not rwx------");
    }

    @Test
    void aPlannerDirectoryThatIsALinkIsRefused() throws Exception {
        // A link can point the file into a directory another account controls.
        final Path elsewhere = Files.createDirectory(runtime.resolve("elsewhere"), ownerOnly());
        Files.createSymbolicLink(runtime.resolve("porelo-planner"), elsewhere);
        assertThatThrownBy(() -> AddressFile.open(runtime.toString()))
                .as("a planner directory that is a link")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is a link");
    }

    @Test
    void aLockThatIsALinkIsNotFollowed() throws Exception {
        // Followed, the lock's creation could make or truncate a file wherever the link points.
        final Path directory =
                Files.createDirectory(runtime.resolve("porelo-planner"), ownerOnly());
        final Path target = runtime.resolve("target");
        Files.createSymbolicLink(directory.resolve("planner.lock"), target);
        assertThatThrownBy(() -> AddressFile.open(runtime.toString()))
                .as("a lock file that is a link")
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                .as("the link's target was not made")
                .isFalse();
    }

    /** Runs {@link LockProbe} in another process on this test's runtime directory. */
    private String probe() throws IOException, InterruptedException {
        final Process process =
                new ProcessBuilder(
                                ProcessHandle.current().info().command().orElseThrow(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                LockProbe.class.getName(),
                                runtime.toString())
                        .redirectErrorStream(true)
                        .start();
        final String output = new String(process.getInputStream().readAllBytes()).strip();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        return output;
    }

    private static int ownUid() throws IOException {
        return (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
    }

    private static java.nio.file.attribute.FileAttribute<?> ownerOnly() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    }

    private static String mode(final Path path) throws IOException {
        return PosixFilePermissions.toString(
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static Stream<String> namesStream(final Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).toList().stream();
        }
    }

    private static java.util.List<String> names(final Path directory) throws IOException {
        return namesStream(directory).toList();
    }

    @Test
    void theMockModeRefusesTheAccountsOwnRuntimeDirectory(@TempDir final Path runUser)
            throws Exception {
        // A mock found there would show its stand-in board in the person's own window.
        final Path own =
                runUser.resolve(
                        String.valueOf(Files.getAttribute(Path.of("/proc/self"), "unix:uid")));
        assertThatThrownBy(() -> AddressFile.requireApartFromTheAccount("", runUser))
                .as("no runtime directory, which falls back to the account's own")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--mock needs a runtime directory of its own");
        assertThatThrownBy(() -> AddressFile.requireApartFromTheAccount(own + "/./", runUser))
                .as("the account's own, written another way")
                .isInstanceOf(IllegalStateException.class);
        AddressFile.requireApartFromTheAccount(runUser.resolve("test").toString(), runUser);
    }
}
