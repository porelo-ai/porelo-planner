package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequestIssueFieldValuesInner;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TopicLabels;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/**
 * One write that adds new issues, and a story above them, to the GitHub project: every part is
 * checked before the first call; then the story, with its Priority and Rank, and each issue in turn
 * is made, added to the project, made a sub-issue of the story and given its field values before
 * the next one is made. GitHub has no transaction, so a write that stops after the first issue was
 * made is {@link StoreException.PartlySaved}, naming what GitHub holds. The same write tried again
 * finishes it: the person's open issue of the same title and body made within {@link
 * #TRIED_AGAIN_WITHIN} is taken as the one an earlier try made, never made twice: a story only
 * while an issue planned under it has none alike hanging under it with all its values; an issue
 * only where an earlier try leaves it: under the story it is planned under, off the project under
 * no story, or on the project under no story holding no planned value but an automatic first
 * Status. A taken item gets its place, its requested Status and the values it lacks, keeping its
 * other values; several alike stop the write, naming them. The calls go at the pace of a {@link
 * WritePace}.
 */
final class NewItems {

    /**
     * How long before a call the look for an issue GitHub made without answering starts: GitHub
     * keeps whole seconds, and a clock running apart from GitHub's would miss the issue otherwise.
     */
    static final Duration CLOCK_SLACK = Duration.ofSeconds(60);

    /** How long after an earlier try a write tried again takes the issues that try made. */
    static final Duration TRIED_AGAIN_WITHIN = Duration.ofHours(24);

    /** The most pages of a repository's issues read to find one GitHub made without answering. */
    static final int MOST_FOUND_PAGES = 10;

    /**
     * The Worker an issue made outside the project is given when it is added: no agent takes an
     * issue nobody chose for one.
     */
    static final Worker ADDED_WORKER = Worker.MANUAL;

    /** The Priority an issue made outside the project is given when it is added. */
    static final String ADDED_PRIORITY = "Medium";

    /** Anything that would break a title's or a line's one line. */
    private final ProjectWrites github;

    private final AccessRefusals refusals;

    private final InstantSource clock;

    private final WritePace.Sleeper sleeper;

    private final Snapshot current;

    private final LimitFactors limits;

    /** The pace of this write's calls, started when it writes with the person's token. */
    private @Nullable WritePace pace;

    private @Nullable IssueField priorityField;

    private final Set<String> priorities = new LinkedHashSet<>();

    /** The person's issues of the last {@link #TRIED_AGAIN_WITHIN}, by repository, read once. */
    private final Map<String, List<ProjectWrites.Found>> earlier = new HashMap<>();

    /** The last rank given, by kind and priority, so the items of one write follow each other. */
    private final Map<String, Integer> ranks = new HashMap<>();

    /** The references of the issues GitHub holds from this write, in the order made. */
    private final List<String> made = new ArrayList<>();

    /** The repositories an issue was made in, or may have been made in, in order. */
    private final Set<String> written = new LinkedHashSet<>();

    /** Whether a call that makes an issue went without a clear answer, so GitHub may hold it. */
    private boolean sent;

    /** The issues standing on the project a new story is made over, empty for any other write. */
    private List<Over> over = List.of();

    /**
     * Starts a write.
     *
     * @param github the calls the write is made of
     * @param refusals turns a failure into the store's refusal, scrubbed of the token
     * @param clock tells the time a call is sent at
     * @param sleeper waits between calls and before a call is made again
     * @param current the snapshot the store holds
     * @param limits the factors a call's tries and their waits follow
     */
    NewItems(
            final ProjectWrites github,
            final AccessRefusals refusals,
            final InstantSource clock,
            final WritePace.Sleeper sleeper,
            final Snapshot current,
            final LimitFactors limits) {
        this.github = github;
        this.refusals = refusals;
        this.clock = clock;
        this.sleeper = sleeper;
        this.current = current;
        this.limits = limits;
    }

    /**
     * A field value an item is given, made for its item id when it is written.
     *
     * @param field the field's name
     * @param make the value for an item id
     */
    private record Value(String field, Function<String, GitHubGraphQl.FieldValue> make) {}

    /** An issue to be made, with what it is given once GitHub made it. */
    static final class Planned {
        private final String repository;
        private final String title;
        private final String body;
        private final boolean story;
        private final List<Value> values;
        private final String priority;
        private List<String> labels = List.of();
        private boolean existing;
        private @Nullable String existingPriority;
        private ProjectWrites.@Nullable Created created;
        private @Nullable String itemId;

        private Planned(
                final String repository,
                final String title,
                final String body,
                final boolean story,
                final List<Value> values,
                final String priority) {
            this.repository = repository;
            this.title = title;
            this.body = body;
            this.story = story;
            this.values = List.copyOf(values);
            this.priority = priority;
        }

        /**
         * Answers its tracker reference, once GitHub made it.
         *
         * @return {@code <repository>#<number>}
         */
        String reference() {
            return repository + "#" + Objects.requireNonNull(created).number();
        }

