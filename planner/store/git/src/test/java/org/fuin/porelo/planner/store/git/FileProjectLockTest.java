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
}
