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

        /**
         * Whether the reading stopped because its findings could not be read; only that stop keeps
         * no findings, so it stays known when a failed choice has replaced its reason.
         *
         * @return true for a stopped reading without findings
         */
        public boolean unreadable() {
            return rounds.stopped() && findings.isEmpty();
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
         * @param choice the rule's choice for the task's risk
         * @param chosen the person's choice, or null for the rule's first mode and the risk's agent
         *     and model
         * @return the chosen settings, or the proposed ones
         * @throws WorkRefusal {@code InvalidSettings} when the choice is not one of the rule's
         *     modes together with an agent and model the risk offers
         */
        public static Settings of(
                final WorkRule rule, final Choice choice, final @Nullable Settings chosen) {
            if (chosen == null) {
                return new Settings(rule.modes().getFirst(), choice.agent(), choice.model());
            }
            if (!rule.modes().contains(chosen.mode())
                    || !choice.offers(chosen.agent(), chosen.model())) {
                throw WorkRefusal.invalidSettings();
            }
            return chosen;
        }
    }

    /**
     * Where a build's work landed, copied unchanged from Porelo's task row; the planner never
     * derives one of these names.
     *
     * @param branch the branch the work landed on
     * @param ref the ref Porelo serves it under
     * @param remote the remote-tracking name a reader sees after a plain {@code git fetch}
     * @param commit the landed commit
     */
    public record Landing(String branch, String ref, String remote, String commit) {}

    /**
     * The build whose work a reader reads: its Porelo task, and where Porelo reports its work
     * landed, empty while that work still waits at the gate.
     *
     * @param task the Porelo task that built the work, empty when none is known
     * @param landed where the work landed, empty while it has not landed
     */
    public record Build(String task, List<Landing> landed) {
        /** Keeps the landings as they were reported. */
        public Build {
            landed = List.copyOf(landed);
        }
    }

    /**
     * An offer's Start arguments together with the working state it will enter and where its agent,
     * model and effort came from.
     */
    public record Planned(WorkOrder.Task task, String state, WorkRule rule, String basis) {}

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

    /**
     * Judges continuation without allowing a bad next rule to refuse the accepted end; {@code
     * ended} is the build that ended, whose work a following reader is told to read. A reader of a
     * build whose work has not landed yet is not offered here, but by {@link #landed}.
     */
    public static Result after(
            final Board board,
            final Binding binding,
            final WorkEnds.Judgment judgment,
            final Map<String, Progress> previous,
            final String comment,
            final Moves.StoryState derive,
            final Build ended) {
        return after(board, binding, judgment, previous, comment, derive, true, "", null, ended);
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
            final @Nullable Settings rework,
            final Build ended) {
        final WorkEnds.Judgment judgment =
                binding.role().equals("READER")
                        ? new WorkEnds.Read(
                                reviewAnyway
                                        ? List.of()
                                        : progress(previous, binding.issues().getFirst())
                                                .findings())
                        : new WorkEnds.Done();
        return after(
                board,
                binding,
                judgment,
                previous,
                comment,
                derive,
                false,
                operatorWords,
                rework,
                ended);
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
            final @Nullable Settings rework,
            final Build ended) {
        final Map<String, Progress> progress = new LinkedHashMap<>(previous);
        binding.issues().forEach(id -> progress.putIfAbsent(id, Progress.start(2)));
        if (!owns(board, binding)) {
            return attention(binding, progress, "NEXT_STEP_UNAVAILABLE");
        }
        if (judgment instanceof WorkEnds.NeedsOperator needs) {
            return stopsReading(binding, needs.reason())
                    ? unreadable(board, binding, progress, needs.reason())
                    : attention(binding, progress, needs.reason().name());
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
            final String role = judgment instanceof WorkEnds.Read ? "BUILDER" : "READER";
            if (next != null && !awaitsLanding(role, ended)) {
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
                                    role,
                                    ended));
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

    /**
     * Offers the readers of a build whose work has now landed where {@code build} names, for each
     * of its issues that stands in the state its end moved it to; an issue moved on, or still
     * waiting for its children, gets none here.
     *
     * @param board the board as it stands
     * @param binding the build's binding
     * @param previous the run's progress
     * @param comment the run's comment
     * @param build the build with where its work landed
     * @return the readers, writing nothing
     */
    public static Result landed(
            final Board board,
            final Binding binding,
            final Map<String, Progress> previous,
            final String comment,
            final Build build) {
        final Map<String, Progress> progress = new LinkedHashMap<>(previous);
        final List<Planned> tasks = new ArrayList<>();
        final String target = successState(binding.rule());
        final WorkRule next = WorkRules.check(board.workflow(), board.work()).rules().get(target);
        final boolean owned = owns(board, binding);
        // As at a build's end, a reading the build's state leads to must have a sound rule.
        final boolean reading =
                board.work().rules().containsKey(target) || target.equals(binding.rule().then());
        for (final String id : binding.issues()) {
            final Issue issue = board.issue(id).orElse(null);
            if (issue != null
                    && (issue.worker() == Worker.MANUAL
                            || !issue.state().equals(target)
                            || next == null && !reading)) {
                // A person works it, moved it on, or its children hold it; none waits on a reader.
                continue;
            }
            try {
                if (issue == null || !owned || next == null) {
                    throw new IllegalArgumentException("No reader can follow the landed build");
                }
                tasks.add(
                        task(
                                board,
                                binding.story(),
                                issue,
                                target,
                                next,
                                null,
                                comment,
                                "",
                                "READER",
                                build));
            } catch (final WorkRefusal | IllegalArgumentException cannotBuild) {
                progress.computeIfPresent(id, (key, p) -> p.attention("NEXT_STEP_UNAVAILABLE"));
            }
        }
        return new Result(new Writes(Map.of(), Map.of(), List.of()), tasks, progress, List.of());
    }

    /** A reader of a known build whose work still waits at the gate is offered on its landing. */
    private static boolean awaitsLanding(final String role, final Build build) {
        return role.equals("READER") && !build.task().isEmpty() && build.landed().isEmpty();
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
     * mode and the agent and model it names for the issue's risk when the planner starts it by
     * itself, always with the effort it names for that risk; a reading's order names the Porelo
     * task {@code built} whose work it reads and where that work landed, and its offer reads that
     * task.
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
            final String role,
            final Build built) {
        return task(board, storyId, issue, state, rule, chosen, comment, words, role, built, null);
    }

    /**
     * The effort a person chose for a task started by hand.
     *
     * @param effort the effort chosen, or null for none
     */
    public record Chosen(@Nullable String effort) {}

    /**
     * Builds a single-issue task as {@link #task(Board, String, Issue, String, WorkRule, Settings,
     * String, String, String, Build)} does, with the effort a person chose when {@code effort} is
     * given, which is then the one the task is said to use.
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
            final String role,
            final Build built,
            final @Nullable Chosen effort) {
        final Choice choice = Choice.of(state, rule, List.of(issue));
        final Settings settings = Settings.of(rule, choice, chosen);
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
                        role.equals("READER") ? builtBy(built) : "",
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
                        effort == null ? choice.effort() : effort.effort(),
                        settings.mode(),
                        prompt,
                        role.equals("READER") ? List.of(built.task()) : List.of(),
                        choice.basis(),
                        effort == null ? null : effort.effort()),
                state,
                rule,
                choice.basis());
    }

    /**
     * Names the task whose work a reader reads and each commit Porelo reported it landed as, with
     * the remote-tracking name a plain fetch gives it, so the reader neither takes its order for a
     * build nor reads other work; never a made-up name.
     */
    static String builtBy(final Build built) {
        if (built.task().isEmpty() || built.landed().isEmpty()) {
            throw new IllegalArgumentException("No landed build to read");
        }
        return String.join(
                "; ",
                built.landed().stream()
                        .map(
                                landing ->
                                        "the work of Porelo task "
                                                + built.task()
                                                + ": commit "
                                                + landing.commit()
                                                + " on "
                                                + landing.remote()
                                                + ", after `git fetch origin`")
                        .toList());
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

    /**
     * Whether an end stops its reading as one whose findings could not be read: findings that could
     * not be read, or a reader that failed or said its work is blocked, which leaves no reading to
     * count, so a Start never takes the issue on as if it had none.
     */
    private static boolean stopsReading(final Binding binding, final WorkEnds.Reason reason) {
        return reason == WorkEnds.Reason.INVALID_FINDINGS
                || binding.role().equals("READER")
                        && (reason == WorkEnds.Reason.FAILED || reason == WorkEnds.Reason.BLOCKED);
    }

    /**
     * Stops a reading whose findings could not be read, counting no round and keeping no findings,
     * so the person can have the same build read again or take it to Review; an issue moved on
     * meanwhile only asks the person, as at any end, since no choice could act on its stop.
     */
    private static Result unreadable(
            final Board board,
            final Binding binding,
            final Map<String, Progress> progress,
            final WorkEnds.Reason reason) {
        binding.issues()
                .forEach(
                        id -> {
                            final Progress before = progress(progress, id);
                            if (!board.issue(id)
                                    .map(Issue::state)
                                    .orElse("")
                                    .equals(binding.state())) {
                                progress.put(id, before.attention("NEXT_STEP_UNAVAILABLE"));
                                return;
                            }
                            progress.put(
                                    id,
                                    new Progress(
                                            new ReadingCycle.Rounds(
                                                    before.rounds().rounds(),
                                                    before.rounds().roundsMost(),
                                                    true),
                                            List.of(),
                                            true,
                                            reason.name(),
                                            before.answer()));
                        });
        return new Result(
                new Writes(Map.of(), Map.of(), List.of()), List.of(), progress, List.of());
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
