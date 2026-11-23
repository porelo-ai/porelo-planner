package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.TrackedStores;
import org.fuin.porelo.planner.store.cache.BoardCopies;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a project whose stories and issues a GitHub project keeps: opening sends GitHub nothing,
 * and a field the project lacks is a fault of the board, never made here; a write sets values and
 * never a field or its options.
 */
public final class GitHubStoreOpener implements TrackedStores {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubStoreOpener.class);

    /** The kind {@code project.yml} names a GitHub project's tracker by. */
    public static final String KIND = "github";

    /** The one forge a GitHub tracker is kept on; an enterprise host is not taken yet. */
    public static final URI GITHUB = URI.create("https://github.com");

    private final TokenSource tokens;

    private final Function<StoryStates, BoardReader> readers;

    private final Function<Touched, BoardWriter> writers;

    private final Supplier<ChangeProbe> probes;

    private final Duration lockWait;

    /** Runs the change probe's look right after a read or save. */
    private final Executor looks;

    /** Gives the place a project's board copy is kept, checked as each project is opened. */
    private final @Nullable Supplier<BoardCopies> copies;

    /** Gives each project's look for what changed on its board since the last one. */
    private final Supplier<TrackerChanges> changes;

    /** The pace of the person's token the reads are held by, or null when none is kept. */
    private final @Nullable GitHubPace pace;

    /** Runs a look after a read or save on a thread of its own, so the caller does not wait. */
    private static final Executor SETTLE_LOOKS =
            task -> Thread.ofVirtual().name("github-settle").start(task);

    /**
     * Makes an opener that reads and writes each project's board over GitHub's API.
     *
     * @param tokens gives the person's token for the forge
     * @param queries the GraphQL reads of a project
     * @param writes the calls a write to a project is made of
     * @param lockWait how long a call waits for another on the same project
     */
    public GitHubStoreOpener(
            final TokenSource tokens,
            final ProjectQueries queries,
            final ProjectWrites writes,
            final Duration lockWait) {
        this(
                tokens,
                states ->
                        new ItemsBoardReader(
                                queries, tokens, new ForgeAccessRefusals(GITHUB), states),
                touched ->
                        new FieldsBoardWriter(
                                touched.recording(writes), tokens, new ForgeAccessRefusals(GITHUB)),
                () -> ChangeProbe.ALWAYS_CHANGED,
                lockWait,
                SETTLE_LOOKS);
    }

    /**
     * Makes an opener that reads and writes each project's board over GitHub's API, and reads it
     * again only once GitHub answers a conditional ask that it changed; one pace of the person's
     * token holds every project while GitHub's rate limit is spent.
     *
     * @param tokens gives the person's token for the forge
     * @param queries the GraphQL reads of a project
     * @param writes the calls a write to a project is made of
     * @param conditional the conditional reads that ask whether a project changed
     * @param issuePages the pages of a repository's open issues, read so the board names those that
     *     are no items of the project
     * @param executor runs a project's conditional reads side by side, and a look after a read or
     *     save beside the caller
     * @param clock tells the time a wait of GitHub's ends by
     * @param lockWait how long a call waits for another on the same project
     */
    public GitHubStoreOpener(
            final TokenSource tokens,
            final ProjectQueries queries,
            final ProjectWrites writes,
            final ConditionalReads conditional,
            final RepositoryIssues.Pages issuePages,
            final Executor executor,
            final InstantSource clock,
            final Duration lockWait) {
        this(
                tokens,
                queries,
                writes,
                conditional,
                issuePages,
                executor,
                clock,
                lockWait,
                ItemsBoardReader.DEADLINE);
    }

    /**
     * Makes the opener above with its own limit on GitHub's silence during a project's read.
     *
     * @param tokens gives the person's token for the forge
     * @param queries the GraphQL reads of a project
     * @param writes the calls a write to a project is made of
     * @param conditional the conditional reads that ask whether a project changed
     * @param issuePages the pages of a repository's open issues, read so the board names those that
     *     are no items of the project
     * @param executor runs a project's conditional reads side by side, and a look after a read or
     *     save beside the caller
     * @param clock tells the time a wait of GitHub's ends by
     * @param lockWait how long a call waits for another on the same project
     * @param readSilence how long GitHub may leave a project's read without an answer
     */
    public GitHubStoreOpener(
            final TokenSource tokens,
            final ProjectQueries queries,
            final ProjectWrites writes,
            final ConditionalReads conditional,
            final RepositoryIssues.Pages issuePages,
            final Executor executor,
            final InstantSource clock,
            final Duration lockWait,
            final Duration readSilence) {
        this(
                tokens,
                queries,
                writes,
                conditional,
                issuePages,
                executor,
                new GitHubPace(clock),
                lockWait,
                readSilence);
    }

    private GitHubStoreOpener(
            final TokenSource tokens,
            final ProjectQueries queries,
            final ProjectWrites writes,
            final ConditionalReads conditional,
            final RepositoryIssues.Pages issuePages,
            final Executor executor,
            final GitHubPace pace,
            final Duration lockWait,
            final Duration readSilence) {
        this(
                tokens,
                states ->
                        new ItemsBoardReader(
                                pace.watching(queries),
                                pace.watching(issuePages),
                                tokens,
                                new ForgeAccessRefusals(GITHUB),
                                states,
                                readSilence),
                touched ->
                        new FieldsBoardWriter(
                                touched.recording(writes), tokens, new ForgeAccessRefusals(GITHUB)),
                () -> new ConditionalChangeProbe(conditional, tokens, pace, executor),
                lockWait,
                executor,
                null,
                () -> TrackerChanges.NONE,
                pace);
    }

    /**
     * Makes an opener with its own board reader and writer per project.
     *
     * @param tokens gives the person's token for the forge
     * @param readers makes a project's reader, given how its story states follow the workflow
     * @param writers makes a project's writer
     * @param lockWait how long a call waits for another on the same project
     */
    GitHubStoreOpener(
            final TokenSource tokens,
            final Function<StoryStates, BoardReader> readers,
            final Supplier<BoardWriter> writers,
            final Duration lockWait) {
        this(tokens, readers, writers, () -> ChangeProbe.ALWAYS_CHANGED, lockWait);
    }

    /**
     * Makes an opener with its own board reader, writer and change probe per project.
     *
     * @param tokens gives the person's token for the forge
     * @param readers makes a project's reader, given how its story states follow the workflow
     * @param writers makes a project's writer
     * @param probes makes a project's change probe
     * @param lockWait how long a call waits for another on the same project
     */
    GitHubStoreOpener(
            final TokenSource tokens,
            final Function<StoryStates, BoardReader> readers,
            final Supplier<BoardWriter> writers,
            final Supplier<ChangeProbe> probes,
            final Duration lockWait) {
        this(tokens, readers, touched -> writers.get(), probes, lockWait, SETTLE_LOOKS);
    }

    /**
     * Makes an opener whose writer per project records what its calls touch.
     *
     * @param tokens gives the person's token for the forge
     * @param readers makes a project's reader, given how its story states follow the workflow
     * @param writers makes a project's writer, given what records its calls
     * @param probes makes a project's change probe
     * @param lockWait how long a call waits for another on the same project
     * @param looks runs the change probe's look right after a read or save, beside the caller
     */
    private GitHubStoreOpener(
            final TokenSource tokens,
            final Function<StoryStates, BoardReader> readers,
            final Function<Touched, BoardWriter> writers,
            final Supplier<ChangeProbe> probes,
            final Duration lockWait,
            final Executor looks) {
        this(
                tokens,
                readers,
                writers,
                probes,
                lockWait,
                looks,
                null,
                () -> TrackerChanges.NONE,
                null);
    }

    private GitHubStoreOpener(
            final TokenSource tokens,
            final Function<StoryStates, BoardReader> readers,
            final Function<Touched, BoardWriter> writers,
            final Supplier<ChangeProbe> probes,
            final Duration lockWait,
            final Executor looks,
            final @Nullable Supplier<BoardCopies> copies,
            final Supplier<TrackerChanges> changes,
            final @Nullable GitHubPace pace) {
        this.pace = pace;
        this.copies = copies;
        this.changes = changes;
        this.looks = looks;
        this.tokens = tokens;
        this.readers = readers;
        this.writers = writers;
        this.probes = probes;
        this.lockWait = lockWait;
    }

    /**
     * The same opener, keeping each project's board on disk, so a start shows it before GitHub is
     * read.
     *
     * @param copies gives the copies' place; asked as each project is opened, and a place it cannot
     *     give leaves that project without a copy, read whole from GitHub as before
     * @return the opener
     */
    public GitHubStoreOpener keepingCopies(final Supplier<BoardCopies> copies) {
        return new GitHubStoreOpener(
                tokens,
                readers,
                writers,
                probes,
                lockWait,
                looks,
                Objects.requireNonNull(copies),
                changes,
                pace);
    }

    /**
     * The same opener, asking GitHub's item list at each poll what changed since the last look, so
     * only those items are read again.
     *
     * @param pages GitHub's REST item list
     * @param queries reads an item missing from the list, or new on it, by its issue
     * @return the opener
     */
    public GitHubStoreOpener lookingForChanges(
            final GitHubChanges.ItemPages pages, final ProjectQueries queries) {
        Objects.requireNonNull(pages, "pages");
        Objects.requireNonNull(queries, "queries");
        // The look goes through the token's pace, so a spent rate limit holds it as other reads.
        final GitHubPace held = pace;
        final GitHubChanges.ItemPages paced = held == null ? pages : held.watching(pages);
        final ProjectQueries watched = held == null ? queries : held.watching(queries);
        return new GitHubStoreOpener(
                tokens,
                readers,
                writers,
                probes,
                lockWait,
                looks,
                copies,
                () -> new GitHubChanges(paced, watched),
                pace);
    }

    @Override
    public PlanningStore open(final TrackedProject project, final Source again) {
        final ProjectConfig first = config(project);
        // The workflow read last decides a story's state, so a changed one counts at once.
        final AtomicReference<TrackedProject> latest = new AtomicReference<>(project);
        final StoryStates states =
                issueStates ->
                        Objects.requireNonNull(latest.get()).storyStates().storyState(issueStates);
        final Touched touched = new Touched();
        return new GitHubStore(
                first,
                () -> {
                    final TrackedProject now = again.current();
                    final ProjectConfig config = config(now);
                    latest.set(now);
                    return config;
                },
                GitHubStore.Parts.reading(readers.apply(states), states)
                        .writer(writers.apply(touched))
                        .refusals(new ForgeAccessRefusals(GITHUB))
                        .probe(probes.get())
                        .copies(copies())
                        .changes(changes.get()),
                tokens,
                lockWait,
                touched,
                looks);
    }

    /** The copies' place, or null when none is kept or it cannot be given now. */
    private @Nullable BoardCopies copies() {
        if (copies == null) {
            return null;
        }
        try {
            return copies.get();
        } catch (final RuntimeException e) {
            // Without its copy the board is read whole from GitHub, as it was before copies. The
            // failure's words may quote the environment or a path naming the person's account,
            // so only its class is named.
            LOG.warn(
                    "The board's copies cannot be kept ({}); the board is read whole from GitHub",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /** Checks the tracker the project's files name, and answers what its board is read with. */
    private static ProjectConfig config(final TrackedProject project) {
        final String own = project.repositories().get(0);
        if (!KIND.equals(project.kind())) {
            throw new StoreException.Unreadable(
                    own, "project.yml names a tracker of a kind the planner does not keep");
        }
        if (!GITHUB.equals(project.forge())) {
            throw new StoreException.Unreadable(
                    own, "a GitHub tracker is kept only for repositories on github.com");
        }
        final ProjectRef ref;
        try {
            ref = ProjectRef.parse(project.owner() + "/" + project.project());
        } catch (final IllegalArgumentException e) {
            throw new StoreException.Unreadable(own, "project.yml names no GitHub project");
        }
        return new ProjectConfig(
                project.forge(),
                ref,
                project.filesCommit(),
                project.workflow(),
                project.work(),
                project.repositories(),
                Map.of(),
                Map.of());
    }
}
