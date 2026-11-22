package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.git.PrivateFiles.Kind;
import org.fuin.porelo.planner.store.git.PrivateFiles.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The start's private directories checked in one PowerShell call, on a Windows file system in
 * memory and a shell that answers as Windows does.
 */
class WindowsStartDirectoriesTest {

    private static final String SELF = "S-1-5-21-1-2-3-1001";

    private static final String OTHER = "S-1-5-21-1-2-3-1002";

    private FileSystem disk;

    private Path local;

    private final Answers shell = new Answers();

    private WindowsPrivateFiles files;

    @BeforeEach
    void start() throws IOException {
        disk = Jimfs.newFileSystem(Configuration.windows());
        local = disk.getPath("C:\\Users\\person\\AppData\\Local");
        Files.createDirectories(local);
        files = new WindowsPrivateFiles(Map.of(), shell);
    }

    @AfterEach
    void stop() throws IOException {
        disk.close();
    }

    private Request request(final Kind kind) {
        return new Request(local, true, kind, files.steps(kind));
    }

    private Path directories(final WindowsPrivateFiles of, final Kind kind) {
        return of.directories(local, true, kind, of.steps(kind));
    }

    @Test
    void aStepMadeButNotProtectedIsRemovedSoTheNextCallProtectsIt() {
        // Harm: a call that fails after its directory was made leaves one that keeps only what it
        // inherited; every later call finds it there, never protects it, and the planner cannot
        // start again.
        shell.failures = 1;
        assertThatThrownBy(() -> directories(files, Kind.STATE))
                .as("the failed call is refused as before")
                .isInstanceOf(IllegalStateException.class);
        assertThat(local.resolve("porelo-planner"))
                .as("no step made by the failed call is left")
                .doesNotExist();

        directories(files, Kind.STATE);

        assertThat(shell.protect.getLast())
                .as("the next call makes and protects every step")
                .containsExactly(
                        local.resolve("porelo-planner").toString(),
                        local.resolve("porelo-planner").resolve("state").toString());
    }

    @Test
    void aFailedPrepareLeavesNoStepUnprotectedAndEachCallThenChecksOnItsOwn() {
        // Harm: the start's one call failing after Java made the directories of every kind, each
        // left unprotected for good.
        shell.failures = 1;
        files.prepare(List.of(request(Kind.RUNTIME), request(Kind.STATE), request(Kind.LOGS)));
        assertThat(local.resolve("porelo-planner")).as("nothing of the failed call").doesNotExist();
        assertThat(local.resolve("porelo")).as("nothing of the failed call").doesNotExist();

        directories(files, Kind.STATE);

        assertThat(shell.calls).as("the prepare's call, then the state's own").isEqualTo(2);
        assertThat(shell.protect.getLast())
                .as("the state's call protects what it makes")
                .contains(local.resolve("porelo-planner").resolve("state").toString());

        directories(files, Kind.RUNTIME);
        directories(files, Kind.LOGS);
        assertThat(shell.calls).as("each kind then checks on its own").isEqualTo(4);
    }

    @Test
    void theStartsDirectoriesAreCheckedInOneCallAndEachIsThenAnsweredWithoutAnother() {
        // Harm: a start that waits on one PowerShell per kind of directory.
        files.prepare(List.of(request(Kind.RUNTIME), request(Kind.LOGS), request(Kind.STATE)));

        final Path runtime = directories(files, Kind.RUNTIME);
        final Path logs = directories(files, Kind.LOGS);
        final Path state = directories(files, Kind.STATE);

        assertThat(shell.calls).as("one call for the three kinds").isEqualTo(1);
        assertThat(List.of(runtime, logs, state))
                .as("each kind's own directory")
                .containsExactly(
                        local.resolve("porelo-planner"),
                        local.resolve("porelo").resolve("logs"),
                        local.resolve("porelo-planner").resolve("state"));
    }

