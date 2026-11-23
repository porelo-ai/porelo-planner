package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Correction;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * Previews and carries out the correction a store offers for one warning on a story or an issue:
 * only that warning is lifted, only for that correction's own write, and only while it still
 * stands. A correction that is a move of an issue is previewed and saved as any move; this checks
 * the others, which {@link WorkRun} previews and saves with the runs at work on the item. No store
 * offers another kind now, so every other correction is answered as not offered.
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
     * What a correction writes, checked against the board the store holds.
     *
     * @param revision the board's revision the preview was read at
     * @param asked the correction
     * @param from the item's state now, empty when none is told
     * @param writes what saving it writes: the one item's state, nothing else
     * @param warnings what it can do harm by, as a move's preview names it: a run with tasks at
     *     work on the story ({@link MovePreview#RUNNING_WORK}), which a confirmed save stops
     * @param targets every item the correction changes with the version it stands at, by id,
     *     sorted; the save checks exactly these
     */
    public record Preview(
            String revision,
            Asked asked,
            String from,
            Writes writes,
            List<MovePreview.Warning> warnings,
            Map<String, String> targets) {

        /** Copies the list and the targets, keeping the targets sorted. */
        public Preview {
            warnings = List.copyOf(warnings);
            targets = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(targets));
        }

        /**
         * The preview's targets as the versions a caller who saw it sends back with the save.
         *
         * @return each target with the version it stood at
         */
        public org.fuin.porelo.planner.store.Basis basis() {
            return MovePreview.basis(targets);
        }

        /**
         * Answers the digest over what this preview showed and where its save goes, every target
         * with its version among it; never the board's revision.
         *
         * @param repository the project's own repository, as the planner was given it
         * @param branch its branch, or null for the remote's default
         * @return a SHA-256 as 64 lower-case hexadecimal characters
         */
        public String digest(final String repository, final @Nullable String branch) {
            final MovePreview.Digest digest = new MovePreview.Digest();
            digest.add("porelo-planner correction preview 2");
            digest.add(repository);
            digest.add(branch == null ? "\u0000" : branch);
            digest.count(targets.size());
            targets.forEach(
                    (target, version) -> {
                        digest.add(target);
                        digest.add(version);
                    });
            digest.add(asked.story() ? "story" : "issue");
            digest.add(asked.id());
            digest.add(asked.warning());
            digest.add(from);
            digest.add(asked.state());
            MovePreview.warnings(digest, warnings);
            return digest.hex();
        }
    }

    /**
     * Checks a correction on a board, writing nothing.
     *
     * @param board the board
     * @param asked the correction
     * @param running the runs with tasks at work over the story
     * @return what saving it writes and what it can do harm by
     * @throws Moves.Refused {@code NoSuchStory} or {@code NoSuchIssue} for an item the board lacks,
     *     {@link #REFUSED} when the warning no longer stands, another warning holds the item, the
     *     work rules have faults or the state is none the correction offers
     * @throws UnsupportedOperationException when the store offers no correction of the warning
     */
    static Preview check(final Board board, final Asked asked, final MovePreview.Running running) {
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
        // Whatever else the board holds the item back for, as a move of it is judged: a warning
        // about its state alone is what the correction sets right.
        final Optional<String> held = board.holdsMove(id);
        if (held.isPresent()) {
            throw refused(asked, held.get());
        }
        if (!board.work().faults().isEmpty()) {
            // A working state whose rule could not be read would be offered as an open one.
            throw refused(
                    asked,
                    "The work rules have faults: " + String.join("; ", board.work().faults()));
        }
        final Correction correction = warnings.get(at).correction();
        if (correction == null) {
            throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
        }
        if (Correction.MOVE.equals(correction.kind())) {
            throw refused(
                    asked, "It is set right by a move of it, previewed and saved as any move.");
        }
        // No store offers another kind of correction.
        throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
    }

    private static Moves.Refused refused(final Asked asked, final String reason) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", asked.id());
        parameters.put("warning", asked.warning());
        parameters.put("reason", reason);
        return new Moves.Refused(REFUSED, reason, parameters);
    }
}
