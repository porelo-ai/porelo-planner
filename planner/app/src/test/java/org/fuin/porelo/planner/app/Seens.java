package org.fuin.porelo.planner.app;

import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The {@code seen} a window sends with a write, built from a board a test holds. */
final class Seens {

    private static final JsonMapper JSON = new JsonMapper();

    private Seens() {}

    /**
     * The versions of every item the board holds once, as a JSON object.
     *
     * @param board the board the window saw
     * @return the object, by id
     */
    static String of(final Board board) {
        final ObjectNode seen = JSON.createObjectNode();
        Basis.of(board).items().forEach((id, version) -> seen.put(id, version.value()));
        return JSON.writeValueAsString(seen);
    }

    /**
     * The versions of every item a board answer names, as the window keeps them from its last read.
     *
     * @param answer the JSON answer of a board read, such as the stories list
     * @return the object, by id
     */
    static String ofAnswer(final String answer) {
        final ObjectNode seen = JSON.createObjectNode();
        collect(JSON.readTree(answer), seen);
        return JSON.writeValueAsString(seen);
    }

    private static void collect(final tools.jackson.databind.JsonNode node, final ObjectNode seen) {
        if (node.isObject() && node.path("id").isString() && node.path("version").isString()) {
            seen.put(node.path("id").asString(), node.path("version").asString());
        }
        node.forEach(child -> collect(child, seen));
    }

    /**
     * The same ids at a version none of them stands at, as a window sends after another change.
     *
     * @param board the board the items stand on now
     * @return the object, by id
     */
    static String stale(final Board board) {
        final ObjectNode seen = JSON.createObjectNode();
        Basis.of(board).items().keySet().forEach(id -> seen.put(id, "f".repeat(64)));
        return JSON.writeValueAsString(seen);
    }
}
