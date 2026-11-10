package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.WorkRule;

/** The immutable choices proposed from one board, with no runtime or store changes. */
public record WorkProposal(
        List<String> modes,
        List<Repository> repositories,
        String coordinator,
        String comment,
        int readingRounds,
        List<IssueRounds> issueReadingRounds,
        Revision revision) {

    /** Copies every collection so recomputation cannot be changed by a caller. */
    public WorkProposal {
        modes = List.copyOf(modes);
        repositories = List.copyOf(repositories);
        issueReadingRounds = List.copyOf(issueReadingRounds);
    }

    /** The proposed main participant; only its offered agent/model pair is a choice. */
    public record Main(String name, String role, String agent, String model, String brief) {}

    /** A stable sub-issue identity with an explicit participant choice. */
    public record Assignment(
            String id,
            String issue,
            String name,
            String kind,
            String agent,
            String model,
            String workingState,
            String brief,
            String choice) {}

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
    }

    /** Proposes work without inventing running records. */
    public static WorkProposal propose(final Board board, final String id, final String to) {
        return propose(board, id, to, story -> List.of());
    }

    /** Proposes work using only the board and explicitly supplied coordinator knowledge. */
    public static WorkProposal propose(
            final Board board, final String id, final String to, final RunningWork running) {
        final Story story = story(board, id);
        final WorkRule rule = rule(board, story, to);
        bounded(story, to, rule.order());
        final List<Issue> issues = issues(board, story, to);
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
        final Map.Entry<String, String> model = rule.models().entrySet().iterator().next();
        final List<Repository> repositories = new ArrayList<>();
        for (final Map.Entry<String, List<Issue>> group : groups.entrySet()) {
            final List<Assignment> helpers = new ArrayList<>();
            for (final Issue issue : group.getValue()) {
                final String brief = brief(issue, story, to);
                if (issue.parent() != null && story.issues().contains(issue.parent())) {
                    helpers.add(
                            new Assignment(
                                    group.getKey() + "/" + issue.id() + "/BUILDER",
                                    issue.id(),
                                    "builder",
                                    "BUILDER",
                                    model.getKey(),
                                    model.getValue(),
                                    to,
                                    brief,
                                    "KEEP"));
                }
            }
            final Main main =
                    new Main("main", "MAIN", model.getKey(), model.getValue(), rule.order());
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
                board.revision());
    }

    static Story story(final Board board, final String id) {
        Ids.story(id);
        return board.story(id).orElseThrow(() -> WorkRefusal.noSuchStory(id));
    }

    static WorkRule rule(final Board board, final Story story, final String to) {
        if (!board.workflow().states().contains(to)) {
            throw WorkRefusal.invalidSettings();
        }
        final WorkRule rule = WorkRules.check(board.workflow(), board.work()).rules().get(to);
        if (rule == null) {
            throw WorkRefusal.notAllowed(story.state(), to, "The target has no sound work rule.");
        }
        if (!rule.from().contains(story.state())) {
            throw WorkRefusal.notAllowed(
                    story.state(), to, "The rule does not allow work from this state.");
        }
        return rule;
    }

    private static List<Issue> issues(final Board board, final Story story, final String to) {
        final Map<String, Issue> named = new LinkedHashMap<>();
        for (final String id : story.issues()) {
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
