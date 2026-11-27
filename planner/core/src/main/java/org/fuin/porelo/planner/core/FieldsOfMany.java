package org.fuin.porelo.planner.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Story;
import org.jspecify.annotations.Nullable;

/**
 * Judges the fields of many issues and the priorities of many stories set in one call: each item is
 * checked by the rules its call alone keeps ({@link Edits#fields}, {@link Edits#storyIssues},
 * {@link Priorities#check}), an item the rules refuse is dropped with its refusal, and the rest is
 * written in one save. A story's priority set also carries to each of its issues the stories'
 * priority rule would otherwise find breached, found by {@link StoryRules}.
 */
public final class FieldsOfMany {

    /** What became of one item. */
    public enum Outcome {
        /** The item's change stands in the save. */
        SAVED,
        /** The item asked for what stood already. */
        UNCHANGED,
        /** The rules refuse the item. */
        REFUSED
    }

    /**
     * One issue's fields and story asked.
     *
     * @param issue the issue's id
     * @param fields the fields to set, none named for an ask of the story alone
     * @param story the story the issue is to join, or null to leave its story as it is
     */
    public record IssueAsk(String issue, Fields fields, @Nullable String story) {}

    /**
     * One story's priority asked.
     *
     * @param story the story's id
     * @param priority the priority, one of {@link Story#PRIORITIES}
     */
    public record StoryAsk(String story, String priority) {}

    /**
     * The answer for one item.
     *
     * @param item the issue's or the story's id
     * @param outcome what became of it
     * @param raised for a story, the issues raised with its priority in the same save, otherwise
     *     null
     * @param refusal why the item is refused, a {@link Moves.Refused} or a {@link WorkRefusal}, or
     *     null when it is not
     */
    public record Item(
            String item,
            Outcome outcome,
            @Nullable List<String> raised,
            @Nullable RuntimeException refusal) {

        /** Copies the list. */
        public Item {
            raised = raised == null ? null : List.copyOf(raised);
        }
    }

    /** One write of the save, each judged on the versions of its targets. */
    public sealed interface Step permits Members, Priority, SetIssue {

        /**
         * Answers the items the write is judged on.
         *
         * @return their ids
         */
        Set<String> targets();
    }

    /**
     * A story's whole issue list.
     *
     * @param story the story
     * @param issues its issues, in order
     * @param targets the items the write is judged on: the story, each issue it gains and every
     *     other story an issue it gains leaves
     */
    public record Members(String story, List<String> issues, Set<String> targets) implements Step {

        /** Copies the collections. */
        public Members {
            issues = List.copyOf(issues);
            targets = Set.copyOf(targets);
        }
    }

    /**
     * A story's priority.
     *
     * @param story the story
     * @param priority its new priority
     */
    public record Priority(String story, String priority) implements Step {

        @Override
        public Set<String> targets() {
            return Set.of(story);
        }
    }

    /**
     * An issue's fields.
     *
     * @param issue the issue
     * @param fields the fields to write
     */
    public record SetIssue(String issue, Fields fields) implements Step {

        @Override
        public Set<String> targets() {
            return Set.of(issue);
        }
    }

    /**
     * A call judged on one board.
     *
     * @param items the answer for each item: the issues in the order sent, then the stories
     * @param steps the writes the save carries, in the order they are written
     */
    public record Judged(List<Item> items, List<Step> steps) {

        /** Copies the lists. */
        public Judged {
            items = List.copyOf(items);
            steps = List.copyOf(steps);
        }
    }

    private static final String CLASH =
            "Another change of this call writes the same story; send this one apart.";

    private FieldsOfMany() {}

    /**
     * Judges the call: every issue and every story is checked on its own against the board, an
     * issue given a story joins it as {@link Edits#storyIssues} moves it, and a story's priority
     * set raises each of its issues that the stories' priority rule would find breached, unless the
     * call sends a priority for the issue, which then stands or, when it breaches, is refused for
     * that issue. An issue another story holds, over which a task is at work, is refused.
     *
     * @param board the board the call is judged on
     * @param issues the issues' fields and stories, in the order sent
     * @param stories the stories' priorities, in the order sent
     * @param running answers the runs with tasks at work over what a change of membership touches
     * @return what became of each item and what saving the rest writes
     */
    public static Judged judge(
            final Board board,
            final List<IssueAsk> issues,
            final List<StoryAsk> stories,
            final MovePreview.Running running) {
        return new Judging(board, issues, stories, running).judge();
    }

