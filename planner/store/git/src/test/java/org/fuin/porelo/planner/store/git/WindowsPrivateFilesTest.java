package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WindowsPrivateFilesTest {

    @Test
    void aDrivesAbsolutePathIsTheBase() {
        assertThat(
                        WindowsPrivateFiles.drivePath(
                                Map.of("LOCALAPPDATA", "C:\\Users\\person\\AppData\\Local"),
                                "LOCALAPPDATA"))
                .as("the account's local application data is where the planner's files go")
                .isEqualTo("C:\\Users\\person\\AppData\\Local");
    }

    @Test
    void theVariableIsFoundInAnyCaseAsWindowsFindsIt() {
        assertThat(
                        WindowsPrivateFiles.drivePath(
                                Map.of("LocalAppData", "D:\\Profiles\\person\\Local"),
                                "LOCALAPPDATA"))
                .as("a copied environment keeps the name's case as the system gave it")
                .isEqualTo("D:\\Profiles\\person\\Local");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "\\\\server\\share\\person",
                "\\\\?\\C:\\Users\\person",
                "\\\\.\\C:\\Users\\person",
                "Users\\person",
                "C:Users\\person",
                "C:\\Users\\..\\Public",
                "C:\\Users\\.\\person",
                "C:\\Users\\person\nD:\\x"
            })
    void anythingButAPlainDrivePathIsRefused(final String value) {
        // A share would send the account's login to another machine; a relative or dotted path
        // lands somewhere nobody checked.
        assertThatThrownBy(
                        () ->
                                WindowsPrivateFiles.drivePath(
                                        Map.of("LOCALAPPDATA", value), "LOCALAPPDATA"))
                .as("'%s' is refused before anything is made", value)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOCALAPPDATA");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aProgramThatWritesMuchToItsErrorsAnswersWhileTheCommonPoolIsBusy() throws Exception {
        final ForkJoinPool pool = ForkJoinPool.commonPool();
        final int workers = ForkJoinPool.getCommonPoolParallelism();
        final CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < workers; i++) {
            pool.execute(
                    () -> {
                        try {
                            release.await();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }
        try {
            final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (pool.getActiveThreadCount() < workers && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertThat(pool.getActiveThreadCount())
                    .as("every worker of the pool is busy")
                    .isGreaterThanOrEqualTo(workers);
            final ProcessBuilder builder =
                    new ProcessBuilder("sh", "-c", "head -c 300000 /dev/zero >&2; echo done");

            final String answered =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(20),
                            () -> WindowsPrivateFiles.answer(builder, "sh", Duration.ofSeconds(10)),
                            "a reader waiting for a busy pool leaves the planner waiting for ever");
            assertThat(answered)
                    .as("both of the program's outputs are read while the pool is busy")
                    .isEqualTo("done\n");
        } finally {
            release.countDown();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void anInterruptedCallLeavesNoProgramRunning() throws Exception {
        final Set<Long> before = children();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final Thread caller =
                new Thread(
                        () -> {
                            try {
                                WindowsPrivateFiles.answer(
                                        new ProcessBuilder("sleep", "30"),
                                        "sleep",
                                        Duration.ofSeconds(60));
                            } catch (final RuntimeException e) {
                                thrown.set(e);
                            }
                        });
        caller.start();
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (children().equals(before) && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(10));
        final long gone = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!children().equals(before) && System.nanoTime() < gone) {
            Thread.onSpinWait();
        }

        assertThat(thrown.get()).as("the interrupted call is refused").isNotNull();
        assertThat(children())
                .as("a program left running would go on setting rights after the planner stopped")
                .isEqualTo(before);
    }

    private static Set<Long> children() {
        return ProcessHandle.current()
                .children()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .collect(Collectors.toSet());
    }
}
