package org.fuin.porelo.planner.store.git.stories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A repository's {@code issues/}: one file an issue, its data in the JSON head right below its
 * heading, and each directory's {@code README.md} generated from the heads of its files.
 *
 * <p>The index keeps from its own text only what no head holds: the part above the first state's
 * table, the prose sections between the tables, and the rows of a closed state whose files are
 * gone.
 */
public final class IssuesIndex {

    /** Each directory's index. */
    static final String INDEX = "README.md";

    /** An issue's file: its id, a sub-issue's with its dot, then words. */
    private static final Pattern FILE =
            Pattern.compile("([A-Z]{1,3}[0-9]{2,3}(?:\\.[1-9][0-9]{0,2})?)-[^/]+\\.md");

    /** A table's separator line. */
    private static final Pattern SEPARATOR = Pattern.compile("^\\|(\\s*:?-+:?\\s*\\|)+\\s*$");

    /** A field line of today's prose head, such as {@code **Status:** Review.}. */
    private static final Pattern FIELD =
            Pattern.compile("^\\*\\*([A-Za-z][A-Za-z ]*):\\*\\*\\s*(.*)$");

    /** An issue id inside a longer text. */
    private static final String ID_TEXT = "[A-Z]{1,3}[0-9]{2,3}(?:\\.[1-9][0-9]{0,2})?";

    /** A repository of the project, as a code span or before a {@code #} names it. */
    private static final String REPOSITORY = "porelo(?:-[a-z0-9]+)*(?:\\.github\\.io)?";

    /** What a wait is written with: a code span, a qualified id, an id. */
    private static final Pattern WAIT =
            Pattern.compile(
                    "`([^`\\n]*)`|\\b("
                            + REPOSITORY
                            + ")#("
                            + ID_TEXT
                            + ")\\b|\\b("
                            + ID_TEXT
                            + ")\\b");

    /** The words a list of waits may hold besides its ids. */
    private static final Pattern FILLER =
            Pattern.compile("(?i)of this repository|\\band\\b|\\bnone\\b|[\\[\\]\\-—,.;:()\\s]");

    /** A heading as today's issue files write it. */
    private static final Pattern HEADING = Pattern.compile("^# (\\S+) — (.+)$");

    /** A size with the reason after it. */
    private static final Pattern SIZE =
            Pattern.compile("^(XS|S|M|L|XL|-|—)(?:\\s*[.]?\\s*$|\\s+[-—]\\s+([\\s\\S]*)$)");

    /** The lead of the paragraph that keeps a converted size's reason. */
    static final String WHY_SIZE = "**Why this size.** ";

    /**
     * What a run found and did.
     *
     * @param faults faults relative to the issues directory; for a conversion, every difference
     * @param written the files changed, in write order
     * @param notes what a conversion did, one line a file
     */
    public record Result(List<String> faults, List<Path> written, List<String> notes) {
        /** Copies the lists so the result stays as it was. */
        public Result {
            faults = List.copyOf(faults);
            written = List.copyOf(written);
            notes = List.copyOf(notes);
        }
    }

    private record Issue(Path file, String id, String text, IssueHead.@Nullable Located head) {}

    private record Chunk(@Nullable String state, int start, List<String> lines) {}

    private final Path root;
    private final Workflow workflow;
    private final Map<Path, List<Issue>> issues = new TreeMap<>();
    private final Map<Path, String> indexes = new TreeMap<>();
    private final Map<Path, List<IssueIndexes.Row>> rows = new HashMap<>();
    private final List<String> headFaults = new ArrayList<>();

    /**
     * Whether the issues are porelo-project's, whose stories beside them hold the workflow: only
     * its issues wait on another repository's.
     */
    private final boolean project;

    /** Where each repository's pages are, by name, as the project's {@code project.yml} says. */
    private final Map<String, String> webs;

    private IssuesIndex(Path root, Workflow workflow) throws IOException {
        this.root = root;
        this.workflow = workflow;
        final Path parent = root.toAbsolutePath().normalize().getParent();
        this.project =
                parent != null && Files.isRegularFile(parent.resolve("stories/workflow.yml"));
        scan();
        this.webs =
                project && parent != null && waitsElsewhere()
                        ? webs(parent.resolve("project.yml"))
                        : Map.of();
    }

    // Only a wait on another repository needs project.yml, so a project converted from prose
    // heads, which name no wait yet, is read without one.
    private boolean waitsElsewhere() {
        for (final Issue issue : all()) {
            final IssueHead.Located located = issue.head();
            if (located != null
                    && located.head().blockedBy().stream().anyMatch(wait -> wait.contains("#")))
                return true;
        }
        return false;
    }

    /**
     * Each repository's pages, so a wait on another repository links its own host and owner; a
     * project without its {@code project.yml} fails, since none of its waits could be linked.
     */
    private static Map<String, String> webs(Path file) throws IOException {
        StoriesIndex.refuseSymlink(file);
        final Project read = Project.read(file);
        final Map<String, String> webs = new HashMap<>();
        for (final String name : read.repositories().keySet()) webs.put(name, read.web(name));
        return Map.copyOf(webs);
    }

