package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.git.stories.StageRecords;
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

    /** How long a save waits for a check or another save of the project. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(30);

    private final List<Repository> repositories;

    private final Remote remote;

    private final PartReader reader;

    private final ProjectLock lock;

    private final Clock clock;

    private final Duration interval;

    private final int parallel;

    private final Executor background;

    private final LocalSaves saves;

    private final AtomicReference<@Nullable Snapshot> current = new AtomicReference<>();

    private final AtomicBoolean checking = new AtomicBoolean();

    private final AtomicBoolean retired = new AtomicBoolean();

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
        this(
                repositories,
                remote,
                reader,
                lock,
                clock,
                interval,
                parallel,
                background,
                LocalSaves.none(repositories, remote));
    }

    /**
     * Makes a store whose saves stand in the planner's own clones and are pushed in the background.
     *
     * @param repositories the project's repositories, its own first
     * @param remote reaches them
     * @param reader reads one at a commit
     * @param lock keeps a refresh and a save of the project apart
     * @param clock tells when the last check was
     * @param interval how old the last check may be before a request starts another
     * @param parallel how many remotes are asked at once
     * @param background runs a check a request started
     * @param saves holds the saves and pushes them
     */
    public GitStore(
            final List<Repository> repositories,
            final Remote remote,
            final PartReader reader,
            final ProjectLock lock,
            final Clock clock,
            final Duration interval,
            final int parallel,
            final Executor background,
            final LocalSaves saves) {
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
        this.saves = saves;
        saves.tell(this::pushed);
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
            // A save a former start left unpushed goes out now.
            saves.push();
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
        final Board board = refreshed();
        // A push that failed is tried again with each check.
        saves.push();
        return board;
    }

    private Board refreshed() {
        try (ProjectLock.Held held = lock.acquire()) {
            requireActive();
            final Snapshot old = current.get();
            // A repository with saves not pushed yet is read at its last save, never fetched over.
            final Map<String, String> commits =
                    each(
                            repository -> {
                                final String name = repository.name();
                                if (saves.pending(name)) {
                                    return saves.tip(name).orElseThrow();
                                }
                                final String head =
                                        remote.head(name, repository.url(), repository.branch());
                                if (old != null && head.equals(old.commits().get(name))) {
                                    return head;
                                }
                                return remote.fetch(name, repository.url(), repository.branch());
                            });
            final String own = repositories.get(0).name();
            final Part ownPart =
                    old != null && unchanged(old, commits, own)
                            ? of(old.parts(), own)
                            : ownPart(of(commits, own));
            final org.fuin.porelo.planner.store.git.stories.Workflow project = project(ownPart);
            // A part read against another workflow is read again, since its checks may differ.
            final boolean sameWorkflow =
                    old != null && sameWorkflow(of(old.parts(), own).workflow(), project);
            final Map<String, Part> parts =
                    each(
                            repository -> {
                                final String name = repository.name();
                                if (own(repository)) {
                                    return ownPart;
                                }
                                if (old != null && sameWorkflow && unchanged(old, commits, name)) {
                                    return of(old.parts(), name);
                                }
                                return reader.read(repository, of(commits, name), project);
                            });
            return swap(commits, parts, clock.instant()).board();
        }
    }

    /**
     * Saves the writes: every repository they change gets one commit in the planner's own clone,
     * all built and read back before any stands, and the answer comes once they stand; the pushes
     * follow in the background, the others' before the own.
     */
    @Override
    public Board save(final Revision revision, final Writes writes) {
        return saved(revision, writes, null).board();
    }

    /**
     * Adds the issue as a move is saved: its file and its repository's index in one commit, read
     * back before it is pushed.
     */
    @Override
    public NewIssue.Added createIssue(final Revision revision, final NewIssue issue) {
        if (repositories.stream().noneMatch(r -> r.name().equals(issue.repository()))) {
            throw new StoreException.NotSavable(
                    "", issue.repository() + " is no repository of the project");
        }
        final Saved saved = saved(revision, new Writes(Map.of(), Map.of(), List.of()), issue);
        final String id = saved.added();
        if (id == null) {
            throw new IllegalStateException("a save of a new issue gave it no id");
        }
        return new NewIssue.Added(id, saved.board());
    }

    /** A save's board, and the id it gave a new issue, if it added one. */
    private record Saved(Board board, @Nullable String added) {}

    private Saved saved(
            final Revision revision, final Writes writes, final @Nullable NewIssue idea) {
        final Saved saved = committed(revision, writes, idea);
        saves.push();
        return saved;
    }

    private Saved committed(
            final Revision revision, final Writes writes, final @Nullable NewIssue idea) {
        final String own = repositories.get(0).name();
        final ProjectLock.Held held =
                lock.acquire(LOCK_WAIT).orElseThrow(() -> new StoreException.Busy(own));
        try (held) {
            requireActive();
            final Snapshot snapshot = current.get();
            if (snapshot == null || !snapshot.board().revision().equals(revision)) {
                throw new StoreException.StaleRevision(
                        snapshot == null ? "" : snapshot.board().revision().value());
            }
            final Map<String, Map<String, String>> issues = byRepository(snapshot, writes);
            final Map<String, String> stories = storyHeads(snapshot, writes);
            final Map<String, Map<String, Worker>> workers = workerHeads(snapshot, writes);
            final Map<String, String> priorities = priorityHeads(snapshot, writes);
            if (issues.isEmpty()
                    && stories.isEmpty()
                    && workers.isEmpty()
                    && priorities.isEmpty()
                    && idea == null) {
                return new Saved(snapshot.board(), null);
            }
            final Path scratch;
            try {
                scratch = remote.scratch();
            } catch (final UncheckedIOException e) {
                throw new StoreException.Unreadable(own, "no directory for the save");
            }
            try {
                return commitLocally(
                        snapshot,
                        new Edits(writes, idea, issues, stories, workers, priorities),
                        scratch);
            } catch (final GitFailure | UncheckedIOException e) {
                throw new StoreException.Unreadable(own, local(e, scratch));
            } finally {
                deleteScratch(scratch, own);
            }
        }
    }

    /** A scratch directory left behind holds only copies of fetched files; the answer stands. */
    private void deleteScratch(final Path scratch, final String own) {
        try {
            SaveFiles.delete(scratch);
        } catch (final UncheckedIOException e) {
            lastFailure.set(new StoreException.Unreadable(own, local(e, scratch)));
        }
    }

    /** A failure's words with the save's scratch directory taken out, so no local path is shown. */
    private static String local(final Throwable thrown, final Path scratch) {
        return GitRunner.scrubbed(
                causes(thrown).replace(scratch + "/", "").replace(scratch.toString(), ""));
    }

    @Override
    public @Nullable String storyState(final List<String> issueStates) {
        final Snapshot snapshot = current.get();
        final Part own = snapshot == null ? null : snapshot.parts().get(repositories.get(0).name());
        final org.fuin.porelo.planner.store.git.stories.Workflow workflow =
                own == null ? null : own.workflow();
        if (workflow == null) {
            throw new IllegalStateException("No board was read yet");
        }
        return workflow.storyState(null, issueStates);
    }

    /**
     * Answers what a size took in one of the project's repositories, from its stage records at the
     * board's commit, as {@code stage median} computes it; a person's moves count for nothing.
     *
     * @param repository the repository's name
     * @param size the size, {@code XS} to {@code XL}
     * @return the median minutes, or empty when the repository keeps no {@code stats/seed.csv}
     * @throws StoreException.Unreadable when its seed or a record cannot be read, naming why
     */
    public Optional<Double> velocity(final String repository, final String size) {
        final Snapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("No board was read yet");
        }
        final String commit = snapshot.commits().get(repository);
        if (commit == null) {
            throw new IllegalArgumentException(repository + " is no repository of the project");
        }
        try {
            final Optional<String> seed = remote.read(repository, commit, "stats/seed.csv");
            if (seed.isEmpty()) {
                return Optional.empty();
            }
            final Map<String, String> files = new LinkedHashMap<>();
            for (final String path : remote.list(repository, commit, "stats")) {
                if (!path.endsWith(".jsonl")) {
                    continue;
                }
                files.put(
                        path.substring(path.lastIndexOf('/') + 1),
                        remote.read(repository, commit, path).orElse(""));
            }
            return Optional.of(
                    StageRecords.statistic(
                            seed.get(),
                            files,
                            size,
                            false,
                            project(of(snapshot.parts(), repositories.get(0).name()))));
        } catch (final GitFailure e) {
            throw refusal(repository, e);
        } catch (final IllegalArgumentException e) {
            throw new StoreException.Unreadable(
                    repository, "its velocity cannot be read: " + e.getMessage());
        }
    }

    /** Each written issue's file and new state, by repository; every issue checked savable. */
    private Map<String, Map<String, String>> byRepository(
            final Snapshot snapshot, final Writes writes) {
        final Map<String, Map<String, String>> files = new LinkedHashMap<>();
        for (final Map.Entry<String, String> write : writes.issues().entrySet()) {
            final String id = write.getKey();
            final Issue issue =
                    snapshot.board()
                            .issue(id)
                            .orElseThrow(() -> new StoreException.NoSuchIssue(id));
            final String name = issue.repository();
            final String path = of(snapshot.parts(), name).paths().get(id);
            if (path == null) {
                throw new StoreException.NotSavable(
                        id, "its file is gone and only its index row is left");
            }
            files.computeIfAbsent(name, key -> new LinkedHashMap<>()).put(path, write.getValue());
        }
        return files;
    }

    /**
     * Each changed worker's issue file and its worker, by repository; every one checked savable
     * before anything is written, an unchanged one left out.
     */
    private Map<String, Map<String, Worker>> workerHeads(
            final Snapshot snapshot, final Writes writes) {
        final org.fuin.porelo.planner.store.git.stories.Workflow workflow =
                project(of(snapshot.parts(), repositories.get(0).name()));
        final Map<String, Map<String, Worker>> files = new LinkedHashMap<>();
        for (final Map.Entry<String, Worker> write : writes.workers().entrySet()) {
            final String id = write.getKey();
            final Issue issue =
                    snapshot.board()
                            .issue(id)
                            .orElseThrow(
                                    () ->
                                            new StoreException.NotSavable(
                                                    id, "it is not on the board"));
            if (issue.parent() != null) {
                throw new StoreException.NotSavable(
                        id, "a sub-issue is worked as its parent " + issue.parent() + " is");
            }
            final String state = writes.issues().get(id);
            if (state != null && workflow.states().contains(state) && workflow.closed(state)) {
                throw new StoreException.NotSavable(
                        id, "the same save closes it, and its file goes");
            }
            if (issue.worker() == write.getValue()) {
                continue;
            }
            final String name = issue.repository();
            final String path = of(snapshot.parts(), name).paths().get(id);
            if (path == null) {
                throw new StoreException.NotSavable(
                        id, "its file is gone and only its index row is left");
            }
            files.computeIfAbsent(name, key -> new LinkedHashMap<>()).put(path, write.getValue());
        }
        return files;
    }

    /**
     * What one save edits: the writes asked for, a new issue, and the files they change.
     *
     * @param writes the writes asked for
     * @param idea the new issue, if one is added
     * @param issues each moved issue's file and new state, by repository
     * @param stories each story's file and new stated state, empty where it goes
     * @param workers each changed worker's issue file and worker, by repository
     * @param priorities each changed priority's story file and priority
     */
    private record Edits(
            Writes writes,
            @Nullable NewIssue idea,
            Map<String, Map<String, String>> issues,
            Map<String, String> stories,
            Map<String, Map<String, Worker>> workers,
            Map<String, String> priorities) {}

    /**
     * Each changed priority's story file and its priority; every story checked on the board and
     * savable before anything is written, an unchanged one left out.
     */
    private Map<String, String> priorityHeads(final Snapshot snapshot, final Writes writes) {
        final Part own = of(snapshot.parts(), repositories.get(0).name());
        final Map<String, String> files = new LinkedHashMap<>();
        for (final Map.Entry<String, String> write : writes.priorities().entrySet()) {
            final String id = write.getKey();
            final org.fuin.porelo.planner.store.Story story =
                    snapshot.board()
                            .story(id)
                            .orElseThrow(() -> new StoreException.NoSuchStory(id));
            if (story.priority().equals(write.getValue())) {
                continue;
            }
            final String path = own.paths().get(Part.story(id));
            if (path == null) {
                throw new StoreException.NoSuchStory(id);
            }
            files.put(path, write.getValue());
        }
        return files;
    }

    /** Each written story's file and its new stated state, empty where it goes. */
    private Map<String, String> storyHeads(final Snapshot snapshot, final Writes writes) {
        final Part own = of(snapshot.parts(), repositories.get(0).name());
        final Map<String, String> files = new LinkedHashMap<>();
        final Map<String, String> states = new LinkedHashMap<>(writes.stories());
        writes.unstated().forEach(id -> states.put(id, ""));
        for (final Map.Entry<String, String> write : states.entrySet()) {
            final String id = write.getKey();
            final String path = own.paths().get(Part.story(id));
            if (path == null) {
                throw new StoreException.NoSuchStory(id);
            }
            files.put(path, write.getValue());
        }
        return files;
    }

    private Saved commitLocally(final Snapshot snapshot, final Edits edits, final Path scratch) {
        final Writes writes = edits.writes();
        final @Nullable NewIssue idea = edits.idea();
        final Map<String, Map<String, String>> issues = edits.issues();
        final Map<String, String> stories = edits.stories();
        final Map<String, Map<String, Worker>> workers = edits.workers();
        final Map<String, String> priorities = edits.priorities();
        final String own = repositories.get(0).name();
        final Map<String, Map<String, String>> before = new LinkedHashMap<>();
        for (final Repository repository : repositories) {
            final String name = repository.name();
            try {
                before.put(
                        name,
                        SaveFiles.write(
                                remote,
                                name,
                                of(snapshot.commits(), name),
                                scratch.resolve(Ids.name(name))));
            } catch (final GitFailure | IllegalArgumentException e) {
                throw refusal(name, e);
            }
        }
        final IdeaFile.Written added = idea == null ? null : idea(snapshot, idea, scratch);
        // Written before the indexes, which read the heads; a worker leaves every index alone.
        workers.forEach(
                (name, heads) ->
                        heads.forEach(
                                (path, worker) ->
                                        setWorker(
                                                snapshot,
                                                name,
                                                scratch.resolve(name),
                                                path,
                                                worker)));
        final Set<String> indexed = new java.util.LinkedHashSet<>(issues.keySet());
        if (idea != null) {
            indexed.add(idea.repository());
        }
        final Map<String, List<String>> rewritten = new LinkedHashMap<>();
        for (final String name : indexed) {
            final Map<String, String> moved = issues.getOrDefault(name, Map.of());
            final Path root = scratch.resolve(name);
            for (final Map.Entry<String, String> head : moved.entrySet()) {
                edit(
                        snapshot,
                        name,
                        root,
                        head.getKey(),
                        text -> HeadEdit.issueState(text, head.getValue()));
            }
            final List<String> heads = written(moved.keySet(), added, idea, name);
            heads.addAll(workers.getOrDefault(name, Map.of()).keySet());
            final List<String> written =
                    new ArrayList<>(writeIndex(snapshot, name, root, scratch, heads));
            written.addAll(recordMoves(snapshot, writes, name, root, moved));
            final List<String> settled = settled(snapshot, moved);
            if (!settled.isEmpty()) {
                // The row is written from the head first, then outlives the file it was read from.
                for (final String path : settled) {
                    settle(snapshot, name, root, path);
                }
                written.addAll(writeIndex(snapshot, name, root, scratch, heads));
            }
            rewritten.put(name, written);
        }
        final Path ownRoot = scratch.resolve(own);
        final Set<String> storyFiles = new java.util.LinkedHashSet<>(stories.keySet());
        storyFiles.addAll(priorities.keySet());
        // A story moved and given a priority in one save is edited once, with both.
        for (final String path : storyFiles) {
            final String state = stories.get(path);
            final String priority = priorities.get(path);
            edit(
                    snapshot,
                    own,
                    ownRoot,
                    path,
                    text -> {
                        final String stated =
                                state == null
                                        ? text
                                        : HeadEdit.storyState(text, state.isEmpty() ? null : state);
                        return priority == null ? stated : HeadEdit.storyPriority(stated, priority);
                    });
        }
        // A worker alone changes no row of the stories index, so it leaves the own repository be.
        final boolean storiesIndexed = !issues.isEmpty() || !storyFiles.isEmpty() || idea != null;
        if (storiesIndexed && Files.isDirectory(ownRoot.resolve("stories"))) {
            final org.fuin.porelo.planner.store.git.stories.StoriesIndex.Result index;
            try {
                index =
                        org.fuin.porelo.planner.store.git.stories.StoriesIndex.write(
                                ownRoot.resolve("stories"),
                                org.fuin.porelo.planner.store.git.stories.IssueSource.local(
                                        ownRoot, project(of(snapshot.parts(), own))));
            } catch (final IOException | UncheckedIOException e) {
                throw new StoreException.Unreadable(own, local(e, scratch));
            } catch (final IllegalArgumentException e) {
                // A project.yml the library refuses, such as one naming a central it does not take.
                throw new StoreException.NotSavable("", local(e, scratch));
            }
            final List<String> edited = new ArrayList<>(storyFiles);
            issues.values().forEach(heads -> edited.addAll(heads.keySet()));
            workers.values().forEach(heads -> edited.addAll(heads.keySet()));
            if (added != null) {
                edited.add(added.path());
            }
            refuseFaults(snapshot, own, edited, index.faults());
            rewritten
                    .computeIfAbsent(own, key -> new ArrayList<>())
                    .addAll(relative(ownRoot, index.written()));
        }
        final Map<String, String> subjects = new LinkedHashMap<>();
        final Map<String, List<String>> changed = new LinkedHashMap<>();
        for (final Repository repository : repositories) {
            final String name = repository.name();
            final Map<String, String> edited = issues.getOrDefault(name, Map.of());
            // The heads edited and every file the index library rewrote, the story texts too.
            final Map<String, Worker> set = workers.getOrDefault(name, Map.of());
            final List<String> heads = written(edited.keySet(), added, idea, name);
            heads.addAll(set.keySet());
            heads.addAll(rewritten.getOrDefault(name, List.of()));
            if (name.equals(own)) {
                heads.addAll(storyFiles);
            }
            final List<String> paths =
                    SaveFiles.changed(scratch.resolve(name), of(before, name), heads);
            if (paths.isEmpty()) {
                continue;
            }
            final Map<String, String> movedIssues = new LinkedHashMap<>();
            writes.issues()
                    .forEach(
                            (id, state) -> {
                                final String path = of(snapshot.parts(), name).paths().get(id);
                                if (path != null && edited.containsKey(path)) {
                                    movedIssues.put(id, state);
                                }
                            });
            final Map<String, String> movedStories =
                    name.equals(own) ? stories : Map.<String, String>of();
            subjects.put(
                    name,
                    SaveFiles.subject(
                            movedIssues,
                            movedStories,
                            set.size(),
                            name.equals(own) ? priorities.size() : 0,
                            added != null,
                            paths));
            changed.put(name, paths);
        }
        final Map<String, String> made = new LinkedHashMap<>();
        final Map<String, Part> after = new LinkedHashMap<>(snapshot.parts());
        for (final Map.Entry<String, List<String>> entry : changed.entrySet()) {
            final String name = entry.getKey();
            try {
                final String commit =
                        remote.commit(
                                name,
                                of(snapshot.commits(), name),
                                scratch.resolve(name),
                                entry.getValue(),
                                of(subjects, name));
                readBack(name, commit, scratch.resolve(name), entry.getValue());
                final Part part =
                        reader.read(
                                repository(name),
                                commit,
                                own(repository(name)) ? null : project(of(snapshot.parts(), own)));
                if (added != null && idea != null && name.equals(idea.repository())) {
                    readsBack(name, part, added.id(), idea.worker());
                }
                workersReadBack(snapshot, name, part, workers.getOrDefault(name, Map.of()));
                after.put(name, part);
                made.put(name, commit);
            } catch (final GitFailure e) {
                throw refusal(name, e);
            }
        }
        // The whole board after the save reads before anything leaves.
        final Map<String, String> all = new LinkedHashMap<>(snapshot.commits());
        all.putAll(made);
        board(all, after);
        final Map<String, String> parents = new LinkedHashMap<>();
        made.keySet().forEach(name -> parents.put(name, of(snapshot.commits(), name)));
        // The answer stands once the commits do; the pushes follow in the background.
        if (!saves.record(parents, made)) {
            throw new StoreException.StaleRevision(revision(tips()));
        }
        return new Saved(
                swapAfter(snapshot, all, after).board(), added == null ? null : added.id());
    }

    /**
     * Adds a move line to the stage records of each issue the save moves in a repository, in the
     * save's copy of it.
     *
     * @return the record files written, by their paths in the repository
     */
    private List<String> recordMoves(
            final Snapshot snapshot,
            final Writes writes,
            final String name,
            final Path root,
            final Map<String, String> moved) {
        final org.fuin.porelo.planner.store.git.stories.Workflow workflow =
                project(of(snapshot.parts(), repositories.get(0).name()));
        final String commit = of(snapshot.commits(), name);
        final Instant at = clock.instant();
        final List<String> written = new ArrayList<>();
        for (final Map.Entry<String, String> write : writes.issues().entrySet()) {
            final String id = write.getKey();
            final String path = of(snapshot.parts(), name).paths().get(id);
            final Issue issue = snapshot.board().issue(id).orElse(null);
            if (path == null
                    || !moved.containsKey(path)
                    || issue == null
                    || issue.state().equals(write.getValue())
                    || !StageRecords.recorded(id)) {
                continue;
            }
            final String stats = StageRecords.path(id);
            final String text;
            try {
                text =
                        StageRecords.move(
                                remote.read(name, commit, stats).orElse(null),
                                id,
                                issue.size(),
                                issue.state(),
                                write.getValue(),
                                at,
                                workflow);
            } catch (final GitFailure e) {
                throw refusal(name, e);
            } catch (final IllegalArgumentException e) {
                throw new StoreException.NotSavable(
                        id, stats + " cannot take the move: " + e.getMessage());
            }
            try {
                final Path file = root.resolve(RepoPath.checked(stats));
                Files.createDirectories(file.getParent());
                Files.writeString(file, text, StandardCharsets.UTF_8);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            written.add(stats);
        }
        return written;
    }

    /** Writes the new issue's file in the save's copy of its repository. */
    private IdeaFile.Written idea(
            final Snapshot snapshot, final NewIssue idea, final Path scratch) {
        final String own = repositories.get(0).name();
        try {
            return IdeaFile.write(
                    scratch.resolve(idea.repository()),
                    scratch.resolve(own),
                    idea.repository().equals(own),
                    idea,
                    project(of(snapshot.parts(), own)));
        } catch (final IllegalArgumentException e) {
            throw new StoreException.NotSavable("", local(e, scratch));
        } catch (final IOException e) {
            throw new StoreException.Unreadable(idea.repository(), local(e, scratch));
        }
    }

    /** The heads a save wrote in a repository: those it edited, and a new issue's file. */
    private static List<String> written(
            final java.util.Collection<String> edited,
            final IdeaFile.@Nullable Written added,
            final @Nullable NewIssue idea,
            final String name) {
        final List<String> heads = new ArrayList<>(edited);
        if (added != null && idea != null && name.equals(idea.repository())) {
            heads.add(added.path());
        }
        return heads;
    }

    /**
     * A new issue is pushed only once its commit reads it back in the state and with the worker it
     * was written with.
     */
    private static void readsBack(
            final String name, final Part part, final String id, final Worker worker) {
        if (part.issues().stream()
                .noneMatch(
                        issue ->
                                issue.id().equals(id)
                                        && issue.state().equals(IdeaFile.STATE)
                                        && issue.worker() == worker)) {
            throw new StoreException.Unreadable(
                    name, "the new issue " + id + " does not read back from its commit");
        }
    }

    /** A worker is pushed only once its commit reads the issue back with it. */
    private static void workersReadBack(
            final Snapshot snapshot,
            final String name,
            final Part part,
            final Map<String, Worker> workers) {
        for (final Map.Entry<String, Worker> set : workers.entrySet()) {
            final String id = item(snapshot, name, set.getKey());
            if (part.issues().stream()
                    .noneMatch(
                            issue -> issue.id().equals(id) && issue.worker() == set.getValue())) {
                throw new StoreException.Unreadable(
                        name, "the worker of " + id + " does not read back from its commit");
            }
        }
    }

    /**
     * Sets who works an issue in the save's copy of its file, refusing a head that does not read
     * back with it, such as one written several members a line.
     */
    private void setWorker(
            final Snapshot snapshot,
            final String name,
            final Path root,
            final String path,
            final Worker worker) {
        edit(snapshot, name, root, path, text -> HeadEdit.issueWorker(text, worker));
        final org.fuin.porelo.planner.store.git.stories.IssueHead head;
        try {
            head =
                    org.fuin.porelo.planner.store.git.stories.IssueHead.read(
                            Files.readString(root.resolve(path), StandardCharsets.UTF_8),
                            project(of(snapshot.parts(), repositories.get(0).name())));
        } catch (final IOException e) {
            throw new StoreException.Unreadable(
                    name, item(snapshot, name, path) + " could not be read");
        } catch (final IllegalArgumentException e) {
            throw new StoreException.NotSavable(
                    item(snapshot, name, path),
                    "its head does not take the worker: " + e.getMessage());
        }
        if (head == null || !worker.name().equalsIgnoreCase(String.valueOf(head.worker()))) {
            throw new StoreException.NotSavable(
                    item(snapshot, name, path), "its head does not take the worker");
        }
    }

    /** Writes a repository's issue indexes from its heads, refusing what the index refuses. */
    private List<String> writeIndex(
            final Snapshot snapshot,
            final String name,
            final Path root,
            final Path scratch,
            final java.util.Collection<String> heads) {
        final org.fuin.porelo.planner.store.git.stories.IssuesIndex.Result index;
        try {
            index =
                    org.fuin.porelo.planner.store.git.stories.IssuesIndex.write(
                            root.resolve("issues"),
                            project(of(snapshot.parts(), repositories.get(0).name())));
        } catch (final IOException | UncheckedIOException e) {
            throw new StoreException.Unreadable(name, local(e, scratch));
        } catch (final IllegalArgumentException e) {
            throw new StoreException.NotSavable("", local(e, scratch));
        }
        refuseFaults(snapshot, name, heads, index.faults());
        return relative(root, index.written());
    }

    /** The files of the issues a save moves into a closed state, which it settles. */
    private List<String> settled(final Snapshot snapshot, final Map<String, String> heads) {
        final org.fuin.porelo.planner.store.git.stories.Workflow workflow =
                project(of(snapshot.parts(), repositories.get(0).name()));
        return heads.entrySet().stream()
                .filter(head -> workflow.closed(head.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Settles a closed issue: its row names it without a link to its file, and the file goes, so
     * the row is all that is left of it.
     */
    private static void settle(
            final Snapshot snapshot, final String name, final Path root, final String path) {
        final Path file = root.resolve(path);
        final String id = item(snapshot, name, path);
        final Path index = file.resolveSibling("README.md");
        final String anchor = "<a id=\"" + id.toLowerCase(java.util.Locale.ROOT) + "\"></a>";
        final String linked = anchor + "[" + id + "](" + file.getFileName() + ")";
        try {
            final String text = Files.readString(index, StandardCharsets.UTF_8);
            if (id.isEmpty() || !text.contains(linked)) {
                throw new StoreException.NotSavable(
                        id, "its index row does not stand where the index writes it");
            }
            Files.writeString(index, text.replace(linked, anchor + id), StandardCharsets.UTF_8);
            Files.delete(file);
        } catch (final IOException e) {
            throw new StoreException.Unreadable(name, id + " could not be settled");
        }
    }

    /** Edits one head, a refusal naming the item whose file it is. */
    private static void edit(
            final Snapshot snapshot,
            final String name,
            final Path root,
            final String path,
            final java.util.function.UnaryOperator<String> edit) {
        final Path file = root.resolve(path);
        try {
            Files.writeString(
                    file,
                    edit.apply(Files.readString(file, StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException e) {
            throw new StoreException.NotSavable(
                    item(snapshot, name, path), String.valueOf(e.getMessage()));
        } catch (final IOException e) {
            throw new StoreException.Unreadable(
                    name, item(snapshot, name, path) + " could not be edited");
        }
    }

    /** Refuses the save when the index library finds faults, naming the moved item they concern. */
    private static void refuseFaults(
            final Snapshot snapshot,
            final String name,
            final java.util.Collection<String> edited,
            final List<String> faults) {
        if (faults.isEmpty()) {
            return;
        }
        String item = "";
        for (final String path : edited) {
            final String file = path.substring(path.lastIndexOf('/') + 1);
            // The index names a subgroup's file by its path below issues/, not its name alone.
            final String below = path.substring(path.indexOf('/') + 1);
            if (faults.stream()
                    .anyMatch(
                            fault ->
                                    fault.startsWith(file + ":")
                                            || fault.startsWith(below + ":"))) {
                item = item(snapshot, name, path);
                break;
            }
        }
        throw new StoreException.NotSavable(
                item, "the planning index refuses it: " + GitRunner.scrubbed(faults.get(0)));
    }

    /** The id of the issue or story whose file a path is, or empty. */
    private static String item(final Snapshot snapshot, final String name, final String path) {
        final Part part = snapshot.parts().get(name);
        if (part == null) {
            return "";
        }
        return part.paths().entrySet().stream()
                .filter(entry -> entry.getValue().equals(path))
                .map(Map.Entry::getKey)
                .map(
                        key ->
                                key.startsWith(Part.story(""))
                                        ? key.substring(Part.story("").length())
                                        : key)
                .findFirst()
                .orElse("");
    }

    private static List<String> relative(final Path root, final List<Path> written) {
        return written.stream()
                .map(path -> root.relativize(path.toAbsolutePath().normalize()))
                .filter(path -> !path.startsWith(".."))
                .map(path -> path.toString().replace('\\', '/'))
                .toList();
    }

    private Repository repository(final String name) {
        return repositories.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    /** Every repository's commit as its board is read now: its last save, or what was fetched. */
    private Map<String, String> tips() {
        final Map<String, String> tips = new LinkedHashMap<>();
        for (final Repository repository : repositories) {
            saves.tip(repository.name()).ifPresent(tip -> tips.put(repository.name(), tip));
        }
        return tips;
    }

    /** The board after a save, its changed repositories as read from their new commits. */
    private Snapshot swapAfter(
            final Snapshot snapshot,
            final Map<String, String> commits,
            final Map<String, Part> after) {
        final Map<String, Part> parts = new LinkedHashMap<>(snapshot.parts());
        for (final Repository repository : repositories) {
            final String name = repository.name();
            if (!of(commits, name).equals(snapshot.commits().get(name))) {
                parts.put(name, of(after, name));
            }
        }
        return swap(commits, parts, snapshot.checked());
    }

    /** A commit is pushed only once it holds exactly what the save wrote. */
    private void readBack(
            final String name, final String commit, final Path root, final List<String> paths) {
        for (final String path : paths) {
            final Path file = root.resolve(path);
            if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (remote.read(name, commit, path).isPresent()) {
                    throw new StoreException.Unreadable(
                            name, path + " is still there though the save deleted it");
                }
                continue;
            }
            final String wrote;
            try {
                wrote = Files.readString(file, StandardCharsets.UTF_8);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            if (!remote.read(name, commit, path).orElse("").equals(wrote)) {
                throw new StoreException.Unreadable(
                        name, path + " reads back otherwise than the save wrote it");
            }
        }
    }

    private String revision(final Map<String, String> commits) {
        return revision(repositories.stream().map(Repository::name).toList(), commits);
    }

    /**
     * The revision of the repositories' commits: the SHA-256 of each one's {@code <name>@<commit>},
     * 64 hexadecimal characters however many repositories the project has, since the window keeps
     * it and sends it back as a header.
     *
     * @param names the repositories, in the project's order
     * @param commits each one's commit
     * @return the revision's text
     */
    static String revision(final List<String> names, final Map<String, String> commits) {
        final String text =
                names.stream()
                        .map(name -> name + "@" + of(commits, name))
                        .collect(Collectors.joining(" "));
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }

    @Override
    public void retire() {
        retired.set(true);
        saves.stop();
    }

    /** Refuses a store retired while a read waited on the project's lock. */
    private void requireActive() {
        if (retired.get()) {
            throw new StoreException.Unreadable(
                    repositories.get(0).name(),
                    "the project was given anew while this read waited");
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
            requireActive();
            final Snapshot raced = current.get();
            if (raced != null) {
                return Optional.of(raced);
            }
            final Map<String, String> commits = new LinkedHashMap<>();
            for (final Repository repository : repositories) {
                final Optional<String> fetched = saves.tip(repository.name());
                if (fetched.isEmpty()) {
                    return Optional.empty();
                }
                commits.put(repository.name(), fetched.get());
            }
            final Part ownPart = ownPart(of(commits, repositories.get(0).name()));
            final org.fuin.porelo.planner.store.git.stories.Workflow project = project(ownPart);
            final Map<String, Part> parts =
                    each(
                            repository ->
                                    own(repository)
                                            ? ownPart
                                            : reader.read(
                                                    repository,
                                                    of(commits, repository.name()),
                                                    project));
            // Checked never: the first request starts a check at once.
            return Optional.of(swap(commits, parts, Instant.MIN));
        }
    }

    /** Rebuilds the board after a round of pushes, which tells what became of the saves. */
    private void pushed() {
        // A refresh that swapped the board meanwhile is built on and tried again.
        for (int attempt = 0; attempt < 3; attempt++) {
            final Snapshot snapshot = current.get();
            if (snapshot == null || retired.get() || swappedAfterPush(snapshot)) {
                return;
            }
        }
    }

    /**
     * Rebuilds one board after a round of pushes.
     *
     * @return whether nothing is left to do: the board was swapped, needs no change, or failed
     */
    private boolean swappedAfterPush(final Snapshot snapshot) {
        try {
            // A save made again on another's change, or dropped, is read with the next check.
            final boolean moved =
                    repositories.stream()
                            .anyMatch(
                                    repository ->
                                            !saves.tip(repository.name())
                                                    .equals(
                                                            Optional.ofNullable(
                                                                    snapshot.commits()
                                                                            .get(
                                                                                    repository
                                                                                            .name()))));
            final Board board = board(snapshot.commits(), snapshot.parts());
            if (!moved && board.equals(snapshot.board())) {
                return true;
            }
            final Snapshot next =
                    new Snapshot(
                            board,
                            snapshot.commits(),
                            snapshot.parts(),
                            moved ? Instant.MIN : snapshot.checked());
            if (!current.compareAndSet(snapshot, next)) {
                return false;
            }
            if (!snapshot.board().equals(board)) {
                listeners.forEach(listener -> listener.accept(board));
            }
            return true;
        } catch (final RuntimeException e) {
            lastFailure.set(
                    new StoreException.Unreadable(
                            repositories.get(0).name(), GitRunner.scrubbed(causes(e))));
            return true;
        }
    }

    /** What the board tells of saves whose push failed or that were dropped. */
    private List<String> pushFaults(final Map<String, Part> parts) {
        final List<String> faults = new ArrayList<>();
        for (final Repository repository : repositories) {
            final LocalSaves.PushState state = saves.state().get(repository.name());
            if (state == null) {
                continue;
            }
            switch (state.kind()) {
                case FAILED ->
                        faults.add(
                                repository.name()
                                        + ": a save is not pushed yet and is tried again with the"
                                        + " next check: "
                                        + state.words());
                case DROPPED ->
                        faults.add(
                                repository.name()
                                        + ": a save was dropped, since the remote changed "
                                        + dropped(parts.get(repository.name()), state.dropped())
                                        + " too; move them again");
                case PENDING, PUSHED -> {}
            }
        }
        return faults;
    }

    /** The items whose files a dropped save changed, or its files where they name none. */
    private static String dropped(final @Nullable Part part, final List<String> paths) {
        final List<String> items = new ArrayList<>();
        if (part != null) {
            part.paths()
                    .forEach(
                            (key, path) -> {
                                if (paths.contains(path)) {
                                    items.add(
                                            key.startsWith(Part.story(""))
                                                    ? key.substring(Part.story("").length())
                                                    : key);
                                }
                            });
        }
        items.sort(null);
        return items.isEmpty() ? String.join(", ", paths) : String.join(", ", items);
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
        final org.fuin.porelo.planner.store.git.stories.Workflow read = ownPart.workflow();
        if (read == null) {
            throw new StoreException.WorkflowMissing(own);
        }
        final List<String> faults = new ArrayList<>();
        final Map<String, Issue> known = new LinkedHashMap<>();
        final Set<String> twice = new HashSet<>();
        for (final Repository repository : repositories) {
            faults.addAll(of(parts, repository.name()).faults());
            for (final Issue issue : of(parts, repository.name()).issues()) {
                if (!read.states().contains(issue.state())) {
                    faults.add(
                            issue.repository()
                                    + "#"
                                    + issue.id()
                                    + " is in "
                                    + issue.state()
                                    + ", which the board's workflow does not know, and is left out");
                } else if (known.containsKey(issue.id()) || twice.contains(issue.id())) {
                    twice.add(issue.id());
                    known.remove(issue.id());
                    faults.add(issue.id() + " is in two repositories, and is left out");
                } else {
                    known.put(issue.id(), issue);
                }
            }
        }
        faults.addAll(pushFaults(parts));
        final List<Issue> issues = new ArrayList<>(known.values());
        final Revision revision = new Revision(revision(commits));
        try {
            final Workflow workflow =
                    new Workflow(
                            read.states(),
                            read.phases().stream()
                                    .map(phase -> new Workflow.Phase(phase.name(), phase.states()))
                                    .toList(),
                            read.states().stream().filter(read::closed).toList());
            final Map<String, Issue> byId = new LinkedHashMap<>();
            issues.forEach(issue -> byId.put(issue.id(), issue));
            final List<Story> stories = new ArrayList<>();
            for (final org.fuin.porelo.planner.store.git.stories.Story story : ownPart.stories()) {
                try {
                    stories.add(story(story, byId, read, faults));
                } catch (final IllegalArgumentException e) {
                    // A story whose state cannot be told is left out, not the project's board.
                    faults.add(e.getMessage() + ", and the story is left out");
                }
            }
            final Work work = ownPart.work();
            return new Board(
                    revision,
                    workflow,
                    stories,
                    issues,
                    work != null ? work : WorkReader.read(null),
                    repositories.stream().map(Repository::name).toList(),
                    faults);
        } catch (final IllegalArgumentException e) {
            throw new StoreException.Unreadable(own, String.valueOf(e.getMessage()));
        }
    }

    /**
     * A story as the board shows it: its issues by id, and its state as the stories index gives it.
     */
    private Story story(
            final org.fuin.porelo.planner.store.git.stories.Story story,
            final Map<String, Issue> issues,
            final org.fuin.porelo.planner.store.git.stories.Workflow workflow,
            final List<String> faults) {
        final List<String> ids = new ArrayList<>();
        final List<String> states = new ArrayList<>();
        boolean dropped = false;
        for (final String reference : story.issues()) {
            final int hash = reference.indexOf('#');
            final String repository = reference.substring(0, Math.max(hash, 0));
            final Issue issue = issues.get(reference.substring(hash + 1));
            if (issue == null || !issue.repository().equals(repository)) {
                // Read by its number alone, the story would show another repository's issue.
                faults.add(
                        story.id()
                                + ": "
                                + reference
                                + " is no issue of the project's repositories, and is left out");
                dropped = true;
                continue;
            }
            ids.add(issue.id());
            states.add(issue.state());
        }
        final String stated = story.state();
        if (stated == null && states.isEmpty()) {
            throw new IllegalArgumentException(
                    story.id()
                            + (dropped
                                    ? " states no state and none of its issues is there"
                                    : " states no state and names no issue"));
        }
        // With issues the rule reads only their states, never the stated one.
        final String state = stated != null ? stated : workflow.storyState(null, states);
        if (state == null) {
            throw new IllegalArgumentException(
                    story.id() + ": no state follows from its issues' states " + states);
        }
        return new Story(
                story.id(),
                story.title(),
                state,
                stated != null,
                story.priority(),
                ids,
                story.body(),
                null);
    }

    private static <T> T of(final Map<String, T> map, final String repository) {
        final T value = map.get(repository);
        if (value == null) {
            throw new IllegalStateException("nothing read for " + repository);
        }
        return value;
    }

    private static boolean unchanged(
            final Snapshot old, final Map<String, String> heads, final String name) {
        return of(heads, name).equals(old.commits().get(name));
    }

    /** Reads the project's own repository, which holds the workflow every part is read against. */
    private Part ownPart(final String commit) {
        final Repository own = repositories.get(0);
        try {
            return reader.read(own, commit, null);
        } catch (final RuntimeException e) {
            throw refusal(own.name(), e);
        }
    }

    private org.fuin.porelo.planner.store.git.stories.Workflow project(final Part ownPart) {
        final org.fuin.porelo.planner.store.git.stories.Workflow project = ownPart.workflow();
        if (project == null) {
            throw new StoreException.WorkflowMissing(repositories.get(0).name());
        }
        return project;
    }

    private static boolean sameWorkflow(
            final org.fuin.porelo.planner.store.git.stories.@Nullable Workflow before,
            final org.fuin.porelo.planner.store.git.stories.Workflow now) {
        if (before == now) {
            return true;
        }
        if (before == null
                || !before.states().equals(now.states())
                || !before.phases().equals(now.phases())) {
            return false;
        }
        return now.states().stream()
                .allMatch(
                        state ->
                                before.closed(state) == now.closed(state)
                                        && before.measured(state) == now.measured(state));
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
                case FAILED, REJECTED ->
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
