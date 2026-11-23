package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * porelo-project's {@code stories/}: one file a story, its readable part generated from the JSON
 * head at its end, and an index of them all.
 */
public final class StoriesIndex {

    /** The index beside the stories, generated from their heads. */
    static final String INDEX = "README.md";

    /** Jira's priorities, the highest first, each with Jira's icon. */
    private static final Map<String, String> PRIORITIES = priorities();

    /** The line that starts the last paragraph of a story's generated part, the list of issues. */
    private static final String ISSUES = "**Issues:** ";

    private static final Pattern FILE = Pattern.compile("(ST[0-9]+)(-[^/]*)?\\.md");

    /**
     * What the stories' heads give.
     *
     * @param index the index
     * @param files each story's whole text, its generated part as its head gives it
     * @param stories how many stories there are
     */
    private record Generated(String index, Map<Path, String> files, int stories) {}

    private final IssueSource source;
    private final Workflow workflow;
    private final List<String> notes = new ArrayList<>();
    private final Map<String, Map<String, IssueIndex>> indexes = new LinkedHashMap<>();

    /**
     * Whether the index is written as the board reads the stories: a story the board shows with a
     * warning gets no row, its file is left as it stands, and it is no fault.
     */
    private final boolean forBoard;

    private StoriesIndex(IssueSource source, Path storiesDir) throws IOException {
        this(source, storiesDir, false);
    }

    private StoriesIndex(IssueSource source, Path storiesDir, boolean forBoard) throws IOException {
        this.forBoard = forBoard;
        refuseSymlink(storiesDir);
        refuseSymlink(storiesDir.resolve("workflow.yml"));
        this.workflow = Workflow.read(storiesDir.resolve("workflow.yml"));
        this.source = source;
    }

    /**
     * The faults found and the files actually written, in write order.
     *
     * @param faults faults relative to the stories directory
     * @param written files changed or created; empty for a check or a refused write
     * @param notes legacy states that differ from computed states, and warnings where a stated
     *     state differs from the one its issues give
     */
    public record Result(List<String> faults, List<Path> written, List<String> notes) {
        /** Copies the result so it remains stable after the operation. */
        public Result(List<String> faults, List<Path> written) {
            this(faults, written, List.of());
        }

        public Result {
            notes = List.copyOf(notes);
            faults = List.copyOf(faults);
            written = List.copyOf(written);
        }
    }

    /**
     * Checks heads, issue references, generated story text and README.md without writing.
     *
     * @param storiesDir the stories directory below a project.yml
     * @param source the only source of issue indexes
     * @return faults, with an empty written list
     * @throws IllegalArgumentException when project configuration or an issue state is invalid
     * @throws IOException when an input cannot be read
     */
    public static Result check(Path storiesDir, IssueSource source) throws IOException {
        return new StoriesIndex(source, storiesDir).check(storiesDir);
    }

    /**
     * Checks all inputs before writing generated story text and README.md.
     *
     * @param storiesDir the stories directory below a project.yml
     * @param source the only source of issue indexes
     * @return faults or the changed and created paths actually written; unchanged files are
     *     untouched
     * @throws IllegalArgumentException when project configuration or an issue state is invalid
     * @throws IOException when an input cannot be read or an output cannot be written
     */
    public static Result write(Path storiesDir, IssueSource source) throws IOException {
        return new StoriesIndex(source, storiesDir).write(storiesDir);
    }

    /**
     * Writes as {@link #write(Path, IssueSource)} does, but as the board reads the stories: a story
     * whose stated state the workflow does not know, whose id two files carry, whose state cannot
     * be told, or that names an issue with no row in its repository's index, which an issue the
     * board warns about has none of, gets no row and is no fault, and its file is left as it
     * stands. The stories command and the commit check never write this way.
     *
     * @param storiesDir the stories directory below a project.yml
     * @param source the only source of issue indexes
     * @return faults or the changed and created paths actually written
     * @throws IllegalArgumentException when project configuration or an issue state is invalid
     * @throws IOException when an input cannot be read or an output cannot be written
     */
    public static Result writeForBoard(Path storiesDir, IssueSource source) throws IOException {
        return new StoriesIndex(source, storiesDir, true).write(storiesDir);
    }

