package org.fuin.porelo.planner.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;

/**
 * The references of a board change: a blocker or story named by an id the board holds or by the key
 * of a new issue of the same change, resolved to the id made for it once it stands.
 */
public final class BoardChanges {

    /** The refusal of a reference that names no item of the board and no key of the change. */
    public static final String NO_SUCH_KEY = "NoSuchKey";

    /** The refusal of a reference to the key of a new issue that was refused. */
    public static final String KEY_REFUSED = "KeyRefused";

    private BoardChanges() {}

    /** What a reference names. */
    public sealed interface Target permits Existing, Key {}

    /**
     * An item the board holds.
     *
     * @param id its id
     */
    public record Existing(String id) implements Target {}

    /**
     * A new issue of the same change.
     *
     * @param key the caller's key of it
     */
    public record Key(String key) implements Target {}

    /**
     * Resolves a reference before anything is written: a key of the change names that new issue,
     * otherwise the reference must be an issue's or a story's id the board holds.
     *
     * @param board the board as it stands
     * @param keys the keys of the change's new issues
     * @param item the item that names the reference, empty for a new issue
     * @param reference the reference
     * @return what it names
     * @throws Moves.Refused {@value #NO_SUCH_KEY} naming the item and the reference when it names
     *     nothing
     */
    public static Target target(
            final Board board, final Set<String> keys, final String item, final String reference) {
        if (keys.contains(reference)) {
            return new Key(reference);
        }
        if (board.issue(reference).isPresent() || board.story(reference).isPresent()) {
            return new Existing(reference);
        }
        throw refused(
                NO_SUCH_KEY, item, reference, "The reference names no item of the board or key.");
    }

    /**
     * The id a resolved reference stands for once the new issues are made.
     *
     * @param target what the reference names
     * @param made the id made for each key of a new issue that stands
     * @param item the item that names the reference, empty for a new issue
     * @return the id
     * @throws Moves.Refused {@value #KEY_REFUSED} naming the item and the key when that new issue
     *     was refused
     */
    public static String id(
            final Target target, final Map<String, String> made, final String item) {
        return switch (target) {
            case Existing existing -> existing.id();
            case Key key -> {
                final String id = made.get(key.key());
                if (id == null) {
                    throw refused(
                            KEY_REFUSED, item, key.key(), "The new issue of that key was refused.");
                }
                yield id;
            }
        };
    }

    private static Moves.Refused refused(
            final String name, final String item, final String key, final String detail) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", item);
        parameters.put("key", key);
        return new Moves.Refused(name, detail, parameters);
    }
}
