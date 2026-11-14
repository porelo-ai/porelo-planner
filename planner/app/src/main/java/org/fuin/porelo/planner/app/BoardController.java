package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.planner.app.answers.IdeaAdded;
import org.fuin.porelo.planner.app.answers.IssueAdded;
import org.fuin.porelo.planner.app.answers.IssueDetail;
import org.fuin.porelo.planner.app.answers.IssueList;
import org.fuin.porelo.planner.app.answers.IssueReply;
import org.fuin.porelo.planner.app.answers.MoveWarning;
import org.fuin.porelo.planner.app.answers.Moved;
import org.fuin.porelo.planner.app.answers.MovedIssue;
import org.fuin.porelo.planner.app.answers.MovedStory;
import org.fuin.porelo.planner.app.answers.Phase;
import org.fuin.porelo.planner.app.answers.PlanningStartTask;
import org.fuin.porelo.planner.app.answers.PrioritySaved;
import org.fuin.porelo.planner.app.answers.ReadingReply;
import org.fuin.porelo.planner.app.answers.State;
import org.fuin.porelo.planner.app.answers.StoppedRun;
import org.fuin.porelo.planner.app.answers.StoppedTask;
import org.fuin.porelo.planner.app.answers.StoryDetail;
import org.fuin.porelo.planner.app.answers.StoryList;
import org.fuin.porelo.planner.app.answers.StoryReply;
import org.fuin.porelo.planner.app.answers.StoryWorkOrder;
import org.fuin.porelo.planner.app.answers.StoryWorkProposal;
import org.fuin.porelo.planner.app.answers.StoryWorkReply;
import org.fuin.porelo.planner.app.answers.TaskEventReply;
import org.fuin.porelo.planner.app.answers.WorkerSaved;
import org.fuin.porelo.planner.core.IdeaDiscussion;
import org.fuin.porelo.planner.core.Misfits;
import org.fuin.porelo.planner.core.MovePreview;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.NewIssues;
import org.fuin.porelo.planner.core.Priorities;
import org.fuin.porelo.planner.core.ReadingCycle;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRules;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.core.Workers;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.git.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Serves a project's board as {@code openapi/planner.yaml} describes it; each answer is read from
 * one board, so its parts and its revision agree.
 */
@RestController
public class BoardController {

    private static final Pattern WHAT_MUST_BE_TRUE =
            Pattern.compile(
                    "(?m)^\\*\\*What must be true\\.\\*\\*[ \\t]*(.+(?:\\r?\\n(?!\\s*\\n).+)*)");

    /** A preview's digest as the planner answers it. */
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    /** The most moves one batch may hold. */
    private static final int MOST_MOVES = 200;

    /** A state's name as a refusal may repeat it; anything else is never echoed. */
    private static final Pattern STATE = Pattern.compile("[A-Za-z][A-Za-z0-9 _-]{0,63}");

    private static final Logger LOG = LoggerFactory.getLogger(BoardController.class);

    private final Projects projects;

    private final ChangesRegistry changes;

    /**
     * Makes the controller.
     *
     * @param projects the projects the window has given
     * @param changes tells a followed project's windows of a save
     */
    public BoardController(final Projects projects, final ChangesRegistry changes) {
        this.projects = projects;
        this.changes = changes;
    }

    /**
     * A batch of moves as the window sends it.
     *
     * @param revision the revision the window shows
     * @param moves the moves, in order
     * @param digest the digest of the batch's preview
     * @param confirm whether a story may stand where its issues do not fit
     */
    public record MovesBody(
            @Nullable String revision,
            @Nullable List<@Nullable MoveBody> moves,
            @Nullable String digest,
            @Nullable Boolean confirm) {}

    /**
     * A batch of moves to preview, as the window sends it.
     *
     * @param revision the revision the window shows
     * @param moves the moves, in order
     */
    public record PreviewBody(
            @Nullable String revision, @Nullable List<@Nullable MoveBody> moves) {}

    /**
     * One move as the window sends it, naming a story or an issue.
     *
     * @param story the story's id, or null
     * @param issue the issue's id, or null
     * @param to the state it moves to
     * @param follow for a story, true when it gives up its given state for the one its issues give
     */
    public record MoveBody(
            @Nullable String story,
            @Nullable String issue,
            @Nullable String to,
            @Nullable Boolean follow) {

        /**
         * One plain move.
         *
         * @param story the story's id, or null
         * @param issue the issue's id, or null
         * @param to the state it moves to
         */
        public MoveBody(
                final @Nullable String story,
                final @Nullable String issue,
                final @Nullable String to) {
            this(story, issue, to, null);
        }
    }