    private static IssuesIndex of(Path root) throws IOException {
        StoriesIndex.refuseSymlink(root);
        if (!Files.isDirectory(root)) throw new IOException(root + ": no such directory");
        return new IssuesIndex(root, Workflow.read(workflowFile(root)));
    }

    /**
     * The workflow an issues directory is checked against: its own copy, or porelo-project's beside
     * its stories.
     *
     * @param root the issues directory
     * @return the workflow file
     * @throws IOException when it is a symbolic link
     */
    static Path workflowFile(Path root) throws IOException {
        final Path own = root.resolve("workflow.yml");
        final Path parent = root.toAbsolutePath().normalize().getParent();
        final Path file =
                Files.exists(own) || parent == null
                        ? own
                        : parent.resolve("stories").resolve("workflow.yml");
        StoriesIndex.refuseSymlink(file);
        return file;
    }

    /**
     * Writes each directory's index from its files' heads, after checking every input.
     *
     * @param root the issues directory
     * @return the faults, or the indexes written
     * @throws IOException when an input cannot be read or an index cannot be written
     * @throws IllegalArgumentException when an index has a heading the workflow does not name
     */
    public static Result write(Path root) throws IOException {
        return write(of(root));
    }

    /**
     * Writes each directory's index from its files' heads against the given workflow, whatever
     * workflow file the repository holds, after checking every input.
     *
     * @param root the issues directory
     * @param workflow the project's workflow
     * @return the faults, or the indexes written
     * @throws IOException when an input cannot be read or an index cannot be written
     * @throws IllegalArgumentException when an index has a heading the workflow does not name
     */
    public static Result write(Path root, Workflow workflow) throws IOException {
        StoriesIndex.refuseSymlink(root);
        if (!Files.isDirectory(root)) throw new IOException(root + ": no such directory");
        return write(new IssuesIndex(root, workflow));
    }

    private static Result write(IssuesIndex index) throws IOException {
        final List<String> faults = new ArrayList<>(index.faults());
        final Map<Path, String> generated = index.generate(true, faults);
        if (!faults.isEmpty()) return new Result(faults, List.of(), List.of());
        final List<Path> written = new ArrayList<>();
        for (final Map.Entry<Path, String> page : generated.entrySet()) {
            final Path file = page.getKey().resolve(INDEX);
            if (StoriesIndex.writeChanged(file, page.getValue())) written.add(file);
        }
        return new Result(List.of(), written, List.of());
    }

    /**
     * Checks every head by the schema and the workflow, each heading, each wait and parent, and
     * each index against what the heads give; writes nothing.
     *
     * @param root the issues directory
     * @return the faults
     * @throws IOException when an input cannot be read
     * @throws IllegalArgumentException when an index has a heading the workflow does not name
     */
    public static Result check(Path root) throws IOException {
        final IssuesIndex index = of(root);
        final List<String> faults = new ArrayList<>(index.faults());
        final Map<Path, String> generated = index.generate(true, faults);
        if (faults.isEmpty()) {
            for (final Map.Entry<Path, String> page : generated.entrySet()) {
                final String found = index.indexes.get(page.getKey());
                final String name = index.relative(page.getKey().resolve(INDEX));
                if (found == null) {
                    faults.add(name + ": missing; run porelo-release issues index " + root);
                    continue;
                }
                final String difference = firstDifference(found, page.getValue());
                if (difference != null)
                    faults.add(
                            name
                                    + ":"
                                    + difference
                                    + "; the index is never edited by hand, run porelo-release"
                                    + " issues index "
                                    + root);
            }
        }
        return new Result(faults, List.of(), List.of());
    }

    /**
     * Gives each issue file without a head one from its prose lines and its index row, where they
     * agree, and drops those lines; where they do not, lists every difference and leaves the file.
     *
     * @param root the issues directory
     * @return every difference, the files written and one note for each
     * @throws IOException when an input cannot be read or a file cannot be written
     * @throws IllegalArgumentException when an index has a heading the workflow does not name
     */
    public static Result convert(Path root) throws IOException {
        return of(root).convert();
    }

    /**
     * The index of one directory as its heads give it, with the row from the file kept for an issue
     * whose file has no head yet; null when no file there has a head.
     *
     * @param root the issues directory
     * @param directory the directory below it whose index is asked for
     * @param workflow the repository's workflow
     * @return the index, or null
     * @throws IOException when an input cannot be read or a head is faulty
     */
    static @Nullable String render(Path root, Path directory, Workflow workflow)
            throws IOException {
        final IssuesIndex index = new IssuesIndex(root, workflow);
        if (!index.headFaults.isEmpty()) throw new IOException(String.join("; ", index.headFaults));
        final Path dir = directory.toAbsolutePath().normalize();
        final List<Issue> here = index.issues.getOrDefault(dir, List.of());
        if (here.stream().noneMatch(issue -> issue.head() != null)) return null;
        final List<String> faults = new ArrayList<>();
        final String generated = index.generate(dir, false, faults);
        if (!faults.isEmpty()) throw new IOException(String.join("; ", faults));
        return generated;
    }

