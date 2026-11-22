package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.jspecify.annotations.Nullable;

/** The immutable choices proposed from one board, with no runtime or store changes. */
public record WorkProposal(
        List<String> modes,
        List<Repository> repositories,
        String coordinator,
        String comment,
        int readingRounds,
        List<IssueRounds> issueReadingRounds,
        Revision revision,
        List<String> manual,
        List<Held> held) {

    /** Copies every collection so recomputation cannot be changed by a caller. */
    public WorkProposal {
        modes = List.copyOf(modes);
        repositories = List.copyOf(repositories);
        issueReadingRounds = List.copyOf(issueReadingRounds);
        manual = List.copyOf(manual);
        held = List.copyOf(held);
    }

    /** A proposal that holds no issue back. */
    public WorkProposal(
            final List<String> modes,
            final List<Repository> repositories,
            final String coordinator,
            final String comment,
            final int readingRounds,
            final List<IssueRounds> issueReadingRounds,
            final Revision revision,
            final List<String> manual) {
        this(
                modes,
                repositories,
                coordinator,
                comment,
                readingRounds,
                issueReadingRounds,
                revision,
                manual,
                List.of());
    }

    /**
     * An open issue of the story that no task of the proposal names, since the board holds it back.
     *
     * @param issue the issue's id
     * @param reason why, in the words of its warnings or of its id named twice
     */
    public record Held(String issue, String reason) {}

    /**
     * The proposed main participant; only its offered agent/model pair is a choice. The effort,
     * where the choice came from and the offered agents are the proposal's; a decision does not
     * choose them.
     *
     * @param name the participant's name
     * @param role MAIN for work that builds, READER for a reading started by hand
     * @param agent the proposed or chosen agent
     * @param model the agent's model
     * @param brief the rule's order for the task
     * @param effort the effort, or null for none
     * @param basis where the choice came from in words, empty when not known
     * @param offered every agent with its model the rule offers, in the rule's order, the proposed
     *     one first; empty where the main is a decision's
     */
    public record Main(
            String name,
            String role,
            String agent,
            String model,
            String brief,
            @Nullable String effort,
            String basis,
            List<Offer> offered) {

        /** Copies the offered agents in their order. */
        public Main {
            offered = List.copyOf(offered);
        }

        /** A main with the proposal's effort and basis but no offered agents. */
        public Main(
                final String name,
                final String role,
                final String agent,
                final String model,
                final String brief,
                final @Nullable String effort,
                final String basis) {
            this(name, role, agent, model, brief, effort, basis, List.of());
        }

        /** A main as a decision names it, without the proposal's effort and basis. */
        public Main(
                final String name,
                final String role,
                final String agent,
                final String model,
                final String brief) {
            this(name, role, agent, model, brief, null, "");
        }
    }

    /**
     * An agent the rule offers a task, with the model it offers it.
     *
     * @param agent the agent
     * @param model the agent's offered model
     */
    public record Offer(String agent, String model) {

        /** The offers of a choice, in the rule's order. */
        static List<Offer> of(final Choice choice) {
            return choice.offered().entrySet().stream()
                    .map(e -> new Offer(e.getKey(), e.getValue()))
                    .toList();
        }
    }

    /**
     * A stable sub-issue identity with an explicit participant choice. The effort and where the
     * choice came from are the proposal's; a decision does not choose them.
     */
    public record Assignment(
            String id,
            String issue,
            String name,
            String kind,
            String agent,
            String model,
            String workingState,
            String brief,
            String choice,
            @Nullable String effort,
            String basis) {

        /** An assignment as a decision names it, without the proposal's effort and basis. */
        public Assignment(
                final String id,
                final String issue,
                final String name,
                final String kind,
                final String agent,
                final String model,
                final String workingState,
                final String brief,
                final String choice) {
            this(id, issue, name, kind, agent, model, workingState, brief, choice, null, "");
        }
    }

    /** The issues, main and helpers of one proposed repository group. */
    public record Repository(
            String repository, List<String> issues, Main main, List<Assignment> subAgents) {
        /** Copies both lists to preserve proposal identity. */
        public Repository {
            issues = List.copyOf(issues);
            subAgents = List.copyOf(subAgents);
        }
    }

    /** The maximum reading rounds chosen for one issue. */
    public record IssueRounds(String issue, int rounds) {}

    /** Read-only input from the run; a workflow state is never a running-work record. */
    @FunctionalInterface
    public interface RunningWork {
        /** Answers the names of recorded active tasks of the story. */
        List<String> tasks(String story);

        /** Answers whether the run has an active project coordinator. */
        default boolean coordinatorActive() {
            return false;
        }

        /**
         * Answers the story's issues a Start may work again in the working state they stand in:
         * those its run raised with the reason a row's Start passes over.
         */
        default Set<String> again(final String story) {
            return Set.of();
        }
    }

    /** Proposes work without inventing running records. */
    public static WorkProposal propose(final Board board, final String id, final String to) {
        return propose(board, id, to, story -> List.of());
    }

    /** Proposes work using only the board and explicitly supplied coordinator knowledge. */
    public static WorkProposal propose(
            final Board board, final String id, final String to, final RunningWork running) {
        return propose(board, id, to, running, null);
    }

    /**
     * Proposes work for the story's issues {@code picked}, or, when null, for its least advanced
     * open issues the rule takes from.
     *
     * @param board the board
     * @param id the story
     * @param to the working state
     * @param running the run's activity
     * @param picked the story's issues named for the work, or null for the story's own
     * @return the proposal
     */
    public static WorkProposal propose(
            final Board board,
            final String id,
            final String to,
            final RunningWork running,
            final @Nullable List<String> picked) {
        final Story story = story(board, id);
        // A story held back offers none of its issues.
        WorkRefusal.unlessFree(board, story.id(), story.state(), to);
        final WorkRule rule = picked == null ? rule(board, story, to) : target(board, story, to);
        bounded(story, to, rule.order());
        final List<Held> held = new ArrayList<>();
        final List<Issue> open = free(board, issues(board, story, to, held), held);
        if (picked != null) {
            for (final Held each : held) {
                if (picked.contains(each.issue())) {
                    throw WorkRefusal.notAllowed(story.state(), to, each.reason());
                }
            }
        }
        final List<Issue> issues =
                picked == null
                        ? leastAdvanced(board, rule, agents(open))
                        : named(story, to, rule, open, picked, running.again(id));
        final List<String> manual =
                picked == null
                        ? open.stream()
                                .filter(i -> i.worker() == Worker.MANUAL)
                                .map(Issue::id)
                                .toList()
                        : List.of();
        final boolean reading = rule.blocked() != null;
        if (issues.isEmpty() && !agents(open).isEmpty()) {
            throw WorkRefusal.notAllowed(story.state(), to, standing(agents(open), to, rule));
        }
        if (issues.isEmpty() && !manual.isEmpty()) {
            throw WorkRefusal.notAllowed(
                    story.state(),
                    to,
                    "A person works every open issue of the story: "
                            + String.join(", ", manual)
                            + ".");
        }
        if (issues.isEmpty() && !held.isEmpty()) {
            // Work never starts over what a hand edit left in doubt; the person sees why.
            throw WorkRefusal.notAllowed(
                    story.state(), to, String.join(" ", held.stream().map(Held::reason).toList()));
        }
        if (issues.isEmpty() && rule.then() != null) {
            throw WorkRefusal.notAllowed(story.state(), to, "A reading needs an open issue.");
        }
        final Map<String, List<Issue>> groups = new LinkedHashMap<>();
        if (rule.per() == WorkRule.Per.STORY) {
            if (board.repositories().isEmpty()) {
                throw WorkRefusal.notAllowed(
                        story.state(), to, "The project names no own repository.");
            }
            groups.put(board.repositories().getFirst(), issues);
        } else {
            for (final Issue issue : issues) {
                groups.computeIfAbsent(issue.repository(), key -> new ArrayList<>()).add(issue);
            }
            if (groups.isEmpty()) {
                throw WorkRefusal.notAllowed(
                        story.state(), to, "Repository work needs an open issue.");
            }
        }
        final List<Repository> repositories = new ArrayList<>();
        for (final Map.Entry<String, List<Issue>> group : groups.entrySet()) {
            final List<Assignment> helpers = new ArrayList<>();
            for (final Issue issue : group.getValue()) {
                final String brief = brief(issue, story, to);
                if (!reading && issue.parent() != null && story.issues().contains(issue.parent())) {
                    final Choice own = Choice.of(to, rule, List.of(issue));
                    helpers.add(
                            new Assignment(
                                    group.getKey() + "/" + issue.id() + "/BUILDER",
                                    issue.id(),
                                    "builder",
                                    "BUILDER",
                                    own.agent(),
                                    own.model(),
                                    to,
                                    brief,
                                    "KEEP",
                                    own.effort(),
                                    own.basis()));
                }
            }
            // A careful choice for one issue of the repository is never lost to a lighter beside
            // it.
            final Choice chosen = Choice.of(to, rule, group.getValue());
            final Main main =
                    new Main(
                            "main",
                            reading ? "READER" : "MAIN",
                            chosen.agent(),
                            chosen.model(),
                            rule.order(),
                            chosen.effort(),
                            chosen.basis(),
                            Offer.of(chosen));
            repositories.add(
                    new Repository(
                            group.getKey(),
                            group.getValue().stream().map(Issue::id).toList(),
                            main,
                            helpers));
        }
        return new WorkProposal(
                rule.modes(),
                repositories,
                running.coordinatorActive() ? "TELL" : "START",
                "",
                2,
                List.of(),
                board.revision(),
                manual,
                held);
    }

    static Story story(final Board board, final String id) {
        Ids.story(id);
        return board.story(id).orElseThrow(() -> WorkRefusal.noSuchStory(id));
    }

    static WorkRule rule(final Board board, final Story story, final String to) {
        final WorkRule rule = target(board, story, to);
        if (!rule.from().contains(story.state()) && !agentsReady(board, story, to, rule)) {
            throw WorkRefusal.notAllowed(
                    story.state(), to, "The rule does not allow work from this state.");
        }
        return rule;
    }

    /**
     * The sound rule of the working state {@code to}: for the story's own work one that takes work
     * from the story's state, for issues a person named one whatever the story's state, since each
     * named issue is checked against it.
     */
    static WorkRule rule(
            final Board board,
            final Story story,
            final String to,
            final @Nullable List<String> picked) {
        return picked == null ? rule(board, story, to) : target(board, story, to);
    }

    /** The sound rule of the working state {@code to}, whatever the story's state. */
    private static WorkRule target(final Board board, final Story story, final String to) {
        if (!board.workflow().states().contains(to)) {
            throw WorkRefusal.invalidSettings();
        }
        final WorkRule rule = WorkRules.check(board.workflow(), board.work()).rules().get(to);
        if (rule == null) {
            throw WorkRefusal.notAllowed(story.state(), to, "The target has no sound work rule.");
        }
        return rule;
    }

    /**
     * The least advanced of the agents' open issues standing where the rule takes work from, as a
     * move of the story moves them, so an issue further on is never worked again.
     */
    private static List<Issue> leastAdvanced(
            final Board board, final WorkRule rule, final List<Issue> agents) {
        final List<String> order = board.workflow().states();
        final List<Issue> ready =
                agents.stream().filter(i -> rule.from().contains(i.state())).toList();
        final int least = ready.stream().mapToInt(i -> order.indexOf(i.state())).min().orElse(-1);
        return ready.stream().filter(i -> order.indexOf(i.state()) == least).toList();
    }

    /**
     * The issues a person named for the work, each once, each an open issue of the story an agent
     * works, standing where the rule takes work from, or in the working state itself where {@code
     * again} holds it; any other list is refused whole.
     */
    private static List<Issue> named(
            final Story story,
            final String to,
            final WorkRule rule,
            final List<Issue> open,
            final List<String> chosen,
            final Set<String> again) {
        if (chosen.isEmpty()) {
            throw WorkRefusal.notAllowed(story.state(), to, "Name at least one issue.");
        }
        if (new java.util.HashSet<>(chosen).size() != chosen.size()) {
            throw WorkRefusal.notAllowed(story.state(), to, "Name each issue once.");
        }
        final List<Issue> named = new ArrayList<>();
        for (final String id : chosen) {
            final Issue issue =
                    open.stream()
                            .filter(i -> i.id().equals(id))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            WorkRefusal.notAllowed(
                                                    story.state(),
                                                    to,
                                                    "A named issue is no open issue of the story."));
            if (issue.worker() == Worker.MANUAL) {
                throw WorkRefusal.notAllowed(
                        story.state(),
                        to,
                        "A person works " + id + ", so no agent's task starts on it.");
            }
            // Only a raised row's Start works its issue again where it stands.
            if (!rule.from().contains(issue.state())
                    && !(again.contains(id) && issue.state().equals(to))) {
                throw WorkRefusal.notAllowed(
                        issue.state(), to, "The rule does not allow work from this state.");
            }
            named.add(issue);
        }
        return named;
    }

    /**
     * Whether the agents' issues of a story whose state an issue a person works holds back stand
     * where the rule starts: the least advanced of them is in its {@code from}.
     */
    private static boolean agentsReady(
            final Board board, final Story story, final String to, final WorkRule rule) {
        if (story.stateGiven()) {
            // A state a person set on the story stands over what its issues say.
            return false;
        }
        final List<Issue> open = free(board, issues(board, story, to, new ArrayList<>()), null);
        if (open.stream().noneMatch(i -> i.worker() == Worker.MANUAL)) {
            return false;
        }
        final List<String> order = board.workflow().states();
        return agents(open).stream()
                .map(Issue::state)
                .min((a, b) -> Integer.compare(order.indexOf(a), order.indexOf(b)))
                .filter(rule.from()::contains)
                .isPresent();
    }

    /** How many open issues a refusal names before it only counts the rest, to keep it short. */
    private static final int MOST_NAMED = 10;

    /**
     * Where the agents' open issues stand and where the rule takes from, so a person sees why
     * nothing starts, for example {@code AA2 stands in Idea; Implementing takes from Backlog and
     * Rework.}
     */
    private static String standing(final List<Issue> agents, final String to, final WorkRule rule) {
        final List<String> named =
                agents.stream()
                        .limit(MOST_NAMED)
                        .map(i -> i.id() + " stands in " + i.state())
                        .toList();
        final String more =
                agents.size() > MOST_NAMED ? " and " + (agents.size() - MOST_NAMED) + " more" : "";
        return String.join(", ", named)
                + more
                + "; "
                + to
                + " takes from "
                + spoken(rule.from())
                + ".";
    }

    /** Names joined as words: {@code A}, {@code A and B}, {@code A, B and C}. */
    private static String spoken(final List<String> names) {
        if (names.size() < 2) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    /** The issues an agent works; one a person works never gets an agent's task. */
    private static List<Issue> agents(final List<Issue> issues) {
        return issues.stream().filter(i -> i.worker() != Worker.MANUAL).toList();
    }

    /**
     * The open issues nothing holds back, each one held back added to {@code held} with its reason,
     * so it is named but never worked.
     */
    private static List<Issue> free(
            final Board board, final List<Issue> open, final @Nullable List<Held> held) {
        final List<Issue> free = new ArrayList<>();
        for (final Issue issue : open) {
            final java.util.Optional<String> reason = board.holdsWork(issue.id());
            if (reason.isEmpty()) {
                free.add(issue);
            } else if (held != null) {
                held.add(new Held(issue.id(), reason.get()));
            }
        }
        return free;
    }

    /**
     * The story's open issues and their open sub-issues; an id the board holds twice is added to
     * {@code held}, since it names no one issue.
     */
    private static List<Issue> issues(
            final Board board, final Story story, final String to, final List<Held> held) {
        final Map<String, Issue> named = new LinkedHashMap<>();
        for (final String id : story.issues()) {
            final java.util.Optional<String> twice = board.twice(id);
            if (twice.isPresent()) {
                held.add(new Held(id, twice.get()));
                continue;
            }
            final Issue issue =
                    board.issue(id)
                            .orElseThrow(
                                    () ->
                                            WorkRefusal.notAllowed(
                                                    story.state(),
                                                    to,
                                                    "The board lacks an issue of the story."));
            named.put(id, issue);
        }
        final Map<String, Issue> open = new LinkedHashMap<>();
        for (final Issue parent : named.values()) {
            // Named under its parent when the story names that too, else as an issue of its own.
            if (parent.parent() != null && named.containsKey(parent.parent())) {
                continue;
            }
            if (!board.workflow().closed().contains(parent.state())) {
                open.put(parent.id(), parent);
            }
            for (final Issue child : board.issues()) {
                if (parent.id().equals(child.parent())
                        && parent.repository().equals(child.repository())
                        && !board.workflow().closed().contains(child.state())) {
                    open.put(child.id(), child);
                }
            }
        }
        return List.copyOf(open.values());
    }

    static String brief(final Issue issue, final Story story, final String to) {
        final StringBuilder brief = new StringBuilder(issue.id() + " — " + issue.title());
        bounded(story, to, brief);
        final String marker = "**What must be true.**";
        boolean paragraph = false;
        final var lines = issue.text().lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            if (!paragraph) {
                if (!line.startsWith(marker)) {
                    continue;
                }
                paragraph = true;
                line = line.substring(marker.length()).strip();
                if (line.isEmpty()) {
                    continue;
                }
            } else if (line.isBlank() || line.startsWith("#")) {
                break;
            }
            line = line.stripTrailing();
            if (Character.codePointCount(brief, 0, brief.length())
                            + 1
                            + line.codePointCount(0, line.length())
                    > 4000) {
                throw WorkRefusal.notAllowed(
                        story.state(), to, "A work brief exceeds 4000 characters.");
            }
            brief.append('\n').append(line);
        }
        return brief.toString();
    }

    private static void bounded(final Story story, final String to, final CharSequence brief) {
        if (Character.codePointCount(brief, 0, brief.length()) > 4000) {
            throw WorkRefusal.notAllowed(
                    story.state(), to, "A work brief exceeds 4000 characters.");
        }
    }
}
