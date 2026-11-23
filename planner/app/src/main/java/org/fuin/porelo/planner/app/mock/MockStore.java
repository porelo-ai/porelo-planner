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
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.git.ResultText;
import org.fuin.porelo.planner.store.github.BodySections;
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
     * @param risks what the rule chooses per risk, by the risk's name, or null for nothing
     * @param result what the stage hands back instead of pushing, or null when it pushes
     */
    record RuleFile(
            List<String> from,
            String done,
            @Nullable String then,
            @Nullable String blocked,
            String per,
            List<String> modes,
            String order,
            Map<String, String> models,
            @Nullable Map<String, RiskFile> risks,
            WorkRule.@Nullable Result result) {}

    /**
     * What a rule of the mock board chooses for one risk.
     *
     * @param models the model per agent, empty when it names none
     * @param effort the effort, or null when it names none
     */
    record RiskFile(Map<String, String> models, @Nullable String effort) {}

    private final Board initial;

    private volatile Board current;

    // Guarded by this store's monitor, as every change of the board is.
    private int saves;

    /** The fields of each issue the board does not show, as set; guarded by the monitor. */
    private final Map<String, Map<Fields.Field, Object>> kept = new java.util.HashMap<>();

    /** The reasons kept with each closed item, in the order saved; guarded by the monitor. */
    private final Map<String, List<Writes.Reason>> reasons = new java.util.HashMap<>();

    private static final Writes NOTHING = new Writes(Map.of(), Map.of(), List.of());

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
        // Corrections are offered as the Git store offers them: for a state alone.
        final Work work = new Work(rules, file.faults());
        final org.fuin.porelo.planner.store.Correction move =
                org.fuin.porelo.planner.store.Correction.ofState(false, file.workflow(), work);
        final org.fuin.porelo.planner.store.Correction stated =
                org.fuin.porelo.planner.store.Correction.ofState(true, file.workflow(), work);
        return new Board(
                new Revision(file.revision()),
                file.workflow(),
                file.stories().stream()
                        .map(story -> story.withWarnings(offering(story.warnings(), stated)))
                        .toList(),
                file.issues().stream()
                        .map(issue -> issue.withWarnings(offering(issue.warnings(), move)))
                        .toList(),
                work,
                file.repositories());
    }

    /** Each warning about the state alone with the correction given, the others with none. */
    private static List<org.fuin.porelo.planner.store.Warning> offering(
            final List<org.fuin.porelo.planner.store.Warning> warnings,
            final org.fuin.porelo.planner.store.@Nullable Correction correction) {
        return warnings.stream()
                .map(
                        warning ->
                                warning.offering(
                                        warning.kind()
                                                                .equals(
                                                                        org.fuin.porelo.planner
                                                                                .store.Warning
                                                                                .STATE_OUTSIDE_WORKFLOW)
                                                        || warning.kind()
                                                                .equals(
                                                                        org.fuin.porelo.planner
                                                                                .store.Warning
                                                                                .NO_STATE)
                                                ? correction
                                                : null))
                .toList();
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
                rule.models(),
                risks(rule.risks()),
                rule.result());
    }

    private static Map<Risk, WorkRule.ForRisk> risks(final @Nullable Map<String, RiskFile> risks) {
        final Map<Risk, WorkRule.ForRisk> chosen = new LinkedHashMap<>();
        if (risks != null) {
            risks.forEach(
                    (name, risk) ->
                            chosen.put(
                                    Risk.valueOf(name),
                                    new WorkRule.ForRisk(risk.models(), risk.effort())));
        }
        return chosen;
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
        keep(writes.issueReasons());
        keep(writes.storyReasons());
        return current;
    }

    /** Whether a move's reason is kept: in memory, with the item it closes. */
    @Override
    public boolean keepsReasons() {
        return true;
    }

    /**
     * Answers the reasons kept with an item.
     *
     * @param id the story's or the issue's id
     * @return its reasons, in the order saved
     */
    synchronized List<Writes.Reason> reasons(final String id) {
        return List.copyOf(reasons.getOrDefault(id, List.of()));
    }

    /**
     * Keeps each reason once per item, state and text, as a store writing marked comments does: a
     * new text after a reopen is kept again.
     */
    private void keep(final Map<String, Writes.Reason> given) {
        given.forEach(
                (id, reason) -> {
                    final List<Writes.Reason> held =
                            reasons.computeIfAbsent(id, key -> new ArrayList<>());
                    if (!held.contains(reason)) {
                        held.add(reason);
                    }
                });
    }

    /**
     * Saves a stage's result into the board in memory: each issue's state, size and a text built
     * from the result, refused as a whole when an issue no longer stands in the state the stage
     * completes or would move to a closed state.
     */
    @Override
    public synchronized Board saveResult(final StageResult result) {
        final Board board = current;
        final Map<String, StageResult.ForIssue> given = new LinkedHashMap<>();
        final Map<String, String> states = new LinkedHashMap<>();
        for (final StageResult.ForIssue issue : result.issues()) {
            final Issue known = issueOf(board, issue.id());
            if (!known.state().equals(issue.from())) {
                throw new StoreException.NotSavable(
                        issue.id(), "It stands in " + known.state() + ", not in " + issue.from());
            }
            if (!board.workflow().states().contains(issue.to())
                    || board.workflow().closed().contains(issue.to())) {
                throw new StoreException.NotSavable(issue.id(), "It moves to no open state");
            }
            given.put(issue.id(), issue);
            states.put(issue.id(), issue.to());
        }
        final List<Issue> issues = new ArrayList<>();
        for (final Issue issue : board.issues()) {
            final StageResult.ForIssue written = given.get(issue.id());
            issues.add(
                    written == null
                            ? issue
                            : new Issue(
                                    issue.id(),
                                    issue.repository(),
                                    issue.title(),
                                    issue.state(),
                                    written.size(),
                                    issue.blockedBy(),
                                    issue.parent(),
                                    issue.link(),
                                    ResultText.withoutHead(issue.text(), written),
                                    issue.worker(),
                                    issue.risk(),
                                    issue.estimate(),
                                    issue.minutes(),
                                    issue.priority(),
                                    issue.warnings(),
                                    issue.waitsOn()));
        }
        final Board sized =
                new Board(
                        board.revision(),
                        board.workflow(),
                        board.stories(),
                        issues,
                        board.work(),
                        board.repositories(),
                        board.faults());
        current = saved(sized, new Writes(states, Map.of(), List.of()));
        return current;
    }

    /**
     * Sets an issue's fields in memory: its size, estimate and priority on the board, and all but
     * its size kept beside it with its rank.
     */
    @Override
    public synchronized Board setFields(
            final Revision revision, final String issue, final Fields fields) {
        final Board board = fresh(revision);
        final Issue held = issueOf(board, issue);
        final Map<Fields.Field, Object> values =
                kept.computeIfAbsent(issue, id -> new java.util.EnumMap<>(Fields.Field.class));
        final Map<Fields.Field, @Nullable Object> asked =
                new java.util.EnumMap<>(Fields.Field.class);
        asked.put(Fields.Field.ESTIMATE, fields.estimateMinutes());
        asked.put(Fields.Field.PRIORITY, fields.priority());
        asked.put(Fields.Field.RANK, fields.rank());
        boolean changes =
                fields.named().contains(Fields.Field.SIZE)
                        && !java.util.Objects.equals(fields.size(), held.size());
        for (final Map.Entry<Fields.Field, @Nullable Object> entry : asked.entrySet()) {
            changes |=
                    fields.named().contains(entry.getKey())
                            && !java.util.Objects.equals(
                                    entry.getValue(), values.get(entry.getKey()));
        }
        if (!changes) {
            // Values equal to the issue's save nothing, as the description says.
            return board;
        }
        keep(values, fields, Fields.Field.ESTIMATE, fields.estimateMinutes());
        keep(values, fields, Fields.Field.PRIORITY, fields.priority());
        keep(values, fields, Fields.Field.RANK, fields.rank());
        final List<Issue> issues = new ArrayList<>();
        for (final Issue known : board.issues()) {
            issues.add(
                    known.id().equals(issue)
                                    && (fields.named().contains(Fields.Field.SIZE)
                                            || fields.named().contains(Fields.Field.ESTIMATE)
                                            || fields.named().contains(Fields.Field.PRIORITY))
                            ? new Issue(
                                    known.id(),
                                    known.repository(),
                                    known.title(),
                                    known.state(),
                                    fields.named().contains(Fields.Field.SIZE)
                                            ? fields.size()
                                            : known.size(),
                                    known.blockedBy(),
                                    known.parent(),
                                    known.link(),
                                    known.text(),
                                    known.worker(),
                                    known.risk(),
                                    fields.named().contains(Fields.Field.ESTIMATE)
                                            ? estimate(fields.estimateMinutes())
                                            : known.estimate(),
                                    known.minutes(),
                                    fields.named().contains(Fields.Field.PRIORITY)
                                            ? fields.priority()
                                            : known.priority(),
                                    fields.named().contains(Fields.Field.PRIORITY)
                                                    && fields.priority() != null
                                            ? prioritized(known.warnings())
                                            : known.warnings(),
                                    known.waitsOn())
                            : known);
        }
        current = saved(withItems(board, board.stories(), issues), NOTHING);
        return current;
    }

    /** Sets a story's issue list in memory; its state follows them again unless it is given. */
    @Override
    public synchronized Board setStoryIssues(
            final Revision revision, final String story, final List<String> issues) {
        final Board board = fresh(revision);
        if (board.story(story)
                .orElseThrow(() -> new StoreException.NoSuchStory(story))
                .issues()
                .equals(issues)) {
            return board;
        }
        final List<Story> stories = new ArrayList<>();
        for (final Story known : board.stories()) {
            stories.add(
                    known.id().equals(story)
                            ? new Story(
                                    known.id(),
                                    known.title(),
                                    known.state(),
                                    known.stateGiven(),
                                    known.priority(),
                                    known.size(),
                                    issues,
                                    known.description(),
                                    known.link(),
                                    known.issuesWhole(),
                                    known.warnings())
                            : known);
        }
        current = saved(withItems(board, stories, board.issues()), NOTHING);
        return current;
    }

    /** Sets an issue's blockers in memory. */
    @Override
    public synchronized Board setBlockedBy(
            final Revision revision, final String issue, final List<String> blockedBy) {
        final Board board = fresh(revision);
        if (issueOf(board, issue).blockedBy().equals(blockedBy)) {
            return board;
        }
        final List<Issue> issues = new ArrayList<>();
        for (final Issue known : board.issues()) {
            issues.add(
                    known.id().equals(issue)
                            ? new Issue(
                                    known.id(),
                                    known.repository(),
                                    known.title(),
                                    known.state(),
                                    known.size(),
                                    blockedBy,
                                    known.parent(),
                                    known.link(),
                                    known.text(),
                                    known.worker(),
                                    known.risk(),
                                    known.estimate(),
                                    known.minutes(),
                                    known.priority(),
                                    known.warnings(),
                                    known.waitsOn())
                            : known);
        }
        current = saved(withItems(board, board.stories(), issues), NOTHING);
        return current;
    }

    /**
     * Sets a story's or an issue's title and named parts in memory, through the GitHub store's
     * reading of a body, so every byte outside the parts named stays; an equal text keeps the
     * revision.
     */
    @Override
    public synchronized Board setText(
            final Revision revision, final String id, final TextEdit edit) {
        final Board board = fresh(revision);
        final boolean story = edit.kind() == TextEdit.Kind.STORY;
        final String title;
        final String text;
        if (story) {
            final Story known = storyOf(board, id);
            title = known.title();
            text = known.description();
        } else {
            final Issue known = issueOf(board, id);
            title = known.title();
            text = known.text();
        }
        final String newTitle = edit.title() == null ? title : edit.title();
        final String newText = written(id, text, edit);
        if (newTitle.equals(title) && newText.equals(text)) {
            return board;
        }
        final List<Story> stories = new ArrayList<>();
        for (final Story known : board.stories()) {
            stories.add(
                    story && known.id().equals(id)
                            ? new Story(
                                    known.id(),
                                    newTitle,
                                    known.state(),
                                    known.stateGiven(),
                                    known.priority(),
                                    known.size(),
                                    known.issues(),
                                    newText,
                                    known.link(),
                                    known.issuesWhole(),
                                    known.warnings())
                            : known);
        }
        final List<Issue> issues = new ArrayList<>();
        for (final Issue known : board.issues()) {
            issues.add(
                    !story && known.id().equals(id)
                            ? new Issue(
                                    known.id(),
                                    known.repository(),
                                    newTitle,
                                    known.state(),
                                    known.size(),
                                    known.blockedBy(),
                                    known.parent(),
                                    known.link(),
                                    newText,
                                    known.worker(),
                                    known.risk(),
                                    known.estimate(),
                                    known.minutes(),
                                    known.priority(),
                                    known.warnings(),
                                    known.waitsOn())
                            : known);
        }
        current = saved(withItems(board, stories, issues), NOTHING);
        return current;
    }

    /** The text with the edit's parts removed and written, or the refusal in fixed words. */
    private static String written(final String id, final String text, final TextEdit edit) {
        try {
            BodySections body = BodySections.read(text);
            for (final TextEdit.Part part : TextEdit.Part.values()) {
                if (edit.removed().contains(part)) {
                    body = body.without(BodySections.Part.of(part));
                }
            }
            // Removed first, as the GitHub store does, so a body that ends within the limit is
            // taken.
            for (final TextEdit.Part part : TextEdit.Part.values()) {
                final String given = edit.written().get(part);
                if (given != null) {
                    body = body.withEdited(BodySections.Part.of(part), given);
                }
            }
            return body.text();
        } catch (final IllegalArgumentException e) {
            throw new TextEdit.Refused(id, String.valueOf(e.getMessage()));
        }
    }

    /** Takes a comment and keeps nothing of it: the board shows no comment. */
    @Override
    public synchronized void comment(final String issue, final String text) {
        issueOf(current, issue);
    }

    /**
     * Answers what the mock keeps of an issue's fields the board does not show.
     *
     * @param issue the issue's id
     * @return its estimate, priority and rank as set, by field
     */
    public synchronized Map<Fields.Field, Object> kept(final String issue) {
        return Map.copyOf(kept.getOrDefault(issue, Map.of()));
    }

    private Board fresh(final Revision revision) {
        final Board board = current;
        if (!board.revision().equals(revision)) {
            throw new StoreException.StaleRevision(board.revision().value());
        }
        return board;
    }

    private static void keep(
            final Map<Fields.Field, Object> values,
            final Fields fields,
            final Fields.Field field,
            final @Nullable Object value) {
        if (!fields.named().contains(field)) {
            return;
        }
        if (value == null) {
            values.remove(field);
        } else {
            values.put(field, value);
        }
    }

    private static Board withItems(
            final Board board, final List<Story> stories, final List<Issue> issues) {
        return new Board(
                board.revision(),
                board.workflow(),
                stories,
                issues,
                board.work(),
                board.repositories(),
                board.faults());
    }

    /** Answers Markdown: as the Git store it stands in for. */
    @Override
    public TextFormat textFormat() {
        return TextFormat.MARKDOWN;
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
        // A state the workflow does not know gives no story its state.
        final List<String> known =
                issueStates.stream().filter(workflow.states()::contains).toList();
        if (known.isEmpty()) {
            return null;
        }
        return known.stream()
                .filter(state -> !closed.contains(state))
                .min(Comparator.comparingInt(workflow.states()::indexOf))
                .orElseGet(
                        () ->
                                known.stream().allMatch(state -> closed.indexOf(state) > 0)
                                        ? known.getFirst()
                                        : closed.getFirst());
    }

    /** The mock makes a story over issues of its board, as the GitHub store does. */
    @Override
    public boolean makesStoriesFromIssues() {
        return true;
    }

    /**
     * Makes a story over issues of the board in memory, numbered after the highest story, its body
     * carrying the request's marker; its state follows its issues' as every story's here does, and
     * nothing reaches a repository. The same request sent again takes the story carrying its marker
     * that holds named issues alone, hangs the ones it lacks, and answers the board unchanged when
     * it lacks none, so no story is made twice. A closed issue, a sub-issue or an issue another
     * story holds is refused, as the GitHub store refuses it.
     */
    @Override
    public synchronized NewIdea.Added createStoryFromIssues(
            final Revision revision, final StoryFromIssues story) {
        final Board board = fresh(revision);
        final String marker = story.marker();
        @Nullable Story taken = null;
        for (final Story known : board.stories()) {
            if (known.description().contains(marker)
                    && known.title().equals(story.title())
                    && story.existing().containsAll(known.issues())) {
                taken = known;
                break;
            }
        }
        for (int index = 0; index < story.existing().size(); index++) {
            final String id = story.existing().get(index);
            final Issue issue = issueOf(board, id);
            if (!board.workflow().states().contains(issue.state())
                    || board.workflow().closed().contains(issue.state())) {
                throw new StoreException.NotSavable(
                        id,
                        "the issue is "
                                + issue.state()
                                + "; a new story takes open issues of the workflow only");
            }
            if (issue.parent() != null) {
                throw new StoreException.NotSavable(
                        id, "the issue is a sub-issue of " + issue.parent());
            }
            for (final Story other : board.stories()) {
                if (other != taken && other.issues().contains(id)) {
                    throw new StoreException.InStory(id, other.id(), "existing[" + index + "]");
                }
            }
        }
        final List<Story> stories = new ArrayList<>(board.stories());
        final String id;
        if (taken != null) {
            id = taken.id();
            if (taken.issues().equals(story.existing())) {
                return new NewIdea.Added(id, story.existing(), current);
            }
            stories.set(
                    stories.indexOf(taken),
                    new Story(
                            id,
                            taken.title(),
                            taken.state(),
                            taken.stateGiven(),
                            taken.priority(),
                            taken.size(),
                            story.existing(),
                            taken.description(),
                            taken.link()));
        } else {
            int last = 0;
            for (final Story known : board.stories()) {
                last = Math.max(last, Integer.parseInt(known.id().substring(2)));
            }
            id = "ST" + (last + 1);
            stories.add(
                    new Story(
                            id,
                            story.title(),
                            board.workflow().states().getFirst(),
                            false,
                            story.priority(),
                            null,
                            story.existing(),
                            "**What a user gains.** "
                                    + story.gain()
                                    + "\n\n**The test.** "
                                    + story.test()
                                    + "\n\n"
                                    + marker
                                    + "\n",
                            null));
        }
        current = saved(withItems(board, stories, board.issues()), NOTHING);
        return new NewIdea.Added(id, story.existing(), current);
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
                            board.workflow().states().getFirst(),
                            issue.size(),
                            List.of(),
                            null,
                            null,
                            "**What must be true.** " + issue.mustBeTrue() + "\n",
                            issue.worker(),
                            Risk.ORDINARY,
                            null,
                            null,
                            issue.priority(),
                            List.of(),
                            List.of()));
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
                        board.workflow().states().getFirst(),
                        false,
                        idea.priority(),
                        null,
                        ids,
                        "**What a user gains.** "
                                + idea.gain()
                                + "\n\n**The test.** "
                                + idea.test()
                                + "\n",
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
        kept.clear();
        reasons.clear();
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
        return issueOf(board, issue).repository();
    }

    /**
     * The one issue of an id, refusing one the board holds twice, which no write can tell apart.
     */
    private static Issue issueOf(final Board board, final String id) {
        final java.util.Optional<String> twice = board.twice(id);
        if (twice.isPresent()) {
            throw new StoreException.NotSavable(id, twice.get());
        }
        return board.issue(id).orElseThrow(() -> new StoreException.NoSuchIssue(id));
    }

    /**
     * The one story of an id, refusing one the board holds twice, which no write can tell apart.
     */
    private static Story storyOf(final Board board, final String id) {
        final java.util.Optional<String> twice = board.twice(id);
        if (twice.isPresent()) {
            throw new StoreException.NotSavable(id, twice.get());
        }
        return board.story(id).orElseThrow(() -> new StoreException.NoSuchStory(id));
    }

    /**
     * The board with the writes in it, every story without a stated state following its issues;
     * called only with this store's monitor held, since it counts the saves.
     */
    private Board saved(final Board board, final Writes writes) {
        for (final String id : writes.stories().keySet()) {
            storyOf(board, id);
        }
        for (final String id : writes.issues().keySet()) {
            issueOf(board, id);
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
                            issue.risk(),
                            issue.estimate(),
                            issue.minutes(),
                            issue.priority(),
                            settled(issue.warnings(), writes.issues().containsKey(issue.id())),
                            issue.waitsOn()));
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
                story.link(),
                story.issuesWhole(),
                settled(story.warnings(), stated != null));
    }

    /** An issue's warnings after its priority is written: it has one now. */
    private static List<org.fuin.porelo.planner.store.Warning> prioritized(
            final List<org.fuin.porelo.planner.store.Warning> warnings) {
        return warnings.stream()
                .filter(
                        warning ->
                                !warning.kind()
                                        .equals(org.fuin.porelo.planner.store.Warning.NO_PRIORITY))
                .toList();
    }

    /**
     * An item's warnings after a save: one that wrote its state sets a state of the workflow, so a
     * warning about its state goes, as a store reading the item again would find it.
     */
    private static List<org.fuin.porelo.planner.store.Warning> settled(
            final List<org.fuin.porelo.planner.store.Warning> warnings,
            final boolean stateWritten) {
        return stateWritten
                ? warnings.stream().filter(warning -> !warning.aboutState()).toList()
                : warnings;
    }

    /** An estimate in minutes as the board holds it, or none. */
    private static @Nullable Double estimate(final @Nullable Integer minutes) {
        return minutes == null ? null : minutes.doubleValue();
    }
}
