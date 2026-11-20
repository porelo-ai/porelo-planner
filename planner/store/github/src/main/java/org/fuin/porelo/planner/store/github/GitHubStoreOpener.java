package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.TrackedStores;

/**
 * Opens a project whose stories and issues a GitHub project keeps: opening sends GitHub nothing,
 * and a field the project lacks is a fault of the board, never made here; a write sets values and
 * never a field or its options.
 */
public final class GitHubStoreOpener implements TrackedStores {

    /** The kind {@code project.yml} names a GitHub project's tracker by. */
    public static final String KIND = "github";

    /** The one forge a GitHub tracker is kept on; an enterprise host is not taken yet. */
    public static final URI GITHUB = URI.create("https://github.com");

    private final TokenSource tokens;

    private final Function<StoryStates, BoardReader> readers;

    private final Supplier<BoardWriter> writers;

    private final Duration lockWait;

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
                () -> new FieldsBoardWriter(writes, tokens, new ForgeAccessRefusals(GITHUB)),
                lockWait);
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
        this.tokens = tokens;
        this.readers = readers;
        this.writers = writers;
        this.lockWait = lockWait;
    }

    @Override
    public PlanningStore open(final TrackedProject project, final Source again) {
        final ProjectConfig first = config(project);
        // The workflow read last decides a story's state, so a changed one counts at once.
        final AtomicReference<TrackedProject> latest = new AtomicReference<>(project);
        final StoryStates states =
                issueStates ->
                        Objects.requireNonNull(latest.get()).storyStates().storyState(issueStates);
        return new GitHubStore(
                first,
                () -> {
                    final TrackedProject now = again.current();
                    final ProjectConfig config = config(now);
                    latest.set(now);
                    return config;
                },
                GitHubStore.Parts.reading(readers.apply(states), states)
                        .writer(writers.get())
                        .refusals(new ForgeAccessRefusals(GITHUB)),
                tokens,
                lockWait);
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
