package org.fuin.porelo.planner.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/** Plans the writes and scoped tasks that follow an accepted end, without performing either. */
public final class NextStep {

    private static final String FINDINGS =
            "\n\nEnd with exactly one final Findings: block, through the end of the answer."
                    + "\nUse an empty block for no findings, otherwise consecutive numbered lines:"
                    + "\n<n>. <blocking|note> | <relative file or -> | <line or -> | <text>";

    private NextStep() {}

    /** The immutable scope and rule bound to an offer. */
    public record Binding(
            String story,
            String repository,
            List<String> issues,
            String role,
            String state,
            String mode,
            WorkRule rule) {
        /** Refuses unsafe identities and keeps ownership immutable. */
        public Binding {
            Ids.story(story);
            Ids.name(repository);
            issues = List.copyOf(issues);
            issues.forEach(Ids::issue);
        }
    }

    /** The latest reading and attention state of an issue. */
    public record Progress(
            ReadingCycle.Rounds rounds,
            List<WorkEnds.Finding> findings,
            boolean needsYou,
            String reason,
            String answer) {
        /** Keeps findings immutable across record replacements. */
        public Progress {
            findings = List.copyOf(findings);
        }

        /** Starts counting only when the record has an accepted allowance. */
        public static Progress start(final int allowance) {
            return new Progress(ReadingCycle.Rounds.start(allowance), List.of(), false, "NONE", "");
        }

        /** Keeps the reading of an issue a person now works, which needs no agent and no answer. */
        public Progress manual() {
            return new Progress(rounds, findings, false, "MANUAL", answer);
        }

        /** Retains the reading while making a failure visible. */
        public Progress attention(final String why) {
            return new Progress(rounds, findings, true, why, answer);
        }
    }

    /**
     * How a task runs: its mode and its agent with that agent's model.
     *
     * @param mode the mode
     * @param agent the agent
     * @param model the agent's model
     */
    public record Settings(String mode, String agent, String model) {

        /**
         * The settings a task of the rule runs with.
         *
         * @param rule the rule the task works under
         * @param chosen the person's choice, or null for the rule's first mode and first model
         * @return the chosen settings, or the rule's first ones
         * @throws WorkRefusal {@code InvalidSettings} when the choice is not one of the rule's
         *     modes together with one of its agents and that agent's model
         */
        public static Settings of(final WorkRule rule, final @Nullable Settings chosen) {
            if (chosen == null) {
                final Map.Entry<String, String> first = rule.models().entrySet().iterator().next();
                return new Settings(rule.modes().getFirst(), first.getKey(), first.getValue());
            }
            if (!rule.modes().contains(chosen.mode())
                    || !chosen.model().equals(rule.models().get(chosen.agent()))) {
                throw WorkRefusal.invalidSettings();
            }
            return chosen;
        }
    }

    /** An offer's Start arguments together with the working state it will enter. */
    public record Planned(WorkOrder.Task task, String state, WorkRule rule) {}

    /** A complete next step; deferred parents wait for their participating children. */
    public record Result(
            Writes writes,
            List<Planned> tasks,
            Map<String, Progress> progress,
            List<String> deferred) {
        /** Copies collections so planning cannot mutate a saved run. */
        public Result {
            tasks = List.copyOf(tasks);
            progress = Map.copyOf(progress);
            deferred = List.copyOf(deferred);
        }
    }

    /** Checks that every issue still belongs to the offer's story and repository. */
    public static boolean owns(final Board board, final Binding binding) {
        final Story story = board.story(binding.story()).orElse(null);
        if (story == null) {
            return false;
        }
        for (final String id : binding.issues()) {
            final Issue issue = board.issue(id).orElse(null);
            if (issue == null
                    || !binding.repository().equals(issue.repository())
                    || !(story.issues().contains(id)
                            || issue.parent() != null && story.issues().contains(issue.parent()))) {
                return false;
            }
        }
        return !"READER".equals(binding.role()) || binding.issues().size() == 1;
    }

    /** Plans entry to an offered state; only STARTED calls this. */
    public static Writes started(
            final Board board, final Binding binding, final Moves.StoryState derive) {
        if (!owns(board, binding)) {
            throw WorkRefusal.notAllowed(
                    "", "", "The offered issues no longer belong to this work.");
        }
        final Map<String, String> targets = new LinkedHashMap<>();
        for (final String id : binding.issues()) {
            final Issue issue = board.issue(id).orElseThrow();
            final String from = issue.state();
            if (issue.worker() == Worker.MANUAL) {
                throw WorkRefusal.notAllowed(
                        from,
                        binding.state(),
                        "A person works " + id + ", so no agent's task starts on it.");
            }
            if (!from.equals(binding.state()) && !binding.rule().from().contains(from)) {
                throw stateRefused(
                        id, from, binding.state(), "The rule does not allow this issue's state.");
            }
            targets.put(id, binding.state());
        }
        final Story story = board.story(binding.story()).orElseThrow();
        if (binding.issues().isEmpty()
                && !story.state().equals(binding.state())
                && !binding.rule().from().contains(story.state())) {
            throw stateRefused(
                    story.id(),
                    story.state(),
                    binding.state(),
                    "The rule does not allow this story's state.");
        }
        return writes(
                board,
                binding.story(),
                targets,
                binding.issues().isEmpty() ? binding.state() : null,
                derive);
    }

