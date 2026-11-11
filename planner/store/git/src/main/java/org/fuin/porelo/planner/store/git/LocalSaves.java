package org.fuin.porelo.planner.store.git;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A project's saves as commits in the planner's own clones, which a save answers once they stand,
 * and their pushes in the background: the other repositories' at once, the own repository's last
 * and only once the others are pushed, since its stories index names their states.
 *
 * <p>A save waits on a repository's {@link LocalRefs#local local ref} until it is pushed; a restart
 * finds it there. A push the remote rejects is made again on the remote's new commit when the
 * remote changed none of the paths the saves changed and the two did not both add an issue or both
 * a story; otherwise the saves are dropped and told, and the own repository's with them.
 */
public final class LocalSaves {

    /** What became of a repository's saves. */
    public enum Kind {
        /** Saved here, not pushed yet. */
        PENDING,
        /** Pushed. */
        PUSHED,
        /** The push failed and is tried again with the next check. */
        FAILED,
        /** The remote changed what the saves changed, and the saves were dropped. */
        DROPPED
    }

    /**
     * What became of a repository's saves.
     *
     * @param kind what became of them
     * @param words why a push failed, free of secrets, or empty
     * @param dropped the paths of dropped saves, sorted, or none
     */
    public record PushState(Kind kind, String words, List<String> dropped) {

        /** Copies the paths. */
        public PushState {
            dropped = List.copyOf(dropped);
        }
    }

    /** How often a push the remote rejects is made again on its new commit. */
    private static final int ATTEMPTS = 3;

    /** A story's or an issue's file, whose name carries a number each repository gives once. */
    private static final Pattern NUMBERED =
            Pattern.compile("(?:issues|stories)/[A-Z]{1,3}[0-9]+-[^/]*\\.md");

    private final List<Repository> repositories;

    private final Remote remote;

    private final LocalRefs refs;

    private final Executor background;

    /** Guards the refs: a save's commits and a push's moves of them never interleave. */
    private final Object refsMonitor = new Object();

    private final Map<String, PushState> states = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean();

    private final AtomicBoolean again = new AtomicBoolean();

    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile Runnable told = () -> {};