    /**
     * Answers what a batch of moves would do and what it can do harm by, writing nothing.
     *
     * @param project the project's name
     * @param body the batch
     * @return the preview with its digest
     */
    @PostMapping("/projects/{project}/moves/preview")
    public org.fuin.porelo.planner.app.answers.MovePreview previewMoves(
            @PathVariable("project") final String project, @RequestBody final PreviewBody body) {
        final String name = name(project);
        final List<Moves.Move> moves = moves(name, body.moves());
        final String revision = body.revision();
        if (revision == null) {
            throw notAllowed("", "", "The body names no revision.");
        }
        final Projects.Given given = projects.given(name);
        final MovePreview preview;
        try {
            preview = given.work().preview(moves, revision);
        } catch (final UnsupportedOperationException e) {
            throw movesNotOffered(name);
        }
        return new org.fuin.porelo.planner.app.answers.MovePreview(
                preview.revision(),
                preview.digest(given.repository(), given.branch()),
                preview.issues().stream().map(BoardController::movedIssue).toList(),
                preview.stories().stream()
                        .map(story -> new MovedStory(story.id(), story.from(), story.to()))
                        .toList(),
                preview.warnings().stream().map(BoardController::warning).toList());
    }

    private static MovedIssue movedIssue(final MovePreview.MovedIssue issue) {
        return new MovedIssue(issue.id(), issue.repository(), issue.from(), issue.to());
    }

    private static MoveWarning warning(final MovePreview.Warning warning) {
        return new MoveWarning(
                MoveWarning.KindEnum.fromValue(warning.kind()),
                warning.item(),
                warning.from(),
                warning.to(),
                warning.run(),
                warning.tasks(),
                warning.offers(),
                warning.issues().stream().map(BoardController::movedIssue).toList(),
                warning.skipped());
    }

    /**
     * Saves a batch of moves, all or none, and only the batch whose preview the person saw.
     *
     * @param project the project's name
     * @param body the batch
     * @return the revision after the save and the issues moved
     */
    @PostMapping("/projects/{project}/moves")
    public Moved saveMoves(
            @PathVariable("project") final String project, @RequestBody final MovesBody body) {
        final String name = name(project);
        final List<Moves.Move> moves = moves(name, body.moves());
        final String revision = body.revision();
        if (revision == null) {
            throw notAllowed("", "", "The body names no revision.");
        }
        final String digest = body.digest();
        if (digest == null || !DIGEST.matcher(digest).matches()) {
            throw notAllowed("", "", "The body names no preview's digest.");
        }
        final Projects.Given given = projects.given(name);
        try {
            final WorkRun.MoveSaved<Moved> saved =
                    given.work()
                            .moves(
                                    moves,
                                    revision,
                                    digest,
                                    Boolean.TRUE.equals(body.confirm()),
                                    given.repository(),
                                    given.branch(),
                                    () -> saveMoves(name, given.store(), body, moves));
            final Moved moved = saved.saved();
            return new Moved(moved.revision(), moved.moved(), stopped(saved.stopped()));
        } catch (final UnsupportedOperationException e) {
            throw movesNotOffered(name);
        } finally {
            changes.saved(name);
        }
    }

    /** The runs a confirmed save stopped, as the window ends their tasks. */
    private static List<StoppedRun> stopped(final List<WorkRun.StoppedRun> runs) {
        return runs.stream()
                .map(
                        run ->
                                new StoppedRun(
                                        run.story(),
                                        run.run(),
                                        run.tasks().stream()
                                                .map(t -> new StoppedTask(t.task(), t.container()))
                                                .toList()))
                .toList();
    }

    private static Refusal movesNotOffered(final String name) {
        return new Refusal(
                "NotOffered",
                HttpStatus.NOT_IMPLEMENTED,
                "This planner does not save moves.",
                Map.of("project", name));
    }

