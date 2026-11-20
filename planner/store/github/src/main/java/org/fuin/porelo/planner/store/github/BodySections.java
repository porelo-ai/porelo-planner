package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/**
 * An issue's body on GitHub, read as its parts: the leading paragraphs ({@code covers}, <em>What
 * must be true</em>, <em>Why this size</em>) and its second-level sections. A part is overwritten,
 * added or removed, and every other byte of the body stays as it was, its line endings too.
 */
public final class BodySections {

    /** The longest body GitHub takes for an issue. */
    public static final int LONGEST = 65_536;

    /**
     * The parts a stage result or the mapping writes, in the order the issue skeleton puts them.
     */
    public enum Part {
        /** The first paragraph, which says what the issue covers. */
        COVERS(null),
        /** What must be true once it is done. */
        WHAT_MUST_BE_TRUE("**What must be true.**"),
        /** One line on why it has its size. */
        WHY_THIS_SIZE("**Why this size.**"),
        /** The plan. */
        PLAN("Plan"),
        /** How it is judged done. */
        ACCEPTANCE("Acceptance"),
        /** What a person must decide. */
        OPEN_QUESTIONS("Open questions"),
        /** What is still to be measured, read or confirmed. */
        TO_BE_CHECKED("To be checked"),
        /** What a stage found. */
        FINDINGS("Findings");

        private final @Nullable String label;

        Part(final @Nullable String label) {
            this.label = label;
        }

        private boolean leading() {
            return ordinal() <= WHY_THIS_SIZE.ordinal();
        }

        private String label() {
            return java.util.Objects.requireNonNull(label);
        }

        /** The label without its emphasis and stop, as a message names it. */
        private String named() {
            return label().replace("*", "").replace(".", "");
        }
    }

    /**
     * The refusal to write a body whose sections cannot be told apart for certain. Its message says
     * the first thing a person must repair, naming lines by their number and sections only by the
     * skeleton's names, never by the body's own text.
     */
    public static final class Ambiguous extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        /** What to repair. */
        private final String repair;

        private Ambiguous(final String repair) {
            super("The body's sections cannot be told apart for certain: " + repair);
            this.repair = repair;
        }

