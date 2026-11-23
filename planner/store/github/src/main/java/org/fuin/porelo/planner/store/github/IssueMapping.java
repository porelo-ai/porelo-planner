package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Correction;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TopicLabels;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * Maps a GitHub project's items to the planner's stories and issues and back. An item a person
 * edited by hand into something the board cannot hold is shown, warned where it cannot be held;
 * only an item that cannot be named, or one of a repository the project does not plan, is left out
 * as a fault. Never a crash.
 */
public final class IssueMapping {

    /**
     * The name of the issue type that makes an issue of the project's own repository a story,
     * matched exactly; a label makes none.
     */
    public static final String STORY_TYPE = "Story";

    /** The sizes a {@code Size} field may hold. */
    public static final List<String> SIZES = List.of("XS", "S", "M", "L", "XL");

    /**
     * What an open issue's state reads as when its {@code Status} names a closed state: a state the
     * workflow cannot name, so nothing counts the open issue as closed and a move sets it right.
     */
    static final String OPEN_UNDER = " (open)";

    /** The reasons GitHub closes an issue for that read as a rejection, lowercased. */
    private static final Set<String> REJECTING = Set.of("not_planned", "duplicate");

    /** The highest rank, as an issue's head allows it. */
    public static final int MOST_RANK = 99_999;

    /**
     * The stories and issues an item set maps to, and what was left out.
     *
     * @param stories the stories, by priority and then rank
     * @param issues the issues, by priority and then rank
     * @param faults one fixed sentence per item left out or shown other than GitHub holds it
     * @param outside the node ids of the sub-issues a story was named to have outside the project,
     *     each once
     */
    public record Mapped(
            List<Story> stories, List<Issue> issues, List<String> faults, List<String> outside) {

        /** Copies the lists. */
        public Mapped {
            stories = List.copyOf(stories);
            issues = List.copyOf(issues);
            faults = List.copyOf(faults);
            outside = List.copyOf(outside);
        }
    }

    /** The heading of a story's section that lists the issues of other stories it needs too. */
    public static final String ALSO_NEEDS = "Also needs";

    private static final Pattern REFERENCE =
            Pattern.compile("^[-*]?\\s*([A-Za-z0-9._-]{1,100})#([0-9]{1,9})\\s*$");

    private IssueMapping() {}

    /**
     * Maps the items, each known by its tracker reference. Archived items are not on the board.
     *
     * @param items every item of the project
     * @param config the project's repositories and workflow
     * @param states derives an open story's state from its issues'
     * @return the stories, the issues and the faults
     */
    public static Mapped read(
            final Collection<ItemView> items,
            final ProjectConfig config,
            final StoryStates states) {
        return read(items, Set.of(), config, states);
    }

    /**
     * Maps the items, each known by its tracker reference. Archived items are not on the board; an
     * item read past one page is shown warned, and a link to an item the board does not place is
     * cut and warned, or, to an archived issue GitHub has closed, a wait that is done.
     *
     * @param items every item of the project
     * @param pastPage the item ids of items read past one page of GitHub's answer, each shown
     *     warned
     * @param config the project's repositories and workflow
     * @param states derives an open story's state from its issues'
     * @return the stories, the issues and the faults
     */
    public static Mapped read(
            final Collection<ItemView> items,
            final Set<String> pastPage,
            final ProjectConfig config,
            final StoryStates states) {
        return read(items, pastPage, config, states, Map.of());
    }

    /**
     * Maps the items as {@link #read(Collection, Set, ProjectConfig, StoryStates)} does; an issue
     * blocked by an issue that is no item of the project waits on it, with its state as read.
     *
     * @param items every item of the project
     * @param pastPage the item ids of items read past one page of GitHub's answer, each shown
     *     warned
     * @param config the project's repositories and workflow
     * @param states derives an open story's state from its issues'
     * @param waits the blockers that are no items of the project, by their node ids, as last read
     * @return the stories, the issues and the faults
     */
    public static Mapped read(
            final Collection<ItemView> items,
            final Set<String> pastPage,
            final ProjectConfig config,
            final StoryStates states,
            final Map<String, Snapshot.Waited> waits) {
        return read(
                items,
                pastPage,
                new Mapping(
                        config.workflow(),
                        config.names(),
                        config.forge(),
                        config.project().owner(),
                        config.work(),
                        config.priorities()),
                states,
                waits);
    }

    /**
     * Maps local items with the same fields and link warnings, without a forge or external waits.
     *
     * @param items the local items
     * @param workflow the workflow
     * @param repositories the project's repositories, its own first
     * @param states derives a story's state
     * @return the mapped items and faults
     */
    public static Mapped local(
            final Collection<ItemView> items,
            final Workflow workflow,
            final List<String> repositories,
            final StoryStates states) {
        return read(
                items,
                Set.of(),
                new Mapping(workflow, repositories, null, null, null, List.of()),
                states,
                Map.of());
    }

