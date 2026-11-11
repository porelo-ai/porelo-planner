package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.git.stories.IssueSource;
import org.fuin.porelo.planner.store.git.stories.IssuesIndex;
import org.fuin.porelo.planner.store.git.stories.Project;
import org.fuin.porelo.planner.store.git.stories.StoriesIndex;

/**
 * The work tools a repository's build runs: its issues and stories indexed and checked, and a
 * release's notes cut from the stories, with the same lines and exit codes as the build tools give.
 * It starts no server and reads no planner configuration.
 *
 * <p>Exit 0 is an answer of yes, {@value #REFUSED} a refusal with each fault said, and {@value
 * #UNANSWERED} no answer: something could not be read or written, or the arguments are no command.
 */
final class WorkCommands {

    /** The exit code of a refusal: each fault is said. */
    static final int REFUSED = 1;

    /** The exit code when there is no answer: an input or an output failed, or no command. */
    static final int UNANSWERED = 2;

    /** Each directory's index, and the stories' index. */
    private static final String INDEX = "README.md";

    private static final String ISSUES = "issues index <dir> | issues check <dir>";

    private static final String STORIES =
            "stories index <dir> | stories check <dir>"
                    + " | stories cut <version> <dir> --after <yyyy-mm-dd>";

    private final PrintStream out;

    private final PrintStream err;

