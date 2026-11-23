package org.fuin.porelo.planner.store.github;

import java.util.Collection;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;

/**
 * The texts and ranks of a new story or issue, as every store that keeps GitHub's item shape writes
 * them: the body's sections, the one-line checks and the rank after the last item of a priority.
 */
public final class NewItemTexts {

    private static final Pattern BREAKS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    private NewItemTexts() {}

    /**
     * A new issue's body: what must be true as its lead's paragraph followed by the task that
     * proposed it, or, for a text the lead cannot hold, the task's paragraph followed by a section
     * of its own; refused in fixed words when even a section cannot hold the text.
     *
     * @param issue what the person wrote
     * @return the body
     * @throws StoreException.NotSavable when the text cannot be held
     */
    public static String issueBody(final NewIssue issue) {
        line(issue.title(), "the title of a new issue");
        final String what;
        try {
            what = BodySections.read("").withWhatMustBeTrue(issue.mustBeTrue()).text();
        } catch (final BodySections.TooLong e) {
            throw tooLong();
        } catch (final IllegalArgumentException e) {
            throw new StoreException.NotSavable(
                    "",
                    "what must be true holds a heading of the first or second level or leaves a"
                            + " code block open");
        }
        final String task = issue.task();
        final String proposed = "Proposed by Porelo task `" + task + "`.";
        final String body;
        if (task == null) {
            body = what;
        } else if (BodySections.read(what)
                .section(BodySections.WHAT_MUST_BE_TRUE_SECTION)
                .isPresent()) {
            body = proposed + "\n\n" + what;
        } else {
            body = what.strip() + "\n\n" + proposed + "\n";
        }
        if (body.length() > BodySections.LONGEST) {
            throw tooLong();
        }
        if (BodySections.read(body).ambiguity().isPresent()) {
            throw new StoreException.NotSavable(
                    "", "what must be true would leave the new issue's sections unclear");
        }
        return body;
    }

    /**
     * A new story's body: its gain and its test as a carried story holds them, ending in a mark
     * that tells it from every story not made by the same request.
     *
     * @param idea the story; its issues are not read
     * @param marker the request's marker, one line, or empty for none
     * @return the body
     * @throws StoreException.NotSavable when a line is blank or breaks, or the priority is unknown
     */
    public static String storyBody(final NewIdea idea, final String marker) {
        line(idea.title(), "the title of a new story");
        line(idea.gain(), "what a user gains");
        line(idea.test(), "the test of a new story");
        priority(idea.priority());
        return "**What a user gains.** "
                + idea.gain().strip()
                + "\n\n**The test.** "
                + idea.test().strip()
                + "\n"
                + (marker.isEmpty() ? "" : "\n" + marker + "\n");
    }

    private static StoreException.NotSavable tooLong() {
        return new StoreException.NotSavable(
                "",
                "what must be true and the new issue's other text are longer than the "
                        + BodySections.LONGEST
                        + " characters a GitHub issue's body takes",
                "mustBeTrue");
    }

    /**
     * Refuses a priority none of {@link Story#PRIORITIES}.
     *
     * @param priority the priority
     * @return it
     * @throws StoreException.NotSavable when it is none of them
     */
    public static String priority(final String priority) {
        if (!Story.PRIORITIES.contains(priority)) {
            throw new StoreException.NotSavable("", "a priority is Highest to Lowest");
        }
        return priority;
    }

    /**
     * Refuses a blank text or one holding a line break or a control character.
     *
     * @param text the text
     * @param what what it is, in the refusal's words
     * @throws StoreException.NotSavable when it is no single line
     */
    public static void line(final String text, final String what) {
        if (text.isBlank() || BREAKS.matcher(text).find()) {
            throw new StoreException.NotSavable(
                    "", what + " is blank or holds a line break or a control character");
        }
    }

    /**
     * The highest rank among the items of a kind and priority; an item without a priority counts as
     * Medium, and a rank no number in range gives none to follow.
     *
     * @param items the items
     * @param story whether stories are counted, or issues
     * @param priority the priority
     * @return the highest rank, or 0 when none holds one
     */
    public static int highestRank(
            final Collection<ItemView> items, final boolean story, final String priority) {
        int most = 0;
        for (final ItemView item : items) {
            if (IssueMapping.isStory(item) != story) {
                continue;
            }
            final String given = item.fields().getOrDefault(FieldNames.PRIORITY, "");
            final String its = Story.PRIORITIES.contains(given) ? given : "Medium";
            if (!its.equals(priority)) {
                continue;
            }
            try {
                final double rank =
                        Double.parseDouble(item.fields().getOrDefault(FieldNames.RANK, ""));
                if (Double.isFinite(rank) && rank >= 1 && rank <= IssueMapping.MOST_RANK) {
                    most = Math.max(most, (int) rank);
                }
            } catch (final NumberFormatException e) {
                // A rank no number sorts last and gives none to follow.
            }
        }
        return most;
    }
}
