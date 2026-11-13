package org.fuin.porelo.planner.app;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A batch of moves as the window sends it after its preview: with the preview's digest. */
final class Previewed {

    /** A digest no preview answers, for a batch whose preview was refused. */
    static final String NO_DIGEST = "0".repeat(64);

    private static final JsonMapper JSON = new JsonMapper();

    private Previewed() {}

    /**
     * Adds the digest the preview answered to a batch, or {@link #NO_DIGEST} when it answered none,
     * so the save's own refusal is what a test reads; a batch that is no JSON object is sent as it
     * is.
     *
     * @param batch the batch as JSON
     * @param preview the preview's answer
     * @return the batch with the digest
     */
    static String moves(final String batch, final String preview) {
        final JsonNode parsed;
        try {
            parsed = JSON.readTree(batch);
        } catch (final JacksonException e) {
            return batch;
        }
        if (!(parsed instanceof ObjectNode body)) {
            return batch;
        }
        JsonNode answer;
        try {
            answer = JSON.readTree(preview.isBlank() ? "{}" : preview);
        } catch (final JacksonException e) {
            answer = JSON.createObjectNode();
        }
        body.put("digest", answer.path("digest").asString(NO_DIGEST));
        return JSON.writeValueAsString(body);
    }
}
