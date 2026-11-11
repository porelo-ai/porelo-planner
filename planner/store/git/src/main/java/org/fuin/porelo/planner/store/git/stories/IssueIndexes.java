package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Reads durable rows from a repository's Markdown issue index. */
public final class IssueIndexes {
    static final String MARKER = "<!-- porelo-issue-index ";
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern INDEX_PATH =
            Pattern.compile("issues(?:/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*/README\\.md");

    private IssueIndexes() {}

    /**
     * Reads one index or length-framed pages supplied by a source. Marker lines in page text are
     * reserved and refused, including inside code fences.
     *
     * @param index Markdown text, or frames headed by {@code <!-- porelo-issue-index
     *     {"path":"issues/README.md","source":"...","length":N} -->} and a newline; N counts UTF-16
     *     code units in the following page after CRLF is normalized to LF
     * @param workflow the states and their meanings
     * @param repository the repository owning the rows
     * @return immutable rows keyed by issue number
     * @throws IOException when a row occurs more than once or provenance is malformed
     * @throws IllegalArgumentException when a row has an unknown state heading
     */
    public static Map<String, IssueIndex> read(String index, String repository, Workflow workflow)
            throws IOException {
        index = index.replace("\r\n", "\n");
        final Map<String, IssueIndex> result = new LinkedHashMap<>();
        if (!index.startsWith(MARKER)) {
            refuseMarkers(index, "issues/README.md");
            rows(index, "issues/README.md", repository + "/issues/README.md", result, workflow);
            return Collections.unmodifiableMap(result);
        }
        int offset = 0;
        while (offset < index.length()) {
            final int newline = index.indexOf('\n', offset);
            if (newline < 0)
                throw new IOException("invalid issue index provenance: " + index.substring(offset));
            final String line = index.substring(offset, newline);
            if (!line.startsWith(MARKER) || !line.endsWith(" -->"))
                throw new IOException("invalid issue index provenance: " + line);
            final JsonNode metadata =
                    JSON.readTree(line.substring(MARKER.length(), line.length() - 4));
            if (metadata == null
                    || !metadata.isObject()
                    || !metadata.path("path").isTextual()
                    || !metadata.path("source").isTextual()) {
                throw new IOException("invalid issue index provenance: " + line);
            }
            final String path = metadata.path("path").asText();
            if (!INDEX_PATH.matcher(path).matches())
                throw new IOException("invalid issue index path: " + path);
            final JsonNode length = metadata.path("length");
            final int start = newline + 1;
            if (!length.isIntegralNumber()
                    || !length.canConvertToInt()
                    || length.intValue() < 0
                    || length.intValue() > index.length() - start) {
                throw new IOException(
                        "invalid issue index page length for " + path + ": " + length);
            }
            final int end = start + length.intValue();
            int next = end;
            while (next < index.length() && index.charAt(next) == '\n') next++;
            if (next < index.length() && !index.startsWith(MARKER, next))
                throw new IOException("invalid issue index page boundary after " + path);
            final String page = index.substring(start, end);
            refuseMarkers(page, path);
            rows(page, path, metadata.path("source").asText(), result, workflow);
            offset = next;
        }
        return Collections.unmodifiableMap(result);
    }

    private static void refuseMarkers(String page, String path) throws IOException {
        if (page.lines().anyMatch(line -> line.stripLeading().startsWith(MARKER))) {
            throw new IOException("marker inside index page: " + path);
        }
    }

    private static void rows(
            String text,
            String path,
            String source,
            Map<String, IssueIndex> result,
            Workflow workflow)
            throws IOException {
        for (final Row row : indexRows(text, workflow, source)) {
            workflow.order(row.state());
            final IssueIndex previous =
                    result.put(
                            row.number(),
                            new IssueIndex(
                                    row.number(),
                                    row.state(),
                                    row.size(),
                                    row.title(),
                                    path,
                                    source,
                                    row.blockedBy(),
                                    new IssueIndex.Location(
                                            row.anchor(),
                                            row.file(),
                                            row.line(),
                                            row.numberEnd())));
            if (previous != null)
                throw new IOException(
                        source
                                + ": duplicate index row "
                                + row.number()
                                + "; also in "
                                + previous.source());
        }
    }

    static String page(String path, String source, String text) throws IOException {
        text = text.replace("\r\n", "\n");
        refuseMarkers(text, path);
        return MARKER
                + JSON.writeValueAsString(
                        Map.of("path", path, "source", source, "length", text.length()))
                + " -->\n"
                + text;
    }

    /**
     * An index row's first cell: an optional anchor, then the number, perhaps linked to its file.
     */
    private static final Pattern ROW =
            Pattern.compile(
                    "^\\|\\s*(?:<a id=\"([^\"]*)\"></a>)?\\s*\\[?([A-Z]{1,3}\\d{2,3}(?:\\.[1-9]\\d{0,2})?)\\b(?:\\]\\([^)]*\\))?");

