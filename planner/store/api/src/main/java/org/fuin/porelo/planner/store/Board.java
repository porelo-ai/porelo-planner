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
 * <p>Every store keeps two rules. An item is never left out: what a store cannot read in a story or
 * an issue is a {@link Warning} on it, and only what has no place on the board at all is a fault. A
 * value an item states - its state, its priority, its sums - counts as stated; a store never checks
 * it against the item's text or history, and never warns because they differ.
 *
 * @param revision the commits it was read from
 * @param workflow the states and phases
 * @param stories every story
 * @param issues every issue of every repository
 * @param work the work rules and what could not be read of them
 * @param repositories the project's repositories, its own first
 * @param faults what has no place on the board at all, such as a file that names no item, naming it
 *     and why; an item the store could not fully read is on the board with its warnings instead
 * @param owner the owner of the tracker that keeps the stories and issues, as the store verified it
 *     for the project; null for a board the project's own files hold
 * @param unlisted the open issues of the project's repositories that are no items of the project,
 *     none on the board; empty for a store without a tracker
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
        List<UnlistedIssue> unlisted) {

    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /**
     * A board that knows no open issue outside the project.
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
        unlisted = List.copyOf(unlisted);
        repositories.forEach(Ids::name);
        if (new HashSet<>(repositories).size() != repositories.size()) {
            throw new IllegalArgumentException("A board names a repository twice");
        }
        final Map<String, Story> storyIds = new LinkedHashMap<>();
        for (final Story story : stories) {
            final Story before = storyIds.put(story.id(), story);
            if (before != null && !(twice(before.warnings()) && twice(story.warnings()))) {
                throw new IllegalArgumentException(
                        "Story " + story.id() + " is on the board twice");
            }
            known(workflow, story.state(), story.id(), story.warnings());
            if (story.state().isEmpty() && story.stateGiven()) {
                // A save would write the empty state back as one the story states.
                throw new IllegalArgumentException(
                        "Story " + story.id() + " states no state but is marked as stating one");
            }
        }
        final Map<String, Issue> issueIds = new LinkedHashMap<>();
        for (final Issue issue : issues) {
            if (!repositories.contains(issue.repository())) {
                throw new IllegalArgumentException(
                        "An issue names a repository outside the project");
            }
            final Issue before = issueIds.put(issue.id(), issue);
            if (before != null && !(twice(before.warnings()) && twice(issue.warnings()))) {
                throw new IllegalArgumentException(
                        "Issue " + issue.id() + " is on the board twice");
            }
            known(workflow, issue.state(), issue.id(), issue.warnings());
        }
        final Set<String> unlistedIds = new HashSet<>();
        for (final UnlistedIssue made : unlisted) {
            // An add names it by its id alone, so one id must name one issue.
            if (issueIds.containsKey(made.id())
                    || storyIds.containsKey(made.id())
                    || !unlistedIds.add(made.id())) {
                throw new IllegalArgumentException("Issue " + made.id() + " is on the board twice");
            }
            if (!repositories.contains(made.repository())) {
                throw new IllegalArgumentException(
                        "An issue names a repository outside the project");
            }
        }
    }

    /**
     * Finds a story.
     *
     * @param id the story's id
     * @return the story, if the board has it and its id names no other story
     */
    public Optional<Story> story(final String id) {
        final List<Story> found = stories.stream().filter(story -> story.id().equals(id)).toList();
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /**
     * Finds an issue.
     *
     * @param id the issue's id
     * @return the issue, if the board has it and its id names no other issue
     */
    public Optional<Issue> issue(final String id) {
        final List<Issue> found = copies(id);
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /**
     * Says why an id names no one item: the board holds two stories or two issues of it, each
     * warned {@link Warning#ID_TWICE}, and an id alone cannot tell which a move, an edit or a work
     * means.
     *
     * @param id a story's or an issue's id
     * @return the words of a refusal, naming the repositories of each issue; empty when the id
     *     names one item or none
     */
    public Optional<String> twice(final String id) {
        final List<Issue> found = copies(id);
        if (found.size() > 1) {
            return Optional.of(
                    id
                            + " names an issue in "
                            + spoken(found.stream().map(Issue::repository).toList())
                            + "; it can be neither worked nor moved until one id is changed.");
        }
        if (stories.stream().filter(story -> story.id().equals(id)).count() > 1) {
            return Optional.of(
                    id
                            + " names two stories; it can be neither worked nor moved until one id"
                            + " is changed.");
        }
        return Optional.empty();
    }

    /**
     * Says why no work, move or edit may start on an item: its id names two items, or it carries a
     * warning about its state, parent, waits or id. The one check every route to work or a change
     * passes.
     *
     * @param id a story's or an issue's id
     * @return the words of a refusal; empty when nothing holds the item back
     */
    public Optional<String> holdsWork(final String id) {
        return held(id, Warning::holdsWork);
    }

    /**
     * Says why no move may name an item: what {@link #holdsWork(String)} says, but for a warning
     * about its state alone, since a move into a state of the workflow is what sets that right.
     *
     * @param id a story's or an issue's id
     * @return the words of a refusal; empty when the item may be moved
     */
    public Optional<String> holdsMove(final String id) {
        return held(
                id,
                warning ->
                        warning.holdsWork()
                                && !warning.concerns().equals(Set.of(Warning.Concern.STATE)));
    }

    private Optional<String> held(
            final String id, final java.util.function.Predicate<Warning> holds) {
        final Optional<String> twice = twice(id);
        if (twice.isPresent()) {
            return twice;
        }
        final List<Warning> warnings =
                issue(id)
                        .map(Issue::warnings)
                        .or(() -> story(id).map(Story::warnings))
                        .orElse(List.of());
        final List<String> texts = warnings.stream().filter(holds).map(Warning::text).toList();
        return texts.isEmpty()
                ? Optional.empty()
                : Optional.of(id + ": " + String.join(" ", texts));
    }

    private List<Issue> copies(final String id) {
        return issues.stream().filter(issue -> issue.id().equals(id)).toList();
    }

    /** Names joined as words: {@code A and B}, {@code A, B and C}. */
    private static String spoken(final List<String> names) {
        if (names.size() < 2) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    private static boolean twice(final List<Warning> warnings) {
        return Warning.has(warnings, Warning.ID_TWICE);
    }

    /**
     * Refuses a state the workflow does not know unless the item is warned about its state, and an
     * empty one unless it is warned that no state can be told.
     */
    private static void known(
            final Workflow workflow,
            final String state,
            final String id,
            final List<Warning> warnings) {
        if (state.isEmpty()
                ? Warning.has(warnings, Warning.NO_STATE)
                : Warning.anyAboutState(warnings)) {
            return;
        }
        if (!workflow.states().contains(state)) {
            throw new IllegalArgumentException(id + " has the unknown state '" + state + "'");
        }
    }
}
