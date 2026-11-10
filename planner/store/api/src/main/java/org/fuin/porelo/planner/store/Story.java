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
 * @param priority its priority
 * @param issues the ids of the issues it needs, in its order
 * @param description its body, the text below the head
 * @param link where a person reads it on the forge, if known
 */
public record Story(
        String id,
        String title,
        String state,
        String priority,
        List<String> issues,
        String description,
        @Nullable URI link) {

    /** Checks the ids and copies the list. */
    public Story {
        Ids.story(id);
        issues = List.copyOf(issues);
        issues.forEach(Ids::issue);
    }
}
