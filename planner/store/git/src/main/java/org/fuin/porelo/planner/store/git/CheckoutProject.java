package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.git.stories.IssueHead;
import org.fuin.porelo.planner.store.git.stories.Project;
import org.fuin.porelo.planner.store.git.stories.StageRecords;
import org.fuin.porelo.planner.store.git.stories.Workflow;

/**
 * A Markdown project read from its checkouts on disk, as the Git store reads it from its clones:
 * the own repository's checkout and every other repository's beside it, named as {@code
 * project.yml} names it. Nothing is fetched and nothing is written.
 */
public final class CheckoutProject {

    /** The most issue and story files one repository may hold, as the planner reads them. */
    private static final int FILES_PER_REPOSITORY = 10_000;

    /** The largest file read, as the planner reads its clones. */
    private static final int FILE_BYTES = 1 << 20;

    private final Board board;

    private final Map<String, Path> trees;

    private final Workflow workflow;

    private CheckoutProject(
            final Board board, final Map<String, Path> trees, final Workflow workflow) {
        this.board = board;
        this.trees = trees;
        this.workflow = workflow;
    }

    /**
     * Reads a project from its checkouts.
     *
     * @param root the own repository's checkout, which holds {@code project.yml}
     * @param environment the environment Git runs with to search the checkouts
     * @return the project, its board holding the faults of what it left out
     * @throws IOException naming the file or checkout that cannot be read
     * @throws IllegalArgumentException when {@code project.yml} is no Markdown project, such as one
     *     a tracker already keeps
     * @throws org.fuin.porelo.planner.store.StoreException when a repository holds no store
     */
    public static CheckoutProject read(final Path root, final Map<String, String> environment)
            throws IOException {
        final Path own = root.toAbsolutePath().normalize();
        final Path file = own.resolve(ProjectFile.FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(file + ": not a plain file");
        }
        final String text = Files.readString(file, StandardCharsets.UTF_8);
        if (Project.tracker(text).isPresent()) {
            throw new IllegalArgumentException(
                    file + ": names a tracker already, so its project is kept there");
        }
        final Project project = Project.read(text, Set.of());
        final Map<String, Path> trees = new LinkedHashMap<>();
        final List<Repository> repositories = new ArrayList<>();
        trees.put(project.own(), own);
        repositories.add(repository(project, project.own()));
        for (final String name : project.centrals().keySet()) {
            if (name.equals(project.own())) {
                continue;
            }
            final Path tree = own.resolveSibling(name);
            if (!Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(tree + ": no checkout of " + name);
            }
            trees.put(name, tree);
            repositories.add(repository(project, name));
        }
        final Workflow workflow = Workflow.read(own.resolve("stories/workflow.yml"));
        final Checkouts checkouts =
                new Checkouts(
                        trees,
                        environment,
                        new GitRunner(Duration.ofSeconds(60), 16 << 20),
                        FILE_BYTES);
        final GitStore store =
                new GitStore(
                        repositories,
                        checkouts,
                        new StoreReader(checkouts, FILES_PER_REPOSITORY),
                        () -> () -> {},
                        Clock.systemUTC(),
                        Duration.ofDays(1),
                        1,
                        Runnable::run);
        return new CheckoutProject(store.refresh(), Map.copyOf(trees), workflow);
    }

    /**
     * Answers the board as the checkouts hold it.
     *
     * @return the board, with the faults of what it left out
     */
    public Board board() {
        return board;
    }

    /**
     * Reads an issue's head from its text.
     *
     * @param issue an issue of the board with a file
     * @return its head
     * @throws IllegalArgumentException when its text has no head the schema takes
     */
    public IssueHead head(final Issue issue) {
        final IssueHead head = IssueHead.read(issue.text(), workflow);
        if (head == null) {
            throw new IllegalArgumentException(issue.id() + " has no head");
        }
        return head;
    }

    /**
     * Answers an issue's text below its heading and head, as a person wrote it.
     *
     * @param issue an issue of the board with a file
     * @return the text, without the blank lines around it
     */
    public String below(final Issue issue) {
        final List<String> lines = List.of(issue.text().replace("\r\n", "\n").split("\n", -1));
        final int head = IssueHead.lines(issue.text());
        return String.join("\n", lines.subList(Math.min(head, lines.size()), lines.size())).strip();
    }

    /**
     * Answers what a person reads of a story without the part the stories index generates from its
     * head and its issues: its priority and state, which a tracker keeps as fields, and its list of
     * issues, which a tracker keeps as sub-issues.
     *
     * @param story a story of the board
     * @return its gain, its test and what a person wrote below them
     */
    public static String storyText(final org.fuin.porelo.planner.store.Story story) {
        final List<String> lines = List.of(story.description().split("\n", -1));
        final List<String> kept = new ArrayList<>();
        int at = 0;
        if (!lines.isEmpty() && lines.getFirst().startsWith(PRIORITY)) {
            at = 1;
        }
        while (at < lines.size()) {
            final String line = lines.get(at);
            if (line.startsWith(ISSUES)) {
                at++;
                if (line.equals(ISSUES)) {
                    while (at < lines.size() && lines.get(at).isBlank()) {
                        at++;
                    }
                    while (at < lines.size() && lines.get(at).startsWith("- ")) {
                        at++;
                    }
                }
                continue;
            }
            kept.add(line);
            at++;
        }
        return String.join("\n", kept).strip();
    }

    /** The line that starts a story's generated part. */
    private static final String PRIORITY = "**Priority:** ";

    /** The line that starts a story's generated list of issues. */
    private static final String ISSUES = "**Issues:**";

    /**
     * Reads an issue's stage records from its repository's checkout.
     *
     * @param issue an issue of the board
     * @return its stages, none when it has no record file
     * @throws IOException naming the record file when it cannot be read
     * @throws IllegalArgumentException naming the record file and what is wrong in it, a stage
     *     still open included
     */
    public StageRecords.Carried stages(final Issue issue) throws IOException {
        if (!StageRecords.recorded(issue.id())) {
            return new StageRecords.Carried(List.of(), 0, null);
        }
        final Path tree = trees.get(issue.repository());
        if (tree == null) {
            throw new IOException(issue.repository() + ": no checkout");
        }
        final String path = StageRecords.path(issue.id());
        final Path file = tree.resolve(path);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new StageRecords.Carried(List.of(), 0, null);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(issue.repository() + ": " + path + ": not a plain file");
        }
        try {
            return StageRecords.carried(
                    Files.readString(file, StandardCharsets.UTF_8),
                    issue.id() + ".jsonl",
                    workflow);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    issue.repository() + ": " + path + ": " + e.getMessage(), e);
        }
    }

    private static Repository repository(final Project project, final String name) {
        return new Repository(name, new CloneUrl(project.central(name)), null);
    }
}
