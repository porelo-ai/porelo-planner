package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The writer of a project's board copy, behind its saves. */
class CopyWriterTest {

    @Test
    void aJobOfferedAfterTheStoreRetiredIsNeverRun() {
        // Harm: a read still running as its store is replaced writes the old store's board over the
        // copy of the store that replaced it, and the next start shows the older board.
        final List<Runnable> waiting = new ArrayList<>();
        final List<String> written = new ArrayList<>();
        final CopyWriter writer = new CopyWriter(waiting::add);
        assertThat(writer.offer(() -> written.add("before"))).as("a job before the stop").isTrue();
        writer.finish();

        assertThat(writer.offer(() -> written.add("after"))).as("a job after the stop").isFalse();
        waiting.forEach(Runnable::run);

        assertThat(written).as("the jobs run").containsExactly("before");
    }
}
