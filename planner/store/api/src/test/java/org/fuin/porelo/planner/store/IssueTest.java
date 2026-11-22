package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class IssueTest {

    @Test
    void aSubIssueWorkedAsItsParentKeepsItsWarningsPriorityAndWaits() {
        // A store gives a sub-issue its parent's worker by a copy; one that dropped the warnings
        // would let a held sub-issue be worked and moved, one that dropped the waits would start it
        // over an open blocker.
        final Issue sub =
                new Issue(
                        "AB1.1",
                        "alpha",
                        "Part",
                        "Parked",
                        "S",
                        List.of(),
                        "AB1",
                        null,
                        "",
                        Worker.AGENT,
                        Risk.ORDINARY,
                        12.0,
                        3.0,
                        "High",
                        List.of(Warning.stateOutsideWorkflow()),
                        List.of(new Wait("owner/lib#7", false, null)));

        final Issue worked = sub.withWorkerAndRisk(Worker.MANUAL, Risk.HARM);

        assertThat(worked.worker()).as("the parent's worker").isEqualTo(Worker.MANUAL);
        assertThat(worked.risk()).as("the higher risk").isEqualTo(Risk.HARM);
        assertThat(worked)
                .as("everything else as it was")
                .isEqualTo(
                        new Issue(
                                "AB1.1",
                                "alpha",
                                "Part",
                                "Parked",
                                "S",
                                List.of(),
                                "AB1",
                                null,
                                "",
                                Worker.MANUAL,
                                Risk.HARM,
                                12.0,
                                3.0,
                                "High",
                                List.of(Warning.stateOutsideWorkflow()),
                                List.of(new Wait("owner/lib#7", false, null))));
    }
}
