package org.fuin.porelo.planner.store;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A story as its store holds it. An open story has no state of its own: it stands where its issues
 * stand, and it ends by being closed.
 *
 * @param id the story's id, {@code ST} and a number, or a tracker reference {@code
 *     <repository>#<number>}
 * @param title its title
 * @param state its state, one of the workflow's: while it is open, the one its issues give it,
 *     never a closed one; once closed, the closed state it was closed into
 * @param priority its priority
 * @param size the size given when it was estimated, {@code XS} to {@code XL}, or null when its head
 *     gives none; its own, never added up from its issues' sizes
 * @param issues the ids of the issues it needs, in its order
 * @param description its body, the text below the head
 * @param link where a person reads it on the forge, if known
 * @param issuesWhole whether {@code issues} holds every issue its head names; false when the store
 *     left one out, so nothing is read from its issues as if they were all
 * @param closedAt when it was closed as completed, the day its test passed, or null while it is not
 *     closed so or its store keeps no such time
 * @param warnings what the store could not read in it; its state may be none of the workflow's, or
 *     empty, only with a warning about the state; a warning's correction stands only while no other
 *     of them holds the story back
 * @param version its own stored edit basis, as its store keeps it; a state its issues give is not
 *     part of it
 * @param labels its labels on the tracker, in the store's order, each once: the topic labels and
 *     any label of the person's own; empty for a store that holds none
 */