    private Moved saveMoves(
            final String name,
            final PlanningStore store,
            final MovesBody body,
            final List<Moves.Move> moves) {
        final String revision = String.valueOf(body.revision());
        final Board board = store.board();
        if (!board.revision().value().equals(revision)) {
            throw new StoreException.StaleRevision(board.revision().value());
        }
        final Moves.Planned planned;
        final Board saved;
        try {
            planned =
                    Moves.check(
                            board, moves, Boolean.TRUE.equals(body.confirm()), store::storyState);
            if (planned.issues().isEmpty()
                    && planned.stories().isEmpty()
                    && planned.unstated().isEmpty()) {
                // Nothing changes, so no repository is locked or asked.
                return new Moved(board.revision().value(), planned.moved(), List.of());
            }
            saved =
                    store.save(
                            board.revision(),
                            new Writes(planned.issues(), planned.stories(), planned.unstated()));
        } catch (final StoreException.PartlySaved e) {
            // The repositories pushed first hold the save, and every window shows what they hold.
            throw e;
        } catch (final StoreException.NotSavable e) {
            final String item = e.subject();
            throw notAllowed(
                    item,
                    board.issue(item)
                            .map(Issue::state)
                            .or(() -> board.story(item).map(Story::state))
                            .orElse(""),
                    String.valueOf(e.getMessage()),
                    moves.stream()
                            .filter(move -> move.id().equals(item))
                            .map(Moves.Move::to)
                            .findFirst()
                            .orElse(""));
        } catch (final UnsupportedOperationException e) {
            throw movesNotOffered(name);
        }
        LOG.info(
                "Saved {} moves of project {} as revision {}",
                moves.size(),
                LogText.of(name),
                LogText.of(saved.revision().value()));
        return new Moved(saved.revision().value(), planned.moved(), List.of());
    }

    /**
     * Adds an idea as a new issue in state Idea, saved as a move is saved.
     *
     * @param project the project's name
     * @param request the request, whose body is the new issue
     * @return the new issue's id and the revision after the save, as created
     */
    @PostMapping("/projects/{project}/issues")
    public ResponseEntity<IssueAdded> createIssue(
            @PathVariable("project") final String project, final HttpServletRequest request) {
        final String name = name(project);
        final WorkRequests.IssueRequest body = WorkRequests.issue(request);
        final String revision = body.revision();
        if (revision == null) {
            throw NewIssues.refused("revision", "The body names no revision.");
        }
        final NewIssue.Added added;
        try {
            added = projects.work(name).alone(() -> createIssue(name, revision, body));
        } catch (final StoreException.NotSavable e) {
            throw NewIssues.refused("", String.valueOf(e.getMessage()));
        } catch (final UnsupportedOperationException e) {
            throw new Refusal(
                    "NotOffered",
                    HttpStatus.NOT_IMPLEMENTED,
                    "This planner does not add issues.",
                    Map.of("project", name));
        } finally {
            changes.saved(name);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new IssueAdded(added.id(), added.board().revision().value()));
    }

    private NewIssue.Added createIssue(
            final String name, final String revision, final WorkRequests.IssueRequest body) {
        final PlanningStore store = projects.store(name);
        final Board board = store.board();
        if (!board.revision().value().equals(revision)) {
            throw new StoreException.StaleRevision(board.revision().value());
        }
        final NewIssue issue =
                NewIssues.check(
                        board,
                        body.repository(),
                        body.title(),
                        body.mustBeTrue(),
                        body.size(),
                        body.worker(),
                        body.task());
        final NewIssue.Added added = store.createIssue(board.revision(), issue);
        LOG.info(
                "Added issue {} to project {} as revision {}",
                LogText.of(added.id()),
                LogText.of(name),
                LogText.of(added.board().revision().value()));
        return added;
    }

    /**
     * Sets who works an issue, saved as a move is saved.
     *
     * @param project the project's name
     * @param issue the issue's id
     * @param request the request, whose body is the new worker and the revision
     * @return the revision after the save, unchanged when the issue had that worker already
     */
    @PutMapping("/projects/{project}/issues/{issue}/worker")
    public WorkerSaved setWorker(
            @PathVariable("project") final String project,
            @PathVariable("issue") final String issue,
            final HttpServletRequest request) {
        final String checked = checked(project, () -> Ids.issue(issue), "issue");
        final String name = name(project);
        final WorkRequests.WorkerRequest body = WorkRequests.worker(request, checked);
        try {
            return projects.work(name).worker(checked, () -> saveWorker(name, checked, body));
        } finally {
            changes.saved(name);
        }
    }