    private Map<String, IssueIndex> index(String repository) throws IOException {
        Map<String, IssueIndex> rows = indexes.get(repository);
        if (rows == null) {
            rows = IssueIndexes.read(source.index(repository), repository, workflow);
            indexes.put(repository, rows);
        }
        return rows;
    }

    /**
     * Reads every story's head in a directory.
     *
     * @param dir the stories' directory
     * @param faults where each fault is added
     * @return the heads read whole, by id, in the files' order
     * @throws IOException when the directory or a file cannot be read
     */
    static Map<String, Heads.Head> read(Path dir, List<String> faults, Workflow workflow)
            throws IOException {
        return read(dir, faults, workflow, false);
    }

    /** Reads every story's head; for the board, one whose id two files carry is left out. */
    static Map<String, Heads.Head> read(
            Path dir, List<String> faults, Workflow workflow, boolean forBoard) throws IOException {
        refuseSymlink(dir);
        if (!Files.isDirectory(dir)) {
            throw new IOException("no such directory");
        }
        final List<Path> files;
        try (Stream<Path> listed = Files.list(dir)) {
            files =
                    listed.filter(file -> file.getFileName().toString().endsWith(".md"))
                            .sorted()
                            .toList();
        }
        for (final Path file : files) refuseSymlink(file);
        final Map<String, Heads.Head> heads = new LinkedHashMap<>();
        final java.util.Set<String> twice = new java.util.HashSet<>();
        for (final Path file : files) {
            if (file.getFileName().toString().equals(INDEX)) continue;
            final Heads.Head head = Heads.read(file, faults, workflow, forBoard);
            if (head == null) {
                continue;
            }
            final String name = file.getFileName().toString();
            final String id = head.data().get("id").asText();
            final Matcher named = FILE.matcher(name);
            if (!named.matches() || !named.group(1).equals(id)) {
                faults.add(name + ": the file is not named after its id " + id);
            }
            final Heads.Head before = heads.putIfAbsent(id, head);
            if (before != null) {
                if (forBoard) {
                    twice.add(id);
                } else {
                    faults.add(
                            name + ": id " + id + " also stands in " + before.file().getFileName());
                }
            }
        }
        twice.forEach(heads::remove);
        return heads;
    }

    private Result write(Path dir) throws IOException {
        final List<String> faults = new ArrayList<>();
        final Generated generated = generate(dir, faults);
        if (!faults.isEmpty()) return new Result(faults, List.of(), notes);
        final List<Path> written = new ArrayList<>();
        for (final Map.Entry<Path, String> file : generated.files().entrySet()) {
            if (writeChanged(file.getKey(), file.getValue())) written.add(file.getKey());
        }
        final Path index = dir.resolve(INDEX);
        if (writeChanged(index, generated.index())) written.add(index);
        return new Result(faults, written, notes);
    }