        /**
         * Answers what a person must repair first.
         *
         * @return the repair, in fixed words and line numbers
         */
        public String repair() {
            return repair;
        }
    }

    /** One line of the body with the ending it has, empty for a last line without one. */
    private record Line(String text, String end) {

        private boolean blank() {
            return text.isBlank();
        }
    }

    /** The body's lines, in order. */
    private final List<Line> lines;

    /** The line ending a written line takes: the body's first one. */
    private final String eol;

    /** Whether each line stands in a code block, a fence that opens it or one that closes it. */
    private final boolean[] code;

    private BodySections(final List<Line> lines) {
        this.lines = List.copyOf(lines);
        this.code = code(this.lines);
        this.eol =
                lines.stream()
                        .map(Line::end)
                        .filter(end -> !end.isEmpty())
                        .findFirst()
                        .orElse("\n");
    }

    /**
     * Reads a body.
     *
     * @param body the issue's body as GitHub holds it
     * @return its parts
     * @throws IllegalArgumentException when the body is longer than GitHub takes
     */
    public static BodySections read(final String body) {
        bounded(body);
        final List<Line> lines = new ArrayList<>();
        int at = 0;
        while (at < body.length()) {
            final int feed = body.indexOf('\n', at);
            if (feed < 0) {
                lines.add(new Line(body.substring(at), ""));
                break;
            }
            final boolean cr = feed > at && body.charAt(feed - 1) == '\r';
            lines.add(new Line(body.substring(at, cr ? feed - 1 : feed), cr ? "\r\n" : "\n"));
            at = feed + 1;
        }
        return new BodySections(lines);
    }

    /**
     * Answers the body as it now stands.
     *
     * @return the body's text
     */
    public String text() {
        final StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line.text()).append(line.end()));
        return text.toString();
    }

    /**
     * Reads a part.
     *
     * @param part the part
     * @return its text without its lead or heading, with line feeds and without the blank lines
     *     around it, or empty when the body has no such part
     */
    public Optional<String> part(final Part part) {
        if (part.leading()) {
            final int[] span = paragraph(part);
            if (span.length == 0) {
                return Optional.empty();
            }
            String text = joined(span[0], span[1]);
            if (part != Part.COVERS) {
                text = text.substring(part.label().length());
            }
            return Optional.of(text.strip());
        }
        return section(part.label());
    }

    /**
     * Reads a second-level section by its name, such as {@code Also needs}.
     *
     * @param name the heading's text after {@code ## }
     * @return its text with line feeds and without the blank lines around it, or empty when the
     *     body has no such section; the first of two with one name
     */
    public Optional<String> section(final String name) {
        final int heading = heading(name);
        if (heading < 0) {
            return Optional.empty();
        }
        return Optional.of(joined(heading + 1, sectionEnd(heading)).strip());
    }

    /**
     * Writes a part: replaces its text, or adds it where the skeleton puts it.
     *
     * @param part the part
     * @param text its new text, without its lead or heading
     * @return the body with the part written
     * @throws Ambiguous when the body's sections cannot be told apart for certain
     * @throws IllegalArgumentException when the text is blank, would open a part of its own, or the
     *     body would grow longer than GitHub takes
     */
    public BodySections with(final Part part, final String text) {
        unambiguous();
        final List<String> content = content(part, text);
        final List<Line> out = new ArrayList<>(lines);
        if (part.leading()) {
            if (part != Part.COVERS) {
                content.set(0, part.label() + " " + content.get(0));
            }
            final int[] span = paragraph(part);
            if (span.length == 0) {
                insert(out, leadingPlace(part), content);
            } else {
                final String end = out.get(span[1] - 1).end();
                final List<Line> paragraph = new ArrayList<>(numbered(content, eol));
                paragraph.set(paragraph.size() - 1, new Line(paragraph.getLast().text(), end));
                out.subList(span[0], span[1]).clear();
                out.addAll(span[0], paragraph);
            }
        } else {
            final int heading = heading(part.label());
            if (heading < 0) {
                final List<String> section = new ArrayList<>();
                section.add("## " + part.label());
                section.add("");
                section.addAll(content);
                insert(out, sectionPlace(part), section);
            } else {
                final int end = sectionEnd(heading);
                final boolean last = end == out.size();
                // The body's last line keeps its ending, a missing one too.
                final String lastEnd = out.get(end - 1).end();
                final List<String> body = new ArrayList<>();
                body.add("");
                body.addAll(content);
                if (end < out.size()) {
                    body.add("");
                }
                out.set(heading, new Line(out.get(heading).text(), eol));
                out.subList(heading + 1, end).clear();
                final List<Line> written = new ArrayList<>(numbered(body, eol));
                if (last) {
                    written.set(written.size() - 1, new Line(written.getLast().text(), lastEnd));
                }
                out.addAll(heading + 1, written);
            }
        }
        return checked(out);
    }

    /**
     * Removes a part with its lead or heading.
     *
     * @param part the part
     * @return the body without it, or as it was when it has none
     * @throws Ambiguous when the body's sections cannot be told apart for certain
     */
    public BodySections without(final Part part) {
        unambiguous();
        final List<Line> out = new ArrayList<>(lines);
        if (part.leading()) {
            final int[] span = paragraph(part);
            if (span.length == 0) {
                return this;
            }
            int end = span[1];
            // The blank line that parted it from the next goes with it.
            if (end < out.size() && out.get(end).blank()) {
                end++;
            }
            out.subList(span[0], end).clear();
        } else {
            final int heading = heading(part.label());
            if (heading < 0) {
                return this;
            }
            final int end = sectionEnd(heading);
            // A last section takes the blank line above it, so the body ends as the one before.
            final int start =
                    end == out.size() && heading > 0 && out.get(heading - 1).blank()
                            ? heading - 1
                            : heading;
            out.subList(start, end).clear();
        }
        return new BodySections(out);
    }

    /**
     * Tells whether the body splits into sections unambiguously, as a write needs: every code block
     * closes, none runs over a heading-shaped line and a fence of its own (the sign of a fence left
     * open by hand that pairs with a later block's), no section's heading stands twice, and the
     * leading paragraphs stand in the skeleton's order, each once. A read takes the body either
     * way.
     *
     * @return the first thing a person must repair, in fixed words and line numbers, or empty when
     *     the body can be written
     */
    public Optional<String> ambiguity() {
        final Fence fence = new Fence();
        int opened = -1;
        boolean heading = false;
        boolean inner = false;
        for (int at = 0; at < lines.size(); at++) {
            final String text = lines.get(at).text();
            if (opened < 0) {
                if (fence.inside(text)) {
                    opened = at;
                    heading = false;
                    inner = false;
                }
                continue;
            }
            fence.inside(text);
            if (!fence.open()) {
                if (heading && inner) {
                    return Optional.of(
                            "the code block opened in "
                                    + place(opened)
                                    + " runs over a heading and a fence of its own, so where it"
                                    + " closes is unclear");
                }
                opened = -1;
            } else {
                heading |= headingName(text) != null;
                inner |= new Fence().inside(text);
            }
        }
        if (opened >= 0) {
            return Optional.of("the code block opened in " + place(opened) + " never closes");
        }
        final java.util.Map<String, Integer> headings = new java.util.HashMap<>();
        for (int at = 0; at < lines.size(); at++) {
            final String name = code[at] ? null : headingName(lines.get(at).text());
            if (name == null) {
                continue;
            }
            final Integer first = headings.putIfAbsent(name, at);
            if (first != null) {
                return Optional.of(
                        (known(name) ? "the section " + name : "the heading")
                                + " in line "
                                + (at + 1)
                                + " repeats the one in line "
                                + (first + 1));
            }
        }
        @Nullable Part before = null;
        for (final int[] span : paragraphs()) {
            final Part lead = lead(lines.get(span[0]).text());
            if (lead == null) {
                continue;
            }
            if (before != null && lead.ordinal() <= before.ordinal()) {
                return Optional.of(
                        "the paragraph "
                                + lead.named()
                                + " in line "
                                + (span[0] + 1)
                                + (lead == before
                                        ? " stands twice"
                                        : " stands after " + before.named() + ", not before it"));
            }
            before = lead;
        }
        return Optional.empty();
    }

    private void unambiguous() {
        final Optional<String> repair = ambiguity();
        if (repair.isPresent()) {
            throw new Ambiguous(repair.get());
        }
    }

    /** A line by its number, with the section it stands in when that is one of the skeleton's. */
    private String place(final int at) {
        for (int heading = at - 1; heading >= 0; heading--) {
            final String name = code[heading] ? null : headingName(lines.get(heading).text());
            if (name != null) {
                // A person's own heading is their words, so it is not repeated.
                return "line " + (at + 1) + (known(name) ? " under " + name : "");
            }
        }
        return "line " + (at + 1);
    }

    /** Whether a heading's name is a section of the skeleton, safe to repeat in a message. */
    private static boolean known(final String name) {
        for (final Part part : Part.values()) {
            if (!part.leading() && part.label().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes a stage's result into the body: the parts a result of its kind writes, each replacing
     * what stood there, every other part as it was. Its size and an estimate's minutes are fields
     * of the project, not parts of the body.
     *
     * @param kind what the stage handed back
     * @param result the issue's result
     * @return the body with the result
     * @throws IllegalArgumentException as {@link #with(Part, String)}
     */
    public BodySections withResult(final WorkRule.Result kind, final StageResult.ForIssue result) {
        BodySections body = with(Part.WHY_THIS_SIZE, result.sizeWhy());
        final String plan = result.plan();
        if (plan != null) {
            body = body.with(Part.PLAN, plan);
        }
        // Only an analysis says what is still to be checked; an estimate leaves it as it stands.
        if (kind == WorkRule.Result.ANALYSIS) {
            body =
                    result.toBeChecked().isEmpty()
                            ? body.without(Part.TO_BE_CHECKED)
                            : body.with(Part.TO_BE_CHECKED, bullets(result.toBeChecked()));
        }
        if (!result.findings().isEmpty()) {
            body = body.with(Part.FINDINGS, bullets(result.findings()));
        }
        return body;
    }

    private static String bullets(final List<String> items) {
        final StringBuilder text = new StringBuilder();
        items.forEach(item -> text.append("- ").append(item.strip()).append('\n'));
        return text.toString();
    }

    private static void bounded(final String body) {
        if (body.length() > LONGEST) {
            throw new IllegalArgumentException(
                    "The issue body is longer than the " + LONGEST + " characters GitHub takes");
        }
    }

    private static BodySections checked(final List<Line> out) {
        final BodySections body = new BodySections(out);
        bounded(body.text());
        return body;
    }

    /** The text's lines, refused when blank or when they would open a part of their own. */
    private static List<String> content(final Part part, final String text) {
        final String lf = text.replace("\r\n", "\n").strip();
        if (lf.isEmpty() || lf.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("A part of the body is blank or breaks lines oddly");
        }
        final List<String> content = new ArrayList<>(List.of(lf.split("\n", -1)));
        final Fence fence = new Fence();
        for (final String line : content) {
            final boolean code = fence.inside(line);
            if (!code && headingName(line) != null
                    || part.leading() && (line.isBlank() || lead(line) != null)) {
                throw new IllegalArgumentException(
                        "A part of the body holds a heading, a lead or a blank line that would"
                                + " open a part of its own");
            }
        }
        if (fence.open()) {
            throw new IllegalArgumentException("A part of the body leaves a code block open");
        }
        return content;
    }

    /** The lines, each ending as given. */
    private static List<Line> numbered(final List<String> texts, final String end) {
        return texts.stream().map(text -> new Line(text, end)).toList();
    }

    /** Inserts new lines at an index, parted from what stands before and after by a blank line. */
    private void insert(final List<Line> out, final int at, final List<String> texts) {
        final List<Line> added = new ArrayList<>();
        if (at > 0) {
            final Line before = out.get(at - 1);
            if (before.end().isEmpty()) {
                out.set(at - 1, new Line(before.text(), eol));
            }
            if (!before.blank()) {
                added.add(new Line("", eol));
            }
        }
        added.addAll(numbered(texts, eol));
        if (at < out.size() && !out.get(at).blank()) {
            added.add(new Line("", eol));
        }
        out.addAll(at, added);
    }

    /**
     * Where a missing leading part goes: what it covers first, where it is read back; any other
     * after the last earlier one, else before a later one.
     */
    private int leadingPlace(final Part part) {
        if (part == Part.COVERS) {
            return 0;
        }
        for (int earlier = part.ordinal() - 1; earlier >= 0; earlier--) {
            final int[] span = paragraph(Part.values()[earlier]);
            if (span.length > 0) {
                return span[1];
            }
        }
        for (int later = part.ordinal() + 1; later <= Part.WHY_THIS_SIZE.ordinal(); later++) {
            final int[] span = paragraph(Part.values()[later]);
            if (span.length > 0) {
                return span[0];
            }
        }
        return firstHeading();
    }

    /** Where a missing section goes: after the last earlier one, else before a later one. */
    private int sectionPlace(final Part part) {
        int at = -1;
        for (int earlier = Part.PLAN.ordinal(); earlier < part.ordinal(); earlier++) {
            final int heading = heading(Part.values()[earlier].label());
            if (heading >= 0) {
                at = Math.max(at, sectionEnd(heading));
            }
        }
        if (at >= 0) {
            return at;
        }
        for (int later = part.ordinal() + 1; later < Part.values().length; later++) {
            final int heading = heading(Part.values()[later].label());
            if (heading >= 0) {
                return heading;
            }
        }
        return lines.size();
    }

    /**
     * The span of a leading part's paragraph, {@code [start, end)}, or none; the covers paragraph
     * is the first one, when it opens with no lead.
     */
    private int[] paragraph(final Part part) {
        final List<int[]> spans = paragraphs();
        for (final int[] span : spans) {
            final Part lead = lead(lines.get(span[0]).text());
            if (part == Part.COVERS ? lead == null && span == spans.getFirst() : lead == part) {
                return span;
            }
        }
        return new int[0];
    }

    /**
     * The paragraphs above the first section, each {@code [start, end)}: parted by a blank line or
     * a lead outside code blocks.
     */
    private List<int[]> paragraphs() {
        final int stop = firstHeading();
        final List<int[]> spans = new ArrayList<>();
        int start = -1;
        for (int at = 0; at < stop; at++) {
            final String text = lines.get(at).text();
            final boolean blank = !code[at] && text.isBlank();
            if (start >= 0 && (blank || !code[at] && lead(text) != null)) {
                spans.add(new int[] {start, at});
                start = -1;
            }
            if (start < 0 && !blank) {
                start = at;
            }
        }
        if (start >= 0) {
            spans.add(new int[] {start, stop});
        }
        return spans;
    }

    private static @Nullable Part lead(final String text) {
        for (final Part part : List.of(Part.WHAT_MUST_BE_TRUE, Part.WHY_THIS_SIZE)) {
            if (text.startsWith(part.label())) {
                return part;
            }
        }
        return null;
    }

    /** The index of the first section's heading, or the line count. */
    private int firstHeading() {
        for (int at = 0; at < lines.size(); at++) {
            if (!code[at] && headingName(lines.get(at).text()) != null) {
                return at;
            }
        }
        return lines.size();
    }

    /** The index of a section's heading outside code blocks, or -1. */
    private int heading(final String name) {
        for (int at = 0; at < lines.size(); at++) {
            if (!code[at] && name.equals(headingName(lines.get(at).text()))) {
                return at;
            }
        }
        return -1;
    }

    /** The index after a section's last line: the next heading outside code blocks, or the end. */
    private int sectionEnd(final int heading) {
        for (int at = heading + 1; at < lines.size(); at++) {
            if (!code[at] && headingName(lines.get(at).text()) != null) {
                return at;
            }
        }
        return lines.size();
    }

    /**
     * Which lines stand in a code block. A fence that is never closed opens none: Markdown would
     * run it to the body's end, but then a write over the part above it would take every section
     * after it, so its headings stay sections for reads and writes alike.
     */
    private static boolean[] code(final List<Line> lines) {
        final boolean[] code = new boolean[lines.size()];
        int at = 0;
        while (at < lines.size()) {
            final Fence fence = new Fence();
            if (!fence.inside(lines.get(at).text())) {
                at++;
                continue;
            }
            int close = -1;
            for (int next = at + 1; next < lines.size() && close < 0; next++) {
                fence.inside(lines.get(next).text());
                if (!fence.open()) {
                    close = next;
                }
            }
            if (close < 0) {
                at++;
                continue;
            }
            java.util.Arrays.fill(code, at, close + 1, true);
            at = close + 1;
        }
        return code;
    }

    /** A second-level heading's name, or null for any other line. */
    private static @Nullable String headingName(final String text) {
        return text.startsWith("## ") ? text.substring(3).strip() : null;
    }

    private String joined(final int from, final int to) {
        final StringBuilder text = new StringBuilder();
        for (final Line line : lines.subList(from, to)) {
            text.append(line.text()).append('\n');
        }
        return text.toString();
    }

    /**
     * Tells code blocks apart as Markdown does: a fence of three or more backticks or tildes,
     * indented by three spaces at most, closed by a bare fence of the same kind at least as long; a
     * backtick fence whose words after it hold a backtick is inline code, no fence.
     */
    static final class Fence {

        private char kind;

        private int length;

        /** Whether the line opens, closes or stands inside a code block. */
        boolean inside(final String text) {
            final String bare = text.stripLeading();
            if (text.length() - bare.length() > 3) {
                return length > 0;
            }
            final int run = run(bare);
            if (length > 0) {
                if (run >= length && bare.charAt(0) == kind && bare.substring(run).isBlank()) {
                    length = 0;
                }
                return true;
            }
            if (run >= 3 && (bare.charAt(0) != '`' || bare.indexOf('`', run) < 0)) {
                kind = bare.charAt(0);
                length = run;
                return true;
            }
            return false;
        }

        private boolean open() {
            return length > 0;
        }

        private static int run(final String bare) {
            if (bare.isEmpty() || bare.charAt(0) != '`' && bare.charAt(0) != '~') {
                return 0;
            }
            int run = 0;
            while (run < bare.length() && bare.charAt(run) == bare.charAt(0)) {
                run++;
            }
            return run;
        }
    }
}