    /** Judges continuation without allowing a bad next rule to refuse the accepted end. */
    public static Result after(
            final Board board,
            final Binding binding,
            final WorkEnds.Judgment judgment,
            final Map<String, Progress> previous,
            final String comment,
            final Moves.StoryState derive) {
        return after(board, binding, judgment, previous, comment, derive, true, "", null);
    }

    /** Resumes a deferred transition from its saved findings without judging or counting again. */
    public static Result resume(
            final Board board,
            final Binding binding,
            final Map<String, Progress> previous,
            final String comment,
            final Moves.StoryState derive) {
        return resume(board, binding, previous, comment, derive, false);
    }

    /** Retains an explicit review choice while a parent waits for its children. */
    public static Result resume(
            final Board board,
            final Binding binding,
            final Map<String, Progress> previous,
            final String comment,
            final Moves.StoryState derive,
            final boolean reviewAnyway) {
        return resume(board, binding, previous, comment, derive, reviewAnyway, "", null);
    }

    /**
     * Retains the operator's rework words and settings while a parent waits for its children; a
     * reading reaches this only by the operator's choice, so blocking findings rework at once.
     */
    public static Result resume(
            final Board board,
            final Binding binding,
            final Map<String, Progress> previous,
            final String comment,
            final Moves.StoryState derive,
            final boolean reviewAnyway,
            final String operatorWords,
            final @Nullable Settings rework) {
        final WorkEnds.Judgment judgment =
                binding.role().equals("READER")
                        ? new WorkEnds.Read(
                                reviewAnyway
                                        ? List.of()
                                        : progress(previous, binding.issues().getFirst())
                                                .findings())
                        : new WorkEnds.Done();
        return after(
                board, binding, judgment, previous, comment, derive, false, operatorWords, rework);
    }

    private static Result after(
            final Board board,
            final Binding binding,
            final WorkEnds.Judgment judgment,
            final Map<String, Progress> previous,
            final String comment,
            final Moves.StoryState derive,
            final boolean countReading,
            final String operatorWords,
            final @Nullable Settings rework) {
        final Map<String, Progress> progress = new LinkedHashMap<>(previous);
        binding.issues().forEach(id -> progress.putIfAbsent(id, Progress.start(2)));
        if (!owns(board, binding)) {
            return attention(binding, progress, "NEXT_STEP_UNAVAILABLE");
        }
        if (judgment instanceof WorkEnds.NeedsOperator needs) {
            return attention(binding, progress, needs.reason().name());
        }
        final Map<String, String> targets = new LinkedHashMap<>();
        final List<Planned> tasks = new ArrayList<>();
        final List<String> deferred = new ArrayList<>();
        for (final String id : binding.issues()) {
            final Issue issue = board.issue(id).orElseThrow();
            if (!issue.state().equals(binding.state())) {
                progress.put(id, progress(progress, id).attention("NEXT_STEP_UNAVAILABLE"));
                continue;
            }
            String target = successState(binding.rule());
            String words = "";
            Settings settings = null;
            if (judgment instanceof WorkEnds.Read read) {
                final Progress before = progress(progress, id);
                if (countReading && before.rounds().rounds() == Integer.MAX_VALUE
                        || before.rounds().stopped()) {
                    progress.put(id, before.attention("NEXT_STEP_UNAVAILABLE"));
                    continue;
                }
                final ReadingCycle.Counted counted =
                        countReading
                                ? ReadingCycle.count(before.rounds(), read)
                                : new ReadingCycle.Counted(
                                        before.rounds(),
                                        read.blocking()
                                                ? ReadingCycle.Next.REWORK
                                                : ReadingCycle.Next.REVIEW);
                final boolean stopped = counted.next() == ReadingCycle.Next.STOP;
                progress.put(
                        id,
                        new Progress(
                                counted.rounds(),
                                countReading ? read.findings() : before.findings(),
                                stopped,
                                stopped ? ReadingCycle.stopReason(counted.rounds()) : "NONE",
                                before.answer()));
                if (stopped) {
                    continue;
                }
                if (read.blocking()) {
                    target = binding.rule().blocked();
                    words =
                            findingText(read.findings())
                                    + (operatorWords.isEmpty() ? "" : "\n" + operatorWords);
                    settings = rework;
                    if (target == null) {
                        progress.put(id, progress(progress, id).attention("NEXT_STEP_UNAVAILABLE"));
                        continue;
                    }
                } else {
                    target = binding.rule().done();
                }
            }
            if (!board.workflow().states().contains(target)) {
                progress.put(id, progress(progress, id).attention("NEXT_STEP_UNAVAILABLE"));
                continue;
            }
            final WorkRule next =
                    WorkRules.check(board.workflow(), board.work()).rules().get(target);
            final boolean working =
                    board.work().rules().containsKey(target)
                            || target.equals(binding.rule().then())
                            || target.equals(binding.rule().blocked());
            if (working && (next == null || !next.from().contains(issue.state()))) {
                progress.put(id, progress(progress, id).attention("NEXT_STEP_UNAVAILABLE"));
                continue;
            }
            if (waitsForChildren(board, issue, target)) {
                deferred.add(id);
                continue;
            }
            targets.put(id, target);
            if (issue.worker() == Worker.MANUAL) {
                // The ended task's state stands; a person moves the issue on from there.
                progress.put(id, progress(progress, id).manual());
                continue;
            }
            if (next != null) {
                try {
                    tasks.add(
                            task(
                                    board,
                                    binding.story(),
                                    issue,
                                    target,
                                    next,
                                    settings,
                                    comment,
                                    words,
                                    judgment instanceof WorkEnds.Read ? "BUILDER" : "READER"));
                } catch (final WorkRefusal | IllegalArgumentException cannotBuild) {
                    progress.put(id, progress(progress, id).attention("NEXT_STEP_UNAVAILABLE"));
                }
            }
        }
        if (binding.issues().isEmpty()) {
            final String target = successState(binding.rule());
            return finish(board, binding, targets, target, derive, tasks, progress, deferred);
        }
        return finish(board, binding, targets, null, derive, tasks, progress, deferred);
    }

