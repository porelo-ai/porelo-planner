package org.fuin.porelo.planner.app.mock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves the window's mock board from memory for the window's tests and saves moves into it; it
 * reaches no Git and no network.
 */
public final class MockStore implements PlanningStore {

    /** Where the mock board lies on the class path. */
    public static final String BOARD = "mock/board.json";

    /**
     * The story whose save over more than one repository saves the first and fails the next, so the
     * window can test a partly saved batch.
     */
    public static final String PARTLY_SAVED = "ST292"; // a mock board's story, not-a-citation

    private static final String REVISION_PREFIX = "mock@";

    // A misspelled or missing field in the board must stop the start, not serve less.
    private static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .build();

    /**
     * The mock board as {@code mock/board.json} holds it.
     *
     * @param revision the board's fixed revision
     * @param repositories the project's repositories, its own first
     * @param workflow the states, phases and closed states
     * @param work the work rule per working state, in the shape of {@code stories/work.yml}
     * @param faults what the store could not read, in words
     * @param stories every story
     * @param issues every issue
     */
    record BoardFile(
            String revision,
            List<String> repositories,
            Workflow workflow,
            Map<String, RuleFile> work,
            List<String> faults,
            List<Story> stories,
            List<Issue> issues) {}

    /**
     * One work rule as {@code stories/work.yml} writes it.
     *
     * @param from the states the work starts from
     * @param done the state its success leads to
     * @param then the working state that follows, or null
     * @param blocked the working state blocking findings lead to, or null
     * @param per {@code story} or {@code repository}
     * @param modes the modes, the first the default
     * @param order the order the task is given
     * @param models the model per agent
     */
    record RuleFile(
            List<String> from,
            String done,
            @Nullable String then,
            @Nullable String blocked,
            String per,
            List<String> modes,
            String order,
            Map<String, String> models) {}

    private final Board initial;

    private volatile Board current;

    // Guarded by this store's monitor, as every change of the board is.
    private int saves;

    private static final Pattern ID = Pattern.compile("([A-Z]+)([0-9]+)");

    /**
     * Makes a store serving a board until it is changed or reset.
     *
     * @param board the board a reset restores
     */
    public MockStore(final Board board) {
        this.initial = board;
        this.current = board;
    }

    /**
     * Makes a store of its own on the board this one was made with, so a project's moves leave
     * another's board as it was.
     *
     * @return the new store
     */
    public MockStore copy() {
        return new MockStore(initial);
    }

    /**
     * Reads the mock board from the class path.
     *
     * @return a store serving it
     * @throws IllegalStateException if the board is missing or not sound
     */
    public static MockStore fromClassPath() {
        try (InputStream in = MockStore.class.getClassLoader().getResourceAsStream(BOARD)) {
            if (in == null) {
                throw new IllegalStateException(
                        "The mock board " + BOARD + " is not on the class path");
            }
            return new MockStore(read(in));
        } catch (final IOException e) {
            throw new UncheckedIOException("The mock board " + BOARD + " could not be read", e);
        }
    }

    /**
     * Reads a board in the shape of {@code mock/board.json}.
     *
     * @param in the board's JSON
     * @return the board
     * @throws IllegalStateException if the JSON does not hold a sound board
     */
    static Board read(final InputStream in) {
        final BoardFile file;
        try {
            file = MAPPER.readValue(in, BoardFile.class);
        } catch (final RuntimeException e) {
            throw new IllegalStateException("The mock board is not sound: " + e.getMessage(), e);
        }
        final Map<String, WorkRule> rules = new LinkedHashMap<>();
        file.work().forEach((state, rule) -> rules.put(state, rule(state, rule)));
        return new Board(
                new Revision(file.revision()),
                file.workflow(),
                file.stories(),
                file.issues(),
                new Work(rules, file.faults()),
                file.repositories());
    }

    private static WorkRule rule(final String state, final RuleFile rule) {
        final WorkRule.Per per =
                switch (rule.per()) {
                    case "story" -> WorkRule.Per.STORY;
                    case "repository" -> WorkRule.Per.REPOSITORY;
                    default ->
                            throw new IllegalStateException(
                                    "The mock board's rule for "
                                            + state
                                            + " has per '"
                                            + rule.per()
                                            + "', neither story nor repository");
                };
        return new WorkRule(
                rule.from(),
                rule.done(),
                rule.then(),
                rule.blocked(),
                per,
                rule.modes(),
                rule.order(),
                rule.models());
    }

