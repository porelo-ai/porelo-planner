package org.fuin.porelo.planner.core;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.jspecify.annotations.Nullable;

/**
 * Saves a discussed idea once per offer: the offer is written as pending before the store saves and
 * as done with the story's id after, so neither a repeated hand-in nor a planner stopped mid-save
 * makes a second story.
 */
public final class HandedIdeas {

    /** An offer whose save began and has not ended. */
    public static final String PENDING = "PENDING";

    /** An offer whose idea is saved as its story. */
    public static final String DONE = "DONE";

    /** An offer whose idea reached some repositories but not all. */
    public static final String PARTLY = "PARTLY";

    /** The most done offers kept; a new one drops the oldest. */
    public static final int MOST_DONE = 1000;

    private HandedIdeas() {}

    /**
     * A discussion's offer whose idea was handed in.
     *
     * @param status {@value #PENDING}, {@value #DONE} or {@value #PARTLY}
     * @param story the story's id once done, else null
     * @param pushed the repositories already pushed when partly saved, else empty
     * @param order the record's generation when it was written, the oldest the lowest
     */
    public record Handed(String status, @Nullable String story, List<String> pushed, long order) {

        /** Copies the repositories. */
        public Handed {
            pushed = List.copyOf(pushed);
        }
    }

    /**
     * Checks an offer's form: a canonical lowercase UUID, as every offer of the record is.
     *
     * @param offer the offer
     * @return the offer
     * @throws WorkRefusal {@code IdeaRefused} for any other text
     */
    public static String offer(final String offer) {
        try {
            if (UUID.fromString(offer).toString().equals(offer)) {
                return offer;
            }
        } catch (final IllegalArgumentException invalid) {
            // Refused below in fixed words.
        }
        throw WorkRefusal.ideaRefused(
                "The body is no hand-in of an idea.",
                List.of("offer: The offer must be a lowercase UUID."),
                null);
    }

    /**
     * Checks a handed offer as the record keeps it.
     *
     * @param offer the offer
     * @param handed what the record keeps of it
     * @throws IllegalArgumentException when a part fails its check
     */
    public static void validate(final String offer, final Handed handed) {
        if (!UUID.fromString(offer).toString().equals(offer) || handed.order() < 0) {
            throw new IllegalArgumentException("a handed idea fails its check");
        }
        final String story = handed.story();
        switch (handed.status()) {
            case DONE -> {
                if (story == null || !story.matches("ST[1-9][0-9]{0,4}")) {
                    throw new IllegalArgumentException("a handed idea fails its check");
                }
                Ids.story(story);
            }
            case PENDING, PARTLY -> {
                if (story != null) {
                    throw new IllegalArgumentException("a handed idea fails its check");
                }
            }
            default -> throw new IllegalArgumentException("a handed idea fails its check");
        }
        handed.pushed().forEach(Ids::name);
    }

    /**
     * Saves an idea under the record's lock.
     *
     * @param store the project's store
     * @param session the locked record
     * @param snapshot the record as read under the lock
     * @param offer the discussion's offer, checked
     * @param idea the idea
     * @param shape the faults of the idea's shape its reader found
     * @return the story's id
     */
    static String save(
            final PlanningStore store,
            final WorkRun.Session session,
            final WorkRun.Snapshot snapshot,
            final String offer,
            final NewIdeas.Idea idea,
            final List<String> shape) {
        final Handed known = snapshot.ideas().get(offer);
        if (known != null) {
            return known(known);
        }
        Board board = store.board();
        NewIdea checked = NewIdeas.check(board, idea, shape);
        final WorkRun.Snapshot pending =
                with(snapshot, offer, new Handed(PENDING, null, List.of(), next(snapshot)));
        session.preflight(pending);
        session.write(pending);
        final NewIdea.Added added;
        try {
            NewIdea.Added saved;
            try {
                saved = store.createIdea(board.revision(), checked);
            } catch (final StoreException.StaleRevision stale) {
                // Nothing was pushed, so the idea is saved once against the board as it is now.
                board = store.refresh();
                checked = NewIdeas.check(board, idea, shape);
                saved = store.createIdea(board.revision(), checked);
            }
            added = saved;
        } catch (final StoreException.PartlySaved partly) {
            session.write(
                    with(pending, offer, new Handed(PARTLY, null, partly.pushed(), next(pending))));
            throw partly;
        } catch (final RuntimeException refused) {
            session.write(without(pending, offer));
            throw refused;
        }
        session.write(
                with(pending, offer, new Handed(DONE, added.story(), List.of(), next(pending))));
        return added.story();
    }

    private static String known(final Handed known) {
        final String story = known.story();
        if (DONE.equals(known.status()) && story != null) {
            return story;
        }
        final String reason =
                PARTLY.equals(known.status())
                        ? "The idea of this offer was saved only in part and is not saved again."
                        : "The save of this offer's idea did not end and is not made again.";
        throw WorkRefusal.ideaRefused(reason, List.of("offer: " + reason), known.pushed());
    }

    private static long next(final WorkRun.Snapshot snapshot) {
        return snapshot.generation() + 1;
    }

    private static WorkRun.Snapshot with(
            final WorkRun.Snapshot snapshot, final String offer, final Handed handed) {
        final Map<String, Handed> ideas = new LinkedHashMap<>(snapshot.ideas());
        ideas.put(offer, handed);
        while (ideas.values().stream().filter(h -> DONE.equals(h.status())).count() > MOST_DONE) {
            ideas.entrySet().stream()
                    .filter(entry -> DONE.equals(entry.getValue().status()))
                    .min(Comparator.comparingLong(entry -> entry.getValue().order()))
                    .map(Map.Entry::getKey)
                    .ifPresent(ideas::remove);
        }
        return new WorkRun.Snapshot(next(snapshot), snapshot.runs(), ideas);
    }

    private static WorkRun.Snapshot without(final WorkRun.Snapshot snapshot, final String offer) {
        final Map<String, Handed> ideas = new LinkedHashMap<>(snapshot.ideas());
        ideas.remove(offer);
        return new WorkRun.Snapshot(next(snapshot), snapshot.runs(), ideas);
    }
}