    private void scan() throws IOException {
        final Path base = root.toAbsolutePath().normalize();
        final List<Path> paths;
        try (Stream<Path> walked = Files.walk(base)) {
            paths = walked.sorted().toList();
        }
        for (final Path path : paths) {
            StoriesIndex.refuseSymlink(path);
            if (!Files.isRegularFile(path)) continue;
            final String name = path.getFileName().toString();
            final Path dir = Objects.requireNonNull(path.getParent());
            if (name.equals(INDEX)) {
                final String text =
                        Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
                indexes.put(dir, text);
                rows.put(dir, IssueIndexes.indexRows(text, workflow, relative(path)));
                continue;
            }
            final Matcher file = FILE.matcher(name);
            if (!file.matches()) continue;
            final String text =
                    Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
            IssueHead.Located head = null;
            if (IssueHead.present(text)) {
                final List<String> faults = new ArrayList<>();
                head = IssueHead.locate(text, workflow, faults);
                for (final String fault : faults) headFaults.add(relative(path) + ": " + fault);
            }
            issues.computeIfAbsent(dir, key -> new ArrayList<>())
                    .add(new Issue(path, file.group(1), text, head));
        }
    }

    /** What no index is needed to see: the heads themselves, and what they name. */
    private List<String> faults() {
        final List<String> faults = new ArrayList<>(headFaults);
        final Set<String> known = known();
        final Map<String, Issue> ids = new HashMap<>();
        for (final Issue issue : all()) {
            final String name = relative(issue.file());
            if (issue.head() == null) {
                if (!IssueHead.present(issue.text()))
                    faults.add(
                            name
                                    + ": no head right below the heading; run porelo-release issues"
                                    + " convert");
                continue;
            }
            final IssueHead head = issue.head().head();
            if (!issue.head().heading().equals(head.heading()))
                faults.add(name + ": the heading is not '" + head.heading() + "'");
            if (!issue.id().equals(head.id()))
                faults.add(name + ": the file is not named after its id " + head.id());
            final Issue before = ids.putIfAbsent(head.id(), issue);
            if (before != null)
                faults.add(
                        name + ": id " + head.id() + " also stands in " + relative(before.file()));
            final int dot = head.id().indexOf('.');
            if (head.parent() != null) {
                if (dot < 0)
                    faults.add(
                            name
                                    + ": /parent: only a sub-issue, its id with a dot, has a"
                                    + " parent");
                else if (!head.parent().equals(head.id().substring(0, dot)))
                    faults.add(
                            name
                                    + ": /parent: "
                                    + head.parent()
                                    + " is not the id before the dot of "
                                    + head.id());
                else if (!known.contains(head.parent()))
                    faults.add(
                            name
                                    + ": /parent: "
                                    + head.parent()
                                    + " is no issue of this repository");
            }
            if (head.size().equals("-") && needsSize(head.state()))
                faults.add(name + ": /size: a dash, but " + head.state() + " needs a size");
            for (final String wait : head.blockedBy()) {
                if (wait.contains("#")) {
                    // Another repository's issue is checked across repositories, not here.
                    if (!project)
                        faults.add(
                                name
                                        + ": /blockedBy: "
                                        + wait
                                        + " is another repository's issue; only"
                                        + " porelo-project's issues wait on one");
                    else if (!webs.containsKey(wait.substring(0, wait.indexOf('#'))))
                        faults.add(
                                name
                                        + ": /blockedBy: "
                                        + wait
                                        + " names a repository project.yml does not");
                } else if (wait.equals(head.id()))
                    faults.add(name + ": /blockedBy: " + wait + " is the issue itself");
                else if (!known.contains(wait))
                    faults.add(name + ": /blockedBy: " + wait + " is no issue of this repository");
            }
        }
        return faults;
    }

    /** Whether work in a state is measured or done, and so has a size, as stage asks. */
    private boolean needsSize(String state) {
        return workflow.measured(state) || (workflow.closed(state) && !workflow.rejected(state));
    }

    /** Every issue that is there: a file, or a closed row whose file is gone. */
    private Set<String> known() {
        final Set<String> known = new HashSet<>();
        for (final Issue issue : all()) {
            known.add(issue.id());
            if (issue.head() != null) known.add(issue.head().head().id());
        }
        rows.values().stream()
                .flatMap(List::stream)
                .filter(row -> workflow.closed(row.state()))
                .forEach(row -> known.add(row.number()));
        return known;
    }

    private List<Issue> all() {
        return issues.values().stream().flatMap(List::stream).toList();
    }

    /** The directory each issue's row stands in, for a link to it. */
    private Map<String, Path> places() {
        final Map<String, Path> places = new HashMap<>();
        rows.forEach((dir, list) -> list.forEach(row -> places.put(row.number(), dir)));
        issues.forEach(
                (dir, list) ->
                        list.forEach(
                                issue -> {
                                    places.put(issue.id(), dir);
                                    if (issue.head() != null)
                                        places.put(issue.head().head().id(), dir);
                                }));
        return places;
    }

    private Map<Path, String> generate(boolean strict, List<String> faults) {
        final Set<Path> dirs = new LinkedHashSet<>(issues.keySet());
        indexes.forEach(
                (dir, text) -> {
                    if (chunks(text).stream().anyMatch(chunk -> chunk.state() != null))
                        dirs.add(dir);
                });
        final Map<Path, String> generated = new TreeMap<>();
        for (final Path dir : dirs) generated.put(dir, generate(dir, strict, faults));
        return generated;
    }

