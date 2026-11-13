package org.fuin.porelo.planner.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/**
 * The tasks the window may start, computed without changing the board or recording a run, with the
 * repositories as accepted: the person's choices, and where each choice came from as the rule says.
 */
public record WorkOrder(
        Revision revision, List<Task> tasks, List<WorkProposal.Repository> repositories) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(story|issues|tasks)\\}");

    /** Copies tasks so a prepared order cannot be changed through its input. */
    public WorkOrder {
        tasks = List.copyOf(tasks);
        repositories = List.copyOf(repositories);
    }

    /**
     * The complete arguments for one call to Start; {@code reads} names the Porelo tasks whose work
     * a reader reads, so Porelo's gate gives it their branches, and is empty for every other role.
     *
     * @param repository the repository the task works in
     * @param issues the issues it owns
     * @param role its role
     * @param agent its agent
     * @param model the agent's model
     * @param effort the effort Start is handed: the person's choice for a task started by hand, the
     *     rule's for the task's risk for one that starts by itself; null for none
     * @param mode its mode
     * @param prompt its order
     * @param reads the Porelo tasks whose work it reads
     * @param basis where the rule's choice of agent, model and effort came from, empty when unknown
     * @param used the effort the window said the task starts with, null while nobody said
     */
    public record Task(
            String repository,
            List<String> issues,
            String role,
            String agent,
            String model,
            @Nullable String effort,
            String mode,
            String prompt,
            List<String> reads,
            String basis,
            @Nullable String used) {
        /** Copies issue identities to preserve the accepted scope. */
        public Task {
            issues = List.copyOf(issues);
            reads = List.copyOf(reads);
        }

        /** A task whose choice's origin is unknown and whose effort nobody confirmed. */
        public Task(
                final String repository,
                final List<String> issues,
                final String role,
                final String agent,
                final String model,
                final @Nullable String effort,
                final String mode,
                final String prompt,
                final List<String> reads) {
            this(repository, issues, role, agent, model, effort, mode, prompt, reads, "", null);
        }

        /** A task that reads no other task's work. */
        public Task(
                final String repository,
                final List<String> issues,
                final String role,
                final String agent,
                final String model,
                final @Nullable String effort,
                final String mode,
                final String prompt) {
            this(repository, issues, role, agent, model, effort, mode, prompt, List.of());
        }
    }

    /** An issue's chosen reading limit before validation. */
    public record Limit(String issue, Number rounds) {}

    /**
     * The operator's choices; omitted collections and limits take the proposal's defaults, and
     * {@code issues}, when given, are the story's issues the work is for, none other.
     */
    public record Decision(
            String mode,
            String revision,
            @Nullable List<WorkProposal.Repository> repositories,
            @Nullable String comment,
            @Nullable Number readingRounds,
            @Nullable List<Limit> issueReadingRounds,
            @Nullable List<String> issues) {
        /** Copies present choices to prevent concurrent changes while accepting. */
        public Decision {
            repositories = repositories == null ? null : List.copyOf(repositories);
            issueReadingRounds =
                    issueReadingRounds == null ? null : List.copyOf(issueReadingRounds);
            issues = issues == null ? null : List.copyOf(issues);
        }

        /**
         * Choices for the story's work as its proposal names it.
         *
         * @param mode the mode
         * @param revision the board's revision the person saw
         * @param repositories the chosen repositories, or null for the proposal's
         * @param comment the person's words, or null
         * @param readingRounds the readings granted, or null for the default
         * @param issueReadingRounds the readings granted per issue, or null
         */
        public Decision(
                final String mode,
                final String revision,
                final @Nullable List<WorkProposal.Repository> repositories,
                final @Nullable String comment,
                final @Nullable Number readingRounds,
                final @Nullable List<Limit> issueReadingRounds) {
            this(mode, revision, repositories, comment, readingRounds, issueReadingRounds, null);
        }
    }

    /** Accepts a decision without inventing running records. */
    public static WorkOrder accept(
            final Board board, final String id, final String to, final Decision decision) {
        return accept(board, id, to, decision, story -> List.of());
    }

    /** Validates the decision and returns tasks using explicit read-only running-work input. */
    public static WorkOrder accept(
            final Board board,
            final String id,
            final String to,
            final Decision decision,
            final WorkProposal.RunningWork running) {
        final Story story = WorkProposal.story(board, id);
        if (!board.workflow().states().contains(to)) {
            throw WorkRefusal.invalidSettings();
        }
        if (!board.revision().value().equals(decision.revision())) {
            throw WorkRefusal.staleRevision(board.revision().value());
        }
        final List<String> active = List.copyOf(running.tasks(id));
        if (!active.isEmpty()) {
            throw WorkRefusal.workRunning(id, active);
        }
        final WorkProposal proposal =
                WorkProposal.propose(board, id, to, running, decision.issues());
        final WorkRule rule = WorkProposal.rule(board, story, to, decision.issues());
        if (!rule.modes().contains(decision.mode())) {
            throw refused(story, to, "The project does not allow that mode.");
        }
        final String suppliedComment = decision.comment();
        final String comment = suppliedComment == null ? "" : suppliedComment;
        if (comment.codePointCount(0, comment.length()) > 4000) {
            throw refused(story, to, "The comment exceeds 4000 characters.");
        }
        limits(story, to, proposal, decision);
        final List<WorkProposal.Repository> suppliedRepositories = decision.repositories();
        final List<WorkProposal.Repository> selected =
                suppliedRepositories == null ? proposal.repositories() : suppliedRepositories;
        final Map<String, WorkProposal.Repository> byRepository = new LinkedHashMap<>();
        for (final WorkProposal.Repository repository : selected) {
            if (byRepository.put(repository.repository(), repository) != null) {
                throw refused(story, to, "Choose every repository exactly once.");
            }
        }
        if (!byRepository
                .keySet()
                .equals(
                        new HashSet<>(
                                proposal.repositories().stream()
                                        .map(WorkProposal.Repository::repository)
                                        .toList()))) {
            throw refused(story, to, "Choose every proposed repository exactly once.");
        }
        final List<Task> tasks = new ArrayList<>();
        final List<WorkProposal.Repository> accepted = new ArrayList<>();
        for (final WorkProposal.Repository source : proposal.repositories()) {
            final WorkProposal.Repository choice = byRepository.get(source.repository());
            if (choice == null) {
                throw refused(story, to, "The repository was not proposed.");
            }
            final List<WorkProposal.Assignment> helpers =
                    validate(board, story, to, rule, source, choice, comment);
            final String mainEffort = effort(story, to, choice.main().effort());
            accepted.add(
                    new WorkProposal.Repository(
                            source.repository(),
                            source.issues(),
                            new WorkProposal.Main(
                                    source.main().name(),
                                    source.main().role(),
                                    choice.main().agent(),
                                    choice.main().model(),
                                    source.main().brief(),
                                    mainEffort,
                                    source.main().basis()),
                            helpers));
            final List<WorkProposal.Assignment> kept =
                    helpers.stream().filter(helper -> !helper.choice().equals("DROP")).toList();
            final List<String> dropped =
                    helpers.stream()
                            .filter(helper -> helper.choice().equals("DROP"))
                            .map(WorkProposal.Assignment::issue)
                            .toList();
            final String issues = issueText(board, story, to, source.issues());
            final String names =
                    String.join("\n", kept.stream().map(WorkProposal.Assignment::id).toList());
            String mainPrompt = prompt(rule, story, issues, names, comment);
            if (!dropped.isEmpty()) {
                mainPrompt +=
                        "\n\nOwn work from dropped participants:\n"
                                + issueText(board, story, to, dropped);
            }
            tasks.add(
                    task(
                            story,
                            to,
                            source.repository(),
                            source.issues(),
                            "MAIN",
                            choice.main().agent(),
                            choice.main().model(),
                            mainEffort,
                            decision.mode(),
                            mainPrompt,
                            source.main().basis()));
            for (final WorkProposal.Assignment helper : kept) {
                final Issue issue = board.issue(helper.issue()).orElseThrow();
                tasks.add(
                        task(
                                story,
                                to,
                                issue.repository(),
                                List.of(helper.issue()),
                                helper.kind(),
                                helper.agent(),
                                helper.model(),
                                helper.effort(),
                                decision.mode(),
                                prompt(
                                        rule,
                                        story,
                                        issueText(board, story, to, List.of(helper.issue())),
                                        "",
                                        comment),
                                helper.basis()));
            }
        }
        return new WorkOrder(board.revision(), tasks, accepted);
    }

    private static List<WorkProposal.Assignment> validate(
            final Board board,
            final Story story,
            final String to,
            final WorkRule rule,
            final WorkProposal.Repository source,
            final WorkProposal.Repository choice,
            final String comment) {
        if (!source.issues().equals(choice.issues())
                || !source.main().name().equals(choice.main().name())
                || !source.main().brief().equals(choice.main().brief())
                || !choice.main().role().equals(source.main().role())) {
            throw refused(story, to, "A main identity, brief or repository's issues were changed.");
        }
        offered(
                story,
                to,
                Choice.of(
                        to,
                        rule,
                        source.issues().stream().map(id -> board.issue(id).orElseThrow()).toList()),
                choice.main().agent(),
                choice.main().model());
        final Map<String, WorkProposal.Assignment> selected = new LinkedHashMap<>();
        for (final WorkProposal.Assignment helper : choice.subAgents()) {
            if (selected.put(helper.id(), helper) != null) {
                throw refused(story, to, "Choose every proposed helper exactly once.");
            }
        }
        final Set<String> ids =
                new HashSet<>(
                        source.subAgents().stream().map(WorkProposal.Assignment::id).toList());
        if (!ids.containsAll(selected.keySet())) {
            throw refused(story, to, "A helper identity or brief was changed.");
        }
        if (!selected.keySet().equals(ids)) {
            throw refused(story, to, "Choose every proposed helper exactly once.");
        }
        final List<WorkProposal.Assignment> checked = new ArrayList<>();
        for (final WorkProposal.Assignment original : source.subAgents()) {
            final WorkProposal.Assignment helper = selected.get(original.id());
            if (helper == null
                    || !original.issue().equals(helper.issue())
                    || !original.name().equals(helper.name())
                    || !original.kind().equals(helper.kind())
                    || !original.workingState().equals(helper.workingState())
                    || !original.brief().equals(helper.brief())) {
                throw refused(story, to, "A helper identity or brief was changed.");
            }
            if (!Set.of("KEEP", "CHANGE", "DROP").contains(helper.choice())
                    || helper.kind().equals("OTHER") && !helper.choice().equals("DROP")) {
                throw refused(story, to, "Unknown helpers cannot start.");
            }
            if (!helper.choice().equals("KEEP") && comment.isBlank()) {
                throw refused(story, to, "A changed or dropped participant needs a comment.");
            }
            if (helper.choice().equals("KEEP")
                    && (!original.agent().equals(helper.agent())
                            || !original.model().equals(helper.model()))) {
                throw refused(story, to, "A kept participant's agent or model was changed.");
            }
            // A kept helper starts with the offer's effort, whether the caller sent it back or not.
            if (helper.choice().equals("KEEP")
                    && helper.effort() != null
                    && !helper.effort().equals(original.effort())) {
                throw refused(story, to, "A kept participant's effort was changed.");
            }
            if (helper.choice().equals("CHANGE")) {
                offered(
                        story,
                        to,
                        Choice.of(to, rule, List.of(board.issue(original.issue()).orElseThrow())),
                        helper.agent(),
                        helper.model());
            }
            checked.add(
                    new WorkProposal.Assignment(
                            original.id(),
                            original.issue(),
                            original.name(),
                            original.kind(),
                            helper.choice().equals("CHANGE") ? helper.agent() : original.agent(),
                            helper.choice().equals("CHANGE") ? helper.model() : original.model(),
                            original.workingState(),
                            original.brief(),
                            helper.choice(),
                            helper.choice().equals("CHANGE")
                                    ? effort(story, to, helper.effort())
                                    : original.effort(),
                            original.basis()));
        }
        return checked;
    }

    /** An effort is a plain name or none, since it reaches the window's Start. */
    private static @Nullable String effort(
            final Story story, final String to, final @Nullable String effort) {
        if (effort != null && !WorkRule.sound(effort)) {
            throw refused(story, to, "Choose an effort that is a plain name, or none.");
        }
        return effort;
    }

    private static void offered(
            final Story story,
            final String to,
            final Choice choice,
            final String agent,
            final String model) {
        if (!choice.offers(agent, model)) {
            throw refused(story, to, "Choose an agent and model offered by the rule.");
        }
    }

    private static void limits(
            final Story story,
            final String to,
            final WorkProposal proposal,
            final Decision decision) {
        final Number suppliedRounds = decision.readingRounds();
        final Number rounds = suppliedRounds == null ? proposal.readingRounds() : suppliedRounds;
        if (!validRounds(rounds)) {
            throw refused(story, to, "Reading rounds must be integers from 1 to 10.");
        }
        final Set<String> proposed = new HashSet<>();
        proposal.repositories().forEach(repository -> proposed.addAll(repository.issues()));
        final Set<String> seen = new HashSet<>();
        final List<Limit> suppliedOverrides = decision.issueReadingRounds();
        final List<Limit> overrides = suppliedOverrides == null ? List.of() : suppliedOverrides;
        for (final Limit limit : overrides) {
            if (!proposed.contains(limit.issue())
                    || !seen.add(limit.issue())
                    || !validRounds(limit.rounds())) {
                throw refused(
                        story,
                        to,
                        "Choose a bounded reading limit for each proposed issue at most once.");
            }
        }
    }

    private static boolean validRounds(final Number number) {
        if (!(number instanceof Byte
                || number instanceof Short
                || number instanceof Integer
                || number instanceof Long
                || number instanceof BigInteger)) {
            return false;
        }
        try {
            final int value = new BigDecimal(number.toString()).intValueExact();
            return value >= 1 && value <= 10;
        } catch (final NumberFormatException | ArithmeticException invalid) {
            return false;
        }
    }

    static String issueText(
            final Board board, final Story story, final String to, final List<String> ids) {
        final List<String> pieces = new ArrayList<>();
        for (final String id : ids) {
            final String brief = WorkProposal.brief(board.issue(id).orElseThrow(), story, to);
            int longest = 0;
            int run = 0;
            for (int index = 0; index < brief.length(); index++) {
                run = brief.charAt(index) == '`' ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
            final String fence = "`".repeat(Math.max(3, longest + 1));
            pieces.add(
                    "The following is the issue's text ("
                            + id
                            + "):\n"
                            + fence
                            + "\n"
                            + brief
                            + "\n"
                            + fence);
        }
        return String.join("\n\n", pieces);
    }

    /**
     * The planner moves an issue, writes stats/ and rewrites the issues' index itself, so a task
     * that commits a move too lands moves the planner did not make.
     */
    static final String PLANNER_MOVES =
            "\n\nThe planner alone moves these issues from state to state and writes stats/:"
                    + " change no issue's state and nothing under stats/. The planner rewrites"
                    + " issues/README.md itself after each move.";

    static String prompt(
            final WorkRule rule,
            final Story story,
            final String issues,
            final String tasks,
            final String comment) {
        final Matcher matcher = PLACEHOLDER.matcher(rule.order());
        final StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            final String value =
                    switch (matcher.group(1)) {
                        case "story" -> story.id();
                        case "issues" -> "\n" + issues + "\n";
                        case "tasks" -> tasks;
                        default -> throw new IllegalStateException("Unknown order placeholder");
                    };
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return "Work order:\n"
                + expanded
                + "\n\nRelevant issues:\n"
                + issues
                + "\n\nSuccessful work reaches "
                + rule.done()
                + "."
                + PLANNER_MOVES
                + "\n\nOperator comment:\n"
                + comment;
    }

    private static Task task(
            final Story story,
            final String to,
            final String repository,
            final List<String> issues,
            final String role,
            final String agent,
            final String model,
            final @Nullable String effort,
            final String mode,
            final String prompt,
            final String basis) {
        if (prompt.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw refused(story, to, "A task prompt exceeds 65536 UTF-8 bytes.");
        }
        // A task started by hand starts with the effort the person's order names.
        return new Task(
                repository,
                issues,
                role,
                agent,
                model,
                effort,
                mode,
                prompt,
                List.of(),
                basis,
                effort);
    }

    private static WorkRefusal refused(final Story story, final String to, final String reason) {
        return WorkRefusal.notAllowed(story.state(), to, reason);
    }
}