    private WorkerSaved saveWorker(
            final String name, final String issue, final WorkRequests.WorkerRequest body) {
        final PlanningStore store = projects.store(name);
        final Board board = store.board();
        if (!board.revision().value().equals(body.revision())) {
            throw new StoreException.StaleRevision(board.revision().value());
        }
        final Writes writes = Workers.check(board, issue, body.worker());
        if (writes.none()) {
            // Nothing changes, so no repository is locked or asked.
            return new WorkerSaved(board.revision().value());
        }
        try {
            final String saved = store.save(board.revision(), writes).revision().value();
            LOG.info(
                    "Saved the worker of issue {} of project {} as revision {}",
                    LogText.of(issue),
                    LogText.of(name),
                    LogText.of(saved));
            return new WorkerSaved(saved);
        } catch (final UnsupportedOperationException e) {
            throw new Refusal(
                    "NotOffered",
                    HttpStatus.NOT_IMPLEMENTED,
                    "This planner does not save workers.",
                    Map.of("project", name));
        }
    }

    /**
     * Sets a story's priority, saved as a move is saved: its head and the stories index in one
     * commit that starts no build.
     *
     * @param project the project's name
     * @param story the story's id
     * @param request the request, whose body is the new priority and the revision
     * @return the revision after the save, unchanged when the story had that priority already
     */
    @PutMapping("/projects/{project}/stories/{story}/priority")
    public PrioritySaved setPriority(
            @PathVariable("project") final String project,
            @PathVariable("story") final String story,
            final HttpServletRequest request) {
        final String checked = storyId(project, story);
        final String name = name(project);
        final WorkRequests.PriorityRequest body = WorkRequests.priority(request, checked);
        try {
            // The priority touches no issue, so a running work does not hold it back.
            return projects.work(name).alone(() -> savePriority(name, checked, body));
        } finally {
            changes.saved(name);
        }
    }

    private PrioritySaved savePriority(
            final String name, final String story, final WorkRequests.PriorityRequest body) {
        final PlanningStore store = projects.store(name);
        final Board board = store.board();
        if (!board.revision().value().equals(body.revision())) {
            throw new StoreException.StaleRevision(board.revision().value());
        }
        final Writes writes = Priorities.check(board, story, body.priority());
        if (writes.none()) {
            // Nothing changes, so no repository is locked or asked.
            return new PrioritySaved(board.revision().value());
        }
        try {
            final String saved = store.save(board.revision(), writes).revision().value();
            LOG.info(
                    "Saved the priority of story {} of project {} as revision {}",
                    LogText.of(story),
                    LogText.of(name),
                    LogText.of(saved));
            return new PrioritySaved(saved);
        } catch (final UnsupportedOperationException e) {
            throw new Refusal(
                    "NotOffered",
                    HttpStatus.NOT_IMPLEMENTED,
                    "This planner does not save priorities.",
                    Map.of("project", name));
        }
    }

    private static List<Moves.Move> moves(
            final String project, final @Nullable List<@Nullable MoveBody> given) {
        if (given == null || given.size() > MOST_MOVES) {
            throw notAllowed("", "", "Moves must be a list of at most " + MOST_MOVES + ".");
        }
        final List<Moves.Move> moves = new ArrayList<>();
        for (final MoveBody move : given) {
            if (move == null || (move.story() == null) == (move.issue() == null)) {
                throw notAllowed("", "", "Each move must name exactly one item, once.");
            }
            final String story = move.story();
            final String id =
                    story != null
                            ? checked(project, () -> Ids.story(story), "story")
                            : checked(
                                    project,
                                    () -> Ids.issue(String.valueOf(move.issue())),
                                    "issue");
            final String to = move.to();
            if (to == null || !STATE.matcher(to).matches()) {
                throw notAllowed(id, "", "The destination must be a state's name.");
            }
            moves.add(new Moves.Move(story != null, id, to, Boolean.TRUE.equals(move.follow())));
        }
        return moves;
    }

    private static Refusal notAllowed(final String item, final String from, final String reason) {
        return notAllowed(item, from, reason, "");
    }

    private static Refusal notAllowed(
            final String item, final String from, final String reason, final String to) {
        final Map<String, String> parameters = new java.util.LinkedHashMap<>();
        parameters.put("item", item);
        parameters.put("from", from);
        parameters.put("to", to);
        parameters.put("reason", reason);
        return new Refusal("MoveNotAllowed", HttpStatus.UNPROCESSABLE_CONTENT, reason, parameters);
    }

    /**
     * The project's own repository, as the window reads it from Porelo.
     *
     * @param repository its clone URL
     * @param branch its branch, or null for the remote's default
     */
    public record ProjectSource(@Nullable String repository, @Nullable String branch) {}

