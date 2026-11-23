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
 * @param repositories the project's repositories, its own first, each with the folder a task
 *     reading the whole project finds it in
 * @param faults what has no place on the board at all, such as a file that names no item, naming it
 *     and why; an item the store could not fully read is on the board with its warnings instead
 * @param owner the owner of the tracker that keeps the stories and issues, as the store verified it
 *     for the project; null for a board the project's own files hold
 * @param unlisted the open issues of the project's repositories that are no items of the project,
 *     none on the board; empty for a store without a tracker
 * @param repositoryWarnings what is wrong in the project's repositories themselves, at most one of
 *     a kind per repository, in the repositories' order; empty for a store that keeps no
 *     repository's labels
 */
public record Board(
        Revision revision,
        Workflow workflow,
        List<Story> stories,
        List<Issue> issues,
        Work work,
        List<ProjectRepository> repositories,
        List<String> faults,
        @Nullable String owner,
        List<UnlistedIssue> unlisted,
        List<RepositoryWarning> repositoryWarnings) {

    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /**
     * A board without a warning about a repository itself.
     *
     * @param revision the commits it was read from
     * @param workflow the states and phases
     * @param stories every story
     * @param issues every issue of every repository
     * @param work the work rules and what could not be read of them
     * @param repositories the project's repositories, its own first
     * @param faults what has no place on the board at all
     * @param owner the owner of the tracker that keeps the stories and issues, or null
     * @param unlisted the open issues of the project's repositories that are no items of it
     */
    public Board(
            final Revision revision,
            final Workflow workflow,
            final List<Story> stories,
            final List<Issue> issues,
            final Work work,
            final List<ProjectRepository> repositories,
            final List<String> faults,
            final @Nullable String owner,
            final List<UnlistedIssue> unlisted) {
        this(
                revision,
                workflow,
                stories,
                issues,
                work,
                repositories,
                faults,
                owner,
                unlisted,
                List.of());
    }

    /**
     * The same board with the warnings about its repositories given and the faults added.
     *
     * @param warnings what is wrong in the repositories themselves, in their order
     * @param more faults to add after those it has
     * @return the board
     */
    public Board withRepositories(final List<RepositoryWarning> warnings, final List<String> more) {
        final List<String> all = new java.util.ArrayList<>(faults);
        all.addAll(more);
        return new Board(
                revision,
                workflow,
                stories,
                issues,
                work,
                repositories,
                all,
                owner,
                unlisted,
                warnings);
    }

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
            final List<ProjectRepository> repositories,
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
            final List<ProjectRepository> repositories,
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
            final List<ProjectRepository> repositories) {
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
                ProjectRepository.named(
                        issues.stream().map(Issue::repository).distinct().toList()));
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
        repositoryWarnings = List.copyOf(repositoryWarnings);
        final List<String> names = ProjectRepository.names(repositories);
        if (new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("A board names a repository twice");
        }
        // A task reading the whole project finds each repository in a folder of its own.
        if (repositories.stream().map(ProjectRepository::folder).distinct().count()
                != repositories.size()) {
            throw new IllegalArgumentException("A board gives two repositories one folder");
        }
        final Map<String, Story> storyIds = new LinkedHashMap<>();
        for (final Story story : stories) {
            final Story before = storyIds.put(story.id(), story);
            if (before != null && !(twice(before.warnings()) && twice(story.warnings()))) {
                throw new IllegalArgumentException(
                        "Story " + story.id() + " is on the board twice");
            }
            known(workflow, story.state(), story.id(), story.warnings());
        }
        final Map<String, Issue> issueIds = new LinkedHashMap<>();
        for (final Issue issue : issues) {
            if (!names.contains(issue.repository())) {
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
            if (!names.contains(made.repository())) {
                throw new IllegalArgumentException(
                        "An issue names a repository outside the project");
            }
        }
        final Set<String> warned = new HashSet<>();
        for (final RepositoryWarning warning : repositoryWarnings) {
            // A correction names its repository and kind alone, so they name one warning.
            if (!names.contains(warning.repository())
                    || !warned.add(warning.repository() + "/" + warning.kind())) {
                throw new IllegalArgumentException(
                        "A repository's warning names one outside the project, or twice");
            }
        }
    }

    /**
     * Finds the warning of a kind about one of the project's repositories.
     *
     * @param repository the repository's name
     * @param kind the warning's kind
     * @return the warning, or empty when the board holds none
     */
    public Optional<RepositoryWarning> repositoryWarning(
            final String repository, final String kind) {
        return repositoryWarnings.stream()
                .filter(w -> w.repository().equals(repository) && w.kind().equals(kind))
                .findFirst();
    }

    /**
     * The names of the project's repositories, which an issue's repository carries.
     *
     * @return their names, its own first
     */
    public List<String> names() {
        return ProjectRepository.names(repositories);
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
     * Says why no work, move or edit may start on an item: its id names two items, it carries a
     * warning that holds work back, or a parent above it is held so. The one check every route to
     * work or a change passes.
     *
     * @param id a story's or an issue's id
     * @return the words of a refusal; empty when nothing holds the item back
     */
    public Optional<String> holdsWork(final String id) {
        return held(id, Warning::holdsWork);
    }

    /**
     * Says why no move may name an item: what {@link #holdsWork(String)} says, but for a warning
     * about its state alone, its own or a parent's, since a move into a state of the workflow is
     * what sets that right.
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
        final Optional<String> own = heldItself(id, holds);
        if (own.isPresent()) {
            return own;
        }
        // Work under a parent a person stopped, or the board cannot place, is stopped with it.
        final Set<String> seen = new HashSet<>(Set.of(id));
        String parent = parentOf(id);
        while (parent != null && seen.add(parent)) {
            final Optional<String> above = heldItself(parent, holds);
            if (above.isPresent()) {
                return Optional.of(
                        id
                                + ": it stands under "
                                + parent
                                + ", which is held back. "
                                + above.get());
            }
            parent = parentOf(parent);
        }
        return Optional.empty();
    }

    /** Why the item itself is held back, by its id or its own warnings. */
    private Optional<String> heldItself(
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

    /** The parent of the one issue of an id, or null for a story, an id on two issues or none. */
    private @Nullable String parentOf(final String id) {
        final List<Issue> found = copies(id);
        return found.size() == 1 ? found.getFirst().parent() : null;
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
