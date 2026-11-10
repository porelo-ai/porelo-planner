package org.fuin.porelo.planner.app;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.core.WorkRules;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a project's board as {@code openapi/planner.yaml} describes it; each answer is read from
 * one board, so its parts and its revision agree.
 */
@RestController
public class BoardController {

    private static final Pattern WHAT_MUST_BE_TRUE =
            Pattern.compile(
                    "(?m)^\\*\\*What must be true\\.\\*\\*[ \\t]*(.+(?:\\r?\\n(?!\\s*\\n).+)*)");

    private final Projects projects;

    /**
     * Makes the controller.
     *
     * @param projects the projects the window has given
     */
    public BoardController(final Projects projects) {
        this.projects = projects;
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
            throw Refusal.invalidName(
                    "The " + kind + " " + e.getMessage(), Map.of("project", name));
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
