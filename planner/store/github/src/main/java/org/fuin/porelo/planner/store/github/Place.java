package org.fuin.porelo.planner.store.github;

import java.util.Comparator;
import java.util.Objects;
import org.fuin.porelo.planner.store.TextEdit;
import org.jspecify.annotations.Nullable;

/**
 * A place on a project item that a command writes or its judgment read: a change there since the
 * command was judged is a conflict, a change anywhere else is merged.
 *
 * @param kind what the place is
 * @param name the label, field or body part it names, or null for a kind that names none and for
 *     the whole body
 */
record Place(Kind kind, @Nullable String name) implements Comparable<Place> {

    /** What a place is. */
    enum Kind {
        /** The item itself: its ids, repository, number, issue type, and whether it is archived. */
        ITEM,
        /** An issue's {@code Status}, whether it is open and why it closed, as one place. */
        STATE,
        /** The {@code Worker} field. */
        WORKER,
        /** The priority. */
        PRIORITY,
        /** One topic label. */
        LABEL,
        /** The title. */
        TITLE,
        /** One part of the body, or the whole body when it names none. */
        BODY,
        /** The blockers. */
        BLOCKERS,
        /** The parent. */
        PARENT,
        /** The sub-issues, and a story's issues under {@code Also needs} in its body. */
        SUB_ISSUES,
        /** Another field, by its name. */
        FIELD
    }

    private static final Comparator<Place> ORDER =
            Comparator.comparing(Place::kind)
                    .thenComparing(Place::name, Comparator.nullsFirst(Comparator.naturalOrder()));

    static final Place ITEM = new Place(Kind.ITEM, null);
    static final Place STATE = new Place(Kind.STATE, null);
    static final Place WORKER = new Place(Kind.WORKER, null);
    static final Place PRIORITY = new Place(Kind.PRIORITY, null);
    static final Place TITLE = new Place(Kind.TITLE, null);
    static final Place WHOLE_BODY = new Place(Kind.BODY, null);
    static final Place BLOCKERS = new Place(Kind.BLOCKERS, null);
    static final Place PARENT = new Place(Kind.PARENT, null);
    static final Place SUB_ISSUES = new Place(Kind.SUB_ISSUES, null);

    /** Checks that exactly the kinds that name something name it. */
    Place {
        final boolean named = kind == Kind.LABEL || kind == Kind.FIELD;
        if (named && name == null || !named && kind != Kind.BODY && name != null) {
            throw new IllegalArgumentException("The place " + kind + " is named wrongly.");
        }
    }

    /** One topic label, by its name. */
    static Place label(final String name) {
        return new Place(Kind.LABEL, Objects.requireNonNull(name));
    }

    /** A field other than those with a place of their own, by its name. */
    static Place field(final String name) {
        return new Place(Kind.FIELD, Objects.requireNonNull(name));
    }

    /** One part of the body. */
    static Place body(final TextEdit.Part part) {
        return new Place(Kind.BODY, part.name());
    }

    @Override
    public int compareTo(final Place other) {
        return ORDER.compare(this, other);
    }
}