    /** The state of one judging, changed only while it runs. */
    private static final class Judging {

        private final Board board;

        private final List<IssueAsk> issues;

        private final List<StoryAsk> stories;

        private final MovePreview.Running running;

        private final @Nullable RuntimeException[] issueRefusal;

        private final @Nullable RuntimeException[] storyRefusal;

        private final boolean[] fieldsChange;

        private final boolean[] joins;

        private final boolean[] priorityChange;

        /** The asks, by index, that join each story. */
        private final Map<String, List<Integer>> joining = new LinkedHashMap<>();

        /** The priority each story is given. */
        private final Map<String, String> given = new LinkedHashMap<>();

        /** The priority each issue is raised to. */
        private final Map<String, String> raisedTo = new LinkedHashMap<>();

        /** The issues raised with each story's priority. */
        private final Map<String, List<String>> raised = new LinkedHashMap<>();

        Judging(
                final Board board,
                final List<IssueAsk> issues,
                final List<StoryAsk> stories,
                final MovePreview.Running running) {
            this.board = board;
            this.issues = issues;
            this.stories = stories;
            this.running = running;
            this.issueRefusal = new RuntimeException[issues.size()];
            this.storyRefusal = new RuntimeException[stories.size()];
            this.fieldsChange = new boolean[issues.size()];
            this.joins = new boolean[issues.size()];
            this.priorityChange = new boolean[stories.size()];
        }

        Judged judge() {
            twice();
            for (int i = 0; i < issues.size(); i++) {
                judgeIssue(i);
            }
            for (int j = 0; j < stories.size(); j++) {
                judgeStory(j);
            }
            memberships();
            clashes();
            raising();
            return answer();
        }

        private void twice() {
            final Set<String> seen = new HashSet<>();
            final Set<String> again = new HashSet<>();
            for (final IssueAsk ask : issues) {
                if (!seen.add(ask.issue())) {
                    again.add(ask.issue());
                }
            }
            for (int i = 0; i < issues.size(); i++) {
                if (again.contains(issues.get(i).issue())) {
                    issueRefusal[i] =
                            Edits.refused(issues.get(i).issue(), "", "Each issue is named once.");
                }
            }
            seen.clear();
            again.clear();
            for (final StoryAsk ask : stories) {
                if (!seen.add(ask.story())) {
                    again.add(ask.story());
                }
            }
            for (int j = 0; j < stories.size(); j++) {
                if (again.contains(stories.get(j).story())) {
                    storyRefusal[j] =
                            Edits.refused(stories.get(j).story(), "", "Each story is named once.");
                }
            }
        }

        private void judgeIssue(final int i) {
            if (issueRefusal[i] != null) {
                return;
            }
            final IssueAsk ask = issues.get(i);
            try {
                if (ask.fields().none() && ask.story() == null) {
                    throw Edits.refused(ask.issue(), "", "The body names no field to set.");
                }
                if (!ask.fields().none()) {
                    Edits.fields(board, ask.issue(), ask.fields());
                    fieldsChange[i] = differs(board.issue(ask.issue()).orElseThrow(), ask.fields());
                }
                final @Nullable String story = ask.story();
                if (story != null) {
                    joins[i] =
                            Edits.storyIssues(
                                    board, story, with(current(story), List.of(ask.issue())));
                }
            } catch (final Moves.Refused refused) {
                issueRefusal[i] = refused;
            }
        }

        private void judgeStory(final int j) {
            if (storyRefusal[j] != null) {
                return;
            }
            final StoryAsk ask = stories.get(j);
            try {
                priorityChange[j] = !Priorities.check(board, ask.story(), ask.priority()).none();
            } catch (final Moves.Refused refused) {
                storyRefusal[j] = refused;
            } catch (final IllegalArgumentException invalid) {
                storyRefusal[j] =
                        Edits.refused(
                                ask.story(), "priority", String.valueOf(invalid.getMessage()));
            }
        }

