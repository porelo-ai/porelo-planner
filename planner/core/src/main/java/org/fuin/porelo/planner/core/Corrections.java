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
import org.fuin.porelo.planner.store.RepositoryWarning;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * Previews and carries out the correction a store offers for one warning on a story or an issue:
 * only that warning is lifted, only for that correction's own write, and only while it still
 * stands. A correction that is a move of an issue is previewed and saved as any move; this checks
 * the others, which {@link WorkRun} previews and saves: a story's priority set to the one picked.
 * It checks a repository's correction too, which {@link WorkRun} carries out on the store: its
 * labels brought to the topic labels. Every other correction is answered as not offered. No
 * correction checked here writes a state, so none touches a run at work.
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
     * @param choice what the person picked among the correction's choices: a state for a move, a
     *     priority for a priority
     */
    public record Asked(boolean story, String id, String warning, String choice) {}

    /**
     * What a correction writes, checked against the board the store holds.
     *
     * @param revision the board's revision the preview was read at
     * @param asked the correction
     * @param from what the item holds now of what the correction changes, empty when none is told:
     *     a priority correction's warning tells none
     * @param writes what saving it writes: the one item's value picked, nothing else
     * @param targets every item the correction changes with the version it stands at, by id,
     *     sorted; the save checks exactly these
     */
    public record Preview(
            String revision, Asked asked, String from, Writes writes, Map<String, String> targets) {

        /** Copies the targets, keeping them sorted. */
        public Preview {
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
            digest.add("porelo-planner correction preview 3");
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
            digest.add(asked.choice());
            return digest.hex();
        }
    }

    /**
     * Checks a correction on a board, writing nothing.
     *
     * @param board the board
     * @param asked the correction
     * @return what saving it writes
     * @throws Moves.Refused {@code NoSuchStory} or {@code NoSuchIssue} for an item the board lacks,
     *     {@link #REFUSED} when the warning no longer stands, another warning holds the item, the
     *     work rules have faults, the correction is a move or the choice is none it offers
     * @throws UnsupportedOperationException when the store offers no correction of the warning
     */
    static Preview check(final Board board, final Asked asked) {
        final String id = asked.id();
        final Optional<String> twice = board.twice(id);
        if (twice.isPresent()) {
            throw refused(asked, twice.get());
        }
        final List<Warning> warnings;
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
        if (!Correction.PRIORITY.equals(correction.kind()) || !asked.story()) {
            // No write holds an issue's priority, and no other kind is carried out.
            throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
        }
        if (!correction.choices().contains(asked.choice())
                || !Story.PRIORITIES.contains(asked.choice())) {
            throw refused(asked, "The choice is none of those the correction offers.");
        }
        // The priority touches no issue, so no run at work on the story is stopped for it.
        return new Preview(
                board.revision().value(),
                asked,
                "",
                new Writes(Map.of(), Map.of(), Map.of(), Map.of(id, asked.choice())),
                MovePreview.versions(board, Targets.of(board, new Operation.Correct(asked))));
    }

    /**
     * A correction of one of the project's repositories as the person asked for it.
     *
     * @param repository the repository's name
     * @param warning the kind of the warning it sets right
     * @param choice what the person picked, or null; a Labels offers no choice, so one sent is
     *     refused
     */
    public record Repository(String repository, String warning, @Nullable String choice) {}

    /**
     * The calls a repository's correction makes, checked against the board the store holds.
     *
     * @param revision the board's revision the preview was read at
     * @param asked the correction
     * @param calls every call it makes on the repository's labels, in their order
     */
    public record Labels(String revision, Repository asked, List<RepositoryWarning.Call> calls) {

        /** Copies the calls. */
        public Labels {
            calls = List.copyOf(calls);
        }

        /**
         * Answers the digest over what this preview showed and where its save goes: the project,
         * the repository, the warning's kind and every call; never the board's revision.
         *
         * @param repository the project's own repository, as the planner was given it
         * @param branch its branch, or null for the remote's default
         * @return a SHA-256 as 64 lower-case hexadecimal characters
         */
        public String digest(final String repository, final @Nullable String branch) {
            final MovePreview.Digest digest = new MovePreview.Digest();
            digest.add("porelo-planner labels preview 1");
            digest.add(repository);
            digest.add(branch == null ? "\u0000" : branch);
            digest.add(asked.repository());
            digest.add(asked.warning());
            digest.count(calls.size());
            for (final RepositoryWarning.Call call : calls) {
                digest.add(call.action().spelled());
                digest.add(call.label());
                digest.add(call.color() == null ? "\u0000" : call.color());
                digest.add(call.description() == null ? "\u0000" : call.description());
            }
            return digest.hex();
        }
    }

    /**
     * Checks a repository's correction on a board, writing nothing: the calls its warning's Labels
     * makes, as the store keeps the repository's labels now.
     *
     * @param board the board
     * @param asked the correction
     * @return the calls and the revision they were read at
     * @throws Moves.Refused {@link #REFUSED} when the repository is none of the project's, the
     *     warning no longer stands on it or a choice is sent
     * @throws UnsupportedOperationException when the warning offers no Labels
     */
    static Labels checkRepository(final Board board, final Repository asked) {
        final String name = asked.repository();
        if (!board.names().contains(name)) {
            throw refused(asked, "The project has no repository " + name + ".");
        }
        final RepositoryWarning warning =
                board.repositoryWarning(name, asked.warning())
                        .orElseThrow(() -> refused(asked, "The warning no longer stands on it."));
        final Correction correction = warning.correction();
        if (correction == null || !Correction.LABELS.equals(correction.kind())) {
            throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
        }
        if (asked.choice() != null) {
            throw refused(asked, "The choice is none of those the correction offers.");
        }
        return new Labels(board.revision().value(), asked, warning.calls());
    }

    private static Moves.Refused refused(final Repository asked, final String reason) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", asked.repository());
        parameters.put("warning", asked.warning());
        parameters.put("reason", reason);
        return new Moves.Refused(REFUSED, reason, parameters);
    }

    private static Moves.Refused refused(final Asked asked, final String reason) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", asked.id());
        parameters.put("warning", asked.warning());
        parameters.put("reason", reason);
        return new Moves.Refused(REFUSED, reason, parameters);
    }
}