    private String generate(Path dir, boolean strict, List<String> faults) {
        final String old = indexes.get(dir);
        final List<Chunk> chunks = old == null ? List.of() : chunks(old);
        final Map<Integer, IssueIndexes.Row> byLine = new HashMap<>();
        rows.getOrDefault(dir, List.of()).forEach(row -> byLine.put(row.line(), row));
        final Set<String> headed = new HashSet<>();
        final Map<String, Issue> files = new HashMap<>();
        for (final Issue issue : all()) {
            files.put(issue.id(), issue);
            if (issue.head() != null) headed.add(issue.head().head().id());
        }
        final List<List<String>> blocks = new ArrayList<>();
        final List<String> intro = new ArrayList<>();
        final Map<String, List<Chunk>> followers = new HashMap<>();
        final Map<String, Chunk> closed = new HashMap<>();
        final Map<String, List<String>> kept = new HashMap<>();
        String current = null;
        final Set<String> seen = new HashSet<>();
        for (final Chunk chunk : chunks) {
            if (chunk.state() == null) {
                if (current == null) intro.addAll(chunk.lines());
                else followers.computeIfAbsent(current, key -> new ArrayList<>()).add(chunk);
                continue;
            }
            current = chunk.state();
            if (!seen.add(current)) {
                faults.add(
                        relative(dir.resolve(INDEX))
                                + ":"
                                + (chunk.start() + 1)
                                + ": a second section "
                                + current
                                + "; each state has one");
                continue;
            }
            if (workflow.closed(current)) {
                closed.putIfAbsent(current, chunk);
                misfits(dir, chunk, byLine, headed, faults);
                continue;
            }
            for (int i = 0; i < chunk.lines().size(); i++) {
                final IssueIndexes.Row row = byLine.get(chunk.start() + i + 1);
                if (row == null || headed.contains(row.number())) continue;
                if (strict && !files.containsKey(row.number()))
                    faults.add(
                            relative(dir.resolve(INDEX))
                                    + ":"
                                    + row.line()
                                    + ": "
                                    + row.number()
                                    + " stands under "
                                    + current
                                    + " but has no file");
                kept.computeIfAbsent(current, key -> new ArrayList<>()).add(chunk.lines().get(i));
            }
        }
        blocks.add(intro.isEmpty() ? List.of("# Issues") : intro);
        final Map<String, Path> places = places();
        final List<IssueHead.Located> heads =
                issues.getOrDefault(dir, List.of()).stream()
                        .map(Issue::head)
                        .filter(Objects::nonNull)
                        .sorted(order())
                        .toList();
        for (final String state : workflow.states()) {
            final List<IssueHead.Located> inState =
                    heads.stream().filter(head -> head.head().state().equals(state)).toList();
            if (workflow.closed(state)) {
                final List<String> table =
                        closedTable(dir, state, inState, closed.get(state), places);
                if (!table.isEmpty()) blocks.add(table);
            } else {
                final boolean questions =
                        inState.stream().anyMatch(head -> head.head().openQuestions() != null);
                final List<String> table = new ArrayList<>();
                for (final IssueHead.Located head : inState)
                    table.add(openRow(dir, head, questions, places));
                if (!strict) table.addAll(kept.getOrDefault(state, List.of()));
                if (!table.isEmpty()) {
                    final List<String> block = new ArrayList<>();
                    block.add("## " + state);
                    block.add("");
                    block.add(
                            "| # | Size | Blocked by | What it covers |"
                                    + (questions ? " Open questions |" : ""));
                    block.add("|---|---|---|---|" + (questions ? "---|" : ""));
                    block.addAll(table);
                    blocks.add(block);
                }
            }
            for (final Chunk chunk : followers.getOrDefault(state, List.of()))
                blocks.add(chunk.lines());
        }
        final List<String> text = new ArrayList<>();
        for (final List<String> block : blocks) {
            int from = 0;
            int to = block.size();
            while (from < to && block.get(from).isBlank()) from++;
            while (to > from && block.get(to - 1).isBlank()) to--;
            if (from == to) continue;
            if (!text.isEmpty()) text.add("");
            text.addAll(block.subList(from, to));
        }
        return String.join("\n", text) + "\n";
    }