        /** Refuses each issue over which a task is at work that another story holds. */
        private void memberships() {
            for (int i = 0; i < issues.size(); i++) {
                final @Nullable String story = issues.get(i).story();
                if (issueRefusal[i] == null && joins[i] && story != null) {
                    joining.computeIfAbsent(story, key -> new ArrayList<>()).add(i);
                }
            }
            for (final Map.Entry<String, List<Integer>> entry : joining.entrySet()) {
                for (final int i : entry.getValue()) {
                    final String issue = issues.get(i).issue();
                    if (heldElsewhere(entry.getKey(), issue)) {
                        final List<MovePreview.Owned> owned =
                                running.over(withSubIssues(issue), Set.of());
                        if (!owned.isEmpty()) {
                            issueRefusal[i] =
                                    WorkRefusal.workRunning(
                                            owned.getFirst().story(), owned.getFirst().tasks());
                        }
                    }
                }
                final List<Integer> live = live(entry.getValue());
                if (live.isEmpty()) {
                    continue;
                }
                try {
                    Edits.storyIssues(board, entry.getKey(), list(entry.getKey(), live));
                } catch (final Moves.Refused refused) {
                    live.forEach(i -> issueRefusal[i] = refused);
                }
            }
        }

        /** Refuses what writes a story an earlier write of the call writes too. */
        private void clashes() {
            final Set<String> written = new HashSet<>();
            for (final Map.Entry<String, List<Integer>> entry : joining.entrySet()) {
                final List<Integer> live = live(entry.getValue());
                if (live.isEmpty()) {
                    continue;
                }
                final Set<String> targets = membersTargets(entry.getKey(), live);
                if (!Collections.disjoint(targets, written)) {
                    live.forEach(
                            i ->
                                    issueRefusal[i] =
                                            Edits.refused(issues.get(i).issue(), "story", CLASH));
                    continue;
                }
                targets.stream().filter(id -> board.story(id).isPresent()).forEach(written::add);
            }
            for (int j = 0; j < stories.size(); j++) {
                if (storyRefusal[j] != null || !priorityChange[j]) {
                    continue;
                }
                final String story = stories.get(j).story();
                if (written.contains(story)) {
                    storyRefusal[j] = Edits.refused(story, "priority", CLASH);
                } else {
                    written.add(story);
                }
            }
        }

        /**
         * Raises the issues the stories' priority rule needs raised, one found at a time on the
         * board as it would stand: a story refused for an issue it cannot raise gives its priority
         * up, an issue whose own priority breaches is refused.
         */
        private void raising() {
            final Map<String, String> sent = new LinkedHashMap<>();
            for (int i = 0; i < issues.size(); i++) {
                final IssueAsk ask = issues.get(i);
                if (issueRefusal[i] == null
                        && ask.fields().named().contains(Fields.Field.PRIORITY)) {
                    sent.put(ask.issue(), Objects.requireNonNull(ask.fields().priority()));
                }
            }
            for (int j = 0; j < stories.size(); j++) {
                if (storyRefusal[j] == null && priorityChange[j]) {
                    given.put(stories.get(j).story(), stories.get(j).priority());
                    raised.put(stories.get(j).story(), new ArrayList<>());
                }
            }
            while (true) {
                final Map<String, String> issuePriorities = new HashMap<>(sent);
                issuePriorities.putAll(raisedTo);
                final StoryRules.@Nullable Breach breach = breach(issuePriorities);
                if (breach == null) {
                    return;
                }
                final String story = breach.item();
                final String to = Objects.requireNonNull(given.get(story));
                final String issue = breach.issues().getFirst();
                if (sent.containsKey(issue)) {
                    // The priority sent for the issue breaches, so it is refused for the issue;
                    // the issue then stands as the board holds it, and the rule raises it as any
                    // other, so no breach is saved unnamed.
                    sent.remove(issue);
                    for (int i = 0; i < issues.size(); i++) {
                        if (issueRefusal[i] == null && issues.get(i).issue().equals(issue)) {
                            issueRefusal[i] =
                                    Edits.refused(
                                            issue,
                                            "priority",
                                            "The priority is higher than the priority its story"
                                                    + " is given in this call.");
                        }
                    }
                    continue;
                }
                try {
                    Edits.fields(board, issue, priority(to));
                    raisedTo.put(issue, to);
                    final List<String> carried =
                            raised.computeIfAbsent(story, key -> new ArrayList<>());
                    if (!carried.contains(issue)) {
                        carried.add(issue);
                    }
                } catch (final Moves.Refused refused) {
                    // The rule cannot be kept, so the story's priority is not set.
                    for (int j = 0; j < stories.size(); j++) {
                        if (stories.get(j).story().equals(story)) {
                            storyRefusal[j] = refused;
                        }
                    }
                    raised.getOrDefault(story, List.of()).forEach(raisedTo::remove);
                    raised.remove(story);
                    given.remove(story);
                }
            }
        }