    static void refuseSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path))
            throw new IOException("symbolic link is not allowed: " + path);
    }

    static boolean writeChanged(Path file, String content) throws IOException {
        refuseSymlink(file);
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && Arrays.equals(Files.readAllBytes(file), bytes)) return false;
        final Path temporary =
                Files.createTempFile(
                        file.toAbsolutePath().getParent(), "." + file.getFileName() + "-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }

    private Result check(Path dir) throws IOException {
        final List<String> faults = new ArrayList<>();
        final Generated generated = generate(dir, faults);
        if (faults.isEmpty()) {
            final String again = "; run porelo-release stories index " + dir;
            for (final Map.Entry<Path, String> file : generated.files().entrySet()) {
                if (!Files.readString(file.getKey(), StandardCharsets.UTF_8)
                        .equals(file.getValue())) {
                    faults.add(
                            file.getKey().getFileName()
                                    + ": the text above "
                                    + Heads.DATA
                                    + " is not what its head gives"
                                    + again);
                }
            }
            final Path index = dir.resolve(INDEX);
            if (!Files.isRegularFile(index)
                    || !Files.readString(index, StandardCharsets.UTF_8).equals(generated.index())) {
                faults.add(INDEX + ": differs from what the stories' heads give" + again);
            }
        }
        return new Result(faults, List.of(), notes);
    }

    private static boolean isDay(String text) {
        try {
            LocalDate.parse(text);
            return true;
        } catch (java.time.format.DateTimeParseException ex) {
            return false;
        }
    }

    /** Whether a head's tested day closes its story: every issue closed, not every one rejected. */
    private static boolean testedClose(
            final Workflow workflow, final Heads.Head head, final List<String> issueStates) {
        return !head.data().get("tested").isNull()
                && !issueStates.isEmpty()
                && issueStates.stream().allMatch(workflow::closed)
                && !issueStates.stream().allMatch(workflow::rejected);
    }

    /** The workflow's first closed state that is not rejected, its completed state. */
    private static String completed(final Workflow workflow) {
        return workflow.states().stream()
                .filter(state -> workflow.closed(state) && !workflow.rejected(state))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("workflow.yml has no completed state"));
    }

    private static int number(Heads.Head head) {
        return Integer.parseInt(head.data().get("id").asText().substring(2));
    }

    /**
     * What the stories' heads give, or nothing to go by when a head or an issue is at fault. A
     * story-2 head's stated state is the story's, with a warning where its issues give another;
     * otherwise a story takes the state the one rule derives from its issues ({@link
     * org.fuin.porelo.planner.core.StoryState}). A workflow that gives no rule is a fault for every
     * story with issues, never a guessed state.
     */
    private Generated generate(Path dir, List<String> faults) throws IOException {
        final Map<String, Heads.Head> heads = read(dir, faults, workflow, forBoard);
        if (!faults.isEmpty()) {
            return new Generated("", Map.of(), heads.size());
        }
        final Project project = Project.read(dir.resolve(INDEX), this::index);
        final Map<String, List<Heads.Head>> byState = new LinkedHashMap<>();
        workflow.states().forEach(state -> byState.put(state, new ArrayList<>()));
        final Map<Path, String> files = new LinkedHashMap<>();
        for (final Heads.Head head : heads.values()) {
            final String name = head.file().getFileName().toString();
            final String stated = head.data().path("state").asText();
            if (forBoard && !stated.isEmpty() && !workflow.states().contains(stated)) {
                continue;
            }
            final JsonNode tested = head.data().get("tested");
            if (!tested.isNull() && !isDay(tested.asText())) {
                faults.add(name + ": /tested: " + tested.asText() + " is no day");
            }
            final List<String> issues = new ArrayList<>();
            final List<String> issueStates = new ArrayList<>();
            final List<String> issuesWithStates = new ArrayList<>();
            for (final JsonNode node : head.data().get("issues")) {
                final String issue = node.asText();
                final String repository = issue.substring(0, issue.indexOf('#'));
                final String number = issue.substring(issue.indexOf('#') + 1);
                if (!project.repositories().containsKey(repository)) {
                    if (!forBoard)
                        faults.add(
                                name + ": " + issue + " names a repository project.yml does not");
                    continue;
                }
                final IssueIndex row = index(repository).get(number);
                if (row == null) {
                    if (!forBoard)
                        faults.add(name + ": " + issue + " is in no issue index of " + repository);
                    continue;
                }
                issues.add(
                        "- ["
                                + issue
                                + "]("
                                + row.link(project.web(repository))
                                + ") ("
                                + row.state()
                                + ")");
                issueStates.add(row.state());
                issuesWithStates.add(issue + " " + row.state());
            }

            if (issueStates.size() != head.data().get("issues").size()) continue;
            if (!forBoard && !issueStates.isEmpty() && workflow.storyRule().isEmpty()) {
                final String fault =
                        "workflow.yml: " + org.fuin.porelo.planner.core.StoryState.NO_RULE;
                if (!faults.contains(fault)) faults.add(fault);
                continue;
            }
            // A story whose state cannot be told stands on the board without one, not here.
            if (forBoard && issueStates.isEmpty() && stated.isEmpty()) continue;
            // A legacy head's state beside issues is no one's word: the migration drops it.
            final boolean given = head.data().has("$schema") && head.data().has("state");
            // A stated state is given, so the rule gives one too.
            // A story with no issue left once the rejected ones are left out keeps the state it
            // states, or else is an Idea, the workflow's first state. One whose test passed, its
            // day in its head, is finished once its issues are all closed and not all rejected:
            // that day is its close.
            final String byIssues =
                    testedClose(workflow, head, issueStates)
                            ? completed(workflow)
                            : Objects.requireNonNull(
                                    workflow.storyState(
                                            stated.isEmpty() && !issueStates.isEmpty()
                                                    ? workflow.states().get(0)
                                                    : stated,
                                            issueStates));
            final String state = given ? stated : byIssues;
            if (given && !issueStates.isEmpty() && !stated.equals(byIssues))
                notes.add(
                        "WARN  "
                                + head.data().get("id").asText()
                                + ": stated "
                                + stated
                                + ", its issues give "
                                + byIssues
                                + " ("
                                + String.join(", ", issuesWithStates)
                                + ")");
            else if (!given && !stated.isEmpty() && !issueStates.isEmpty() && !stated.equals(state))
                notes.add(
                        "NOTE  "
                                + head.data().get("id").asText()
                                + ": stated "
                                + stated
                                + ", computed "
                                + state);
            final boolean completed = workflow.closed(state) && !workflow.rejected(state);
            if (completed
                    && (issueStates.isEmpty() || given)
                    && head.data().get("tested").isNull()) {
                faults.add(name + ": " + state + " without a tested day");
            } else if (!completed && !head.data().get("tested").isNull()) {
                faults.add(
                        name
                                + ": a tested day on a story that is "
                                + state
                                + ", not a completed story");
            }
            java.util.Objects.requireNonNull(byState.get(state)).add(head);
            files.put(head.file(), text(head, state, issues));
        }
        final StringBuilder page =
                new StringBuilder(
                        "# Stories\n\nWritten by `porelo-release stories index` from each story's"
                                + " head; a change goes into the story's file. Each story is one"
                                + " function of value to a user, with the test the person runs at"
                                + " its end and every issue it needs. A story moves through the"
                                + " same states as an issue, in workflow.yml order: "
                                + String.join(", ", workflow.states())
                                + ". Work follows the priority.\n\n"
                                + "Priority: ");
        page.append(
                String.join(
                        " · ",
                        PRIORITIES.entrySet().stream()
                                .map(priority -> priority.getValue() + " " + priority.getKey())
                                .toList()));
        page.append(". Issues are counted by their prefix (");
        page.append(
                String.join(
                        ", ",
                        project.repositories().entrySet().stream()
                                .flatMap(
                                        repository ->
                                                repository.getValue().stream()
                                                        .map(
                                                                prefix ->
                                                                        Map.entry(
                                                                                prefix,
                                                                                repository
                                                                                        .getKey())))
                                .sorted(Map.Entry.comparingByKey())
                                .map(prefix -> "`" + prefix.getKey() + "` " + prefix.getValue())
                                .toList()));
        page.append(").\n\n| State | Stories |\n|---|---|\n");
        byState.forEach(
                (state, stories) -> {
                    if (!stories.isEmpty()) {
                        page.append("| [")
                                .append(state)
                                .append("](#")
                                .append(state.toLowerCase(Locale.ROOT).replace(' ', '-'))
                                .append(") | ")
                                .append(stories.size())
                                .append(" |\n");
                    }
                });
        for (final Map.Entry<String, List<Heads.Head>> state : byState.entrySet()) {
            if (state.getValue().isEmpty()) {
                continue;
            }
            page.append("\n## ")
                    .append(state.getKey())
                    .append("\n\n| Story | Priority | Issues | What a user gains |\n")
                    .append("|---|---|---|---|\n");
            state.getValue().stream()
                    .sorted(
                            Comparator.comparingInt(
                                            (Heads.Head head) ->
                                                    List.copyOf(PRIORITIES.keySet())
                                                            .indexOf(
                                                                    head.data()
                                                                            .get("priority")
                                                                            .asText()))
                                    .thenComparingInt(StoriesIndex::number))
                    .forEach(head -> page.append(row(head)));
        }
        return new Generated(page.toString(), files, heads.size());
    }

    /**
     * A story's whole text: its heading, the part its head gives, then what a person wrote below it
     * down to the head, unchanged. The generated part runs from the heading to the issues' line.
     */
    private static String text(Heads.Head head, String state, List<String> issues)
            throws IOException {
        final JsonNode data = head.data();
        final String shown =
                " · **State:** "
                        + state
                        + (data.get("tested").isNull()
                                ? ""
                                : " (" + data.get("tested").asText() + ")");
        final String generated =
                "**Priority:** "
                        + priority(data)
                        + shown
                        + "\n\n**What a user gains.** "
                        + data.get("gain").asText()
                        + "\n\n**The test.** "
                        + data.get("test").asText()
                        + "\n\n"
                        + (issues.isEmpty()
                                ? ISSUES + "none yet"
                                : ISSUES.strip() + "\n\n" + String.join("\n", issues))
                        + "\n";
        final List<String> lines = head.lines();
        final int data0 = lines.lastIndexOf(Heads.DATA);
        final int rest = ownStart(lines, data0);
        return lines.getFirst()
                + "\n\n"
                + generated
                + "\n"
                + String.join("\n", lines.subList(rest, data0))
                + (rest < data0 ? "\n" : "")
                + data(head, data0);
    }

    /**
     * The line a person's own text starts on below a story's heading: past the part the index
     * generates, which ends with the issues' line or the list below it, and the blank lines after.
     */
    private static int ownStart(List<String> lines, int data0) {
        int rest = 1;
        for (int i = 1; i < data0; i++) {
            if (lines.get(i).startsWith(ISSUES.strip())) {
                rest = i + 1;
                if (lines.get(i).equals(ISSUES.strip())) {
                    while (rest < data0 && lines.get(rest).isBlank()) {
                        rest++;
                    }
                    while (rest < data0 && lines.get(rest).startsWith("- ")) {
                        rest++;
                    }
                }
                break;
            }
        }
        while (rest < data0 && lines.get(rest).isBlank()) {
            rest++;
        }
        return rest;
    }

    /**
     * What a story's file holds of the story's own: its heading, the text a person wrote and its
     * head under {@code ## Data}, without the part the index generates from its head and its
     * issues, so a change of its issues alone leaves it as it was.
     *
     * @param text the story file's text
     * @return the heading, then every line from the person's own text to the end
     */
    public static String ownText(String text) {
        final List<String> lines = List.of(text.split("\n", -1));
        final int data0 = lines.lastIndexOf(Heads.DATA);
        if (data0 < 1) {
            return text;
        }
        return lines.getFirst()
                + "\n"
                + String.join("\n", lines.subList(ownStart(lines, data0), lines.size()));
    }

    private static String data(Heads.Head head, int data0) throws IOException {
        if (head.data().has("$schema"))
            return String.join("\n", head.lines().subList(data0, head.lines().size()));
        return String.join("\n", head.lines().subList(data0, head.jsonStart()))
                + "\n"
                + Heads.storyTwo(head)
                + "\n"
                + String.join("\n", head.lines().subList(head.jsonEnd(), head.lines().size()));
    }

    private static String row(Heads.Head head) {
        final JsonNode data = head.data();
        final String id = data.get("id").asText();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        data.get("issues")
                .forEach(
                        issue ->
                                counts.merge(
                                        issue.asText()
                                                .substring(issue.asText().indexOf('#') + 1)
                                                .replaceAll("[0-9]+$", ""),
                                        1,
                                        Integer::sum));
        final String issues =
                String.join(
                        " · ",
                        counts.entrySet().stream()
                                .sorted(
                                        Map.Entry.<String, Integer>comparingByValue()
                                                .reversed()
                                                .thenComparing(Map.Entry.comparingByKey()))
                                .map(count -> count.getKey() + ":" + count.getValue())
                                .toList());
        return "| <a id=\""
                + id.toLowerCase(Locale.ROOT)
                + "\"></a>["
                + id
                + "]("
                + head.file().getFileName()
                + ") "
                + cell(data.get("title").asText())
                + " | "
                + priority(data)
                + " | "
                + (issues.isEmpty() ? "none yet" : issues)
                + " | "
                + cell(data.get("gain").asText())
                + " |\n";
    }

    private static String cell(String text) {
        return text.replace("|", "\\|");
    }

    private static String priority(JsonNode data) {
        final String priority = data.get("priority").asText();
        return PRIORITIES.get(priority) + " " + priority;
    }

    private static Map<String, String> priorities() {
        final Map<String, String> priorities = new LinkedHashMap<>();
        priorities.put("Highest", "⏫");
        priorities.put("High", "🔼");
        priorities.put("Medium", "🟰");
        priorities.put("Low", "🔽");
        priorities.put("Lowest", "⏬");
        return java.util.Collections.unmodifiableMap(priorities);
    }
}
