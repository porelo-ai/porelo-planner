package org.fuin.porelo.planner.store;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An issue as its repository's index and its head state it.
 *
 * @param id the issue's id, its prefix and number, and for a sub-issue its parent's id, a dot and a
 *     number; or a tracker reference {@code <repository>#<number>} naming its own repository
 * @param repository the name of the repository that holds it
 * @param title its title
 * @param state its state, one of the workflow's
 * @param size its size, if it has one yet
 * @param blockedBy the ids of the issues of the board it waits on
 * @param parent its parent's id, for a sub-issue
 * @param link where a person reads it on the forge, if known
 * @param text its whole text in the store's format, its title and head included; empty when its
 *     file is gone
 * @param worker who works it; a head that names none is worked by an agent
 * @param risk how much harm its change can do; a head that names none is ordinary, and a sub-issue
 *     is worked with the higher of its own and its parent's
 * @param estimate the minutes it is expected to take, if it was estimated
 * @param minutes the minutes its work took so far, if the store keeps them
 * @param priority its priority, one of {@link Story#PRIORITIES}, or null when the store holds none
 *     or one outside them
 * @param warnings what the store could not read in it; its state may be none of the workflow's only
 *     with a warning about the state; a warning's correction stands only while no other of them
 *     holds the issue back
 * @param waitsOn the issues it waits on that the board does not hold, such as those of another
 *     repository; empty for a store that knows none
 * @param version its own stored edit basis, as its store keeps it; a value it takes from its parent
 *     issue, such as an inherited worker or risk, is not part of it
 * @param labels its labels on the tracker, in the store's order, each once: the topic labels and
 *     any label of the person's own; empty for a store that holds none
 */
public record Issue(
        String id,
        String repository,
        String title,
        String state,
        @Nullable String size,
        List<String> blockedBy,
        @Nullable String parent,
        @Nullable URI link,
        String text,
        Worker worker,
        Risk risk,
        @Nullable Double estimate,
        @Nullable Double minutes,
        @Nullable String priority,
        List<Warning> warnings,
        List<Wait> waitsOn,
        Version version,
        List<String> labels) {

    /** Checks the ids, the repository's name, the priority and the labels and copies the lists. */
    public Issue {
        Ids.issue(id);
        labels = TopicLabels.checked(id, labels);
        Ids.name(repository);
        blockedBy = List.copyOf(blockedBy);
        blockedBy.forEach(Ids::issue);
        waitsOn = List.copyOf(waitsOn);
        final boolean reference = Ids.isReference(id);
        // A reference into another repository would read or write an issue there.
        if (reference && !Ids.repositoryOf(id).equals(repository)) {
            throw new IllegalArgumentException(
                    "Issue " + id + " does not stand in its repository " + repository);
        }
        if (parent != null) {
            Ids.issue(parent);
            if (reference
                    ? !Ids.isReference(parent) || !Ids.repositoryOf(parent).equals(repository)
                    : !id.startsWith(parent + ".")) {
                throw new IllegalArgumentException(
                        "Sub-issue " + id + " does not carry its parent's id " + parent);
            }
        }
        if (estimate != null && !(estimate >= 0 && Double.isFinite(estimate))) {
            throw new IllegalArgumentException("Issue " + id + " has no sound estimate");
        }
        if (minutes != null && !(minutes >= 0 && Double.isFinite(minutes))) {
            throw new IllegalArgumentException("Issue " + id + " has no sound minutes");
        }
        if (priority != null && !Story.PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException(
                    "The priority of " + id + " is none of " + Story.PRIORITIES);
        }
        warnings = Warning.offerable(warnings);
    }

    /**
     * An issue without labels whose version is taken over its own stored values, for a store that
     * keeps no edit basis of its own.
     */
    public Issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String size,
            final List<String> blockedBy,
            final @Nullable String parent,
            final @Nullable URI link,
            final String text,
            final Worker worker,
            final Risk risk,
            final @Nullable Double estimate,
            final @Nullable Double minutes,
            final @Nullable String priority,
            final List<Warning> warnings,
            final List<Wait> waitsOn) {
        this(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                List.of());
    }

    /**
     * An issue whose version is taken over its own stored values, its labels among them, for a
     * store that keeps no edit basis of its own; an issue without labels has the version it had
     * before labels were kept.
     *
     * @param id the issue's id
     * @param repository the name of the repository that holds it
     * @param title its title
     * @param state its state
     * @param size its size, if it has one yet
     * @param blockedBy the ids of the issues of the board it waits on
     * @param parent its parent's id, for a sub-issue
     * @param link where a person reads it on the forge, if known
     * @param text its whole text, title and head included
     * @param worker who works it
     * @param risk how much harm its change can do
     * @param estimate the minutes it is expected to take, if it was estimated
     * @param minutes the minutes its work took so far, if the store keeps them
     * @param priority its priority, or null
     * @param warnings what the store could not read in it
     * @param waitsOn the issues it waits on outside the board
     * @param labels its labels, in the store's order
     */
    public Issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String size,
            final List<String> blockedBy,
            final @Nullable String parent,
            final @Nullable URI link,
            final String text,
            final Worker worker,
            final Risk risk,
            final @Nullable Double estimate,
            final @Nullable Double minutes,
            final @Nullable String priority,
            final List<Warning> warnings,
            final List<Wait> waitsOn,
            final List<String> labels) {
        this(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                labels.isEmpty()
                        ? Version.of(
                                "issue",
                                id,
                                repository,
                                title,
                                state,
                                size,
                                blockedBy,
                                parent,
                                link,
                                text,
                                worker,
                                risk,
                                estimate,
                                minutes,
                                priority)
                        : Version.of(
                                "issue",
                                id,
                                repository,
                                title,
                                state,
                                size,
                                blockedBy,
                                parent,
                                link,
                                text,
                                worker,
                                risk,
                                estimate,
                                minutes,
                                priority,
                                labels),
                labels);
    }

    /**
     * An issue without labels with the version its store gives.
     *
     * @param id the issue's id
     * @param repository the name of the repository that holds it
     * @param title its title
     * @param state its state
     * @param size its size, if it has one yet
     * @param blockedBy the ids of the issues of the board it waits on
     * @param parent its parent's id, for a sub-issue
     * @param link where a person reads it on the forge, if known
     * @param text its whole text, title and head included
     * @param worker who works it
     * @param risk how much harm its change can do
     * @param estimate the minutes it is expected to take, if it was estimated
     * @param minutes the minutes its work took so far, if the store keeps them
     * @param priority its priority, or null
     * @param warnings what the store could not read in it
     * @param waitsOn the issues it waits on outside the board
     * @param version its own stored edit basis
     */
    public Issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String size,
            final List<String> blockedBy,
            final @Nullable String parent,
            final @Nullable URI link,
            final String text,
            final Worker worker,
            final Risk risk,
            final @Nullable Double estimate,
            final @Nullable Double minutes,
            final @Nullable String priority,
            final List<Warning> warnings,
            final List<Wait> waitsOn,
            final Version version) {
        this(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                version,
                List.of());
    }

    /**
     * This issue with another version, as its store reads it.
     *
     * @param version its own stored edit basis
     * @return the issue with that version
     */
    public Issue withVersion(final Version version) {
        return new Issue(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                version,
                labels);
    }

    /**
     * This issue in another state, as a move to it leaves it.
     *
     * @param state its state
     * @return the issue
     */
    public Issue withState(final String state) {
        return new Issue(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                version,
                labels);
    }

    /**
     * This issue with another priority.
     *
     * @param priority its priority
     * @return the issue
     */
    public Issue withPriority(final String priority) {
        return new Issue(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                version,
                labels);
    }

    /**
     * This issue with other warnings.
     *
     * @param warnings its warnings
     * @return the issue
     */
    public Issue withWarnings(final List<Warning> warnings) {
        return new Issue(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                version,
                labels);
    }

    /**
     * This issue worked by another worker with another risk, as a sub-issue is worked as its
     * parent; every other part, its priority, warnings and waits among them, stays.
     *
     * @param worker who works it
     * @param risk how much harm its change can do
     * @return the issue
     */
    public Issue withWorkerAndRisk(final Worker worker, final Risk risk) {
        return new Issue(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                version,
                labels);
    }

    /**
     * This issue waiting on other issues outside the board.
     *
     * @param waits what it waits on outside the board
     * @return the issue
     */
    public Issue withWaits(final List<Wait> waits) {
        return new Issue(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waits,
                version,
                labels);
    }

    /**
     * This issue carrying other labels, its version as it was.
     *
     * @param labels its labels, in the store's order
     * @return the issue
     */
    public Issue withLabels(final List<String> labels) {
        return new Issue(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                priority,
                warnings,
                waitsOn,
                version,
                labels);
    }

    /**
     * An issue without a priority.
     *
     * @param id the issue's id
     * @param repository the name of the repository that holds it
     * @param title its title
     * @param state its state
     * @param size its size, if it has one yet
     * @param blockedBy the ids of the issues of the board it waits on
     * @param parent its parent's id, for a sub-issue
     * @param link where a person reads it on the forge, if known
     * @param text its whole text, title and head included
     * @param worker who works it
     * @param risk how much harm its change can do
     * @param estimate the minutes it is expected to take, if it was estimated
     * @param minutes the minutes its work took so far, if the store keeps them
     */
    public Issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String size,
            final List<String> blockedBy,
            final @Nullable String parent,
            final @Nullable URI link,
            final String text,
            final Worker worker,
            final Risk risk,
            final @Nullable Double estimate,
            final @Nullable Double minutes) {
        this(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                estimate,
                minutes,
                null,
                List.of(),
                List.of());
    }

    /**
     * An issue without figures and priority: no estimate, no minutes and no priority kept.
     *
     * @param id the issue's id
     * @param repository the name of the repository that holds it
     * @param title its title
     * @param state its state
     * @param size its size, if it has one yet
     * @param blockedBy the ids of the issues it waits on
     * @param parent its parent's id, for a sub-issue
     * @param link where a person reads it on the forge, if known
     * @param text its whole text, title and head included
     * @param worker who works it
     * @param risk how much harm its change can do
     */
    public Issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String size,
            final List<String> blockedBy,
            final @Nullable String parent,
            final @Nullable URI link,
            final String text,
            final Worker worker,
            final Risk risk) {
        this(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                worker,
                risk,
                null,
                null);
    }

    /**
     * An issue whose head names neither worker nor risk, so an agent works it as an ordinary one.
     *
     * @param id the issue's id
     * @param repository the name of the repository that holds it
     * @param title its title
     * @param state its state
     * @param size its size, if it has one yet
     * @param blockedBy the ids of the issues it waits on
     * @param parent its parent's id, for a sub-issue
     * @param link where a person reads it on the forge, if known
     * @param text its whole text, title and head included
     */
    public Issue(
            final String id,
            final String repository,
            final String title,
            final String state,
            final @Nullable String size,
            final List<String> blockedBy,
            final @Nullable String parent,
            final @Nullable URI link,
            final String text) {
        this(
                id,
                repository,
                title,
                state,
                size,
                blockedBy,
                parent,
                link,
                text,
                Worker.AGENT,
                Risk.ORDINARY);
    }
}
