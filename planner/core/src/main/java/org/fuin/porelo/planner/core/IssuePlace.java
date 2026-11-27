package org.fuin.porelo.planner.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where an issue's work waits, as a run keeps it: the place, whose work its runs there read, the
 * task bound there, the author runs used there and the last complete answer read there, so the
 * issue's next step and the waits of the people at that place can be judged after its task is gone.
 *
 * @param place the place
 * @param foreign whether the runs at the place read another task's work
 * @param task the bound task's name within the project, as Start took it, never its container
 * @param authorRuns the offers of the author runs used at the place, each once, in their order
 * @param last the last complete answer read at the place, or null while none was read
 * @param reviewed the head a reviewer run was last offered at there, or null while none was
 * @param again when the person started the place's task again by hand to work there, or null while
 *     no such run works
 */
public record IssuePlace(
        RunPlace place,
        boolean foreign,
        String task,
        List<String> authorRuns,
        @Nullable Review last,
        @Nullable String reviewed,
        @Nullable String again) {

    /** The most author runs a place keeps. */
    public static final int MOST_RUNS = 16;

    /** The most open points of an answer a place keeps; the count beside them keeps the rest. */
    public static final int MOST_POINTS = 100;

    /** The decisions after which the work no longer waits at its place. */
    private static final Set<ReviewOpen.Decision> GONE =
            Set.of(
                    ReviewOpen.Decision.APPROVED,
                    ReviewOpen.Decision.LANDED,
                    ReviewOpen.Decision.MERGED,
                    ReviewOpen.Decision.CLOSED);

    /**
     * Refuses a task that is no task's name, no author run, one that is no offer's id or one named
     * twice, and more than {@link #MOST_RUNS}.
     */
    public IssuePlace {
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(task, "task");
        authorRuns = List.copyOf(authorRuns);
        if (!RunPlace.TASK.matcher(task).matches()) {
            throw new IllegalArgumentException("A kept place names no task");
        }
        if (authorRuns.isEmpty()
                || authorRuns.size() > MOST_RUNS
                || new HashSet<>(authorRuns).size() != authorRuns.size()
                || authorRuns.stream().anyMatch(id -> !UUID.fromString(id).toString().equals(id))) {
            throw new IllegalArgumentException("A kept place's author runs do not fit");
        }
    }

    /**
     * A place no run started by hand works at.
     *
     * @param place the place
     * @param foreign whether the runs at the place read another task's work
     * @param task the bound task's name within the project
     * @param authorRuns the offers of the author runs used at the place
     * @param last the last complete answer read at the place, or null while none was read
     * @param reviewed the head a reviewer run was last offered at there, or null while none was
     */
    public IssuePlace(
            final RunPlace place,
            final boolean foreign,
            final String task,
            final List<String> authorRuns,
            final @Nullable Review last,
            final @Nullable String reviewed) {
        this(place, foreign, task, authorRuns, last, reviewed, null);
    }

    /**
     * A place no reviewer run was offered at.
     *
     * @param place the place
     * @param foreign whether the runs at the place read another task's work
     * @param task the bound task's name within the project
     * @param authorRuns the offers of the author runs used at the place
     * @param last the last complete answer read at the place, or null while none was read
     */
    public IssuePlace(
            final RunPlace place,
            final boolean foreign,
            final String task,
            final List<String> authorRuns,
            final @Nullable Review last) {
        this(place, foreign, task, authorRuns, last, null);
    }

    /**
     * The last complete answer read at a place, as a place keeps it: its open points cut to {@link
     * #MOST_POINTS} in Porelo's order, with how many there were beside them.
     *
     * @param head the head at the place, or null when nothing stands there any more
     * @param decision the place's decision
     * @param mergedCommit the forwarded or merge commit, or null
     * @param mergedHead the branch that commit landed on, null with mergedCommit
     * @param decisionSince when the decision began, or null where Porelo's answer may lack it
     * @param points the open points kept, each with the time of its last word
     * @param pointsTotal how many open points the answer named, the cut ones included
     * @param findings the findings no one answered
     * @param held the review items held back from the run at the place
     * @param version the answer's version
     */
    public record Review(
            @Nullable String head,
            ReviewOpen.Decision decision,
            @Nullable String mergedCommit,
            @Nullable String mergedHead,
            @Nullable Instant decisionSince,
            List<ReviewOpen.Point> points,
            int pointsTotal,
            int findings,
            int held,
            String version) {

        /**
         * Refuses what a complete answer would not hold, more than {@link #MOST_POINTS} points and
         * a count that does not fit them.
         */
        public Review {
            points = List.copyOf(points);
            // Its parts are checked as a complete answer's are.
            answer(head, decision, mergedCommit, mergedHead, decisionSince, points, findings, held);
            Objects.requireNonNull(version, "version");
            if (version.isBlank() || points.size() != Math.min(pointsTotal, MOST_POINTS)) {
                throw new IllegalArgumentException("A kept answer's points do not fit");
            }
        }

        /**
         * Keeps a complete answer.
         *
         * @param answer the answer
         * @return the answer as kept
         * @throws IllegalArgumentException for an incomplete answer, which is never kept
         */
        public static Review of(final ReviewOpen answer) {
            if (!answer.complete()) {
                throw new IllegalArgumentException("An incomplete answer is never kept");
            }
            final List<ReviewOpen.Point> all = answer.points();
            return new Review(
                    answer.head(),
                    answer.decision(),
                    answer.mergedCommit(),
                    answer.mergedHead(),
                    answer.decisionSince(),
                    all.subList(0, Math.min(all.size(), MOST_POINTS)),
                    all.size(),
                    answer.findings(),
                    answer.held(),
                    answer.version());
        }

        private static void answer(
                final @Nullable String head,
                final ReviewOpen.Decision decision,
                final @Nullable String mergedCommit,
                final @Nullable String mergedHead,
                final @Nullable Instant since,
                final List<ReviewOpen.Point> points,
                final int findings,
                final int held) {
            new ReviewOpen(
                    head,
                    decision,
                    mergedCommit,
                    mergedHead,
                    since,
                    points,
                    findings,
                    held,
                    true,
                    null,
                    "kept");
        }
    }

    /**
     * The place a builder's end leaves its work at: its own task's push at the gate, its author's.
     *
     * @param task the task's name within the project
     * @param offer the builder's offer
     * @return the place
     */
    public static IssuePlace atGate(final String task, final String offer) {
        return new IssuePlace(RunPlace.gate(null, task), false, task, List.of(offer), null);
    }

    /**
     * The place with one more author run used, or this place when it already names the run.
     *
     * @param offer the run's offer
     * @return the place
     */
    public IssuePlace usedBy(final String offer) {
        if (authorRuns.contains(offer)) {
            return this;
        }
        final List<String> runs = new ArrayList<>(authorRuns);
        runs.add(offer);
        return new IssuePlace(place, foreign, task, runs, last, reviewed, again);
    }

    /**
     * The place keeping a complete answer.
     *
     * @param answer the answer
     * @return the place
     * @throws IllegalArgumentException for an incomplete answer
     */
    public IssuePlace read(final ReviewOpen answer) {
        return new IssuePlace(place, foreign, task, authorRuns, Review.of(answer), reviewed, again);
    }

    /**
     * The place with a reviewer run offered at the head given.
     *
     * @param head the head the reviewer reviews, or null when no head counts as reviewed any more
     * @return the place
     */
    public IssuePlace reviewing(final @Nullable String head) {
        return new IssuePlace(place, foreign, task, authorRuns, last, head, again);
    }

    /**
     * Whether the place's last answer still lets its work wait there: none was read, or its
     * decision did not take the work away.
     *
     * @return true while the work may wait
     */
    public boolean open() {
        final Review review = last;
        return review == null || !GONE.contains(review.decision());
    }

    /**
     * The place an author's work goes on at once its gate push was forwarded: its task's own pull
     * request, for the same task and author runs, nothing read there yet.
     *
     * @return the place
     */
    public IssuePlace forwarded() {
        return new IssuePlace(RunPlace.own(), foreign, task, authorRuns, null, null, again);
    }

    /**
     * The place with a run the person started by hand in its task working there, or with none.
     *
     * @param since when that run started, or null once it ended
     * @return the place
     */
    public IssuePlace runAgain(final @Nullable String since) {
        return new IssuePlace(place, foreign, task, authorRuns, last, reviewed, since);
    }

    /**
     * Whether a place a task record names is the gate push this own pull request's work was
     * forwarded from: its task's own push, where its runs worked before the forwarding.
     *
     * @param read the place the record names
     * @param repository the repository the work is in
     * @return true for that push
     */
    public boolean forwardedFrom(final RunPlace read, final String repository) {
        return place.ownPullRequest() != null
                && own(read, repository).equals(RunPlace.gate(null, task));
    }

    /**
     * Whether a place read is this one: a push at the gate naming the task's own repository is that
     * task's own push, as Porelo takes a push naming none.
     *
     * @param read the place read
     * @param repository the repository the bound task works in
     * @return true for the same place
     */
    public boolean same(final RunPlace read, final String repository) {
        return own(place, repository).equals(own(read, repository));
    }

    private static RunPlace own(final RunPlace place, final String repository) {
        final RunPlace.GatePush push = place.gatePush();
        return push != null && repository.equals(push.repository())
                ? RunPlace.gate(null, push.name())
                : place;
    }
}
