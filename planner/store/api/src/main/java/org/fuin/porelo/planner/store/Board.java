package org.fuin.porelo.planner.store;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * @param outside the issues left out because their Status is none of the workflow's states, each
 *     named among the faults too and none on the board; empty for a store without a tracker
 */
public record Board(
        Revision revision,
        Workflow workflow,
        List<Story> stories,
        List<Issue> issues,
        Work work,
        List<String> repositories,
        List<String> faults,
        @Nullable String owner,
        List<OutsideIssue> outside) {

    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /**
     * A board with no issue left out of its workflow.
     *
     * @param revision the commits it was read from
     * @param workflow the states and phases
     * @param stories every story
     * @param issues every issue of every repository
     * @param work the work rules and what could not be read of them
     * @param repositories the project's repositories, its own first
     * @param faults each file or reference left out because it could not be read, naming it and why
     * @param owner the owner of the tracker that keeps the stories and issues; null for a board the
     *     project's own files hold
     */
    public Board(
            final Revision revision,
            final Workflow workflow,
            final List<Story> stories,
            final List<Issue> issues,
            final Work work,
            final List<String> repositories,
            final List<String> faults,
            final @Nullable String owner) {
        this(revision, workflow, stories, issues, work, repositories, faults, owner, List.of());
    }

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
        outside = List.copyOf(outside);
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
        final Set<String> outsideIds = new HashSet<>();
        for (final OutsideIssue left : outside) {
            // A move names an issue by its id alone, so one id must name one issue.
            if (issueIds.containsKey(left.id()) || !outsideIds.add(left.id())) {
                throw new IllegalArgumentException("Issue " + left.id() + " is on the board twice");
            }
            if (!repositories.contains(left.repository())) {
                throw new IllegalArgumentException(
                        "An issue names a repository outside the project");
            }
            if (left.story() != null && !storyIds.containsKey(left.story())) {
                throw new IllegalArgumentException(
                        "Issue " + left.id() + " stands under a story the board does not hold");
            }
        }
        for (final OutsideIssue left : outside) {
            // Its parent may be outside the workflow too, and is moved under the same rules.
            if (left.parent() != null
                    && !issueIds.containsKey(left.parent())
                    && !outsideIds.contains(left.parent())) {
                throw new IllegalArgumentException(
                        "Issue " + left.id() + " stands under an issue the board does not hold");
            }
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

    /**
     * Finds an issue left out because its Status is none of the workflow's states.
     *
     * @param id the issue's id
     * @return the issue, if the board left it out so
     */
    public Optional<OutsideIssue> outsideIssue(final String id) {
        return outside.stream().filter(issue -> issue.id().equals(id)).findFirst();
    }

    private static void known(final Workflow workflow, final String state, final String id) {
        if (!workflow.states().contains(state)) {
            throw new IllegalArgumentException(id + " has the unknown state '" + state + "'");
        }
    }
}
