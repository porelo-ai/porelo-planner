package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.TrackedStores;

/**
 * Opens a project whose stories and issues a GitHub project keeps, reading only: opening sends
 * GitHub nothing, and a field the project lacks is a fault of the board, never made here.
 */
public final class GitHubStoreOpener implements TrackedStores {

    /** The kind {@code project.yml} names a GitHub project's tracker by. */
    public static final String KIND = "github";

    /** The one forge a GitHub tracker is kept on; an enterprise host is not taken yet. */
    public static final URI GITHUB = URI.create("https://github.com");

    private final TokenSource tokens;

    private final BoardReader reader;

    private final Duration lockWait;

    /**
     * Makes an opener.
     *
     * @param tokens gives the person's token for the forge
     * @param reader reads a project's board from GitHub
     * @param lockWait how long a call waits for another on the same project
     */
    public GitHubStoreOpener(
            final TokenSource tokens, final BoardReader reader, final Duration lockWait) {
        this.tokens = tokens;
        this.reader = reader;
        this.lockWait = lockWait;
    }

    @Override
    public PlanningStore open(final TrackedProject project) {
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
        final ProjectConfig config =
                new ProjectConfig(
                        project.forge(),
                        ref,
                        project.workflow(),
                        project.work(),
                        project.repositories(),
                        Map.of(),
                        Map.of());
        return new GitHubStore(
                config,
                GitHubStore.Parts.reading(reader, project.storyStates()::storyState),
                tokens,
                lockWait);
    }
}