        /**
         * Answers its database id, once GitHub made it.
         *
         * @return the id
         */
        long databaseId() {
            return Objects.requireNonNull(created).databaseId();
        }

        private ProjectWrites.Created made() {
            return Objects.requireNonNull(created);
        }
    }

    /**
     * Plans a new issue in its requested state, or the workflow's first: its body says what must be
     * true and which task proposed it, as the Git store's file does, and it takes the rank after
     * the last issue of its priority.
     *
     * @param issue what the person wrote
     * @param priority its priority, which its rank follows
     * @return the planned issue
     * @throws StoreException.NotSavable when the project cannot hold it
     */
    Planned issue(final NewIssue issue, final String priority) {
        final String repository = issue.repository();
        if (!current.config().names().contains(repository)) {
            throw new StoreException.NotSavable(
                    "", repository + " is no repository of the project");
        }
        final String body = NewItemTexts.issueBody(issue);
        final List<Value> values = new ArrayList<>();
        final String asked = issue.state();
        final String state = asked == null ? current.config().workflow().states().get(0) : asked;
        if (!current.config().workflow().states().contains(state)
                || current.config().workflow().closed().contains(state)) {
            throw new StoreException.NotSavable(
                    "", "a new issue starts in an open state of the workflow");
        }
        values.add(option(FieldNames.STATUS, state));
        final String size = issue.size();
        if (size != null) {
            values.add(option(FieldNames.SIZE, size));
        }
        values.add(worker(issue.worker()));
        IssuePriority.option("", priority);
        values.add(rank(false, priority));
        final Planned planned =
                planned(repository, issue.title().strip(), body, false, values, priority);
        planned.labels = issue.labels();
        return planned;
    }

    /**
     * Plans the add of an open issue GitHub holds that is no item of the project: an issue gets the
     * workflow's first state as its Status, {@link #ADDED_WORKER} as its Worker, {@link
     * #ADDED_PRIORITY} when it lacks a priority, and the rank after the last issue of its priority;
     * a story gets its priority and rank and no Status, so its state follows its issues'.
     *
     * @param repository the repository that holds it, as the board names it
     * @param number its number
     * @param nodeId its node id, which the project adds it by
     * @param databaseId its id in REST
     * @param story whether it is a story
     * @param heldPriority its issue-field option, or null when absent
     * @return the planned add
     * @throws StoreException.NotSavable when the project cannot hold it
     */
    Planned existing(
            final String repository,
            final int number,
            final String nodeId,
            final long databaseId,
            final boolean story,
            final @Nullable String heldPriority) {
        final List<Value> values = new ArrayList<>();
        if (!story) {
            values.add(option(FieldNames.STATUS, current.config().workflow().states().get(0)));
            values.add(worker(ADDED_WORKER));
        }
        // A Priority the planner does not know is kept as it is and ranked with Medium, as the
        // board sorts it.
        final String priority =
                Objects.requireNonNullElse(IssuePriority.read(heldPriority), ADDED_PRIORITY);
        values.add(rank(story, priority));
        final Planned planned = new Planned(repository, "", "", story, values, priority);
        planned.existing = true;
        planned.existingPriority = heldPriority;
        if (heldPriority == null) {
            // Only a priority written needs the organization's field; a held one is kept.
            priorities.add(priority);
        }
        planned.created =
                new ProjectWrites.Created(
                        nodeId, databaseId, number, story ? IssueMapping.STORY_TYPE : null);
        return planned;
    }

    /**
     * Adds an issue GitHub holds to the project and gives it its values. Once GitHub added it, a
     * later failure is {@link StoreException.PartlySaved}; an add GitHub refused saved nothing, and
     * one it took without answering may stand.
     *
     * @param token the person's token
     * @param planned the add, as {@link #existing} planned it
     */
    void list(final String token, final Planned planned) {
        final String projectId = begin(token);
        final var preserved = priorityValues(token, planned);
        addToProject(token, projectId, List.of(planned));
        writePriority(token, planned, preserved);
        setValues(token, projectId, planned, null);
    }

    private Value worker(final Worker worker) {
        return option(FieldNames.WORKER, worker == Worker.MANUAL ? "Manual" : "Agent");
    }

    /**
     * Plans an idea's story in the project's own repository, of the issue type Story, its gain and
     * its test in its body as a carried story holds them; it gets no Status, so its state follows
     * its issues'.
     *
     * @param idea the idea
     * @return the planned story
     * @throws StoreException.NotSavable when the project cannot hold it
     */
    Planned story(final NewIdea idea) {
        return story(idea, "");
    }

    /**
     * Plans a new story as {@link #story(NewIdea)} does, its body ending in a mark that tells it
     * from every story not made by the same request.
     *
     * @param idea the story, with no issues
     * @param marker the request's marker, one line, or empty for none
     * @return the planned story
     * @throws StoreException.NotSavable when the project cannot hold it
     */
    Planned story(final NewIdea idea, final String marker) {
        IssuePriority.option("", idea.priority());
        final String body = NewItemTexts.storyBody(idea, marker);
        final List<Value> values = new ArrayList<>();
        values.add(rank(true, idea.priority()));
        return planned(
                current.config().own(), idea.title().strip(), body, true, values, idea.priority());
    }

