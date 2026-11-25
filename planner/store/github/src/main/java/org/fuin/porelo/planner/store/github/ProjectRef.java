package org.fuin.porelo.planner.store.github;

import java.util.regex.Pattern;

/**
 * The GitHub project that holds a Porelo project's fields, as {@code planner.yml}'s {@code tracker}
 * names it.
 *
 * @param owner the organization or user that owns the project
 * @param number the project's number within its owner
 */
public record ProjectRef(String owner, int number) {

    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})");

    /** The most digits a project's number takes, so a value never overflows an {@code int}. */
    private static final int MOST_DIGITS = 9;

    /** Refuses an owner GitHub would not name and a number below one. */
    public ProjectRef {
        if (!OWNER.matcher(owner).matches()) {
            throw new IllegalArgumentException(
                    "Not a GitHub owner ("
                            + OWNER.pattern()
                            + "), "
                            + owner.length()
                            + " characters");
        }
        if (number < 1) {
            throw new IllegalArgumentException("A GitHub project's number is 1 or more");
        }
    }

    /**
     * Reads {@code <owner>/<number>}.
     *
     * @param text the text, as {@code project.yml} writes it
     * @return the project
     * @throws IllegalArgumentException if it is not of that form
     */
    public static ProjectRef parse(final String text) {
        final int slash = text.indexOf('/');
        if (slash < 1 || slash != text.lastIndexOf('/')) {
            throw new IllegalArgumentException("A GitHub project is named as <owner>/<number>");
        }
        final String digits = text.substring(slash + 1);
        if (digits.isEmpty()
                || digits.length() > MOST_DIGITS
                || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("A GitHub project's number is a whole number");
        }
        return new ProjectRef(text.substring(0, slash), Integer.parseInt(digits));
    }
}
