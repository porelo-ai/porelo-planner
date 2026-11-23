package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.planner.store.files.PrivateFiles.Kind;
import org.fuin.porelo.planner.store.files.PrivateFiles.Request;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LoggingSystem;

/** The calls the start checks ahead, against the calls its beans then make. */
class StartRequestsTest {

    @Test
    void theStartChecksAheadExactlyTheCallsItsBeansMake() {
        // Harm: a start that checks ahead what its beans then do not ask, so each still waits on
        // its own PowerShell call, or one that checks a call no bean makes.
        final Recording files = new Recording();
        final List<Request> prepared =
                AccessConfiguration.startRequests(files, Map.of(), "", "", "", false);

        assertThatThrownBy(() -> AddressFile.open("", Path.of("/run/user"), files))
                .as("the address file asks for its directory");
        assertThatThrownBy(() -> LogFile.directory(files, Map.of(), ""))
                .as("the log file asks for its directory");
        assertThatThrownBy(
                        () ->
                                LogLevel.kept(
                                        LoggingSystem.get(getClass().getClassLoader()),
                                        files,
                                        Map.of(),
                                        ""))
                .as("the log level asks for its directory");

        assertThat(prepared)
                .as("the runtime, log and state calls, each as its bean makes it")
                .containsExactlyInAnyOrderElementsOf(files.asked);
    }

    @Test
    void theMockModeAndAGivenBaseAreNotCheckedAhead() {
        // Harm: the mock mode making the person's own directories, or a base given by the person
        // passing on a check of the platform's.
        final Recording files = new Recording();

        assertThat(AccessConfiguration.startRequests(files, Map.of(), "", "", "", true))
                .as("nothing in the mock mode")
                .isEmpty();
        assertThat(
                        AccessConfiguration.startRequests(
                                        files, Map.of(), "/run/test", "/logs", "/state", false)
                                .stream()
                                .map(Request::kind))
                .as("no kind whose base is given")
                .isEmpty();
    }

    /** Windows' steps under one base, each call recorded and then refused. */
    private static final class Recording implements PrivateFiles {

        final List<Request> asked = new ArrayList<>();

        @Override
        public boolean windows() {
            return true;
        }

        @Override
        public Path base(final Kind kind, final Map<String, String> environment) {
            return Path.of("/local");
        }

        @Override
        public List<String> steps(final Kind kind) {
            return switch (kind) {
                case RUNTIME -> List.of("porelo-planner");
                case DATA -> List.of("porelo-planner");
                case STATE -> List.of("porelo-planner", "state");
                case CACHE -> List.of("porelo-planner", "cache");
                case LOGS -> List.of("porelo", "logs");
            };
        }

        @Override
        public Path directories(
                final Path base, final boolean create, final Kind kind, final List<String> steps) {
            asked.add(new Request(base, create, kind, steps));
            throw new IllegalStateException("recorded");
        }

        @Override
        public FileAttribute<?>[] ownerOnlyFile() {
            return new FileAttribute<?>[0];
        }

        @Override
        public void requireOwnFile(final Path file, final boolean alone) {
            throw new IllegalStateException("not asked here");
        }

        @Override
        public byte[] readItemFile(final Path file, final int most) {
            throw new IllegalStateException("not asked here");
        }
    }
}
