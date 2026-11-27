package org.fuin.porelo.planner.app.mock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.TopicLabels;
import org.fuin.porelo.planner.store.Tracker;
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
                    .addMixIn(Issue.class, WithoutVersions.IssueMixIn.class)
                    .build();

    /**
     * The mock board as {@code mock/board.json} holds it.
     *
     * @param revision the board's fixed revision
     * @param repositories the project's repositories, its own first
     * @param workflow the states, phases and closed states
     * @param roles the roles of the project's tasks by name, in the shape of {@code
     *     stories/work.yml}
     * @param work the work rule per working state, in the shape of {@code stories/work.yml}
     * @param faults what the store could not read, in words
     * @param stories every story
     * @param issues every issue
     * @param labels the labels of the items that carry any, by id, in their order
     */
    record BoardFile(
            String revision,
            List<String> repositories,
            Workflow workflow,
            Map<String, RoleFile> roles,
            Map<String, RuleFile> work,
            List<String> faults,
            List<StoryFile> stories,
            List<Issue> issues,
            Map<String, List<String>> labels) {}

    /**
     * One story as {@code mock/board.json} holds it: its values, without a version, which the mock
     * takes over them as it does after every save.
     *
     * @param id its id
     * @param title its title
     * @param state its state
     * @param priority its priority
     * @param size its size, or null
     * @param issues the ids of the issues it needs, in its order
     * @param description its body
     * @param link where a person reads it, or null
     * @param issuesWhole whether {@code issues} holds every issue it names
     * @param closedAt when it was closed as completed, or null
     * @param warnings what the store could not read in it
     */
    record StoryFile(
            String id,
            String title,
            String state,
            String priority,
            @Nullable String size,
            List<String> issues,
            String description,
            java.net.@Nullable URI link,
            boolean issuesWhole,
            java.time.@Nullable Instant closedAt,
            List<org.fuin.porelo.planner.store.Warning> warnings) {

        /**
         * The story, its state its own stored value only when closed, as the mock gives every story
         * after a save, so a save leaves an untouched story's version as it was.
         */
        Story story(final Workflow workflow, final List<String> labels) {
            return new Story(
                    id,
                    title,
                    state,
                    workflow.closed().contains(state),
                    priority,
                    size,
                    issues,
                    description,
                    link,
                    issuesWhole,
                    closedAt,
                    warnings,
                    labels);
        }
    }

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
     * @param role the name of the role its task takes, one of the board's roles
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
            WorkRule.@Nullable Result result,
            String role) {}

    /**
     * One role as {@code stories/work.yml} writes it, under its name.
     *
     * @param end how its task ends, by the end kind's name
     * @param workspace its workspace, in Porelo's word
     * @param readLanded whether its task also reads the forwarded work of the tasks whose build it
     *     reads
     * @param projectServices whether its task has the project's services
     * @param writeAs the account written as at a pull request, in Porelo's word
     * @param rights its rights, in Porelo's words
     */
    record RoleFile(
            String end,
            String workspace,
            boolean readLanded,
            boolean projectServices,
            String writeAs,
            List<String> rights) {}

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

    private static final Writes NOTHING = new Writes(Map.of(), Map.of());

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
        final Map<String, Role> roles = new LinkedHashMap<>();
        file.roles().forEach((name, role) -> roles.put(name, role(name, role)));
        final Map<String, WorkRule> rules = new LinkedHashMap<>();
        file.work().forEach((state, rule) -> rules.put(state, rule(state, rule, roles)));
        // Corrections are offered as the Git store offers them: for an issue's state alone.
        final Work work = new Work(rules, file.faults(), Map.of(), roles);
        final org.fuin.porelo.planner.store.Correction move =
                org.fuin.porelo.planner.store.Correction.ofState(file.workflow(), work);
        return new Board(
                new Revision(file.revision()),
                file.workflow(),
                file.stories().stream()
                        .map(
                                story ->
                                        story.story(
                                                file.workflow(),
                                                file.labels().getOrDefault(story.id(), List.of())))
                        .toList(),
                file.issues().stream()
                        .map(
                                issue ->
                                        labelled(
                                                        issue,
                                                        file.labels()
                                                                .getOrDefault(
                                                                        issue.id(), List.of()))
                                                .withWarnings(offering(issue.warnings(), move)))
                        .toList(),
                work,
                // The mock board knows no project.yml, so each repository's folder is its name.
                org.fuin.porelo.planner.store.ProjectRepository.named(file.repositories()));
    }

    /** The issue carrying its labels, its version taken over them too. */
    private static Issue labelled(final Issue issue, final List<String> labels) {
        return new Issue(
                issue.id(),
                issue.repository(),
                issue.title(),
                issue.state(),
                issue.size(),
                issue.blockedBy(),
                issue.parent(),
                issue.link(),
                issue.text(),
                issue.worker(),
                issue.risk(),
                issue.estimate(),
                issue.minutes(),
                issue.priority(),
                issue.warnings(),
                issue.waitsOn(),
                labels);
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

    private static Role role(final String name, final RoleFile role) {
        final Role read =
                new Role(
                        name,
                        Role.End.valueOf(role.end()),
                        Role.Workspace.of(role.workspace())
                                .orElseThrow(() -> unsound(name, "workspace")),
                        role.readLanded(),
                        role.projectServices(),
                        Role.WriteAs.of(role.writeAs()).orElseThrow(() -> unsound(name, "writeAs")),
                        role.rights().stream()
                                .map(
                                        right ->
                                                Role.Right.of(right)
                                                        .orElseThrow(() -> unsound(name, "rights")))
                                .toList());
        if (!read.broken().isEmpty()) {
            throw new IllegalStateException(
                    "The mock board's role " + name + " breaks " + read.broken());
        }
        return read;
    }

    private static IllegalStateException unsound(final String role, final String member) {
        return new IllegalStateException(
                "The mock board's role " + role + " has a " + member + " Porelo does not know");
    }

    private static WorkRule rule(
            final String state, final RuleFile rule, final Map<String, Role> roles) {
        final Role role = roles.get(rule.role());
        if (role == null) {
            throw new IllegalStateException(
                    "The mock board's rule for "
                            + state
                            + " names the role '"
                            + rule.role()
                            + "', which its roles do not define");
        }
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
                rule.result(),
                role);
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

    /** The mock board stands for a project GitHub's tracker keeps. */
    @Override
    public Tracker tracker() {
        return Tracker.GITHUB;
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

    /** Sets the topic labels of an issue or a story and keeps every other label it carries. */
    @Override
    public boolean setsLabels() {
        return true;
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
     * from the result, or for a readiness check each issue's state and why one is not ready kept as
     * its reason; refused as a whole when an issue no longer stands in the state the stage
     * completes or would move to a closed state.
     */
    @Override
    public synchronized Board saveResult(final StageResult result) {
        final Board board = current;
        final Map<String, StageResult.ForIssue> given = new LinkedHashMap<>();
        final Map<String, String> states = new LinkedHashMap<>();
        // A readiness check moves its issues alone and keeps why one is not ready.
        final Map<String, Writes.Reason> notReady = new LinkedHashMap<>();
        for (final StageResult.Verdict verdict : result.verdicts()) {
            final Issue known = issueOf(board, verdict.id());
            if (!known.state().equals(verdict.from())) {
                throw new StoreException.NotSavable(
                        verdict.id(),
                        "It stands in " + known.state() + ", not in " + verdict.from());
            }
            if (!board.workflow().states().contains(verdict.to())
                    || board.workflow().closed().contains(verdict.to())) {
                throw new StoreException.NotSavable(verdict.id(), "It moves to no open state");
            }
            states.put(verdict.id(), verdict.to());
            final String reason = verdict.reason();
            if (reason != null) {
                notReady.put(verdict.id(), new Writes.Reason(verdict.to(), reason));
            }
        }
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
                                            issue.waitsOn())
                                    .withLabels(issue.labels()));
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
        current = saved(sized, new Writes(states, Map.of()));
        keep(notReady);
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
                                    .withLabels(known.labels())
                            : known);
        }
        current = saved(withItems(board, board.stories(), issues), NOTHING);
        return current;
    }

    /** Sets a story's issue list in memory; an open story's state follows them again. */
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
                                            known.priority(),
                                            known.size(),
                                            issues,
                                            known.description(),
                                            known.link(),
                                            known.issuesWhole(),
                                            known.warnings())
                                    .withLabels(known.labels())
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
                                    .withLabels(known.labels())
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
        final List<String> labels =
                story ? storyOf(board, id).labels() : issueOf(board, id).labels();
        final String newTitle = edit.title() == null ? title : edit.title();
        final String newText = written(id, text, edit);
        final List<String> asked = edit.labels();
        // The topic labels it carries asked again, in any order, are kept as they stand.
        final List<String> newLabels =
                asked == null || TopicLabels.topicOf(labels).equals(asked)
                        ? labels
                        : TopicLabels.after(labels, asked);
        if (newTitle.equals(title) && newText.equals(text) && newLabels.equals(labels)) {
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
                                            known.priority(),
                                            known.size(),
                                            known.issues(),
                                            newText,
                                            known.link(),
                                            known.issuesWhole(),
                                            known.warnings())
                                    .withLabels(newLabels)
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
                                    .withLabels(newLabels)
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
     * Derives a story's state by the one rule every store derives it by; the mock's workflow names
     * its completed state first among the closed ones, every later closed state rejecting.
     */
    @Override
    public @Nullable String storyState(final List<String> issueStates) {
        return rule(current.workflow()).map(rule -> rule.state(issueStates)).orElse(null);
    }

    /**
     * The one rule for the mock's workflow, or none when the workflow lacks the states it needs.
     */
    private static java.util.Optional<org.fuin.porelo.planner.core.StoryState> rule(
            final Workflow workflow) {
        final List<String> closed = workflow.closed();
        return org.fuin.porelo.planner.core.StoryState.of(
                workflow, closed.isEmpty() ? List.of() : closed.subList(1, closed.size()));
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
                                    taken.priority(),
                                    taken.size(),
                                    story.existing(),
                                    taken.description(),
                                    taken.link())
                            .withLabels(taken.labels()));
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
        final String own = board.repositories().getFirst().name();
        if (!writes.stories().isEmpty()) {
            written.add(own);
        }
        final List<String> order = new ArrayList<>();
        board.names().stream()
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
        return new Writes(issues, Map.of(), workers);
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
     * The board with the writes in it, every open story following its issues; called only with this
     * store's monitor held, since it counts the saves.
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
                            issue.waitsOn(),
                            issue.labels()));
        }
        final List<Story> stories = new ArrayList<>();
        for (final Story story : board.stories()) {
            stories.add(story(board, story, writes, states));
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

    /**
     * A story closed or reopened by the writes, or else as it stood: a closed one stays closed, as
     * a closed issue on GitHub does, and an open one follows its issues' states, never into a
     * close.
     */
    private Story story(
            final Board board,
            final Story story,
            final Writes writes,
            final Map<String, String> states) {
        final List<String> closed = board.workflow().closed();
        final String written = writes.stories().get(story.id());
        final List<String> issueStates = new ArrayList<>();
        final List<String> issueIds = new ArrayList<>();
        for (final String id : story.issues()) {
            final String issueState = states.get(id);
            if (issueState != null) {
                issueStates.add(issueState);
                issueIds.add(id);
            }
        }
        final String derived = storyState(issueStates);
        final String state;
        if (written != null && closed.contains(written)) {
            state = written;
        } else if (written == null && closed.contains(story.state())) {
            state = story.state();
        } else if (derived != null) {
            state = derived;
        } else {
            state = written != null ? written : story.state();
        }
        // The day its test passed is kept while it stands closed as it was.
        final java.time.Instant closedAt =
                state.equals(story.state()) && closed.contains(state) ? story.closedAt() : null;
        // A closed story's state is its own, so it counts toward its version; one its issues give
        // does not.
        return new Story(
                story.id(),
                story.title(),
                state,
                closed.contains(state),
                story.priority(),
                story.size(),
                story.issues(),
                story.description(),
                story.link(),
                story.issuesWhole(),
                closedAt,
                behind(
                        settled(story.warnings(), written != null),
                        board,
                        state,
                        derived,
                        issueIds,
                        issueStates),
                story.labels());
    }

    /**
     * A story's warnings with the one naming its issues behind its derived state, set anew from the
     * issues as they stand now; a story whose state is not derived carries none.
     */
    private static List<org.fuin.porelo.planner.store.Warning> behind(
            final List<org.fuin.porelo.planner.store.Warning> warnings,
            final Board board,
            final String state,
            final @Nullable String derived,
            final List<String> ids,
            final List<String> issueStates) {
        final List<org.fuin.porelo.planner.store.Warning> kept =
                new ArrayList<>(
                        warnings.stream()
                                .filter(
                                        warning ->
                                                !warning.kind()
                                                        .equals(
                                                                org.fuin.porelo.planner.store
                                                                        .Warning
                                                                        .ISSUE_BEHIND_STORY))
                                .toList());
        if (state.equals(derived)) {
            rule(board.workflow())
                    .flatMap(rule -> rule.behind(state, ids, issueStates))
                    .ifPresent(kept::add);
        }
        return List.copyOf(kept);
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
