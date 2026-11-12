package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.git.stories.IssueHead;
import org.fuin.porelo.planner.store.git.stories.IssueIndex;
import org.fuin.porelo.planner.store.git.stories.IssueIndexes;
import org.fuin.porelo.planner.store.git.stories.Project;
import org.fuin.porelo.planner.store.git.stories.Workflow;

/**
 * Writes a new issue's file in a save's copy of its repository: the next free number of the
 * repository's prefix, a head in state Idea and what must be true.
 */
final class IdeaFile {

    /** The state a new issue is written in. */
    static final String STATE = "Idea";

    private static final String PRIORITY = "Medium";

    private static final int MOST_RANK = 99999;

    private static final int MOST_NUMBER = 999;

    /** How long the words of a file's name may grow, so the name stays a repository path. */
    private static final int MOST_WORDS = 100;

    // The shape of an issue's id, so another Markdown file under issues/ adds no prefix.
    private static final Pattern ISSUE_FILE =
            Pattern.compile("([A-Z]{1,3})([0-9]{2,3})[.-].*\\.md");

    private static final Pattern NUMBER = Pattern.compile("([A-Z]{1,3})([0-9]{2,3})(\\..*)?");

    private static final Pattern APOSTROPHE = Pattern.compile("['’]");

    private static final Pattern NO_WORD = Pattern.compile("[^A-Za-z0-9]+");

    private static final Pattern BREAKS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    private IdeaFile() {}

    /**
     * What was written.
     *
     * @param id the new issue's id
     * @param path its file, relative to the repository
     */
    record Written(String id, String path) {}

    /**
     * Writes the issue's file.
     *
     * @param root the repository's directory in the save's scratch directory
     * @param ownRoot the project's own repository there, which holds {@code project.yml}
     * @param own whether the issue's repository is the project's own
     * @param issue what the person wrote
     * @param workflow the project's workflow
     * @return its id and path
     * @throws IllegalArgumentException when no number can be given or an index cannot be read
     * @throws IOException when a file cannot be read or written
     */
    static Written write(
            final Path root,
            final Path ownRoot,
            final boolean own,
            final NewIssue issue,
            final Workflow workflow)
            throws IOException {
        if (!workflow.states().contains(STATE)) {
            throw new IllegalArgumentException(
                    "the project's workflow has no state " + STATE + " for a new issue");
        }
        // The heading repeats the title unquoted, so a line break there could add a head above it.
        if (BREAKS.matcher(issue.title()).find()) {
            throw new IllegalArgumentException(
                    "the title of a new issue holds a line break or a control character");
        }
        final Path issues = root.resolve("issues");
        final List<Path> files = files(issues);
        final Map<String, Set<Integer>> used = used(issue.repository(), files, workflow);
        final String prefix = prefix(ownRoot, own, issue.repository(), used);
        final int next = used.getOrDefault(prefix, Set.of()).stream().reduce(0, Math::max) + 1;
        if (next > MOST_NUMBER) {
            throw new IllegalArgumentException(
                    "the prefix " + prefix + " has no number left below " + (MOST_NUMBER + 1));
        }
        final String id = String.format("%s%02d", prefix, next);
        final IssueHead head =
                new IssueHead(
                        id,
                        issue.title(),
                        STATE,
                        issue.size() == null ? "-" : issue.size(),
                        PRIORITY,
                        rank(files, workflow),
                        List.of(),
                        issue.title(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        // An agent's issue names none, so its head holds to the schema before it.
                        issue.worker() == Worker.MANUAL ? "Manual" : null,
                        // An idea names no risk, so its issue is ordinary until a person says more.
                        null);
        final String path = "issues/" + id + "-" + words(issue.title()) + ".md";
        final Path file = root.resolve(RepoPath.checked(path));
        Files.createDirectories(issues);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(path + " is there already");
        }
        Files.writeString(
                file,
                head.file("**What must be true.** " + issue.mustBeTrue().strip() + "\n"),
                StandardCharsets.UTF_8);
        return new Written(id, path);
    }

