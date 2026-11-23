package org.fuin.porelo.planner.store.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.github.CommentView;
import org.fuin.porelo.planner.store.github.StageComment;

/**
 * A stage in an item file: the JSON block a stage comment holds, checked as one is, and the instant
 * it ended, which the store writes itself where GitHub has the comment's time.
 */
final class LocalStage {

    private static final String OPENING = "```json\n";

    private static final String END = "end";

    private LocalStage() {}

    /**
     * One stage and when it ended.
     *
     * @param stage the stage
     * @param end when it ended
     */
    record Entry(StageEntry stage, Instant end) {}

    /**
     * Writes a stage as its JSON object.
     *
     * @param entry the stage
     * @return the object
     * @throws IllegalArgumentException when a value would not read back as written
     */
    static ObjectNode write(final Entry entry) {
        final String comment = StageComment.write(entry.stage(), null);
        try {
            final int from = comment.indexOf(OPENING) + OPENING.length();
            final ObjectNode node =
                    (ObjectNode)
                            LocalItem.JSON.readTree(
                                    comment.substring(from, comment.lastIndexOf("\n```")));
            node.put(END, entry.end().toString());
            return node;
        } catch (final IOException e) {
            throw new IllegalStateException("A written stage is JSON", e);
        }
    }

    /**
     * Reads a stage's JSON object with the checks a stage comment's block passes.
     *
     * @param given the object
     * @return the stage
     * @throws IllegalArgumentException when it is no stage
     * @throws java.time.DateTimeException when its end is no instant
     */
    static Entry read(final JsonNode given) {
        if (!given.isObject() || !given.path(END).isTextual()) {
            throw new IllegalArgumentException("a stage names the instant it ended");
        }
        final ObjectNode node = ((ObjectNode) given).deepCopy();
        final Instant end = Instant.parse(node.remove(END).asText());
        final String comment = StageComment.MARKER + "\nstage\n" + OPENING + node + "\n```\n";
        final StageComment.Read read =
                StageComment.read(new CommentView("local", comment, end))
                        .orElseThrow(() -> new IllegalArgumentException("a stage is no stage"));
        if (!(read instanceof StageComment.Stage stage) || stage.base() != null) {
            throw new IllegalArgumentException("a stage holds a field or value it cannot have");
        }
        return new Entry(stage.entry(), end);
    }
}