    /**
     * Places a new story, then each issue in turn under it, or issues alone: each is made or taken
     * from an earlier try, added to the project, made a sub-issue of the story and given its field
     * values, the Status first, before the next one is made.
     *
     * @param token the person's token
     * @param story the story, or null when only issues are added
     * @param issues the issues, in the story's order
     */
    void write(final String token, final @Nullable Planned story, final List<Planned> issues) {
        final String projectId = begin(token);
        @Nullable Parent parent = null;
        if (story != null) {
            place(token, projectId, story, null, issues);
            parent =
                    new Parent(
                            story.repository,
                            story.made().number(),
                            story.made().nodeId(),
                            story.reference());
        }
        for (final Planned issue : issues) {
            place(token, projectId, issue, parent, List.of());
        }
    }

    /**
     * Places one issue under a story the board holds: made or taken from an earlier try, added to
     * the project, made the story's last sub-issue and given its field values.
     *
     * @param token the person's token
     * @param story the story on the board
     * @param issue the issue
     */
    void writeUnder(final String token, final ItemView story, final Planned issue) {
        final String projectId = begin(token);
        place(
                token,
                projectId,
                issue,
                new Parent(
                        story.repository(),
                        story.number(),
                        story.issueId(),
                        story.repository() + "#" + story.number()),
                List.of());
    }

    /**
     * An issue standing on the project that a new story is made over.
     *
     * @param id the issue's id on the board
     * @param item its item
     */
    record Over(String id, ItemView item) {}

    /**
     * Places a new story over issues that stand on the project: the story is made, or taken from an
     * earlier try of the same request, then each issue it does not hold yet is hung under it in the
     * story's order, keeping its own fields. A story is taken only when its body carries the
     * request's marker, as the planned story's body does, it is the person's own, made within
     * {@link #TRIED_AGAIN_WITHIN}, and every sub-issue the board shows under it is named; a story a
     * person made, or one of another request, is never taken. Before the story is made, an issue
     * hanging under any other parent on GitHub is refused, naming that parent, and every issue, and
     * a story taken that the board shows, is read again and checked unchanged, so nothing is
     * written over a board the caller never saw. Two projects sharing a repository may still race
     * between that check and the links: GitHub then refuses the second parent, and the later call
     * answers PartlySaved with its story left empty, a window this write accepts as the other
     * writes of a plan accept theirs.
     *
     * @param token the person's token
     * @param story the planned story, its body ending in the request's marker
     * @param existing the issues, in the story's order
     * @param unchanged given the board's item of a story taken, or null, reads the issues and that
     *     item again, refuses the write when one changed since the board was read, and answers each
     *     issue's id in REST by its node id
     * @throws StoreException.InStory when an issue hangs under another parent on GitHub
     * @throws StoreException.StaleRevision when an issue or the story taken changed since the board
     *     was read
     */
    void writeOver(
            final String token,
            final Planned story,
            final List<Over> existing,
            final Function<@Nullable ItemView, Map<String, Long>> unchanged) {
        final String projectId = begin(token);
        over = List.copyOf(existing);
        final ProjectWrites.@Nullable Created taken = madeBefore(token, story, null, List.of());
        for (int index = 0; index < existing.size(); index++) {
            final Over issue = existing.get(index);
            final @Nullable String parent = issue.item().parentIssueId();
            if (parent != null && (taken == null || !parent.equals(taken.nodeId()))) {
                throw new StoreException.InStory(
                        issue.id(), parentNamed(parent), "existing[" + index + "]");
            }
        }
        final Map<String, Long> databaseIds =
                unchanged.apply(taken == null ? null : onBoard(story.repository, taken.number()));
        placeTaken(token, projectId, story, null, taken);
        final Parent parent =
                new Parent(
                        story.repository,
                        story.made().number(),
                        story.made().nodeId(),
                        story.reference());
        for (final Over issue : existing) {
            if (parent.nodeId().equals(issue.item().parentIssueId())) {
                // An earlier try hung it already; the story is read again as after a link.
                github.issueTaken(parent.nodeId());
                continue;
            }
            hang(
                    token,
                    parent,
                    issue.id(),
                    Objects.requireNonNull(
                            databaseIds.get(issue.item().issueId()),
                            "an issue hung was not read before the write"),
                    story.repository);
        }
    }

    /**
     * Names a parent on GitHub by its id on the board, or by its tracker reference when it is an
     * issue outside the project the board offers; empty when neither tells it.
     */
    private String parentNamed(final String nodeId) {
        for (final Map.Entry<String, ItemView> entry : current.items().entrySet()) {
            if (entry.getValue().issueId().equals(nodeId)) {
                return entry.getKey();
            }
        }
        for (final Snapshot.Unlisted listed : current.unlisted()) {
            if (listed.nodeId().equals(nodeId)) {
                return listed.repository() + "#" + listed.number();
            }
        }
        return "";
    }