    /**
     * The words of a file's name: the title's letters and digits, a dash between words.
     *
     * @param title the issue's title
     * @return the words, never empty
     */
    static String words(final String title) {
        String words =
                String.join(
                        "-",
                        Stream.of(NO_WORD.split(APOSTROPHE.matcher(title).replaceAll("")))
                                .filter(word -> !word.isEmpty())
                                .toList());
        if (words.length() > MOST_WORDS) {
            words = words.substring(0, MOST_WORDS);
            final int dash = words.lastIndexOf('-');
            words = dash > 0 ? words.substring(0, dash) : words;
        }
        return words.isEmpty() ? "Idea" : words;
    }

    /** The prefix a new issue takes: the first project.yml gives, or the one the own issues use. */
    private static String prefix(
            final Path ownRoot,
            final boolean own,
            final String repository,
            final Map<String, Set<Integer>> used)
            throws IOException {
        if (own) {
            // project.yml names no prefix for its own repository; its issues show the one it uses.
            if (used.size() != 1) {
                throw new IllegalArgumentException(
                        "the project's own repository uses "
                                + (used.isEmpty() ? "no issue prefix" : "several issue prefixes")
                                + ", so a new issue's number cannot be told");
            }
            return used.keySet().iterator().next();
        }
        final Path file = ownRoot.resolve(ProjectFile.FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("the project has no " + ProjectFile.FILE);
        }
        final Set<String> prefixes =
                Project.read(Files.readString(file, StandardCharsets.UTF_8), Set.of())
                        .repositories()
                        .getOrDefault(repository, Set.of());
        if (prefixes.isEmpty()) {
            throw new IllegalArgumentException(
                    ProjectFile.FILE + " names no issue prefix for " + repository);
        }
        return prefixes.iterator().next();
    }

    /** Every number in use by prefix: a file's, and an index row's, whose file may be gone. */
    private static Map<String, Set<Integer>> used(
            final String repository, final List<Path> files, final Workflow workflow)
            throws IOException {
        final Map<String, Set<Integer>> used = new java.util.TreeMap<>();
        for (final Path file : files) {
            final String name = file.getFileName().toString();
            if (name.equals("README.md")) {
                final Map<String, IssueIndex> rows;
                try {
                    rows =
                            IssueIndexes.read(
                                    Files.readString(file, StandardCharsets.UTF_8),
                                    repository,
                                    workflow);
                } catch (final IOException | IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "an issue index cannot be read: " + e.getMessage(), e);
                }
                rows.keySet().forEach(number -> add(used, NUMBER.matcher(number)));
            } else {
                add(used, ISSUE_FILE.matcher(name));
            }
        }
        return used;
    }

    private static void add(final Map<String, Set<Integer>> used, final Matcher matcher) {
        if (matcher.matches()) {
            used.computeIfAbsent(matcher.group(1), key -> new TreeSet<>())
                    .add(Integer.parseInt(matcher.group(2)));
        }
    }

    /** The rank after every other issue's, so a new idea stands last among its priority. */
    private static int rank(final List<Path> files, final Workflow workflow) throws IOException {
        int most = 0;
        for (final Path file : files) {
            if (!ISSUE_FILE.matcher(file.getFileName().toString()).matches()) {
                continue;
            }
            try {
                final IssueHead head =
                        IssueHead.read(Files.readString(file, StandardCharsets.UTF_8), workflow);
                if (head != null) {
                    most = Math.max(most, head.rank());
                }
            } catch (final IllegalArgumentException e) {
                // A head that cannot be read is the index's to refuse; it gives no rank.
            }
        }
        return Math.min(most + 1, MOST_RANK);
    }

    /** The issue files and indexes of the directory and the groups below it, never a link. */
    private static List<Path> files(final Path issues) throws IOException {
        final List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(issues, LinkOption.NOFOLLOW_LINKS)) {
            return files;
        }
        try (Stream<Path> all = Files.walk(issues, 2)) {
            for (final Path path : all.sorted().toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString().endsWith(".md")) {
                    files.add(path);
                }
            }
        }
        return files;
    }
}
