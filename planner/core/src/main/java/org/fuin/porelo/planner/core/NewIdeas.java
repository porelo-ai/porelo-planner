package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Worker;
import org.jspecify.annotations.Nullable;

/**
 * Checks an idea a discussion wrote against the idea format and the board, before the store writes
 * anything from it; a refusal lists every fault by its field in fixed words and never repeats what
 * was sent.
 */
public final class NewIdeas {

    /** The refusal's name the window reads. */
    public static final String REFUSED = "IdeaRefused";

    /** The most code points a story's or an issue's title may have. */
    public static final int MOST_TITLE = 120;

    /** The most code points a story's gain or test may have. */
    public static final int MOST_LINE = 400;

    /** The most code points what must be true may have. */
    public static final int MOST_TEXT = 4000;

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
        final Story story = idea.story();
        String title = "";
        String gain = "";
        String test = "";
        String priority = "";
        if (story == null) {
            faults.add("story: The idea must hold a story with title, gain, test and priority.");
        } else {
            title = line(faults, "story.title", "The title", story.title(), MOST_TITLE);
            gain = line(faults, "story.gain", "What a user gains", story.gain(), MOST_LINE);
            test = line(faults, "story.test", "The test", story.test(), MOST_LINE);
            priority = String.valueOf(story.priority());
            if (!org.fuin.porelo.planner.store.Story.PRIORITIES.contains(priority)) {
                faults.add(
                        "story.priority: The priority must be Highest, High, Medium, Low or"
                                + " Lowest.");
            }
        }
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
        return new NewIdea(title, gain, test, priority, issues);
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
        if (repository == null
                || !NAME.matcher(repository).matches()
                || !board.repositories().contains(repository)) {
            faults.add(field + ".repository: The repository is no repository of the project.");
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
