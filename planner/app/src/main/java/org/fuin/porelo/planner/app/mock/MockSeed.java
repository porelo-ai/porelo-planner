package org.fuin.porelo.planner.app.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.app.WorkRecord;
import org.fuin.porelo.planner.core.PlaceRead;
import org.fuin.porelo.planner.core.ReviewOpen;
import org.fuin.porelo.planner.core.RunPlace;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;

/**
 * The work record a mock project is given when the window's test asks for a reviewer: one pushed
 * issue whose work waits at its author's gate push, a reviewer offered there. It is made by the
 * planner's own flow on a copy of the board, so the test sees a reviewer's order with its place as
 * the real planner serves it; the board the mock serves is left as it is.
 */
public final class MockSeed {

    /** The story whose run the seed holds. */
    public static final String STORY = "ST14"; // not-a-citation: a mock board id

    /** The issue whose work waits at its gate push. */
    public static final String ISSUE = "PJ58"; // not-a-citation: a mock board id

    /** The author task whose push the work waits at. */
    public static final String TASK = "mock-author";

    private static final String BUILT = "Implementing";

    private static final String REVIEWED = "Reading";

    /** The board's role that reads another task's work at its place and gives points there. */
    public static final String READS_AT_PLACE = "reviewer";

    private static final Instant AT = Instant.parse("2026-11-25T08:00:00Z");

    private MockSeed() {}

    /**
     * Makes the record on a copy of the board given.
     *
     * @param board the mock board
     * @return the record, its story's reviewer offered at the issue's gate push
     * @throws IllegalStateException if the flow offers no reviewer there
     */
    public static WorkRun.Snapshot of(final Board board) {
        try (WorkRecord scratch = new WorkRecord(Map.of(), true)) {
            final WorkRun.Record record = scratch.open("mock-seed", "porelo-project", null);
            final WorkRun run =
                    new WorkRun(
                            new MockStore(reviewed(board)),
                            record,
                            Clock.fixed(AT, ZoneOffset.UTC));
            final WorkRun.Offered build =
                    run.order(
                                    STORY,
                                    BUILT,
                                    new WorkOrder.Decision(
                                            "AGENT", Basis.of(board), null, "", 1, null))
                            .tasks()
                            .getFirst();
            run.report(
                    TASK,
                    new WorkRun.Report(
                            build.offer(), WorkRun.Event.STARTED, "mock-box", "workstation", null));
            run.report(
                    TASK,
                    new WorkRun.Report(
                            build.offer(),
                            WorkRun.Event.ENDED,
                            "mock-box",
                            "workstation",
                            new WorkRun.Ended(AT.toString(), true, "", "Built", 0, List.of())));
            final WorkRun.PlaceOutcome outcome =
                    run.placeRead(
                            STORY,
                            ISSUE,
                            new PlaceRead.Opened(
                                    RunPlace.gate(null, TASK),
                                    TASK,
                                    new ReviewOpen(
                                            "a".repeat(40),
                                            ReviewOpen.Decision.OPEN,
                                            null,
                                            null,
                                            AT,
                                            List.of(),
                                            0,
                                            0,
                                            true,
                                            null,
                                            "mock-open")));
            if (outcome != WorkRun.PlaceOutcome.READING_OFFERED) {
                throw new IllegalStateException(
                        "The mock's pushed work was read as " + outcome + ", not a reviewer");
            }
            return record.latest();
        }
    }

    /**
     * The board whose built work goes on to a reading rule whose role reads the work at its place.
     */
    private static Board reviewed(final Board board) {
        final Map<String, WorkRule> rules = new LinkedHashMap<>(board.work().rules());
        final WorkRule built = rules.get(BUILT);
        final WorkRule reading = rules.get(REVIEWED);
        final Role reviewer = board.work().roles().get(READS_AT_PLACE);
        if (built == null || reading == null || reviewer == null) {
            throw new IllegalStateException(
                    "The mock board has no rule "
                            + BUILT
                            + " or "
                            + REVIEWED
                            + ", or no role "
                            + READS_AT_PLACE);
        }
        rules.put(
                BUILT,
                new WorkRule(
                        built.from(),
                        built.done(),
                        REVIEWED,
                        built.blocked(),
                        built.per(),
                        built.modes(),
                        built.order(),
                        built.models(),
                        built.risks(),
                        built.result(),
                        built.role()));
        rules.put(
                REVIEWED,
                new WorkRule(
                        reading.from(),
                        reading.done(),
                        reading.then(),
                        reading.blocked(),
                        reading.per(),
                        reading.modes(),
                        "Review {tasks} for {issues}.",
                        reading.models(),
                        reading.risks(),
                        null,
                        reviewer));
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                board.issues(),
                new Work(rules, board.work().faults(), board.work().seed(), board.work().roles()),
                board.repositories());
    }
}
