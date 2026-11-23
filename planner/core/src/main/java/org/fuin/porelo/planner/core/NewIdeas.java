package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.Worker;
import org.jspecify.annotations.Nullable;

/**
 * Checks an idea a discussion wrote against the idea format and the board, before the store writes
 * anything from it; a refusal lists every fault by its field in fixed words and never repeats what
 * was sent beyond an issue's id, which the reader checked as one, and a repository's name that has
 * the form of one; a refused repository is told with the names the project allows.
 */
public final class NewIdeas {

    /** The refusal's name the window reads. */
    public static final String REFUSED = "IdeaRefused";

    /** The most code points a story's or an issue's title may have. */
    public static final int MOST_TITLE = 256;

    /** The most code points a story's gain or test may have. */
    public static final int MOST_LINE = 400;

    /** The most code points what must be true may have. */
    public static final int MOST_TEXT = 65536;

    /** The most faults a refusal lists. */
    public static final int MOST_FAULTS = 100;

    /**
     * Anything that breaks one line: a C0 or C1 control character, NEL or a line or paragraph
     * separator.
     */
    private static final Pattern BREAKS = Pattern.compile("[\\x00-\\x1F\\x7F-\\x9F\\u2028\\u2029]");

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    private NewIdeas() {}

    /**
     * An idea as the idea format holds it, each member null where it is missing or not a text.
     *
     * @param story the story, or null when it is missing or no object
     * @param issues the issues, an item null where it is no object
     */
    public record Idea(@Nullable Story story, List<@Nullable Issue> issues) {

        /** Copies the issues, keeping an item that is no object as null. */
        public Idea {
            issues = java.util.Collections.unmodifiableList(new ArrayList<>(issues));
        }
    }

    /**
     * The idea's story.
     *
     * @param title its title
     * @param gain what a user gains
     * @param test how a person sees it work
     * @param priority its priority
     */
    public record Story(
            @Nullable String title,
            @Nullable String gain,
            @Nullable String test,
            @Nullable String priority) {}

    /**
     * One issue of the idea.
     *
     * @param repository the repository's name
     * @param title its title
     * @param mustBeTrue what must be true
     * @param size its size, or null
     * @param worker who works it, {@code Agent} or {@code Manual}
     */
    public record Issue(
            @Nullable String repository,
            @Nullable String title,
            @Nullable String mustBeTrue,
            @Nullable String size,
            @Nullable String worker) {}

    /**
     * Checks an idea and answers what the store saves.
     *
     * @param board the board the idea is saved against
     * @param idea the idea
     * @param shape the faults of the idea's shape the reader found, each by its field
     * @return the idea the store saves, its texts without the spaces around them
     * @throws WorkRefusal {@code IdeaRefused} listing at most {@value #MOST_FAULTS} faults
     */
    public static NewIdea check(final Board board, final Idea idea, final List<String> shape) {
        final Faults faults = new Faults();
        shape.forEach(faults::add);
        final Head head = head(faults, idea.story(), "The idea");
        if (idea.issues().isEmpty()) {
            faults.add("issues: The idea must name one issue at least.");
        }
        final List<NewIssue> issues = new ArrayList<>();
        final Set<String> named = new HashSet<>();
        for (int index = 0; index < idea.issues().size() && !faults.full(); index++) {
            final Issue issue = idea.issues().get(index);
            if (issue == null) {
                faults.add(
                        "issues["
                                + index
                                + "]: The issue must be an object with repository, title,"
                                + " mustBeTrue and worker.");
                continue;
            }
            final NewIssue checked = issue(faults, "issues[" + index + "]", issue, board, named);
            if (checked != null) {
                issues.add(checked);
            }
        }
        if (!faults.list.isEmpty()) {
            throw refused(faults.list);
        }
        return new NewIdea(head.title(), head.gain(), head.test(), head.priority(), issues);
    }

