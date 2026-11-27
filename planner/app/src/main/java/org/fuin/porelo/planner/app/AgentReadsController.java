package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.app.answers.Breach;
import org.fuin.porelo.planner.app.answers.Breaches;
import org.fuin.porelo.planner.app.answers.IssueParts;
import org.fuin.porelo.planner.app.answers.Outline;
import org.fuin.porelo.planner.app.answers.OutlineIssue;
import org.fuin.porelo.planner.app.answers.OutlineStory;
import org.fuin.porelo.planner.app.answers.SearchAnswer;
import org.fuin.porelo.planner.app.answers.SearchMatch;
import org.fuin.porelo.planner.app.answers.TextParts;
import org.fuin.porelo.planner.core.AgentReads;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the reads an agent asks of the board the planner holds as {@code openapi/planner.yaml}
 * describes them; each reads one board and asks the store for nothing anew.
 */
@RestController
public class AgentReadsController {

    /** The most words a search takes. */
    private static final int MOST_WORDS = 10;

    /** The longest word a search takes. */
    private static final int LONGEST_WORD = 100;

    private final Projects projects;

    /**
     * Makes the controller.
     *
     * @param projects the projects the window has given
     */
    public AgentReadsController(final Projects projects) {
        this.projects = projects;
    }

    /**
     * Answers the board in short.
     *
     * @param project the project's name
     * @return the stories with their issues, then the issues in no story
     */
    @GetMapping("/projects/{project}/outline")
    public Outline getOutline(@PathVariable("project") final String project) {
        final Board board = board(project);
        final AgentReads.Outline outline = AgentReads.outline(board);
        return new Outline(
                board.revision().value(),
                outline.stories().stream().map(AgentReadsController::story).toList(),
                outline.issues().stream().map(AgentReadsController::issue).toList());
    }

    /**
     * Answers one issue with its text by parts.
     *
     * @param project the project's name
     * @param id the issue's id
     * @return the issue
     */
    @GetMapping("/projects/{project}/issues/{issue}/parts")
    public IssueParts getIssueParts(
            @PathVariable("project") final String project, @PathVariable("issue") final String id) {
        final String name = BoardController.name(project);
        final String checked;
        try {
            checked = Ids.issue(id);
        } catch (final IllegalArgumentException e) {
            throw Refusal.invalidName("The issue id is invalid.", Map.of("project", name));
        }
        final Board board = projects.store(name).board();
        final AgentReads.IssueParts read =
                AgentReads.parts(board, checked, IssueTextSplit.INSTANCE);
        final var issue = read.issue();
        final Map<String, String> held = read.split().parts();
        return new IssueParts(
                board.revision().value(),
                issue.version().value(),
                issue.id(),
                issue.repository(),
                issue.title(),
                issue.state(),
                issue.size(),
                issue.priority(),
                issue.worker().name(),
                read.story(),
                issue.blockedBy(),
                held == null ? null : textParts(held),
                held == null ? issue.text() : null,
                held == null ? read.split().fault() : null);
    }

    /**
     * Answers the breaches of the stories' rules.
     *
     * @param project the project's name
     * @return the breaches, none when the board breaks no rule
     */
    @GetMapping("/projects/{project}/breaches")
    public Breaches listBreaches(@PathVariable("project") final String project) {
        final Board board = board(project);
        return new Breaches(
                board.revision().value(),
                AgentReads.breaches(board).stream()
                        .map(
                                breach ->
                                        new Breach(
                                                Breach.RuleEnum.fromValue(breach.rule().spelled()),
                                                breach.story(),
                                                breach.issues(),
                                                breach.words()))
                        .toList());
    }

    /**
     * Finds the stories and issues whose title or text holds the words.
     *
     * @param project the project's name
     * @param request the request, whose {@code words} query parameter repeats once per word
     * @return the matching items
     */
    @GetMapping("/projects/{project}/search")
    public SearchAnswer searchItems(
            @PathVariable("project") final String project, final HttpServletRequest request) {
        final String name = BoardController.name(project);
        final List<String> words = words(request, name);
        final Board board = projects.store(name).board();
        final AgentReads.Found found = AgentReads.search(board, words, IssueTextSplit.INSTANCE);
        return new SearchAnswer(
                board.revision().value(),
                found.matches().stream()
                        .map(
                                match ->
                                        new SearchMatch(
                                                match.id(),
                                                SearchMatch.KindEnum.valueOf(match.kind().name()),
                                                match.title(),
                                                match.state(),
                                                match.found()))
                        .toList(),
                found.more());
    }

    private Board board(final String project) {
        return projects.store(BoardController.name(project)).board();
    }

    // The query is read from the request, since a list parameter would split a word at a comma.
    private static List<String> words(final HttpServletRequest request, final String name) {
        final String @Nullable [] given = request.getParameterValues("words");
        if (given == null
                || given.length < 1
                || given.length > MOST_WORDS
                || java.util.Arrays.stream(given)
                        .anyMatch(
                                word ->
                                        word.isBlank()
                                                || word.codePointCount(0, word.length())
                                                        > LONGEST_WORD)) {
            throw Refusal.invalidName(
                    "The words query parameter takes 1 to "
                            + MOST_WORDS
                            + " words of 1 to "
                            + LONGEST_WORD
                            + " characters.",
                    Map.of("project", name));
        }
        return List.of(given);
    }

    private static OutlineStory story(final AgentReads.OutlineStory story) {
        return new OutlineStory(
                story.id(),
                story.title(),
                story.state(),
                story.priority(),
                story.issues().stream().map(AgentReadsController::issue).toList());
    }

    private static OutlineIssue issue(final AgentReads.OutlineIssue issue) {
        return new OutlineIssue(
                issue.id(), issue.title(), issue.state(), issue.size(), issue.priority());
    }

    private static TextParts textParts(final Map<String, String> parts) {
        return new TextParts(
                parts.get("covers"),
                parts.get("whyThisSize"),
                parts.get("whatMustBeTrue"),
                parts.get("plan"),
                parts.get("acceptance"),
                parts.get("openQuestions"),
                parts.get("toBeChecked"),
                parts.get("findings"));
    }
}
