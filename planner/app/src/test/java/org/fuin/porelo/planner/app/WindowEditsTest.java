package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.fuin.porelo.planner.core.Edits;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.NewIdeas;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.Dependencies;
import org.fuin.porelo.planner.store.DirectQueue;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The window's edits handed to the project's queue as one command each, carrying the versions the
 * window saw and the value core checked, and the queue's outcome answered as the store's own.
 */
class WindowEditsTest {

    private static final String OFFERED = "porelo-store#7";

    private final List<CommandGroup> groups = new ArrayList<>();

    private final List<String> written = new ArrayList<>();

    /** Answers a group; none writes it through the store as the stand-in does. */
    private @Nullable Function<CommandGroup, CommandOutcome> answer;

    private Board current = board("r1");

    /** Set once a comment is saved: the store's reads fail from then on. */
    private boolean unreadable;

    private MockMvc mvc;

    private ChangesRegistry changes;

    private BoardEdits edits;

    /** ST1 holds AB1 and AB2; AB4 stands in no story; an issue made outside is offered. */
    private static Board board(final String revision) {
        return board(revision, List.of());
    }

    /** The board with AB4 waiting on the given issues. */
    private static Board board(final String revision, final List<String> fourWaitsOn) {
        return new Board(
                new Revision(revision),
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
                                null)),
                List.of(
                        issue("AB1"),
                        issue("AB2"),
                        new Issue(
                                "AB4",
                                "porelo-store",
                                "Issue AB4",
                                "Idea",
                                null,
                                fourWaitsOn,
                                null,
                                null,
                                "")),
                new Work(Map.of(), List.of()),
                ProjectRepository.named(List.of("porelo-store")),
                List.of(),
                null,
                List.of(
                        new UnlistedIssue(
                                OFFERED,
                                "porelo-store",
                                "Made outside",
                                UnlistedIssue.Kind.ISSUE,
                                URI.create("https://example.org/porelo-store/issues/7"))));
    }

    private static Issue issue(final String id) {
        return new Issue(
                id, "porelo-store", "Issue " + id, "Idea", null, List.of(), null, null, "");
    }

    private Board moved() {
        current = board("r2");
        return current;
    }

    @BeforeEach
    void serve() {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        if (unreadable) {
                            throw new IllegalStateException("The board cannot be read now.");
                        }
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
                        written.add("issues " + story);
                        return moved();
                    }

                    @Override
                    public Board setBlockedBy(
                            final Revision revision,
                            final String issue,
                            final List<String> blockedBy) {
                        written.add("blockers " + issue);
                        return moved();
                    }

                    @Override
                    public Board setText(
                            final Revision revision, final String id, final TextEdit edit) {
                        written.add("text " + id);
                        return moved();
                    }

                    @Override
                    public void comment(final String issue, final String text) {
                        written.add("comment " + issue);
                        unreadable = true;
                    }

                    @Override
                    public boolean addsUnlistedIssues() {
                        return true;
                    }

                    @Override
                    public Board addUnlistedIssue(final Revision revision, final String issue) {
                        written.add("unlisted " + issue);
                        return moved();
                    }

                    @Override
                    public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
                        written.add("idea " + idea.title());
                        return new NewIdea.Added("ST3", List.of("AB5"), moved());
                    }

                    @Override
                    public boolean makesStoriesFromIssues() {
                        return true;
                    }

                    @Override
                    public NewIdea.Added createStoryFromIssues(
                            final Revision revision, final StoryFromIssues story) {
                        written.add("story " + story.title());
                        return new NewIdea.Added("ST3", story.existing(), moved());
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        projects.put("alpha", "git@example.org:a/porelo-store.git", null);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        final CommandQueues queues =
                (project, given) ->
                        group -> {
                            groups.add(group);
                            final Function<CommandGroup, CommandOutcome> chosen = answer;
                            return chosen != null
                                    ? chosen.apply(group)
                                    : new DirectQueue(given).submit(group);
                        };
        edits = new BoardEdits(projects, changes, queues);
        mvc =
                MockMvcBuilders.standaloneSetup(new EditsController(edits))
                        .setControllerAdvice(new Problems(projects))
                        .build();
    }

    @AfterEach
    void close() {
        changes.close();
    }

    private Command only() {
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().commands()).hasSize(1);
        return groups.getFirst().commands().getFirst();
    }

    @Test
    void aSavedEditsVersionsComeFromTheQueueSnapshotEvenWhenTheHeldBoardIsOlder() {
        // Harm: a second store read replaces the saved version with stale data in the answer.
        final Board saved =
                new Board(
                        new Revision("r2"),
                        current.workflow(),
                        current.stories(),
                        current.issues().stream()
                                .map(
                                        issue ->
                                                issue.id().equals("AB1")
                                                        ? issue.withVersion(
                                                                org.fuin.porelo.planner.store
                                                                        .Version.of("saved fields"))
                                                        : issue)
                                .toList(),
                        current.work(),
                        current.repositories());
        answer = group -> new CommandOutcome.Done(saved, Map.of());
        final BoardEdits.Saved result =
                edits.setFields(
                        "alpha",
                        "AB1",
                        Basis.of(current),
                        new Fields(Set.of(Fields.Field.SIZE), "M", null, null, null));
        assertThat(result.board()).as("the queue's saved snapshot").isSameAs(saved);
        assertThat(result.versions().get("AB1"))
                .as("the saved version rather than the older held one")
                .isEqualTo(saved.issue("AB1").orElseThrow().version().value())
                .isNotEqualTo(current.issue("AB1").orElseThrow().version().value());
    }

    @Test
    void fieldsAreOneCommandJudgedOnTheWholeSeen() {
        // Harm: an edit written past the queue, or on versions other than those the window saw.
        final Basis seen = Basis.of(current);
        final Fields size = new Fields(Set.of(Fields.Field.SIZE), "M", null, null, null);

        assertThat(edits.setFields("alpha", "AB1", seen, size).revision()).isEqualTo("r2");

        assertThat(only())
                .isEqualTo(
                        new Command.SetFields(
                                only().id(),
                                "AB1",
                                size,
                                seen,
                                ((Command.SetFields) only()).recheck()));
        assertThat(written).containsExactly("fields AB1");
    }

    @Test
    void aStorysIssuesAndBlockersAreOneCommandEachBlockersRecheckedOnTheirReadSet() {
        // Harm: a blocker circle written that a fresh read of the blockers would refuse.
        final Basis seen = Basis.of(current);

        edits.setStoryIssues("alpha", "ST1", seen, List.of("AB1"));
        final Command issues = only();
        groups.clear();
        edits.setBlockedBy("alpha", "AB1", Basis.of(current), List.of("AB4"));

        assertThat(issues).isInstanceOf(Command.SetStoryIssues.class);
        assertThat(((Command.SetStoryIssues) issues).issues()).containsExactly("AB1");
        assertThat(issues.basis()).isEqualTo(seen);
        final Command.SetBlockers blockers = (Command.SetBlockers) only();
        assertThat(blockers.blockedBy()).containsExactly("AB4");
        assertThat(blockers.recheck().needs()).containsExactly(Recheck.Need.BLOCKERS);
        assertThat(blockers.recheck().from()).containsExactly("AB1");
        // AB4 has waited on AB1 since the window looked: the fresh read makes AB1 waiting on AB4 a
        // circle.
        final Board fresh = board("r3", List.of("AB1"));
        assertThat(catchThrowable(() -> blockers.recheck().rule().accept(Dependencies.of(fresh))))
                .as("the recheck run on a fresh board with a circle")
                .isInstanceOfSatisfying(
                        Moves.Refused.class,
                        refused -> assertThat(refused.name()).isEqualTo(Edits.REFUSED));
    }

    @Test
    void aCreateAnsweredWithoutABoardIsPartlySavedAndReadsNothingAfterIt() {
        // Harm: a create that stands answered as a plain failure, or by a read that fails, so the
        // window's retry makes the story twice.
        answer =
                group -> {
                    unreadable = true;
                    return new CommandOutcome.Done(
                            null,
                            Map.of(
                                    group.commands().getFirst().id(),
                                    new CommandOutcome.Created("ST7", List.of("AB4"))));
                };

        final Throwable answered =
                catchThrowable(
                        () ->
                                edits.createStoryFromIssues(
                                        "alpha",
                                        Basis.of(current),
                                        new NewIdeas.Story(
                                                "Over AB4", "A gain.", "A test.", "High"),
                                        List.of("AB4"),
                                        List.of()));

        assertThat(answered)
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageContaining("ST7");
        assertThat(only()).isInstanceOf(Command.CreateStory.class);
    }

    @Test
    void aCommentSavedIsAnsweredNoContentThoughTheBoardCannotBeReadAfterIt() throws Exception {
        // Harm: a comment that stands answered as failed, so the window's retry writes it twice.
        mvc.perform(
                        post("/projects/alpha/issues/AB1/comments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\": \"A note.\"}"))
                .andExpect(status().isNoContent());

        assertThat(only()).isInstanceOf(Command.Comment.class);
        assertThat(written).containsExactly("comment AB1");
    }

    @Test
    void aTextCarriesTheEditCoreCheckedNotTheOneSent() {
        // Harm: a title written with the spaces core takes off, so the tracker shows one nobody
        // wrote.
        edits.setText(
                "alpha",
                "ST1",
                Basis.of(current),
                new TextEdit(TextEdit.Kind.STORY, " One more ", Map.of(), Set.of()));

        assertThat(((Command.SetText) only()).edit().title()).isEqualTo("One more");
    }

    @Test
    void anIssueMadeOutsideAndACommentAreOneCommandEach() {
        // Harm: a comment written twice, or past the queue that keeps it.
        edits.addUnlistedIssue("alpha", OFFERED, Basis.of(current));
        assertThat(((Command.AddUnlisted) only()).issue()).isEqualTo(OFFERED);
        groups.clear();

        edits.comment("alpha", "AB1", "A note.");

        assertThat(only()).isInstanceOf(Command.Comment.class);
        assertThat(((Command.Comment) only()).text()).isEqualTo("A note.");
        assertThat(written).containsExactly("unlisted " + OFFERED, "comment AB1");
    }

    @Test
    void aStoryFromAnIdeaCarriesTheCheckedIdeaAndAnswersTheIdsTheQueueMade() {
        // Harm: the window shown ids nothing made, or a story made from an idea core did not check.
        final List<Story> stories = new ArrayList<>(current.stories());
        stories.add(new Story("ST7", "A story", "Idea", "High", null, List.of("AB8"), "", null));
        final List<Issue> issues = new ArrayList<>(current.issues());
        issues.add(issue("AB8"));
        final Board saved =
                new Board(
                        new Revision("r2"),
                        current.workflow(),
                        stories,
                        issues,
                        current.work(),
                        current.repositories());
        answer =
                group ->
                        new CommandOutcome.Done(
                                saved,
                                Map.of(
                                        group.commands().getFirst().id(),
                                        new CommandOutcome.Created("ST7", List.of("AB8"))));

        final BoardEdits.Added added =
                edits.createStory(
                        "alpha",
                        Basis.of(current),
                        new NewIdeas.Idea(
                                new NewIdeas.Story(" A story ", "A gain.", "A test.", "High"),
                                List.of(
                                        new NewIdeas.Issue(
                                                "porelo-store",
                                                "An issue",
                                                "It works.",
                                                null,
                                                "Agent"))),
                        List.of());

        assertThat(added.story()).isEqualTo("ST7");
        assertThat(added.issues()).containsExactly("AB8");
        assertThat(added.versions())
                .as("the created story and issue can be edited immediately")
                .containsOnly(
                        Map.entry("ST7", saved.story("ST7").orElseThrow().version().value()),
                        Map.entry("AB8", saved.issue("AB8").orElseThrow().version().value()));
        assertThat(((Command.CreateIdea) only()).idea().title()).isEqualTo("A story");
        assertThat(written).as("nothing written past the queue").isEmpty();
    }

    @Test
    void aStoryFromIssuesCarriesTheCheckedStoryAndAnswersTheIdsTheQueueMade() {
        // Harm: the window shown ids nothing made.
        answer =
                group ->
                        new CommandOutcome.Done(
                                moved(),
                                Map.of(
                                        group.commands().getFirst().id(),
                                        new CommandOutcome.Created("ST7", List.of("AB4"))));

        final BoardEdits.Added added =
                edits.createStoryFromIssues(
                        "alpha",
                        Basis.of(current),
                        new NewIdeas.Story(" Over AB4 ", "A gain.", "A test.", "High"),
                        List.of("AB4"),
                        List.of());

        assertThat(added.story()).isEqualTo("ST7");
        final Command.CreateStory create = (Command.CreateStory) only();
        assertThat(create.story().title()).isEqualTo("Over AB4");
        assertThat(create.story().existing()).containsExactly("AB4");
    }

    @Test
    void aRefusalBeforeAnythingWasWrittenIsAnsweredAsTheStoresOwn() {
        // Harm: today's 422 for a refused text, or NotUnlisted for an issue gone, answered as
        // another refusal.
        answer =
                group ->
                        new CommandOutcome.Refused(
                                group.commands().getFirst(),
                                group.commands().getFirst() instanceof Command.SetText
                                        ? new TextEdit.Refused("AB1", "A part holds a heading.")
                                        : new StoreException.NoSuchIssue(OFFERED),
                                List.of(),
                                List.of());

        final Throwable text =
                catchThrowable(
                        () ->
                                edits.setText(
                                        "alpha",
                                        "AB1",
                                        Basis.of(current),
                                        new TextEdit(
                                                TextEdit.Kind.ISSUE,
                                                "A title",
                                                Map.of(),
                                                Set.of())));
        final Throwable gone =
                catchThrowable(() -> edits.addUnlistedIssue("alpha", OFFERED, Basis.of(current)));

        assertThat(((Moves.Refused) text).name()).isEqualTo(Edits.REFUSED);
        assertThat(((Moves.Refused) gone).name()).isEqualTo(Edits.NOT_UNLISTED);
    }
}