    /**
     * Gives the planner a project.
     *
     * @param project the project's name
     * @param source its own repository
     * @return no content
     */
    @PutMapping("/projects/{project}")
    public ResponseEntity<Void> putProject(
            @PathVariable("project") final String project,
            @RequestBody final ProjectSource source) {
        final String name = name(project);
        final String repository = source.repository();
        if (repository == null) {
            throw new Refusal(
                    "NotACloneUrl",
                    HttpStatus.BAD_REQUEST,
                    "The body names no repository",
                    Map.of("project", name));
        }
        projects.put(name, repository, source.branch());
        // The repository's address may carry a credential; the project's name says which it is.
        LOG.info("The window gave project {}", LogText.of(name));
        return ResponseEntity.noContent().build();
    }

    /**
     * Answers the workflow, each state's kind and the sound work rules.
     *
     * @param project the project's name
     * @return the workflow
     */
    @GetMapping("/projects/{project}/workflow")
    public org.fuin.porelo.planner.app.answers.Workflow workflow(
            @PathVariable("project") final String project) {
        return workflowOf(board(project));
    }

    /**
     * Maps a board's workflow, with its states' kinds and its sound work rules.
     *
     * @param board the board
     * @return the workflow's answer
     */
    static org.fuin.porelo.planner.app.answers.Workflow workflowOf(final Board board) {
        final Workflow workflow = board.workflow();
        final WorkRules.Checked checked = WorkRules.check(workflow, board.work());
        final List<org.fuin.porelo.planner.app.answers.WorkRule> work = new ArrayList<>();
        checked.rules().forEach((state, rule) -> work.add(rule(state, rule)));
        return new org.fuin.porelo.planner.app.answers.Workflow(
                board.revision().value(),
                workflow.states().stream()
                        .map(
                                state ->
                                        new State(
                                                state,
                                                State.KindEnum.fromValue(
                                                        kind(workflow, checked, state))))
                        .toList(),
                workflow.phases().stream()
                        .map(phase -> new Phase(phase.name(), phase.states()))
                        .toList(),
                work,
                Stream.concat(board.faults().stream(), checked.faults().stream()).toList());
    }

    /**
     * Maps the whole board with its local work, as an event of the changes stream carries it.
     *
     * @param board the board
     * @param snapshot the local work generation
     * @param derive derives a story's state from its issues, as the board's store does
     * @return the workflow's answer and every story in one
     */
    static org.fuin.porelo.planner.app.answers.Board boardOf(
            final Board board, final WorkRun.Snapshot snapshot, final Moves.StoryState derive) {
        final org.fuin.porelo.planner.app.answers.Workflow workflow = workflowOf(board);
        return new org.fuin.porelo.planner.app.answers.Board(
                workflow.revision(),
                board.repositories(),
                workflow.states(),
                workflow.phases(),
                workflow.work(),
                workflow.faults(),
                board.stories().stream()
                        .map(story -> story(board, story, snapshot, derive))
                        .toList());
    }

    /**
     * Answers the stories, all or those of the given states, without their descriptions.
     *
     * @param project the project's name
     * @param states the states asked for, none for all
     * @return the stories
     */
    @GetMapping("/projects/{project}/stories")
    public StoryList stories(
            @PathVariable("project") final String project,
            @RequestParam(name = "state", required = false) final @Nullable List<String> states) {
        final WorkRun work = projects.work(name(project));
        return work.view(
                (board, snapshot) ->
                        new StoryList(
                                board.revision().value(),
                                board.stories().stream()
                                        .filter(
                                                story ->
                                                        states == null
                                                                || states.isEmpty()
                                                                || states.contains(story.state()))
                                        .map(story -> story(board, story, snapshot, work.derive()))
                                        .toList()));
    }

    /**
     * Answers one story with its description.
     *
     * @param project the project's name
     * @param id the story's id
     * @return the story
     */
    @GetMapping("/projects/{project}/stories/{story}")
    public StoryReply story(
            @PathVariable("project") final String project, @PathVariable("story") final String id) {
        final String checked = storyId(project, id);
        final Projects.Given given = projects.given(name(project));
        final String format = given.store().textFormat().name();
        final WorkRun work = given.work();
        return work.view(
                (board, snapshot) -> storyDetail(board, snapshot, checked, work.derive(), format));
    }

    private static StoryReply storyDetail(
            final Board board,
            final WorkRun.Snapshot snapshot,
            final String checked,
            final Moves.StoryState derive,
            final String format) {
        final Story story = found(board, checked);
        return new StoryReply(
                board.revision().value(),
                new StoryDetail(
                        story.id(),
                        story.title(),
                        story.state(),
                        story.stateGiven(),
                        Misfits.of(board, story, derive),
                        Misfits.derivedState(board, story, derive),
                        story.priority(),
                        story.size(),
                        story.link(),
                        issues(board, story, snapshot),
                        WorkAnswers.work(board, snapshot.runs().get(checked)),
                        story.description(),
                        format));
    }

