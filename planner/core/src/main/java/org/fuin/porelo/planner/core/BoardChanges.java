package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.TextEdit;
import org.jspecify.annotations.Nullable;

/**
 * The references of a board change: a blocker or story named by an id the board holds or by the key
 * of a new issue of the same change, resolved to the id made for it once it stands; and the
 * judgment of every item of the change on what needs no write.
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

    /**
     * One item of a change as {@link #judge} reads it.
     *
     * @param key the key of a new issue, null for a changed issue
     * @param issue the id of a changed issue, empty for a new issue
     * @param text the title and parts a text edit of the item writes, or null when it writes none
     * @param story the story it is hung into, an id or key, or null for none
     * @param blockedBy its whole list of blockers, each an id or key, or null to keep them
     * @param state the state it is made in or moved to, or null for none
     */
    public record Item(
            @Nullable String key,
            String issue,
            @Nullable TextEdit text,
            @Nullable String story,
            @Nullable List<String> blockedBy,
            @Nullable String state) {}

    /**
     * Judges every item before anything is written, with what can be judged without writing: its
     * references, its text, its blockers - none itself, none a story, none twice, none leading back
     * through the board's blockers and the change's own - its story, which must be a story of the
     * board, and its state, which the workflow must name. An item that waits on a new issue refused
     * is refused too, a new issue listed after it included, until no more are.
     *
     * @param board the board as it stands
     * @param items the items, the new issues first, in the order they are written
     * @return the refusal of each item refused, by its place in the list; an item left out stands
     */
    public static Map<Integer, RuntimeException> judge(final Board board, final List<Item> items) {
        final Set<String> keys = new HashSet<>();
        for (final Item item : items) {
            final @Nullable String key = item.key();
            if (key != null) {
                keys.add(key);
            }
        }
        final Map<Integer, RuntimeException> refusals = new TreeMap<>();
        final Map<Integer, List<Target>> blockers = new HashMap<>();
        for (int at = 0; at < items.size(); at++) {
            try {
                final @Nullable List<Target> named = alone(board, keys, items.get(at));
                if (named != null) {
                    blockers.put(at, named);
                }
            } catch (final Moves.Refused refused) {
                refusals.put(at, refused);
            }
        }
        do {
            keysRefused(items, blockers, refusals);
        } while (circles(board, items, blockers, refusals));
        return refusals;
    }

    /** What an item needs to be judged alone; returns its blockers resolved. */
    private static @Nullable List<Target> alone(
            final Board board, final Set<String> keys, final Item item) {
        final String name = item.issue();
        final boolean created = item.key() != null;
        final @Nullable String reference = item.story();
        final @Nullable Target story =
                reference == null ? null : target(board, keys, name, reference);
        final @Nullable List<String> references = item.blockedBy();
        @Nullable List<Target> blockers = null;
        if (references != null) {
            blockers = new ArrayList<>();
            for (final String each : references) {
                blockers.add(target(board, keys, name, each));
            }
        }
        final @Nullable Issue held;
        if (created) {
            held = null;
        } else {
            held =
                    board.issue(name)
                            .orElseThrow(
                                    () ->
                                            new Moves.Refused(
                                                    "NoSuchIssue",
                                                    "There is no issue " + name + ".",
                                                    Map.of("issue", name)));
        }
        final @Nullable TextEdit text = item.text();
        if (text != null) {
            Edits.text(name, text);
        }
        if (story instanceof Key) {
            throw Edits.refused(name, "story", "The story is no story of the project.");
        }
        if (story instanceof Existing existing && board.story(existing.id()).isEmpty()) {
            throw created
                    ? NewIssues.refused("story", "The story is no story of the project.")
                    : new Moves.Refused(
                            "NoSuchStory",
                            "There is no story " + existing.id() + ".",
                            Map.of("story", existing.id()));
        }
        if (blockers != null) {
            blockersOf(board, item, held, blockers);
        }
        final @Nullable String state = item.state();
        if (state != null && !board.workflow().states().contains(state)) {
            throw held == null
                    ? NewIssues.notAnInitialState(board)
                    : Moves.notConfigured(name, held.state(), state);
        }
        return blockers;
    }

    private static void blockersOf(
            final Board board,
            final Item item,
            final @Nullable Issue held,
            final List<Target> blockers) {
        final String name = item.issue();
        final int outside = held == null ? 0 : held.waitsOn().size();
        if (blockers.size() + outside > Edits.MOST_BLOCKERS) {
            throw Edits.refused(
                    name,
                    "blockedBy",
                    "An issue has at most " + Edits.MOST_BLOCKERS + " blockers.");
        }
        final String own = held == null ? String.valueOf(item.key()) : name;
        final Set<Target> named = new HashSet<>();
        for (final Target blocker : blockers) {
            if (blocker instanceof Existing existing && board.issue(existing.id()).isEmpty()) {
                // A story or an id no issue has: the write would refuse it as no issue.
                throw new Moves.Refused(
                        "NoSuchIssue",
                        "There is no issue " + existing.id() + ".",
                        Map.of("issue", existing.id()));
            }
            if (!named.add(blocker)) {
                throw Edits.refused(
                        name, "blockedBy", "The blocker " + nameOf(blocker) + " is named twice.");
            }
            if (nameOf(blocker).equals(own)) {
                throw Edits.refused(name, "blockedBy", "An issue does not block itself.");
            }
        }
    }

    /** Refuses each item that waits on a new issue refused, until no more are. */
    private static void keysRefused(
            final List<Item> items,
            final Map<Integer, List<Target>> blockers,
            final Map<Integer, RuntimeException> refusals) {
        boolean again;
        do {
            again = false;
            final Set<String> refused = new HashSet<>();
            for (int at = 0; at < items.size(); at++) {
                final @Nullable String key = items.get(at).key();
                if (key != null && refusals.containsKey(at)) {
                    refused.add(key);
                }
            }
            for (int at = 0; at < items.size(); at++) {
                final @Nullable List<Target> named = blockers.get(at);
                if (named == null || refusals.containsKey(at)) {
                    continue;
                }
                for (final Target blocker : named) {
                    if (blocker instanceof Key key && refused.contains(key.key())) {
                        refusals.put(
                                at,
                                refused(
                                        KEY_REFUSED,
                                        items.get(at).issue(),
                                        key.key(),
                                        "The new issue of that key was refused."));
                        again = true;
                        break;
                    }
                }
            }
        } while (again);
    }

    /**
     * Refuses each item whose blockers lead back to it, writing the items in order: the first of a
     * circle stands, as the writes would let it.
     *
     * @return whether an item was refused
     */
    private static boolean circles(
            final Board board,
            final List<Item> items,
            final Map<Integer, List<Target>> blockers,
            final Map<Integer, RuntimeException> refusals) {
        final Map<String, List<String>> waits = new HashMap<>();
        for (final Issue issue : board.issues()) {
            waits.put(issue.id(), issue.blockedBy());
        }
        boolean added = false;
        for (int at = 0; at < items.size(); at++) {
            final @Nullable List<Target> named = blockers.get(at);
            if (named == null || refusals.containsKey(at)) {
                continue;
            }
            final Item item = items.get(at);
            final @Nullable String key = item.key();
            final String node = key == null ? item.issue() : key;
            final List<String> names = named.stream().map(BoardChanges::nameOf).toList();
            String through = null;
            for (final String blocker : names) {
                if (Edits.reaches(waits, blocker, node)) {
                    through = blocker;
                    break;
                }
            }
            if (through == null) {
                waits.put(node, names);
            } else {
                refusals.put(
                        at,
                        Edits.refused(
                                item.issue(),
                                "blockedBy",
                                "The blockers lead back to the issue through " + through + "."));
                added = true;
            }
        }
        return added;
    }

    private static String nameOf(final Target target) {
        return switch (target) {
            case Existing existing -> existing.id();
            case Key key -> key.key();
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
