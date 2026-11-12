package org.fuin.porelo.planner.store;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A story as its head and body state it.
 *
 * @param id the story's id, {@code ST} and a number
 * @param title its title
 * @param state its state, one of the workflow's
 * @param stateGiven whether its head states the state, rather than its issues giving it
 * @param priority its priority
 * @param size the size given when it was estimated, {@code XS} to {@code XL}, or null when its head
 *     gives none; its own, never added up from its issues' sizes
 * @param issues the ids of the issues it needs, in its order
 * @param description its body, the text below the head
 * @param link where a person reads it on the forge, if known
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
        @Nullable URI link) {

    /** The priorities a story may have, the highest first. */
    public static final List<String> PRIORITIES =
            List.of("Highest", "High", "Medium", "Low", "Lowest");

    /** Checks the ids and copies the list. */
    public Story {
        Ids.story(id);
        issues = List.copyOf(issues);
        issues.forEach(Ids::issue);
    }
}
