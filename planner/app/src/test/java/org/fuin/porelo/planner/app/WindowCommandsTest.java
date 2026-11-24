package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.DirectQueue;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The window's moves, a new issue's move, a worker and a priority handed to the project's queue as
 * one command per item, each judged on the version seen of it, and the queue's outcome answered.
 */
class WindowCommandsTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<CommandGroup> groups = new ArrayList<>();

    /** Answers a group; none writes it through the store as the stand-in does. */
    private @Nullable Function<CommandGroup, CommandOutcome> answer;

    private final Map<String, String> states = new LinkedHashMap<>();

    private Board current;

    /** The board a create answers, which the store's own may have moved past. */
    private @Nullable Board madeAnswer;

    private MockMvc mvc;

    private ChangesRegistry changes;

    private int revision = 1;

    private Board board(final @Nullable String added, final String title) {
        final List<Issue> issues = new ArrayList<>();
        issues.add(issue("AB01", "porelo-store", states.get("AB01"), "An issue"));
        issues.add(issue("CD01", "porelo-other", states.get("CD01"), "An issue"));
        if (added != null) {
            issues.add(issue(added, "porelo-other", states.get(added), title));
        }
        return new Board(
                new Revision("r" + revision),
                new Workflow(
                        List.of("Backlog", "Review", "Finished"), List.of(), List.of("Finished")),
                List.of(
                        new Story(
                                "ST1",
                                "A story",
                                states.get("ST1"),
                                "High",
                                null,
                                List.of("AB01", "CD01"),
                                "",
                                null)),
                issues,
                new Work(Map.of(), List.of()),
                ProjectRepository.named(List.of("porelo-store", "porelo-other")));
    }

    private static Issue issue(
            final String id, final String repository, final String state, final String title) {
        return new Issue(
                id,
                repository,
                title,
                state,
                "S",
                List.of(),
                null,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY);
    }

    @BeforeEach
    void serve() throws Exception {
        states.put("AB01", "Backlog");
        states.put("CD01", "Backlog");
        states.put("ST1", "Backlog");
        current = board(null, "");
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
                    public Board save(final Revision seen, final Writes writes) {
                        states.putAll(writes.issues());
                        states.putAll(writes.stories());
                        revision++;
                        current =
                                WindowCommandsTest.this.board(
                                        states.containsKey("CD03") ? "CD03" : null, "T");
                        return current;
                    }

                    @Override
                    public NewIssue.Added createIssue(final Revision seen, final NewIssue issue) {
                        states.put("CD03", "Backlog");
                        revision++;
                        final Board made = WindowCommandsTest.this.board("CD03", "T");
                        current = madeAnswer != null ? madeAnswer : made;
                        return new NewIssue.Added("CD03", made);
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
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
        mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects, changes, queues))
                        .setControllerAdvice(new Problems(projects))
                        .build();
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    @AfterEach
    void close() {
        changes.close();
    }

    private ResultActions moveBoth() throws Exception {
        return mvc.perform(
                post("/projects/alpha/moves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"seen\": "
                                        + Seens.of(current)
                                        + ", \"confirm\": true, \"moves\": ["
                                        + "{\"issue\": \"AB01\", \"to\": \"Review\"},"
                                        + " {\"issue\": \"CD01\", \"to\": \"Review\"}]}"));
    }

    private static List<String> targets(final CommandGroup group) {
        return group.commands().stream().map(c -> ((Command.Write) c).target()).toList();
    }

    @Test
    void aMoveIsOneGroupOfACommandPerItemEachJudgedOnTheVersionTheWindowSaw() throws Exception {
        // Harm: a move written on a basis it was not judged on, or past the queue that keeps it.
        final Basis seen = Basis.of(current);

        moveBoth().andExpect(status().isOk());

        assertThat(groups).hasSize(1);
        final CommandGroup group = groups.getFirst();
        assertThat(targets(group)).containsExactly("AB01", "CD01");
        for (final Command command : group.commands()) {
            final String target = ((Command.Write) command).target();
            assertThat(command.basis().items()).containsExactly(Map.entry(target, seen.of(target)));
        }
        assertThat(group.commands().getFirst().recheck().needs())
                .containsExactlyInAnyOrder(Recheck.Need.STORY_ISSUES, Recheck.Need.PARENT);
    }

    @Test
    void aWorkerIsOneCommandForItsIssueAloneWithTheWorkersRecheck() throws Exception {
        // Harm: a worker written past the queue, or on a version the window did not see.
        final Basis seen = Basis.of(current);

        mvc.perform(
                        put("/projects/alpha/issues/AB01/worker")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"worker\": \"MANUAL\", \"seen\": "
                                                + Seens.of(current)
                                                + "}"))
                .andExpect(status().isOk());

        assertThat(groups).hasSize(1);
        assertThat(targets(groups.getFirst())).containsExactly("AB01");
        final Command command = groups.getFirst().commands().getFirst();
        assertThat(command.basis().items()).containsExactly(Map.entry("AB01", seen.of("AB01")));
        assertThat(command.recheck().from()).containsExactly("AB01");
    }

    @Test
    void aPriorityIsOneCommandForItsStoryAlone() throws Exception {
        // Harm: a priority written past the queue, lost once the queue keeps what it was handed.
        final Basis seen = Basis.of(current);

        mvc.perform(
                        put("/projects/alpha/stories/ST1/priority")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"priority\": \"Low\", \"seen\": "
                                                + Seens.of(current)
                                                + "}"))
                .andExpect(status().isOk());

        assertThat(groups).hasSize(1);
        assertThat(targets(groups.getFirst())).containsExactly("ST1");
        assertThat(groups.getFirst().commands().getFirst().basis().items())
                .containsExactly(Map.entry("ST1", seen.of("ST1")));
    }

    private ResultActions addInReview() throws Exception {
        return mvc.perform(
                post("/projects/alpha/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"repository\": \"porelo-other\", \"title\": \"T\","
                                        + " \"mustBeTrue\": \"W\", \"seen\": "
                                        + Seens.of(current)
                                        + ", \"state\": \"Review\"}"));
    }

    @Test
    void aNewIssuesMoveIsJudgedOnItsVersionOnTheBoardItsCreateAnswered() throws Exception {
        // Harm: the move judged by the board's revision, refused for any other item's change, or
        // on no version at all.
        addInReview().andExpect(status().isCreated());

        assertThat(groups).hasSize(1);
        assertThat(targets(groups.getFirst())).containsExactly("CD03");
        final Command command = groups.getFirst().commands().getFirst();
        states.put("CD03", "Backlog");
        assertThat(command.basis().items())
                .containsExactly(
                        Map.entry(
                                "CD03", board("CD03", "T").issue("CD03").orElseThrow().version()));
        assertThat(command.recheck().needs())
                .containsExactlyInAnyOrder(Recheck.Need.STORY_ISSUES, Recheck.Need.PARENT);
    }

    @Test
    void aNewIssueChangedSinceItsCreateIsNotMovedAndAnsweredAsBefore() throws Exception {
        // Harm: a move handed on for a version the store no longer holds.
        states.put("CD03", "Backlog");
        madeAnswer = board("CD03", "Retitled by another");

        addInReview()
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:PartlySaved"));
        assertThat(groups).as("no move handed on").isEmpty();
    }

    @Test
    void aGroupRefusedBeforeItWroteAnswersTheStoresRefusalAsToday() throws Exception {
        // Harm: the window told part of a move stands when none of it does.
        answer =
                group ->
                        new CommandOutcome.Refused(
                                group.commands().getFirst(),
                                new StoreException.Changed(List.of("AB01")),
                                List.of(),
                                group.commands().subList(1, group.commands().size()));

        moveBoth()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:ItemChanged"))
                .andExpect(jsonPath("$.parameters.changed[0]").value("AB01"));
    }

    @Test
    void aGroupRefusedAfterACommandWroteIsPartlySaved() throws Exception {
        // Harm: a partly written move told as refused whole.
        answer =
                group ->
                        new CommandOutcome.Refused(
                                group.commands().get(1),
                                new StoreException.Changed(List.of("CD01")),
                                group.commands().subList(0, 1),
                                group.commands().subList(2, group.commands().size()));

        moveBoth()
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:PartlySaved"))
                .andExpect(jsonPath("$.parameters.pushed").value("porelo-store"))
                .andExpect(jsonPath("$.parameters.failed").value("porelo-other"));
    }

    @Test
    void aGroupHeldUnsentIsBusy() throws Exception {
        // Harm: a move nothing wrote answered as saved.
        answer = group -> new CommandOutcome.Held(group.commands(), "held");

        moveBoth()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Busy"));
        assertThat(Set.copyOf(states.values())).containsExactly("Backlog");
    }
}