    /**
     * Checks a story to be made over issues that stand on the board, and answers what the store
     * saves. Each issue must stand on the board in an open state of the workflow, be no story and
     * no sub-issue, be held back by no warning, be named once and belong to no other story; one
     * that belongs to a story of the same title whose body carries this request's {@link
     * StoryFromIssues#marker() marker} and whose every issue is named too is let through, since
     * that is the story a cut-off save of the same request made, and the store takes it or refuses
     * the issue before it writes.
     *
     * @param board the board the story is saved against
     * @param story the story, or null when it is missing or no object
     * @param existing the issues' ids, each already checked as an issue's id, in the story's order
     * @param shape the faults of the body's shape the reader found, each by its field
     * @return the story the store saves, its texts without the spaces around them
     * @throws WorkRefusal {@code IdeaRefused} listing at most {@value #MOST_FAULTS} faults, each
     *     refused issue named by its id, or, with none, {@code IssueInStory} naming the first issue
     *     another story holds, that story and its field
     */
    public static StoryFromIssues checkFromIssues(
            final Board board,
            final @Nullable Story story,
            final List<String> existing,
            final List<String> shape) {
        final Faults faults = new Faults();
        shape.forEach(faults::add);
        final Head head = head(faults, story, "The body");
        if (existing.isEmpty()) {
            faults.add("existing: The story must name one issue at least.");
        } else if (existing.size() > Edits.MOST_ISSUES) {
            faults.add("existing: A story holds at most " + Edits.MOST_ISSUES + " issues.");
        }
        final Map<String, org.fuin.porelo.planner.store.Story> holder = new HashMap<>();
        for (final org.fuin.porelo.planner.store.Story other : board.stories()) {
            other.issues().forEach(id -> holder.putIfAbsent(id, other));
        }
        final Set<String> named = new HashSet<>();
        final String marker =
                StoryFromIssues.marker(head.title(), head.gain(), head.test(), existing);
        @Nullable WorkRefusal held = null;
        for (int index = 0; index < existing.size() && !faults.full(); index++) {
            final String id = existing.get(index);
            final String fault = existingFault(board, id, named);
            if (fault != null) {
                faults.add("existing[" + index + "]: " + fault);
                continue;
            }
            final org.fuin.porelo.planner.store.Story other = holder.get(id);
            if (held == null
                    && other != null
                    && !madeBefore(other, head.title(), marker, existing)) {
                held = WorkRefusal.issueInStory(id, other.id(), "existing[" + index + "]");
            }
        }
        if (!faults.list.isEmpty()) {
            throw WorkRefusal.ideaRefused(
                    "The story cannot be made from these issues.", faults.list, null);
        }
        if (held != null) {
            // A malformed request is told first; an issue held elsewhere is a conflict of the
            // board.
            throw held;
        }
        return new StoryFromIssues(
                head.title(), head.gain(), head.test(), head.priority(), existing);
    }

    /** Why one issue cannot be taken into a new story, naming it by its checked id, or null. */
    private static @Nullable String existingFault(
            final Board board, final String id, final Set<String> named) {
        if (!named.add(id)) {
            return "The issue " + id + " is named twice.";
        }
        if (board.twice(id).isPresent()) {
            return "The id " + id + " names two items of the board, so no one issue is meant.";
        }
        if (board.story(id).isPresent()) {
            return id + " is a story, not an issue.";
        }
        final org.fuin.porelo.planner.store.Issue issue = board.issue(id).orElse(null);
        if (issue == null) {
            return "There is no issue " + id + " on the board.";
        }
        if (!board.workflow().states().contains(issue.state())) {
            return "The issue "
                    + id
                    + " stands outside the workflow; move it into a state of the workflow first.";
        }
        final java.util.Optional<String> held = board.holdsWork(id);
        if (held.isPresent()) {
            // A held issue is neither added to nor taken from a story, whatever else it allows.
            return held.get();
        }
        if (board.workflow().closed().contains(issue.state())) {
            return "The issue "
                    + id
                    + " is "
                    + issue.state()
                    + "; a new story takes open issues only.";
        }
        if (issue.parent() != null) {
            return "The issue " + id + " is a sub-issue of " + issue.parent() + ".";
        }
        return null;
    }

    /**
     * Whether a story holding an issue may be the one an earlier, cut-off save of the same request
     * made: its body carries that request's marker, it has the same title and every issue it holds
     * is named. A story a person made, or one of another request, carries no such marker.
     */
    static boolean madeBefore(
            final org.fuin.porelo.planner.store.Story other,
            final String title,
            final String marker,
            final List<String> existing) {
        return other.issuesWhole()
                && other.description().contains(marker)
                && other.title().strip().equals(title)
                && existing.containsAll(other.issues());
    }

