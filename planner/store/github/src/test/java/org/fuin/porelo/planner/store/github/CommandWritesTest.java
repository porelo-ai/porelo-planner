package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

/** A window's write in the merge's terms, and back. */
class CommandWritesTest {

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Ready", "Building", "Done", "Rejected"),
                    List.of(),
                    List.of("Done", "Rejected"));

    private static Command.Write write(final Writes writes) {
        return new Command.Write("c1", "PL1", writes, Basis.none(), Recheck.none());
    }

    @Test
    void aMoveIntoTheSecondClosedStateClosesTheIssueAsNotPlanned() {
        // Harm: a rejection merged as a plain close, so GitHub shows it completed.
        final MergeTargets targets =
                CommandWrites.targets(
                        write(new Writes(Map.of("PL1", "Rejected"), Map.of())), false, WORKFLOW);

        assertThat(targets.state()).isEqualTo(new MergeTargets.State(null, false, true));
        assertThat(
                        CommandWrites.targets(
                                        write(new Writes(Map.of("PL1", "Building"), Map.of())),
                                        false,
                                        WORKFLOW)
                                .state())
                .as("an open state is the issue's status")
                .isEqualTo(new MergeTargets.State("Building", true, false));
    }

    @Test
    void theWritesLeftKeepTheCommandsOwnValues() {
        // Harm: a merged write sending another value than the person chose, or a worker turned
        // around.
        final Writes writes =
                new Writes(
                        Map.of("PL1", "Rejected"),
                        Map.of(),
                        Map.of("PL1", Worker.MANUAL),
                        Map.of(),
                        Map.of("PL1", new Writes.Reason("Rejected", "Not needed after all.")),
                        Map.of());
        final Command.Write write = write(writes);

        assertThat(CommandWrites.writes(CommandWrites.targets(write, false, WORKFLOW), write))
                .isEqualTo(writes);
    }

    @Test
    void aPlaceWhoseNameIsNoPlainWordIsNamedByItsKindAlone() {
        // Harm: a field name another chose carried as free words to the owner account.
        assertThat(
                        CommandWrites.names(
                                new TreeSet<>(
                                        List.of(
                                                Place.STATE,
                                                Place.field("Size"),
                                                Place.field("Risk‮!")))))
                .containsExactlyInAnyOrder("STATE", "FIELD:Size", "FIELD");
    }
}
