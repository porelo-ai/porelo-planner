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
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
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
            requireActive();
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
     * Saves the writes: every repository they change gets one commit, all built and read back
     * before the first push, the others' pushed before the own, each only while its branch still
     * stands where it was fetched.
     */
    @Override
    public Board save(final Revision revision, final Writes writes) {
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
            final Map<String, String> heads =
                    each(
                            repository ->
                                    remote.head(
                                            repository.name(),
                                            repository.url(),
                                            repository.branch()));
            if (!heads.equals(snapshot.commits())) {
                throw new StoreException.StaleRevision(revision(heads));
            }
            final Map<String, Map<String, String>> issues = byRepository(snapshot, writes);
            final Map<String, String> stories = storyHeads(snapshot, writes);
            if (issues.isEmpty() && stories.isEmpty()) {
                return snapshot.board();
            }
            final Path scratch;
            try {
                scratch = remote.scratch();
            } catch (final UncheckedIOException e) {
                throw new StoreException.Unreadable(own, "no directory for the save");
            }
            try {
                return commitAndPush(snapshot, writes, issues, stories, scratch);
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
        final org.fuin.porelo.stories.Workflow workflow = own == null ? null : own.workflow();
        if (workflow == null) {
            throw new IllegalStateException("No board was read yet");
        }
        return workflow.storyState(null, issueStates);
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
            final String commit = of(snapshot.commits(), name);
            // The repository's commit check refuses a state change without the issue's record.
            if (records(name, commit, "stats/seed.csv")
                    && !records(name, commit, "stats/" + id + ".jsonl")) {
                throw new StoreException.NotSavable(
                        id, "the repository records issue stats and this issue has none");
            }
            files.computeIfAbsent(name, key -> new LinkedHashMap<>()).put(path, write.getValue());
        }
        return files;
    }

    private boolean records(final String name, final String commit, final String path) {
        try {
            return remote.read(name, commit, path).isPresent();
        } catch (final GitFailure e) {
            throw refusal(name, e);
        }
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

    private Board commitAndPush(
            final Snapshot snapshot,
            final Writes writes,
            final Map<String, Map<String, String>> issues,
            final Map<String, String> stories,
            final Path scratch) {
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
        final Map<String, List<String>> rewritten = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, String>> repository : issues.entrySet()) {
            final String name = repository.getKey();
            final Path root = scratch.resolve(name);
            for (final Map.Entry<String, String> head : repository.getValue().entrySet()) {
                edit(
                        snapshot,
                        name,
                        root,
                        head.getKey(),
                        text -> HeadEdit.issueState(text, head.getValue()));
            }
            final org.fuin.porelo.stories.IssuesIndex.Result index;
            try {
                index = org.fuin.porelo.stories.IssuesIndex.write(root.resolve("issues"));
            } catch (final IOException | UncheckedIOException e) {
                throw new StoreException.Unreadable(name, local(e, scratch));
            } catch (final IllegalArgumentException e) {
                throw new StoreException.NotSavable("", local(e, scratch));
            }
            refuseFaults(snapshot, name, repository.getValue().keySet(), index.faults());
            rewritten.put(name, new ArrayList<>(relative(root, index.written())));
        }
        final Path ownRoot = scratch.resolve(own);
        for (final Map.Entry<String, String> head : stories.entrySet()) {
            final String state = head.getValue();
            edit(
                    snapshot,
                    own,
                    ownRoot,
                    head.getKey(),
                    text -> HeadEdit.storyState(text, state.isEmpty() ? null : state));
        }
        if (Files.isDirectory(ownRoot.resolve("stories"))) {
            final org.fuin.porelo.stories.StoriesIndex.Result index;
            try {
                index =
                        org.fuin.porelo.stories.StoriesIndex.write(
                                ownRoot.resolve("stories"),
                                org.fuin.porelo.stories.IssueSource.local(ownRoot));
            } catch (final IOException | UncheckedIOException e) {
                throw new StoreException.Unreadable(own, local(e, scratch));
            } catch (final IllegalArgumentException e) {
                // A project.yml the library refuses, such as one naming a central it does not take.
                throw new StoreException.NotSavable("", local(e, scratch));
            }
            final List<String> edited = new ArrayList<>(stories.keySet());
            issues.values().forEach(heads -> edited.addAll(heads.keySet()));
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
            final List<String> heads = new ArrayList<>(edited.keySet());
            heads.addAll(rewritten.getOrDefault(name, List.of()));
            if (name.equals(own)) {
                heads.addAll(stories.keySet());
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
            subjects.put(name, SaveFiles.subject(movedIssues, movedStories));
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
                after.put(name, reader.read(repository(name), commit, own(repository(name))));
                made.put(name, commit);
            } catch (final GitFailure e) {
                throw refusal(name, e);
            }
        }
        // The whole board after the save reads before anything leaves.
        final Map<String, String> all = new LinkedHashMap<>(snapshot.commits());
        all.putAll(made);
        board(all, after);
        // The own repository last: its stories index names the others' states, pushed first.
        final List<String> order = new ArrayList<>(made.keySet());
        if (order.remove(own)) {
            order.add(own);
        }
        final List<String> pushed = new ArrayList<>();
        final Map<String, String> commits = new LinkedHashMap<>(snapshot.commits());
        for (final String name : order) {
            final Repository repository = repository(name);
            try {
                remote.push(
                        name,
                        repository.url(),
                        repository.branch(),
                        of(made, name),
                        of(snapshot.commits(), name));
            } catch (final GitFailure e) {
                if (pushed.isEmpty()) {
                    if (e.kind() == GitFailure.Kind.REJECTED) {
                        throw new StoreException.StaleRevision(revision(heads()));
                    }
                    throw refusal(name, e);
                }
                final StoreException.PartlySaved partly =
                        new StoreException.PartlySaved(
                                name, pushed, String.valueOf(refusal(name, e).getMessage()));
                try {
                    swapAfter(snapshot, commits, after);
                } catch (final RuntimeException board) {
                    partly.addSuppressed(board);
                }
                throw partly;
            }
            commits.put(name, of(made, name));
            pushed.add(name);
            try {
                remote.advance(name, of(made, name));
            } catch (final GitFailure e) {
                // The remote holds the save; the next fetch records its commit in the clone.
                lastFailure.set(
                        new StoreException.Unreadable(name, String.valueOf(e.getMessage())));
            }
        }
        return swapAfter(snapshot, commits, after).board();
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
            if (faults.stream().anyMatch(fault -> fault.startsWith(file + ":"))) {
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

    /** Every repository's branch head as the remotes hold it now. */
    private Map<String, String> heads() {
        return each(
                repository ->
                        remote.head(repository.name(), repository.url(), repository.branch()));
    }

    /** The board after a save, its changed repositories as read before the first push. */
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
            final String wrote;
            try {
                wrote = Files.readString(root.resolve(path), StandardCharsets.UTF_8);
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
            for (final org.fuin.porelo.stories.Story story : ownPart.stories()) {
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
            final org.fuin.porelo.stories.Story story,
            final Map<String, Issue> issues,
            final org.fuin.porelo.stories.Workflow workflow,
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
