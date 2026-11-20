package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.planner.app.answers.EditSaved;
import org.fuin.porelo.planner.app.answers.StoryAdded;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.StoreException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the edits a person, or an agent acting for them, keeps the board with as {@code
 * openapi/planner.yaml} describes them: a story made with its issues, an issue's fields, a story's
 * issues, an issue's blockers and a comment. Each checks the request, reads it into its record,
 * calls {@link BoardEdits} once and answers what it answered; the core's and the store's refusals
 * are answered by {@link Problems}.
 */
@RestController
public class EditsController {

    private final BoardEdits edits;

    /**
     * Makes the controller.
     *
     * @param edits the edits' use cases
     */
    public EditsController(final BoardEdits edits) {
        this.edits = edits;
    }

    /**
     * Makes a story with its issues.
     *
     * @param project the project's name
     * @param request the request, whose body is the idea and the revision
     * @return the story's and its issues' ids and the revision after the save, as created
     */
    @PostMapping("/projects/{project}/stories")
    public ResponseEntity<StoryAdded> createStory(
            @PathVariable("project") final String project, final HttpServletRequest request) {
        final String name = BoardController.name(project);
        final EditRequests.NewStory body = EditRequests.story(request);
        final NewIdea.Added added;
        try {
            added =
                    offered(
                            name,
                            "This planner does not add stories.",
                            () ->
                                    edits.createStory(
                                            name,
                                            body.request().revision(),
                                            body.request().idea(),
                                            body.shape()));
        } catch (final StoreException.NotSavable e) {
            throw WorkRefusal.ideaRefused(
                    "The store cannot write the idea.",
                    List.of(bounded(String.valueOf(e.getMessage()))),
                    null);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new StoryAdded(
                                added.story(), added.issues(), added.board().revision().value()));
    }

    /**
     * Sets an issue's size, estimate, priority and rank.
     *
     * @param project the project's name
     * @param issue the issue's id
     * @param request the request, whose body is the fields and the revision
     * @return the revision after the save
     */
    @PutMapping("/projects/{project}/issues/{issue}/fields")
    public EditSaved setIssueFields(
            @PathVariable("project") final String project,
            @PathVariable("issue") final String issue,
            final HttpServletRequest request) {
        final String name = BoardController.name(project);
        final String id = issueId(name, issue);
        final EditRequests.FieldsEdit body = EditRequests.fields(request, id);
        return new EditSaved(
                offered(
                        name,
                        "This planner does not set an issue's fields.",
                        () -> edits.setFields(name, id, body.revision(), body.fields())));
    }

    /**
     * Sets a story's whole issue list.
     *
     * @param project the project's name
     * @param story the story's id
     * @param request the request, whose body is the list and the revision
     * @return the revision after the save
     */
    @PutMapping("/projects/{project}/stories/{story}/issues")
    public EditSaved setStoryIssues(
            @PathVariable("project") final String project,
            @PathVariable("story") final String story,
            final HttpServletRequest request) {
        final String name = BoardController.name(project);
        final String id = checked(name, () -> Ids.story(story), "story");
        final EditRequests.StoryIssuesRequest body = EditRequests.storyIssues(request, id);
        return new EditSaved(
                offered(
                        name,
                        "This planner does not set a story's issues.",
                        () -> edits.setStoryIssues(name, id, body.revision(), body.issues())));
    }

    /**
     * Sets an issue's whole list of blockers.
     *
     * @param project the project's name
     * @param issue the issue's id
     * @param request the request, whose body is the list and the revision
     * @return the revision after the save
     */
    @PutMapping("/projects/{project}/issues/{issue}/blockedBy")
    public EditSaved setBlockedBy(
            @PathVariable("project") final String project,
            @PathVariable("issue") final String issue,
            final HttpServletRequest request) {
        final String name = BoardController.name(project);
        final String id = issueId(name, issue);
        final EditRequests.BlockedByRequest body = EditRequests.blockedBy(request, id);
        return new EditSaved(
                offered(
                        name,
                        "This planner does not set an issue's blockers.",
                        () -> edits.setBlockedBy(name, id, body.revision(), body.blockedBy())));
    }

    /**
     * Writes a comment on an issue.
     *
     * @param project the project's name
     * @param issue the issue's id
     * @param request the request, whose body is the text
     * @return no content
     */
    @PostMapping("/projects/{project}/issues/{issue}/comments")
    public ResponseEntity<Void> comment(
            @PathVariable("project") final String project,
            @PathVariable("issue") final String issue,
            final HttpServletRequest request) {
        final String name = BoardController.name(project);
        final String id = issueId(name, issue);
        final EditRequests.CommentRequest body = EditRequests.comment(request, id);
        offered(
                name,
                "This planner does not write comments.",
                () -> {
                    edits.comment(name, id, body.text());
                    return id;
                });
        return ResponseEntity.noContent().build();
    }

    /** Answers a store that does not offer the edit as not offered, never as a server fault. */
    private static <T> T offered(final String name, final String words, final Supplier<T> edit) {
        try {
            return edit.get();
        } catch (final UnsupportedOperationException e) {
            throw new Refusal(
                    "NotOffered", HttpStatus.NOT_IMPLEMENTED, words, Map.of("project", name));
        }
    }

    private static String issueId(final String name, final String issue) {
        return checked(name, () -> Ids.issue(issue), "issue");
    }

    // The project's name is checked first, so a refusal never names one the planner does not take.
    private static String checked(
            final String name, final Supplier<String> check, final String kind) {
        try {
            return check.get();
        } catch (final IllegalArgumentException e) {
            throw Refusal.invalidName("The " + kind + " id is invalid.", Map.of("project", name));
        }
    }

    private static String bounded(final String words) {
        return words.length() <= 1000 ? words : words.substring(0, 1000);
    }
}
