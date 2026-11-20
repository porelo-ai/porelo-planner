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

    private final List<String> faults;

    private final List<String> asides;

    private final Map<String, Map<String, String>> paths;

    private final Map<String, String> webs;

    private final Checkouts checkouts;

    private final Workflow workflow;

    private final String own;

    private CheckoutProject(
            final Board board,
            final List<String> faults,
            final List<String> asides,
            final Map<String, Map<String, String>> paths,
            final Map<String, String> webs,
            final Checkouts checkouts,
            final Workflow workflow,
            final String own) {
        this.board = board;
        this.faults = List.copyOf(faults);
        this.asides = List.copyOf(asides);
        this.paths = Map.copyOf(paths);
        this.webs = Map.copyOf(webs);
        this.checkouts = checkouts;
        this.workflow = workflow;
        this.own = own;
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
        return read(root, environment, false);
    }

    /**
     * Reads a project from its checkouts, perhaps one whose {@code project.yml} names a tracker
     * already: one a carry-over wrote and stopped before it ended.
     *
     * @param root the own repository's checkout, which holds {@code project.yml}
     * @param environment the environment Git runs with to search the checkouts
     * @param trackerTaken whether a tracker named in {@code project.yml} is taken rather than
     *     refused
     * @return the project, its board holding the faults of what it left out
     * @throws IOException naming the file or checkout that cannot be read
     * @throws IllegalArgumentException when {@code project.yml} is no Markdown project
     * @throws org.fuin.porelo.planner.store.StoreException when a repository holds no store
     */
    public static CheckoutProject read(
            final Path root, final Map<String, String> environment, final boolean trackerTaken)
            throws IOException {
        // Each repository beside the own one is found where the own checkout really stands.
        final Path own = root.toRealPath();
        final Path file = own.resolve(ProjectFile.FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(file + ": not a plain file");
        }
        final String text = Files.readString(file, StandardCharsets.UTF_8);
        if (Project.tracker(text).isPresent() && !trackerTaken) {
            throw new IllegalArgumentException(
                    file + ": names a tracker already, so its project is kept there");
        }
        final Project project = Project.read(text, Set.of());
        final Map<String, Path> trees = new LinkedHashMap<>();
        final List<Repository> repositories = new ArrayList<>();
        final Map<String, String> webs = new LinkedHashMap<>();
        trees.put(project.own(), own);
        repositories.add(repository(project, project.own()));
        for (final String name : project.centrals().keySet()) {
            webs.put(name, project.web(name));
            if (name.equals(project.own())) {
                continue;
            }
            final Path tree = own.resolveSibling(name);
            if (!Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(tree + ": no checkout of " + name);
            }
            if (trees.containsValue(tree.toRealPath())) {
                // One directory read as two repositories gives each the other's issues.
                throw new IOException(tree + ": is the checkout of two repositories");
            }
            trees.put(name, tree.toRealPath());
            repositories.add(repository(project, name));
        }
        final Checkouts checkouts =
                new Checkouts(
                        trees,
                        environment,
                        new GitRunner(Duration.ofSeconds(60), 16 << 20),
                        FILE_BYTES);
        final Workflow workflow =
                Workflow.read(
                        read(checkouts, project.own(), "stories/workflow.yml")
                                .orElseThrow(
                                        () -> new IOException(own + ": no stories/workflow.yml")));
        final List<String> asides = new ArrayList<>();
        final StoreReader reader = new StoreReader(checkouts, FILES_PER_REPOSITORY, asides::add);
        final Map<String, Map<String, String>> paths = new LinkedHashMap<>();
        final GitStore store =
                new GitStore(
                        repositories,
                        checkouts,
                        (repository, commit, ownWorkflow) -> {
                            final Part part = reader.read(repository, commit, ownWorkflow);
                            paths.put(repository.name(), part.paths());
                            return part;
                        },
                        () -> () -> {},
                        Clock.systemUTC(),
                        Duration.ofDays(1),
                        1,
                        Runnable::run);
        final Board board = store.refresh();
        final List<String> faults = new ArrayList<>(board.faults());
        for (final Issue issue : board.issues()) {
            final Set<String> prefixes = project.repositories().get(issue.repository());
            final String prefix = issue.id().replaceFirst("[0-9].*$", "");
            if (!issue.repository().equals(project.own())
                    && prefixes != null
                    && !prefixes.contains(prefix)) {
                // A checkout of another repository under the expected name holds other issues.
                faults.add(
                        issue.repository()
                                + ": "
                                + issue.id()
                                + " has a prefix project.yml does not give "
                                + issue.repository());
            }
        }
        return new CheckoutProject(
                board, faults, asides, paths, webs, checkouts, workflow, project.own());
    }

    /** Reads a file of a checkout, a refusal in the words of a file that cannot be read. */
    private static java.util.Optional<String> read(
            final Checkouts checkouts, final String repository, final String path)
            throws IOException {
        try {
            return checkouts.read(repository, Checkouts.TREE, path);
        } catch (final GitFailure e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Answers every fault that leaves the project read only in part: the board's, and an issue
     * whose prefix its repository does not own.
     *
     * @return the faults, none when the project was read whole
     */
    public List<String> faults() {
        return faults;
    }

    /**
     * Answers whether the own checkout's {@code project.yml} has changes not committed, or is not
     * tracked.
     *
     * @return whether it differs from the head commit
     * @throws IOException when the checkout is no repository or Git fails
     */
    public boolean projectFileChanged() throws IOException {
        try {
            return checkouts.changed(own, ProjectFile.FILE);
        } catch (final GitFailure e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Answers whether a {@code project.yml} names a key at its top.
     *
     * @param text the file's text
     * @param key the key
     * @return whether it stands there
     * @throws IllegalArgumentException when the text is no YAML mapping
     */
    public static boolean names(final String text, final String key) {
        return Project.names(text, key);
    }

    /**
     * Answers the faults no issue's or story's reading depends on: a settled issue still named by
     * an index or a page outside the issue files.
     *
     * @return the faults
     */
    public List<String> asides() {
        return asides;
    }

    /**
     * Answers the file an issue was read from in its repository.
     *
     * @param issue an issue of the board with a file
     * @return its path in its repository
     * @throws IllegalArgumentException when it was read from no file
     */
    public String path(final Issue issue) {
        final String path = paths.getOrDefault(issue.repository(), Map.of()).get(issue.id());
        if (path == null) {
            throw new IllegalArgumentException(issue.id() + " was read from no file");
        }
        return path;
    }

    /**
     * Answers where each repository's pages are linked on the web, by its name.
     *
     * @return each repository's web address, without a trailing slash
     */
    public Map<String, String> webs() {
        return webs;
    }

    /**
     * Takes every secret form out of a text this reading gives, such as a link a fault names.
     *
     * @param text the text
     * @return the text with each secret replaced by {@code ***}
     */
    public static String withoutSecrets(final String text) {
        return GitRunner.withoutSecrets(text);
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
        final List<String> lines =
                List.of(story.description().replace("\r\n", "\n").split("\n", -1));
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
                // The generated part ends with its list of issues, as the stories index writes it.
                kept.addAll(lines.subList(at, lines.size()));
                break;
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
        final String path = StageRecords.path(issue.id());
        final java.util.Optional<String> text = read(checkouts, issue.repository(), path);
        if (text.isEmpty()) {
            return new StageRecords.Carried(List.of(), 0, null);
        }
        try {
            return StageRecords.carried(text.get(), issue.id() + ".jsonl", workflow);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    issue.repository() + ": " + path + ": " + e.getMessage(), e);
        }
    }

    private static Repository repository(final Project project, final String name) {
        return new Repository(name, new CloneUrl(project.central(name)), null);
    }
}
