package org.fuin.porelo.planner.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What is open at a run's place for one role, and the place's decision, as Porelo answers it: read
 * anew at each call, never kept by Porelo.
 *
 * @param head the head now at the place, or null when nothing stands there any more
 * @param decision the place's decision
 * @param mergedCommit the forwarded or merge commit, or null until one exists
 * @param mergedHead the branch that commit landed on, null with mergedCommit
 * @param decisionSince when the current decision began, or null when the answer is incomplete for a
 *     missing time
 * @param points the open threads whose last word is not the asking role's side
 * @param findings the findings no one answered, counted apart from the points
 * @param held the review items held back from the run at the place
 * @param complete false when part of the answer could not be read
 * @param reason why the answer is incomplete, null when it is complete
 * @param version a digest of the answer: equal versions are equal answers
 */
public record ReviewOpen(
        @Nullable String head,
        Decision decision,
        @Nullable String mergedCommit,
        @Nullable String mergedHead,
        @Nullable Instant decisionSince,
        List<Point> points,
        int findings,
        int held,
        boolean complete,
        @Nullable String reason,
        String version) {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    /** A place's decision. */
    public enum Decision {
        /** The push waits at the gate, or the pull request is open. */
        OPEN,
        /** The gate's owner returned the push with open threads. */
        RETURNED,
        /** The gate's owner rejected the push without open threads. */
        REJECTED,
        /** The gate found the push merged elsewhere. */
        LANDED,
        /** The gate forwarded the push, or an approving review stands on the pull request. */
        APPROVED,
        /** The pull request was merged. */
        MERGED,
        /** The pull request was closed unmerged. */
        CLOSED
    }

    /**
     * An open thread.
     *
     * @param thread the thread's id
     * @param path the file it is on, or null for a thread on the whole push
     * @param line the thread's first line on that file, null with path
     * @param lastBy who wrote its last item
     * @param lastAt when its last item was written
     */
    public record Point(
            String thread,
            @Nullable String path,
            @Nullable Integer line,
            String lastBy,
            Instant lastAt) {

        /** Refuses a blank thread or author, a line without a file and a line below one. */
        public Point {
            Objects.requireNonNull(lastAt, "lastAt");
            if (thread.isBlank() || lastBy.isBlank()) {
                throw new IllegalArgumentException("An open point names no thread or no author");
            }
            if (line != null && (path == null || line < 1)) {
                throw new IllegalArgumentException("An open point's line is no line of a file");
            }
        }
    }

    /**
     * Refuses an answer whose parts do not fit together: an incomplete one without a reason or a
     * complete one with one, a complete one without the time of its decision, a merge commit
     * without the branch it landed on, a commit that is no commit, a branch that is no branch name,
     * negative counts and a blank version.
     */
    public ReviewOpen {
        Objects.requireNonNull(decision, "decision");
        points = List.copyOf(points);
        if (complete == (reason != null)) {
            throw new IllegalArgumentException(
                    "An answer gives a reason exactly when it is incomplete");
        }
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("An incomplete answer gives a blank reason");
        }
        if (complete && decisionSince == null) {
            throw new IllegalArgumentException("A complete answer says when its decision began");
        }
        if ((mergedCommit == null) != (mergedHead == null)) {
            throw new IllegalArgumentException(
                    "An answer gives a merged commit exactly with the branch it landed on");
        }
        for (final String commit : new String[] {head, mergedCommit}) {
            if (commit != null && !COMMIT.matcher(commit).matches()) {
                throw new IllegalArgumentException("An answer names a commit that is no commit");
            }
        }
        if (mergedHead != null && !RunPlace.BRANCH.matcher(mergedHead).matches()) {
            throw new IllegalArgumentException(
                    "An answer's merged commit landed on what is no branch name");
        }
        if (findings < 0 || held < 0) {
            throw new IllegalArgumentException("An answer counts fewer than no items");
        }
        if (version.isBlank()) {
            throw new IllegalArgumentException("An answer has no version");
        }
    }
}
