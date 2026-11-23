package org.fuin.porelo.planner.store.local;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StageMetrics;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.TrackedStores;
import org.fuin.porelo.planner.store.Tracker;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.jspecify.annotations.Nullable;

/**
 * Opens the store of a project whose {@code project.yml} names a tracker of the kind {@code local}:
 * its items lie in the directory the person chose for it, kept by {@link ItemsDirectories}, or by
 * default in {@code $XDG_DATA_HOME/porelo-planner/items/<project>/} on Linux and {@code
 * %LOCALAPPDATA%\porelo-planner\items\<project>\} on Windows. Only that default directory is ever
 * made, at the project's first opening, as the account's alone; a directory chosen elsewhere must
 * exist and is never made. A chosen directory that cannot be used, or a kept choice that cannot be
 * read, opens a store whose every read is refused, naming the directory where it can: the default
 * is never read meanwhile.
 */
public final class LocalDirectoryStores implements TrackedStores {

    /** The kind {@code project.yml} names a local directory's tracker by. */
    public static final String KIND = "local";

    /** The step below the planner's data directory that holds the projects' items. */
    static final String ITEMS = "items";

    private final PrivateFiles files;

    private final Map<String, String> environment;

    private final Clock clock;

    private final ItemsDirectories directories;

    /**
     * Makes the opener.
     *
     * @param files the account's checks of directories and files
     * @param environment the planner's environment, which names the data directory
     * @param clock tells when a stage's result is saved
     * @param directories the directories the person chose, by project
     */
    public LocalDirectoryStores(
            final PrivateFiles files,
            final Map<String, String> environment,
            final Clock clock,
            final ItemsDirectories directories) {
        this.files = files;
        this.environment = Map.copyOf(environment);
        this.clock = clock;
        this.directories = directories;
    }

    /**
     * Refuses: a local project's items are found by the project's name.
     *
     * @throws StoreException.Unreadable always
     */
    @Override
    public PlanningStore open(final TrackedProject project, final Source again) {
        throw new StoreException.Unreadable(
                project.repositories().getFirst().name(),
                "a local directory's project is opened by the project's name");
    }

    /** Opens the project's store over the directory kept for it, or its default. */
    @Override
    public PlanningStore open(final String name, final TrackedProject project, final Source again) {
        return switch (directories.kept(Ids.name(name))) {
            case ItemsDirectories.Default any -> open(name, project, again, null);
            case ItemsDirectories.Chosen chosen -> open(name, project, again, chosen.directory());
            case ItemsDirectories.Unusable unusable ->
                    refused(project, ItemsDirectories.NOT_WRITTEN_HERE);
        };
    }

    /**
     * Opens the project's store over a directory: the one chosen, never made and checked at every
     * read, or the default, made at the project's first opening.
     *
     * @param name the project's name, as the window names it
     * @param project the project as its files name it at opening
     * @param again gives the project as its files name it now
     * @param chosen the directory the person chose, or null for the default
     * @return the store
     * @throws StoreException.Unreadable when the default directory is another account's, open to
     *     others or reached through a link
     */
    public PlanningStore open(
            final String name,
            final TrackedProject project,
            final Source again,
            final @Nullable Path chosen) {
        Ids.name(name);
        final String own = own(project);
        final Path standard = defaultPath(name);
        final String unshaped = ItemsDirectories.shapeFault(standard.toString());
        if (unshaped != null) {
            // The window is never told a directory outside the rule, the default neither.
            return refused(project, unshaped);
        }
        final Path directory;
        if (chosen == null) {
            try {
                directory = defaultDirectory(name);
            } catch (final IllegalStateException | UncheckedIOException e) {
                // The checks' words name paths, which may name the person's account.
                throw new StoreException.Unreadable(
                        own,
                        "the project's items directory is missing, is not this account's alone or"
                                + " is reached through a link");
            }
        } else {
            directory = chosen;
        }
        return new LocalDirectoryStore(
                directory,
                standard,
                files,
                project,
                again,
                StageMetrics.NONE,
                clock,
                LocalDirectoryStore.LOCK_WAIT);
    }

    /**
     * A store whose kept choice cannot be read, or whose default directory is outside the rule:
     * every read is refused, the default never read, and no directory is told.
     */
    private static PlanningStore refused(final TrackedProject project, final String reason) {
        own(project);
        return new PlanningStore() {
            @Override
            public Board board() {
                throw new StoreException.ItemsDirectoryRefused(null, reason);
            }

            @Override
            public Board refresh() {
                throw new StoreException.ItemsDirectoryRefused(null, reason);
            }

            @Override
            public Tracker tracker() {
                return Tracker.LOCAL_REFUSED;
            }
        };
    }

    private static String own(final TrackedProject project) {
        final String own = project.repositories().getFirst().name();
        if (!KIND.equals(project.kind())) {
            throw new StoreException.Unreadable(
                    own, "project.yml names a tracker of another kind than a local directory");
        }
        return own;
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
        return files.directories(
                files.base(PrivateFiles.Kind.DATA, environment),
                true,
                PrivateFiles.Kind.DATA,
                steps(name));
    }

    /** Where the default items directory lies, neither made nor checked. */
    private Path defaultPath(final String name) {
        Path path = files.base(PrivateFiles.Kind.DATA, environment);
        for (final String step : steps(name)) {
            path = path.resolve(step);
        }
        return path;
    }

    private List<String> steps(final String name) {
        final List<String> steps = new ArrayList<>(files.steps(PrivateFiles.Kind.DATA));
        steps.add(ITEMS);
        steps.add(Ids.name(name));
        return steps;
    }
}