    private static Result finish(
            final Board board,
            final Binding binding,
            final Map<String, String> targets,
            final @Nullable String storyState,
            final Moves.StoryState derive,
            final List<Planned> tasks,
            final Map<String, Progress> progress,
            final List<String> deferred) {
        try {
            return new Result(
                    writes(board, binding.story(), targets, storyState, derive),
                    tasks,
                    progress,
                    deferred);
        } catch (final Moves.Refused
                | WorkRefusal
                | IllegalArgumentException
                | UnsupportedOperationException cannotWrite) {
            return attention(binding, progress, "NEXT_STEP_UNAVAILABLE");
        }
    }

    /**
     * Builds a single-issue reading or rework with the person's settings, or with the rule's first
     * mode and first model when the planner starts it by itself.
     */
    public static Planned task(
            final Board board,
            final String storyId,
            final Issue issue,
            final String state,
            final WorkRule rule,
            final @Nullable Settings chosen,
            final String comment,
            final String words,
            final String role) {
        final Settings settings = Settings.of(rule, chosen);
        final Story story =
                board.story(storyId).orElseThrow(() -> WorkRefusal.noSuchStory(storyId));
        if (settings.agent().length() > 128
                || settings.model().length() > 128
                || settings.mode().length() > 128) {
            throw WorkRefusal.invalidSettings();
        }
        String prompt =
                WorkOrder.prompt(
                        rule,
                        story,
                        WorkOrder.issueText(board, story, state, List.of(issue.id())),
                        "",
                        fence(comment));
        if (!words.isEmpty()) {
            prompt += "\n\nReading and operator words:\n" + fence(words);
        }
        if (role.equals("READER")) {
            prompt += FINDINGS;
        }
        if (prompt.getBytes(StandardCharsets.UTF_8).length > WorkEnds.MOST_ANSWER) {
            throw WorkRefusal.invalidSettings();
        }
        return new Planned(
                new WorkOrder.Task(
                        issue.repository(),
                        List.of(issue.id()),
                        role,
                        settings.agent(),
                        settings.model(),
                        settings.mode(),
                        prompt),
                state,
                rule);
    }

