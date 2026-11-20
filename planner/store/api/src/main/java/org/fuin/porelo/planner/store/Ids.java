package org.fuin.porelo.planner.store;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The shapes an id or a name must have before the planner lets it reach a path or a command. */
public final class Ids {

    private static final Pattern STORY = Pattern.compile("ST[0-9]{1,5}");

    private static final Pattern ISSUE = Pattern.compile("[A-Z]{1,4}[0-9]{1,5}(\\.[0-9]{1,3})?");

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    private static final Pattern REFERENCE =
            Pattern.compile("(" + NAME.pattern() + ")#([1-9][0-9]{0,8})");

    private static final Pattern TASK = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    private Ids() {}

    /**
     * Refuses a story id that is neither {@code ST} and a number nor a tracker reference.
     *
     * @param id the id to check
     * @return the id
     */
    public static String story(final String id) {
        return checked(STORY, id, "story id");
    }

    /**
     * Refuses an issue id that is neither a prefix of capitals, a number and an optional sub-issue
     * number nor a tracker reference.
     *
     * @param id the id to check
     * @return the id
     */
    public static String issue(final String id) {
        return checked(ISSUE, id, "issue id");
    }

    /**
     * Refuses a story's alias, the id a story carried over into a tracker had in the Git store:
     * {@code ST} and a number, never a tracker reference.
     *
     * @param alias the alias to check
     * @return the alias
     */
    public static String storyAlias(final String alias) {
        return shaped(STORY.matcher(alias).matches(), STORY.pattern(), alias, "story alias");
    }

    /**
     * Refuses an issue's alias, the id an issue carried over into a tracker had in the Git store,
     * never a tracker reference.
     *
     * @param alias the alias to check
     * @return the alias
     */
    public static String issueAlias(final String alias) {
        return shaped(ISSUE.matcher(alias).matches(), ISSUE.pattern(), alias, "issue alias");
    }

    /**
     * Whether an id is a tracker reference within the project, {@code <repository>#<number>}: a
     * repository's name as {@link #name} takes it and a number of one to nine digits without a
     * leading zero.
     *
     * @param id the id to look at
     * @return whether it is one
     */
    public static boolean isReference(final String id) {
        final Matcher matcher = REFERENCE.matcher(id);
        return matcher.matches() && !matcher.group(1).contains("..");
    }

    /**
     * The repository a tracker reference names.
     *
     * @param reference a tracker reference, {@code <repository>#<number>}
     * @return the repository's name
     */
    public static String repositoryOf(final String reference) {
        if (!isReference(reference)) {
            throw new IllegalArgumentException(
                    "Not a tracker reference, " + reference.length() + " characters");
        }
        return reference.substring(0, reference.indexOf('#'));
    }

    /**
     * Refuses a project's or repository's name that could leave a directory or read as an option.
     *
     * @param name the name to check
     * @return the name
     */
    public static String name(final String name) {
        // A refused name is never shown: it may be a clone URL with a password in it.
        if (!NAME.matcher(name).matches() || name.contains("..")) {
            throw new IllegalArgumentException(
                    "Not a name ("
                            + NAME.pattern()
                            + ", without '..'), "
                            + name.length()
                            + " characters");
        }
        return name;
    }

    /**
     * Whether a text has the shape of a Porelo task's name or label: no space, no line break and
     * nothing Markdown reads, so it can stand in a file or a refusal as it is.
     *
     * @param task the text to check
     * @return whether it is one
     */
    public static boolean isTask(final String task) {
        return TASK.matcher(task).matches();
    }

    /**
     * Refuses a text that is not a Porelo task's name.
     *
     * @param task the text to check
     * @return the name
     */
    public static String task(final String task) {
        // A refused name is never shown: it may carry a bidi control or a line separator.
        if (!isTask(task)) {
            throw new IllegalArgumentException(
                    "Not a task name (" + TASK.pattern() + "), " + task.length() + " characters");
        }
        return task;
    }

    private static String checked(final Pattern pattern, final String value, final String what) {
        return shaped(
                pattern.matcher(value).matches() || isReference(value),
                pattern.pattern() + " or <repository>#<number>",
                value,
                what);
    }

    // A refused id is never shown: what a caller put in its place may be a token or a URL.
    private static String shaped(
            final boolean fits, final String shape, final String value, final String what) {
        if (!fits) {
            throw new IllegalArgumentException(
                    "Not a " + what + " (" + shape + "), " + value.length() + " characters");
        }
        return value;
    }

    static String quoted(final String value) {
        final String shown = value.length() > 40 ? value.substring(0, 40) + "..." : value;
        return "'" + shown.replaceAll("\\p{Cntrl}", "?") + "'";
    }
}
