package org.fuin.porelo.planner.store;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a store could not read in a story or an issue it still hands on: the board shows the item,
 * and this beside it.
 *
 * @param kind an open name of what is wrong, such as {@code StateOutsideWorkflow}; a later store
 *     may name a kind of its own
 * @param text one fixed sentence saying what is wrong, which never repeats a person's words
 * @param concerns what of the item it is about, which decides whether work may start on it
 */
public record Warning(String kind, String text, Set<Concern> concerns) {

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
        TEXT
    }

    /** What a warning may be about and still let work start: its state, parent, waits or id. */
    private static final Set<Concern> HOLDING =
            EnumSet.of(Concern.STATE, Concern.PARENT, Concern.BLOCKERS, Concern.ID);

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
     * Whether work never starts on an item with this warning, nor a move or an edit, since it is
     * about the item's state, parent, waits or id, which a hand edit could otherwise slip past a
     * work rule.
     *
     * @return true when it holds the item back
     */
    public boolean holdsWork() {
        return concerns.stream().anyMatch(HOLDING::contains);
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
     * Every warning the store API names, one of each kind.
     *
     * @return them, in a fixed order
     */
    public static List<Warning> named() {
        return List.of(
                stateOutsideWorkflow(),
                noState(),
                noPriority(),
                idTwice(),
                parentOutsideProject(),
                blockerOutsideProject(),
                issueOutsideProject(),
                wrongParent(),
                bodyNotSectioned());
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