    /**
     * What the items are mapped by; without work rules, as for local items, no correction is
     * offered, and without priorities the organization's field holds, no priority's.
     */
    private record Mapping(
            Workflow workflow,
            List<String> repositories,
            @Nullable URI forge,
            @Nullable String owner,
            @Nullable Work work,
            List<String> priorities) {
        String own() {
            return repositories.getFirst();
        }
    }

    /**
     * The item's warnings with the correction given on each of the kinds named, the others as they
     * are.
     */
    private static List<Warning> offering(
            final List<Warning> warnings,
            final @Nullable Correction correction,
            final Set<String> kinds) {
        if (correction == null) {
            return warnings;
        }
        return warnings.stream()
                .map(
                        warning ->
                                kinds.contains(warning.kind())
                                        ? warning.offering(correction)
                                        : warning)
                .toList();
    }

    private static Mapped read(
            final Collection<ItemView> items,
            final Set<String> pastPage,
            final Mapping config,
            final StoryStates states,
            final Map<String, Snapshot.Waited> waits) {
        final Workflow workflow = config.workflow();
        final List<String> faults = new ArrayList<>();
        // A workflow the rule cannot read is the board's fault; its open stories get no state.
        states.fault().ifPresent(faults::add);
        // Only an item that cannot be named, or one of a repository the project does not plan,
        // stays off the board as a fault; every other is shown, warned where it cannot be held.
        final List<ItemView> candidates = new ArrayList<>();
        final Map<String, Integer> named = new HashMap<>();
        for (final ItemView item : items) {
            final String id = idOf(item);
            if (id == null) {
                faults.add(where(item) + " has no valid tracker reference and is left out");
                continue;
            }
            if (!config.repositories().contains(item.repository())) {
                if (!item.archived()) {
                    faults.add(
                            where(item)
                                    + " lies outside the project's repositories and is left out");
                }
                continue;
            }
            candidates.add(item);
            if (!item.archived()) {
                named.merge(id, 1, Integer::sum);
            }
        }
        final Context context = new Context(config, pastPage, waits);
        final List<ItemView> live = new ArrayList<>();
        for (final ItemView item : candidates) {
            final String id = Objects.requireNonNull(idOf(item));
            context.byIssueId.put(item.issueId(), item);
            context.idOf.put(item.issueId(), id);
            if (!item.archived()) {
                live.add(item);
                if (named.getOrDefault(id, 0) > 1) {
                    // Every copy is warned, so the board takes the id on each.
                    context.twice.add(item.issueId());
                }
            }
        }

        final Map<String, ItemView> issueItems = new LinkedHashMap<>();
        final Map<String, ItemView> storyItems = new LinkedHashMap<>();
        final List<ItemView> twiceIssueItems = new ArrayList<>();
        final List<ItemView> twiceStoryItems = new ArrayList<>();
        for (final ItemView item : live) {
            final String id = Objects.requireNonNull(context.idOf.get(item.issueId()));
            final boolean twice = context.twice.contains(item.issueId());
            if (context.storyOf(item)) {
                if (twice) {
                    twiceStoryItems.add(item);
                } else {
                    storyItems.put(id, item);
                }
            } else if (twice) {
                twiceIssueItems.add(item);
            } else {
                issueItems.put(id, item);
            }
        }

        final Map<String, Issue> issues = new LinkedHashMap<>();
        for (final Map.Entry<String, ItemView> entry : issueItems.entrySet()) {
            issues.put(entry.getKey(), issue(entry.getKey(), entry.getValue(), context, faults));
        }
        final List<Map.Entry<Issue, ItemView>> twiceIssues = new ArrayList<>();
        for (final ItemView item : twiceIssueItems) {
            final String id = Objects.requireNonNull(context.idOf.get(item.issueId()));
            final Issue issue = issue(id, item, context, faults);
            // Its id names another item too: no issue can stand under it, nor it under one.
            twiceIssues.add(
                    Map.entry(
                            relinked(issue, null, issue.blockedBy(), List.of(Warning.idTwice())),
                            item));
        }
        cutCycles(issues);
        cutSubSubIssues(issues);
        // A link to one of the project's items the board does not show, such as an archived one.
        final Set<String> shown = Set.copyOf(issues.keySet());
        cutDangling(issues, id -> !shown.contains(id));
        workedAsParents(issues);
        for (final String id : List.copyOf(issues.keySet())) {
            final Issue issue = Objects.requireNonNull(issues.get(id));
            if (workflow.states().contains(issue.state())) {
                continue;
            }
            // Shown in its own state, warned, under its story; a move into the workflow repairs it.
            issues.put(id, outsideWorkflow(issue, workflow));
        }
        // A copy of an id named twice takes the same steps: it stands under no issue and none
        // under it, so no circle or deeper chain runs through it, but its state and its links are
        // warned and cut as any issue's are.
        final Set<String> placed = Set.copyOf(issues.keySet());
        twiceIssues.replaceAll(
                entry ->
                        Map.entry(
                                outsideWorkflow(
                                        cutDangling(entry.getKey(), id -> !placed.contains(id)),
                                        workflow),
                                entry.getValue()));
        for (final String id : issues.keySet()) {
            final ItemView item = Objects.requireNonNull(issueItems.get(id));
            unclearBody(item).ifPresent(repair -> faults.add(where(item) + repair));
        }

        final Set<String> twiceIds = new HashSet<>();
        twiceIssues.forEach(entry -> twiceIds.add(entry.getKey().id()));
        final List<Map.Entry<Story, ItemView>> stories = new ArrayList<>();
        final Set<String> outside = new LinkedHashSet<>();
        for (final Map.Entry<String, ItemView> entry : storyItems.entrySet()) {
            stories.add(
                    Map.entry(
                            story(
                                    entry.getKey(),
                                    entry.getValue(),
                                    issues,
                                    twiceIds,
                                    pastPage,
                                    context.idOf,
                                    config.repositories(),
                                    states,
                                    workflow,
                                    faults,
                                    outside),
                            entry.getValue()));
        }
        for (final ItemView item : twiceStoryItems) {
            final String id = Objects.requireNonNull(context.idOf.get(item.issueId()));
            final Story story =
                    story(
                            id,
                            item,
                            issues,
                            twiceIds,
                            pastPage,
                            context.idOf,
                            config.repositories(),
                            states,
                            workflow,
                            faults,
                            outside);
            stories.add(
                    Map.entry(
                            story.withWarnings(withAdded(story.warnings(), Warning.idTwice())),
                            item));
        }

        // A state is set right by a move the person picks, a story's priority by one the
        // organization's field holds; a story's state, an issue's priority and every other warning
        // only by hand.
        final Work work = config.work();
        final Correction move = work == null ? null : Correction.ofState(workflow, work);
        final Correction priority =
                work == null ? null : Correction.ofPriority(work, config.priorities());
        final Comparator<ItemView> order = byPriorityThenRank();
        final List<Story> sortedStories =
                stories.stream()
                        .sorted(Comparator.comparing(Map.Entry::getValue, order))
                        .map(Map.Entry::getKey)
                        .map(
                                story ->
                                        story.withWarnings(
                                                offering(
                                                        story.warnings(),
                                                        priority,
                                                        Set.of(
                                                                Warning.NO_PRIORITY,
                                                                Warning.UNKNOWN_PRIORITY))))
                        .toList();
        final List<Map.Entry<Issue, ItemView>> all = new ArrayList<>();
        issues.values()
                .forEach(
                        issue ->
                                all.add(
                                        Map.entry(
                                                issue,
                                                Objects.requireNonNull(
                                                        issueItems.get(issue.id())))));
        all.addAll(twiceIssues);
        final List<Issue> sortedIssues =
                all.stream()
                        .sorted(Comparator.comparing(Map.Entry::getValue, order))
                        .map(Map.Entry::getKey)
                        .map(
                                issue ->
                                        issue.withWarnings(
                                                offering(
                                                        issue.warnings(),
                                                        move,
                                                        Set.of(Warning.STATE_OUTSIDE_WORKFLOW))))
                        .toList();
        return new Mapped(sortedStories, sortedIssues, faults, List.copyOf(outside));
    }

