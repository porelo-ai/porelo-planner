package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** Tests the pace one project's saves share toward GitHub. */
class WritePaceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void aSaveWritesWhileAnotherSavesCallIsStillAtGitHub() throws Exception {
        // Harm: one save's slow call to GitHub holding every other save of the project, since
        // the shared pace would be held across the call instead of only its own bookkeeping.
        final WritePace pace =
                new WritePace(() -> NOW, wait -> {}, "a-token", LimitFactors.DEFAULT);
        final CountDownLatch atGitHub = new CountDownLatch(1);
        final CountDownLatch answer = new CountDownLatch(1);
        final ExecutorService saves = Executors.newFixedThreadPool(2);
        try {
            final Future<String> slow =
                    saves.submit(
                            () ->
                                    pace.again(
                                            "a slow write",
                                            () -> {
                                                atGitHub.countDown();
                                                try {
                                                    answer.await();
                                                } catch (final InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return "slow";
                                            }));
            assertThat(atGitHub.await(10, TimeUnit.SECONDS))
                    .as("the first save's call reached GitHub")
                    .isTrue();

            final Future<String> other =
                    saves.submit(() -> pace.once("another write", () -> "other"));

            assertThat(other.get(10, TimeUnit.SECONDS))
                    .as("the other save's write, made while the first call is still at GitHub")
                    .isEqualTo("other");
            assertThat(slow.isDone()).as("the first call still at GitHub").isFalse();
            answer.countDown();
            assertThat(slow.get(10, TimeUnit.SECONDS))
                    .as("the first call's answer")
                    .isEqualTo("slow");
        } finally {
            answer.countDown();
            saves.shutdownNow();
        }
    }
}