    @Override
    public Board board() {
        return current;
    }

    @Override
    public Board refresh() {
        return current;
    }

    /**
     * Saves the writes into the board, all of them or none, and answers it with the next revision;
     * a batch moving issues of {@link #PARTLY_SAVED} in more than one repository saves only those
     * of the first repository pushed and names the next as failed.
     *
     * @throws StoreException.StaleRevision if the board moved since that revision
     * @throws StoreException.PartlySaved for a batch over the fixed story's repositories
     * @throws UnsupportedOperationException for a priority, which the mock does not save
     */
    @Override
    public synchronized Board save(final Revision revision, final Writes writes) {
        if (!writes.priorities().isEmpty()) {
            throw new UnsupportedOperationException("The mock board saves no priorities");
        }
        final Board board = current;
        if (!board.revision().equals(revision)) {
            throw new StoreException.StaleRevision(board.revision().value());
        }
        if (writes.none()) {
            return board;
        }
        final List<String> order = pushOrder(board, writes);
        if (order.size() > 1 && movesPartlySavedStory(board, writes)) {
            final String pushed = order.getFirst();
            current = saved(board, only(board, writes, pushed));
            throw new StoreException.PartlySaved(
                    order.get(1),
                    List.of(pushed),
                    "The mock refuses the push of "
                            + order.get(1)
                            + " for a batch over the issues of "
                            + PARTLY_SAVED);
        }
        current = saved(board, writes);
        return current;
    }

    /**
     * Derives a story's state as the stories index does: the earliest open state of its issues in
     * the workflow's order; with every issue closed, the first issue's state when all are rejected,
     * else the completed state. The mock's workflow names its completed state first among the
     * closed ones, every later closed state rejecting.
     */
    @Override
    public @Nullable String storyState(final List<String> issueStates) {
        if (issueStates.isEmpty()) {
            return null;
        }
        final Workflow workflow = current.workflow();
        final List<String> closed = workflow.closed();
        return issueStates.stream()
                .filter(state -> !closed.contains(state))
                .min(Comparator.comparingInt(workflow.states()::indexOf))
                .orElseGet(
                        () ->
                                issueStates.stream().allMatch(state -> closed.indexOf(state) > 0)
                                        ? issueStates.getFirst()
                                        : closed.getFirst());
    }