    private List<String> closedTable(
            Path dir,
            String state,
            List<IssueHead.Located> heads,
            @Nullable Chunk chunk,
            Map<String, Path> places) {
        // A table kept in the open form gets rows of that form: its header stays, so must its
        // cells.
        final List<String> header = chunk == null ? List.of() : header(chunk);
        final boolean open = header.stream().anyMatch("size"::equalsIgnoreCase);
        final boolean questions = header.stream().anyMatch("open questions"::equalsIgnoreCase);
        final List<String> generated = new ArrayList<>();
        for (final IssueHead.Located head : heads)
            generated.add(open ? openRow(dir, head, questions, places) : closedRow(head));
        final Set<String> headed = new HashSet<>();
        for (final Issue issue : all())
            if (issue.head() != null) headed.add(issue.head().head().id());
        if (chunk == null) {
            if (generated.isEmpty()) return List.of();
            final List<String> table = new ArrayList<>();
            table.add("## " + state);
            table.add("");
            table.add(
                    workflow.rejected(state)
                            ? "| # | Title | Why |"
                            : "| # | Title | What was built |");
            table.add("|---|---|---|");
            table.addAll(generated);
            return table;
        }
        final Map<Integer, IssueIndexes.Row> byLine = new HashMap<>();
        rows.getOrDefault(dir, List.of()).forEach(row -> byLine.put(row.line(), row));
        final List<String> table = new ArrayList<>();
        boolean inserted = false;
        int count = 0;
        for (int i = 0; i < chunk.lines().size(); i++) {
            final String line = chunk.lines().get(i);
            final IssueIndexes.Row row = byLine.get(chunk.start() + i + 1);
            if (row != null && headed.contains(row.number())) continue;
            if (row != null) count++;
            table.add(line);
            if (!inserted && SEPARATOR.matcher(line).matches()) {
                table.addAll(generated);
                inserted = true;
            }
        }
        if (!inserted && !generated.isEmpty()) {
            table.add("");
            table.add(
                    workflow.rejected(state)
                            ? "| # | Title | Why |"
                            : "| # | Title | What was built |");
            table.add("|---|---|---|");
            table.addAll(generated);
        }
        return count + generated.size() == 0 ? List.of() : table;
    }

    /** The cells of the header line above a chunk's first table separator, or none. */
    private static List<String> header(Chunk chunk) {
        for (int i = 1; i < chunk.lines().size(); i++)
            if (SEPARATOR.matcher(chunk.lines().get(i)).matches())
                return cells(chunk.lines().get(i - 1));
        return List.of();
    }

    /**
     * Faults each row of a closed chunk whose count of cells differs from its table's header; a row
     * of an issue with a head is left to the head, which writes it anew.
     */
    private void misfits(
            Path dir,
            Chunk chunk,
            Map<Integer, IssueIndexes.Row> byLine,
            Set<String> headed,
            List<String> faults) {
        int width = -1;
        for (int i = 0; i < chunk.lines().size(); i++) {
            final String line = chunk.lines().get(i);
            if (i > 0 && SEPARATOR.matcher(line).matches())
                width = cells(chunk.lines().get(i - 1)).size();
            final IssueIndexes.Row row = byLine.get(chunk.start() + i + 1);
            if (row == null || width < 0 || headed.contains(row.number())) continue;
            final int count = cells(line).size();
            if (count != width)
                faults.add(
                        relative(dir.resolve(INDEX))
                                + ":"
                                + row.line()
                                + ": "
                                + row.number()
                                + " has "
                                + count
                                + " cells under a header of "
                                + width);
        }
    }

    /** The stripped cells of a table line; an escaped bar stays inside its cell. */
    private static List<String> cells(String line) {
        final String inner = line.strip().replaceAll("^\\|", "").replaceAll("(?<!\\\\)\\|$", "");
        final List<String> cells = new ArrayList<>();
        for (final String cell : inner.split("(?<!\\\\)\\|", -1)) cells.add(cell.strip());
        return cells;
    }

    private static Comparator<IssueHead.Located> order() {
        return Comparator.comparingInt(
                        (IssueHead.Located head) ->
                                IssueSchema.PRIORITIES.indexOf(head.head().priority()))
                .thenComparingInt(head -> head.head().rank())
                .thenComparing(head -> head.head().id(), IssuesIndex::compareIds);
    }

