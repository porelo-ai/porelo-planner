package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileSystemException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** A step on the planner's own files that Windows refuses while another program has them open. */
class SharedFilesTest {

    @Test
    void aRefusedStepIsTriedAsOftenAndWithAsLongAPauseAsTheRetriesFactorSays() {
        // Harm: a slow scanner that holds the planner's files longer than the default tries
        // failing the planner's save or end, though the person's retries factor allows for it.
        final AtomicInteger tries = new AtomicInteger();
        final List<Duration> pauses = new ArrayList<>();

        assertThatThrownBy(
                        () ->
                                SharedFiles.whileShared(
                                        () -> {
                                            tries.incrementAndGet();
                                            throw new FileSystemException("held by another");
                                        },
                                        LimitFactors.parse("retries=2.0"),
                                        pauses::add))
                .as("the last refusal")
                .isInstanceOf(FileSystemException.class);
        assertThat(tries.get()).as("the tries").isEqualTo(2 * SharedFiles.SHARED_TRIES);
        assertThat(pauses)
                .as("the pauses between them")
                .hasSize(2 * SharedFiles.SHARED_TRIES - 1)
                .containsOnly(SharedFiles.SHARED_PAUSE.multipliedBy(2));
    }

    @Test
    void aStepTakenAfterARefusalEndsTheTries() throws Exception {
        // Harm: a step that succeeded taken again, or reported as refused.
        final AtomicInteger tries = new AtomicInteger();

        SharedFiles.whileShared(
                () -> {
                    if (tries.incrementAndGet() < 3) {
                        throw new FileSystemException("held by another");
                    }
                },
                LimitFactors.DEFAULT,
                wait -> {});

        assertThat(tries.get()).as("the tries").isEqualTo(3);
    }
}
