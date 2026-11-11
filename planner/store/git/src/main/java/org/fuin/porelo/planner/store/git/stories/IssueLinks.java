package org.fuin.porelo.planner.store.git.stories;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads how the pages under {@code issues/} name an issue: every number a link to its row in an
 * index, every row anchored by its number, every such link reaching an anchor that is there, and
 * every number of the repository's own naming an issue that exists.
 *
 * <p>The row outlives the file - a finished issue keeps its row - so a link to the row does not
 * break when the issue is done. Only the repository's own pages are read: a link into another
 * repository's index is left unchecked, so another repository's state never decides this one's
 * faults.
 */
public final class IssueLinks {

    /** Where the repositories' indexes are published. */
    private static final Pattern GITHUB =
            Pattern.compile(
                    "^https://github\\.com/porelo-ai/([\\w.-]+)/blob/main/([^#\\s]+)#([a-z]{1,3}\\d{2,3})$");

    /** An anchor anywhere on a page. */
    private static final Pattern ANCHOR = Pattern.compile("<a id=\"([^\"]+)\"></a>");

    /** A Markdown link with its text and target. */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]*)\\]\\(([^)\\s]+)\\)");

    /** A target ending in an anchor of an issue number's shape. */
    private static final Pattern TO_A_ROW = Pattern.compile("#([a-z]{1,3}\\d{2,3})$");

    /** The text of a link that is one issue number, perhaps emphasized. */
    private static final Pattern NUMBER_TEXT =
            Pattern.compile("^[*_`]*([A-Z]{1,3}\\d{2,3})[*_`]*$");

    /** An issue number with its prefix apart. */
    private static final Pattern NUMBER = Pattern.compile("\\b([A-Z]{1,3})\\d{2,3}\\b");

    /** An issue file's name: its number's prefix, then its digits. */
    private static final Pattern ISSUE_FILE =
            Pattern.compile("^(([A-Z]{1,3})\\d{2,3})(?:\\.[1-9]\\d{0,2})?-.*\\.md$");

    /** A code span on one line. */
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`\\n]*`");

    /** A line that says it holds the shape on purpose. */
    private static final String MARKED = "not-a-citation";

    /** A line that says it holds the shape on purpose, as a whole line. */
    private static final Pattern MARKED_LINE = Pattern.compile("(?m)^.*not-a-citation.*$");

    /** Names of an issue number's shape that are something else. */
    private static final Set<String> NOT_ISSUES = Set.of("S256");

    private static final String INDEX = "README.md";

    private final Map<String, String> pages;

    private final Map<String, Map<String, IssueIndex>> indexes;

    private final Function<String, Optional<String>> more;

    private final Map<String, Set<String>> anchors = new HashMap<>();

    private final Set<String> rows = new HashSet<>();

    private final Set<String> prefixes = new HashSet<>();

    private final Set<String> ownPrefixes = new HashSet<>();

    private final Set<String> issues = new HashSet<>();

    private final List<String> faults = new ArrayList<>();

    private IssueLinks(
            final Map<String, String> pages,
            final Map<String, Map<String, IssueIndex>> indexes,
            final Function<String, Optional<String>> more) {
        this.pages = pages;
        this.indexes = indexes;
        this.more = more;
    }

    /**
     * Reads the pages of one repository's {@code issues/} and names each way they cite an issue
     * that does not hold.
     *
     * @param pages each page read, by its path in the repository: the issue files and the indexes
     * @param indexes the rows of each index page that could be read, by its path; an index left out
     *     is named apart and only lends its anchors
     * @param more reads another page under {@code issues/} that a link names, empty when there is
     *     none
     * @return each fault, as {@code <path>:<line>: <what is wrong>}
     */
    public static List<String> check(
            final Map<String, String> pages,
            final Map<String, Map<String, IssueIndex>> indexes,
            final Function<String, Optional<String>> more) {
        final IssueLinks links = new IssueLinks(pages, indexes, more);
        links.read();
        return List.copyOf(links.faults);
    }

    private void read() {
        for (final String page : pages.keySet()) {
            final Matcher file = ISSUE_FILE.matcher(name(page));
            if (file.matches()) {
                ownPrefixes.add(file.group(2));
                issues.add(file.group(1));
            }
        }
        prefixes.addAll(ownPrefixes);
        final Map<String, Map<Integer, IssueIndex>> pageRows = new HashMap<>();
        for (final Map.Entry<String, String> page : pages.entrySet()) {
            final Map<String, IssueIndex> parsed = indexes.get(page.getKey());
            if (parsed == null) {
                anchors.put(page.getKey(), anchorsIn(page.getValue()));
                continue;
            }
            final Map<Integer, IssueIndex> positions = new HashMap<>();
            final Set<String> rowAnchors = new HashSet<>();
            for (final IssueIndex row : parsed.values()) {
                prefixes.add(prefixOf(row.number()));
                rows.add(row.number());
                final IssueIndex.Location location = row.location();
                positions.put(location.line(), row);
                if (location.anchor() != null) {
                    rowAnchors.add(location.anchor());
                }
                if (!row.number().toLowerCase(Locale.ROOT).equals(location.anchor())) {
                    faults.add(
                            page.getKey()
                                    + ":"
                                    + location.line()
                                    + ": the row of "
                                    + row.number()
                                    + " does not open with its anchor <a id=\""
                                    + row.number().toLowerCase(Locale.ROOT)
                                    + "\"></a>");
                }
            }
            pageRows.put(page.getKey(), positions);
            anchors.put(page.getKey(), rowAnchors);
        }
        final Set<String> named = new HashSet<>(issues);
        // A finished issue keeps its row when its file goes, so a row is as good as a file.
        named.addAll(rows);
        for (final Map.Entry<String, String> page : pages.entrySet()) {
            final boolean index = name(page.getKey()).equals(INDEX);
            if (index && !pageRows.containsKey(page.getKey())) {
                // An index that cannot be read is named apart; its rows are not guessed at.
                continue;
            }
            readPage(
                    page.getKey(), page.getValue(), pageRows.getOrDefault(page.getKey(), Map.of()));
            ownNumbersThatAreGone(page.getKey(), page.getValue(), named);
        }
    }

    private void readPage(
            final String page, final String text, final Map<Integer, IssueIndex> parsedRows) {
        final String[] lines = text.split("\n", -1);
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced || line.startsWith("#") || line.contains(MARKED)) {
                continue;
            }
            final String where = page + ":" + (i + 1) + ": ";
            line = blank(CODE_SPAN, line);
            // The row's own first cell names its file, as an index does.
            final IssueIndex row = parsedRows.get(i + 1);
            if (row != null) {
                final int end = Math.min(row.location().numberEnd(), line.length());
                line = " ".repeat(end) + line.substring(end);
            }
            final Matcher link = LINK.matcher(line);
            final StringBuilder rest = new StringBuilder();
            int last = 0;
            while (link.find()) {
                checkLink(page, where, link.group(1), link.group(2));
                rest.append(line, last, link.start()).append(" ".repeat(link.end() - link.start()));
                last = link.end();
            }
            rest.append(line.substring(last));
            final Matcher number = NUMBER.matcher(rest);
            while (number.find()) {
                final String found = number.group();
                if (!prefixes.contains(number.group(1)) || NOT_ISSUES.contains(found)) {
                    continue;
                }
                if (rows.contains(found)) {
                    faults.add(where + found + " is not a link to its index row");
                } else if (!ownPrefixes.contains(number.group(1))) {
                    // One of the repository's own is the deletion a mention missed, said apart.
                    faults.add(where + found + " has no row in an index, so it cannot be linked");
                }
            }
        }
    }

    private void checkLink(
            final String page, final String where, final String text, final String target) {
        final Matcher named = NUMBER_TEXT.matcher(text.strip());
        final boolean number = named.matches() && !NOT_ISSUES.contains(named.group(1));
        final Matcher toRow = TO_A_ROW.matcher(target);
        if (!toRow.find()) {
            if (number) {
                faults.add(
                        where + named.group(1) + " links to " + target + ", not to its index row");
            }
            return;
        }
        final String anchor = toRow.group(1);
        if (number && !named.group(1).toLowerCase(Locale.ROOT).equals(anchor)) {
            faults.add(where + named.group(1) + " links to " + target + ", another number's row");
            return;
        }
        if (GITHUB.matcher(target).matches() || target.contains("://")) {
            // Another repository's index is not read here.
            return;
        }
        // A link to an anchor on the same page names no file.
        final String file =
                target.startsWith("#")
                        ? page
                        : resolve(folder(page), target.substring(0, target.indexOf('#')));
        final Set<String> here = file == null ? null : anchorsOf(file);
        if (here == null || !here.contains(anchor)) {
            faults.add(where + target + " reaches no row's anchor");
        }
    }

    private void ownNumbersThatAreGone(
            final String page, final String text, final Set<String> named) {
        final String read = blank(MARKED_LINE, withoutHead(text));
        final Matcher number = NUMBER.matcher(read);
        while (number.find()) {
            if (ownPrefixes.contains(number.group(1)) && !named.contains(number.group())) {
                faults.add(
                        page
                                + ":"
                                + lineOf(text, number.start())
                                + ": "
                                + number.group()
                                + " names no issue of this repository, a mention its deletion"
                                + " missed");
            }
        }
    }

    /** The anchors of a page, a page no reading fetched read once when a link names it. */
    private Set<String> anchorsOf(final String file) {
        final Set<String> known = anchors.get(file);
        if (known != null) {
            return known;
        }
        final Set<String> found =
                file.startsWith("issues/") && file.endsWith(".md")
                        ? more.apply(file).map(IssueLinks::anchorsIn).orElse(Set.of())
                        : Set.of();
        anchors.put(file, found);
        return found;
    }

    /** The text with the heading and the head blanked, its lines kept for their numbers. */
    private static String withoutHead(final String text) {
        final int lines = IssueHead.lines(text);
        if (lines == 0) {
            return text;
        }
        int end = 0;
        for (int i = 0; i < lines; i++) {
            end = text.indexOf('\n', end) + 1;
        }
        if (end <= 0) {
            end = text.length();
        }
        return text.substring(0, end).replaceAll("[^\n]", " ") + text.substring(end);
    }

    private static String folder(final String page) {
        final int slash = page.lastIndexOf('/');
        return slash < 0 ? "" : page.substring(0, slash);
    }

    private static String name(final String page) {
        return page.substring(page.lastIndexOf('/') + 1);
    }

    /** A relative target read from a folder, or {@code null} when it leaves the repository. */
    private static @org.jspecify.annotations.Nullable String resolve(
            final String folder, final String target) {
        if (target.startsWith("/")) {
            return null;
        }
        final Deque<String> parts = new ArrayDeque<>();
        for (final String part : (folder + "/" + target).split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    private static Set<String> anchorsIn(final String text) {
        final Set<String> anchors = new HashSet<>();
        final Matcher anchor = ANCHOR.matcher(text);
        while (anchor.find()) {
            anchors.add(anchor.group(1));
        }
        return anchors;
    }

    private static String prefixOf(final String number) {
        return number.replaceAll("[\\d.]+$", "");
    }

    private static String blank(final Pattern pattern, final String text) {
        return pattern.matcher(text).replaceAll(found -> " ".repeat(found.group().length()));
    }

    private static int lineOf(final String text, final int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }
}
