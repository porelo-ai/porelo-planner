package org.fuin.porelo.planner.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.UnlistedIssue;

/**
 * Checks the edits a person keeps the board with by hand - an issue's fields, a story's issues, an
 * issue's blockers, a comment - against the board the caller read, before the store writes
 * anything; a refusal never repeats what was sent beyond the board's own ids.
 */
public final class Edits {

    /** The refusal's name the window reads. */
    public static final String REFUSED = "EditNotAllowed";

    /** The refusal's name of an add of an issue the board does not offer. */
    public static final String NOT_UNLISTED = "NotUnlisted";

    /** The most issues one story's list may hold. */
    public static final int MOST_ISSUES = 100;

    /** The most blockers one issue may have. */
    public static final int MOST_BLOCKERS = 50;

    /** How every comment the planner writes as its own record opens. */
    public static final String MARKER = "<!-- porelo:";

    /** The most code points one comment may have. */
    public static final int MOST_COMMENT = 65536;

    /** The most code points a title may have. */
    public static final int MOST_TITLE = 256;

    /** The most code points one part of a text may have, as the longest body GitHub takes. */
    public static final int MOST_PART = 65536;

    /**
     * Anything that breaks one line: a C0 or C1 control character, NEL or a line or paragraph
     * separator.
     */
    private static final java.util.regex.Pattern BREAKS =
            java.util.regex.Pattern.compile("[\\x00-\\x1F\\x7F-\\x9F\\u2028\\u2029]");

    private Edits() {}

    /**
     * Checks only this write's targets against the versions the caller saw. Missing versions are
     * reported together before any changed item; extra basis entries do not affect the write.
     *
     * @param board the board the store holds
     * @param basis the versions the caller saw
     * @param targets the ids determined by {@link Targets}
     * @throws StoreException.BasisIncomplete for every target without a version
     * @throws StoreException.Changed for targets changed, absent or duplicated on the board
     */
    public static void unchanged(final Board board, final Basis basis, final Set<String> targets) {
        basis.unchanged(board, targets);
    }

    /**
     * Checks an add of an issue made outside the project: the board the store holds offers it among
     * the issues that are no items of the project.
     *
     * @param board the board the store holds
     * @param issue the issue's tracker reference
     * @return the issue as the board offers it
     * @throws Moves.Refused {@value #NOT_UNLISTED} for an issue the board does not offer, one on
     *     the board or outside its workflow among them
     */
    public static UnlistedIssue unlisted(final Board board, final String issue) {
        for (final UnlistedIssue offered : board.unlisted()) {
            if (offered.id().equals(issue)) {
                return offered;
            }
        }
        throw notUnlisted(issue);
    }

    /**
     * A refusal of an add of an issue the board does not offer, or no longer offers: the window
     * reads the board again.
     *
     * @param issue the issue's tracker reference
     * @return the refusal
     */
    public static Moves.Refused notUnlisted(final String issue) {
        return new Moves.Refused(
                NOT_UNLISTED,
                "The issue " + issue + " is not offered to add to the project.",
                Map.of("issue", issue));
    }

    /**
     * Checks a change of an issue's fields: the issue stands on the board the store holds.
     *
     * @param board the board the store holds
     * @param issue the issue's id
     * @param fields the fields to set
     * @throws Moves.Refused {@code NoSuchIssue} for an issue the board lacks, {@value #REFUSED} for
     *     a change that names no field
     */
    public static void fields(final Board board, final String issue, final Fields fields) {
        known(board, issue);
        notHeld(board, issue);
        if (fields.none()) {
            throw refused(issue, "", "The body names no field to set.");
        }
    }

