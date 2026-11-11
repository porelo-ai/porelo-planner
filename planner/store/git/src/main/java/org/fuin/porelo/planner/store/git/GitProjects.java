package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.StoreException;
import org.jspecify.annotations.Nullable;

/** Opens a project's store from its own repository, over the project's cache directory. */
public final class GitProjects {

    /** The file in a project's cache directory naming the source its clones were fetched for. */
    static final String SOURCE = "source";

    private static final String LOCK = "lock";

    /**
     * How a project's store reaches and reads its repositories.
     *
     * @param connectTimeout how long ssh waits for a host
     * @param callLimit how long one Git call may run
     * @param outputBytes how much one Git call may print
     * @param fileBytes the largest file read
     * @param filesPerRepository the most files read of one repository
     * @param mostRepositories the most repositories a project may have
     * @param interval how old the board may be before a request starts a check
     * @param parallel how many remotes are asked at once
     */
    public record Settings(
            Duration connectTimeout,
            Duration callLimit,
            int outputBytes,
            int fileBytes,
            int filesPerRepository,
            int mostRepositories,
            Duration interval,
            int parallel) {}

    private final Map<String, String> environment;

    private final Settings settings;

    private final Clock clock;

    private final Executor background;

    /**
     * Makes an opener.
     *
     * @param environment the planner's own environment, which holds the person's Git login
     * @param settings the bounds of each store
     * @param clock tells when a board was checked
     * @param background runs the checks requests start
     */
    public GitProjects(
            final Map<String, String> environment,
            final Settings settings,
            final Clock clock,
            final Executor background) {
        this.environment = Map.copyOf(environment);
        this.settings = settings;
        this.clock = clock;
        this.background = background;
    }

    /**
     * Opens a project: clears its clones when they were fetched for another source, then reads its
     * repositories from {@code project.yml}.
     *
     * @param project the project's name
     * @param own its own repository's clone URL
     * @param branch the own repository's branch, or null for the remote's default
     * @return the project's store
     * @throws IllegalArgumentException if the URL's last part is no repository name
     */
    public GitStore open(final String project, final CloneUrl own, final @Nullable String branch) {
        final String name = ProjectFile.checkedName(own);
        try {
            return opened(project, own, branch);
        } catch (final UncheckedIOException | IllegalStateException e) {
            // The cache directory or its lock refused: told as the store's fault, free of paths.
            throw new StoreException.Unreadable(name, GitRunner.scrubbed(e.getMessage()));
        }
    }

    private GitStore opened(
            final String project, final CloneUrl own, final @Nullable String branch) {
        final Path directory = CacheDirectory.of(environment, project);
        final FileProjectLock lock = new FileProjectLock(directory);
        final GitRunner runner = new GitRunner(settings.callLimit(), settings.outputBytes());
        final GitRemote remote =
                new GitRemote(
                        directory,
                        environment,
                        settings.connectTimeout(),
                        runner,
                        settings.fileBytes());
        final List<Repository> repositories;
        try (ProjectLock.Held held = lock.acquire()) {
            keepOnlyFor(directory, source(own, branch));
            repositories =
                    ProjectFile.repositories(remote, own, branch, settings.mostRepositories());
        }
        return new GitStore(
                repositories,
                remote,
                new StoreReader(remote, settings.filesPerRepository()),
                lock,
                clock,
                settings.interval(),
                settings.parallel(),
                background,
                new LocalSaves(
                        repositories,
                        remote,
                        new GitLocalRefs(directory, environment, settings.connectTimeout(), runner),
                        background));
    }

    /**
     * Answers whether a text is a branch name the planner takes.
     *
     * @param branch the text
     * @return whether it is one
     */
    public static boolean isBranch(final String branch) {
        return GitRemote.isBranch(branch);
    }

    private static String source(final CloneUrl own, final @Nullable String branch) {
        return own.value() + "\n" + (branch == null ? "" : branch) + "\n";
    }

    /**
     * Deletes everything but the lock from a project's cache directory unless its clones were
     * fetched for this source, so no clone of one source is read for another.
     */
    static void keepOnlyFor(final Path directory, final String source) {
        final Path recorded = directory.resolve(SOURCE);
        try {
            if (Files.isRegularFile(recorded, LinkOption.NOFOLLOW_LINKS)
                    && Files.readString(recorded, StandardCharsets.UTF_8).equals(source)) {
                return;
            }
            // Gone first, so a clearing that fails halfway never leaves the old source recorded.
            Files.deleteIfExists(recorded);
            try (Stream<Path> entries = Files.list(directory)) {
                for (final Path entry : entries.toList()) {
                    if (!entry.getFileName().toString().equals(LOCK)) {
                        delete(entry);
                    }
                }
            }
            Files.writeString(recorded, source, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("the project's old clones could not be removed", e);
        }
    }

    /** Deletes a file or a tree; a link is removed itself, never followed. */
    private static void delete(final Path entry) throws IOException {
        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(entry);
            return;
        }
        Files.walkFileTree(
                entry,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(
                            final Path file, final BasicFileAttributes attributes)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(
                            final Path visited, final @Nullable IOException failure)
                            throws IOException {
                        if (failure != null) {
                            throw failure;
                        }
                        Files.delete(visited);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
