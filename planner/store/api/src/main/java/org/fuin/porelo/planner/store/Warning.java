package org.fuin.porelo.planner.store;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a store could not read in a story or an issue it still hands on: the board shows the item,
 * and this beside it.
 *
 * @param kind an open name of what is wrong, such as {@code StateOutsideWorkflow}; a later store
 *     may name a kind of its own
 * @param text one fixed sentence saying what is wrong, which never repeats a person's words
 * @param concerns what of the item it is about, which decides whether work may start on it
 * @param correction the one change the store offers to set it right, or null when it offers none
 */
public record Warning(
        String kind, String text, Set<Concern> concerns, @Nullable Correction correction) {

    private static final Pattern KIND = Pattern.compile("[A-Z][A-Za-z]{0,63}");

    /** What of an item a warning is about. */
    public enum Concern {
        /** Its state: none is known, or the one it states is none of the workflow's. */
        STATE,
        /** Its parent. */
        PARENT,
        /** The issues it waits on. */
        BLOCKERS,
        /** Its id, which names another item too. */
        ID,
        /** The issues a story names. */
        ISSUES,
        /** Its priority. */
        PRIORITY,
        /** Its text. */
        TEXT,
        /** What it is: a story or an issue. */
        KIND,
        /** Who works it. */
        WORKER,
        /** How much harm its change can do. */
        RISK,
        /** The issue a story stands under, which no story does on the board. */
        STORY_PARENT,
        /** Its size. */
        SIZE,
        /** Its number fields. */
        FIGURES,
        /** That GitHub has it open while its state is a closed one. */
        OPEN,
        /** That GitHub has it closed while its state is an open one. */
        CLOSED
    }

    /**
     * What a warning that holds work back is about: the state, parent, waits, id, kind, worker or
     * risk.
     */
    private static final Set<Concern> HOLDING =
            EnumSet.of(
                    Concern.STATE,
                    Concern.PARENT,
                    Concern.BLOCKERS,
                    Concern.ID,
                    Concern.KIND,
                    Concern.WORKER,
                    Concern.RISK);

    /**
     * What a warning that holds back only the start of work is about: an item GitHub has closed,
     * which a move into a closed state sets right.
     */
    private static final Set<Concern> HOLDING_START = EnumSet.of(Concern.CLOSED);

    /** A field's name a warning may name: the planner's own word, never a person's text. */
    private static final Pattern FIELD = Pattern.compile("[A-Z][A-Za-z ]{0,39}");

    /** Checks the kind's spelling and copies the concerns. */
    public Warning {
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException("Not a warning's kind, " + kind.length() + " chars");
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("A warning " + kind + " says nothing");
        }
        if (concerns.isEmpty()) {
            throw new IllegalArgumentException("A warning " + kind + " is about nothing");
        }
        concerns = Set.copyOf(concerns);
    }

    /**
     * A warning without a correction.
     *
     * @param kind an open name of what is wrong
     * @param text one fixed sentence saying what is wrong
     * @param concerns what of the item it is about
     */
    public Warning(final String kind, final String text, final Set<Concern> concerns) {
        this(kind, text, concerns, null);
    }

    /**
     * This warning with the correction a store offers for it.
     *
     * @param offered the correction, or null for none
     * @return the warning
     */
    public Warning offering(final @Nullable Correction offered) {
        return new Warning(kind, text, concerns, offered);
    }

    /**
     * An item's warnings as the board shows them: a correction stands only while no other warning
     * of the item holds it back, since that one would refuse the correction's write.
     *
     * @param warnings an item's warnings
     * @return them, in order, a correction left out where another warning holds the item
     */
    static List<Warning> offerable(final List<Warning> warnings) {
        final List<Warning> shown = new ArrayList<>(warnings.size());
        for (int at = 0; at < warnings.size(); at++) {
            final Warning warning = warnings.get(at);
            boolean held = false;
            for (int other = 0; other < warnings.size(); other++) {
                held |= other != at && warnings.get(other).holdsWork();
            }
            shown.add(warning.correction() != null && held ? warning.offering(null) : warning);
        }
        return List.copyOf(shown);
    }

    /**
     * Whether work never starts on an item with this warning, nor a move or an edit, since it is
     * about the item's state, parent, waits, id, kind, worker or risk, which a hand edit could
     * otherwise slip past a work rule or a careful gate.
     *
     * @return true when it holds the item back
     */
    public boolean holdsWork() {
        return concerns.stream().anyMatch(HOLDING::contains);
    }

    /**
     * Whether no work is proposed or started on an item with this warning: what {@link
     * #holdsWork()} holds, and an item GitHub has closed under an open state, which a move or an
     * edit may still name.
     *
     * @return true when it holds back the start of work
     */
    public boolean holdsStart() {
        return holdsWork() || concerns.stream().anyMatch(HOLDING_START::contains);
    }

    /**
     * Whether it is about the item's state, so the board takes a state the workflow does not know.
     *
     * @return true when it concerns the state
     */
    public boolean aboutState() {
        return concerns.contains(Concern.STATE);
    }

    /** The kind of a state that is none of the workflow's. */
    public static final String STATE_OUTSIDE_WORKFLOW = "StateOutsideWorkflow";

    /** The kind of a story for which no state can be told. */
    public static final String NO_STATE = "NoState";

    /** The kind of an item without a priority. */
    public static final String NO_PRIORITY = "NoPriority";

    /** The kind of an id that names another item too. */
    public static final String ID_TWICE = "IdTwice";

    /** The kind of a parent outside the project. */
    public static final String PARENT_OUTSIDE_PROJECT = "ParentOutsideProject";

    /** The kind of a blocker outside the project. */
    public static final String BLOCKER_OUTSIDE_PROJECT = "BlockerOutsideProject";

    /** The kind of an issue a story names that is outside the project. */
    public static final String ISSUE_OUTSIDE_PROJECT = "IssueOutsideProject";

    /** The kind of a sub-issue under another parent than its id names. */
    public static final String WRONG_PARENT = "WrongParent";

    /** The kind of a text without the sections the planner reads. */
    public static final String BODY_NOT_SECTIONED = "BodyNotSectioned";

    /** The kind of an issue marked a story outside the repository that holds the stories. */
    public static final String STORY_OUTSIDE_OWN_REPOSITORY = "StoryOutsideOwnRepository";

    /** The kind of a sub-issue in another repository than its parent. */
    public static final String PARENT_IN_OTHER_REPOSITORY = "ParentInOtherRepository";

    /** The kind of an issue blocked by a story. */
    public static final String BLOCKED_BY_STORY = "BlockedByStory";

    /** The kind of an issue whose parents lead back to it. */
    public static final String PARENT_CIRCLE = "ParentCircle";

    /** The kind of a sub-issue of a sub-issue. */
    public static final String SUB_ISSUE_OF_SUB_ISSUE = "SubIssueOfSubIssue";

    /** The kind of an item with more links, labels or values than one page of GitHub's answer. */
    public static final String PAST_ONE_PAGE = "PastOnePage";

    /** The kind of an issue whose parent or blocker the board cannot place. */
    public static final String DEPENDS_ON_UNPLACED = "DependsOnUnplaced";

    /** The kind of an issue whose worker is none the planner knows. */
    public static final String UNKNOWN_WORKER = "UnknownWorker";

    /** The kind of an issue whose risk is none the planner knows. */
    public static final String UNKNOWN_RISK = "UnknownRisk";

    /** The kind of a story standing under an issue. */
    public static final String STORY_UNDER_ISSUE = "StoryUnderIssue";

    /** The kind of a story with a line under Also needs that names no issue the board shows. */
    public static final String ALSO_NEEDS_NOT_ON_BOARD = "AlsoNeedsNotOnBoard";

    /** The kind of an item whose text is longer than GitHub takes. */
    public static final String BODY_TOO_LONG = "BodyTooLong";

    /** The kind of an item whose size is none the planner knows. */
    public static final String UNKNOWN_SIZE = "UnknownSize";

    /** The kind of an item whose priority is none the planner knows. */
    public static final String UNKNOWN_PRIORITY = "UnknownPriority";

    /** The kind of an issue with a number field that holds no number in the range it reads. */
    public static final String UNREADABLE_NUMBER = "UnreadableNumber";

    /** The kind of an item GitHub has closed while its state is an open one. */
    public static final String CLOSED_UNDER_OPEN_STATE = "ClosedUnderOpenState";

    /** The kind of an item GitHub has open while its state is a closed one. */
    public static final String OPEN_UNDER_CLOSED_STATE = "OpenUnderClosedState";

    /**
     * An item whose state is none of the workflow's; the board shows it in a column of its own.
     *
     * @return the warning
     */
    public static Warning stateOutsideWorkflow() {
        return new Warning(
                STATE_OUTSIDE_WORKFLOW,
                "Its state is none of the workflow's states, so no work starts on it.",
                Set.of(Concern.STATE));
    }

    /**
     * A story that states no state and whose issues give none.
     *
     * @return the warning
     */
    public static Warning noState() {
        return new Warning(
                NO_STATE,
                "No state can be told for it: it states none, and its issues give none.",
                Set.of(Concern.STATE));
    }

    /**
     * An item without a priority, sorted as Medium.
     *
     * @return the warning
     */
    public static Warning noPriority() {
        return new Warning(
                NO_PRIORITY,
                "It has no priority, so it is sorted as Medium.",
                Set.of(Concern.PRIORITY));
    }

    /**
     * An item whose id names another item too; each copy carries it.
     *
     * @return the warning
     */
    public static Warning idTwice() {
        return new Warning(
                ID_TWICE,
                "Its id names another item too, so it can be neither worked nor moved until one id"
                        + " is changed.",
                Set.of(Concern.ID));
    }

    /**
     * An item whose id names another item too, naming where each copy is kept, so the person
     * changes one id by hand there.
     *
     * @param places each copy's file and repository, as words; none gives {@link #idTwice()}
     * @return the warning
     */
    public static Warning idTwice(final List<String> places) {
        if (places.isEmpty()) {
            return idTwice();
        }
        return new Warning(
                ID_TWICE,
                "Its id names another item too, so it can be neither worked nor moved until one id"
                        + " is changed by hand; it is kept in "
                        + spoken(places)
                        + ".",
                Set.of(Concern.ID));
    }

    /**
     * An issue whose parent is outside the project, dropped from it.
     *
     * @return the warning
     */
    public static Warning parentOutsideProject() {
        return new Warning(
                PARENT_OUTSIDE_PROJECT,
                "Its parent is outside the project and is not shown.",
                Set.of(Concern.PARENT));
    }

    /**
     * An issue waiting on an issue outside the project, dropped from it.
     *
     * @return the warning
     */
    public static Warning blockerOutsideProject() {
        return new Warning(
                BLOCKER_OUTSIDE_PROJECT,
                "An issue it waits on is outside the project and is not shown.",
                Set.of(Concern.BLOCKERS));
    }

    /**
     * A story naming an issue that is no issue of the project's repositories, dropped from it.
     *
     * @return the warning
     */
    public static Warning issueOutsideProject() {
        return new Warning(
                ISSUE_OUTSIDE_PROJECT,
                "It names an issue that is no issue of the project's repositories, which is not"
                        + " shown.",
                Set.of(Concern.ISSUES));
    }

    /**
     * A story naming an issue that is no issue of the project's repositories, naming where the
     * story is kept, so the person removes or mends the reference by hand there.
     *
     * @param places the story's file and repository, as words, for each file of its id; none gives
     *     {@link #issueOutsideProject()}
     * @return the warning
     */
    public static Warning issueOutsideProject(final List<String> places) {
        if (places.isEmpty()) {
            return issueOutsideProject();
        }
        return new Warning(
                ISSUE_OUTSIDE_PROJECT,
                "It names an issue that is no issue of the project's repositories, which is not"
                        + " shown; it is set right by hand in "
                        + spoken(places)
                        + ".",
                Set.of(Concern.ISSUES));
    }

    /**
     * Where a store keeps an item, as a warning names it.
     *
     * @param path the item's file, relative to its repository, or null when it has none
     * @param repository the repository's name
     * @return the words
     */
    public static String place(final @Nullable String path, final String repository) {
        return path == null ? "the index of " + repository : path + " of " + repository;
    }

    /** Names joined as words: {@code A and B}, {@code A, B and C}. */
    private static String spoken(final List<String> names) {
        if (names.size() < 2) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    /**
     * A sub-issue standing under another parent than its id names.
     *
     * @return the warning
     */
    public static Warning wrongParent() {
        return new Warning(
                WRONG_PARENT,
                "It stands under another parent than its id names.",
                Set.of(Concern.PARENT));
    }

    /**
     * An item whose text lacks the sections the planner reads.
     *
     * @return the warning
     */
    public static Warning bodyNotSectioned() {
        return new Warning(
                BODY_NOT_SECTIONED,
                "Its text has no sections the planner can read; it is shown as it stands.",
                Set.of(Concern.TEXT));
    }

    /**
     * An issue marked a story outside the repository that holds the stories, shown as an issue.
     *
     * @return the warning
     */
    public static Warning storyOutsideOwnRepository() {
        return new Warning(
                STORY_OUTSIDE_OWN_REPOSITORY,
                "It is marked a story outside the repository that holds the stories, so it is"
                        + " shown as an issue and no work starts on it.",
                Set.of(Concern.KIND));
    }

    /**
     * A sub-issue in another repository than its parent, shown without its parent.
     *
     * @return the warning
     */
    public static Warning parentInOtherRepository() {
        return new Warning(
                PARENT_IN_OTHER_REPOSITORY,
                "Its parent is in another repository, so it is shown without its parent.",
                Set.of(Concern.PARENT));
    }

    /**
     * An issue blocked by a story, shown without that blocker.
     *
     * @return the warning
     */
    public static Warning blockedByStory() {
        return new Warning(
                BLOCKED_BY_STORY,
                "It is blocked by a story, which only an issue can block, so it is shown without"
                        + " that blocker.",
                Set.of(Concern.BLOCKERS));
    }

    /**
     * An issue whose parents lead back to it, shown without its parent.
     *
     * @return the warning
     */
    public static Warning parentCircle() {
        return new Warning(
                PARENT_CIRCLE,
                "Its parents lead back to it, so it is shown without its parent.",
                Set.of(Concern.PARENT));
    }

    /**
     * A sub-issue of a sub-issue, shown without its parent.
     *
     * @return the warning
     */
    public static Warning subIssueOfSubIssue() {
        return new Warning(
                SUB_ISSUE_OF_SUB_ISSUE,
                "Its parent is a sub-issue itself, which the board cannot hold, so it is shown"
                        + " without its parent.",
                Set.of(Concern.PARENT));
    }

    /**
     * An item with more sub-issues, blockers, labels or field values than one page of the tracker's
     * answer carries, shown as far as it was read.
     *
     * @return the warning
     */
    public static Warning pastOnePage() {
        return new Warning(
                PAST_ONE_PAGE,
                "It has more sub-issues, blockers, labels or field values than one page carries, so"
                        + " it is shown as far as it was read.",
                Set.of(Concern.STATE, Concern.BLOCKERS, Concern.ISSUES));
    }

    /**
     * An issue whose parent or blocker the board does not place, shown without that link.
     *
     * @return the warning
     */
    public static Warning dependsOnUnplaced() {
        return new Warning(
                DEPENDS_ON_UNPLACED,
                "An issue it stands under or waits on is not placed on the board, so it is shown"
                        + " without that link.",
                Set.of(Concern.PARENT, Concern.BLOCKERS));
    }

    /**
     * An issue whose worker is none the planner knows, shown as an agent's and held, so no agent
     * starts on what a person may have meant to work.
     *
     * @return the warning
     */
    public static Warning unknownWorker() {
        return new Warning(
                UNKNOWN_WORKER,
                "Its worker is none the planner knows, so no work starts on it until one it knows is"
                        + " set.",
                Set.of(Concern.WORKER));
    }

    /**
     * An issue whose risk is none the planner knows, read as the highest and held, so no task
     * starts on it, or on the work above it, with less care than was meant.
     *
     * @return the warning
     */
    public static Warning unknownRisk() {
        return new Warning(
                UNKNOWN_RISK,
                "Its risk is none the planner knows, so no work starts on it until one it knows is"
                        + " set.",
                Set.of(Concern.RISK));
    }

    /**
     * A story standing under an issue, shown without it.
     *
     * @return the warning
     */
    public static Warning storyUnderIssue() {
        return new Warning(
                STORY_UNDER_ISSUE,
                "It stands under an issue, which a story never does, so it is shown without it.",
                Set.of(Concern.STORY_PARENT));
    }

    /**
     * A story with a line under Also needs that names no issue the board shows, shown without it.
     *
     * @return the warning
     */
    public static Warning alsoNeedsNotOnBoard() {
        return new Warning(
                ALSO_NEEDS_NOT_ON_BOARD,
                "A line under Also needs names no issue the board shows, so it is shown without"
                        + " it.",
                Set.of(Concern.ISSUES));
    }

    /**
     * An item whose text is longer than GitHub takes, shown without it.
     *
     * @return the warning
     */
    public static Warning bodyTooLong() {
        return new Warning(
                BODY_TOO_LONG,
                "Its text is longer than GitHub takes, so it is shown without it.",
                Set.of(Concern.TEXT));
    }

    /**
     * An item whose size is none the planner knows, shown without one.
     *
     * @return the warning
     */
    public static Warning unknownSize() {
        return new Warning(
                UNKNOWN_SIZE,
                "Its size is none the planner knows, so it is shown without one.",
                Set.of(Concern.SIZE));
    }

    /**
     * An item whose priority is none the planner knows, sorted as Medium.
     *
     * @return the warning
     */
    public static Warning unknownPriority() {
        return new Warning(
                UNKNOWN_PRIORITY,
                "Its priority is none the planner knows, so it is sorted as Medium.",
                Set.of(Concern.PRIORITY));
    }

    /**
     * An issue with a number field that holds no number in the range the planner reads: the board
     * counts it as none, and no stage is saved over it.
     *
     * @param field the field's name, as the planner names it
     * @return the warning
     * @throws IllegalArgumentException when the name is no plain field name
     */
    public static Warning unreadableNumber(final String field) {
        if (!FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("Not a field's name, " + field.length() + " chars");
        }
        return new Warning(
                UNREADABLE_NUMBER,
                "Its field "
                        + field
                        + " holds no number in the range the planner reads, so the board counts it"
                        + " as none and saves no stage over it.",
                Set.of(Concern.FIGURES));
    }

    /**
     * An item GitHub has closed while its state is an open one; the state counts, no work is
     * proposed or started on it, and the planner reopens it only on a move out of a closed state.
     *
     * @return the warning
     */
    public static Warning closedUnderOpenState() {
        return new Warning(
                CLOSED_UNDER_OPEN_STATE,
                "It is closed on GitHub while its state is an open one, so no work starts on it;"
                        + " the board goes by its state, and only a move out of a closed state"
                        + " reopens it.",
                Set.of(Concern.CLOSED));
    }

    /**
     * An item GitHub has open while its state is a closed one; the state counts.
     *
     * @return the warning
     */
    public static Warning openUnderClosedState() {
        return new Warning(
                OPEN_UNDER_CLOSED_STATE,
                "It is open on GitHub while its state is a closed one; the board goes by its"
                        + " state.",
                Set.of(Concern.OPEN));
    }

    /**
     * Whether any of the warnings is about the state.
     *
     * @param warnings an item's warnings
     * @return true when one is
     */
    static boolean anyAboutState(final List<Warning> warnings) {
        return warnings.stream().anyMatch(Warning::aboutState);
    }

    /**
     * Whether the warnings name the kind.
     *
     * @param warnings an item's warnings
     * @param kind the kind
     * @return true when one is of it
     */
    static boolean has(final List<Warning> warnings, final String kind) {
        return warnings.stream().anyMatch(warning -> warning.kind().equals(kind));
    }
}
