package org.fuin.porelo.planner.store.cache;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One item of a tracked project's board, as its tracker gave it: the item, the issue it holds and
 * the values of the board's fields.
 *
 * @param id the item's own id at the tracker
 * @param archived whether the tracker keeps it off the board
 * @param issue the issue it holds, or null for an item that holds none
 * @param values the values of the board's fields, in the tracker's order
 * @param updatedAt when the tracker last changed the item itself, beside its issue, or null when it
 *     did not say
 * @param partial whether the read that gave it held only part of its labels or field values, so it
 *     is never taken as complete and is read again at the next look
 * @param valueCount how many field values the tracker counts on the item, which may be more than
 *     the values it gave
 */
public record TrackedItem(
        String id,
        boolean archived,
        @Nullable Issue issue,
        List<Value> values,
        @Nullable Instant updatedAt,
        boolean partial,
        int valueCount) {

    /** Requires the id and copies the values. */
    public TrackedItem {
        Objects.requireNonNull(id, "id");
        values = List.copyOf(values);
        if (valueCount < 0) {
            throw new IllegalArgumentException("An item counts no values below none");
        }
    }

    /**
     * An issue as the board shows it.
     *
     * @param id its own id at the tracker
     * @param key the tracker's second, numeric id of it, or 0 where it has none
     * @param number its number in its repository
     * @param title its title
     * @param body its text, or null when it has none
     * @param state its state at the tracker, such as {@code OPEN} or {@code CLOSED}
     * @param stateReason why it was closed, or null
     * @param updatedAt when the tracker last changed it
     * @param repository its repository, as the tracker names it
     * @param labels its labels, in the tracker's order
     * @param parentId the id of the issue it is a sub-issue of, or null
     * @param subIssues its sub-issues
     * @param blockedBy the issues that block it
     * @param type the name of its issue type at the tracker, or null when it has none
     * @param closedAt when the tracker closed it, or null when it never did
     * @param choices the options chosen in its single-select issue fields, in the tracker's order
     * @param labelCount how many labels the tracker counts, which may be more than it named
     */
    public record Issue(
            String id,
            long key,
            int number,
            String title,
            @Nullable String body,
            String state,
            @Nullable String stateReason,
            Instant updatedAt,
            String repository,
            List<String> labels,
            @Nullable String parentId,
            Links subIssues,
            Links blockedBy,
            @Nullable String type,
            @Nullable Instant closedAt,
            List<Choice> choices,
            int labelCount) {

        /** Requires every part the tracker always gives and copies the labels and choices. */
        public Issue {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(repository, "repository");
            labels = List.copyOf(labels);
            Objects.requireNonNull(subIssues, "subIssues");
            Objects.requireNonNull(blockedBy, "blockedBy");
            choices = List.copyOf(choices);
            if (labelCount < 0) {
                throw new IllegalArgumentException("An issue counts no labels below none");
            }
        }

        /**
         * An issue the tracker never closed.
         *
         * @param id its own id at the tracker
         * @param key the tracker's second, numeric id of it, or 0 where it has none
         * @param number its number in its repository
         * @param title its title
         * @param body its text, or null when it has none
         * @param state its state at the tracker
         * @param stateReason why it was closed, or null
         * @param updatedAt when the tracker last changed it
         * @param repository its repository, as the tracker names it
         * @param labels its labels, in the tracker's order
         * @param parentId the id of the issue it is a sub-issue of, or null
         * @param subIssues its sub-issues
         * @param blockedBy the issues that block it
         * @param type the name of its issue type at the tracker, or null when it has none
         * @param choices the options chosen in its single-select issue fields, in the tracker's
         *     order
         */
        public Issue(
                final String id,
                final long key,
                final int number,
                final String title,
                final @Nullable String body,
                final String state,
                final @Nullable String stateReason,
                final Instant updatedAt,
                final String repository,
                final List<String> labels,
                final @Nullable String parentId,
                final Links subIssues,
                final Links blockedBy,
                final @Nullable String type,
                final List<Choice> choices) {
            this(
                    id,
                    key,
                    number,
                    title,
                    body,
                    state,
                    stateReason,
                    updatedAt,
                    repository,
                    labels,
                    parentId,
                    subIssues,
                    blockedBy,
                    type,
                    null,
                    choices,
                    labels.size());
        }

        /**
         * An issue the tracker never closed, with no chosen option in an issue field.
         *
         * @param id its own id at the tracker
         * @param key the tracker's second, numeric id of it, or 0 where it has none
         * @param number its number in its repository
         * @param title its title
         * @param body its text, or null when it has none
         * @param state its state at the tracker
         * @param stateReason why it was closed, or null
         * @param updatedAt when the tracker last changed it
         * @param repository its repository, as the tracker names it
         * @param labels its labels, in the tracker's order
         * @param parentId the id of the issue it is a sub-issue of, or null
         * @param subIssues its sub-issues
         * @param blockedBy the issues that block it
         * @param type the name of its issue type at the tracker, or null when it has none
         */
        public Issue(
                final String id,
                final long key,
                final int number,
                final String title,
                final @Nullable String body,
                final String state,
                final @Nullable String stateReason,
                final Instant updatedAt,
                final String repository,
                final List<String> labels,
                final @Nullable String parentId,
                final Links subIssues,
                final Links blockedBy,
                final @Nullable String type) {
            this(
                    id,
                    key,
                    number,
                    title,
                    body,
                    state,
                    stateReason,
                    updatedAt,
                    repository,
                    labels,
                    parentId,
                    subIssues,
                    blockedBy,
                    type,
                    null,
                    List.of(),
                    labels.size());
        }

        /**
         * An issue of no issue type and no chosen option in an issue field, which the tracker never
         * closed.
         *
         * @param id its own id at the tracker
         * @param key the tracker's second, numeric id of it, or 0 where it has none
         * @param number its number in its repository
         * @param title its title
         * @param body its text, or null when it has none
         * @param state its state at the tracker
         * @param stateReason why it was closed, or null
         * @param updatedAt when the tracker last changed it
         * @param repository its repository, as the tracker names it
         * @param labels its labels, in the tracker's order
         * @param parentId the id of the issue it is a sub-issue of, or null
         * @param subIssues its sub-issues
         * @param blockedBy the issues that block it
         */
        public Issue(
                final String id,
                final long key,
                final int number,
                final String title,
                final @Nullable String body,
                final String state,
                final @Nullable String stateReason,
                final Instant updatedAt,
                final String repository,
                final List<String> labels,
                final @Nullable String parentId,
                final Links subIssues,
                final Links blockedBy) {
            this(
                    id,
                    key,
                    number,
                    title,
                    body,
                    state,
                    stateReason,
                    updatedAt,
                    repository,
                    labels,
                    parentId,
                    subIssues,
                    blockedBy,
                    null,
                    null,
                    List.of(),
                    labels.size());
        }
    }

    /**
     * The option chosen in one of an issue's single-select issue fields.
     *
     * @param field the field's name
     * @param option the chosen option's name
     */
    public record Choice(String field, String option) {

        /** Requires both names. */
        public Choice {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(option, "option");
        }
    }

    /**
     * The issues an issue names, as far as the tracker listed them.
     *
     * @param links the issues
     * @param more whether the tracker holds more than it listed
     * @param after where the tracker's list goes on, or null
     * @param total how many the tracker holds, more than it listed when {@code more}
     */
    public record Links(List<Link> links, boolean more, @Nullable String after, int total) {

        /** Copies the issues. */
        public Links {
            links = List.copyOf(links);
        }
    }

    /**
     * An issue another names.
     *
     * @param id its own id at the tracker
     * @param key the tracker's second, numeric id of it, or 0 where it has none
     * @param number its number in its repository
     * @param repository its repository, as the tracker names it
     */
    public record Link(String id, long key, int number, String repository) {

        /** Requires the id and the repository. */
        public Link {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(repository, "repository");
        }
    }

    /**
     * The value of one field of the board on the item.
     *
     * @param fieldId the field's id at the tracker
     * @param optionId the option chosen, for a field of options
     * @param name the option's name, for a field of options
     * @param text the text, for a text field
     * @param number the number, for a number field
     */
    public record Value(
            String fieldId,
            @Nullable String optionId,
            @Nullable String name,
            @Nullable String text,
            @Nullable Double number) {

        /** Requires the field. */
        public Value {
            Objects.requireNonNull(fieldId, "fieldId");
        }
    }
}