    /**
     * What an item's mapping reads beside it: the project's items by their issues' node ids and
     * their ids, the node ids of the items whose id names another item too, and those read past one
     * page.
     */
    private static final class Context {
        private final Mapping config;
        private final Set<String> pastPage;
        private final Map<String, Snapshot.Waited> waits;
        private final Map<String, ItemView> byIssueId = new HashMap<>();
        private final Map<String, String> idOf = new HashMap<>();
        private final Set<String> twice = new HashSet<>();

        private Context(
                final Mapping config,
                final Set<String> pastPage,
                final Map<String, Snapshot.Waited> waits) {
            this.config = config;
            this.pastPage = pastPage;
            this.waits = waits;
        }

        /** Whether an item is a story: typed Story, in the repository that holds the stories. */
        private boolean storyOf(final @Nullable ItemView item) {
            return item != null && isStory(item) && item.repository().equals(config.own());
        }
    }

    /** The issue with another parent and other blockers, and the warnings added it lacks. */
    private static Issue relinked(
            final Issue issue,
            final @Nullable String parent,
            final List<String> blockedBy,
            final List<Warning> added) {
        List<Warning> warnings = issue.warnings();
        for (final Warning warning : added) {
            warnings = withAdded(warnings, warning);
        }
        return new Issue(
                issue.id(),
                issue.repository(),
                issue.title(),
                issue.state(),
                issue.size(),
                blockedBy,
                parent,
                issue.link(),
                issue.text(),
                issue.worker(),
                issue.risk(),
                issue.estimate(),
                issue.minutes(),
                issue.priority(),
                warnings,
                issue.waitsOn(),
                issue.version(),
                issue.labels());
    }

