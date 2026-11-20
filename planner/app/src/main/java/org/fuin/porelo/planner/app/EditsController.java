package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.fuin.porelo.planner.app.answers.EditSaved;
import org.fuin.porelo.planner.app.answers.StoryAdded;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the edits a person, or an agent acting for them, keeps the board with as {@code
 * openapi/planner.yaml} describes them: a story made with its issues, an issue's fields, a story's
 * issues, an issue's blockers and a comment.
 */
@RestController
public class EditsController {

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
        throw notOffered(project);
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
        throw notOffered(project);
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
        throw notOffered(project);
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
        throw notOffered(project);
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
        throw notOffered(project);
    }

    private static Refusal notOffered(final String project) {
        return new Refusal(
                "NotOffered",
                HttpStatus.NOT_IMPLEMENTED,
                "This planner does not keep the board by hand yet.",
                Map.of("project", BoardController.name(project)));
    }
}