    /**
     * Answers exactly the issues of one story, in its order.
     *
     * @param project the project's name
     * @param id the story's id
     * @return the issues
     */
    @GetMapping("/projects/{project}/stories/{story}/issues")
    public IssueList storyIssues(
            @PathVariable("project") final String project, @PathVariable("story") final String id) {
        final String checked = storyId(project, id);
        return projects.work(name(project))
                .view(
                        (board, snapshot) ->
                                new IssueList(
                                        board.revision().value(),
                                        issues(board, found(board, checked), snapshot)));
    }

    /**
     * Answers one issue with its text and sub-issues.
     *
     * @param project the project's name
     * @param id the issue's id
     * @return the issue
     */
    @GetMapping("/projects/{project}/issues/{issue}")
    public IssueReply issue(
            @PathVariable("project") final String project, @PathVariable("issue") final String id) {
        final String checked = checked(project, () -> Ids.issue(id), "issue");
        final Projects.Given given = projects.given(name(project));
        final String format = given.store().textFormat().name();
        return given.work()
                .view((board, snapshot) -> issueDetail(board, snapshot, checked, format));
    }

    private static IssueReply issueDetail(
            final Board board,
            final WorkRun.Snapshot snapshot,
            final String checked,
            final String format) {
        final Issue issue =
                board.issue(checked).orElseThrow(() -> new StoreException.NoSuchIssue(checked));
        final Matcher what = WHAT_MUST_BE_TRUE.matcher(issue.text());
        final org.fuin.porelo.planner.app.answers.Issue view = issue(board, issue, snapshot);
        return new IssueReply(
                board.revision().value(),
                new IssueDetail(
                        issue.id(),
                        issue.repository(),
                        issue.title(),
                        issue.state(),
                        issue.size(),
                        issue.blockedBy(),
                        issue.parent(),
                        issue.link(),
                        view.worker(),
                        view.rounds(),
                        view.roundsMost(),
                        view.atWork(),
                        view.needsYou(),
                        view.readingStopped(),
                        view.noRunHere(),
                        IssueDetail.AttentionEnum.fromValue(view.attention().getValue()),
                        view.findings(),
                        issue.text(),
                        format,
                        board.issues().stream()
                                .filter(other -> checked.equals(other.parent()))
                                .map(Issue::id)
                                .toList(),
                        what.find() ? what.group(1).strip() : null));
    }

    /**
     * Offers one task that discusses an idea over every repository of the project; it records,
     * saves and announces nothing.
     */
    @PostMapping("/projects/{project}/ideas/discussion")
    public PlanningStartTask discussIdea(
            @PathVariable("project") final String project, final HttpServletRequest request) {
        final String name = name(project);
        final String idea = WorkRequests.idea(WorkRequests.read(request));
        return WorkAnswers.discussion(IdeaDiscussion.offer(board(name), idea));
    }

    /**
     * Saves the idea a discussion wrote as a story in state Idea with its issues, once per offer.
     *
     * @param project the project's name
     * @param request the request, whose body is the offer and the idea
     * @return the story's id, as created
     */
    @PostMapping("/projects/{project}/ideas")
    public ResponseEntity<IdeaAdded> handIdea(
            @PathVariable("project") final String project, final HttpServletRequest request) {
        final String name = name(project);
        final WorkRequests.HandIn handIn = WorkRequests.handIn(request);
        final String offer = handIn.request().offer();
        final String story;
        try {
            story = projects.work(name).idea(offer, handIn.request().idea(), handIn.shape());
        } catch (final StoreException.NotSavable e) {
            throw WorkRefusal.ideaRefused(
                    "The store cannot write the idea.",
                    List.of(bounded(String.valueOf(e.getMessage()))),
                    null);
        } catch (final UnsupportedOperationException e) {
            throw new Refusal(
                    "NotOffered",
                    HttpStatus.NOT_IMPLEMENTED,
                    "This planner does not add ideas.",
                    Map.of("project", name));
        } finally {
            changes.saved(name);
        }
        LOG.info(
                "Saved the idea of offer {} in project {} as story {}",
                LogText.of(offer),
                LogText.of(name),
                LogText.of(story));
        return ResponseEntity.status(HttpStatus.CREATED).body(new IdeaAdded(story));
    }