    /** Fences untrusted words with a delimiter they cannot close. */
    public static String fence(final String words) {
        int longest = 0;
        int run = 0;
        for (int index = 0; index < words.length(); index++) {
            run = words.charAt(index) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        final String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + "\n" + words + "\n" + fence;
    }

    /** Formats findings only as fenced prompt data, never as another issue's scope. */
    public static String findingText(final List<WorkEnds.Finding> findings) {
        return String.join(
                "\n",
                findings.stream()
                        .map(
                                f ->
                                        f.number()
                                                + ". "
                                                + f.severity()
                                                + " | "
                                                + (f.file() == null ? "-" : f.file())
                                                + " | "
                                                + (f.line() == null ? "-" : f.line())
                                                + " | "
                                                + f.text())
                        .toList());
    }

    /** Holds a parent until its open children have participated in the same transition. */
    public static boolean waitsForChildren(
            final Board board, final Issue issue, final String target) {
        final Map<String, WorkRule> rules = WorkRules.check(board.workflow(), board.work()).rules();
        final WorkRule source = rules.get(issue.state());
        for (final Issue child : board.issues()) {
            if (!issue.id().equals(child.parent())
                    || !issue.repository().equals(child.repository())
                    || board.workflow().closed().contains(child.state())) {
                continue;
            }
            if (board.workflow().closed().contains(target)) {
                return true;
            }
            if (target.equals(child.state())
                    || source != null && source.done().equals(child.state())) {
                continue;
            }
            if (child.state().equals(issue.state())
                    || !reaches(rules, target, child.state(), issue.state())) {
                return true;
            }
        }
        return false;
    }

    private static boolean reaches(
            final Map<String, WorkRule> rules,
            final String target,
            final String child,
            final String source) {
        final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        pending.add(target);
        while (!pending.isEmpty()) {
            final String state = pending.removeFirst();
            if (state.equals(source) || !seen.add(state)) {
                continue;
            }
            if (state.equals(child)) {
                return true;
            }
            final WorkRule rule = rules.get(state);
            if (rule != null) {
                pending.add(successState(rule));
                final String blocked = rule.blocked();
                if (blocked != null) {
                    pending.add(blocked);
                }
            }
        }
        return false;
    }

    private static Progress progress(final Map<String, Progress> progress, final String id) {
        return java.util.Objects.requireNonNull(
                progress.get(id), "Owned issues have recorded progress");
    }

    private static String successState(final WorkRule rule) {
        final String then = rule.then();
        return then == null ? rule.done() : then;
    }

    /** Computes only the bound story's head, removing an agreeing override. */
    public static Writes writes(
            final Board board,
            final String storyId,
            final Map<String, String> targets,
            final @Nullable String emptyStoryState,
            final Moves.StoryState derive) {
        final Map<String, String> changed = new LinkedHashMap<>(targets);
        changed.entrySet()
                .removeIf(
                        entry ->
                                board.issue(entry.getKey())
                                        .orElseThrow()
                                        .state()
                                        .equals(entry.getValue()));
        final Story story = board.story(storyId).orElseThrow();
        final Map<String, String> stories = new LinkedHashMap<>();
        final List<String> unstated = new ArrayList<>();
        if (story.issues().isEmpty()
                && emptyStoryState != null
                && !story.state().equals(emptyStoryState)) {
            stories.put(storyId, emptyStoryState);
        } else if (story.stateGiven() && !story.issues().isEmpty()) {
            final String derived =
                    derive.of(
                            story.issues().stream()
                                    .map(
                                            id ->
                                                    targets.getOrDefault(
                                                            id,
                                                            board.issue(id)
                                                                    .map(Issue::state)
                                                                    .orElse("")))
                                    .toList());
            if (derived != null
                    && (derived.equals(story.state()) || targets.containsValue(derived))) {
                unstated.add(storyId);
            }
        }
        for (final Issue child : board.issues()) {
            final String parent = child.parent();
            if (parent != null
                    && board.issue(parent).isPresent()
                    && board.workflow()
                            .closed()
                            .contains(
                                    targets.getOrDefault(
                                            parent, board.issue(parent).orElseThrow().state()))
                    && !board.workflow()
                            .closed()
                            .contains(targets.getOrDefault(child.id(), child.state()))) {
                throw stateRefused(
                        parent,
                        board.issue(parent).orElseThrow().state(),
                        targets.getOrDefault(parent, board.issue(parent).orElseThrow().state()),
                        "A parent cannot close before its sub-issues.");
            }
        }
        return new Writes(changed, stories, unstated);
    }

    private static Result attention(
            final Binding binding, final Map<String, Progress> progress, final String reason) {
        binding.issues().forEach(id -> progress.put(id, progress(progress, id).attention(reason)));
        return new Result(
                new Writes(Map.of(), Map.of(), List.of()), List.of(), progress, List.of());
    }

    private static Moves.Refused stateRefused(
            final String item, final String from, final String to, final String reason) {
        return new Moves.Refused(
                "MoveNotAllowed",
                reason,
                Map.of("item", item, "from", from, "to", to, "reason", reason));
    }
}
