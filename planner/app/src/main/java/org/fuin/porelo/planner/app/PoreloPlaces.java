package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Set;
import java.util.function.BiFunction;
import org.fuin.porelo.planner.core.RunPlace;
import org.fuin.porelo.planner.core.RunRole;
import org.jspecify.annotations.Nullable;

/**
 * Reads a run's role and place as Porelo's answers give them, checking each member, so a reader of
 * Porelo refuses what the contract does not describe instead of guessing it.
 */
final class PoreloPlaces {

    private static final Set<String> PLACE = Set.of("gatePush", "pullRequest", "ownPullRequest");

    private static final Set<String> GATE = Set.of("repository", "name");

    private PoreloPlaces() {}

    /**
     * Answers a role, or null when the member is absent or null.
     *
     * @param object the object holding it
     * @param name the member's name
     * @param path the member's path, named in a refusal
     * @param refused makes the refusal from a path and what is wrong
     * @return the role, or null
     */
    static @Nullable RunRole role(
            final JsonNode object,
            final String name,
            final String path,
            final BiFunction<String, String, RuntimeException> refused) {
        final JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw refused.apply(path, "is neither absent nor a string");
        }
        try {
            return RunRole.valueOf(value.textValue());
        } catch (final IllegalArgumentException ex) {
            throw refused.apply(path, "is none of [AUTHOR, REVIEWER]");
        }
    }

    /**
     * Answers a place, or null when the member is absent or null.
     *
     * @param object the object holding it
     * @param name the member's name
     * @param path the member's path, named in a refusal
     * @param refused makes the refusal from a path and what is wrong
     * @return the place, or null
     */
    static @Nullable RunPlace place(
            final JsonNode object,
            final String name,
            final String path,
            final BiFunction<String, String, RuntimeException> refused) {
        final JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return place(value, path, refused);
    }

    /**
     * Answers a place that must be given.
     *
     * @param value the place
     * @param path its path, named in a refusal
     * @param refused makes the refusal from a path and what is wrong
     * @return the place
     */
    static RunPlace place(
            final JsonNode value,
            final String path,
            final BiFunction<String, String, RuntimeException> refused) {
        if (!value.isObject()) {
            throw refused.apply(path, "is no object");
        }
        only(value, PLACE, path, refused);
        final JsonNode gate = value.get("gatePush");
        RunPlace.GatePush push = null;
        if (gate != null && !gate.isNull()) {
            if (!gate.isObject()) {
                throw refused.apply(path + ".gatePush", "is no object");
            }
            only(gate, GATE, path + ".gatePush", refused);
            final JsonNode repository = gate.get("repository");
            if (repository != null && !repository.isNull() && !repository.isTextual()) {
                throw refused.apply(
                        path + ".gatePush.repository", "is neither absent nor a string");
            }
            final JsonNode named = gate.get("name");
            if (named == null || !named.isTextual()) {
                throw refused.apply(path + ".gatePush.name", "is no string");
            }
            try {
                push =
                        new RunPlace.GatePush(
                                repository == null || repository.isNull()
                                        ? null
                                        : repository.textValue(),
                                named.textValue());
            } catch (final IllegalArgumentException ex) {
                throw refused.apply(path + ".gatePush", "is no gate push: " + ex.getMessage());
            }
        }
        final JsonNode branch = value.get("pullRequest");
        if (branch != null && !branch.isNull() && !branch.isTextual()) {
            throw refused.apply(path + ".pullRequest", "is neither absent nor a string");
        }
        final JsonNode own = value.get("ownPullRequest");
        if (own != null && !own.isNull() && !own.isBoolean()) {
            throw refused.apply(path + ".ownPullRequest", "is neither absent nor a boolean");
        }
        try {
            return new RunPlace(
                    push,
                    branch == null || branch.isNull() ? null : branch.textValue(),
                    own == null || own.isNull() ? null : own.booleanValue());
        } catch (final IllegalArgumentException ex) {
            throw refused.apply(path, "is no place: " + ex.getMessage());
        }
    }

    private static void only(
            final JsonNode object,
            final Set<String> known,
            final String path,
            final BiFunction<String, String, RuntimeException> refused) {
        for (final Iterator<String> names = object.fieldNames(); names.hasNext(); ) {
            final String member = names.next();
            if (!known.contains(member)) {
                // The member's name comes from outside, so only that one is unknown is said.
                throw refused.apply(path, "holds a member the contract does not describe");
            }
        }
    }
}
