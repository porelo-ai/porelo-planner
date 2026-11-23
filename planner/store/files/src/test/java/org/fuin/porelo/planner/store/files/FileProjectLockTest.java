package org.fuin.porelo.planner.store.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The lock of one project's clones, as two stores of one project in one process take it. */
class FileProjectLockTest {

    /** Each call on a thread of its own that never keeps the test's process alive. */
    private static final Executor DAEMON =
            run -> {
                final Thread thread = new Thread(run, "lock-taker");
                thread.setDaemon(true);
                thread.start();
            };

    @TempDir Path dir;

    @Test
    @Tag("linux")
    @EnabledOnOs(OS.LINUX)
    void aPipeInPlaceOfTheLockFileIsRefusedAtOnceAndNeverWaitedOn() throws Exception {
        // Harm: a named pipe put where the lock file belongs blocks its open until a reader comes,
        // past every bound, and with it every save of the project.
        final Path lock = dir.resolve("lock");
        assertThat(new ProcessBuilder("mkfifo", lock.toString()).start().waitFor())
                .as("the pipe is made")
                .isZero();
        final CompletableFuture<Throwable> bounded =
                CompletableFuture.supplyAsync(
                        () ->
                                refusal(
                                        () ->
                                                new FileProjectLock(dir)
                                                        .acquire(Duration.ofSeconds(1))),
                        DAEMON);
        try {
            assertThat(bounded.get(5, TimeUnit.SECONDS))
                    .as("the bounded wait is refused, not left waiting on the pipe")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a regular file");
            assertThat(
                            CompletableFuture.supplyAsync(
                                            () -> refusal(() -> new FileProjectLock(dir).acquire()),
                                            DAEMON)
                                    .get(5, TimeUnit.SECONDS))
                    .as("the wait without bound is refused as well")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a regular file");
        } finally {
            if (!bounded.isDone()) {
                // The writer hung on the pipe is let go by a reader that comes and goes.
                try (FileChannel reader = FileChannel.open(lock, StandardOpenOption.READ)) {
                    bounded.get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private static Throwable refusal(final Callable<?> taking) {
        try {
            return new AssertionError("taken: " + taking.call());
        } catch (final Exception e) {
            return e;
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void theLockFileIsTheAccountsAlone() throws Exception {
        // Harm: the lock file takes the account's umask and is readable or writable by others.
        try (ProjectLock.Held held = new FileProjectLock(dir).acquire()) {
            assertThat(
                            java.nio.file.attribute.PosixFilePermissions.toString(
                                    java.nio.file.Files.getPosixFilePermissions(
                                            dir.resolve("lock"))))
                    .as("owner-only, whatever the umask")
                    .isEqualTo("rw-------");
        }
    }

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
        heldElsewhere(ProjectLockHolder.class);
    }

    @Test
    void aLockJavasOwnFileLockHoldsKeepsTheProjectToItsHolder() throws Exception {
        // Harm: a planner that locks as Java's own file lock does and one that does not both take
        // the project at once, and one's save is lost under the other's.
        heldElsewhere(ChannelLockHolder.class);
    }

    private void heldElsewhere(final Class<?> holder) throws Exception {
        final Process other =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                holder.getName(),
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
