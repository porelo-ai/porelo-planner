package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A planner in a JVM of its own, started as the window starts it, with its input a pipe. */
class PlannerInputEndTest {

    private static final Duration START = Duration.ofSeconds(60);

    private static final Duration END = Duration.ofSeconds(15);

    private Path runtime;

    private Path output;

    @BeforeEach
    void directories() throws Exception {
        runtime = RuntimeDirectories.create();
        output = Files.createTempFile("porelo-planner-input", ".log");
    }

    @AfterEach
    void cleanUp() throws Exception {
        Files.deleteIfExists(output);
        RuntimeDirectories.delete(runtime);
    }

    @Test
    void aPlannerStartedToEndWithItsInputEndsWhenTheWindowsPipeCloses() throws Exception {
        // A planner that outlives the window that started it serves the next, newer window from
        // an older build.
        final Process planner = start(List.of(PlannerApplication.END_WITH_INPUT));
        try {
            awaitAddress(planner);
            planner.getOutputStream().close();
            assertThat(planner.waitFor(END.toSeconds(), TimeUnit.SECONDS))
                    .as(
                            "the planner ended within %s of its input closing; it wrote:%n%s",
                            END, log())
                    .isTrue();
            assertThat(planner.exitValue())
                    .as("the exit code of a planner whose input closed")
                    .isZero();
            assertThat(addressFile())
                    .as("the address file of a planner that ended with its input")
                    .doesNotExist();
        } finally {
            planner.destroyForcibly().waitFor();
        }
    }

    @Test
    void aPlannerStartedByHandRunsOnWhenItsInputCloses() throws Exception {
        // A planner a person started by hand would end under them when their terminal's input
        // closes, as it does when the planner is started in the background.
        final Process planner = start(List.of());
        try {
            awaitAddress(planner);
            planner.getOutputStream().close();
            assertThat(planner.waitFor(END.toSeconds(), TimeUnit.SECONDS))
                    .as("the planner ended though no option told it to; it wrote:%n%s", log())
                    .isFalse();
            assertThat(addressFile()).as("the address file of a planner still running").exists();
        } finally {
            planner.destroyForcibly().waitFor();
        }
    }

    private Process start(final List<String> options) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(PlannerApplication.class.getName());
        command.add("--planner.runtime-directory=" + runtime);
        // The test's log, never the person's.
        command.add("--planner.log.base=" + runtime);
        command.addAll(options);
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .redirectInput(ProcessBuilder.Redirect.PIPE);
        // The test's work record, never the person's.
        builder.environment().put("XDG_STATE_HOME", runtime.toString());
        return builder.start();
    }

    private void awaitAddress(final Process planner) throws Exception {
        final long end = System.nanoTime() + START.toNanos();
        while (System.nanoTime() < end && planner.isAlive()) {
            if (Files.exists(addressFile())) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(
                (planner.isAlive()
                                ? "no address file within " + START
                                : "the planner ended with exit code " + planner.exitValue())
                        + "; it wrote:\n"
                        + log());
    }

    private Path addressFile() {
        return runtime.resolve(AddressFile.DIRECTORY).resolve(AddressFile.FILE);
    }

    private String log() throws Exception {
        return Files.readString(output);
    }
}
