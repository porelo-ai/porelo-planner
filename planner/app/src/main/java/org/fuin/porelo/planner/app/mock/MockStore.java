package org.fuin.porelo.planner.app.mock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves the window's mock board from memory for the window's tests; it reaches no Git and no
 * network.
 */
public final class MockStore implements PlanningStore {

    /** Where the mock board lies on the class path. */
    public static final String BOARD = "mock/board.json";

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

    /** Restores the board the store was made with, so each of the window's tests starts alike. */
    public void reset() {
        current = initial;
    }
}