        /** The first breach of a story given a priority, on the board as it would stand. */
        private StoryRules.@Nullable Breach breach(final Map<String, String> issuePriorities) {
            for (final StoryRules.Breach found : StoryRules.of(hypothetical(issuePriorities))) {
                if (found.rule() == StoryRules.Rule.STORY_PRIORITY_BELOW_ISSUE
                        && given.containsKey(found.item())) {
                    return found;
                }
            }
            return null;
        }

        /** The board as it would stand with the stories' priorities and these issues' set. */
        private Board hypothetical(final Map<String, String> issuePriorities) {
            return new Board(
                    board.revision(),
                    board.workflow(),
                    board.stories().stream()
                            .map(
                                    story -> {
                                        final @Nullable String to = given.get(story.id());
                                        final Story held = members(story);
                                        return to == null ? held : held.withPriority(to);
                                    })
                            .toList(),
                    board.issues().stream()
                            .map(
                                    issue -> {
                                        final @Nullable String to = issuePriorities.get(issue.id());
                                        return to == null ? issue : issue.withPriority(to);
                                    })
                            .toList(),
                    board.work(),
                    board.repositories(),
                    board.faults(),
                    board.owner(),
                    board.unlisted(),
                    board.repositoryWarnings());
        }

        /** The story with the issues of it as the call's joins leave them. */
        private Story members(final Story story) {
            final Map<String, String> joined = new HashMap<>();
            for (final Map.Entry<String, List<Integer>> entry : joining.entrySet()) {
                for (final int i : live(entry.getValue())) {
                    joined.put(issues.get(i).issue(), entry.getKey());
                }
            }
            if (joined.isEmpty()) {
                return story;
            }
            final List<String> after = new ArrayList<>();
            for (final String id : story.issues()) {
                if (!joined.containsKey(id) || story.id().equals(joined.get(id))) {
                    after.add(id);
                }
            }
            joined.forEach(
                    (id, to) -> {
                        if (to.equals(story.id()) && !after.contains(id)) {
                            after.add(id);
                        }
                    });
            return new Story(
                    story.id(),
                    story.title(),
                    story.state(),
                    story.priority(),
                    story.size(),
                    after,
                    story.description(),
                    story.link(),
                    story.issuesWhole(),
                    story.closedAt(),
                    story.warnings(),
                    story.version(),
                    story.labels());
        }

