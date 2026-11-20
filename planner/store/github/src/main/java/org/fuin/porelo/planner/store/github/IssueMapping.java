package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
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
     *     number fields
     * @param rewrite the issues whose number fields disagree with their sums, which the next save
     *     of each writes again
     */
    public record Mapped(
            List<Story> stories,
            List<Issue> issues,
            List<String> faults,
            Map<String, StageComment.Sums> sums,
            List<String> rewrite) {

        /** Copies the lists and the map. */
        public Mapped {
            stories = List.copyOf(stories);
            issues = List.copyOf(issues);
            faults = List.copyOf(faults);
            sums = Collections.unmodifiableMap(new LinkedHashMap<>(sums));
            rewrite = List.copyOf(rewrite);
        }
    }

    /** The heading of a story's section that lists the issues of other stories it needs too. */
    public static final String ALSO_NEEDS = "Also needs";

    private static final Pattern REFERENCE =
            Pattern.compile("^[-*]?\\s*([A-Za-z0-9._-]{1,100})#([0-9]{1,9})\\s*$");

    private IssueMapping() {}

    /**
     * Maps the items. Archived items are not on the board, but their ids stay taken.
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
        final Workflow workflow = config.workflow();
        final List<String> faults = new ArrayList<>();
        final Map<String, ItemView> byIssueId = new HashMap<>();
        final Map<String, ItemView> byReference = new HashMap<>();
        final Map<String, String> idOf = new HashMap<>();
        final Set<String> taken = new HashSet<>();
        final List<ItemView> live = new ArrayList<>();
        for (final ItemView item : items) {
            byIssueId.put(item.issueId(), item);
            byReference.put(item.repository() + "#" + item.number(), item);
            final String id = idOf(item);
            if (id == null) {
                faults.add(where(item) + " has no valid Id and is left out");
                continue;
            }
            if (!taken.add(id)) {
                faults.add(where(item) + " repeats the Id " + id + " and is left out");
                continue;
            }
            idOf.put(item.issueId(), id);
            if (!item.archived()) {
                live.add(item);
            }
        }

        final Map<String, ItemView> issueItems = new LinkedHashMap<>();
        final Map<String, ItemView> storyItems = new LinkedHashMap<>();
        for (final ItemView item : live) {
            // Every live item was given its id above.
            final String id = Objects.requireNonNull(idOf.get(item.issueId()));
            final boolean story = item.labels().contains(STORY_LABEL);
            if (story != isStoryId(id)) {
                faults.add(id + " is labeled against its Id and is left out");
            } else if (!config.repositories().contains(item.repository())) {
                faults.add(id + " lies outside the project's repositories and is left out");
            } else if (story && !item.repository().equals(config.own())) {
                faults.add(id + " is a story outside the project's own repository and is left out");
            } else if (story) {
                storyItems.put(id, item);
            } else {
                issueItems.put(id, item);
            }
        }

        final Map<String, Issue> issues = new LinkedHashMap<>();
        for (final Map.Entry<String, ItemView> entry : issueItems.entrySet()) {
            final Issue issue =
                    issue(entry.getKey(), entry.getValue(), workflow, byIssueId, idOf, faults);
            if (issue != null) {
                issues.put(issue.id(), issue);
            }
        }
        leaveOutCycles(issues, faults);
        leaveOutDangling(issues, faults);
        workedAsParents(issues);
        final Map<String, StageComment.Sums> sums = new LinkedHashMap<>();
        final List<String> rewrite = new ArrayList<>();
        for (final String id : issues.keySet()) {
            final ItemView item = Objects.requireNonNull(issueItems.get(id));
            final StageComment.Sums sum = StageComment.sums(item.comments());
            sum.faults().forEach(fault -> faults.add(id + ": " + fault));
            sums.put(id, sum);
            if (checkSums(id, item, sum, faults)) {
                rewrite.add(id);
            }
        }

        final List<Story> stories = new ArrayList<>();
        for (final Map.Entry<String, ItemView> entry : storyItems.entrySet()) {
            final Story story =
                    story(
                            entry.getKey(),
                            entry.getValue(),
                            issues,
                            idOf,
                            byReference,
                            config.repositories(),
                            states,
                            workflow,
                            faults);
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
        return new Mapped(sortedStories, sortedIssues, faults, sums, rewrite);
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
        fields.put(FieldNames.ID, issue.id());
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
        fields.put(FieldNames.ID, story.id());
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
            final List<String> faults) {
        final String state = item.fields().getOrDefault(FieldNames.STATUS, "");
        if (!workflow.states().contains(state)) {
            faults.add(id + " has a Status outside the workflow and is left out");
            return null;
        }
        checkOpen(id, item, state, workflow, faults);
        String parent = null;
        if (item.parentIssueId() != null) {
            final ItemView parentItem = byIssueId.get(item.parentIssueId());
            final String parentId = parentItem == null ? null : idOf.get(parentItem.issueId());
            if (parentId == null) {
                faults.add(id + " has a parent outside the project and is left out");
                return null;
            }
            if (!isStoryId(parentId)) {
                if (!Objects.requireNonNull(parentItem).repository().equals(item.repository())) {
                    faults.add(
                            id + " stands in another repository than its parent and is left out");
                    return null;
                }
                parent = parentId;
            }
        }
        final boolean sub = id.contains(".");
        if (sub != (parent != null) || (sub && !id.startsWith(parent + "."))) {
            faults.add(id + " does not stand under the issue its Id names and is left out");
            return null;
        }
        final List<String> blockedBy = new ArrayList<>();
        for (final String blocker : item.blockedByIds()) {
            final String blockerId = idOf.get(blocker);
            if (blockerId == null || isStoryId(blockerId)) {
                faults.add(id + " is blocked by an issue outside the project and is left out");
                return null;
            }
            blockedBy.add(blockerId);
        }
        final String size = choice(id, item, FieldNames.SIZE, SIZES, faults);
        final Worker worker = workerOf(id, item, faults);
        final Risk risk = riskOf(id, item, faults);
        return new Issue(
                id,
                item.repository(),
                item.title(),
                state,
                size,
                blockedBy,
                parent,
                item.link(),
                item.body(),
                worker,
                risk);
    }

    private static @Nullable Story story(
            final String id,
            final ItemView item,
            final Map<String, Issue> issues,
            final Map<String, String> idOf,
            final Map<String, ItemView> byReference,
            final List<String> repositories,
            final StoryStates states,
            final Workflow workflow,
            final List<String> faults) {
        final List<String> own = new ArrayList<>();
        boolean whole = true;
        for (final String subIssueId : item.subIssueIds()) {
            final String subId = idOf.get(subIssueId);
            if (subId != null && issues.containsKey(subId)) {
                own.add(subId);
            } else {
                if (subId == null) {
                    faults.add(id + " has a sub-issue outside the project");
                }
                whole = false;
            }
        }
        String also;
        try {
            also = BodySections.read(item.body()).section(ALSO_NEEDS).orElse("");
        } catch (final IllegalArgumentException e) {
            faults.add(id + " has a body too long to read its Also needs");
            also = "";
            whole = false;
        }
        for (final String line : also.lines().map(String::strip).toList()) {
            if (line.isEmpty()) {
                continue;
            }
            final Matcher matcher = REFERENCE.matcher(line);
            final ItemView needed =
                    matcher.matches() && repositories.contains(matcher.group(1))
                            ? byReference.get(matcher.group(1) + "#" + matcher.group(2))
                            : null;
            final String neededId = needed == null ? null : idOf.get(needed.issueId());
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
                id,
                item.title(),
                state,
                given,
                priority,
                size,
                own,
                item.body(),
                item.link(),
                whole);
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
                            Risk.highest(List.of(issue.risk(), parent.risk()))));
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

    /** Leaves out, until none is left, every issue whose parent or blocker was left out. */
    private static void leaveOutDangling(
            final Map<String, Issue> issues, final List<String> faults) {
        boolean removed = true;
        while (removed) {
            removed = false;
            for (final Issue issue : List.copyOf(issues.values())) {
                final boolean parentGone =
                        issue.parent() != null && !issues.containsKey(issue.parent());
                final boolean blockerGone =
                        issue.blockedBy().stream().anyMatch(b -> !issues.containsKey(b));
                if (parentGone || blockerGone) {
                    issues.remove(issue.id());
                    faults.add(issue.id() + " depends on an issue left out and is left out too");
                    removed = true;
                }
            }
        }
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

    private static @Nullable String idOf(final ItemView item) {
        final String id = item.fields().get(FieldNames.ID);
        if (id == null) {
            return null;
        }
        final String stripped = id.strip();
        try {
            return stripped.startsWith("ST") && !stripped.contains(".")
                    ? Ids.story(stripped)
                    : Ids.issue(stripped);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isStoryId(final String id) {
        return id.matches("ST[0-9]{1,5}");
    }

    /** Names an item by its repository and number alone, never its title or body. */
    private static String where(final ItemView item) {
        return "Item " + item.repository() + "#" + item.number();
    }
}