    /**
     * Makes the saves of a project.
     *
     * @param repositories the project's repositories, its own first
     * @param remote fetches and pushes them
     * @param refs holds the saves not yet pushed
     * @param background runs the pushes
     */
    public LocalSaves(
            final List<Repository> repositories,
            final Remote remote,
            final LocalRefs refs,
            final Executor background) {
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("A project has at least its own repository");
        }
        this.repositories = List.copyOf(repositories);
        this.remote = remote;
        this.refs = refs;
        this.background = background;
    }

    /**
     * Makes saves that never save, for a store that only reads.
     *
     * @param repositories the project's repositories, its own first
     * @param remote reads them
     * @return the saves
     */
    static LocalSaves none(final List<Repository> repositories, final Remote remote) {
        return new LocalSaves(repositories, remote, LocalRefs.none(), Runnable::run);
    }

    /**
     * Registers what is run after each round of pushes, on the thread that pushed.
     *
     * @param listener the listener
     */
    void tell(final Runnable listener) {
        told = listener;
    }

    /**
     * Answers the commit a repository's board is read at: its last save not yet pushed, or the
     * commit last fetched.
     *
     * @param repository the repository's name
     * @return the commit, or empty when nothing was fetched yet
     */
    public Optional<String> tip(final String repository) {
        synchronized (refsMonitor) {
            final Optional<String> local = refs.local(repository);
            return local.isPresent() ? local : remote.fetched(repository);
        }
    }

    /**
     * Answers whether a repository has saves not pushed yet.
     *
     * @param repository the repository's name
     * @return whether it has
     */
    public boolean pending(final String repository) {
        synchronized (refsMonitor) {
            return refs.local(repository).isPresent();
        }
    }

    /**
     * Makes a save's commits the repositories' saves, all or none, and only while each repository
     * still stands at the commit the save was made on.
     *
     * @param parents each saved repository's commit the save was made on, by name
     * @param commits each saved repository's new commit, its parent the one named, by name
     * @return whether they stood there; when not, nothing changed
     */
    public boolean record(final Map<String, String> parents, final Map<String, String> commits) {
        if (!parents.keySet().equals(commits.keySet())) {
            throw new IllegalArgumentException("a save names other parents than commits");
        }
        synchronized (refsMonitor) {
            final Map<String, @Nullable String> before = new LinkedHashMap<>();
            for (final Map.Entry<String, String> parent : parents.entrySet()) {
                final Optional<String> local = refs.local(parent.getKey());
                final Optional<String> tip =
                        local.isPresent() ? local : remote.fetched(parent.getKey());
                if (!tip.equals(Optional.of(parent.getValue()))) {
                    return false;
                }
                before.put(parent.getKey(), local.orElse(null));
            }
            final List<String> moved = new ArrayList<>();
            try {
                for (final Map.Entry<String, String> commit : commits.entrySet()) {
                    refs.move(commit.getKey(), commit.getValue(), before.get(commit.getKey()));
                    moved.add(commit.getKey());
                }
            } catch (final RuntimeException e) {
                undo(moved, before, commits, e);
                throw e;
            }
            commits.keySet()
                    .forEach(name -> states.put(name, new PushState(Kind.PENDING, "", List.of())));
            return true;
        }
    }

    /** Puts back the refs a save moved before one of its moves failed. */
    private void undo(
            final List<String> moved,
            final Map<String, @Nullable String> before,
            final Map<String, String> commits,
            final RuntimeException failure) {
        for (final String name : moved) {
            final String made = commits.get(name);
            if (made == null) {
                continue;
            }
            try {
                final String was = before.get(name);
                if (was == null) {
                    refs.delete(name, made);
                } else {
                    refs.move(name, was, made);
                }
            } catch (final RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
    }

    /**
     * Answers what became of each repository's saves.
     *
     * @return the states, by repository; a repository never saved is missing
     */
    public Map<String, PushState> state() {
        return Map.copyOf(states);
    }

    /**
     * Starts pushing the saves not pushed yet in the background, unless a round runs already, which
     * then runs once more.
     */
    public void push() {
        if (stopped.get()) {
            return;
        }
        again.set(true);
        if (running.compareAndSet(false, true)) {
            background.execute(this::drain);
        }
    }

    /** Stops pushing; saves not pushed yet wait in the clones for the next start. */
    public void stop() {
        stopped.set(true);
    }

    private void drain() {
        try {
            while (again.getAndSet(false) && !stopped.get()) {
                try {
                    round();
                } catch (final RuntimeException e) {
                    final String own = repositories.getFirst().name();
                    final PushState state = states.get(own);
                    // A dropped save stays told; a later failure must not hide its paths.
                    if (state == null || state.kind() != Kind.DROPPED) {
                        failed(own, GitRunner.scrubbed(String.valueOf(e.getMessage())));
                    }
                }
                try {
                    told.run();
                } catch (final RuntimeException e) {
                    // The board is read anew with the next check; the pushes go on.
                    again.set(true);
                }
            }
        } finally {
            running.set(false);
        }
        // A push asked for after the loop's last look and before the flag fell.
        if (again.get() && !stopped.get() && running.compareAndSet(false, true)) {
            background.execute(this::drain);
        }
    }

    /** Pushes the others' saves at once, then the own repository's once they all are pushed. */
    private void round() {
        final Repository own = repositories.getFirst();
        final List<Repository> others =
                repositories.stream().skip(1).filter(r -> pending(r.name())).toList();
        boolean all = true;
        if (!others.isEmpty()) {
            final Map<String, Future<Boolean>> futures = new LinkedHashMap<>();
            try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
                for (final Repository repository : others) {
                    futures.put(repository.name(), threads.submit(() -> pushed(repository, false)));
                }
                for (final Map.Entry<String, Future<Boolean>> future : futures.entrySet()) {
                    all &= answer(future.getKey(), future.getValue());
                }
            }
        }
        final boolean dropped =
                others.stream()
                        .map(r -> states.get(r.name()))
                        .anyMatch(state -> state != null && state.kind() == Kind.DROPPED);
        if (dropped && pending(own.name())) {
            drop(own.name());
        } else if (all && pending(own.name())) {
            pushed(own, true);
        }
    }

    /**
     * Drops the own repository's saves after another's were dropped, since its stories index names
     * the states the dropped saves set.
     */
    private void drop(final String name) {
        try {
            synchronized (refsMonitor) {
                final Optional<String> local = refs.local(name);
                final Optional<String> base = remote.fetched(name);
                if (local.isEmpty() || base.isEmpty()) {
                    return;
                }
                final List<String> paths = refs.changed(name, base.get(), local.get());
                refs.delete(name, local.get());
                states.put(name, new PushState(Kind.DROPPED, "", paths));
            }
        } catch (final RuntimeException e) {
            failed(name, GitRunner.scrubbed(String.valueOf(e.getMessage())));
        }
    }

    private boolean answer(final String name, final Future<Boolean> future) {
        try {
            return future.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            failed(name, "interrupted");
            return false;
        } catch (final ExecutionException e) {
            failed(name, GitRunner.scrubbed(String.valueOf(e.getCause())));
            return false;
        }
    }

    /**
     * Pushes a repository's saves, made again on the remote's new commit while it changed none of
     * their paths.
     *
     * @param last whether the repository goes only once no other has a save waiting
     * @return whether they are pushed
     */
    private boolean pushed(final Repository repository, final boolean last) {
        final String name = repository.name();
        try {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                final Optional<String> local;
                final String base;
                synchronized (refsMonitor) {
                    local = refs.local(name);
                    if (local.isEmpty()) {
                        return true;
                    }
                    if (last && othersWaiting()) {
                        // A save of another came during the round; the next round takes both.
                        again.set(true);
                        return false;
                    }
                    base =
                            remote.fetched(name)
                                    .orElseThrow(
                                            () ->
                                                    new GitFailure(
                                                            GitFailure.Kind.FAILED,
                                                            "nothing fetched to push on"));
                }
                final String tip = local.get();
                try {
                    remote.push(name, repository.url(), repository.branch(), tip, base);
                } catch (final GitFailure e) {
                    if (e.kind() != GitFailure.Kind.REJECTED) {
                        throw e;
                    }
                    if (!replayed(repository, base, tip, e)) {
                        return false;
                    }
                    continue;
                }
                synchronized (refsMonitor) {
                    remote.advance(name, tip);
                    if (refs.local(name).equals(Optional.of(tip))) {
                        refs.delete(name, tip);
                    } else {
                        // A save came while this one was pushed; the next round takes it.
                        again.set(true);
                    }
                    states.put(name, new PushState(Kind.PUSHED, "", List.of()));
                }
                return true;
            }
            failed(name, "the remote moved again during each of " + ATTEMPTS + " pushes");
            return false;
        } catch (final GitFailure e) {
            failed(name, String.valueOf(e.getMessage()));
            return false;
        } catch (final RuntimeException e) {
            failed(name, GitRunner.scrubbed(String.valueOf(e.getMessage())));
            return false;
        }
    }

    /**
     * Makes the saves again on the remote's new commit, or drops them when the remote changed a
     * path they changed.
     *
     * @return whether they were made again
     */
    private boolean replayed(
            final Repository repository,
            final String base,
            final String tip,
            final GitFailure rejection) {
        final String name = repository.name();
        final String head = refs.fetchRemote(name, repository.url(), repository.branch());
        if (head.equals(base)) {
            // The remote did not move: it refuses for another reason, which a replay cannot change.
            throw rejection;
        }
        if (refs.commits(name, base, tip).contains(head)) {
            // An earlier push of these saves landed without being recorded; it is the new base.
            synchronized (refsMonitor) {
                remote.advance(name, head);
            }
            return true;
        }
        final Set<String> theirs = new HashSet<>(refs.changed(name, base, head));
        if (refs.changed(name, base, tip).stream().anyMatch(theirs::contains)
                || issueAddedOnBoth(name, base, tip, head)) {
            synchronized (refsMonitor) {
                final String current = refs.local(name).orElse(tip);
                final List<String> dropped = refs.changed(name, base, current);
                refs.delete(name, current);
                remote.advance(name, head);
                states.put(name, new PushState(Kind.DROPPED, "", dropped));
            }
            return false;
        }
        String onto = head;
        for (final String commit : refs.commits(name, base, tip)) {
            onto = refs.replay(name, commit, onto);
        }
        synchronized (refsMonitor) {
            if (refs.local(name).equals(Optional.of(tip))) {
                refs.move(name, onto, tip);
                remote.advance(name, head);
            }
            // Otherwise a save came meanwhile on the old commit; the next attempt replays both.
        }
        return true;
    }

    /**
     * Whether both sides added an issue file, or both a story file, which may carry the number the
     * other took, even where the files' names differ.
     */
    private boolean issueAddedOnBoth(
            final String name, final String base, final String tip, final String head) {
        final List<String> mine = refs.added(name, base, tip);
        final List<String> theirs = refs.added(name, base, head);
        return Stream.of("issues/", "stories/")
                .anyMatch(directory -> added(mine, directory) && added(theirs, directory));
    }

    private static boolean added(final List<String> added, final String directory) {
        return added.stream()
                .anyMatch(path -> path.startsWith(directory) && NUMBERED.matcher(path).matches());
    }

    /** Whether another repository than the own has a save waiting; called under the monitor. */
    private boolean othersWaiting() {
        return repositories.stream().skip(1).anyMatch(r -> refs.local(r.name()).isPresent());
    }

    private void failed(final String name, final String words) {
        states.put(name, new PushState(Kind.FAILED, words, List.of()));
    }
}