    @Test
    void aForeignPathFailsExactlyTheKindsItLiesOnAndEachByItsOwnEntry() {
        // Harm: one kind's verdict read from another kind's entry, when the kinds share the
        // directories on their way, so a foreign directory passes for the planner's own.
        shell.foreign.add(local.resolve("porelo-planner").resolve("state").toString());
        files.prepare(List.of(request(Kind.RUNTIME), request(Kind.STATE), request(Kind.CACHE)));

        directories(files, Kind.RUNTIME);
        directories(files, Kind.CACHE);
        assertThat(shell.calls).as("the runtime and cache directories passed").isEqualTo(1);

        assertThatThrownBy(() -> directories(files, Kind.STATE))
                .as("the state directory is checked on its own and refused")
                .hasMessageContaining("belongs to " + OTHER);
        assertThat(shell.calls).as("its own call").isEqualTo(2);
    }

    @Test
    void aSharedForeignDirectoryFailsEveryKindBelowIt() {
        // Harm: a verdict kept for a kind whose way runs through a foreign directory.
        shell.foreign.add(local.resolve("porelo-planner").toString());
        files.prepare(List.of(request(Kind.RUNTIME), request(Kind.STATE), request(Kind.LOGS)));

        directories(files, Kind.LOGS);
        assertThat(shell.calls).as("the logs lie elsewhere and passed").isEqualTo(1);
        assertThatThrownBy(() -> directories(files, Kind.RUNTIME))
                .as("refused on its own call")
                .hasMessageContaining("belongs to " + OTHER);
        assertThatThrownBy(() -> directories(files, Kind.STATE))
                .as("refused on its own call")
                .hasMessageContaining("belongs to " + OTHER);
        assertThat(shell.calls).as("one call of each refused kind").isEqualTo(3);
    }

    @Test
    void aRequestJavaRefusesIsLeftOutAndTheOthersPass() {
        // Harm: one bad request, such as a base given by the person, failing the start's check of
        // every other kind, or passing unchecked.
        final Request crooked =
                new Request(local.resolve("x").resolve(".."), true, Kind.LOGS, List.of("logs"));
        files.prepare(List.of(crooked, request(Kind.STATE)));
        assertThat(shell.calls).as("the others are checked in the one call").isEqualTo(1);

        directories(files, Kind.STATE);
        assertThat(shell.calls).as("the state directory passed in the one call").isEqualTo(1);
        assertThatThrownBy(
                        () -> files.directories(crooked.base(), true, Kind.LOGS, crooked.steps()))
                .as("the crooked one is refused on its own")
                .hasMessageContaining("plain form");
    }

    @Test
    void aVerdictAnswersOnlyItsOwnCallOnceAndOnlyUntilForgotten() {
        // Harm: a check made once standing for a later call, made when the directory may have
        // changed, or for another call's arguments.
        files.prepare(List.of(request(Kind.STATE), request(Kind.RUNTIME), request(Kind.CACHE)));

        files.directories(local, false, Kind.STATE, files.steps(Kind.STATE));
        assertThat(shell.calls).as("another create flag checks on its own").isEqualTo(2);
        files.directories(local, true, Kind.CACHE, files.steps(Kind.STATE));
        assertThat(shell.calls).as("another kind checks on its own").isEqualTo(3);

        directories(files, Kind.STATE);
        directories(files, Kind.STATE);
        assertThat(shell.calls).as("a verdict is used once").isEqualTo(4);

        files.forget();
        directories(files, Kind.RUNTIME);
        assertThat(shell.calls).as("none is kept once forgotten").isEqualTo(5);
    }

    @Test
    void anotherInstanceKeepsNoVerdictOfThisOne() {
        // Harm: a verdict of one planner's files answering another, made from another
        // environment.
        files.prepare(List.of(request(Kind.STATE)));
        final WindowsPrivateFiles other = new WindowsPrivateFiles(Map.of(), shell);

        directories(other, Kind.STATE);

        assertThat(shell.calls).as("the other one checks on its own").isEqualTo(2);
    }