    /**
     * Checks a story's new issue list: every issue named once, on the board, held by no other story
     * and no sub-issue of an issue, since an issue belongs to one story; an issue hung in or taken
     * out is held back by nothing.
     *
     * @param board the board the store holds
     * @param story the story's id
     * @param issues the issues' ids, in the story's order
     * @return whether the list differs from the story's, so anything is written
     * @throws Moves.Refused {@code NoSuchStory} or {@code NoSuchIssue} for an item the board lacks,
     *     {@value #REFUSED} for a list the board cannot hold
     */
    public static boolean storyIssues(
            final Board board, final String story, final List<String> issues) {
        final Story found = story(board, story);
        notHeld(board, story);
        if (!found.issuesWhole()) {
            // An issue the board left out would be dropped from a list written whole.
            throw refused(story, "issues", "The board does not show every issue of the story.");
        }
        if (issues.size() > MOST_ISSUES) {
            throw refused(story, "issues", "A story holds at most " + MOST_ISSUES + " issues.");
        }
        final Map<String, String> holder = new HashMap<>();
        for (final Story other : board.stories()) {
            if (!other.id().equals(story)) {
                other.issues().forEach(id -> holder.putIfAbsent(id, other.id()));
            }
        }
        for (final String id : found.issues()) {
            if (!issues.contains(id)) {
                // Taken out, it would lose its story on GitHub, a write on an item held back.
                notHeld(board, id, story, "issues");
            }
        }
        final Set<String> named = new HashSet<>();
        for (final String id : issues) {
            final Issue issue = known(board, id);
            if (!named.add(id)) {
                throw refused(story, "issues", "The issue " + id + " is named twice.");
            }
            if (found.issues().contains(id)) {
                continue;
            }
            notHeld(board, id, story, "issues");
            final String other = holder.get(id);
            if (other != null) {
                throw refused(
                        story,
                        "issues",
                        "The issue " + id + " belongs to " + other + "; an issue has one story.");
            }
            if (issue.parent() != null) {
                throw refused(
                        story,
                        "issues",
                        "The issue " + id + " is a sub-issue of " + issue.parent() + ".");
            }
        }
        return !found.issues().equals(issues);
    }

    /**
     * Checks an issue's new blockers: each named once, on the board, not the issue itself, none
     * leading back to the issue through the blockers the board holds, and none added or taken out
     * held back.
     *
     * @param board the board the store holds
     * @param issue the issue's id
     * @param blockedBy the blockers' ids
     * @return whether the blockers differ from the issue's, so anything is written
     * @throws Moves.Refused {@code NoSuchIssue} for an issue the board lacks, {@value #REFUSED} for
     *     blockers the board cannot hold
     */
    public static boolean blockedBy(
            final Board board, final String issue, final List<String> blockedBy) {
        final Issue found = known(board, issue);
        notHeld(board, issue);
        // GitHub counts the issues it waits on outside the board among its blockers too.
        final int outside = found.waitsOn().size();
        if (blockedBy.size() + outside > MOST_BLOCKERS) {
            throw refused(
                    issue,
                    "blockedBy",
                    "An issue has at most "
                            + MOST_BLOCKERS
                            + " blockers"
                            + (outside == 0
                                    ? "."
                                    : ", the "
                                            + outside
                                            + " it waits on outside the board included."));
        }
        final Set<String> named = new HashSet<>();
        for (final String id : blockedBy) {
            known(board, id);
            if (!named.add(id)) {
                throw refused(issue, "blockedBy", "The blocker " + id + " is named twice.");
            }
            if (id.equals(issue)) {
                throw refused(issue, "blockedBy", "An issue does not block itself.");
            }
            if (!found.blockedBy().contains(id)) {
                notHeld(board, id, issue, "blockedBy");
            }
        }
        for (final String id : found.blockedBy()) {
            if (!named.contains(id)) {
                notHeld(board, id, issue, "blockedBy");
            }
        }
        final Map<String, List<String>> waits = new LinkedHashMap<>();
        board.issues().forEach(each -> waits.put(each.id(), each.blockedBy()));
        waits.put(issue, List.copyOf(blockedBy));
        for (final String blocker : blockedBy) {
            if (reaches(waits, blocker, issue)) {
                throw refused(
                        issue,
                        "blockedBy",
                        "The blockers lead back to the issue through " + blocker + ".");
            }
        }
        return !Set.copyOf(found.blockedBy()).equals(named);
    }

    /**
     * Checks a change of a story's or an issue's title and named parts: the item stands on the
     * board the store holds, the change names anything, the title is one line of 1 to {@value
     * #MOST_TITLE} characters without control characters, and each part written is not blank and at
     * most {@value #MOST_PART} characters. A task at work on the issue does not refuse it: its
     * result is checked against the versions it read, as every write is.
     *
     * @param board the board the store holds
     * @param id the story's or the issue's id
     * @param edit the change
     * @return the change with its title without the spaces around it
     * @throws Moves.Refused {@code NoSuchStory} or {@code NoSuchIssue} for an item the board lacks,
     *     {@value #REFUSED} for a change that names nothing or a title or part out of bounds
     */
    public static TextEdit text(final Board board, final String id, final TextEdit edit) {
        if (edit.kind() == TextEdit.Kind.STORY) {
            story(board, id);
        } else {
            known(board, id);
        }
        notHeld(board, id);
        if (edit.none()) {
            throw refused(id, "", "The body names no title and no part to write.");
        }
        String title = edit.title();
        if (title != null) {
            if (BREAKS.matcher(title).find()) {
                throw refused(
                        id, "title", "The title must be one line without control characters.");
            }
            title = title.strip();
            if (title.isEmpty() || title.codePointCount(0, title.length()) > MOST_TITLE) {
                throw refused(
                        id, "title", "The title must have 1 to " + MOST_TITLE + " characters.");
            }
        }
        for (final Map.Entry<TextEdit.Part, String> part : edit.written().entrySet()) {
            final String text = part.getValue();
            if (text.isBlank() || text.codePointCount(0, text.length()) > MOST_PART) {
                throw refused(
                        id,
                        member(part.getKey()),
                        "A part must have 1 to " + MOST_PART + " characters and not be blank.");
            }
        }
        return new TextEdit(edit.kind(), title, edit.written(), edit.removed());
    }

