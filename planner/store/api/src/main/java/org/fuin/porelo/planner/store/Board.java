package org.fuin.porelo.planner.store;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A project's whole board, read at one revision: every answer about a story or an issue at that
 * revision comes from it.
 *
 * @param revision the commits it was read from
 * @param workflow the states and phases
 * @param stories every story
 * @param issues every issue of every repository
 * @param work the work rules and what could not be read of them
 * @param repositories the project's repositories, its own first
 * @param faults each file or reference left out because it could not be read, naming it and why
 * @param owner the owner of the tracker that keeps the stories and issues, as the store verified it
 *     for the project; null for a board the project's own files hold
 */
public record Board(
        Revision revision,
        Workflow workflow,
        List<Story> stories,
        List<Issue> issues,
        Work work,
        List<String> repositories,
        List<String> faults,
        @Nullable String owner) {

    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /**
     * A board the project's own files hold, which no tracker keeps.
     *
     * @param revision the commits it was read from
     * @param workflow the states and phases
     * @param stories every story
     * @param issues every issue of every repository
     * @param work the work rules and what could not be read of them
     * @param repositories the project's repositories, its own first
     * @param faults each file or reference left out because it could not be read
     */
    public Board(
            final Revision revision,
            final Workflow workflow,
            final List<Story> stories,
            final List<Issue> issues,
            final Work work,
            final List<String> repositories,
            final List<String> faults) {
        this(revision, workflow, stories, issues, work, repositories, faults, null);
    }

    /**
     * A board with nothing left out.
     *
     * @param revision the commits it was read from
     * @param workflow the states and phases
     * @param stories every story
     * @param issues every issue of every repository
     * @param work the work rules
     * @param repositories the project's repositories, its own first
     */
    public Board(
            final Revision revision,
            final Workflow workflow,
            final List<Story> stories,
            final List<Issue> issues,
            final Work work,
            final List<String> repositories) {
        this(revision, workflow, stories, issues, work, repositories, List.of());
    }

    /** Keeps repositories in their issues' order for callers without a project definition. */
    public Board(
            final Revision revision,
            final Workflow workflow,
            final List<Story> stories,
            final List<Issue> issues,
            final Work work) {
        this(
                revision,
                workflow,
                stories,
                issues,
                work,
                issues.stream().map(Issue::repository).distinct().toList());
    }

    /**
     * Copies the lists and refuses an id twice, a state the workflow does not know or an owner that
     * could name more than one.
     */
    public Board {
        // The owner reaches Porelo's Start as part of an issue's reference.
        if (owner != null && (!OWNER.matcher(owner).matches() || owner.contains(".."))) {
            throw new IllegalArgumentException(
                    "Not a tracker's owner, " + owner.length() + " characters");
        }
        stories = List.copyOf(stories);
        issues = List.copyOf(issues);
        repositories = List.copyOf(repositories);
        faults = List.copyOf(faults);
        repositories.forEach(Ids::name);
        if (new HashSet<>(repositories).size() != repositories.size()) {
            throw new IllegalArgumentException("A board names a repository twice");
        }
        final Map<String, Story> storyIds = new LinkedHashMap<>();
        for (final Story story : stories) {
            if (storyIds.put(story.id(), story) != null) {
                throw new IllegalArgumentException(
                        "Story " + story.id() + " is on the board twice");
            }
            known(workflow, story.state(), story.id());
        }
        final Map<String, Issue> issueIds = new LinkedHashMap<>();
        for (final Issue issue : issues) {
            if (!repositories.contains(issue.repository())) {
                throw new IllegalArgumentException(
                        "An issue names a repository outside the project");
            }
            if (issueIds.put(issue.id(), issue) != null) {
                throw new IllegalArgumentException(
                        "Issue " + issue.id() + " is on the board twice");
            }
            known(workflow, issue.state(), issue.id());
        }
    }

    /**
     * Finds a story.
     *
     * @param id the story's id
     * @return the story, if the board has it
     */
    public Optional<Story> story(final String id) {
        return stories.stream().filter(story -> story.id().equals(id)).findFirst();
    }

    /**
     * Finds an issue.
     *
     * @param id the issue's id
     * @return the issue, if the board has it
     */
    public Optional<Issue> issue(final String id) {
        return issues.stream().filter(issue -> issue.id().equals(id)).findFirst();
    }

    private static void known(final Workflow workflow, final String state, final String id) {
        if (!workflow.states().contains(state)) {
            throw new IllegalArgumentException(id + " has the unknown state '" + state + "'");
        }
    }
}