    private static int compareIds(String left, String right) {
        final String[] a = left.split("(?<=[A-Z])(?=[0-9])|\\.");
        final String[] b = right.split("(?<=[A-Z])(?=[0-9])|\\.");
        final int prefix = a[0].compareTo(b[0]);
        if (prefix != 0) return prefix;
        for (int i = 1; i < Math.max(a.length, b.length); i++) {
            final int x = i < a.length ? Integer.parseInt(a[i]) : -1;
            final int y = i < b.length ? Integer.parseInt(b[i]) : -1;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private String openRow(
            Path dir, IssueHead.Located located, boolean questions, Map<String, Path> places) {
        final IssueHead head = located.head();
        final List<String> waits = new ArrayList<>();
        for (final String wait : head.blockedBy()) {
            final int hash = wait.indexOf('#');
            if (hash < 0) waits.add("[" + wait + "](" + link(dir, places.get(wait), wait) + ")");
            else {
                final String repository = wait.substring(0, hash);
                final String id = wait.substring(hash + 1);
                final String web = webs.get(repository);
                // A repository project.yml does not name is a fault already; its wait gets no link.
                waits.add(
                        "`"
                                + repository
                                + "` "
                                + (web == null
                                        ? id
                                        : "["
                                                + id
                                                + "]("
                                                + web
                                                + "/blob/main/issues/README.md#"
                                                + id.toLowerCase(Locale.ROOT)
                                                + ")"));
            }
        }
        return first(located)
                + " | "
                + head.size()
                + " | "
                + (waits.isEmpty() ? "-" : String.join(", ", waits))
                + " | "
                + cell(head.covers())
                + " |"
                + (questions
                        ? " "
                                + (head.openQuestions() == null
                                        ? "-"
                                        : head.openQuestions().toString())
                                + " |"
                        : "");
    }

    private String closedRow(IssueHead.Located located) {
        final IssueHead head = located.head();
        return first(located) + " | " + cell(head.title()) + " | " + cell(head.covers()) + " |";
    }

    private String first(IssueHead.Located located) {
        final String id = located.head().id();
        final Issue issue =
                all().stream().filter(each -> each.head() == located).findFirst().orElseThrow();
        return "| <a id=\""
                + id.toLowerCase(Locale.ROOT)
                + "\"></a>["
                + id
                + "]("
                + issue.file().getFileName()
                + ")";
    }

    private static String link(Path dir, @Nullable Path target, String id) {
        final String anchor = "#" + id.toLowerCase(Locale.ROOT);
        if (target == null || target.equals(dir)) return INDEX + anchor;
        return dir.relativize(target).resolve(INDEX).toString().replace('\\', '/') + anchor;
    }

    private static String cell(String text) {
        return text.replace("|", "\\|");
    }

    /** Splits an index at its headings, each part marked with the state its heading names. */
    private List<Chunk> chunks(String text) {
        final String[] raw = text.split("\n", -1);
        final int length = text.endsWith("\n") ? raw.length - 1 : raw.length;
        final String[] plain = IssueIndexes.unfenced(text).split("\n", -1);
        final List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= length; i++) {
            if (i == length || IssueIndexes.HEADING.matcher(plain[i]).matches()) {
                chunks.add(
                        new Chunk(
                                state(plain, start),
                                start,
                                List.of(raw).subList(start, i).stream().toList()));
                start = i;
            }
        }
        return chunks;
    }

    private @Nullable String state(String[] plain, int line) {
        final Matcher heading = IssueIndexes.HEADING.matcher(plain[line]);
        if (!heading.matches()) return null;
        final String name =
                heading.group(1) == null
                        ? ""
                        : heading.group(1)
                                .strip()
                                .replaceFirst("(?:^|\\s+)#+\\s*$", "")
                                .stripTrailing();
        return workflow.states().contains(name) || IssueIndexes.issueTableFollows(plain, line)
                ? name
                : null;
    }

    private static @Nullable String firstDifference(String found, String expected) {
        final String[] a = found.split("\n", -1);
        final String[] b = expected.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            final String x = i < a.length ? a[i] : "(end of file)";
            final String y = i < b.length ? b[i] : "(end of file)";
            if (!x.equals(y)) return (i + 1) + ": is '" + x + "', the heads give '" + y + "'";
        }
        return null;
    }