    /**
     * The member of a text edit's body that names a part.
     *
     * @param part the part
     * @return the member's name
     */
    public static String member(final TextEdit.Part part) {
        return switch (part) {
            case COVERS -> "covers";
            case WHY_THIS_SIZE -> "whyThisSize";
            case WHAT_MUST_BE_TRUE -> "whatMustBeTrue";
            case PLAN -> "plan";
            case ACCEPTANCE -> "acceptance";
            case OPEN_QUESTIONS -> "openQuestions";
            case TO_BE_CHECKED -> "toBeChecked";
            case FINDINGS -> "findings";
            case GAIN -> "gain";
            case TEST -> "test";
        };
    }

    /**
     * Checks a comment: its issue stands on the board, and its text is not blank and at most
     * {@value #MOST_COMMENT} characters.
     *
     * @param board the board the store holds
     * @param issue the issue's id
     * @param text the comment's text
     * @throws Moves.Refused {@code NoSuchIssue} for an issue the board lacks, {@value #REFUSED} for
     *     a text out of bounds
     */
    public static void comment(final Board board, final String issue, final String text) {
        known(board, issue);
        if (text.isBlank() || text.codePointCount(0, text.length()) > MOST_COMMENT) {
            throw refused(
                    issue,
                    "text",
                    "The comment must have 1 to " + MOST_COMMENT + " characters and not be blank.");
        }
        if (text.strip().startsWith(MARKER)) {
            // The planner reads its own records from comments that open with its marker.
            throw refused(issue, "text", "A comment does not open with the planner's own marker.");
        }
    }

    /**
     * A refusal of an edit.
     *
     * @param item the story's or the issue's id the edit is about, or empty
     * @param field the member of the body it is about, or empty
     * @param reason why, in fixed words
     * @return the refusal
     */
    public static Moves.Refused refused(
            final String item, final String field, final String reason) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", item);
        parameters.put("field", field);
        parameters.put("reason", reason);
        return new Moves.Refused(REFUSED, reason, parameters);
    }

    /**
     * Refuses an edit of an item held back by its warnings or an id it shares, so a hand edit
     * cannot be built on by the planner's own writes.
     */
    private static void notHeld(final Board board, final String id) {
        final Optional<String> held = board.holdsWork(id);
        if (held.isPresent()) {
            throw refused(id, "", held.get());
        }
    }

    /**
     * Refuses an edit of another item's list that adds or takes out an item held back, which a
     * write would link or unlink on GitHub; one kept in the list is neither.
     */
    private static void notHeld(
            final Board board, final String id, final String edited, final String field) {
        final Optional<String> held = board.holdsWork(id);
        if (held.isPresent()) {
            throw refused(edited, field, held.get());
        }
    }

    private static Story story(final Board board, final String id) {
        twice(board, id);
        return board.story(id)
                .orElseThrow(
                        () ->
                                new Moves.Refused(
                                        "NoSuchStory",
                                        "There is no story " + id + ".",
                                        Map.of("story", id)));
    }

    /** Refuses an id that names two items, which no edit can tell apart. */
    private static void twice(final Board board, final String id) {
        final Optional<String> twice = board.twice(id);
        if (twice.isPresent()) {
            throw refused(id, "", twice.get());
        }
    }

    private static Issue known(final Board board, final String id) {
        twice(board, id);
        return board.issue(id)
                .orElseThrow(
                        () ->
                                new Moves.Refused(
                                        "NoSuchIssue",
                                        "There is no issue " + id + ".",
                                        Map.of("issue", id)));
    }

    /** Whether one issue waits on another through the blockers, however deep. */
    private static boolean reaches(
            final Map<String, List<String>> waits, final String from, final String to) {
        final Set<String> seen = new HashSet<>();
        final Deque<String> open = new ArrayDeque<>(List.of(from));
        while (!open.isEmpty()) {
            final String at = open.pop();
            if (at.equals(to)) {
                return true;
            }
            if (seen.add(at)) {
                open.addAll(waits.getOrDefault(at, List.of()));
            }
        }
        return false;
    }
}
