package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

/** A change of who works an issue checked against the board a window shows. */
class WorkersTest {

    private final Board board = NextStepTest.board("Backlog", "Backlog");

    @Test
    void aChangedWorkerIsWrittenForThatIssueAlone() {
        // A worker written to another issue, or with a state, would change work nobody chose.
        final Writes writes = Workers.check(board, "AA1", Worker.MANUAL);

        assertThat(writes.workers())
                .as("the worker of the issue named, and no other")
                .isEqualTo(Map.of("AA1", Worker.MANUAL));
        assertThat(
                        writes.issues().isEmpty()
                                && writes.stories().isEmpty()
                                && writes.unstated().isEmpty())
                .as("no state is written with a worker")
                .isTrue();
    }

    @Test
    void theWorkerAnIssueHasAlreadyWritesNothing() {
        // A save of nothing would still commit and push an empty change to the store.
        assertThat(Workers.check(board, "AA1", Worker.AGENT).none())
                .as("an agent's issue given to an agent")
                .isTrue();
    }

    @Test
    void aSubIssueIsRefusedNamingIt() {
        // A sub-issue is worked as its parent is; a worker of its own would split one issue's work.
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class, () -> Workers.check(board, "AA1.1", Worker.MANUAL));

        assertThat(refused).as("a sub-issue's worker is refused").isNotNull();
        assertThat(refused.name()).as("the refusal's name").isEqualTo("MoveNotAllowed");
        assertThat(refused.parameters().get("item"))
                .as("the refusal names the sub-issue")
                .isEqualTo("AA1.1");
    }

    @Test
    void anIssueTheBoardLacksIsRefused() {
        // A worker saved for no issue would write a head the board does not hold.
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class, () -> Workers.check(board, "AA9", Worker.MANUAL));

        assertThat(refused).as("an unknown issue is refused").isNotNull();
        assertThat(refused.name()).as("the refusal's name").isEqualTo("NoSuchIssue");
        assertThat(refused.parameters())
                .as("the refusal names the issue")
                .isEqualTo(Map.of("issue", "AA9"));
    }
}