    /**
     * A story's title, gain, test and priority as checked.
     *
     * @param title its title
     * @param gain what a user gains
     * @param test its test
     * @param priority its priority
     */
    private record Head(String title, String gain, String test, String priority) {}

    private static Head head(final Faults faults, final @Nullable Story story, final String whole) {
        if (story == null) {
            faults.add(
                    "story: " + whole + " must hold a story with title, gain, test and priority.");
            return new Head("", "", "", "");
        }
        final String title = line(faults, "story.title", "The title", story.title(), MOST_TITLE);
        final String gain =
                line(faults, "story.gain", "What a user gains", story.gain(), MOST_LINE);
        final String test = line(faults, "story.test", "The test", story.test(), MOST_LINE);
        final String priority = String.valueOf(story.priority());
        if (!org.fuin.porelo.planner.store.Story.PRIORITIES.contains(priority)) {
            faults.add(
                    "story.priority: The priority must be Highest, High, Medium, Low or Lowest.");
        }
        return new Head(title, gain, test, priority);
    }

    // The repository is checked before any NewIssue is built, whose constructor refuses a bad name.
    private static @Nullable NewIssue issue(
            final Faults faults,
            final String field,
            final Issue issue,
            final Board board,
            final Set<String> named) {
        final int before = faults.list.size();
        final String repository = issue.repository();
        if (repository == null || !NAME.matcher(repository).matches()) {
            faults.add(
                    field
                            + ".repository: The repository is no repository name; it must be one of "
                            + String.join(", ", board.names())
                            + ".");
        } else if (!board.names().contains(repository)) {
            // A well-formed name is safe to repeat, and tells the agent which name it got wrong.
            faults.add(
                    field
                            + ".repository: The repository "
                            + repository
                            + " is no repository of the project; it must be one of "
                            + String.join(", ", board.names())
                            + ".");
        } else if (!named.add(repository)) {
            faults.add(
                    field
                            + ".repository: The repository is named by another issue of the"
                            + " idea.");
        }
        final String title = line(faults, field + ".title", "The title", issue.title(), MOST_TITLE);
        final String mustBeTrue = issue.mustBeTrue();
        if (mustBeTrue == null
                || mustBeTrue.isBlank()
                || mustBeTrue.codePointCount(0, mustBeTrue.length()) > MOST_TEXT) {
            faults.add(
                    field
                            + ".mustBeTrue: What must be true must have 1 to "
                            + MOST_TEXT
                            + " characters and not be blank.");
        }
        final String size = issue.size();
        if (size != null && !NewIssues.SIZES.contains(size)) {
            faults.add(field + ".size: The size must be XS, S, M, L or XL.");
        }
        final Worker worker =
                "Agent".equals(issue.worker())
                        ? Worker.AGENT
                        : "Manual".equals(issue.worker()) ? Worker.MANUAL : null;
        if (worker == null) {
            faults.add(field + ".worker: The worker must be Agent or Manual.");
        }
        if (faults.list.size() != before
                || repository == null
                || mustBeTrue == null
                || worker == null) {
            return null;
        }
        return new NewIssue(repository, title, mustBeTrue, size, worker);
    }

    private static String line(
            final Faults faults,
            final String field,
            final String what,
            final @Nullable String text,
            final int most) {
        final String line = text == null ? "" : text.strip();
        if (text == null
                || BREAKS.matcher(text).find()
                || line.isEmpty()
                || line.codePointCount(0, line.length()) > most) {
            faults.add(field + ": " + what + " must be one line of 1 to " + most + " characters.");
        }
        return line;
    }

    /**
     * A refusal of an idea, as the window reads it.
     *
     * @param faults the faults, each by its field in fixed words
     * @return the refusal
     */
    public static WorkRefusal refused(final List<String> faults) {
        return WorkRefusal.ideaRefused("The idea does not follow the idea format.", faults, null);
    }

    /** At most {@value #MOST_FAULTS} faults, the first ones found. */
    private static final class Faults {

        private final List<String> list = new ArrayList<>();

        private void add(final String fault) {
            if (!full()) {
                list.add(fault);
            }
        }

        private boolean full() {
            return list.size() >= MOST_FAULTS;
        }
    }
}
