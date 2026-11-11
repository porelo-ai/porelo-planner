package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.git.PrivateFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LogFileTest {

    private static final PrivateFiles FILES = PrivateFiles.of(System.getenv());

    private static final Logger LOG = LoggerFactory.getLogger(LogFileTest.class);

    private Path base;

    @BeforeEach
    void makeBase() throws IOException {
        base = RuntimeDirectories.create();
    }

    @AfterEach
    void deleteBase() throws IOException {
        RuntimeDirectories.delete(base);
    }

    private Path directory() {
        return LogFile.directory(FILES, Map.of(), base.toString());
    }

    @Test
    void theLogFileIsTheAccountsAloneAndHoldsOneLinePerEntry() throws Exception {
        // Another account reading the log reads the person's projects and repositories.
        final Path directory = directory();
        try (LogFile log = LogFile.open(directory, FILES, 1 << 20, 3)) {
            LOG.info("first {}", "entry");
            LOG.warn("second {}", "entry");
            assertThat(log.path()).as("the log file").isEqualTo(directory.resolve("planner.log"));
        }
        final Path file = directory.resolve("planner.log");
        assertThat(mode(file)).as("the log file's mode").isEqualTo("rw-------");
        assertThat(mode(directory)).as("the log directory's mode").isEqualTo("rwx------");
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8))
                .as("the lines of two entries")
                .hasSize(2);
    }

    @Test
    void pastItsBoundTheOldestLinesAreGoneAndEveryFileIsTheAccountsAlone() throws Exception {
        // A log without a bound fills the disk; a file rolled over by its name takes the umask.
        final Path directory = directory();
        final String marker = "the-first-entry";
        try (LogFile log = LogFile.open(directory, FILES, 4096, 2)) {
            LOG.info("{}", marker);
            for (int i = 0; i < 400; i++) {
                LOG.info("entry {} {}", i, "x".repeat(100));
            }
        }
        final List<Path> files;
        try (Stream<Path> listed = Files.list(directory)) {
            files = listed.toList();
        }
        assertThat(files)
                .as("the log file and two older ones")
                .extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder("planner.log", "planner.1.log", "planner.2.log");
        long size = 0;
        for (final Path file : files) {
            size += Files.size(file);
            assertThat(mode(file)).as("the mode of %s", file.getFileName()).isEqualTo("rw-------");
            assertThat(Files.readString(file, StandardCharsets.UTF_8))
                    .as("%s after the bound was passed", file.getFileName())
                    .doesNotContain(marker);
        }
        assertThat(size).as("the size of all log files").isLessThanOrEqualTo(3 * (4096 + 512));
    }

    @Test
    void aLogFileThatIsALinkIsRefusedAndItsTargetLeftAlone() throws Exception {
        // A link in the log's place would have the planner write wherever it points.
        final Path directory = directory();
        final Path target = base.resolve("elsewhere");
        Files.writeString(target, "kept\n");
        Files.createSymbolicLink(directory.resolve("planner.log"), target);
        assertThatThrownBy(() -> LogFile.open(directory, FILES, 1 << 20, 3).close())
                .as("a log file that is a link")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(directory.resolve("planner.log").toString());
        assertThat(Files.readString(target)).as("the link's target").isEqualTo("kept\n");
    }

    private static String mode(final Path path) throws IOException {
        return PosixFilePermissions.toString(
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }
}