    private WorkCommands(final PrintStream out, final PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Whether the arguments name a work tool rather than the server.
     *
     * @param args the command line
     * @return whether the first argument is {@code issues} or {@code stories}
     */
    static boolean named(final String... args) {
        return args.length > 0 && (args[0].equals("issues") || args[0].equals("stories"));
    }

    /**
     * Runs a work tool.
     *
     * @param args the command line, its first argument {@code issues} or {@code stories}
     * @param out where an answer goes
     * @param err where each fault and every failure goes
     * @return the exit code: 0, {@value #REFUSED} or {@value #UNANSWERED}
     */
    static int run(final List<String> args, final PrintStream out, final PrintStream err) {
        final WorkCommands commands = new WorkCommands(out, err);
        try {
            final List<String> rest = args.subList(1, args.size());
            return args.getFirst().equals("issues")
                    ? commands.issues(rest)
                    : commands.stories(rest);
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError ex) {
            // The JVM's own exit 1 would read as a refusal of the files checked.
            err.println(
                    "internal error - this is not an answer to the question asked: " + said(ex));
            return UNANSWERED;
        }
    }

    private int issues(final List<String> rest) {
        if (rest.size() != 2) return usage(ISSUES);
        final Path dir = Path.of(rest.get(1));
        return switch (rest.getFirst()) {
            case "index" -> issuesIndex(dir);
            case "check" -> issuesCheck(dir);
            default -> usage(ISSUES);
        };
    }

    private int issuesIndex(final Path dir) {
        try {
            final IssuesIndex.Result result = IssuesIndex.write(dir);
            if (report(dir, result.faults(), "fault(s) in the issues in ")) return REFUSED;
            out.println(
                    "OK    "
                            + result.written().size()
                            + " index(es) under "
                            + shown(dir.toString())
                            + " written from the issues' heads");
            return 0;
        } catch (IOException ex) {
            err.println(shown("could not read or write " + dir + ": " + said(ex)));
            return UNANSWERED;
        } catch (IllegalArgumentException ex) {
            err.println(said(ex));
            return REFUSED;
        }
    }

    private int issuesCheck(final Path dir) {
        try {
            final IssuesIndex.Result result = IssuesIndex.check(dir);
            if (report(dir, result.faults(), "fault(s) in the issues in ")) return REFUSED;
            out.println(
                    "OK    issue heads in "
                            + shown(dir.toString())
                            + ", each by issue-1.json, each wait and parent an issue, each index as"
                            + " they give it");
            return 0;
        } catch (IOException ex) {
            err.println(shown("could not read " + dir + ": " + said(ex)));
            return UNANSWERED;
        } catch (IllegalArgumentException ex) {
            err.println(said(ex));
            return REFUSED;
        }
    }

    private int stories(final List<String> rest) {
        if (rest.size() == 2 && rest.getFirst().equals("index"))
            return storiesIndex(Path.of(rest.get(1)));
        if (rest.size() == 2 && rest.getFirst().equals("check"))
            return storiesCheck(Path.of(rest.get(1)));
        if (rest.size() == 5
                && rest.getFirst().equals("cut")
                && rest.get(1).matches("[0-9A-Za-z][0-9A-Za-z._-]*")
                && rest.get(3).equals("--after")
                && rest.get(4).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            final LocalDate after;
            try {
                after = LocalDate.parse(rest.get(4));
            } catch (DateTimeParseException ex) {
                return usage(STORIES);
            }
            return cut(rest.get(1), Path.of(rest.get(2)), after);
        }
        return usage(STORIES);
    }

    private int storiesIndex(final Path dir) {
        try {
            final StoriesIndex.Result result = StoriesIndex.write(dir, source(dir));
            notes(result);
            if (report(dir, result.faults(), "fault(s) in the stories in ")) return REFUSED;
            out.println(
                    "OK    "
                            + count(dir)
                            + " story file(s) and "
                            + shown(dir.resolve(INDEX).toString())
                            + " written from the stories' heads");
            return 0;
        } catch (IOException ex) {
            return unreadable("could not read or write ", dir, ex);
        } catch (IllegalArgumentException ex) {
            err.println(said(ex));
            return REFUSED;
        }
    }

    private int storiesCheck(final Path dir) {
        final int stories;
        try {
            final StoriesIndex.Result result = StoriesIndex.check(dir, source(dir));
            notes(result);
            if (report(dir, result.faults(), "fault(s) in the stories in ")) return REFUSED;
            stories = count(dir);
        } catch (IOException ex) {
            return unreadable("could not read ", dir, ex);
        } catch (IllegalArgumentException ex) {
            err.println(said(ex));
            return REFUSED;
        }
        out.println(
                "OK    "
                        + stories
                        + " story head(s) in "
                        + shown(dir.toString())
                        + ", each by the schema, each issue in its index, each story's text and "
                        + INDEX
                        + " as they give it");
        return 0;
    }

    /**
     * Says, for a release's notes, the stories tested after the day of the last release, each
     * linked on the host and under the owner {@code project.yml} names for the project.
     */
    private int cut(final String version, final Path dir, final LocalDate after) {
        try {
            if (report(dir, cutFaults(dir), "fault(s) in the stories in ")) return REFUSED;
            final Project project = project(dir);
            final String stories =
                    project.web(project.own())
                            + "/blob/main/"
                            + dir.toAbsolutePath().normalize().getFileName()
                            + "/";
            final StringBuilder notes =
                    new StringBuilder(
                            "## " + version + "\n\nThe stories tested after " + after + ":\n\n");
            tested(dir).stream()
                    .filter(story -> story.tested().isAfter(after))
                    .sorted(Comparator.comparing(Tested::tested).thenComparingInt(Tested::number))
                    .forEach(
                            story ->
                                    notes.append("- [")
                                            .append(shown(story.id()))
                                            .append("](")
                                            .append(shown(stories + story.file()))
                                            .append(") ")
                                            .append(shown(story.title()))
                                            .append(" (")
                                            .append(story.tested())
                                            .append(")\n"));
            out.print(notes);
            return 0;
        } catch (IOException ex) {
            return unreadable("could not read ", dir, ex);
        } catch (IllegalArgumentException ex) {
            err.println(said(ex));
            return REFUSED;
        }
    }

    /** The faults a cut is refused for: those of the inputs, not those of the generated text. */
    private List<String> cutFaults(final Path dir) throws IOException {
        try {
            return inputFaults(dir, dir);
        } catch (IOException ex) {
            // A cut has never depended on the generated index being readable.
            if (!Files.isRegularFile(dir.resolve(INDEX))) throw ex;
            try {
                Files.readString(dir.resolve(INDEX), StandardCharsets.UTF_8);
            } catch (IOException output) {
                return withoutIndex(dir, ex);
            }
            throw ex;
        }
    }

    private List<String> inputFaults(final Path checked, final Path original) throws IOException {
        final StoriesIndex.Result result = StoriesIndex.check(checked, source(original));
        notes(result);
        return result.faults().stream()
                .filter(fault -> !fault.endsWith("; run porelo-release stories index " + checked))
                .toList();
    }

    /** The inputs checked in a copy without the index, which could not be read. */
    private List<String> withoutIndex(final Path dir, final IOException original)
            throws IOException {
        final Path root = root(dir);
        if (root == null) throw original;
        final Path temporary = Files.createTempDirectory("porelo-stories-cut-");
        try {
            Files.copy(unlinked(root.resolve("project.yml")), temporary.resolve("project.yml"));
            final Path stories = Files.createDirectory(temporary.resolve("stories"));
            Files.copy(unlinked(dir.resolve("workflow.yml")), stories.resolve("workflow.yml"));
            for (final Path file : storyFiles(dir)) {
                Files.copy(file, stories.resolve(file.getFileName()));
            }
            return inputFaults(stories, dir);
        } finally {
            try (Stream<Path> files = Files.walk(temporary)) {
                for (final Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    /** A story tested on a day, as its head gives it. */
    private record Tested(String id, String title, LocalDate tested, String file) {
        int number() {
            return Integer.parseInt(id.substring(2));
        }
    }

    private static List<Tested> tested(final Path dir) throws IOException {
        final ObjectMapper json = new ObjectMapper();
        final List<Tested> result = new ArrayList<>();
        for (final Path file : storyFiles(dir)) {
            final String text = Files.readString(file, StandardCharsets.UTF_8);
            final int start = text.lastIndexOf("```json\n") + "```json\n".length();
            final JsonNode head = json.readTree(text.substring(start, text.lastIndexOf("```")));
            if (head.get("tested").isNull()) continue;
            result.add(
                    new Tested(
                            head.get("id").asText(),
                            head.get("title").asText(),
                            LocalDate.parse(head.get("tested").asText()),
                            String.valueOf(file.getFileName())));
        }
        return result;
    }

    /** Every story's file in a directory, by name, without the index; none of them a link. */
    private static List<Path> storyFiles(final Path dir) throws IOException {
        return storyNames(dir).stream().map(WorkCommands::unlinked).toList();
    }

    private static List<Path> storyNames(final Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".md"))
                    .filter(file -> !file.getFileName().toString().equals(INDEX))
                    .sorted()
                    .toList();
        }
    }

    /** A file read for the notes, refused when it is a link, which may lead out of the clone. */
    private static Path unlinked(final Path file) {
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException(shown(file.toString()) + ": a link, not a file");
        }
        return file;
    }

    private static int count(final Path dir) throws IOException {
        return storyNames(dir).size();
    }

    /** The issue indexes, read from the checkouts beside the project's own, never the network. */
    private static IssueSource source(final Path dir) {
        return repository -> {
            final Path root = root(dir);
            if (root == null)
                throw new IOException(dir.resolve(INDEX) + ": no project.yml above it");
            return IssueSource.local(root).index(repository);
        };
    }

    /** The project's checkout: the nearest directory above the stories holding project.yml. */
    private static @org.jspecify.annotations.Nullable Path root(final Path dir) {
        Path root = dir.toAbsolutePath().normalize().getParent();
        while (root != null && !Files.isRegularFile(root.resolve("project.yml"))) {
            root = root.getParent();
        }
        return root;
    }

    private static Project project(final Path dir) throws IOException {
        final Path root = root(dir);
        if (root == null) throw new IOException(dir.resolve(INDEX) + ": no project.yml above it");
        return Project.read(
                Files.readString(root.resolve("project.yml"), StandardCharsets.UTF_8), Set.of());
    }

    private void notes(final StoriesIndex.Result result) {
        result.notes().forEach(note -> out.println(shown(note)));
    }

    private int unreadable(final String operation, final Path dir, final IOException ex) {
        if (ex instanceof NoSuchFileException missing
                && "no project.yml above the plan".equals(missing.getReason())) {
            err.println(said(ex));
        } else err.println(shown(operation + dir + ": " + said(ex)));
        return UNANSWERED;
    }

    private boolean report(final Path dir, final List<String> faults, final String what) {
        if (faults.isEmpty()) return false;
        // Said as written: a fault taken for a path would lose the second slash of an address.
        faults.forEach(fault -> err.println(shown(dir + "/" + fault)));
        err.println(shown(faults.size() + " " + what + dir));
        return true;
    }

    private int usage(final String expected) {
        err.println("usage: " + expected);
        return UNANSWERED;
    }

    private static String said(final Throwable failure) {
        final String message = failure.getMessage();
        return shown(message == null ? failure.getClass().getSimpleName() : message);
    }

    /**
     * A text from a repository as it may reach a terminal: every control, format and line or
     * paragraph separator character written as its escape, so no file can move the cursor, hide a
     * line or turn the text around.
     *
     * @param text the text
     * @return the text, each such character as {@code \\uXXXX}
     */
    static String shown(final String text) {
        final StringBuilder shown = new StringBuilder(text.length());
        text.codePoints()
                .forEach(
                        point -> {
                            final int type = Character.getType(point);
                            if (Character.isISOControl(point)
                                    || type == Character.FORMAT
                                    || type == Character.LINE_SEPARATOR
                                    || type == Character.PARAGRAPH_SEPARATOR) {
                                for (final char unit : Character.toChars(point))
                                    shown.append(String.format("\\u%04x", (int) unit));
                            } else shown.appendCodePoint(point);
                        });
        return shown.toString();
    }
}