    private String relative(Path path) {
        return root.toAbsolutePath()
                .normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    // ---- convert ----

    private Result convert() throws IOException {
        final List<String> differences = new ArrayList<>();
        final List<Path> written = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        for (final Map.Entry<Path, List<Issue>> dir : issues.entrySet()) {
            final Map<String, IssueIndexes.Row> byId = new HashMap<>();
            final Map<String, Integer> ranks = new HashMap<>();
            final Map<String, Integer> counts = new HashMap<>();
            for (final IssueIndexes.Row row : rows.getOrDefault(dir.getKey(), List.of())) {
                byId.putIfAbsent(row.number(), row);
                ranks.put(row.number(), counts.merge(row.state(), 1, Integer::sum));
            }
            for (final Issue issue : dir.getValue()) {
                if (IssueHead.present(issue.text())) {
                    // A head convert cannot read is named, never passed over for the prose below.
                    final String prefix = relative(issue.file()) + ": ";
                    if (issue.head() == null)
                        headFaults.stream()
                                .filter(fault -> fault.startsWith(prefix))
                                .forEach(differences::add);
                    continue;
                }
                final List<String> found = new ArrayList<>();
                final Converted converted =
                        convert(
                                issue,
                                byId.get(issue.id()),
                                ranks.getOrDefault(issue.id(), 1),
                                found);
                final String name = relative(issue.file());
                if (converted == null) {
                    found.forEach(difference -> differences.add(name + ": " + difference));
                    continue;
                }
                if (StoriesIndex.writeChanged(issue.file(), converted.text())) {
                    written.add(issue.file());
                    notes.add(name + ": head written, its prose lines dropped");
                }
            }
        }
        if (differences.isEmpty() && written.isEmpty())
            notes.add("every issue file has a head; nothing to convert");
        return new Result(differences, written, notes);
    }

    private record Converted(String text) {}

    private @Nullable Converted convert(
            Issue issue, IssueIndexes.@Nullable Row row, int rank, List<String> differences) {
        final List<String> lines = List.of(issue.text().split("\n", -1));
        final Matcher heading = HEADING.matcher(lines.getFirst());
        String title = "";
        if (!heading.matches()) differences.add("the first line is no heading '# <id> — <title>'");
        else {
            title = heading.group(2).strip();
            if (!heading.group(1).equals(issue.id()))
                differences.add(
                        "the heading names " + heading.group(1) + ", the file name " + issue.id());
        }
        int start = 1;
        while (start < lines.size() && lines.get(start).isBlank()) start++;
        int end = start;
        final Map<String, StringBuilder> fields = new LinkedHashMap<>();
        if (start < lines.size() && FIELD.matcher(lines.get(start)).matches()) {
            String last = null;
            while (end < lines.size() && !lines.get(end).isBlank()) {
                final Matcher field = FIELD.matcher(lines.get(end));
                if (field.matches()) {
                    last = field.group(1);
                    if (fields.containsKey(last)) differences.add("two **" + last + ":** lines");
                    fields.put(last, new StringBuilder(field.group(2)));
                } else Objects.requireNonNull(fields.get(last)).append('\n').append(lines.get(end));
                end++;
            }
        }
        for (final String label : fields.keySet())
            if (!Set.of("Status", "Size", "Blocked by", "Priority", "Role", "Parent")
                    .contains(label))
                differences.add("a **" + label + ":** line convert does not read");
        final Set<String> waits = new LinkedHashSet<>();
        String state = null;
        final StringBuilder status = fields.get("Status");
        if (status == null) differences.add("no **Status:** line");
        else {
            final String value = status.toString().replace('\n', ' ').strip();
            state =
                    workflow.states().stream()
                            .sorted(Comparator.comparingInt(String::length).reversed())
                            .filter(
                                    each ->
                                            value.startsWith(each)
                                                    && (value.length() == each.length()
                                                            || ".; "
                                                                            .indexOf(
                                                                                    value.charAt(
                                                                                            each
                                                                                                    .length()))
                                                                    >= 0))
                            .findFirst()
                            .orElse(null);
            if (state == null)
                differences.add("**Status:** names no state of workflow.yml: " + value);
            else {
                final String rest =
                        value.substring(state.length()).replaceFirst("^[.;]?\\s*", "").strip();
                if (!rest.isEmpty() && !rest.equals(".")) {
                    if (!rest.toLowerCase(Locale.ROOT).startsWith("blocked by "))
                        differences.add("**Status:** says more than its state: " + rest);
                    else {
                        final Waits named = waits(rest.substring("blocked by ".length()));
                        waits.addAll(named.ids());
                        if (named.more())
                            differences.add("**Status:** says more than its waits: " + rest);
                    }
                }
            }
        }
        String size = "-";
        String reason = null;
        final StringBuilder sized = fields.get("Size");
        if (sized != null) {
            final Matcher matcher = SIZE.matcher(sized.toString().strip());
            if (!matcher.matches())
                differences.add("**Size:** is no size: " + sized.toString().replace('\n', ' '));
            else {
                size = matcher.group(1).equals("—") ? "-" : matcher.group(1);
                reason = matcher.group(2) == null ? null : matcher.group(2).strip();
            }
        }
        final StringBuilder blocked = fields.get("Blocked by");
        if (blocked != null) {
            final Waits named = waits(blocked.toString());
            waits.addAll(named.ids());
            if (named.more())
                differences.add(
                        "**Blocked by:** says more than its waits: "
                                + blocked.toString().replace('\n', ' ').strip());
        }
        if (!project)
            for (final String wait : waits)
                if (wait.contains("#"))
                    differences.add(
                            "the file waits on "
                                    + wait
                                    + ", another repository's issue; only porelo-project's"
                                    + " issues wait on one");
        String priority = "Medium";
        final StringBuilder prioritized = fields.get("Priority");
        if (prioritized != null) {
            final String value = prioritized.toString().replaceAll("[^A-Za-z]", "");
            if (IssueSchema.PRIORITIES.contains(value)) priority = value;
            else differences.add("**Priority:** is no priority: " + prioritized);
        }
        String role = null;
        final StringBuilder roled = fields.get("Role");
        if (roled != null) {
            role = roled.toString().replaceAll("[`*_]", "").strip().replaceFirst("\\.$", "");
            if (!List.of("builder", "tester", "reader").contains(role))
                differences.add(
                        "**Role:** is no role: " + roled.toString().replace('\n', ' ').strip());
        }
        final int dot = issue.id().indexOf('.');
        String parent = dot < 0 ? null : issue.id().substring(0, dot);
        final StringBuilder parented = fields.get("Parent");
        if (parented != null) {
            final Waits read = waits(parented.toString());
            final List<String> named = read.ids();
            if (read.more())
                differences.add(
                        "**Parent:** says more than its parent: "
                                + parented.toString().replace('\n', ' ').strip());
            else if (named.size() != 1 || !named.getFirst().equals(parent))
                differences.add(
                        "**Parent:** names "
                                + named
                                + ", the id "
                                + (parent == null ? "no parent" : parent));
        }
        String covers = "";
        Integer questions = null;
        if (row == null) differences.add("no row in its directory's " + INDEX);
        else {
            if (state != null && !row.state().equals(state))
                differences.add(
                        "**Status:** says "
                                + state
                                + ", the index row stands under "
                                + row.state());
            // A closed row of the finished form says no size, so the file's stands. One of the open
            // form keeps a Size column, which the library reads as a dash for any closed row.
            final @Nullable String sizeCell = row.cells().get("size");
            final boolean rowSized = !workflow.closed(row.state()) || sizeCell != null;
            final String given =
                    workflow.closed(row.state()) && sizeCell != null
                            ? sizeCell.strip()
                            : row.size();
            final String rowSize = given.isBlank() || given.equals("—") ? "-" : given;
            if (rowSized && !rowSize.equals(size))
                differences.add(
                        (sized == null ? "no **Size:** line" : "**Size:** says " + size)
                                + ", the index row "
                                + rowSize);
            final String cell = row.cells().get("blocked by");
            final List<String> rowWaits = cell == null ? row.blockedBy() : waits(cell).ids();
            if (!new HashSet<>(rowWaits).equals(waits))
                differences.add(
                        "the file waits on "
                                + (waits.isEmpty() ? "nothing" : String.join(", ", waits))
                                + ", the index row on "
                                + (rowWaits.isEmpty() ? "nothing" : String.join(", ", rowWaits)));
            covers =
                    (workflow.closed(row.state())
                                    ? row.cells()
                                            .getOrDefault(
                                                    "what was built",
                                                    row.cells()
                                                            .getOrDefault(
                                                                    "why",
                                                                    row.cells()
                                                                            .getOrDefault(
                                                                                    "what it covers",
                                                                                    row.title())))
                                    : row.title())
                            .replace("\\|", "|");
            final String asked = row.cells().get("open questions");
            if (asked != null && !asked.isBlank() && !asked.equals("-") && !asked.equals("—")) {
                if (asked.matches("[0-9]{1,3}")) questions = Integer.valueOf(asked);
                else differences.add("the index row's open questions are no count: " + asked);
            }
        }
        if (state != null && size.equals("-") && needsSize(state))
            differences.add("no size, but " + state + " needs one");
        if (!differences.isEmpty() || state == null || row == null) return null;
        final IssueHead head =
                new IssueHead(
                        issue.id(),
                        title,
                        state,
                        size,
                        priority,
                        rank,
                        List.copyOf(waits),
                        covers,
                        parent,
                        role,
                        null,
                        null,
                        questions);
        final StringBuilder text =
                new StringBuilder(lines.getFirst())
                        .append("\n\n")
                        .append(IssueHead.FENCE)
                        .append('\n')
                        .append(head.json())
                        .append("\n```\n");
        // The head must read back as itself under the same heading, or nothing is written.
        if (!lines.getFirst().equals(head.heading())) {
            differences.add(
                    "the heading is not '" + head.heading() + "', as the head would give it");
            return null;
        }
        final List<String> faults = new ArrayList<>();
        IssueHead.locate(text.toString(), workflow, faults);
        if (!faults.isEmpty()) {
            differences.addAll(faults);
            return null;
        }
        if (reason != null && !reason.isEmpty())
            text.append('\n').append(WHY_SIZE).append(reason).append('\n');
        int rest = end;
        while (rest < lines.size() && lines.get(rest).isBlank()) rest++;
        if (rest < lines.size())
            text.append('\n').append(String.join("\n", lines.subList(rest, lines.size())));
        String result = text.toString();
        if (!result.endsWith("\n")) result += "\n";
        return new Converted(result);
    }

    /**
     * The ids a list of waits names, and whether it says more than ids.
     *
     * @param ids the ids, another repository's as {@code <repository>#<id>}
     * @param more whether words other than the list's own stand beside them
     */
    private record Waits(List<String> ids, boolean more) {}

    /**
     * Reads waits as an issue file or an index row writes them. In porelo-project a code span
     * naming a repository, {@code `porelo`}, makes the ids after it that repository's; elsewhere
     * such a span names the repository itself. A link's target is not read.
     */
    private Waits waits(String text) {
        final String plain = text.replaceAll("\\]\\([^)]*\\)", "]");
        final List<String> ids = new ArrayList<>();
        final StringBuilder left = new StringBuilder();
        String repository = null;
        int last = 0;
        final Matcher matcher = WAIT.matcher(plain);
        while (matcher.find()) {
            left.append(plain, last, matcher.start()).append(' ');
            last = matcher.end();
            final String id;
            if (matcher.group(1) != null) {
                final String span = matcher.group(1);
                if (!span.matches(REPOSITORY)) left.append(matcher.group());
                else if (project) repository = span.equals("porelo-project") ? null : span;
                continue;
            } else if (matcher.group(2) != null)
                id =
                        project && matcher.group(2).equals("porelo-project")
                                ? matcher.group(3)
                                : matcher.group(2) + "#" + matcher.group(3);
            else id = repository == null ? matcher.group(4) : repository + "#" + matcher.group(4);
            if (!ids.contains(id)) ids.add(id);
        }
        left.append(plain.substring(last));
        return new Waits(ids, !FILLER.matcher(left).replaceAll("").isEmpty());
    }

    /**
     * Whether a directory below the issues directory holds an issue file with a head.
     *
     * @param directory the directory
     * @return whether one there has a head
     * @throws IOException when it cannot be listed or read
     */
    static boolean headed(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return false;
        try (Stream<Path> files = Files.list(directory)) {
            for (final Path file : files.sorted().toList()) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) continue;
                if (!FILE.matcher(file.getFileName().toString()).matches()) continue;
                if (IssueHead.present(Files.readString(file, StandardCharsets.UTF_8))) return true;
            }
        }
        return false;
    }
}
