package org.fuin.porelo.planner.store;

import java.util.regex.Pattern;

/** The shapes an id or a name must have before the planner lets it reach a path or a command. */
public final class Ids {

    private static final Pattern STORY = Pattern.compile("ST[0-9]{1,5}");

    private static final Pattern ISSUE = Pattern.compile("[A-Z]{1,4}[0-9]{1,5}(\\.[0-9]{1,3})?");

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

    private static final Pattern TASK = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    private Ids() {}

    /**
     * Refuses a story id that is not {@code ST} and a number.
     *
     * @param id the id to check
     * @return the id
     */
    public static String story(final String id) {
        return checked(STORY, id, "story id");
    }

    /**
     * Refuses an issue id that is not a prefix of capitals, a number and an optional sub-issue
     * number.
     *
     * @param id the id to check
     * @return the id
     */
    public static String issue(final String id) {
        return checked(ISSUE, id, "issue id");
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
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Not a " + what + " (" + pattern.pattern() + "): " + quoted(value));
        }
        return value;
    }

    static String quoted(final String value) {
        final String shown = value.length() > 40 ? value.substring(0, 40) + "..." : value;
        return "'" + shown.replaceAll("\\p{Cntrl}", "?") + "'";
    }
}