        private Judged answer() {
            final List<Step> steps = new ArrayList<>();
            for (final Map.Entry<String, List<Integer>> entry : joining.entrySet()) {
                final List<Integer> live = live(entry.getValue());
                if (!live.isEmpty()) {
                    steps.add(
                            new Members(
                                    entry.getKey(),
                                    list(entry.getKey(), live),
                                    membersTargets(entry.getKey(), live)));
                }
            }
            for (int j = 0; j < stories.size(); j++) {
                if (storyRefusal[j] == null && priorityChange[j]) {
                    steps.add(new Priority(stories.get(j).story(), stories.get(j).priority()));
                }
            }
            final List<Item> items = new ArrayList<>();
            final Set<String> asked = new HashSet<>();
            for (int i = 0; i < issues.size(); i++) {
                final IssueAsk ask = issues.get(i);
                asked.add(ask.issue());
                if (issueRefusal[i] != null) {
                    items.add(new Item(ask.issue(), Outcome.REFUSED, null, issueRefusal[i]));
                    continue;
                }
                final @Nullable String to = raisedTo.get(ask.issue());
                if (fieldsChange[i] || to != null) {
                    steps.add(new SetIssue(ask.issue(), withRaised(ask.fields(), to)));
                }
                items.add(
                        new Item(
                                ask.issue(),
                                fieldsChange[i] || joins[i] || to != null
                                        ? Outcome.SAVED
                                        : Outcome.UNCHANGED,
                                null,
                                null));
            }
            raisedTo.forEach(
                    (issue, to) -> {
                        if (!liveAsk(issue)) {
                            steps.add(new SetIssue(issue, priority(to)));
                        }
                    });
            for (int j = 0; j < stories.size(); j++) {
                final String story = stories.get(j).story();
                if (storyRefusal[j] != null) {
                    items.add(new Item(story, Outcome.REFUSED, null, storyRefusal[j]));
                } else {
                    items.add(
                            new Item(
                                    story,
                                    priorityChange[j] ? Outcome.SAVED : Outcome.UNCHANGED,
                                    raised.getOrDefault(story, List.of()),
                                    null));
                }
            }
            return new Judged(items, steps);
        }

        /** Whether an issue is asked and not refused, so its own step carries its raise. */
        private boolean liveAsk(final String issue) {
            for (int i = 0; i < issues.size(); i++) {
                if (issueRefusal[i] == null && issues.get(i).issue().equals(issue)) {
                    return true;
                }
            }
            return false;
        }

        private List<Integer> live(final List<Integer> asks) {
            return asks.stream().filter(i -> issueRefusal[i] == null).toList();
        }

        private List<String> current(final String story) {
            return board.story(story).map(Story::issues).orElse(List.of());
        }

        /** The story's issues with each live ask's issue after them. */
        private List<String> list(final String story, final List<Integer> live) {
            return with(current(story), live.stream().map(i -> issues.get(i).issue()).toList());
        }

        private static List<String> with(final List<String> list, final List<String> more) {
            final List<String> all = new ArrayList<>(list);
            more.stream().filter(id -> !all.contains(id)).forEach(all::add);
            return all;
        }

        private Set<String> membersTargets(final String story, final List<Integer> live) {
            return Targets.of(board, new Operation.SetStoryIssues(story, list(story, live)));
        }

        private boolean heldElsewhere(final String story, final String issue) {
            return board.stories().stream()
                    .anyMatch(other -> !other.id().equals(story) && other.issues().contains(issue));
        }

        /** The issue and its sub-issues, at any depth. */
        private Set<String> withSubIssues(final String issue) {
            final Set<String> all = new LinkedHashSet<>(Set.of(issue));
            final ArrayDeque<String> pending = new ArrayDeque<>(all);
            while (!pending.isEmpty()) {
                final String parent = pending.remove();
                for (final Issue child : board.issues()) {
                    if (parent.equals(child.parent()) && all.add(child.id())) {
                        pending.add(child.id());
                    }
                }
            }
            return all;
        }
    }

    private static Fields priority(final String priority) {
        return new Fields(EnumSet.of(Fields.Field.PRIORITY), null, null, priority, null);
    }

    /** The fields with the priority an issue is raised to, as the issue's own are named. */
    private static Fields withRaised(final Fields fields, final @Nullable String to) {
        if (to == null) {
            return fields;
        }
        final Set<Fields.Field> named = EnumSet.of(Fields.Field.PRIORITY);
        named.addAll(fields.named());
        return new Fields(named, fields.size(), fields.estimateMinutes(), to, fields.rank());
    }

    /** Whether naming the fields changes the issue. */
    private static boolean differs(final Issue issue, final Fields fields) {
        for (final Fields.Field field : fields.named()) {
            switch (field) {
                case SIZE -> {
                    if (!Objects.equals(issue.size(), fields.size())) {
                        return true;
                    }
                }
                case PRIORITY -> {
                    if (!Objects.equals(issue.priority(), fields.priority())) {
                        return true;
                    }
                }
                default -> {
                    return true;
                }
            }
        }
        return false;
    }
}
