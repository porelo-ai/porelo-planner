package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.core.Moves;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRules;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
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

    /** The most moves one batch may hold. */
    private static final int MOST_MOVES = 200;

    /** A state's name as a refusal may repeat it; anything else is never echoed. */
    private static final Pattern STATE = Pattern.compile("[A-Za-z][A-Za-z0-9 _-]{0,63}");

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
     * @param confirm whether a story may stand where its issues do not fit
     */
    public record MovesBody(
            @Nullable String revision,
            @Nullable List<@Nullable MoveBody> moves,
            @Nullable Boolean confirm) {}

    /**
     * One move as the window sends it, naming a story or an issue.
     *
     * @param story the story's id, or null
     * @param issue the issue's id, or null
     * @param to the state it moves to
     */
    public record MoveBody(@Nullable String story, @Nullable String issue, @Nullable String to) {}

    /**
     * Saves a batch of moves, all or none.
     *
     * @param project the project's name
     * @param body the batch
     * @return the revision after the save and the issues moved
     */
    @PostMapping("/projects/{project}/moves")
    public Answers.Moved saveMoves(
            @PathVariable("project") final String project, @RequestBody final MovesBody body) {
        final String name = name(project);
        final List<Moves.Move> moves = moves(name, body);
        final String revision = body.revision();
        if (revision == null) {
            throw notAllowed("", "", "The body names no revision.");
        }
        final PlanningStore store = projects.store(name);
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
                return new Answers.Moved(board.revision().value(), planned.moved());
            }
            saved =
                    store.save(
                            board.revision(),
                            new Writes(planned.issues(), planned.stories(), planned.unstated()));
        } catch (final StoreException.PartlySaved e) {
            // The repositories pushed first hold the save, and every window shows what they hold.
            changes.saved(name);
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
            throw new Refusal(
                    "NotOffered",
                    HttpStatus.NOT_IMPLEMENTED,
                    "This planner does not save moves.",
                    Map.of("project", name));
        }
        changes.saved(name);
        return new Answers.Moved(saved.revision().value(), planned.moved());
    }

    private static List<Moves.Move> moves(final String project, final MovesBody body) {
        final List<@Nullable MoveBody> given = body.moves();
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
            moves.add(new Moves.Move(story != null, id, to));
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
        return ResponseEntity.noContent().build();
    }

    /**
     * Answers the workflow, each state's kind and the sound work rules.
     *
     * @param project the project's name
     * @return the workflow
     */
    @GetMapping("/projects/{project}/workflow")
    public Answers.Workflow workflow(@PathVariable("project") final String project) {
        return workflowOf(board(project));
    }

    /**
     * Maps a board's workflow, with its states' kinds and its sound work rules.
     *
     * @param board the board
     * @return the workflow's answer
     */
    static Answers.Workflow workflowOf(final Board board) {
        final Workflow workflow = board.workflow();
        final WorkRules.Checked checked = WorkRules.check(workflow, board.work());
        final List<Answers.WorkRule> work = new ArrayList<>();
        checked.rules().forEach((state, rule) -> work.add(rule(state, rule)));
        return new Answers.Workflow(
                board.revision().value(),
                workflow.states().stream()
                        .map(state -> new Answers.State(state, kind(workflow, checked, state)))
                        .toList(),
                workflow.phases().stream()
                        .map(phase -> new Answers.Phase(phase.name(), phase.states()))
                        .toList(),
                work,
                checked.faults());
    }

    /**
     * Maps the whole board as an event of the changes stream carries it.
     *
     * @param board the board
     * @return the workflow's answer and every story in one
     */
    static Answers.Board boardOf(final Board board) {
        final Answers.Workflow workflow = workflowOf(board);
        return new Answers.Board(
                workflow.revision(),
                workflow.states(),
                workflow.phases(),
                workflow.work(),
                workflow.faults(),
                board.stories().stream().map(story -> story(board, story)).toList());
    }

    /**
     * Answers the stories, all or those of the given states, without their descriptions.
     *
     * @param project the project's name
     * @param states the states asked for, none for all
     * @return the stories
     */
    @GetMapping("/projects/{project}/stories")
    public Answers.StoryList stories(
            @PathVariable("project") final String project,
            @RequestParam(name = "state", required = false) final @Nullable List<String> states) {
        final Board board = board(project);
        return new Answers.StoryList(
                board.revision().value(),
                board.stories().stream()
                        .filter(
                                story ->
                                        states == null
                                                || states.isEmpty()
                                                || states.contains(story.state()))
                        .map(story -> story(board, story))
                        .toList());
    }

    /**
     * Answers one story with its description.
     *
     * @param project the project's name
     * @param id the story's id
     * @return the story
     */
    @GetMapping("/projects/{project}/stories/{story}")
    public Answers.StoryReply story(
            @PathVariable("project") final String project, @PathVariable("story") final String id) {
        final String checked = storyId(project, id);
        final Board board = board(project);
        final Story story = found(board, checked);
        return new Answers.StoryReply(
                board.revision().value(),
                new Answers.StoryDetail(
                        story.id(),
                        story.title(),
                        story.state(),
                        story.stateGiven(),
                        story.priority(),
                        link(story.link()),
                        issues(board, story),
                        null,
                        story.description()));
    }

    /**
     * Answers exactly the issues of one story, in its order.
     *
     * @param project the project's name
     * @param id the story's id
     * @return the issues
     */
    @GetMapping("/projects/{project}/stories/{story}/issues")
    public Answers.IssueList storyIssues(
            @PathVariable("project") final String project, @PathVariable("story") final String id) {
        final String checked = storyId(project, id);
        final Board board = board(project);
        return new Answers.IssueList(
                board.revision().value(), issues(board, found(board, checked)));
    }

    /**
     * Answers one issue with its text and sub-issues.
     *
     * @param project the project's name
     * @param id the issue's id
     * @return the issue
     */
    @GetMapping("/projects/{project}/issues/{issue}")
    public Answers.IssueReply issue(
            @PathVariable("project") final String project, @PathVariable("issue") final String id) {
        final String checked = checked(project, () -> Ids.issue(id), "issue");
        final Board board = board(project);
        final Issue issue =
                board.issue(checked).orElseThrow(() -> new StoreException.NoSuchIssue(checked));
        final Matcher what = WHAT_MUST_BE_TRUE.matcher(issue.text());
        return new Answers.IssueReply(
                board.revision().value(),
                new Answers.IssueDetail(
                        issue.id(),
                        issue.repository(),
                        issue.title(),
                        issue.state(),
                        issue.size(),
                        issue.blockedBy(),
                        issue.parent(),
                        link(issue.link()),
                        issue.text(),
                        board.issues().stream()
                                .filter(other -> checked.equals(other.parent()))
                                .map(Issue::id)
                                .toList(),
                        what.find() ? what.group(1).strip() : null));
    }

    /** Computes the proposal from one board without starting or recording work. */
    @PostMapping("/projects/{project}/stories/{story}/proposal")
    public WorkAnswers.Proposal proposeStoryWork(
            @PathVariable("project") final String project,
            @PathVariable("story") final String id,
            final HttpServletRequest request) {
        final String checked = storyId(project, id);
        final WorkRequests.Proposal body = WorkRequests.proposal(WorkRequests.read(request));
        final Board board = board(project);
        return WorkAnswers.proposal(WorkProposal.propose(board, checked, body.to()));
    }

    /**
     * Answers the validated arguments the window starts, leaving board state and revision alone.
     */
    @PostMapping("/projects/{project}/stories/{story}/work")
    public WorkAnswers.Order orderStoryWork(
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
        return WorkAnswers.order(WorkOrder.accept(board, checked, to, WorkRequests.decision(body)));
    }

    private Board board(final String project) {
        return projects.store(name(project)).board();
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

    private static Answers.WorkRule rule(final String state, final WorkRule rule) {
        return new Answers.WorkRule(
                state,
                rule.from(),
                rule.done(),
                rule.then(),
                rule.blocked(),
                rule.per().name().toLowerCase(Locale.ROOT),
                rule.modes(),
                rule.order(),
                rule.models());
    }

    private static Answers.Story story(final Board board, final Story story) {
        return new Answers.Story(
                story.id(),
                story.title(),
                story.state(),
                story.stateGiven(),
                story.priority(),
                link(story.link()),
                issues(board, story),
                null);
    }

    private static List<Answers.Issue> issues(final Board board, final Story story) {
        final List<Answers.Issue> issues = new ArrayList<>();
        for (final String id : story.issues()) {
            board.issue(id).ifPresent(issue -> issues.add(issue(issue)));
        }
        return issues;
    }

    private static Answers.Issue issue(final Issue issue) {
        return new Answers.Issue(
                issue.id(),
                issue.repository(),
                issue.title(),
                issue.state(),
                issue.size(),
                issue.blockedBy(),
                issue.parent(),
                link(issue.link()));
    }

    private static @Nullable String link(final @Nullable URI link) {
        return link == null ? null : link.toString();
    }
}
