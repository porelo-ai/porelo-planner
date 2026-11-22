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
import org.fuin.porelo.planner.store.OutsideIssue;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * Maps a GitHub project's items to the planner's stories and issues and back. An item a person
 * edited by hand into something the board cannot hold is left out as a fault, never a crash.
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
     * @param outsideWorkflow the issues left out because their Status is none of the workflow's
     *     states, by priority and then rank
     */
    public record Mapped(
            List<Story> stories,
            List<Issue> issues,
            List<String> faults,
            Map<String, StageComment.Sums> sums,
            List<String> rewrite,
            List<String> outside,
            List<OutsideIssue> outsideWorkflow) {

        /** Copies the lists and the map. */
        public Mapped {
            stories = List.copyOf(stories);
            issues = List.copyOf(issues);
            faults = List.copyOf(faults);
            sums = Collections.unmodifiableMap(new LinkedHashMap<>(sums));
            rewrite = List.copyOf(rewrite);
            outside = List.copyOf(outside);
            outsideWorkflow = List.copyOf(outsideWorkflow);
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
     * Maps the items, each known by its tracker reference. Archived items and those named left out
     * are not on the board, and a story or issue that needs one of those left out is told so.
     *
     * @param items every item of the project
     * @param leftOut the item ids of items the board leaves out, already named in a fault
     * @param config the project's repositories and workflow
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @return the stories, the issues and the faults
     */
    public static Mapped read(
            final Collection<ItemView> items,
            final Set<String> leftOut,
            final ProjectConfig config,
            final StoryStates states) {
        return read(items, leftOut, config, states, Map.of());
    }

    /**
     * Maps the items as {@link #read(Collection, Set, ProjectConfig, StoryStates)} does; an issue
     * blocked by an issue that is no item of the project waits on it, with its state as read.
     *
     * @param items every item of the project
     * @param leftOut the item ids of items the board leaves out, already named in a fault
     * @param config the project's repositories and workflow
     * @param states derives a story's state from its issues' when its {@code Status} is empty
     * @param waits the blockers that are no items of the project, by their node ids, as last read
     * @return the stories, the issues and the faults
     */
    public static Mapped read(
            final Collection<ItemView> items,
            final Set<String> leftOut,
            final ProjectConfig config,
            final StoryStates states,
            final Map<String, Snapshot.Waited> waits) {
        final Workflow workflow = config.workflow();
        final List<String> faults = new ArrayList<>();
        final Map<String, ItemView> byIssueId = new HashMap<>();
        final Map<String, String> idOf = new HashMap<>();
        final Set<String> taken = new HashSet<>();
        final List<ItemView> live = new ArrayList<>();
        for (final ItemView item : items) {
            final String id = idOf(item);
            if (id == null) {
                faults.add(where(item) + " has no valid tracker reference and is left out");
                continue;
            }
            if (!taken.add(id)) {
                faults.add(where(item) + " stands in the project twice and is left out");
                continue;
            }
            final boolean shown = !item.archived() && !leftOut.contains(item.itemId());
            // Left out before it can be found as a parent or a sub-issue, so nothing hangs on it.
            if (!config.repositories().contains(item.repository())) {
                if (shown) {
                    faults.add(
                            where(item)
                                    + " lies outside the project's repositories and is left out");
                }
                continue;
            }
            if (isStory(item) && !item.repository().equals(config.own())) {
                if (shown) {
                    faults.add(
                            id
                                    + " is a story outside the project's own repository and is left out");
                }
                continue;
            }
            byIssueId.put(item.issueId(), item);
            idOf.put(item.issueId(), id);
            if (shown) {
                live.add(item);
            }
        }

        final Map<String, ItemView> issueItems = new LinkedHashMap<>();
        final Map<String, ItemView> storyItems = new LinkedHashMap<>();
        for (final ItemView item : live) {
            // Every live item was given its id above.
            final String id = Objects.requireNonNull(idOf.get(item.issueId()));
            final boolean story = isStory(item);
            if (story) {
                if (item.parentIssueId() != null) {
                    faults.add(id + " is a story under another issue, shown without it");
                }
                storyItems.put(id, item);
            } else {
                issueItems.put(id, item);
            }
        }

        final Map<String, Issue> issues = new LinkedHashMap<>();
        final Set<String> unstated = new HashSet<>();
        for (final Map.Entry<String, ItemView> entry : issueItems.entrySet()) {
            final Issue issue =
                    issue(
                            entry.getKey(),
                            entry.getValue(),
                            workflow,
                            byIssueId,
                            idOf,
                            waits,
                            config.forge(),
                            faults);
            if (issue != null) {
                issues.put(issue.id(), issue);
                if (!workflow.states().contains(issue.state())) {
                    unstated.add(issue.id());
                }
            }
        }
        // An issue whose Status is its one defect goes through the same checks as one on the
        // board, so only an issue a move can put on the board is listed outside it.
        leaveOutCycles(issues, faults);
        leaveOutSubSubIssues(issues, faults);
        workedAsParents(issues);
        final Map<String, Issue> unplaced = new LinkedHashMap<>();
        for (final String id : List.copyOf(issues.keySet())) {
            if (unstated.contains(id)) {
                unplaced.put(id, Objects.requireNonNull(issues.remove(id)));
            }
        }
        leaveOutDangling(issues, unplaced, faults);
        unplaced.values().forEach(issue -> faults.add(outsideFault(issue.id(), issue.state())));
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

        final List<Story> stories = new ArrayList<>();
        final Set<String> outside = new LinkedHashSet<>();
        for (final Map.Entry<String, ItemView> entry : storyItems.entrySet()) {
            final Story story =
                    story(
                            entry.getKey(),
                            entry.getValue(),
                            issues,
                            idOf,
                            config.repositories(),
                            states,
                            workflow,
                            faults,
                            outside);
            if (story != null) {
                stories.add(story);
            }
        }

        final Comparator<ItemView> order = byPriorityThenRank();
        final List<Story> sortedStories =
                stories.stream()
                        .sorted(Comparator.comparing(s -> storyItems.get(s.id()), order))
                        .toList();
        final List<Issue> sortedIssues =
                issues.values().stream()
                        .sorted(Comparator.comparing(i -> issueItems.get(i.id()), order))
                        .toList();
        final List<OutsideIssue> outsideWorkflow =
                unplaced.values().stream()
                        .sorted(Comparator.comparing(i -> issueItems.get(i.id()), order))
                        .map(issue -> outsideIssue(issue, issueItems, stories, byIssueId, idOf))
                        .toList();
        return new Mapped(
                sortedStories,
                sortedIssues,
                faults,
                sums,
                rewrite,
                List.copyOf(outside),
                outsideWorkflow);
    }

    /**
     * An issue left out because its Status is none of the workflow's, with the story or the issue
     * the board holds that it stands under, so a move into the workflow is judged as its place
     * there will be.
     */
    private static OutsideIssue outsideIssue(
            final Issue issue,
            final Map<String, ItemView> issueItems,
            final List<Story> stories,
            final Map<String, ItemView> byIssueId,
            final Map<String, String> idOf) {
        final ItemView item = Objects.requireNonNull(issueItems.get(issue.id()));
        final ItemView parentItem =
                item.parentIssueId() == null ? null : byIssueId.get(item.parentIssueId());
        final String storyId =
                parentItem != null && isStory(parentItem) ? idOf.get(parentItem.issueId()) : null;
        final String story =
                storyId != null && stories.stream().anyMatch(s -> s.id().equals(storyId))
                        ? storyId
                        : null;
        // Its parent and worker are those the board issue would have, a parent outside too.
        return new OutsideIssue(
                issue.id(),
                issue.repository(),
                issue.title(),
                issue.state(),
                story,
                issue.parent(),
                issue.link(),
                issue.worker());
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

    private static @Nullable Issue issue(
            final String id,
            final ItemView item,
            final Workflow workflow,
            final Map<String, ItemView> byIssueId,
            final Map<String, String> idOf,
            final Map<String, Snapshot.Waited> waits,
            final URI forge,
            final List<String> faults) {
        final String state = item.fields().getOrDefault(FieldNames.STATUS, "");
        // An issue outside the workflow has no open state to check against it.
        if (workflow.states().contains(state)) {
            checkOpen(id, item, state, workflow, faults);
        }
        String parent = null;
        if (item.parentIssueId() != null) {
            final ItemView parentItem = byIssueId.get(item.parentIssueId());
            if (parentItem == null) {
                faults.add(id + " has a parent outside the project and is left out");
                return null;
            }
            // Under a story it is the story's issue; under an issue, a Porelo sub-issue, whose
            // parent left out leaves it out too.
            if (!isStory(parentItem)) {
                if (!parentItem.repository().equals(item.repository())) {
                    faults.add(
                            id + " stands in another repository than its parent and is left out");
                    return null;
                }
                parent = Objects.requireNonNull(idOf.get(parentItem.issueId()));
            }
        }
        final List<String> blockedBy = new ArrayList<>();
        final List<Wait> waitsOn = new ArrayList<>();
        for (final String blocker : item.blockedByIds()) {
            final ItemView blocking = byIssueId.get(blocker);
            if (blocking == null) {
                // No item of the project: the issue stays on the board and waits on it.
                waitsOn.add(wait(item, blocker, waits, forge));
                continue;
            }
            final String blockerId = idOf.get(blocking.issueId());
            if (blockerId == null || isStory(blocking)) {
                faults.add(id + " is blocked by a story and is left out");
                return null;
            }
            blockedBy.add(blockerId);
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
                List.of(),
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

    /** The longest Status a fault names, as a plain option name of a single-select field. */
    private static final int LONGEST_NAMED_STATUS = 100;

    /**
     * Names an issue left out of the workflow and how a person brings it back; a Status that is no
     * plain option name is a person's text and not repeated.
     */
    private static String outsideFault(final String id, final String status) {
        final String what;
        if (status.isEmpty()) {
            what = id + " has no Status";
        } else if (plainName(status)) {
            what = id + " has the Status " + status + ", which the workflow does not hold";
        } else {
            what = id + " has a Status the workflow does not hold";
        }
        return what + "; move it into a state of the board to repair it";
    }

    /** Whether a text is short and on one line, without a control or line-breaking character. */
    private static boolean plainName(final String text) {
        return text.codePointCount(0, text.length()) <= LONGEST_NAMED_STATUS
                && text.codePoints()
                        .noneMatch(
                                c ->
                                        Character.isISOControl(c)
                                                || Character.getType(c) == Character.LINE_SEPARATOR
                                                || Character.getType(c)
                                                        == Character.PARAGRAPH_SEPARATOR);
    }

    private static @Nullable Story story(
            final String id,
            final ItemView item,
            final Map<String, Issue> issues,
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
            if (subId != null && issues.containsKey(subId)) {
                own.add(subId);
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
                faults.add(id + " also needs " + neededId + ", which the board left out");
                whole = false;
            }
        }
        final String status = item.fields().getOrDefault(FieldNames.STATUS, "");
        final String state;
        final boolean given = !status.isEmpty();
        if (given) {
            if (!workflow.states().contains(status)) {
                faults.add(id + " has a Status outside the workflow and is left out");
                return null;
            }
            state = status;
            checkOpen(id, item, state, workflow, faults);
        } else {
            final String followed =
                    states.storyState(
                            own.stream()
                                    .map(i -> Objects.requireNonNull(issues.get(i)).state())
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
                id, item.title(), state, given, priority, size, own, body, item.link(), whole);
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
                    new Issue(
                            issue.id(),
                            issue.repository(),
                            issue.title(),
                            issue.state(),
                            issue.size(),
                            issue.blockedBy(),
                            issue.parent(),
                            issue.link(),
                            issue.text(),
                            parent.worker(),
                            Risk.highest(List.of(issue.risk(), parent.risk())),
                            issue.estimate(),
                            issue.minutes(),
                            issue.priority(),
                            issue.warnings(),
                            issue.waitsOn()));
        }
    }

    /**
     * Leaves out every issue whose parents lead back to it, which GitHub itself refuses but a race
     * may leave.
     */
    private static void leaveOutCycles(final Map<String, Issue> issues, final List<String> faults) {
        final Set<String> cyclic = new HashSet<>();
        for (final Issue issue : issues.values()) {
            final Set<String> seen = new HashSet<>();
            Issue at = issue;
            while (at != null && at.parent() != null) {
                if (!seen.add(at.id())) {
                    cyclic.add(issue.id());
                    break;
                }
                at = issues.get(at.parent());
            }
        }
        for (final String id : cyclic) {
            issues.remove(id);
            faults.add(id + " stands in a circle of parents and is left out");
        }
    }

    /**
     * Leaves out every sub-issue of a sub-issue, which the board cannot hold: a Porelo sub-issue's
     * parent stands under a story, never under another issue.
     */
    private static void leaveOutSubSubIssues(
            final Map<String, Issue> issues, final List<String> faults) {
        final List<String> deep = new ArrayList<>();
        for (final Issue issue : issues.values()) {
            final Issue parent = issue.parent() == null ? null : issues.get(issue.parent());
            if (parent != null && parent.parent() != null) {
                deep.add(issue.id());
            }
        }
        for (final String id : deep) {
            issues.remove(id);
            faults.add(id + " is a sub-issue of a sub-issue and is left out");
        }
    }

    /**
     * Leaves out, until none is left, every issue whose parent or blocker was left out: a board
     * issue that depends on one off the board, and an issue outside the workflow that depends on
     * one neither on the board nor outside it.
     */
    private static void leaveOutDangling(
            final Map<String, Issue> issues,
            final Map<String, Issue> unplaced,
            final List<String> faults) {
        boolean removed = true;
        while (removed) {
            removed =
                    leaveOutDanglingFrom(issues, issues, unplaced, faults)
                            | leaveOutDanglingFrom(unplaced, issues, unplaced, faults);
        }
    }

    /** One pass over one of the two sets; answers whether it left any out. */
    private static boolean leaveOutDanglingFrom(
            final Map<String, Issue> from,
            final Map<String, Issue> issues,
            final Map<String, Issue> unplaced,
            final List<String> faults) {
        // A board issue needs what it hangs on to be on the board too.
        final boolean onBoard = from == issues;
        boolean removed = false;
        for (final Issue issue : List.copyOf(from.values())) {
            final java.util.function.Predicate<String> gone =
                    id -> !issues.containsKey(id) && (onBoard || !unplaced.containsKey(id));
            final boolean parentGone = issue.parent() != null && gone.test(issue.parent());
            final boolean blockerGone = issue.blockedBy().stream().anyMatch(gone);
            if (parentGone || blockerGone) {
                from.remove(issue.id());
                faults.add(issue.id() + " depends on an issue left out and is left out too");
                removed = true;
            }
        }
        return removed;
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
