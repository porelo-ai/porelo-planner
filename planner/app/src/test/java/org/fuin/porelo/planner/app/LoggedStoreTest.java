package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** The store every project is served through hands each edit on to the store it wraps. */
class LoggedStoreTest {

    private static final Board BOARD =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea"), List.of(), List.of()),
                    List.of(),
                    List.of(),
                    new Work(Map.of(), List.of()));

    @Test
    void everyEditReachesTheWrappedStore() {
        // An edit the wrapper does not hand on falls to the interface's default and is refused as
        // not offered by every store, the GitHub store too.
        final List<String> reached = new ArrayList<>();
        final PlanningStore wrapped =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return BOARD;
                    }

                    @Override
                    public Board refresh() {
                        return BOARD;
                    }

                    @Override
                    public Board setFields(
                            final Revision revision, final String issue, final Fields fields) {
                        reached.add("fields " + issue);
                        return BOARD;
                    }

                    @Override
                    public Board setStoryIssues(
                            final Revision revision,
                            final String story,
                            final List<String> issues) {
                        reached.add("issues " + story);
                        return BOARD;
                    }

                    @Override
                    public Board setBlockedBy(
                            final Revision revision,
                            final String issue,
                            final List<String> blockedBy) {
                        reached.add("blockers " + issue);
                        return BOARD;
                    }

                    @Override
                    public void comment(final String issue, final String text) {
                        reached.add("comment " + issue);
                    }
                };
        final LoggedStore store = new LoggedStore("alpha", wrapped);

        store.setFields(
                BOARD.revision(),
                "AB1",
                new Fields(Set.of(Fields.Field.SIZE), "M", null, null, null));
        store.setStoryIssues(BOARD.revision(), "ST1", List.of("AB1"));
        store.setBlockedBy(BOARD.revision(), "AB1", List.of());
        store.comment("AB1", "A comment.");

        assertThat(reached)
                .as("the edits the wrapped store was given")
                .containsExactly("fields AB1", "issues ST1", "blockers AB1", "comment AB1");
    }
}
