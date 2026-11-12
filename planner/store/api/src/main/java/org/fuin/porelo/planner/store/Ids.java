package org.fuin.porelo.planner.store;

import java.util.regex.Pattern;

/** The shapes an id or a name must have before the planner lets it reach a path or a command. */
public final class Ids {

    private static final Pattern STORY = Pattern.compile("ST[0-9]{1,5}");

    private static final Pattern ISSUE = Pattern.compile("[A-Z]{1,4}[0-9]{1,5}(\\.[0-9]{1,3})?");

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");

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
