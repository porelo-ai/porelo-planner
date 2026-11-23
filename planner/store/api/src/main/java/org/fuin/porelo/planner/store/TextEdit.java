package org.fuin.porelo.planner.store;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A change of a story's or an issue's title, named parts of its text and topic labels: each part
 * named is written or removed, every other byte of the text stays as it was, and a label outside
 * the topic labels stays on the item.
 *
 * @param kind whether a story or an issue is changed
 * @param title the new title, or null to leave it
 * @param written the parts to write, each with its new text without its lead or heading
 * @param removed the parts to remove with their lead or heading
 * @param labels the topic labels the item carries after the change, in the order of {@link
 *     TopicLabels#NAMES}, or null to leave its labels
 */
public record TextEdit(
        Kind kind,
        @Nullable String title,
        Map<Part, String> written,
        Set<Part> removed,
        @Nullable List<String> labels) {

    /** What a text edit changes. */
    public enum Kind {
        /** A story. */
        STORY,
        /** An issue. */
        ISSUE
    }

    /** The named parts of a text, each of one kind of item. */
    public enum Part {
        /** The first paragraph, which says what the issue covers. */
        COVERS(Kind.ISSUE, false),
        /** One line on why the issue has its size. */
        WHY_THIS_SIZE(Kind.ISSUE, true),
        /** What must be true once the issue is done. */
        WHAT_MUST_BE_TRUE(Kind.ISSUE, false),
        /** The plan. */
        PLAN(Kind.ISSUE, true),
        /** How the issue is judged done. */
        ACCEPTANCE(Kind.ISSUE, true),
        /** What a person must decide. */
        OPEN_QUESTIONS(Kind.ISSUE, true),
        /** What is still to be measured, read or confirmed. */
        TO_BE_CHECKED(Kind.ISSUE, true),
        /** What a stage found. */
        FINDINGS(Kind.ISSUE, true),
        /** What a user gains from the story. */
        GAIN(Kind.STORY, true),
        /** How the story is tested. */
        TEST(Kind.STORY, true);

        private final Kind of;

        private final boolean removable;

        Part(final Kind of, final boolean removable) {
            this.of = of;
            this.removable = removable;
        }

        /**
         * Answers the kind of item the part belongs to.
         *
         * @return a story's or an issue's
         */
        public Kind of() {
            return of;
        }

        /**
         * Answers whether the part may be removed; what an issue covers and what must be true of it
         * may not.
         *
         * @return whether a removal is taken
         */
        public boolean removable() {
            return removable;
        }
    }

    /**
     * Copies the parts and checks that each belongs to the kind, none is both written and removed,
     * and none removed may not be.
     *
     * @throws IllegalArgumentException for a part of the other kind, written and removed at once,
     *     or not removable, or a label outside the topic labels or named twice
     */
    public TextEdit {
        if (labels != null) {
            labels = TopicLabels.topic(labels);
        }
        written = written.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(written));
        removed = removed.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(removed));
        for (final Part part : written.keySet()) {
            if (part.of() != kind || removed.contains(part)) {
                throw new IllegalArgumentException("The part " + part + " is not written here.");
            }
        }
        for (final Part part : removed) {
            if (part.of() != kind || !part.removable()) {
                throw new IllegalArgumentException("The part " + part + " is not removed here.");
            }
        }
    }

    /**
     * A change of the title and named parts that leaves the labels.
     *
     * @param kind whether a story or an issue is changed
     * @param title the new title, or null to leave it
     * @param written the parts to write
     * @param removed the parts to remove
     */
    public TextEdit(
            final Kind kind,
            final @Nullable String title,
            final Map<Part, String> written,
            final Set<Part> removed) {
        this(kind, title, written, removed, null);
    }

    /**
     * Answers whether nothing is changed.
     *
     * @return whether no title, no part and no labels are named
     */
    public boolean none() {
        return title == null && written.isEmpty() && removed.isEmpty() && labels == null;
    }

    /**
     * Answers whether the text is changed: a title or a part is named.
     *
     * @return whether the title or a part is named
     */
    public boolean text() {
        return title != null || !written.isEmpty() || !removed.isEmpty();
    }

    /**
     * The refusal of a text the store cannot write as asked, in fixed words that never repeat the
     * text: a heading, a lead or an open block in it, a body grown past what the store takes, or a
     * body whose parts cannot be told apart.
     */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String item;

        /**
         * Names the item and why.
         *
         * @param item the story's or the issue's id
         * @param reason why, in fixed words
         */
        public Refused(final String item, final String reason) {
            super(reason);
            this.item = item;
        }

        /**
         * Answers the item refused.
         *
         * @return the story's or the issue's id
         */
        public String item() {
            return item;
        }
    }
}
