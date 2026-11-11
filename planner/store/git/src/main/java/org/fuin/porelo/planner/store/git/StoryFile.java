package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.git.stories.StoryHead;

/**
 * Writes a new story's file in a save's copy of the project's own repository: the next free story
 * number, and a head naming the idea's new issues, which the stories index then completes.
 */
final class StoryFile {

    /** The highest number {@code story-2.json} takes, {@code ST99999}. */
    static final int MOST_NUMBER = 99999;

    private static final Pattern FILE = Pattern.compile("ST([0-9]{1,9})(-[^/]*)?\\.md");

    // A finished story may live on only in its index row, its file deleted.
    private static final Pattern ROW = Pattern.compile("<a id=\"st([0-9]{1,9})\"></a>");

    private StoryFile() {}

    /**
     * Writes the story's file.
     *
     * @param ownRoot the project's own repository in the save's scratch directory
     * @param idea the idea
     * @param issues the new issues' references, each {@code <repository>#<id>}
     * @return its head and path
     * @throws IllegalArgumentException when the repository keeps no stories or no number is left
     * @throws IOException when a file cannot be read or written
     */
    static Written write(final Path ownRoot, final NewIdea idea, final List<String> issues)
            throws IOException {
        final Path stories = ownRoot.resolve("stories");
        if (!Files.isRegularFile(stories.resolve("workflow.yml"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "the project's own repository keeps no stories/workflow.yml for a new story");
        }
        final int next = highest(stories) + 1;
        if (next > MOST_NUMBER) {
            throw new IllegalArgumentException(
                    "no story number is left below ST" + (MOST_NUMBER + 1));
        }
        final String id = "ST" + next;
        // With issues a story's state follows theirs; without, it states the one it starts in.
        final StoryHead head =
                new StoryHead(
                        id,
                        idea.title(),
                        issues.isEmpty() ? IdeaFile.STATE : null,
                        idea.gain(),
                        idea.test(),
                        idea.priority(),
                        issues);
        final String path =
                RepoPath.checked("stories/" + id + "-" + IdeaFile.words(idea.title()) + ".md");
        final Path file = ownRoot.resolve(path);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(path + " is there already");
        }
        Files.writeString(file, head.file(), StandardCharsets.UTF_8);
        return new Written(head, path);
    }

    /**
     * What was written.
     *
     * @param head the story's head
     * @param path its file, relative to the repository
     */
    record Written(StoryHead head, String path) {}

    /** The highest story number a file's name or an index row holds, or 0. */
    private static int highest(final Path stories) throws IOException {
        int most = 0;
        try (Stream<Path> files = Files.list(stories)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                if (name.equals("README.md")) {
                    final Matcher rows =
                            ROW.matcher(
                                    Files.readString(file, StandardCharsets.UTF_8)
                                            .toLowerCase(java.util.Locale.ROOT));
                    while (rows.find()) {
                        most = Math.max(most, Integer.parseInt(rows.group(1)));
                    }
                    continue;
                }
                final Matcher named = FILE.matcher(name);
                if (named.matches()) {
                    most = Math.max(most, Integer.parseInt(named.group(1)));
                }
            }
        }
        return most;
    }
}