    /** Checks the project's id and starts the pace of this write's calls. */
    private String begin(final String token) {
        final String projectId = current.config().projectId();
        if (projectId.isBlank()) {
            throw new StoreException.Unreadable(
                    current.config().own(), "the GitHub project's id was not read");
        }
        if (!priorities.isEmpty()) {
            final IssueField field;
            try {
                field =
                        IssuePriority.field(
                                "", github.issueFields(token, current.config().project().owner()));
            } catch (final StoreException e) {
                throw e;
            } catch (final RuntimeException e) {
                throw failed(e, current.config().own(), token);
            }
            priorityField = field;
            priorities.forEach(
                    priority ->
                            IssuePriority.accepts("", field, IssuePriority.option("", priority)));
        }
        pace = new WritePace(clock, sleeper, token, limits);
        return projectId;
    }

    /**
     * The story an issue of this write hangs under.
     *
     * @param repository the story's repository
     * @param number the story's number
     * @param nodeId the story's node id, which a sub-issue's parent names
     * @param reference the story's tracker reference
     */
    private record Parent(String repository, int number, String nodeId, String reference) {}

    /**
     * Makes an item or takes it from an earlier try, adds it to the project unless the board holds
     * it, hangs it under its story unless the board shows it there, and gives it the values it
     * lacks. The link comes before the values, so a try cut off before them leaves an issue under
     * its story that the next try takes.
     *
     * @param parent the story the item hangs under, or null for a story or an issue alone
     * @param under for a story, the issues planned under it; empty for an issue
     */
    private void place(
            final String token,
            final String projectId,
            final Planned planned,
            final @Nullable Parent parent,
            final List<Planned> under) {
        placeTaken(token, projectId, planned, parent, madeBefore(token, planned, parent, under));
    }

    /**
     * Places an item as {@link #place} does, taking the issue an earlier try made when one is given
     * and making it otherwise.
     */
    private void placeTaken(
            final String token,
            final String projectId,
            final Planned planned,
            final @Nullable Parent parent,
            final ProjectWrites.@Nullable Created taken) {
        planned.created = taken != null ? taken : create(token, planned);
        made.add(planned.reference());
        written.add(planned.repository);
        if (planned.story && !IssueMapping.STORY_TYPE.equals(planned.made().type())) {
            throw partly(
                    planned.repository,
                    "GitHub made "
                            + planned.reference()
                            + " without the issue type "
                            + IssueMapping.STORY_TYPE
                            + ", which only an account that may set issue types gives it, so it is"
                            + " no story");
        }
        if (taken == null
                && !IssuePriority.option("", planned.priority)
                        .equals(IssuePriority.held(planned.made().fieldValues()))) {
            throw partly(
                    planned.repository,
                    "GitHub made " + planned.reference() + " without the requested Priority");
        }
        final var preserved = taken == null ? null : priorityValues(token, planned);
        final @Nullable ItemView standing = onBoard(planned.repository, planned.made().number());
        if (standing != null) {
            planned.itemId = standing.itemId();
        } else {
            addToProject(token, projectId, List.of(planned));
        }
        if (parent != null
                && standing != null
                && parent.nodeId().equals(standing.parentIssueId())) {
            // Its story's item may not list it yet, so the story is read again as after a link.
            github.issueTaken(parent.nodeId());
        } else if (parent != null) {
            hang(
                    token,
                    parent,
                    planned.reference(),
                    planned.made().databaseId(),
                    planned.repository);
        }
        writePriority(token, planned, preserved);
        setValues(token, projectId, planned, standing);
        if (taken == null && !TopicLabels.topicOf(planned.made().labels()).equals(planned.labels)) {
            // Placed with every value, so only its labels are missing; GitHub drops them in
            // silence for an account that may not set them.
            throw new StoreException.LabelsNotKept(planned.repository, planned.reference());
        }
    }

    /** Makes an issue the last sub-issue of its story, at the pace of this write's calls. */
    private void hang(
            final String token,
            final Parent parent,
            final String reference,
            final long databaseId,
            final String repository) {
        try {
            // Made again after a lost answer: a link GitHub made is refused then, so the write
            // stops partly saved and the next try finds it linked.
            pace().again(
                            "the sub-issue " + reference + " of " + parent.reference(),
                            () -> {
                                github.addSubIssue(
                                        token,
                                        current.config().project().owner(),
                                        parent.repository(),
                                        parent.number(),
                                        databaseId);
                                return null;
                            });
        } catch (final RuntimeException e) {
            throw failed(e, repository, token);
        }
    }

    /** The board's item of an issue, or null when the project does not hold it. */
    private @Nullable ItemView onBoard(final String repository, final int number) {
        for (final ItemView item : current.items().values()) {
            if (item.repository().equals(repository) && item.number() == number) {
                return item;
            }
        }
        return null;
    }

