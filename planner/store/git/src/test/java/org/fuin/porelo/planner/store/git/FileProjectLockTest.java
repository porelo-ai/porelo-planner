package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The lock of one project's clones, as two stores of one project in one process take it. */
class FileProjectLockTest {

    @TempDir Path dir;

    @Test
    void aSecondLockOnTheSameProjectWaitsInsteadOfFailingAndTheProjectStaysUsable()
            throws Exception {
        // A replaced store and its successor meet on one directory; a throw there left the project
        // locked for good.
        final FileProjectLock first = new FileProjectLock(dir);
        final FileProjectLock second = new FileProjectLock(dir);
        final CompletableFuture<Void> waiting;
        try (ProjectLock.Held held = first.acquire()) {
            waiting =
                    CompletableFuture.runAsync(
                            () -> {
                                try (ProjectLock.Held other = second.acquire()) {
                                    // held and released
                                }
                            });
            Thread.sleep(300);
            assertThat(waiting.isDone())
                    .as("the second lock waits while the first is held")
                    .isFalse();
        }
        waiting.get(5, TimeUnit.SECONDS);
        final CompletableFuture<Void> again =
                CompletableFuture.runAsync(
                        () -> {
                            try (ProjectLock.Held held = first.acquire()) {
                                // held and released
                            }
                        });
        again.get(5, TimeUnit.SECONDS);
        assertThat(again.isCompletedExceptionally())
                .as("the project can be locked again afterwards")
                .isFalse();
    }

    @Test
    void aBoundedWaitGivesUpWhileAnotherProcessHoldsTheProjectAndTakesItOnceFree()
            throws Exception {
        // A save waiting without bound on a hung fetch would hang the window's call with it.
        final Process other =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                ProjectLockHolder.class.getName(),
                                dir.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        try {
            final java.io.BufferedReader said =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    other.getInputStream(),
                                    java.nio.charset.StandardCharsets.UTF_8));
            assertThat(said.readLine()).as("the other process holds the lock").isEqualTo("held");
            final FileProjectLock lock = new FileProjectLock(dir);
            final long start = System.nanoTime();
            final java.util.Optional<ProjectLock.Held> waited =
                    CompletableFuture.supplyAsync(
                                    () -> lock.acquire(java.time.Duration.ofMillis(300)))
                            .get(5, TimeUnit.SECONDS);
            assertThat(waited).as("not taken while held").isEmpty();
            assertThat(System.nanoTime() - start)
                    .as("given up after about the bound")
                    .isLessThan(TimeUnit.SECONDS.toNanos(3));
            other.getOutputStream().close();
            assertThat(other.waitFor(5, TimeUnit.SECONDS)).as("the other process let go").isTrue();
            final java.util.Optional<ProjectLock.Held> taken =
                    lock.acquire(java.time.Duration.ofSeconds(2));
            assertThat(taken).as("taken once free").isPresent();
            taken.get().close();
        } finally {
            other.destroyForcibly();
        }
    }
}