    /** Computes the proposal from one board without starting or recording work. */
    @PostMapping("/projects/{project}/stories/{story}/proposal")
    public StoryWorkProposal proposeStoryWork(
            @PathVariable("project") final String project,
            @PathVariable("story") final String id,
            final HttpServletRequest request) {
        final String checked = storyId(project, id);
        final WorkRequests.Proposal body = WorkRequests.proposal(WorkRequests.read(request));
        return WorkAnswers.proposal(
                projects.work(name(project)).propose(checked, body.to(), body.issues()));
    }

    /**
     * Answers the validated arguments the window starts, leaving board state and revision alone.
     */
    @PostMapping("/projects/{project}/stories/{story}/work")
    public StoryWorkOrder orderStoryWork(
            @PathVariable("project") final String project,
            @PathVariable("story") final String id,
            final HttpServletRequest request) {
        final String checked = storyId(project, id);
        final JsonNode json = WorkRequests.read(request);
        final Board board = board(project);
        final Story story = found(board, checked);
        final String to = WorkRequests.target(json);
        if (!board.workflow().states().contains(to)) {
            throw WorkRefusal.invalidSettings();
        }
        final WorkRequests.Request body = WorkRequests.request(json, story.state(), to);
        if (!project.equals(body.project()) || !checked.equals(body.story())) {
            throw WorkRefusal.invalidSettings();
        }
        try {
            final WorkRun.Order order =
                    projects.work(name(project))
                            .order(
                                    checked,
                                    to,
                                    WorkRequests.decision(body),
                                    WorkRequests.start(body));
            LOG.info(
                    "Offered {} tasks for story {} of project {} toward {}",
                    order.tasks().size(),
                    LogText.of(checked),
                    LogText.of(name(project)),
                    LogText.of(to));
            return WorkAnswers.order(order);
        } finally {
            changes.saved(name(project));
        }
    }

    /** Records a report against its saved offer and answers still-valid next Start arguments. */
    @PostMapping("/projects/{project}/tasks/{task}/events")
    public TaskEventReply taskEvent(
            @PathVariable("project") final String project,
            @PathVariable("task") final String task,
            final HttpServletRequest request) {
        final String name = name(project);
        final WorkRun.Report report = WorkRequests.event(WorkRequests.read(request, 131072));
        WorkRun.checkedReport(task, report);
        try {
            final WorkRun.Answer answer = projects.work(name).report(task, report);
            LOG.info(
                    "Task {} of project {} reported {}{}",
                    LogText.of(task),
                    LogText.of(name),
                    report.event(),
                    report.ended() == null
                            ? ""
                            : report.ended().finished() ? ", finished" : ", not finished");
            return new TaskEventReply(
                    answer.revision(),
                    WorkAnswers.knownWork(answer.board(), answer.work()),
                    WorkAnswers.starts(answer.tasks()));
        } catch (final UnsupportedOperationException unavailable) {
            throw notOffered(name);
        } finally {
            changes.saved(name);
        }
    }

    /** Reads the current run, preserving null when no local record exists. */
    @GetMapping("/projects/{project}/stories/{story}/work")
    public StoryWorkReply getStoryWork(
            @PathVariable("project") final String project,
            @PathVariable("story") final String story) {
        final String checked = storyId(project, story);
        return projects.work(name(project))
                .view(
                        (board, snapshot) -> {
                            found(board, checked);
                            return new StoryWorkReply(
                                    WorkAnswers.work(board, snapshot.runs().get(checked)));
                        });
    }

    /** Cancels follow-ups while leaving active tasks for the window to stop and report. */
    @PostMapping("/projects/{project}/stories/{story}/work/stop")
    public StoryWorkReply stopStoryWork(
            @PathVariable("project") final String project,
            @PathVariable("story") final String story,
            final HttpServletRequest request) {
        final String checked = storyId(project, story);
        final String run = WorkRequests.stop(request);
        try {
            final WorkRun.Run stopped = projects.work(name(project)).stop(checked, run);
            LOG.info(
                    "Stopped the work on story {} of project {}",
                    LogText.of(checked),
                    LogText.of(name(project)));
            return new StoryWorkReply(WorkAnswers.work(board(project), stopped));
        } finally {
            changes.saved(name(project));
        }
    }

