package org.fuin.porelo.planner.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * What a batch of moves would do and what it can do harm by, answered before it is saved, so a
 * person confirms exactly what the save then writes.
 *
 * @param revision the board's revision the preview was read at
 * @param moves the batch, in the order sent
 * @param issues every issue the batch moves, each once
 * @param stories every story the batch moves
 * @param warnings what the batch can do harm by; empty when it is saved without asking
 */
public record MovePreview(
        String revision,
        List<Moves.Move> moves,
        List<MovedIssue> issues,
        List<MovedStory> stories,
        List<Warning> warnings) {

    /** A story's run has tasks at work over what moves; its stop ends or voids them. */
    public static final String RUNNING_WORK = "RUNNING_WORK";

    /** A story's move carries along issues in a working state or of another repository. */
    public static final String ISSUES_ALONG = "ISSUES_ALONG";

    /** A move forward past waiting states into an open one. */
    public static final String SKIPS_STATES = "SKIPS_STATES";

    /** A story would stand where its issues do not fit. */
    public static final String STORY_MISMATCH = "STORY_MISMATCH";

    /** Copies the lists. */
    public MovePreview {
        moves = List.copyOf(moves);
        issues = List.copyOf(issues);
        stories = List.copyOf(stories);
        warnings = List.copyOf(warnings);
    }

    /**
     * An issue a batch moves, or one a warning names.
     *
     * @param id the issue's id
     * @param repository the repository that holds it
     * @param from its state now
     * @param to its state after the batch
     */
    public record MovedIssue(String id, String repository, String from, String to) {}

    /**
     * A story a batch moves.
     *
     * @param id the story's id
     * @param from the state it shows now
     * @param to the state it is moved to
     */
    public record MovedStory(String id, String from, String to) {}

    /**
     * One harm a batch can do.
     *
     * @param kind one of the kinds named by this class's constants
     * @param item the story or issue it is about
     * @param from the item's state now
     * @param to the state the batch moves it to, or empty when the batch does not move it
     * @param run the run whose tasks it names, or null
     * @param tasks each task at work the run's stop ends
     * @param offers each offer not yet started the run's stop voids
     * @param issues the issues it names, with their states now and after the batch
     * @param skipped the waiting states a move forward passes
     */
    public record Warning(
            String kind,
            String item,
            String from,
            String to,
            @Nullable String run,
            List<String> tasks,
            List<String> offers,
            List<MovedIssue> issues,
            List<String> skipped) {

        /** Copies the lists. */
        public Warning {
            tasks = List.copyOf(tasks);
            offers = List.copyOf(offers);
            issues = List.copyOf(issues);
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * A run with tasks at work over items a batch touches.
     *
     * @param story the run's story
     * @param run the run's id
     * @param tasks each task at work
     * @param offers each offer not yet started, which the run's stop voids
     */
    public record Owned(String story, String run, List<String> tasks, List<String> offers) {

        /** Copies the lists. */
        public Owned {
            tasks = List.copyOf(tasks);
            offers = List.copyOf(offers);
        }
    }

    /** Answers the runs whose tasks at work own what a batch touches. */
    @FunctionalInterface
    public interface Running {

        /**
         * Answers the runs with tasks at work over the issues or the stories given.
         *
         * @param issues the issues the batch touches
         * @param stories the stories the batch moves
         * @return each such run, once
         */
        List<Owned> over(Set<String> issues, Set<String> stories);
    }

    static MovePreview of(
            final Board board,
            final List<Moves.Move> moves,
            final Moves.Plan plan,
            final Running running) {
        final Map<String, String> after = plan.states();
        final List<MovedIssue> issues = new ArrayList<>();
        for (final String id : plan.planned().moved()) {
            issues.add(moved(board, id, after));
        }
        final List<MovedStory> stories = new ArrayList<>();
        final Set<String> storyIds = new HashSet<>();
        final Set<String> ownMoves = new HashSet<>();
        for (final Moves.Move move : moves) {
            if (move.story()) {
                storyIds.add(move.id());
                stories.add(new MovedStory(move.id(), storyState(board, move.id()), move.to()));
            } else {
                ownMoves.add(move.id());
            }
        }
        final List<Warning> warnings = new ArrayList<>();
        for (final Owned owned : running.over(Moves.touched(board, moves), storyIds)) {
            warnings.add(
                    new Warning(
                            RUNNING_WORK,
                            owned.story(),
                            storyState(board, owned.story()),
                            moves.stream()
                                    .filter(m -> m.story() && m.id().equals(owned.story()))
                                    .map(Moves.Move::to)
                                    .findFirst()
                                    .orElse(""),
                            owned.run(),
                            owned.tasks(),
                            owned.offers(),
                            List.of(),
                            List.of()));
        }
        final String own = board.repositories().isEmpty() ? "" : board.repositories().getFirst();
        final Set<String> working = board.work().rules().keySet();
        for (final Map.Entry<String, List<String>> carried : plan.along().entrySet()) {
            final List<MovedIssue> along =
                    carried.getValue().stream()
                            // An issue's own move in the batch wins, so it was asked for.
                            .filter(id -> !ownMoves.contains(id))
                            .map(id -> moved(board, id, after))
                            .filter(
                                    issue ->
                                            !issue.repository().equals(own)
                                                    || working.contains(issue.from()))
                            .toList();
            if (!along.isEmpty()) {
                final String story = carried.getKey();
                warnings.add(
                        new Warning(
                                ISSUES_ALONG,
                                story,
                                storyState(board, story),
                                to(moves, story),
                                null,
                                List.of(),
                                List.of(),
                                along,
                                List.of()));
            }
        }
        for (final Moves.Move move : moves) {
            final String from =
                    move.story()
                            ? storyState(board, move.id())
                            : board.issue(move.id()).map(Issue::state).orElse("");
            final List<String> skipped = skipped(board.workflow(), working, from, move.to());
            if (!skipped.isEmpty()) {
                warnings.add(
                        new Warning(
                                SKIPS_STATES,
                                move.id(),
                                from,
                                move.to(),
                                null,
                                List.of(),
                                List.of(),
                                List.of(),
                                skipped));
            }
        }
        for (final Map.Entry<String, List<String>> misfit : plan.misfits().entrySet()) {
            final String story = misfit.getKey();
            warnings.add(
                    new Warning(
                            STORY_MISMATCH,
                            story,
                            storyState(board, story),
                            to(moves, story),
                            null,
                            List.of(),
                            List.of(),
                            misfit.getValue().stream().map(id -> moved(board, id, after)).toList(),
                            List.of()));
        }
        return new MovePreview(board.revision().value(), moves, issues, stories, warnings);
    }

    /**
     * Answers the digest over what this preview showed and where its save goes: the revision, the
     * project's repository and branch, the batch, the issues and stories it moves and the warnings
     * with the runs, tasks and offers they name.
     *
     * @param repository the project's own repository, as the planner was given it
     * @param branch its branch, or null for the remote's default
     * @return a SHA-256 as 64 lower-case hexadecimal characters
     */
    public String digest(final String repository, final @Nullable String branch) {
        final Digest digest = new Digest();
        digest.add("porelo-planner move preview 1");
        digest.add(revision);
        digest.add(repository);
        digest.add(branch == null ? "\u0000" : branch);
        digest.count(moves.size());
        for (final Moves.Move move : moves) {
            digest.add(move.story() ? "story" : "issue");
            digest.add(move.id());
            digest.add(move.to());
        }
        digest.count(issues.size());
        issues.forEach(digest::issue);
        digest.count(stories.size());
        for (final MovedStory story : stories) {
            digest.add(story.id());
            digest.add(story.from());
            digest.add(story.to());
        }
        digest.count(warnings.size());
        for (final Warning warning : warnings) {
            digest.add(warning.kind());
            digest.add(warning.item());
            digest.add(warning.from());
            digest.add(warning.to());
            digest.add(warning.run() == null ? "\u0000" : warning.run());
            digest.list(warning.tasks());
            digest.list(warning.offers());
            digest.count(warning.issues().size());
            warning.issues().forEach(digest::issue);
            digest.list(warning.skipped());
        }
        return digest.hex();
    }

    /** The waiting states a move forward into an open state passes; none for any other move. */
    private static List<String> skipped(
            final Workflow workflow,
            final Set<String> working,
            final String from,
            final String to) {
        final int start = workflow.states().indexOf(from);
        final int end = workflow.states().indexOf(to);
        // A closed target can be moved back at any time, so passing states on the way is no harm.
        if (start < 0 || end <= start + 1 || workflow.closed().contains(to)) {
            return List.of();
        }
        return workflow.states().subList(start + 1, end).stream()
                .filter(state -> !working.contains(state) && !workflow.closed().contains(state))
                .toList();
    }

    private static MovedIssue moved(
            final Board board, final String id, final Map<String, String> after) {
        final Issue issue = board.issue(id).orElseThrow();
        return new MovedIssue(
                id, issue.repository(), issue.state(), after.getOrDefault(id, issue.state()));
    }

    private static String storyState(final Board board, final String id) {
        return board.story(id).map(Story::state).orElse("");
    }

    private static String to(final List<Moves.Move> moves, final String story) {
        return moves.stream()
                .filter(move -> move.story() && move.id().equals(story))
                .map(Moves.Move::to)
                .findFirst()
                .orElse("");
    }

    /** Feeds length-prefixed fields to SHA-256, so no two different previews read alike. */
    private static final class Digest {

        private final MessageDigest sha;

        Digest() {
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (final NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is missing from the runtime", e);
            }
        }

        void add(final String value) {
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            count(bytes.length);
            sha.update(bytes);
        }

        void count(final int value) {
            sha.update(
                    new byte[] {
                        (byte) (value >>> 24),
                        (byte) (value >>> 16),
                        (byte) (value >>> 8),
                        (byte) value
                    });
        }

        void list(final List<String> values) {
            count(values.size());
            values.forEach(this::add);
        }

        void issue(final MovedIssue issue) {
            add(issue.id());
            add(issue.repository());
            add(issue.from());
            add(issue.to());
        }

        String hex() {
            return HexFormat.of().formatHex(sha.digest());
        }
    }
}
