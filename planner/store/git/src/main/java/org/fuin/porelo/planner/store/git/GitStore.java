package org.fuin.porelo.planner.store.git;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * A project's {@link PlanningStore} in Git. Every answer comes from one board in memory; a check
 * asks each remote only for its branch's commit, and fetches and reads again only the repositories
 * that moved.
 */
public final class GitStore implements PlanningStore {

    /** One board and what it was made of. */
    private record Snapshot(
            Board board, Map<String, String> commits, Map<String, Part> parts, Instant checked) {}

    private final List<Repository> repositories;

    private final Remote remote;

    private final PartReader reader;

    private final ProjectLock lock;

    private final Clock clock;

    private final Duration interval;

    private final int parallel;

    private final Executor background;

    private final AtomicReference<@Nullable Snapshot> current = new AtomicReference<>();

    private final AtomicBoolean checking = new AtomicBoolean();

    private final AtomicReference<@Nullable StoreException> lastFailure = new AtomicReference<>();

    private final List<Consumer<Board>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Makes a store.
     *
     * @param repositories the project's repositories, its own first
     * @param remote reaches them
     * @param reader reads one at a commit
     * @param lock keeps a refresh and a save of the project apart
     * @param clock tells when the last check was
     * @param interval how old the last check may be before a request starts another
     * @param parallel how many remotes are asked at once
     * @param background runs a check a request started
     */
    public GitStore(
            final List<Repository> repositories,
            final Remote remote,
            final PartReader reader,
            final ProjectLock lock,
            final Clock clock,
            final Duration interval,
            final int parallel,
            final Executor background) {
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("A project has at least its own repository");
        }
        if (repositories.stream().map(Repository::name).distinct().count() != repositories.size()) {
            throw new IllegalArgumentException("A project names a repository twice");
        }
        this.repositories = List.copyOf(repositories);
        this.remote = remote;
        this.reader = reader;
        this.lock = lock;
        this.clock = clock;
        this.interval = interval;
        this.parallel = parallel;
        this.background = background;
    }

    /**
     * Answers the board in memory at once; when the last check is older than the interval, one
     * check starts in the background, and requests meanwhile share it.
     */
    @Override
    public Board board() {
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            snapshot = fromClones().orElse(null);
            if (snapshot == null) {
                return refresh();
            }
        }
        if (!clock.instant().isBefore(snapshot.checked().plus(interval))
                && checking.compareAndSet(false, true)) {
            background.execute(
                    () -> {
                        try {
                            refresh();
                            lastFailure.set(null);
                        } catch (final StoreException e) {
                            // The board stays as it was; the failure is told, not thrown at a
                            // reader.
                            lastFailure.set(e);
                        } catch (final RuntimeException e) {
                            lastFailure.set(
                                    new StoreException.Unreadable(
                                            repositories.get(0).name(),
                                            GitRunner.scrubbed(causes(e))));
                        } finally {
                            checking.set(false);
                        }
                    });
        }
        return snapshot.board();
    }

    @Override
    public Board refresh() {
        try (ProjectLock.Held held = lock.acquire()) {
            final Snapshot old = current.get();
            final Map<String, String> heads =
                    each(
                            repository ->
                                    remote.head(
                                            repository.name(),
                                            repository.url(),
                                            repository.branch()));
            final Map<String, Map.Entry<String, Part>> read =
                    each(
                            repository -> {
                                final String name = repository.name();
                                if (old != null
                                        && of(heads, name).equals(old.commits().get(name))) {
                                    return Map.entry(
                                            of(old.commits(), name), of(old.parts(), name));
                                }
                                final String commit =
                                        remote.fetch(name, repository.url(), repository.branch());
                                return Map.entry(
                                        commit, reader.read(repository, commit, own(repository)));
                            });
            final Map<String, String> commits = new LinkedHashMap<>();
            final Map<String, Part> parts = new LinkedHashMap<>();
            read.forEach(
                    (name, entry) -> {
                        commits.put(name, entry.getKey());
                        parts.put(name, entry.getValue());
                    });
            return swap(commits, parts, clock.instant()).board();
        }
    }

    /**
     * Registers a listener told of every new board, on the thread that made it.
     *
     * @param listener the listener
     */
    public void listen(final Consumer<Board> listener) {
        listeners.add(listener);
    }

    /**
     * Answers why the last check in the background failed.
     *
     * @return the failure, or empty when it succeeded
     */
    public Optional<StoreException> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    /** Every message down the chain of causes, so the first fault is not lost behind a wrapper. */
    private static String causes(final Throwable thrown) {
        final StringBuilder text = new StringBuilder(String.valueOf(thrown.getMessage()));
        for (Throwable cause = thrown.getCause(); cause != null; cause = cause.getCause()) {
            text.append(": ").append(cause.getMessage());
        }
        return text.toString();
    }

    private Optional<Snapshot> fromClones() {
        try (ProjectLock.Held held = lock.acquire()) {
            final Snapshot raced = current.get();
            if (raced != null) {
                return Optional.of(raced);
            }
            final Map<String, String> commits = new LinkedHashMap<>();
            for (final Repository repository : repositories) {
                final Optional<String> fetched = remote.fetched(repository.name());
                if (fetched.isEmpty()) {
                    return Optional.empty();
                }
                commits.put(repository.name(), fetched.get());
            }
            final Map<String, Part> parts =
                    each(
                            repository ->
                                    reader.read(
                                            repository,
                                            of(commits, repository.name()),
                                            own(repository)));
            // Checked never: the first request starts a check at once.
            return Optional.of(swap(commits, parts, Instant.MIN));
        }
    }

    private Snapshot swap(
            final Map<String, String> commits,
            final Map<String, Part> parts,
            final Instant checked) {
        final Snapshot next =
                new Snapshot(
                        board(commits, parts), Map.copyOf(commits), Map.copyOf(parts), checked);
        final Snapshot previous = current.getAndSet(next);
        if (previous == null || !previous.board().equals(next.board())) {
            listeners.forEach(listener -> listener.accept(next.board()));
        }
        return next;
    }

    private Board board(final Map<String, String> commits, final Map<String, Part> parts) {
        final String own = repositories.get(0).name();
        final Part ownPart = of(parts, own);
        final org.fuin.porelo.stories.Workflow read = ownPart.workflow();
        if (read == null) {
            throw new StoreException.WorkflowMissing(own);
        }
        final List<Issue> issues = new ArrayList<>();
        for (final Repository repository : repositories) {
            issues.addAll(of(parts, repository.name()).issues());
        }
        final Revision revision =
                new Revision(
                        repositories.stream()
                                .map(
                                        repository ->
                                                repository.name()
                                                        + "@"
                                                        + of(commits, repository.name()))
                                .collect(Collectors.joining(" ")));
        try {
            final Workflow workflow =
                    new Workflow(
                            read.states(),
                            read.phases().stream()
                                    .map(phase -> new Workflow.Phase(phase.name(), phase.states()))
                                    .toList());
            final Map<String, Issue> byId = new LinkedHashMap<>();
            issues.forEach(issue -> byId.put(issue.id(), issue));
            final List<Story> stories = new ArrayList<>();
            for (final org.fuin.porelo.stories.Story story : ownPart.stories()) {
                stories.add(story(story, byId, read));
            }
            return new Board(revision, workflow, stories, issues);
        } catch (final IllegalArgumentException e) {
            throw new StoreException.Unreadable(own, String.valueOf(e.getMessage()));
        }
    }

    /**
     * A story as the board shows it: its issues by id, and its state as the stories index gives it.
     */
    private Story story(
            final org.fuin.porelo.stories.Story story,
            final Map<String, Issue> issues,
            final org.fuin.porelo.stories.Workflow workflow) {
        final List<String> ids = new ArrayList<>();
        final List<String> states = new ArrayList<>();
        for (final String reference : story.issues()) {
            final int hash = reference.indexOf('#');
            final String repository = reference.substring(0, Math.max(hash, 0));
            final Issue issue = issues.get(reference.substring(hash + 1));
            if (issue == null || !issue.repository().equals(repository)) {
                throw new IllegalArgumentException(
                        story.id()
                                + ": "
                                + reference
                                + " is no issue of the project's repositories");
            }
            ids.add(issue.id());
            states.add(issue.state());
        }
        final String stated = story.state();
        if (stated == null && states.isEmpty()) {
            throw new IllegalArgumentException(story.id() + " states no state and names no issue");
        }
        // With issues the rule reads only their states, never the stated one.
        final String state = stated != null ? stated : workflow.storyState("", states);
        return new Story(
                story.id(), story.title(), state, story.priority(), ids, story.body(), null);
    }

    private static <T> T of(final Map<String, T> map, final String repository) {
        final T value = map.get(repository);
        if (value == null) {
            throw new IllegalStateException("nothing read for " + repository);
        }
        return value;
    }

    private boolean own(final Repository repository) {
        return repository.name().equals(repositories.get(0).name());
    }

    private <T> Map<String, T> each(final Function<Repository, T> work) {
        final Semaphore permits = new Semaphore(parallel);
        final Map<String, Future<T>> futures = new LinkedHashMap<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (final Repository repository : repositories) {
                futures.put(
                        repository.name(),
                        threads.submit(
                                () -> {
                                    permits.acquire();
                                    try {
                                        return work.apply(repository);
                                    } finally {
                                        permits.release();
                                    }
                                }));
            }
            final Map<String, T> results = new LinkedHashMap<>();
            for (final Map.Entry<String, Future<T>> future : futures.entrySet()) {
                results.put(future.getKey(), answer(future.getKey(), future.getValue()));
            }
            return results;
        }
    }

    private static <T> T answer(final String repository, final Future<T> future) {
        try {
            return future.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException.Unreachable(repository, "interrupted");
        } catch (final ExecutionException e) {
            throw refusal(repository, e.getCause());
        }
    }

    static RuntimeException refusal(final String repository, final @Nullable Throwable cause) {
        if (cause instanceof StoreException store) {
            return store;
        }
        if (cause instanceof GitFailure git) {
            return switch (git.kind()) {
                case LOGIN -> new StoreException.CredentialRefused(repository);
                case UNREACHABLE ->
                        new StoreException.Unreachable(
                                repository, String.valueOf(git.getMessage()));
                case FAILED ->
                        new StoreException.Unreadable(repository, String.valueOf(git.getMessage()));
            };
        }
        if (cause instanceof IllegalArgumentException || cause instanceof UncheckedIOException) {
            return new StoreException.Unreadable(
                    repository, GitRunner.scrubbed(cause.getMessage()));
        }
        return new IllegalStateException("reading " + repository + " failed", cause);
    }
}
