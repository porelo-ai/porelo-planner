package org.fuin.porelo.planner.store.local;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StageMetrics;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.TrackedStores;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.jspecify.annotations.Nullable;

/**
 * Opens the store of a project whose {@code project.yml} names a tracker of the kind {@code local}:
 * its items lie by default in {@code $XDG_DATA_HOME/porelo-planner/items/<project>/} on Linux and
 * {@code %LOCALAPPDATA%\porelo-planner\items\<project>\} on Windows. Only that default directory is
 * ever made, at the project's first opening, as the account's alone; a directory chosen elsewhere
 * must exist and is never made.
 */
public final class LocalDirectoryStores implements TrackedStores {

    /** The kind {@code project.yml} names a local directory's tracker by. */
    public static final String KIND = "local";

    /** The step below the planner's data directory that holds the projects' items. */
    static final String ITEMS = "items";

    private final PrivateFiles files;

    private final Map<String, String> environment;

    private final Clock clock;

    /**
     * Makes the opener.
     *
     * @param files the account's checks of directories and files
     * @param environment the planner's environment, which names the data directory
     * @param clock tells when a stage's result is saved
     */
    public LocalDirectoryStores(
            final PrivateFiles files, final Map<String, String> environment, final Clock clock) {
        this.files = files;
        this.environment = Map.copyOf(environment);
        this.clock = clock;
    }

    /**
     * Refuses: a local project's items are found by the project's name.
     *
     * @throws StoreException.Unreadable always
     */
    @Override
    public PlanningStore open(final TrackedProject project, final Source again) {
        throw new StoreException.Unreadable(
                project.repositories().getFirst(),
                "a local directory's project is opened by the project's name");
    }

    /** Opens the project's store over its default directory, made at its first opening. */
    @Override
    public PlanningStore open(final String name, final TrackedProject project, final Source again) {
        return open(name, project, again, null);
    }

    /**
     * Opens the project's store over a directory: the one chosen, which must exist and is never
     * made, or the default, made at the project's first opening.
     *
     * @param name the project's name, as the window names it
     * @param project the project as its files name it at opening
     * @param again gives the project as its files name it now
     * @param chosen the directory the person chose, or null for the default
     * @return the store
     * @throws StoreException.Unreadable when the directory is missing, another account's, open to
     *     others or reached through a link
     */
    public PlanningStore open(
            final String name,
            final TrackedProject project,
            final Source again,
            final @Nullable Path chosen) {
        Ids.name(name);
        final String own = project.repositories().getFirst();
        if (!KIND.equals(project.kind())) {
            throw new StoreException.Unreadable(
                    own, "project.yml names a tracker of another kind than a local directory");
        }
        final Path directory;
        try {
            if (chosen == null) {
                directory = defaultDirectory(name);
            } else {
                files.requireExistingDirectory(chosen);
                directory = chosen;
            }
        } catch (final IllegalStateException | UncheckedIOException e) {
            // The checks' words name paths, which may name the person's account.
            throw new StoreException.Unreadable(
                    own,
                    "the project's items directory is missing, is not this account's alone or is"
                            + " reached through a link");
        }
        return new LocalDirectoryStore(directory, files, project, again, StageMetrics.NONE, clock);
    }

    /**
     * Makes the project's default items directory and each step to it as the account's alone, or
     * checks those there.
     *
     * @param name the project's name
     * @return the directory
     * @throws IllegalStateException when it, or one on its way, is not the account's own
     */
    Path defaultDirectory(final String name) {
        final List<String> steps = new ArrayList<>(files.steps(PrivateFiles.Kind.DATA));
        steps.add(ITEMS);
        steps.add(Ids.name(name));
        return files.directories(
                files.base(PrivateFiles.Kind.DATA, environment),
                true,
                PrivateFiles.Kind.DATA,
                steps);
    }
}