    /** The warnings with one more, each kind once. */
    private static List<Warning> withAdded(final List<Warning> warnings, final Warning warning) {
        if (warnings.contains(warning)) {
            return warnings;
        }
        final List<Warning> more = new ArrayList<>(warnings);
        more.add(warning);
        return more;
    }

    /**
     * The project values and issue Priority option, by field name, as the planner writes them.
     *
     * @param issue the issue
     * @param priority its priority, one of {@link Story#PRIORITIES}
     * @param rank its rank within its priority
     * @return the values
     */
    public static Map<String, String> fields(
            final Issue issue, final String priority, final int rank) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FieldNames.STATUS, issue.state());
        fields.put(FieldNames.PRIORITY, IssuePriority.option(issue.id(), priority));
        fields.put(FieldNames.RANK, Integer.toString(rank));
        if (issue.size() != null) {
            fields.put(FieldNames.SIZE, issue.size());
        }
        fields.put(FieldNames.WORKER, worker(issue.worker()));
        fields.put(FieldNames.RISK, issue.risk().spelled());
        return fields;
    }

    /**
     * The project values and issue Priority option of a story, by field name; a story has no {@code
     * Status}, as it follows its issues while open and ends by being closed.
     *
     * @param story the story
     * @param rank its rank within its priority
     * @return the values
     */
    public static Map<String, String> fields(final Story story, final int rank) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FieldNames.PRIORITY, IssuePriority.option(story.id(), story.priority()));
        fields.put(FieldNames.RANK, Integer.toString(rank));
        if (story.size() != null) {
            fields.put(FieldNames.SIZE, story.size());
        }
        return fields;
    }

    private static Issue issue(
            final String id,
            final ItemView item,
            final Context context,
            final List<String> faults) {
        final Workflow workflow = context.config.workflow();
        final String status = item.fields().getOrDefault(FieldNames.STATUS, "");
        final String state;
        if (!item.open()) {
            // Closing is an issue's only end: GitHub's reason tells which, whatever Status says.
            state = closedState(item.stateReason(), workflow);
        } else if (workflow.closed().contains(status)) {
            // Open under a closed Status: shown outside the workflow, so nothing reads it closed.
            state = status + OPEN_UNDER;
        } else {
            // An issue without a Status stands in the workflow's first state; none is written.
            state = status.isEmpty() ? workflow.states().get(0) : status;
        }
        List<Warning> warnings = new ArrayList<>();
        String parent = null;
        if (item.parentIssueId() != null) {
            final ItemView parentItem = context.byIssueId.get(item.parentIssueId());
            if (parentItem == null) {
                warnings = withAdded(warnings, Warning.parentOutsideProject());
            } else if (!context.storyOf(parentItem)) {
                // Under a story it is the story's issue; under an issue, a Porelo sub-issue.
                if (context.twice.contains(parentItem.issueId())) {
                    warnings = withAdded(warnings, Warning.dependsOnUnplaced());
                } else if (!parentItem.repository().equals(item.repository())) {
                    warnings = withAdded(warnings, Warning.parentInOtherRepository());
                } else {
                    parent = Objects.requireNonNull(context.idOf.get(parentItem.issueId()));
                }
            }
        }
        final List<String> blockedBy = new ArrayList<>();
        final List<Wait> waitsOn = new ArrayList<>();
        for (final String blocker : item.blockedByIds()) {
            final ItemView blocking = context.byIssueId.get(blocker);
            if (blocking == null) {
                // No item of the project: the issue stays on the board and waits on it.
                final URI forge = context.config.forge();
                if (forge == null) {
                    warnings = withAdded(warnings, Warning.blockerOutsideProject());
                } else {
                    waitsOn.add(wait(item, blocker, context.waits, forge));
                }
            } else if (context.storyOf(blocking)) {
                warnings = withAdded(warnings, Warning.blockedByStory());
            } else if (blocking.archived()) {
                if (blocking.open()) {
                    // Open work archived by hand: the board cannot place it, so the link is cut.
                    warnings = withAdded(warnings, Warning.dependsOnUnplaced());
                } else {
                    // Closed and archived, as GitHub archives finished work on its own: a done
                    // wait, as a closed blocker outside the project is.
                    waitsOn.add(
                            new Wait(
                                    context.config.owner()
                                            + "/"
                                            + blocking.repository()
                                            + "#"
                                            + blocking.number(),
                                    true,
                                    blocking.link()));
                }
            } else if (context.twice.contains(blocking.issueId())) {
                warnings = withAdded(warnings, Warning.dependsOnUnplaced());
            } else {
                blockedBy.add(Objects.requireNonNull(context.idOf.get(blocking.issueId())));
            }
        }
        if (isStory(item)) {
            // Marked a story outside the repository that holds the stories: shown as an issue.
            warnings = withAdded(warnings, Warning.storyOutsideOwnRepository());
        }
        if (context.pastPage.contains(item.itemId())) {
            warnings = withAdded(warnings, Warning.pastOnePage());
        }
        // A blocker GitHub counts but does not name is one the person's login cannot read.
        for (int unseen = 0; unseen < item.unseenBlockers(); unseen++) {
            waitsOn.add(Wait.unreadable());
        }
        final List<Warning> found = new ArrayList<>();
        final String size = choice(item, FieldNames.SIZE, SIZES, Warning.unknownSize(), found);
        final String priority = priority(item, found);
        final Double estimate = figure(item, FieldNames.ESTIMATE, found);
        final Double minutes = figure(item, FieldNames.MINUTES, found);
        // The board shows none of the stage sums but Minutes; each is still warned, as no stage
        // is saved over it.
        figure(item, FieldNames.VM_MINUTES, found);
        figure(item, FieldNames.TOKENS, found);
        figure(item, FieldNames.STAGES, found);
        final Worker worker = workerOf(item);
        if (worker == null) {
            warnings = withAdded(warnings, Warning.unknownWorker());
        }
        final Risk risk = riskOf(item);
        if (risk == null) {
            warnings = withAdded(warnings, Warning.unknownRisk());
        }
        String body = item.body();
        if (body.length() > BodySections.LONGEST) {
            // The board holds what GitHub takes; the text itself is a person's and not repeated.
            found.add(Warning.bodyTooLong());
            body = "";
        }
        for (final Warning warning : found) {
            warnings = withAdded(warnings, warning);
        }
        return new Issue(
                id,
                item.repository(),
                item.title(),
                state,
                size,
                blockedBy,
                parent,
                item.link(),
                body,
                // Shown as an agent's and at the highest care, held by its warning until a person
                // sets it.
                worker == null ? Worker.AGENT : worker,
                risk == null ? Risk.HARM : risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                ItemVersions.of(item),
                labels(item));
    }

    /** An owner's or a repository's name as GitHub allows it in an address. */
    private static final Pattern REPOSITORY_PART = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    /**
     * The wait on a blocker that is no item of the project: named as GitHub names it, done when
     * GitHub has it closed, and unknown when it could not be read; a blocker GitHub names in no
     * form an address can have is a wait on something not readable.
     */
    static Wait wait(
            final ItemView item,
            final String blocker,
            final Map<String, Snapshot.Waited> waits,
            final URI forge) {
        final Snapshot.Waited read = waits.get(blocker);
        String repository = read == null ? null : read.repository();
        int number = read == null ? 0 : read.number();
        if (repository == null) {
            for (final ItemView.Blocker named : item.blockers()) {
                if (named.issueId().equals(blocker)) {
                    repository = named.repository();
                    number = named.number();
                    break;
                }
            }
        }
        final int slash = repository == null ? -1 : repository.indexOf('/');
        if (repository == null
                || number < 1
                || slash < 1
                || !REPOSITORY_PART.matcher(repository.substring(0, slash)).matches()
                || !REPOSITORY_PART.matcher(repository.substring(slash + 1)).matches()) {
            return Wait.unreadable();
        }
        return new Wait(
                repository + "#" + number,
                read == null ? null : read.closed(),
                URI.create(forge + "/" + repository + "/issues/" + number));
    }

    /** The most minutes an issue's work is read as having taken, its stages together. */
    static final double MOST_TAKEN = 10_000_000;

    /**
     * An item's priority in the planner's words, read from the organization's issue field: none
     * when it holds no option, and none, warned, for an option the planner does not know.
     */
    private static @Nullable String priority(final ItemView item, final List<Warning> found) {
        final String option = item.fields().getOrDefault(FieldNames.PRIORITY, "");
        if (option.isEmpty()) {
            return null;
        }
        final String priority = IssuePriority.read(option);
        if (priority == null) {
            found.add(Warning.unknownPriority());
        }
        return priority;
    }

    /** The largest whole number a GitHub number field holds exactly. */
    static final double MOST_WHOLE = 9_007_199_254_740_991d;

    /**
     * An item's number field within its bounds, or null when it is empty; any other value is
     * warned, naming the field, and read as none, so no figure overflows a sum.
     */
    private static @Nullable Double figure(
            final ItemView item, final String field, final List<Warning> found) {
        final String given = item.fields().getOrDefault(field, "").strip();
        if (given.isEmpty()) {
            return null;
        }
        final Double value = bounded(field, given);
        if (value == null) {
            found.add(Warning.unreadableNumber(field));
        }
        return value;
    }

    /**
     * A number field's value when it lies within the field's bounds, or null: no number, or one out
     * of range. The one rule the board reads by and a stage's save adds by.
     *
     * @param field the field's name
     * @param given the field's value as GitHub holds it, not empty
     * @return the value, or null when it is none in range
     */
    public static @Nullable Double bounded(final String field, final String given) {
        try {
            final double value = Double.parseDouble(given);
            return inBounds(field, value) ? value : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether a value lies within a number field's bounds: an estimate from 1 minute, minutes from
     * 0, each up to its most; tokens and stages whole, from 0.
     *
     * @param field the field's name
     * @param value the value
     * @return true when the board reads it
     */
    public static boolean inBounds(final String field, final double value) {
        return switch (field) {
            case FieldNames.ESTIMATE -> value >= 1 && value <= Fields.MOST_ESTIMATE;
            case FieldNames.MINUTES, FieldNames.VM_MINUTES -> value >= 0 && value <= MOST_TAKEN;
            case FieldNames.TOKENS, FieldNames.STAGES ->
                    value >= 0 && value <= MOST_WHOLE && value == Math.rint(value);
            default -> false;
        };
    }

    private static Story story(
            final String id,
            final ItemView item,
            final Map<String, Issue> issues,
            final Set<String> twiceIds,
            final Set<String> pastPage,
            final Map<String, String> idOf,
            final List<String> repositories,
            final StoryStates states,
            final Workflow workflow,
            final List<String> faults,
            final Set<String> outside) {
        final List<String> own = new ArrayList<>();
        final List<Warning> warnings = new ArrayList<>();
        boolean whole = true;
        if (item.parentIssueId() != null) {
            warnings.add(Warning.storyUnderIssue());
        }
        for (final String subIssueId : item.subIssueIds()) {
            final String subId = idOf.get(subIssueId);
            if (subId != null && (issues.containsKey(subId) || twiceIds.contains(subId))) {
                if (!own.contains(subId)) {
                    own.add(subId);
                }
            } else {
                if (subId == null) {
                    if (!warnings.contains(Warning.issueOutsideProject())) {
                        warnings.add(Warning.issueOutsideProject());
                    }
                    outside.add(subIssueId);
                }
                whole = false;
            }
        }
        String also;
        String body = item.body();
        try {
            also = BodySections.read(body).section(ALSO_NEEDS).orElse("");
        } catch (final IllegalArgumentException e) {
            warnings.add(Warning.bodyTooLong());
            also = "";
            body = "";
            whole = false;
        }
        for (final String line : also.lines().map(String::strip).toList()) {
            if (line.isEmpty()) {
                continue;
            }
            final Matcher matcher = REFERENCE.matcher(line);
            final String repository =
                    matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
            final String named = repository + "#" + (matcher.matches() ? matcher.group(2) : "");
            final String neededId =
                    repositories.contains(repository) && idOf.containsValue(named) ? named : null;
            if (neededId != null && issues.containsKey(neededId)) {
                if (!own.contains(neededId)) {
                    own.add(neededId);
                }
            } else {
                // The line is a person's words, so the warning does not repeat it.
                if (!warnings.contains(Warning.alsoNeedsNotOnBoard())) {
                    warnings.add(Warning.alsoNeedsNotOnBoard());
                }
                whole = false;
            }
        }
        final String state;
        @Nullable Instant closedAt = null;
        if (pastPage.contains(item.itemId())) {
            warnings.add(Warning.pastOnePage());
            whole = false;
        }
        for (final String part : own) {
            final Issue issue = issues.get(part);
            if (issue != null && issue.warnings().contains(Warning.pastOnePage())) {
                // An issue of it read in part may have parts the story misses.
                whole = false;
            }
        }
        if (!item.open()) {
            // A story ends by being closed; the day its test passed is the day it closed.
            state = closedState(item.stateReason(), workflow);
            if (!workflow.closed().isEmpty() && state.equals(workflow.closed().get(0))) {
                closedAt = item.closedAt();
            }
        } else {
            final List<Issue> heldIssues =
                    own.stream().map(issues::get).filter(Objects::nonNull).toList();
            final List<String> held = heldIssues.stream().map(Issue::state).toList();
            // Only an issue in a state of the workflow tells the story's.
            final List<String> known = held.stream().filter(workflow.states()::contains).toList();
            final boolean inDoubt = known.size() < held.size();
            if (inDoubt && known.stream().allMatch(workflow.closed()::contains)) {
                // Its issues give no state the workflow knows, or only closed ones while another
                // stands outside the workflow, which counts as open: as the Git store says it.
                state = "";
                warnings.add(Warning.noState());
            } else {
                final String followed = states.storyState(known);
                if (followed == null && states.fault().isPresent()) {
                    // No rule: the board names its fault, and the story gets no guessed state.
                    state = "";
                    warnings.add(Warning.noState());
                } else {
                    // With no issue left it is new, the workflow's first state.
                    state = followed != null ? followed : workflow.states().get(0);
                }
                if (followed != null) {
                    states.behind(followed, heldIssues.stream().map(Issue::id).toList(), held)
                            .ifPresent(warnings::add);
                }
            }
        }
        String priority = priority(item, warnings);
        if (priority == null) {
            // A story sorts by its priority, so one without is told; an issue need have none.
            if (item.fields().getOrDefault(FieldNames.PRIORITY, "").isEmpty()) {
                warnings.add(Warning.noPriority());
            }
            priority = "Medium";
        }
        final String size = choice(item, FieldNames.SIZE, SIZES, Warning.unknownSize(), warnings);
        return new Story(
                id,
                item.title(),
                state,
                priority,
                size,
                own,
                body,
                item.link(),
                whole,
                closedAt,
                warnings,
                ItemVersions.of(item),
                labels(item));
    }

    /**
     * An item's labels as the board shows them, in GitHub's order: one that breaks a label's name,
     * or one named again, is left out, as are any past the most an item carries. A label left out
     * is never written, so it stays on GitHub.
     */
    private static List<String> labels(final ItemView item) {
        return item.labels().stream()
                .filter(TopicLabels::wellFormed)
                .distinct()
                .limit(TopicLabels.MOST)
                .toList();
    }

    /**
     * Gives each sub-issue its parent's worker and the higher of the two risks, as a part is
     * worked.
     */
    private static void workedAsParents(final Map<String, Issue> issues) {
        for (final Map.Entry<String, Issue> entry : issues.entrySet()) {
            final Issue issue = entry.getValue();
            final Issue parent = issue.parent() == null ? null : issues.get(issue.parent());
            if (parent == null) {
                continue;
            }
            entry.setValue(
                    issue.withWorkerAndRisk(
                            parent.worker(), Risk.highest(List.of(issue.risk(), parent.risk()))));
        }
    }

    /**
     * Cuts the parent of every issue whose parents lead back to it, which GitHub itself refuses but
     * a race may leave; each is warned and stands without a parent.
     */
    private static void cutCycles(final Map<String, Issue> issues) {
        final List<String> circle = new ArrayList<>();
        for (final Issue issue : issues.values()) {
            final Set<String> seen = new HashSet<>();
            Issue at = issue.parent() == null ? null : issues.get(issue.parent());
            while (at != null && seen.add(at.id())) {
                if (at.id().equals(issue.id())) {
                    circle.add(issue.id());
                    break;
                }
                at = at.parent() == null ? null : issues.get(at.parent());
            }
        }
        for (final String id : circle) {
            final Issue issue = Objects.requireNonNull(issues.get(id));
            issues.put(
                    id, relinked(issue, null, issue.blockedBy(), List.of(Warning.parentCircle())));
        }
    }

    /**
     * Cuts the parent of every sub-issue of a sub-issue, which the board cannot hold: a Porelo
     * sub-issue's parent stands under a story, never under another issue. Each is found before any
     * is cut, so the order the items come in changes nothing.
     */
    private static void cutSubSubIssues(final Map<String, Issue> issues) {
        final List<String> deep = new ArrayList<>();
        for (final Issue issue : issues.values()) {
            final Issue parent = issue.parent() == null ? null : issues.get(issue.parent());
            if (parent != null && parent.parent() != null) {
                deep.add(issue.id());
            }
        }
        for (final String id : deep) {
            final Issue issue = Objects.requireNonNull(issues.get(id));
            issues.put(
                    id,
                    relinked(
                            issue, null, issue.blockedBy(), List.of(Warning.subIssueOfSubIssue())));
        }
    }

    /**
     * Cuts each link of a board issue to an issue the board does not place - one of the project's
     * items the board does not show, such as an archived one, or one whose id names two items - and
     * warns it.
     *
     * @param issues the board's issues, changed in place
     * @param gone whether the board does not place the issue of an id
     */
    private static void cutDangling(
            final Map<String, Issue> issues, final java.util.function.Predicate<String> gone) {
        for (final Issue issue : List.copyOf(issues.values())) {
            issues.put(issue.id(), cutDangling(issue, gone));
        }
    }

    /** The issue without its links to issues the board does not place, warned when any is cut. */
    private static Issue cutDangling(
            final Issue issue, final java.util.function.Predicate<String> gone) {
        final boolean parentGone = issue.parent() != null && gone.test(issue.parent());
        final List<String> blockers = issue.blockedBy().stream().filter(gone.negate()).toList();
        if (!parentGone && blockers.size() == issue.blockedBy().size()) {
            return issue;
        }
        return relinked(
                issue,
                parentGone ? null : issue.parent(),
                blockers,
                List.of(Warning.dependsOnUnplaced()));
    }

    /** The issue warned when its state is none of the workflow's, so the board takes the state. */
    private static Issue outsideWorkflow(final Issue issue, final Workflow workflow) {
        return workflow.states().contains(issue.state())
                ? issue
                : relinked(
                        issue,
                        issue.parent(),
                        issue.blockedBy(),
                        List.of(Warning.stateOutsideWorkflow()));
    }

    /**
     * The closed state an item GitHub has closed stands in, by the reason it was closed for: the
     * workflow's first closed state for one completed or closed for no reason, its second for one
     * not planned or a duplicate. A workflow without a closed state cannot place it.
     *
     * @param reason why GitHub closed it, or null
     * @param workflow the project's workflow
     * @return the state
     */
    static String closedState(final @Nullable String reason, final Workflow workflow) {
        final List<String> closed = workflow.closed();
        if (closed.isEmpty()) {
            return "Closed";
        }
        return rejecting(reason) && closed.size() > 1 ? closed.get(1) : closed.get(0);
    }

    /**
     * Whether GitHub closed an item for a reason that reads as a rejection.
     *
     * @param reason why GitHub closed it, or null
     * @return true for one not planned or a duplicate
     */
    static boolean rejecting(final @Nullable String reason) {
        return reason != null && REJECTING.contains(reason.toLowerCase(Locale.ROOT));
    }

    /** An option the planner knows, or null: an unknown one is warned with {@code unknown}. */
    private static @Nullable String choice(
            final ItemView item,
            final String field,
            final List<String> allowed,
            final Warning unknown,
            final List<Warning> found) {
        final String value = item.fields().getOrDefault(field, "");
        if (value.isEmpty()) {
            return null;
        }
        if (!allowed.contains(value)) {
            found.add(unknown);
            return null;
        }
        return value;
    }

    /** The Worker the item names, an agent when it names none, or null for one unknown. */
    private static @Nullable Worker workerOf(final ItemView item) {
        final String value = item.fields().getOrDefault(FieldNames.WORKER, "");
        return switch (value) {
            case "", "Agent" -> Worker.AGENT;
            case "Manual" -> Worker.MANUAL;
            default -> null;
        };
    }

    private static String worker(final Worker worker) {
        return worker == Worker.MANUAL ? "Manual" : "Agent";
    }

    /** The Risk the item names, ordinary when it names none, or null for one unknown. */
    private static @Nullable Risk riskOf(final ItemView item) {
        final String value = item.fields().getOrDefault(FieldNames.RISK, "");
        if (value.isEmpty()) {
            return Risk.ORDINARY;
        }
        try {
            return Risk.of(value);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static Comparator<ItemView> byPriorityThenRank() {
        return Comparator.comparingInt(IssueMapping::priorityIndex)
                .thenComparingInt(IssueMapping::rankOf)
                .thenComparing(ItemView::repository)
                .thenComparingInt(ItemView::number);
    }

    private static int priorityIndex(final ItemView item) {
        final int index =
                Story.PRIORITIES.indexOf(
                        Objects.requireNonNullElse(
                                IssuePriority.read(item.fields().get(FieldNames.PRIORITY)), ""));
        return index < 0 ? Story.PRIORITIES.indexOf("Medium") : index;
    }

    /**
     * A rank as GitHub's number field holds it, which may carry a fraction; an unreadable one sorts
     * last.
     */
    private static int rankOf(final ItemView item) {
        final String value = item.fields().get(FieldNames.RANK);
        if (value == null) {
            return MOST_RANK + 1;
        }
        try {
            final double rank = Double.parseDouble(value);
            return Double.isFinite(rank) && rank >= 1 && rank <= MOST_RANK
                    ? (int) rank
                    : MOST_RANK + 1;
        } catch (final NumberFormatException e) {
            return MOST_RANK + 1;
        }
    }

    /**
     * Answers an item's id, its tracker reference: its repository, as the reader took it
     * lowercased, and GitHub's number.
     *
     * @param item the item
     * @return the reference, or null when the repository or the number cannot make one
     */
    static @Nullable String idOf(final ItemView item) {
        final String id = item.repository() + "#" + item.number();
        return Ids.isReference(id) ? id : null;
    }

    /**
     * Whether an item is a story: an issue of the issue type {@link #STORY_TYPE}.
     *
     * @param item the item, or null
     * @return true for a story
     */
    static boolean isStory(final @Nullable ItemView item) {
        return item != null && STORY_TYPE.equals(item.issueType());
    }

    /**
     * Why no stage result is written into an issue's body, and what a person must repair; empty
     * when it can be written. A body too long is refused when it is written.
     */
    private static java.util.Optional<String> unclearBody(final ItemView item) {
        final java.util.Optional<String> repair;
        try {
            repair = BodySections.read(item.body()).ambiguity();
        } catch (final IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
        return repair.map(
                what ->
                        " has a body whose sections are unclear, so no stage result is written"
                                + " into it; repair: "
                                + what);
    }

    /** Names an item by its repository and number alone, never its title or body. */
    private static String where(final ItemView item) {
        return "Item " + item.repository() + "#" + item.number();
    }
}
