package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Warning;
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

    /** The label that makes an issue of the project's own repository a story. */
    public static final String STORY_LABEL = "story";

    /** The sizes a {@code Size} field may hold. */
    public static final List<String> SIZES = List.of("XS", "S", "M", "L", "XL");

    /** The highest rank, as an issue's head allows it. */
    public static final int MOST_RANK = 99_999;

    /**
     * The stories and issues an item set maps to, and what was left out.
     *
     * @param stories the stories, by priority and then rank
     * @param issues the issues, by priority and then rank
     * @param faults one fixed sentence per item left out or shown other than GitHub holds it
     * @param sums each issue's stage comments' sums, by its id, which the board shows over its
     *     number fields; an issue whose comments were not read has none
     * @param rewrite the issues whose number fields disagree with their sums, which the next save
     *     of each writes again
     * @param outside the node ids of the sub-issues a story was named to have outside the project,
     *     each once
     */
    public record Mapped(
            List<Story> stories,
            List<Issue> issues,
            List<String> faults,
            Map<String, StageComment.Sums> sums,
            List<String> rewrite,
            List<String> outside) {

        /** Copies the lists and the map. */
        public Mapped {
            stories = List.copyOf(stories);
            issues = List.copyOf(issues);
            faults = List.copyOf(faults);
            sums = Collections.unmodifiableMap(new LinkedHashMap<>(sums));
            rewrite = List.copyOf(rewrite);
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
     * @param states derives a story's state from its issues' when its {@code Status} is empty
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
     * @param states derives a story's state from its issues' when its {@code Status} is empty
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
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @param waits the blockers that are no items of the project, by their node ids, as last read
     * @return the stories, the issues and the faults
     */
    public static Mapped read(
            final Collection<ItemView> items,
            final Set<String> pastPage,
            final ProjectConfig config,
            final StoryStates states,
            final Map<String, Snapshot.Waited> waits) {
        final Workflow workflow = config.workflow();
        final List<String> faults = new ArrayList<>();
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
                if (item.parentIssueId() != null) {
                    faults.add(id + " is a story under another issue, shown without it");
                }
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
        final Map<String, StageComment.Sums> sums = new LinkedHashMap<>();
        final List<String> rewrite = new ArrayList<>();
        for (final String id : issues.keySet()) {
            final ItemView item = Objects.requireNonNull(issueItems.get(id));
            final List<CommentView> comments = item.comments();
            if (comments == null) {
                // Comments not read have no sum: the board shows the number fields as GitHub holds
                // them, and the check runs when the issue is saved, over its own comments.
                continue;
            }
            final StageComment.Sums sum = StageComment.sums(comments);
            sum.faults().forEach(fault -> faults.add(id + ": " + fault));
            sums.put(id, sum);
            if (checkSums(id, item, sum, faults)) {
                rewrite.add(id);
            }
            // The board shows the comments' sum over a field that disagrees, so its minutes do too;
            // an issue no stage was measured for and no field names has none.
            if (sum.stages() > 0 || issues.get(id).minutes() != null) {
                issues.put(id, withMinutes(issues.get(id), sum.minutes()));
            }
        }

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

        final Comparator<ItemView> order = byPriorityThenRank();
        final List<Story> sortedStories =
                stories.stream()
                        .sorted(Comparator.comparing(Map.Entry::getValue, order))
                        .map(Map.Entry::getKey)
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
                        .toList();
        return new Mapped(sortedStories, sortedIssues, faults, sums, rewrite, List.copyOf(outside));
    }

    /**
     * What an item's mapping reads beside it: the project's items by their issues' node ids and
     * their ids, the node ids of the items whose id names another item too, and those read past one
     * page.
     */
    private static final class Context {
        private final ProjectConfig config;
        private final Set<String> pastPage;
        private final Map<String, Snapshot.Waited> waits;
        private final Map<String, ItemView> byIssueId = new HashMap<>();
        private final Map<String, String> idOf = new HashMap<>();
        private final Set<String> twice = new HashSet<>();

        private Context(
                final ProjectConfig config,
                final Set<String> pastPage,
                final Map<String, Snapshot.Waited> waits) {
            this.config = config;
            this.pastPage = pastPage;
            this.waits = waits;
        }

        /** Whether an item is a story: labeled one, in the repository that holds the stories. */
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
                issue.waitsOn());
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
     * The field values an issue's item holds, by field name, as the planner writes them.
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
        fields.put(FieldNames.PRIORITY, priority);
        fields.put(FieldNames.RANK, Integer.toString(rank));
        if (issue.size() != null) {
            fields.put(FieldNames.SIZE, issue.size());
        }
        fields.put(FieldNames.WORKER, worker(issue.worker()));
        fields.put(FieldNames.RISK, issue.risk().spelled());
        return fields;
    }

    /**
     * The field values a story's item holds, by field name; an empty {@code Status} lets the story
     * follow its issues.
     *
     * @param story the story
     * @param rank its rank within its priority
     * @return the values
     */
    public static Map<String, String> fields(final Story story, final int rank) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FieldNames.STATUS, story.stateGiven() ? story.state() : "");
        fields.put(FieldNames.PRIORITY, story.priority());
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
        // An issue without a Status stands in the workflow's first state; none is written for it.
        final String state = status.isEmpty() ? workflow.states().get(0) : status;
        // An issue outside the workflow has no open state to check against it.
        if (!status.isEmpty() && workflow.states().contains(state)) {
            checkOpen(id, item, state, workflow, faults);
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
                waitsOn.add(wait(item, blocker, context.waits, context.config.forge()));
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
                                    context.config.project().owner()
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
        final String size = choice(id, item, FieldNames.SIZE, SIZES, faults);
        final Worker worker = workerOf(id, item, faults);
        final Risk risk = riskOf(id, item, faults);
        String body = item.body();
        if (body.length() > BodySections.LONGEST) {
            // The board holds what GitHub takes; the text itself is a person's and not repeated.
            faults.add(id + " has a body longer than GitHub takes, shown without it");
            body = "";
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
                worker,
                risk,
                figure(id, item, FieldNames.ESTIMATE, 1, Fields.MOST_ESTIMATE, faults),
                figure(id, item, FieldNames.MINUTES, 0, MOST_TAKEN, faults),
                choice(id, item, FieldNames.PRIORITY, Story.PRIORITIES, faults),
                warnings,
                waitsOn);
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
     * A number field's minutes between {@code least} and {@code most}, or null when it is empty;
     * any other value is named as a fault and read as none, so no figure overflows a sum.
     */
    private static @Nullable Double figure(
            final String id,
            final ItemView item,
            final String field,
            final double least,
            final double most,
            final List<String> faults) {
        final String given = item.fields().getOrDefault(field, "").strip();
        if (given.isEmpty()) {
            return null;
        }
        try {
            final double value = Double.parseDouble(given);
            if (value >= least && value <= most) {
                return value;
            }
        } catch (final NumberFormatException e) {
            // Named below like any other value that is no number of minutes.
        }
        faults.add(id + " has no number of minutes in its field " + field + ", read as none");
        return null;
    }

    /** The issue with the minutes its stage comments sum to, within the most read. */
    private static Issue withMinutes(final Issue issue, final double minutes) {
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
                minutes >= 0 && minutes <= MOST_TAKEN ? minutes : issue.minutes(),
                issue.priority(),
                issue.warnings(),
                issue.waitsOn());
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
        boolean whole = true;
        for (final String subIssueId : item.subIssueIds()) {
            final String subId = idOf.get(subIssueId);
            if (subId != null && (issues.containsKey(subId) || twiceIds.contains(subId))) {
                if (!own.contains(subId)) {
                    own.add(subId);
                }
            } else {
                if (subId == null) {
                    faults.add(id + " has a sub-issue outside the project");
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
            faults.add(id + " has a body too long to read its Also needs, shown without it");
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
            if (neededId == null) {
                // The line is a person's words, so the fault does not repeat it.
                faults.add(id + " has a line under Also needs that names no issue of the project");
                whole = false;
            } else if (issues.containsKey(neededId)) {
                if (!own.contains(neededId)) {
                    own.add(neededId);
                }
            } else {
                faults.add(
                        id
                                + " also needs "
                                + neededId
                                + ", which the board does not show as one issue");
                whole = false;
            }
        }
        final String status = item.fields().getOrDefault(FieldNames.STATUS, "");
        final String state;
        final boolean given = !status.isEmpty();
        final List<Warning> warnings = new ArrayList<>();
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
        if (given) {
            state = status;
            if (workflow.states().contains(status)) {
                checkOpen(id, item, state, workflow, faults);
            } else {
                // Shown in a column of its own state, its issues with it.
                warnings.add(Warning.stateOutsideWorkflow());
            }
        } else {
            final String followed =
                    states.storyState(
                            // Only an issue in a state of the workflow tells the story's.
                            own.stream()
                                    .map(issues::get)
                                    .filter(Objects::nonNull)
                                    .map(Issue::state)
                                    .filter(workflow.states()::contains)
                                    .toList());
            state = followed != null ? followed : workflow.states().get(0);
        }
        String priority = item.fields().getOrDefault(FieldNames.PRIORITY, "");
        if (!Story.PRIORITIES.contains(priority)) {
            faults.add(
                    id
                            + (priority.isEmpty()
                                    ? " has no Priority, shown as Medium"
                                    : " has a Priority outside Highest to Lowest, shown as Medium"));
            priority = "Medium";
        }
        final String size = choice(id, item, FieldNames.SIZE, SIZES, faults);
        return new Story(
                        id,
                        item.title(),
                        state,
                        given,
                        priority,
                        size,
                        own,
                        body,
                        item.link(),
                        whole)
                .withWarnings(warnings);
    }

    /**
     * Names each number field that disagrees with its stage comments' sum, a missing one counted as
     * none.
     *
     * @return whether any disagrees
     */
    private static boolean checkSums(
            final String id,
            final ItemView item,
            final StageComment.Sums sum,
            final List<String> faults) {
        boolean apart = false;
        final Map<String, Double> wanted = new LinkedHashMap<>();
        wanted.put(FieldNames.MINUTES, sum.minutes());
        wanted.put(FieldNames.VM_MINUTES, sum.vmMinutes());
        wanted.put(FieldNames.TOKENS, (double) sum.tokens());
        wanted.put(FieldNames.STAGES, (double) sum.stages());
        for (final Map.Entry<String, Double> field : wanted.entrySet()) {
            final String given = item.fields().getOrDefault(field.getKey(), "").strip();
            double value;
            try {
                value = given.isEmpty() ? 0 : Double.parseDouble(given);
            } catch (final NumberFormatException e) {
                value = Double.NaN;
            }
            if (Math.abs(value - field.getValue()) > 1e-6 || Double.isNaN(value)) {
                faults.add(
                        id
                                + " has a "
                                + field.getKey()
                                + " apart from its stage comments' sum, shown as the sum");
                apart = true;
            }
        }
        return apart;
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
     * Cuts each link of a board issue to an issue the board does not place - one listed apart, or
     * one of the project's items the board does not show, such as an archived one - and warns it.
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
     * Names an item whose open or closed state disagrees with its {@code Status}, which stays the
     * truth.
     */
    private static void checkOpen(
            final String id,
            final ItemView item,
            final String state,
            final Workflow workflow,
            final List<String> faults) {
        if (item.open() == workflow.closed().contains(state)) {
            faults.add(
                    id
                            + " is "
                            + (item.open() ? "open" : "closed")
                            + " on GitHub against its Status "
                            + state);
        }
    }

    private static @Nullable String choice(
            final String id,
            final ItemView item,
            final String field,
            final List<String> allowed,
            final List<String> faults) {
        final String value = item.fields().getOrDefault(field, "");
        if (value.isEmpty()) {
            return null;
        }
        if (!allowed.contains(value)) {
            faults.add(id + " has a " + field + " the planner does not know, shown as none");
            return null;
        }
        return value;
    }

    private static Worker workerOf(
            final String id, final ItemView item, final List<String> faults) {
        final String value = item.fields().getOrDefault(FieldNames.WORKER, "");
        return switch (value) {
            case "", "Agent" -> Worker.AGENT;
            case "Manual" -> Worker.MANUAL;
            default -> {
                faults.add(id + " has a Worker the planner does not know, shown as Agent");
                yield Worker.AGENT;
            }
        };
    }

    private static String worker(final Worker worker) {
        return worker == Worker.MANUAL ? "Manual" : "Agent";
    }

    private static Risk riskOf(final String id, final ItemView item, final List<String> faults) {
        final String value = item.fields().getOrDefault(FieldNames.RISK, "");
        if (value.isEmpty()) {
            return Risk.ORDINARY;
        }
        try {
            return Risk.of(value);
        } catch (final IllegalArgumentException e) {
            faults.add(id + " has a Risk the planner does not know, shown as ordinary");
            return Risk.ORDINARY;
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
                Story.PRIORITIES.indexOf(item.fields().getOrDefault(FieldNames.PRIORITY, ""));
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

    /** Whether an item is a story: an issue labeled {@code story}. */
    private static boolean isStory(final @Nullable ItemView item) {
        return item != null && item.labels().contains(STORY_LABEL);
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