    @Test
    void anAnswerMissingAPathKeepsNoVerdictAtAll() {
        // Harm: an answer shifted by one path read as if every entry were in its place.
        shell.missing = 1;
        files.prepare(List.of(request(Kind.RUNTIME), request(Kind.STATE)));
        shell.missing = 0;

        directories(files, Kind.RUNTIME);
        directories(files, Kind.STATE);

        assertThat(shell.calls).as("each checks on its own").isEqualTo(3);
    }

    @Test
    void aStepThatWasThereBeforeIsLeftWhenTheCallFails() throws IOException {
        // Harm: a failed call removing a directory the planner did not make, with what it holds.
        Files.createDirectories(local.resolve("porelo-planner"));
        shell.failures = 1;

        assertThatThrownBy(() -> directories(files, Kind.STATE))
                .as("the failed call is refused")
                .isInstanceOf(IllegalStateException.class);

        assertThat(local.resolve("porelo-planner")).as("what was there stays").isDirectory();
        assertThat(local.resolve("porelo-planner").resolve("state"))
                .as("what the call made goes")
                .doesNotExist();
    }

    @Test
    void aBaseAnotherAccountMayReadFailsEveryKindOnItsOwnCall() {
        // Harm: the planner's directories made below a base another account may read, which the
        // rules of a directory on the way allow.
        shell.readers.add(local.toString());
        files.prepare(List.of(request(Kind.RUNTIME), request(Kind.STATE), request(Kind.LOGS)));

        for (final Kind kind : List.of(Kind.RUNTIME, Kind.STATE, Kind.LOGS)) {
            assertThatThrownBy(() -> directories(files, kind))
                    .as("%s is refused", kind)
                    .hasMessageContaining("lets " + OTHER + " in");
        }
        assertThat(shell.calls).as("each refused on its own call").isEqualTo(4);
    }

    @Test
    void aForeignDirectoryOnTheWayFailsEveryKindOnItsOwnCall() {
        // Harm: a directory on the way that another account could swap, passed for the start.
        shell.foreign.add(local.getParent().toString());
        files.prepare(List.of(request(Kind.RUNTIME), request(Kind.STATE), request(Kind.LOGS)));

        for (final Kind kind : List.of(Kind.RUNTIME, Kind.STATE, Kind.LOGS)) {
            assertThatThrownBy(() -> directories(files, kind))
                    .as("%s is refused", kind)
                    .hasMessageContaining("neither this account nor the system");
        }
        assertThat(shell.calls).as("each refused on its own call").isEqualTo(4);
    }

    /** Answers as Windows does, every path the account's alone but those made foreign. */
    private static final class Answers implements WindowsPrivateFiles.Shell {

        int calls;

        int failures;

        int missing;

        final Set<String> foreign = new HashSet<>();

        final Set<String> readers = new HashSet<>();

        final List<List<String>> protect = new ArrayList<>();

        @Override
        public String run(final String protect, final String paths) {
            calls++;
            this.protect.add(protect.lines().toList());
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("PowerShell did not answer");
            }
            final List<String> asked = paths.lines().toList();
            final StringBuilder answer = new StringBuilder("self " + SELF + "\n");
            for (int i = 0; i < asked.size() - missing; i++) {
                final String owner = foreign.contains(asked.get(i)) ? OTHER : SELF;
                answer.append("path ").append(i).append(' ').append(owner).append(" 0 d\n");
                answer.append("ace ")
                        .append(i)
                        .append(" A ")
                        .append(SELF)
                        .append(' ')
                        .append((int) WindowsAccess.FULL_CONTROL)
                        .append(" 3 0\n");
                if (readers.contains(asked.get(i))) {
                    // Read and run, nothing that renames, deletes or re-permissions.
                    answer.append("ace ")
                            .append(i)
                            .append(" A ")
                            .append(OTHER)
                            .append(" 1179817 0 0\n");
                }
            }
            return answer.toString();
        }
    }
}
