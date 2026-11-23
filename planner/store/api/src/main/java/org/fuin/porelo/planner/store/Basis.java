package org.fuin.porelo.planner.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The versions a caller saw, by item id.
 *
 * @param items each item's own stored edit basis
 */
public record Basis(Map<String, Version> items) {

    /** Copies the entries so a caller cannot change the basis of a write. */
    public Basis {
        items = Map.copyOf(items);
    }

    /** Answers a basis for a write with no targets. */
    public static Basis none() {
        return new Basis(Map.of());
    }

    /**
     * Answers a basis that saw every item the board holds once, each at the version it stands at;
     * an id the board holds twice is left out.
     *
     * @param board the board seen
     * @return the versions of its items
     */
    public static Basis of(final Board board) {
        final Map<String, Version> seen = new HashMap<>();
        final Set<String> twice = new java.util.HashSet<>();
        final java.util.function.BiConsumer<String, Version> add =
                (id, version) -> {
                    if (seen.putIfAbsent(id, version) != null) {
                        twice.add(id);
                    }
                };
        board.issues().forEach(item -> add.accept(item.id(), item.version()));
        board.stories().forEach(item -> add.accept(item.id(), item.version()));
        board.unlisted().forEach(item -> add.accept(item.id(), item.version()));
        twice.forEach(seen::remove);
        return new Basis(seen);
    }

    /** Answers a new basis with the version seen for the named item. */
    public Basis with(final String id, final Version version) {
        final Map<String, Version> added = new LinkedHashMap<>(items);
        added.put(id, version);
        return new Basis(added);
    }

    /** Answers the version seen for an item, or null when none was supplied. */
    public @Nullable Version of(final String id) {
        return items.get(id);
    }

    /**
     * Checks a write's targets against the versions seen, on the board as the store holds it now.
     * Missing versions are reported together before any changed item; an entry for an item that is
     * no target does not affect the write.
     *
     * @param board the board the store holds now
     * @param targets the ids of the items the write changes
     * @throws StoreException.BasisIncomplete for every target without a version
     * @throws StoreException.Changed for every target changed, absent or held twice on the board
     */
    public void unchanged(final Board board, final Set<String> targets) {
        final List<String> missing =
                targets.stream().filter(id -> of(id) == null).sorted().toList();
        if (!missing.isEmpty()) {
            throw new StoreException.BasisIncomplete(missing);
        }
        final Map<String, List<Version>> held = new HashMap<>();
        board.issues()
                .forEach(
                        item ->
                                held.computeIfAbsent(item.id(), id -> new ArrayList<>())
                                        .add(item.version()));
        board.stories()
                .forEach(
                        item ->
                                held.computeIfAbsent(item.id(), id -> new ArrayList<>())
                                        .add(item.version()));
        board.unlisted()
                .forEach(
                        item ->
                                held.computeIfAbsent(item.id(), id -> new ArrayList<>())
                                        .add(item.version()));
        final List<String> changed =
                targets.stream()
                        .filter(
                                id -> {
                                    final List<Version> versions = held.getOrDefault(id, List.of());
                                    return versions.size() != 1
                                            || !versions.getFirst().equals(of(id));
                                })
                        .sorted()
                        .toList();
        if (!changed.isEmpty()) {
            throw new StoreException.Changed(changed);
        }
    }
}
