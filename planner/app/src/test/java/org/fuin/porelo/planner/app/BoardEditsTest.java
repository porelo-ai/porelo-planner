package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.NewIdeas;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Version;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The edits by hand as use cases, over a fake store and without HTTP. */
class BoardEditsTest {

    /** ST1 holds AB1 and AB2, ST2 holds AB3; AB2 waits on AB3. */
    private Board current =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                    List.of(
                            new Story(
                                    "ST1",
                                    "One",
                                    "Idea",
                                    "High",
                                    null,
                                    List.of("AB1", "AB2"),
                                    "",
                                    null),
                            new Story(
                                    "ST2", "Two", "Idea", "High", null, List.of("AB3"), "", null)),
                    List.of(
                            issue("AB1", List.of()),
                            issue("AB2", List.of("AB3")),
                            issue("AB3", List.of()),
                            issue("AB4", List.of())),
                    new Work(Map.of(), List.of()),
                    List.of("porelo-store"));

    private final List<String> written = new ArrayList<>();

    private final CountDownLatch polls = new CountDownLatch(1);

    private ChangesRegistry changes;

    private BoardEdits edits;

    private static Issue issue(final String id, final List<String> blockedBy) {
        return new Issue(
                id, "porelo-store", "Issue " + id, "Idea", null, blockedBy, null, null, "");
    }

    @BeforeEach
    void serve() {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return current;
                    }

                    @Override
                    public Board refresh() {
                        return current;
                    }

                    @Override
                    public Board setFields(
                            final Revision revision, final String issue, final Fields fields) {
                        written.add("fields " + issue);
                        return moved();
                    }

                    @Override
                    public Board setStoryIssues(
                            final Revision revision,
                            final String story,
                            final List<String> issues) {
                        written.add("issues " + story + " " + issues);
                        return moved();
                    }

                    @Override
                    public Board setBlockedBy(
                            final Revision revision,
                            final String issue,
                            final List<String> blockedBy) {
                        written.add("blockers " + issue + " " + blockedBy);
                        return moved();
                    }

                    @Override
                    public Board setText(
                            final Revision revision, final String id, final TextEdit edit) {
                        if (edit.written().containsValue("## Plan")) {
                            throw new TextEdit.Refused(id, "A part holds a heading.");
                        }
                        written.add("text " + id + " " + edit.title());
                        return moved();
                    }

                    @Override
                    public void comment(final String issue, final String text) {
                        written.add("comment " + issue);
                    }

                    @Override
                    public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
                        written.add("idea " + idea.title());
                        return new NewIdea.Added("ST3", List.of("AB5"), moved());
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        projects.put("alpha", "git@example.org:a/porelo-store.git", null);
        // The polls wait behind a held task, so only the edit itself can tell the windows.
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.execute(
                () -> {
                    try {
                        polls.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        changes = new ChangesRegistry(projects, Duration.ofHours(1), scheduler);
        edits = new BoardEdits(projects, changes);
    }

    @AfterEach
    void close() {
        polls.countDown();
        changes.close();
    }

    /** The versions of every item as the board holds them now. */
    private Basis seen() {
        return Basis.of(current);
    }

    /** Versions of every item that the board's items no longer stand at. */
    private Basis stale() {
        final Map<String, Version> old = new java.util.HashMap<>();
        seen().items().keySet().forEach(id -> old.put(id, Version.of("seen before", id)));
        return new Basis(old);
    }

    private Board moved() {
        current =
                new Board(
                        new Revision("r2"),
                        current.workflow(),
                        current.stories(),
                        current.issues(),
                        current.work(),
                        current.repositories());
        return current;
    }

    @Test
    void everyEditOfAnItemThatChangedSinceItWasSeenWritesNothing() {
        // Harm: an edit planned on an old board would undo what someone saved since.
        for (final Runnable edit :
                List.<Runnable>of(
                        () -> edits.setFields("alpha", "AB1", stale(), size()),
                        () -> edits.setStoryIssues("alpha", "ST1", stale(), List.of("AB1")),
                        () -> edits.setBlockedBy("alpha", "AB1", stale(), List.of("AB4")),
                        () -> edits.setText("alpha", "AB1", stale(), title(TextEdit.Kind.ISSUE)))) {
            assertThat(catchThrowable(edit::run))
                    .as("an edit against versions the items no longer stand at")
                    .isInstanceOf(StoreException.Changed.class);
        }
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aSecondStoryForAnIssueIsRefusedAndWritesNothing() {
        // An issue has one story on a tracker that allows one parent; a second would steal it.
        final Moves.Refused refused =
                (Moves.Refused)
                        catchThrowable(
                                () ->
                                        edits.setStoryIssues(
                                                "alpha", "ST1", seen(), List.of("AB1", "AB3")));
        assertThat(refused.name()).as("the refusal").isEqualTo(Edits.REFUSED);
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aCircleOfBlockersIsRefusedAndWritesNothing() {
        // A circle leaves every issue in it waiting forever.
        final Moves.Refused refused =
                (Moves.Refused)
                        catchThrowable(
                                () -> edits.setBlockedBy("alpha", "AB3", seen(), List.of("AB2")));
        assertThat(refused.name()).as("the refusal").isEqualTo(Edits.REFUSED);
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void anUnknownIdIsRefusedAsNotFoundOrUnseenAndWritesNothing() {
        // An edit of an item nobody can see would write to whatever the store finds by that id; one
        // the edit changes is a target no version was seen of, another one the board lacks.
        for (final Runnable edit :
                List.<Runnable>of(
                        () -> edits.setFields("alpha", "AB9", seen(), size()),
                        () -> edits.setStoryIssues("alpha", "ST9", seen(), List.of()),
                        () -> edits.setStoryIssues("alpha", "ST1", seen(), List.of("AB9")),
                        () -> edits.setBlockedBy("alpha", "AB1", seen(), List.of("AB9")),
                        () -> edits.comment("alpha", "AB9", "A note."))) {
            final Throwable refused = catchThrowable(edit::run);
            if (refused instanceof StoreException.BasisIncomplete unseen) {
                assertThat(unseen.missing()).as("the unknown target").containsAnyOf("AB9", "ST9");
            } else {
                assertThat(((Moves.Refused) refused).name())
                        .as("an unknown item")
                        .isIn("NoSuchIssue", "NoSuchStory");
            }
        }
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void anEditIsWrittenOnceAnsweredWithTheNewRevisionAndToldToEveryWindow() throws Exception {
        // An edit the windows are not told of leaves every other window showing the old board.
        final Changes.Subscription window = changes.subscribe("alpha");

        assertThat(edits.setBlockedBy("alpha", "AB1", seen(), List.of("AB4")))
                .as("the revision after the save")
                .isEqualTo("r2");

        assertThat(written).as("the one write").containsExactly("blockers AB1 [AB4]");
        assertThat(window.next(Duration.ofSeconds(2)).map(board -> board.revision().value()))
                .as("the window is told the saved board")
                .contains("r2");
    }

    @Test
    void aListOrBlockersTheItemHoldsWritesNothingAndAnswersTheRevision() {
        // A save of nothing would still announce a change nobody made.
        assertThat(edits.setStoryIssues("alpha", "ST1", seen(), List.of("AB1", "AB2")))
                .as("the list ST1 holds")
                .isEqualTo("r1");
        assertThat(edits.setBlockedBy("alpha", "AB2", seen(), List.of("AB3")))
                .as("the blockers AB2 has")
                .isEqualTo("r1");
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aStoryAndACommentReachTheStore() {
        // A use case that checked and then wrote nothing would answer a story or a note never kept.
        assertThat(edits.createStory("alpha", seen(), idea(), List.of()).story())
                .as("the story's id")
                .isEqualTo("ST3");
        edits.comment("alpha", "AB1", "A note.");
        assertThat(written).as("the writes").containsExactly("idea A story", "comment AB1");
    }

    @Test
    void aMalformedIdeaIsRefusedAndWritesNothing() {
        // A story made from a malformed idea would hold what the idea format refuses.
        assertThat(
                        catchThrowable(
                                () ->
                                        edits.createStory(
                                                "alpha",
                                                seen(),
                                                new NewIdeas.Idea(null, List.of()),
                                                List.of())))
                .as("an idea with no story and no issue")
                .isInstanceOf(WorkRefusal.class);
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aTextIsWrittenOnceWithItsTitleWithoutSpacesAndToldToEveryWindow() throws Exception {
        // A text the windows are not told of leaves every other window showing the old one; a
        // title written with its spaces shows on the tracker as one nobody wrote.
        final Changes.Subscription window = changes.subscribe("alpha");

        assertThat(
                        edits.setText(
                                "alpha",
                                "ST1",
                                seen(),
                                new TextEdit(
                                        TextEdit.Kind.STORY, " One more ", Map.of(), Set.of())))
                .as("the revision after the save")
                .isEqualTo("r2");

        assertThat(written).as("the one write").containsExactly("text ST1 One more");
        assertThat(window.next(Duration.ofSeconds(2)).map(board -> board.revision().value()))
                .as("the window is told the saved board")
                .contains("r2");
    }

    @Test
    void aTextTheStoreRefusesIsAnEditNotAllowedNamingTheItem() {
        // A refusal of the text answered as a server fault gives the person nothing to repair.
        final Moves.Refused refused =
                (Moves.Refused)
                        catchThrowable(
                                () ->
                                        edits.setText(
                                                "alpha",
                                                "AB1",
                                                seen(),
                                                new TextEdit(
                                                        TextEdit.Kind.ISSUE,
                                                        null,
                                                        Map.of(TextEdit.Part.PLAN, "## Plan"),
                                                        Set.of())));
        assertThat(refused.name()).as("the refusal").isEqualTo(Edits.REFUSED);
        assertThat(refused.parameters()).as("the item named").containsEntry("item", "AB1");
        assertThat(written).as("nothing written").isEmpty();
    }

    private static TextEdit title(final TextEdit.Kind kind) {
        return new TextEdit(kind, "A title", Map.of(), Set.of());
    }

    private static Fields size() {
        return new Fields(Set.of(Fields.Field.SIZE), "M", null, null, null);
    }

    private static NewIdeas.Idea idea() {
        return new NewIdeas.Idea(
                new NewIdeas.Story("A story", "A gain.", "A test.", "High"),
                List.of(
                        new NewIdeas.Issue(
                                "porelo-store", "An issue", "It works.", null, "Agent")));
    }
}
