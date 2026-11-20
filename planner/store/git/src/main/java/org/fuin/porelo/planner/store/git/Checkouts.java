package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.Ids;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Remote} over a project's checkouts as they stand on disk: every file read from the
 * working tree, nothing fetched, nothing reached over the network and nothing written. Its one
 * commit is the working tree itself.
 */
final class Checkouts implements Remote {

    /** The one revision a working tree has. */
    static final String TREE = "checkout";

    private final Map<String, Path> trees;

    private final Map<String, String> environment;

    private final GitRunner runner;

    private final int fileBytes;

    /**
     * Makes the checkouts.
     *
     * @param trees each repository's working tree by its name
     * @param environment the environment a search runs Git with
     * @param runner runs the search
     * @param fileBytes the largest file read
     */
    Checkouts(
            final Map<String, Path> trees,
            final Map<String, String> environment,
            final GitRunner runner,
            final int fileBytes) {
        this.trees = Map.copyOf(trees);
        this.environment = Map.copyOf(environment);
        this.runner = runner;
        this.fileBytes = fileBytes;
    }

    @Override
    public String head(final String repository, final CloneUrl url, final @Nullable String branch) {
        tree(repository);
        return TREE;
    }

    @Override
    public String fetch(
            final String repository, final CloneUrl url, final @Nullable String branch) {
        tree(repository);
        return TREE;
    }

    @Override
    public Optional<String> fetched(final String repository) {
        return Optional.of(TREE);
    }

    @Override
    public Optional<String> read(final String repository, final String commit, final String path) {
        final Path tree = tree(repository);
        final Path file = tree.resolve(RepoPath.checked(path));
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        inside(repository, tree, file, path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            // A link may lead out of the checkout, as Git's own reading refuses it too.
            throw new GitFailure(
                    GitFailure.Kind.FAILED, repository + ": " + path + " is not a plain file");
        }
        try {
            if (Files.size(file) > fileBytes) {
                throw new GitFailure(
                        GitFailure.Kind.FAILED,
                        repository + ": " + path + " is larger than " + fileBytes + " bytes");
            }
            return Optional.of(
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file)))
                            .toString());
        } catch (final CharacterCodingException e) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED, repository + ": " + path + " is no UTF-8 text");
        } catch (final IOException e) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED, repository + ": " + path + " could not be read");
        }
    }

    @Override
    public List<String> list(final String repository, final String commit, final String directory) {
        return entries(repository, directory, false);
    }

    @Override
    public List<String> directories(
            final String repository, final String commit, final String directory) {
        return entries(repository, directory, true);
    }

    @Override
    public List<Found> grep(
            final String repository, final String commit, final Collection<String> words) {
        if (words.isEmpty()) {
            return List.of();
        }
        final Path tree = tree(repository);
        final List<String> arguments =
                new ArrayList<>(List.of("grep", "-I", "-n", "-w", "-F", "--null", "--no-color"));
        for (final String word : words) {
            arguments.add("-e");
            arguments.add(Ids.issue(word));
        }
        arguments.add("--");
        // The working tree's tracked files, as a commit's tree holds them.
        final String out =
                new String(
                        runner.find(
                                GitCall.of(environment, Duration.ofSeconds(10), arguments), tree),
                        StandardCharsets.UTF_8);
        final List<Found> found = new ArrayList<>();
        for (final String entry : out.split("\n")) {
            if (entry.isEmpty()) {
                continue;
            }
            final String[] parts = entry.split("\0", 3);
            if (parts.length != 3 || !parts[1].matches("[0-9]{1,9}")) {
                throw new GitFailure(
                        GitFailure.Kind.FAILED, repository + ": git grep answered otherwise");
            }
            found.add(new Found(parts[0], Integer.parseInt(parts[1]), parts[2]));
        }
        return List.copyOf(found);
    }

    private List<String> entries(
            final String repository, final String directory, final boolean directories) {
        final Path tree = tree(repository);
        final Path dir = tree.resolve(RepoPath.checked(directory));
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        inside(repository, tree, dir, directory);
        try (Stream<Path> listed = Files.list(dir)) {
            return listed.filter(
                            entry ->
                                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                                            == directories)
                    .map(entry -> directory + "/" + entry.getFileName())
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED, repository + ": " + directory + " could not be listed");
        }
    }

    /** Refuses a file whose directories lead out of the checkout through a link. */
    private static void inside(
            final String repository, final Path tree, final Path file, final String path) {
        try {
            final Path parent = file.getParent();
            if (parent == null || !parent.toRealPath().startsWith(tree.toRealPath())) {
                throw new GitFailure(
                        GitFailure.Kind.FAILED,
                        repository + ": " + path + " lies outside the checkout");
            }
        } catch (final IOException e) {
            throw new GitFailure(
                    GitFailure.Kind.FAILED, repository + ": " + path + " could not be read");
        }
    }

    private Path tree(final String repository) {
        final Path tree = trees.get(repository);
        if (tree == null) {
            throw new GitFailure(GitFailure.Kind.FAILED, repository + " has no checkout");
        }
        return tree;
    }
}