    /**
     * The issue an earlier try of this write made: the person's one open issue of the same title
     * and body made within {@link #TRIED_AGAIN_WITHIN}, not yet taken by this write, and left by a
     * cut-off try; null when there is none.
     */
    private ProjectWrites.@Nullable Created madeBefore(
            final String token,
            final Planned planned,
            final @Nullable Parent parent,
            final List<Planned> under) {
        final List<ProjectWrites.Found> mine =
                earlier.computeIfAbsent(
                        planned.repository,
                        repository ->
                                issuesOf(
                                        token, planned, clock.instant().minus(TRIED_AGAIN_WITHIN)));
        final List<ProjectWrites.Found> same = new ArrayList<>();
        for (final ProjectWrites.Found issue : mine) {
            final int number = issue.issue().number();
            final @Nullable ItemView item = onBoard(planned.repository, number);
            final boolean left =
                    planned.story
                            ? item == null
                                            && !IssuePriority.option("", planned.priority)
                                                    .equals(
                                                            IssuePriority.held(
                                                                    issue.issue().fieldValues()))
                                    || cutOff(issue.issue().nodeId(), under)
                            : leftUnder(issue, item, parent, planned);
            if (!made.contains(planned.repository + "#" + number)
                    && left
                    && issue.title().equals(planned.title)
                    && plain(issue.body()).equals(plain(planned.body))) {
                same.add(issue);
            }
        }
        if (same.isEmpty()) {
            return null;
        }
        // Each alike is an earlier try's work a failure here must still have read again.
        same.forEach(f -> github.issueTaken(f.issue().nodeId()));
        if (same.size() > 1) {
            same.forEach(f -> made.add(planned.repository + "#" + f.issue().number()));
            written.add(planned.repository);
            throw partly(
                    planned.repository,
                    "the person holds "
                            + same.size()
                            + " open issues with the title and body of a new one in "
                            + planned.repository
                            + ", so none of them is taken as an earlier try's");
        }
        return same.get(0).issue();
    }

