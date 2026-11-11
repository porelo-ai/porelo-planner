package org.fuin.porelo.planner.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIssue;
import org.jspecify.annotations.Nullable;

/**
 * Checks a new issue a person wrote against the board a window shows, before the store writes a
 * file from it; a refusal names the field and never repeats what was sent.
 */
public final class NewIssues {

    /** The refusal's name the window reads. */
    public static final String REFUSED = "IssueNotAllowed";

    /**
     * The most characters a title may have, as an issue's head in {@code issue-1.json} holds it.
     */
    public static final int MOST_TITLE = 120;

    /** The most characters what must be true may have. */
    public static final int MOST_TEXT = 4000;

    /** The sizes an issue may be given. */
    public static final List<String> SIZES = List.of("XS", "S", "M", "L", "XL");

    /** Anything that would break a title's one line: a control character or a line separator. */
    private static final Pattern BREAKS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    private NewIssues() {}

    /**
     * Checks the fields and answers the issue the store writes.
     *
     * @param board the board the window shows
     * @param repository the repository's name
     * @param title the title
     * @param mustBeTrue what must be true
     * @param size the size, or null
     * @return the issue, its title without the spaces around it
     * @throws Moves.Refused {@code IssueNotAllowed} naming the first field out of bounds
     */
    public static NewIssue check(
            final Board board,
            final @Nullable String repository,
            final @Nullable String title,
            final @Nullable String mustBeTrue,
            final @Nullable String size) {
        if (repository == null || !board.repositories().contains(repository)) {
            throw refused("repository", "The repository is no repository of the project.");
        }
        if (title == null || BREAKS.matcher(title).find()) {
            throw refused("title", "The title must be one line without control characters.");
        }
        final String line = title.strip();
        if (line.isEmpty() || line.length() > MOST_TITLE) {
            throw refused("title", "The title must have 1 to " + MOST_TITLE + " characters.");
        }
        if (mustBeTrue == null || mustBeTrue.isBlank() || mustBeTrue.length() > MOST_TEXT) {
            throw refused(
                    "mustBeTrue", "What must be true must have 1 to " + MOST_TEXT + " characters.");
        }
        if (size != null && !SIZES.contains(size)) {
            throw refused("size", "The size must be one of " + String.join(", ", SIZES) + ".");
        }
        return new NewIssue(repository, line, mustBeTrue, size);
    }

    /**
     * A refusal of a new issue, as the window reads it.
     *
     * @param field the field it names, or empty when it is about none
     * @param reason the words the window shows
     * @return the refusal
     */
    public static Moves.Refused refused(final String field, final String reason) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("field", field);
        parameters.put("reason", reason);
        return new Moves.Refused(REFUSED, reason, parameters);
    }
}