public record Story(
        String id,
        String title,
        String state,
        String priority,
        @Nullable String size,
        List<String> issues,
        String description,
        @Nullable URI link,
        boolean issuesWhole,
        @Nullable Instant closedAt,
        List<Warning> warnings,
        Version version,
        List<String> labels) {

    /** The priorities a story may have, the highest first. */
    public static final List<String> PRIORITIES =
            List.of("Highest", "High", "Medium", "Low", "Lowest");

    /** Checks the ids and copies the list. */
    public Story {
        Ids.story(id);
        labels = TopicLabels.checked(id, labels);
        issues = List.copyOf(issues);
        issues.forEach(Ids::issue);
        warnings = Warning.offerable(warnings);
    }

    /**
     * A story whose version is taken over its own stored values, for a store that keeps no edit
     * basis of its own; a state its issues give is left out of it.
     *
     * @param id the story's id
     * @param title its title
     * @param state its state
     * @param stateStored whether its store keeps the state as the story's own, as a closed story's;
     *     false for a state its issues give
     * @param priority its priority
     * @param size its size, or null
     * @param issues the ids of the issues it needs, in its order
     * @param description its body
     * @param link where a person reads it, if known
     * @param issuesWhole whether {@code issues} holds every issue its head names
     * @param closedAt when it was closed as completed, or null
     * @param warnings what the store could not read in it
     */
    public Story(
            final String id,
            final String title,
            final String state,
            final boolean stateStored,
            final String priority,
            final @Nullable String size,
            final List<String> issues,
            final String description,
            final @Nullable URI link,
            final boolean issuesWhole,
            final @Nullable Instant closedAt,
            final List<Warning> warnings) {
        this(
                id,
                title,
                state,
                stateStored,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                closedAt,
                warnings,
                List.of());
    }

    /**
     * A story whose version is taken over its own stored values, its labels among them, for a store
     * that keeps no edit basis of its own; a story without labels has the version it had before
     * labels were kept.
     *
     * @param id the story's id
     * @param title its title
     * @param state its state
     * @param stateStored whether its store keeps the state as the story's own
     * @param priority its priority
     * @param size its size, or null
     * @param issues the ids of the issues it needs, in its order
     * @param description its body
     * @param link where a person reads it, if known
     * @param issuesWhole whether {@code issues} holds every issue its head names
     * @param closedAt when it was closed as completed, or null
     * @param warnings what the store could not read in it
     * @param labels its labels, in the store's order
     */
    public Story(
            final String id,
            final String title,
            final String state,
            final boolean stateStored,
            final String priority,
            final @Nullable String size,
            final List<String> issues,
            final String description,
            final @Nullable URI link,
            final boolean issuesWhole,
            final @Nullable Instant closedAt,
            final List<Warning> warnings,
            final List<String> labels) {
        this(
                id,
                title,
                state,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                closedAt,
                warnings,
                labels.isEmpty()
                        ? Version.of(
                                "story",
                                id,
                                title,
                                stateStored ? state : null,
                                priority,
                                size,
                                issues,
                                description,
                                link)
                        : Version.of(
                                "story",
                                id,
                                title,
                                stateStored ? state : null,
                                priority,
                                size,
                                issues,
                                description,
                                link,
                                labels),
                labels);
    }

    /**
     * A story without labels with the version its store gives.
     *
     * @param id the story's id
     * @param title its title
     * @param state its state
     * @param priority its priority
     * @param size its size, or null
     * @param issues the ids of the issues it needs, in its order
     * @param description its body
     * @param link where a person reads it, if known
     * @param issuesWhole whether {@code issues} holds every issue its head names
     * @param closedAt when it was closed as completed, or null
     * @param warnings what the store could not read in it
     * @param version its own stored edit basis
     */
    public Story(
            final String id,
            final String title,
            final String state,
            final String priority,
            final @Nullable String size,
            final List<String> issues,
            final String description,
            final @Nullable URI link,
            final boolean issuesWhole,
            final @Nullable Instant closedAt,
            final List<Warning> warnings,
            final Version version) {
        this(
                id,
                title,
                state,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                closedAt,
                warnings,
                version,
                List.of());
    }

    /**
     * An open story whose state its issues give, without a closing time, its version taken over its
     * own stored values.
     *
     * @param id the story's id
     * @param title its title
     * @param state the state its issues give
     * @param priority its priority
     * @param size its size, or null
     * @param issues the ids of the issues it needs, in its order
     * @param description its body
     * @param link where a person reads it, if known
     * @param issuesWhole whether {@code issues} holds every issue its head names
     * @param warnings what the store could not read in it
     */
    public Story(
            final String id,
            final String title,
            final String state,
            final String priority,
            final @Nullable String size,
            final List<String> issues,
            final String description,
            final @Nullable URI link,
            final boolean issuesWhole,
            final List<Warning> warnings) {
        this(
                id,
                title,
                state,
                false,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                null,
                warnings);
    }

    /**
     * An open story whose state its issues give, without warnings or a closing time.
     *
     * @param id the story's id
     * @param title its title
     * @param state the state its issues give
     * @param priority its priority
     * @param size its size, or null
     * @param issues the ids of the issues it needs, in its order
     * @param description its body
     * @param link where a person reads it, if known
     * @param issuesWhole whether {@code issues} holds every issue its head names
     */
    public Story(
            final String id,
            final String title,
            final String state,
            final String priority,
            final @Nullable String size,
            final List<String> issues,
            final String description,
            final @Nullable URI link,
            final boolean issuesWhole) {
        this(id, title, state, priority, size, issues, description, link, issuesWhole, List.of());
    }

    /**
     * An open story whose issues are every one its head names.
     *
     * @param id the story's id
     * @param title its title
     * @param state the state its issues give
     * @param priority its priority
     * @param size its size, or null
     * @param issues the ids of the issues it needs, in its order
     * @param description its body
     * @param link where a person reads it, if known
     */
    public Story(
            final String id,
            final String title,
            final String state,
            final String priority,
            final @Nullable String size,
            final List<String> issues,
            final String description,
            final @Nullable URI link) {
        this(id, title, state, priority, size, issues, description, link, true);
    }

    /**
     * This story with another version, as its store reads it.
     *
     * @param version its own stored edit basis
     * @return the story with that version
     */
    public Story withVersion(final Version version) {
        return new Story(
                id,
                title,
                state,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                closedAt,
                warnings,
                version,
                labels);
    }

    /**
     * This story with another priority.
     *
     * @param priority its priority
     * @return the story
     */
    public Story withPriority(final String priority) {
        return new Story(
                id,
                title,
                state,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                closedAt,
                warnings,
                version,
                labels);
    }

    /**
     * This story with other warnings.
     *
     * @param warnings its warnings
     * @return the story
     */
    public Story withWarnings(final List<Warning> warnings) {
        return new Story(
                id,
                title,
                state,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                closedAt,
                warnings,
                version,
                labels);
    }

    /**
     * This story carrying other labels, its version as it was.
     *
     * @param labels its labels, in the store's order
     * @return the story
     */
    public Story withLabels(final List<String> labels) {
        return new Story(
                id,
                title,
                state,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                closedAt,
                warnings,
                version,
                labels);
    }
}