    /**
     * Whether a story alike was left by a cut-off try: an issue planned under it has none of its
     * title and body hanging under it with a value of every field planned for it, as a try cut off
     * before that issue's values leaves it. A complete story is the person's own, whatever its
     * Status, and a new one is made beside it.
     */
    private boolean cutOff(final String storyNodeId, final List<Planned> under) {
        if (!over.isEmpty()) {
            return holdsOnlyOver(storyNodeId);
        }
        for (final Planned issue : under) {
            boolean there = false;
            for (final ItemView item : current.items().values()) {
                if (storyNodeId.equals(item.parentIssueId())
                        && item.repository().equals(issue.repository)
                        && item.title().equals(issue.title)
                        && plain(item.body()).equals(plain(issue.body))
                        && issue.values.stream().allMatch(v -> holds(item, v.field()))
                        && holds(item, FieldNames.PRIORITY)) {
                    there = true;
                    break;
                }
            }
            if (!there) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a story alike was left by an earlier try of a story made over issues that stand:
     * every sub-issue the board shows under it is one of those issues. A try cut off before its
     * first link leaves none, one cut off later some of them, and one whose answer was lost all; a
     * story holding any other issue is the person's own, and a new one is made beside it.
     */
    private boolean holdsOnlyOver(final String storyNodeId) {
        final Set<String> issues = new java.util.HashSet<>();
        over.forEach(issue -> issues.add(issue.item().issueId()));
        for (final ItemView item : current.items().values()) {
            if (storyNodeId.equals(item.parentIssueId()) && !issues.contains(item.issueId())) {
                return false;
            }
            // The story's own item names a sub-issue the project does not hold too.
            if (storyNodeId.equals(item.issueId()) && !issues.containsAll(item.subIssueIds())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an issue alike was left by an earlier try of this write: it hangs under the story it
     * is planned under, it stands off the project under no story, as a try cut off before it added
     * the issue leaves it, or it stands on the project under none holding no planned value but an
     * automatic first Status, as a try cut off before its link or values leaves it. One on the
     * project holding any other planned value, or under another story, is the person's own.
     */
    private boolean leftUnder(
            final ProjectWrites.Found issue,
            final @Nullable ItemView item,
            final @Nullable Parent parent,
            final Planned planned) {
        if (item == null) {
            return !issue.underParent();
        }
        if (parent != null && parent.nodeId().equals(item.parentIssueId())) {
            return true;
        }
        // A try makes the issue with its priority, so one holding another is the person's own.
        final String priority = item.fields().getOrDefault(FieldNames.PRIORITY, "");
        if (!priority.isEmpty() && !priority.equals(IssuePriority.option("", planned.priority))) {
            return false;
        }
        // A try writes its values in one batch, so a cut-off one leaves none but an automatic
        // first Status; an item holding any other is the person's own.
        final String first = current.config().workflow().states().get(0);
        return item.parentIssueId() == null
                && planned.values.stream()
                        .noneMatch(
                                v ->
                                        holds(item, v.field())
                                                && !(v.field().equals(FieldNames.STATUS)
                                                        && first.equals(
                                                                item.fields()
                                                                        .get(FieldNames.STATUS))));
    }

    /** The person's open issues of a repository made since a moment, every page read. */
    private List<ProjectWrites.Found> issuesOf(
            final String token, final Planned planned, final Instant since) {
        final List<ProjectWrites.Found> found = new ArrayList<>();
        for (int page = 1; page <= MOST_FOUND_PAGES; page++) {
            final ProjectWrites.FoundPage read;
            try {
                read =
                        github.issuesCreatedSince(
                                token,
                                current.config().project().owner(),
                                planned.repository,
                                since,
                                page);
            } catch (final RuntimeException e) {
                throw failed(e, planned.repository, token);
            }
            found.addAll(read.issues());
            if (!read.more()) {
                return found;
            }
        }
        final String why =
                "the person made more issues in "
                        + planned.repository
                        + " within a day than are read to find an earlier try's among them";
        if (made.isEmpty() && !sent) {
            // Nothing of this write stands yet, so it is refused, not partly saved.
            throw new StoreException.Unreadable(planned.repository, why);
        }
        throw partly(planned.repository, why);
    }

    /** The pace of this write's calls, started by {@link #begin}. */
    private WritePace pace() {
        return Objects.requireNonNull(pace, "the write was not begun");
    }

    private void addToProject(final String token, final String projectId, final List<Planned> all) {
        for (int from = 0; from < all.size(); from += GitHubGraphQl.MAX_BATCH) {
            final List<Planned> chunk =
                    all.subList(from, Math.min(all.size(), from + GitHubGraphQl.MAX_BATCH));
            final Batch<String> batch;
            try {
                batch =
                        pace().again(
                                        "the project item of " + chunk.get(0).reference(),
                                        () ->
                                                github.addProjectItems(
                                                        token,
                                                        projectId,
                                                        chunk.stream()
                                                                .map(p -> p.made().nodeId())
                                                                .toList()));
            } catch (final RuntimeException e) {
                if (outcome(e) == Outcome.UNANSWERED) {
                    // An add GitHub took without answering may stand.
                    chunk.forEach(this::unsure);
                }
                throw failed(e, chunk.get(0).repository, token);
            }
            for (int at = 0; at < chunk.size(); at++) {
                final Batch.Outcome<String> outcome =
                        at < batch.outcomes().size() ? batch.outcomes().get(at) : null;
                final Planned planned = chunk.get(at);
                if (outcome == null || !outcome.saved() || outcome.value() == null) {
                    final String why =
                            "GitHub did not add "
                                    + planned.reference()
                                    + " to the project"
                                    + refusedWords(outcome, token);
                    if (made.isEmpty() && !sent) {
                        // Nothing of this write stands, so it is refused, not partly saved.
                        throw new StoreException.Unreadable(planned.repository, why);
                    }
                    throw partly(planned.repository, why);
                }
                planned.itemId = outcome.value();
                // From here on GitHub holds it on the project, so a later failure is a part saved.
                if (!made.contains(planned.reference())) {
                    made.add(planned.reference());
                }
                written.add(planned.repository);
            }
        }
    }

    /**
     * Whether a taken item still stands in the workflow's first state with other planned values
     * missing, as a project's automation leaves an item a cut-off try added; a Status a person
     * chose on a whole item is never written over.
     */
    private boolean automaticFirst(final ItemView standing, final Planned planned) {
        final String first = current.config().workflow().states().get(0);
        return first.equals(standing.fields().get(FieldNames.STATUS))
                && planned.values.stream()
                        .anyMatch(
                                v ->
                                        !v.field().equals(FieldNames.STATUS)
                                                && !holds(standing, v.field()));
    }

    /**
     * Writes the values an item lacks, and the requested Status over a project's automatic first
     * one; a taken item keeps its other values so a retry does not move its rank or discard a
     * person's fields.
     */
    private void setValues(
            final String token,
            final String projectId,
            final Planned planned,
            final @Nullable ItemView standing) {
        final List<GitHubGraphQl.FieldValue> values = new ArrayList<>();
        final String itemId = Objects.requireNonNull(planned.itemId);
        for (final Value value : planned.values) {
            if (standing == null
                    || !holds(standing, value.field())
                    || value.field().equals(FieldNames.STATUS)
                            && automaticFirst(standing, planned)) {
                values.add(value.make().apply(itemId));
            }
        }
        for (int from = 0; from < values.size(); from += GitHubGraphQl.MAX_BATCH) {
            final int to = Math.min(values.size(), from + GitHubGraphQl.MAX_BATCH);
            final Batch<String> batch;
            try {
                final List<GitHubGraphQl.FieldValue> part = values.subList(from, to);
                batch =
                        pace().again(
                                        "the field values of " + planned.reference(),
                                        () -> github.setFieldValues(token, projectId, part));
            } catch (final RuntimeException e) {
                throw failed(e, planned.repository, token);
            }
            for (int at = from; at < to; at++) {
                final int part = at - from;
                final Batch.Outcome<String> outcome =
                        part < batch.outcomes().size() ? batch.outcomes().get(part) : null;
                if (outcome == null || !outcome.saved()) {
                    throw partly(
                            planned.repository,
                            "GitHub refused a field value of "
                                    + planned.reference()
                                    + refusedWords(outcome, token));
                }
            }
        }
    }

    /** Whether an item on the board holds a value of a field, for Status one of the workflow. */
    private boolean holds(final ItemView item, final String field) {
        final String value = item.fields().get(field);
        if (value == null || value.isBlank()) {
            return false;
        }
        return !field.equals(FieldNames.STATUS)
                || current.config().workflow().states().contains(value);
    }

    /**
     * Makes an issue once. A call GitHub may have taken without an answer is never sent again
     * blindly: the person's issues made since are looked through for one of the same title and
     * body; one found is taken as made, none found makes it once more, several stop the write.
     */
    private ProjectWrites.Created create(final String token, final Planned planned) {
        final Instant since = clock.instant().minus(CLOCK_SLACK);
        try {
            return send(token, planned);
        } catch (final RuntimeException e) {
            switch (outcome(e)) {
                case REFUSED -> throw failed(e, planned.repository, token);
                case UNKNOWN -> {
                    unsure(planned);
                    throw unknownMade(planned, "GitHub's answer could not be read");
                }
                case UNANSWERED -> unsure(planned);
            }
        }
        final List<ProjectWrites.Found> found = madeSince(token, planned, since);
        // Each alike may be the unanswered call's issue, which a failure here must still read
        // again.
        found.forEach(f -> github.issueTaken(f.issue().nodeId()));
        if (found.size() == 1) {
            return found.get(0).issue();
        }
        if (found.size() > 1) {
            found.forEach(f -> made.add(planned.repository + "#" + f.issue().number()));
            written.add(planned.repository);
            throw partly(
                    planned.repository,
                    "GitHub took a call without answering and holds "
                            + found.size()
                            + " issues of the person with its title and body since, so none of"
                            + " them is taken as the new one");
        }
        try {
            return send(token, planned);
        } catch (final RuntimeException e) {
            if (outcome(e) != Outcome.REFUSED) {
                throw unknownMade(planned, "the call made once more got no clear answer either");
            }
            throw failed(e, planned.repository, token);
        }
    }

    private ProjectWrites.Created send(final String token, final Planned planned) {
        // Made once at the pace: a call that makes an issue is never sent again blindly.
        return pace().once(
                        "the new issue in " + planned.repository,
                        () ->
                                github.createIssue(
                                        token,
                                        current.config().project().owner(),
                                        planned.repository,
                                        planned.title,
                                        planned.body,
                                        planned.story ? IssueMapping.STORY_TYPE : null,
                                        IssuePriority.create(
                                                Objects.requireNonNull(priorityField),
                                                planned.priority),
                                        planned.labels));
    }

    private @Nullable List<IssuesUpdateRequestIssueFieldValuesInner> priorityValues(
            final String token, final Planned planned) {
        // An unlisted issue keeps a priority the person already chose.
        if (planned.existing && planned.existingPriority != null) {
            return null;
        }
        final List<org.fuin.porelo.forge.github.model.IssueFieldValue> held;
        try {
            held =
                    github.issueFieldValues(
                            token,
                            current.config().project().owner(),
                            planned.repository,
                            planned.made().number());
        } catch (final RuntimeException e) {
            throw failed(e, planned.repository, token);
        }
        if (IssuePriority.held(held) != null) {
            // A Priority set on GitHub since the board was read is the person's, never
            // overwritten.
            if (planned.existing) {
                throw new StoreException.StaleRevision(
                        current.board().revision().value(), planned.reference());
            }
            // An issue an earlier try made keeps the priority it holds, as it keeps its other
            // values.
            return null;
        }
        return IssuePriority.update(
                planned.reference(), Objects.requireNonNull(priorityField), held, planned.priority);
    }

    private void writePriority(
            final String token,
            final Planned planned,
            final @Nullable List<IssuesUpdateRequestIssueFieldValuesInner> values) {
        if (values == null) {
            return;
        }
        try {
            pace().again(
                            "the priority of " + planned.reference(),
                            () -> {
                                github.updateIssue(
                                        token,
                                        current.config().project().owner(),
                                        planned.repository,
                                        planned.made().number(),
                                        null,
                                        null,
                                        null,
                                        values);
                                return null;
                            });
        } catch (final RuntimeException e) {
            throw failed(e, planned.repository, token);
        }
    }

    /** The person's issues made since a moment with the planned title and body. */
    private List<ProjectWrites.Found> madeSince(
            final String token, final Planned planned, final Instant since) {
        final List<ProjectWrites.Found> found = new ArrayList<>();
        for (int page = 1; page <= MOST_FOUND_PAGES; page++) {
            final ProjectWrites.FoundPage read;
            try {
                read =
                        github.issuesCreatedSince(
                                token,
                                current.config().project().owner(),
                                planned.repository,
                                since,
                                page);
            } catch (final RuntimeException e) {
                throw unknownMade(
                        planned,
                        "the look for it failed: "
                                + refusals.refusal(planned.repository, e, token).getMessage());
            }
            for (final ProjectWrites.Found issue : read.issues()) {
                if (!known(planned.repository + "#" + issue.issue().number())
                        && issue.title().equals(planned.title)
                        && plain(issue.body()).equals(plain(planned.body))) {
                    found.add(issue);
                }
            }
            if (!read.more()) {
                return found;
            }
        }
        throw unknownMade(
                planned, "the person made more issues since than are read to find it among them");
    }

    /** A body as GitHub may hand it back: its line endings and outer blank space aside. */
    private static String plain(final String body) {
        return body.replace("\r\n", "\n").strip();
    }

    /** What a failed call that makes an issue tells of the issue. */
    private enum Outcome {
        /** Never sent, or refused by GitHub's answer: no issue was made. */
        REFUSED,
        /** Sent, and no answer came: GitHub may have made it, and the person's issues tell. */
        UNANSWERED,
        /** Answered by a failure on GitHub's side or in words that could not be read. */
        UNKNOWN
    }

    private static Outcome outcome(final RuntimeException e) {
        if (e instanceof GitHubException failure) {
            if (failure.delivery() == Delivery.SENT_UNANSWERED) {
                return Outcome.UNANSWERED;
            }
            final boolean refused =
                    failure.delivery() == Delivery.NOT_SENT
                            || failure.status() >= 400 && failure.status() < 500;
            return refused ? Outcome.REFUSED : Outcome.UNKNOWN;
        }
        return e instanceof StoreException ? Outcome.REFUSED : Outcome.UNKNOWN;
    }

    /** Notes that GitHub may hold an issue of this write in a repository. */
    private void unsure(final Planned planned) {
        sent = true;
        written.add(planned.repository);
    }

    /** Whether an issue stood on the board before this write or was made earlier in it. */
    private boolean known(final String reference) {
        if (made.contains(reference)) {
            return true;
        }
        for (final ItemView item : current.items().values()) {
            if (reference.equals(item.repository() + "#" + item.number())) {
                return true;
            }
        }
        return false;
    }

    private StoreException.PartlySaved unknownMade(final Planned planned, final String why) {
        return partly(
                planned.repository,
                "GitHub gave no clear answer to a call, so whether it made an issue in "
                        + planned.repository
                        + " is unknown; "
                        + why);
    }

    /**
     * The refusal of a call that failed: as it is while nothing was made yet, else partly saved.
     */
    private StoreException failed(
            final RuntimeException e, final String repository, final String token) {
        final StoreException refusal = refusals.refusal(repository, e, token);
        if (made.isEmpty() && !sent) {
            return refusal;
        }
        return partly(repository, "A call failed after a part was made: " + refusal.getMessage());
    }

    private StoreException.PartlySaved partly(final String repository, final String reason) {
        return new StoreException.PartlySaved(
                repository,
                List.copyOf(written),
                reason
                        + (made.isEmpty()
                                ? ""
                                : "; GitHub holds " + String.join(", ", made) + " from this save"));
    }

    private static String refusedWords(
            final Batch.@Nullable Outcome<String> outcome, final String token) {
        final String error = outcome == null ? null : outcome.error();
        return error == null ? "" : ": " + Scrubbed.text(error, List.of(token));
    }

    private Planned planned(
            final String repository,
            final String title,
            final String body,
            final boolean story,
            final List<Value> values,
            final String priority) {
        if (body.length() > BodySections.LONGEST) {
            throw new StoreException.NotSavable(
                    "", "the body of a new issue is longer than GitHub takes");
        }
        priorities.add(priority);
        return new Planned(repository, title, body, story, values, priority);
    }

    private Value option(final String field, final String name) {
        final String fieldId = fieldId(field);
        final String optionId =
                current.config().optionIds().getOrDefault(field, Map.of()).get(name);
        if (optionId == null) {
            throw new StoreException.NotSavable(
                    "", "the project's field " + field + " has no option " + name);
        }
        return new Value(
                field, itemId -> GitHubGraphQl.FieldValue.option(itemId, fieldId, optionId));
    }

    /**
     * The rank after the last item of a kind and priority, given when it is written: an item that
     * keeps the rank it holds takes none, so no rank moves and the next follows the last.
     */
    private Value rank(final boolean story, final String priority) {
        final String fieldId = fieldId(FieldNames.RANK);
        return new Value(
                FieldNames.RANK,
                itemId ->
                        GitHubGraphQl.FieldValue.number(
                                itemId, fieldId, nextRank(story, priority)));
    }

    private String fieldId(final String field) {
        final String fieldId = current.config().fieldIds().get(field);
        if (fieldId == null) {
            throw new StoreException.NotSavable("", "the project has no field " + field);
        }
        return fieldId;
    }

    /** The rank after the last item of a kind and priority, and after those this write gave. */
    private int nextRank(final boolean story, final String priority) {
        final String key = story + "\n" + priority;
        final int next =
                Math.min(
                        ranks.computeIfAbsent(
                                        key,
                                        k ->
                                                NewItemTexts.highestRank(
                                                        current.items().values(), story, priority))
                                + 1,
                        IssueMapping.MOST_RANK);
        ranks.put(key, next);
        return next;
    }
}
