package org.fuin.porelo.planner.store.github;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.Version;
import org.jspecify.annotations.Nullable;

/**
 * The items as commands were judged on them, kept by id and version, so a command whose item moved
 * since can be merged against what it was judged on. Bounded; nothing survives a restart, and a
 * command without its judged item is a conflict for the owner account.
 */
final class JudgedViews {

    /** The most items kept; the least recently asked for goes first. */
    static final int MOST = 1024;

    private final Map<String, ItemView> views =
            new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, ItemView> eldest) {
                    return size() > MOST;
                }
            };

    /**
     * Keeps each item the command was judged on that the snapshot holds at the version judged.
     *
     * @param held the snapshot the store holds
     * @param command the command
     */
    synchronized void remember(final Snapshot held, final Command command) {
        command.basis()
                .items()
                .forEach(
                        (id, version) -> {
                            final ItemView view = held.items().get(id);
                            if (view != null && ItemVersions.of(view).equals(version)) {
                                views.put(key(id, version), view);
                            }
                        });
    }

    /**
     * The item as it was judged, when kept.
     *
     * @param id the item's id
     * @param version the version judged, or null for none
     * @return the item, or null when it was not kept
     */
    synchronized @Nullable ItemView judged(final String id, final @Nullable Version version) {
        return version == null ? null : views.get(key(id, version));
    }

    private static String key(final String id, final Version version) {
        return id + "\n" + version.value();
    }
}