    /**
     * Adds an idea to the board in memory: its story and an issue in each repository it names,
     * numbered after the highest of their kind; nothing reaches a repository.
     */
    @Override
    public synchronized NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
        final Board board = current;
        if (!board.revision().equals(revision)) {
            throw new StoreException.StaleRevision(board.revision().value());
        }
        final List<Issue> issues = new ArrayList<>(board.issues());
        final List<String> ids = new ArrayList<>();
        for (final NewIssue issue : idea.issues()) {
            final String id = next(issues, issue.repository());
            ids.add(id);
            issues.add(
                    new Issue(
                            id,
                            issue.repository(),
                            issue.title(),
                            "Idea",
                            issue.size(),
                            List.of(),
                            null,
                            null,
                            "**What must be true.** " + issue.mustBeTrue() + "\n",
                            issue.worker(),
                            Risk.ORDINARY));
        }
        int story = 0;
        for (final Story known : board.stories()) {
            story = Math.max(story, Integer.parseInt(known.id().substring(2)));
        }
        final String id = "ST" + (story + 1);
        final List<Story> stories = new ArrayList<>(board.stories());
        stories.add(
                new Story(
                        id,
                        idea.title(),
                        "Idea",
                        false,
                        idea.priority(),
                        null,
                        ids,
                        idea.gain() + "\n\n" + idea.test(),
                        null));
        saves++;
        current =
                new Board(
                        new Revision(REVISION_PREFIX + (saves + 1)),
                        board.workflow(),
                        stories,
                        issues,
                        board.work(),
                        board.repositories(),
                        board.faults());
        return new NewIdea.Added(id, ids, current);
    }

    private static String next(final List<Issue> issues, final String repository) {
        String prefix = null;
        int highest = 0;
        for (final Issue issue : issues) {
            final Matcher id = ID.matcher(issue.id());
            if (issue.repository().equals(repository) && id.matches()) {
                if (prefix == null) {
                    prefix = id.group(1);
                }
                if (prefix.equals(id.group(1))) {
                    highest = Math.max(highest, Integer.parseInt(id.group(2)));
                }
            }
        }
        if (prefix == null) {
            throw new StoreException.NotSavable("", "The repository's issues give no prefix.");
        }
        return prefix + (highest + 1);
    }

    /** Restores the board the store was made with, so each of the window's tests starts alike. */
    public synchronized void reset() {
        current = initial;
        saves = 0;
    }

    /** The repositories a save writes, in the order the Git store pushes them: the own one last. */
    private static List<String> pushOrder(final Board board, final Writes writes) {
        final Set<String> written = new LinkedHashSet<>();
        writes.issues().keySet().forEach(id -> written.add(repository(board, id)));
        writes.workers().keySet().forEach(id -> written.add(repository(board, id)));
        final String own = board.repositories().getFirst();
        if (!writes.stories().isEmpty() || !writes.unstated().isEmpty()) {
            written.add(own);
        }
        final List<String> order = new ArrayList<>();
        board.repositories().stream()
                .filter(name -> !name.equals(own) && written.contains(name))
                .forEach(order::add);
        if (written.contains(own)) {
            order.add(own);
        }
        return order;
    }

    private static boolean movesPartlySavedStory(final Board board, final Writes writes) {
        return board.story(PARTLY_SAVED)
                .map(story -> story.issues().stream().anyMatch(writes.issues()::containsKey))
                .orElse(false);
    }

    /** The writes that land in one repository other than the own, which holds the stories. */
    private static Writes only(final Board board, final Writes writes, final String repository) {
        final Map<String, String> issues = new LinkedHashMap<>();
        writes.issues()
                .forEach(
                        (id, state) -> {
                            if (repository(board, id).equals(repository)) {
                                issues.put(id, state);
                            }
                        });
        final Map<String, Worker> workers = new LinkedHashMap<>();
        writes.workers()
                .forEach(
                        (id, worker) -> {
                            if (repository(board, id).equals(repository)) {
                                workers.put(id, worker);
                            }
                        });
        return new Writes(issues, Map.of(), List.of(), workers);
    }

    private static String repository(final Board board, final String issue) {
        return board.issue(issue)
                .orElseThrow(() -> new StoreException.NotSavable(issue, "No issue " + issue))
                .repository();
    }

    /**
     * The board with the writes in it, every story without a stated state following its issues;
     * called only with this store's monitor held, since it counts the saves.
     */
    private Board saved(final Board board, final Writes writes) {
        for (final String id : writes.stories().keySet()) {
            if (board.story(id).isEmpty()) {
                throw new StoreException.NotSavable(id, "No story " + id);
            }
        }
        final Map<String, String> states = new LinkedHashMap<>();
        final List<Issue> issues = new ArrayList<>();
        for (final Issue issue : board.issues()) {
            final String state = writes.issues().getOrDefault(issue.id(), issue.state());
            states.put(issue.id(), state);
            issues.add(
                    new Issue(
                            issue.id(),
                            issue.repository(),
                            issue.title(),
                            state,
                            issue.size(),
                            issue.blockedBy(),
                            issue.parent(),
                            issue.link(),
                            issue.text(),
                            writes.workers().getOrDefault(issue.id(), issue.worker()),
                            issue.risk()));
        }
        final List<Story> stories = new ArrayList<>();
        for (final Story story : board.stories()) {
            stories.add(story(story, writes, states));
        }
        final Board next =
                new Board(
                        new Revision(REVISION_PREFIX + (saves + 2)),
                        board.workflow(),
                        stories,
                        issues,
                        board.work(),
                        board.repositories(),
                        board.faults());
        saves++;
        return next;
    }

    /** A story with its stated state written or removed, or else following its issues' states. */
    private Story story(final Story story, final Writes writes, final Map<String, String> states) {
        final String stated = writes.stories().get(story.id());
        final boolean given =
                stated != null || story.stateGiven() && !writes.unstated().contains(story.id());
        final List<String> issueStates = new ArrayList<>();
        for (final String id : story.issues()) {
            final String issueState = states.get(id);
            if (issueState != null) {
                issueStates.add(issueState);
            }
        }
        final String derived = storyState(issueStates);
        final String state;
        if (stated != null) {
            state = stated;
        } else if (given || derived == null) {
            state = story.state();
        } else {
            state = derived;
        }
        return new Story(
                story.id(),
                story.title(),
                state,
                given,
                story.priority(),
                story.size(),
                story.issues(),
                story.description(),
                story.link());
    }
}
