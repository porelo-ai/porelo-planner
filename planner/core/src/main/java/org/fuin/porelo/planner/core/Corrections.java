package org.fuin.porelo.planner.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Correction;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * Previews and carries out the correction a store offers for one warning on a story or an issue:
 * only that warning is lifted, only for that correction's own write, and only while it still
 * stands. A correction that is a move of an issue is previewed and saved as any move; this carries
 * out the others.
 */
public final class Corrections {

    /** The name of a refusal of a correction the board no longer lets stand. */
    public static final String REFUSED = "CorrectionRefused";

    private Corrections() {}

    /**
     * A correction as the person picked it.
     *
     * @param story whether it corrects a story rather than an issue
     * @param id the story's or the issue's id
     * @param warning the kind of the warning it sets right
     * @param state the state the person picked among those the correction offers
     */
    public record Asked(boolean story, String id, String warning, String state) {}

    /**
     * What a correction writes, checked against the board at a revision.
     *
     * @param revision the board's revision
     * @param asked the correction
     * @param from the item's state now, empty when none is told
     * @param writes what saving it writes: the one item's state, nothing else
     */
    public record Preview(String revision, Asked asked, String from, Writes writes) {

        /**
         * Answers the digest over what this preview showed and where its save goes.
         *
         * @param repository the project's own repository, as the planner was given it
         * @param branch its branch, or null for the remote's default
         * @return a SHA-256 as 64 lower-case hexadecimal characters
         */
        public String digest(final String repository, final @Nullable String branch) {
            final MovePreview.Digest digest = new MovePreview.Digest();
            digest.add("porelo-planner correction preview 1");
            digest.add(revision);
            digest.add(repository);
            digest.add(branch == null ? "\u0000" : branch);
            digest.add(asked.story() ? "story" : "issue");
            digest.add(asked.id());
            digest.add(asked.warning());
            digest.add(from);
            digest.add(asked.state());
            return digest.hex();
        }
    }

    /**
     * Answers what a correction would write, against the board at the revision the window shows,
     * writing nothing.
     *
     * @param store the store
     * @param asked the correction
     * @param revision the revision the window shows
     * @return the preview
     * @throws StoreException.StaleRevision when the board moved since that revision
     * @throws Moves.Refused {@code NoSuchStory} or {@code NoSuchIssue} for an item the board lacks,
     *     {@link #REFUSED} when the warning no longer stands, another warning holds the item or the
     *     state is none the correction offers
     * @throws UnsupportedOperationException when the store offers no correction of the warning
     */
    public static Preview preview(
            final PlanningStore store, final Asked asked, final String revision) {
        return check(current(store, revision), asked);
    }

    /**
     * Saves a correction whose preview the person confirmed: checked again on the board as it
     * stands, before anything is written, its digest must be the one sent, so a stale, changed or
     * repeated confirm writes nothing. The caller holds the project's writer lock around it.
     *
     * @param store the store
     * @param asked the correction
     * @param revision the revision its preview was answered for
     * @param digest the preview's digest the window sends back
     * @param repository the project's own repository, which the digest covers
     * @param branch its branch, or null
     * @return the board after the save
     * @throws StoreException.StaleRevision when the board moved since that revision
     * @throws WorkRefusal {@code PreviewChanged} when the preview answers another digest now
     * @throws Moves.Refused as {@link #preview} refuses it
     * @throws UnsupportedOperationException when the store offers no correction of the warning
     */
    public static Board save(
            final PlanningStore store,
            final Asked asked,
            final String revision,
            final String digest,
            final String repository,
            final @Nullable String branch) {
        final Board board = current(store, revision);
        final Preview preview = check(board, asked);
        if (!MessageDigest.isEqual(
                preview.digest(repository, branch).getBytes(StandardCharsets.UTF_8),
                digest.getBytes(StandardCharsets.UTF_8))) {
            throw WorkRefusal.correctionChanged();
        }
        return store.save(board.revision(), preview.writes());
    }

    private static Board current(final PlanningStore store, final String revision) {
        final Board board = store.board();
        if (!board.revision().value().equals(revision)) {
            throw new StoreException.StaleRevision(board.revision().value());
        }
        return board;
    }

    /**
     * Checks a correction on a board.
     *
     * @param board the board
     * @param asked the correction
     * @return what saving it writes
     */
    static Preview check(final Board board, final Asked asked) {
        final String id = asked.id();
        final Optional<String> twice = board.twice(id);
        if (twice.isPresent()) {
            throw refused(asked, twice.get());
        }
        final List<Warning> warnings;
        final String from;
        if (asked.story()) {
            final Story story =
                    board.story(id)
                            .orElseThrow(
                                    () ->
                                            new Moves.Refused(
                                                    "NoSuchStory",
                                                    "There is no story " + id + ".",
                                                    Map.of("story", id)));
            warnings = story.warnings();
            from = story.state();
        } else {
            final Issue issue =
                    board.issue(id)
                            .orElseThrow(
                                    () ->
                                            new Moves.Refused(
                                                    "NoSuchIssue",
                                                    "There is no issue " + id + ".",
                                                    Map.of("issue", id)));
            warnings = issue.warnings();
            from = issue.state();
        }
        int at = -1;
        for (int each = 0; each < warnings.size() && at < 0; each++) {
            if (warnings.get(each).kind().equals(asked.warning())) {
                at = each;
            }
        }
        if (at < 0) {
            throw refused(asked, "The warning no longer stands on it.");
        }
        // Only the warning corrected is lifted: any other that holds the item still refuses.
        final List<String> holding = new ArrayList<>();
        for (int each = 0; each < warnings.size(); each++) {
            if (each != at && warnings.get(each).holdsWork()) {
                holding.add(warnings.get(each).text());
            }
        }
        if (!holding.isEmpty()) {
            throw refused(asked, String.join(" ", holding));
        }
        final Correction correction = warnings.get(at).correction();
        if (correction == null) {
            throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
        }
        if (Correction.MOVE.equals(correction.kind())) {
            throw refused(
                    asked, "It is set right by a move of it, previewed and saved as any move.");
        }
        if (!Correction.STATE_STATED.equals(correction.kind()) || !asked.story()) {
            throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
        }
        if (!correction.states().contains(asked.state())) {
            throw refused(asked, "The state is none of those the correction offers.");
        }
        // The story's stated state alone: none of its issues is moved along.
        return new Preview(
                board.revision().value(),
                asked,
                from,
                new Writes(Map.of(), Map.of(id, asked.state()), List.of()));
    }

    private static Moves.Refused refused(final Asked asked, final String reason) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", asked.id());
        parameters.put("warning", asked.warning());
        parameters.put("reason", reason);
        return new Moves.Refused(REFUSED, reason, parameters);
    }
}
