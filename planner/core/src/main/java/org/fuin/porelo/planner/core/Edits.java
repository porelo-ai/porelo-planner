package org.fuin.porelo.planner.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;

/**
 * Checks the edits a person keeps the board with by hand - an issue's fields, a story's issues, an
 * issue's blockers, a comment - against the board the caller read, before the store writes
 * anything; a refusal never repeats what was sent beyond the board's own ids.
 */
public final class Edits {

    /** The refusal's name the window reads. */
    public static final String REFUSED = "EditNotAllowed";

    /** The most issues one story's list may hold. */
    public static final int MOST_ISSUES = 100;

    /** The most blockers one issue may have. */
    public static final int MOST_BLOCKERS = 50;

    /** How every comment the planner writes as its own record opens. */
    public static final String MARKER = "<!-- porelo:";

    /** The most code points one comment may have. */
    public static final int MOST_COMMENT = 65536;

    /** The most code points a title may have. */
    public static final int MOST_TITLE = 120;

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
     * Refuses an edit against a revision the board no longer holds.
     *
     * @param board the board the store holds
     * @param revision the revision the caller read
     * @throws StoreException.StaleRevision naming the board's revision when they differ
     */
    public static void fresh(final Board board, final String revision) {
        if (!board.revision().value().equals(revision)) {
            throw new StoreException.StaleRevision(board.revision().value());
        }
    }

    /**
     * Checks a change of an issue's fields: the issue stands on the board read at that revision.
     *
     * @param board the board the store holds
     * @param revision the revision the caller read
     * @param issue the issue's id
     * @param fields the fields to set
     * @throws StoreException.StaleRevision when the board moved since that revision
     * @throws Moves.Refused {@code NoSuchIssue} for an issue the board lacks, {@value #REFUSED} for
     *     a change that names no field
     */
    public static void fields(
            final Board board, final String revision, final String issue, final Fields fields) {
        fresh(board, revision);
        known(board, issue);
        if (fields.none()) {
            throw refused(issue, "", "The body names no field to set.");
        }
    }

    /**
     * Checks a story's new issue list: every issue named once, on the board, held by no other story
     * and no sub-issue of an issue, since an issue belongs to one story.
     *
     * @param board the board the store holds
     * @param revision the revision the caller read
     * @param story the story's id
     * @param issues the issues' ids, in the story's order
     * @return whether the list differs from the story's, so anything is written
     * @throws StoreException.StaleRevision when the board moved since that revision
     * @throws Moves.Refused {@code NoSuchStory} or {@code NoSuchIssue} for an item the board lacks,
     *     {@value #REFUSED} for a list the board cannot hold
     */
    public static boolean storyIssues(
            final Board board,
            final String revision,
            final String story,
            final List<String> issues) {
        fresh(board, revision);
        final Story found =
                board.story(story)
                        .orElseThrow(
                                () ->
                                        new Moves.Refused(
                                                "NoSuchStory",
                                                "There is no story " + story + ".",
                                                Map.of("story", story)));
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
        final Set<String> named = new HashSet<>();
        for (final String id : issues) {
            final Issue issue = known(board, id);
            if (!named.add(id)) {
                throw refused(story, "issues", "The issue " + id + " is named twice.");
            }
            if (found.issues().contains(id)) {
                continue;
            }
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
     * Checks an issue's new blockers: each named once, on the board, not the issue itself, and none
     * leading back to the issue through the blockers the board holds.
     *
     * @param board the board the store holds
     * @param revision the revision the caller read
     * @param issue the issue's id
     * @param blockedBy the blockers' ids
     * @return whether the blockers differ from the issue's, so anything is written
     * @throws StoreException.StaleRevision when the board moved since that revision
     * @throws Moves.Refused {@code NoSuchIssue} for an issue the board lacks, {@value #REFUSED} for
     *     blockers the board cannot hold
     */
    public static boolean blockedBy(
            final Board board,
            final String revision,
            final String issue,
            final List<String> blockedBy) {
        fresh(board, revision);
        final Issue found = known(board, issue);
        if (blockedBy.size() > MOST_BLOCKERS) {
            throw refused(
                    issue, "blockedBy", "An issue has at most " + MOST_BLOCKERS + " blockers.");
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
     * board read at that revision, the change names anything, the title is one line of 1 to {@value
     * #MOST_TITLE} characters without control characters, and each part written is not blank and at
     * most {@value #MOST_PART} characters. A task at work on the issue does not refuse it: its
     * result is checked against the revision it read, as every write is.
     *
     * @param board the board the store holds
     * @param revision the revision the caller read
     * @param id the story's or the issue's id
     * @param edit the change
     * @return the change with its title without the spaces around it
     * @throws StoreException.StaleRevision when the board moved since that revision
     * @throws Moves.Refused {@code NoSuchStory} or {@code NoSuchIssue} for an item the board lacks,
     *     {@value #REFUSED} for a change that names nothing or a title or part out of bounds
     */
    public static TextEdit text(
            final Board board, final String revision, final String id, final TextEdit edit) {
        fresh(board, revision);
        if (edit.kind() == TextEdit.Kind.STORY) {
            board.story(id)
                    .orElseThrow(
                            () ->
                                    new Moves.Refused(
                                            "NoSuchStory",
                                            "There is no story " + id + ".",
                                            Map.of("story", id)));
        } else {
            known(board, id);
        }
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

    private static Issue known(final Board board, final String id) {
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
