package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

class BoardFaultsTest {

    @Test
    void whatTheStoreLeftOutIsAnsweredBesideTheBoard() {
        // A story or issue left out without a word would read as lost data.
        final Board board =
                new Board(
                        new Revision("a".repeat(64)),
                        new Workflow(
                                List.of("Idea", "Finished"),
                                List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                                List.of("Finished")),
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of("stories/work.yml is missing")),
                        List.of("porelo-store"),
                        List.of("ST3: a reference names no issue, and is left out"));

        assertThat(BoardController.workflowOf(board).faults())
                .as("the board's faults and the work rules' faults")
                .containsExactly(
                        "ST3: a reference names no issue, and is left out",
                        "stories/work.yml is missing");
    }
}
