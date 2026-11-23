package org.fuin.porelo.planner.store;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A story as its head and body state it.
 *
 * @param id the story's id, {@code ST} and a number, or a tracker reference {@code
 *     <repository>#<number>}
 * @param title its title
 * @param state its state, one of the workflow's
 * @param stateGiven whether its head states the state, rather than its issues giving it
 * @param priority its priority
 * @param size the size given when it was estimated, {@code XS} to {@code XL}, or null when its head
 *     gives none; its own, never added up from its issues' sizes
 * @param issues the ids of the issues it needs, in its order
 * @param description its body, the text below the head
 * @param link where a person reads it on the forge, if known
 * @param issuesWhole whether {@code issues} holds every issue its head names; false when the store
 *     left one out, so nothing is read from its issues as if they were all
 * @param warnings what the store could not read in it; its state may be none of the workflow's, or
 *     empty, only with a warning about the state; a warning's correction stands only while no other
 *     of them holds the story back
 * @param version its own stored edit basis, as its store keeps it; a state its issues give is not
 *     part of it
 */
public record Story(
        String id,
        String title,
        String state,
        boolean stateGiven,
        String priority,
        @Nullable String size,
        List<String> issues,
        String description,
        @Nullable URI link,
        boolean issuesWhole,
        List<Warning> warnings,
        Version version) {

    /** The priorities a story may have, the highest first. */
    public static final List<String> PRIORITIES =
            List.of("Highest", "High", "Medium", "Low", "Lowest");

    /** Checks the ids and copies the list. */
    public Story {
        Ids.story(id);
        issues = List.copyOf(issues);
        issues.forEach(Ids::issue);
        warnings = Warning.offerable(warnings);
    }

    /**
     * A story without warnings.
     *
     * @param id the story's id
     * @param title its title
     * @param state its state
     * @param stateGiven whether its head states the state
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
            final boolean stateGiven,
            final String priority,
            final @Nullable String size,
            final List<String> issues,
            final String description,
            final @Nullable URI link,
            final boolean issuesWhole) {
        this(
                id,
                title,
                state,
                stateGiven,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                List.of());
    }

    /**
     * A story whose version is taken over its own stored values, for a store that keeps no edit
     * basis of its own; a state its issues give is left out.
     */
    public Story(
            final String id,
            final String title,
            final String state,
            final boolean stateGiven,
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
                stateGiven,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                warnings,
                Version.of(
                        "story",
                        id,
                        title,
                        stateGiven ? state : null,
                        priority,
                        size,
                        issues,
                        description,
                        link));
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
                stateGiven,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                warnings,
                version);
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
                stateGiven,
                priority,
                size,
                issues,
                description,
                link,
                issuesWhole,
                warnings,
                version);
    }

    /**
     * A story whose issues are every one its head names.
     *
     * @param id the story's id
     * @param title its title
     * @param state its state
     * @param stateGiven whether its head states the state
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
            final boolean stateGiven,
            final String priority,
            final @Nullable String size,
            final List<String> issues,
            final String description,
            final @Nullable URI link) {
        this(id, title, state, stateGiven, priority, size, issues, description, link, true);
    }
}