    /** A Markdown link with its text and target. */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]*)\\]\\(([^)\\s]+)\\)");

    /** An issue number with its prefix apart, a sub-issue's with its dot and number. */
    private static final Pattern NUMBER =
            Pattern.compile("\\b([A-Z]{1,3})\\d{2,3}(?:\\.[1-9]\\d{0,2})?\\b");

    /** A code span on one line. */
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`\\n]*`");

    static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}(?:[ \t]+(.*))?$");
    private static final Pattern ISSUE_TABLE = Pattern.compile("^\\s*\\|\\s*#\\s*\\|.*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    static @Nullable String indexPath(String index, String target) {
        target = target.split("#", 2)[0];
        if (target.isEmpty() || target.contains(":") || target.startsWith("/")) {
            return null;
        }
        final Path path = Path.of(index).resolveSibling(target).normalize();
        return path.startsWith("issues") ? path.toString().replace('\\', '/') : null;
    }

    /** The citation check and plan reader share one interpretation of an index row. */
    static List<Row> indexRows(String text, Workflow workflow, String source) {
        final List<Row> result = new ArrayList<>();
        String state = "";
        boolean stateSection = false;
        Map<String, Integer> columns = Map.of();
        final String[] lines = unfenced(text).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                state =
                        heading.group(1) == null
                                ? ""
                                : heading.group(1)
                                        .strip()
                                        .replaceFirst("(?:^|\\s+)#+\\s*$", "")
                                        .stripTrailing();
                stateSection = workflow.states().contains(state) || issueTableFollows(lines, i);
                if (stateSection) requireState(state, source, i + 1, workflow);
                columns = Map.of();
            }
            if (ISSUE_TABLE.matcher(line).matches() && !stateSection) {
                requireState("", source, i + 1, workflow);
            }
            if (!stateSection) continue;
            final String[] cells = line.split("(?<!\\\\)\\|", -1);
            if (ISSUE_TABLE.matcher(line).matches()) {
                final Map<String, Integer> header = new HashMap<>();
                for (int column = 1; column < cells.length - 1; column++) {
                    header.put(cells[column].strip().toLowerCase(java.util.Locale.ROOT), column);
                }
                columns = header;
            }
            final Matcher row = ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            final Matcher link = LINK.matcher(cells[1]);
            final String file = link.find() ? link.group(2) : null;
            final boolean closed;
            try {
                closed = workflow.closed(state);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        source
                                + ":"
                                + (i + 1)
                                + ": unknown state heading: "
                                + state
                                + "; "
                                + ex.getMessage(),
                        ex);
            }
            final String size = closed ? "-" : cell(cells, columns.getOrDefault("size", 2));
            final String title =
                    cell(
                            cells,
                            columns.getOrDefault(
                                    closed ? "title" : "what it covers", closed ? 2 : 4));
            // Only a column so headed holds waits; an index without it has none.
            final Integer blocked = columns.get("blocked by");
            final List<String> blockedBy = new ArrayList<>();
            if (blocked != null) {
                final Matcher number = NUMBER.matcher(blank(CODE_SPAN, cell(cells, blocked)));
                while (number.find()) {
                    if (!blockedBy.contains(number.group())) {
                        blockedBy.add(number.group());
                    }
                }
            }
            final Map<String, String> named = new HashMap<>();
            columns.forEach((header, column) -> named.put(header, cell(cells, column)));
            result.add(
                    new Row(
                            row.group(2),
                            row.group(1),
                            state,
                            size,
                            title,
                            file,
                            i + 1,
                            row.end(),
                            List.copyOf(blockedBy),
                            Map.copyOf(named)));
        }
        return result;
    }

    private static void requireState(String state, String source, int line, Workflow workflow) {
        try {
            workflow.order(state);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    source
                            + ":"
                            + line
                            + ": "
                            + (state.isEmpty()
                                    ? "issue table has no state heading"
                                    : "unknown state heading: " + state)
                            + "; "
                            + ex.getMessage(),
                    ex);
        }
    }

    static boolean issueTableFollows(String[] lines, int heading) {
        boolean separated = false;
        for (int i = heading + 1; i < lines.length; i++) {
            if (HEADING.matcher(lines[i]).matches()) return false;
            if (ISSUE_TABLE.matcher(lines[i]).matches()) return true;
            if (lines[i].isBlank()) separated = true;
            else if (separated) return false;
        }
        return false;
    }

    private static String cell(String[] cells, int column) {
        return column < cells.length - 1 ? cells[column].strip() : "";
    }

    static String unfenced(String text) {
        final StringBuilder result = new StringBuilder();
        String fence = "";
        for (final String line : text.split("\n", -1)) {
            final Matcher marker = FENCE.matcher(line);
            if (marker.matches()) {
                final String run = marker.group(1);
                if (fence.isEmpty()) {
                    fence = run;
                } else if (run.charAt(0) == fence.charAt(0)
                        && run.length() >= fence.length()
                        && marker.group(2).isBlank()) {
                    fence = "";
                }
                result.append('\n');
            } else {
                result.append(fence.isEmpty() ? line : "").append('\n');
            }
        }
        return result.toString();
    }

    record Row(
            String number,
            @Nullable String anchor,
            String state,
            String size,
            String title,
            @Nullable String file,
            int line,
            int numberEnd,
            List<String> blockedBy,
            Map<String, String> cells) {}

    private static String blank(Pattern pattern, String text) {
        return pattern.matcher(text).replaceAll(found -> " ".repeat(found.group().length()));
    }
}