    /** Applies a choice to the exact stopped reading the window saw. */
    @PostMapping("/projects/{project}/issues/{issue}/reading")
    public ReadingReply chooseReading(
            @PathVariable("project") final String project,
            @PathVariable("issue") final String issue,
            final HttpServletRequest request) {
        final String checked = checked(project, () -> Ids.issue(issue), "issue");
        final WorkRequests.ReadingRequest body = WorkRequests.reading(WorkRequests.read(request));
        final String story = storyId(project, body.story());
        final String name = name(project);
        try {
            final WorkRun.Answer answer =
                    projects.work(name)
                            .choose(
                                    story,
                                    checked,
                                    body.round(),
                                    ReadingCycle.Choice.valueOf(body.choice()),
                                    body.comment(),
                                    body.revision(),
                                    body.settings());
            final WorkRun.Snapshot snapshot = new WorkRun.Snapshot(0, Map.of(story, answer.work()));
            return new ReadingReply(
                    issue(answer.board(), answer.board().issue(checked).orElseThrow(), snapshot),
                            WorkAnswers.knownWork(answer.board(), answer.work()),
                    answer.revision(), WorkAnswers.starts(answer.tasks()));
        } catch (final UnsupportedOperationException unavailable) {
            throw notOffered(name);
        } finally {
            changes.saved(name);
        }
    }

    private Board board(final String project) {
        return projects.store(name(project)).board();
    }

    private static Refusal notOffered(final String project) {
        return new Refusal(
                "NotOffered",
                HttpStatus.NOT_IMPLEMENTED,
                "This store does not save work.",
                Map.of("project", project));
    }

    /**
     * Checks a project's name.
     *
     * @param project the name as given
     * @return the name
     * @throws Refusal {@code InvalidName} for a name the planner does not take
     */
    static String name(final String project) {
        try {
            return Ids.name(project);
        } catch (final IllegalArgumentException e) {
            throw Refusal.invalidName("The project " + e.getMessage(), Map.of());
        }
    }

    private static String storyId(final String project, final String id) {
        return checked(project, () -> Ids.story(id), "story");
    }

    private static Story found(final Board board, final String id) {
        return board.story(id).orElseThrow(() -> new StoreException.NoSuchStory(id));
    }

    // The project's name is checked first, so a refusal never names one the planner does not take.
    private static String checked(
            final String project, final Supplier<String> check, final String kind) {
        final String name = name(project);
        try {
            return check.get();
        } catch (final IllegalArgumentException e) {
            throw Refusal.invalidName("The " + kind + " id is invalid.", Map.of("project", name));
        }
    }

    private static String kind(
            final Workflow workflow, final WorkRules.Checked checked, final String state) {
        if (workflow.closed().contains(state)) {
            return "CLOSED";
        }
        return checked.rules().containsKey(state) ? "WORKING" : "WAITING";
    }

    private static org.fuin.porelo.planner.app.answers.WorkRule rule(
            final String state, final WorkRule rule) {
        return new org.fuin.porelo.planner.app.answers.WorkRule(
                state,
                rule.from(),
                rule.done(),
                rule.then(),
                rule.blocked(),
                org.fuin.porelo.planner.app.answers.WorkRule.PerEnum.fromValue(
                        rule.per().name().toLowerCase(Locale.ROOT)),
                rule.modes(),
                rule.order(),
                rule.models());
    }

    private static org.fuin.porelo.planner.app.answers.Story story(
            final Board board,
            final Story story,
            final WorkRun.Snapshot snapshot,
            final Moves.StoryState derive) {
        return new org.fuin.porelo.planner.app.answers.Story(
                story.id(),
                story.title(),
                story.state(),
                story.stateGiven(),
                Misfits.of(board, story, derive),
                Misfits.derivedState(board, story, derive),
                story.priority(),
                story.size(),
                story.link(),
                issues(board, story, snapshot),
                WorkAnswers.work(board, snapshot.runs().get(story.id())));
    }

    private static List<org.fuin.porelo.planner.app.answers.Issue> issues(
            final Board board, final Story story, final WorkRun.Snapshot snapshot) {
        final List<org.fuin.porelo.planner.app.answers.Issue> issues = new ArrayList<>();
        for (final String id : story.issues()) {
            board.issue(id).ifPresent(issue -> issues.add(issue(board, issue, snapshot)));
        }
        return issues;
    }

    private static org.fuin.porelo.planner.app.answers.Issue issue(
            final Board board, final Issue issue, final WorkRun.Snapshot snapshot) {
        return WorkAnswers.issue(board, issue, snapshot);
    }

    /** A store's words cut to the bound the description sets on a fault. */
    private static String bounded(final String words) {
        final int most = 1000;
        return words.codePointCount(0, words.length()) <= most
                ? words
                : words.substring(0, words.offsetByCodePoints(0, most - 1)) + "…";
    }
}
