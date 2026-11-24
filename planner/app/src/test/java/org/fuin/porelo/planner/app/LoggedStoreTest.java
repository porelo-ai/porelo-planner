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
import org.fuin.porelo.planner.store.TextEdit;
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
                    public Board setText(
                            final Revision revision, final String id, final TextEdit edit) {
                        reached.add("text " + id);
                        return BOARD;
                    }

                    @Override
                    public void comment(final String issue, final String text) {
                        reached.add("comment " + issue);
                    }

                    @Override
                    public boolean addsUnlistedIssues() {
                        reached.add("lists");
                        return true;
                    }

                    @Override
                    public Board addUnlistedIssue(final Revision revision, final String issue) {
                        reached.add("list " + issue);
                        return BOARD;
                    }

                    @Override
                    public boolean keepsRepositoryLabels() {
                        reached.add("keeps labels");
                        return true;
                    }

                    @Override
                    public Board rereadLabels(final String repository) {
                        reached.add("reread " + repository);
                        return BOARD;
                    }

                    @Override
                    public Board correctLabels(
                            final String repository,
                            final List<org.fuin.porelo.planner.store.RepositoryWarning.Call>
                                    calls) {
                        reached.add("labels " + repository);
                        return BOARD;
                    }
                };
        final LoggedStore store = new LoggedStore("alpha", wrapped);

        store.setFields(
                BOARD.revision(),
                "AB1",
                new Fields(Set.of(Fields.Field.SIZE), "M", null, null, null));
        store.setStoryIssues(BOARD.revision(), "ST1", List.of("AB1"));
        store.setBlockedBy(BOARD.revision(), "AB1", List.of());
        store.setText(
                BOARD.revision(),
                "ST1",
                new TextEdit(TextEdit.Kind.STORY, "A title", Map.of(), Set.of()));
        store.comment("AB1", "A comment.");
        assertThat(store.addsUnlistedIssues()).as("the wrapped store's add").isTrue();
        store.addUnlistedIssue(BOARD.revision(), "porelo-lib#7");
        assertThat(store.keepsRepositoryLabels()).as("the wrapped store's labels").isTrue();
        store.rereadLabels("porelo-lib");
        store.correctLabels("porelo-lib", List.of());

        assertThat(reached)
                .as("the edits the wrapped store was given")
                .containsExactly(
                        "fields AB1",
                        "issues ST1",
                        "blockers AB1",
                        "text ST1",
                        "comment AB1",
                        "lists",
                        "list porelo-lib#7",
                        "keeps labels",
                        "reread porelo-lib",
                        "labels porelo-lib");
    }

    @Test
    void aReloadsNoteAndItsEndReachTheWrappedStore() {
        // Harm: a Reload's whole read never told by the GitHub store, since its note of the
        // Reload fell to the wrapper's default and went nowhere.
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
                    public void reloadAsked() {
                        reached.add("asked");
                    }

                    @Override
                    public void reloadEnded() {
                        reached.add("ended");
                    }
                };
        final LoggedStore store = new LoggedStore("alpha", wrapped);

        store.reloadAsked();
        store.reloadEnded();

        assertThat(reached).containsExactly("asked", "ended");
    }

    @Test
    void theWrapperDeclaresEveryMethodOfTheStore() {
        // Harm: a store's own answer replaced by the interface's default behind the wrapper, so a
        // new default a store overrides is silently skipped for every project.
        final List<String> missing = new ArrayList<>();
        for (final java.lang.reflect.Method method : PlanningStore.class.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                LoggedStore.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (final NoSuchMethodException absent) {
                missing.add(method.getName() + List.of(method.getParameterTypes()));
            }
        }

        assertThat(missing).as("the store's methods the wrapper does not hand on").isEmpty();
    }

    @Test
    void theCommandQueuesCallsReachTheWrappedStore() {
        // Harm: a group judged on the wrapper's default, on the board it last read, instead of by
        // the store's own read afresh, so a refusal before writing is never given and a write
        // goes through against a moved board; or a write and the read after it not the store's.
        final List<String> reached = new ArrayList<>();
        final org.fuin.porelo.planner.store.Command.Write command =
                new org.fuin.porelo.planner.store.Command.Write(
                        "g1/0",
                        "ST1",
                        new org.fuin.porelo.planner.store.Writes(Map.of(), Map.of("ST1", "Idea")),
                        org.fuin.porelo.planner.store.Basis.of(BOARD),
                        org.fuin.porelo.planner.store.Recheck.none());
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
                    public void judge(final org.fuin.porelo.planner.store.CommandGroup group) {
                        reached.add("judge " + group.id());
                    }

                    @Override
                    public org.fuin.porelo.planner.store.Written writeAlone(
                            final org.fuin.porelo.planner.store.Command written) {
                        reached.add("write " + written.id());
                        return new org.fuin.porelo.planner.store.Written.Done(null, null);
                    }

                    @Override
                    public Board readAfter(final Set<String> written) {
                        reached.add("read after " + written);
                        return BOARD;
                    }
                };
        final LoggedStore store = new LoggedStore("alpha", wrapped);

        store.judge(new org.fuin.porelo.planner.store.CommandGroup("g1", List.of(command)));
        store.writeAlone(command);
        store.readAfter(Set.of("ST1"));

        assertThat(reached)
                .as("the queue's calls the wrapped store was given")
                .containsExactly("judge g1", "write g1/0", "read after [ST1]");
    }
}
