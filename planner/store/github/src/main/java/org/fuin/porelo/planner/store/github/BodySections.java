package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/**
 * An issue's or a story's body on GitHub, read as its parts: the leading paragraphs ({@code
 * covers}, <em>Why this size</em>, <em>What must be true</em>, a story's <em>What a user gains</em>
 * and <em>The test</em>) and its second-level sections. A part is overwritten, added or removed,
 * and every other byte of the body stays as it was, its line endings too.
 */
public final class BodySections {

    /** The longest body GitHub takes for an issue. */
    public static final int LONGEST = 65_536;

    /**
     * The heading of what must be true written as a section, for a text of more than one paragraph
     * that the lead cannot hold.
     */
    public static final String WHAT_MUST_BE_TRUE_SECTION = "What must be true";

    /**
     * The parts a stage result or the mapping writes, in the order the issue skeleton puts them.
     */
    public enum Part {
        /** The first paragraph, which says what the issue covers. */
        COVERS(null),
        /** One line on why it has its size. */
        WHY_THIS_SIZE("**Why this size.**"),
        /** What must be true once it is done. */
        WHAT_MUST_BE_TRUE("**What must be true.**"),
        /** What a user gains from a story. */
        GAIN("**What a user gains.**"),
        /** How a story is tested. */
        TEST("**The test.**"),
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
            return ordinal() < PLAN.ordinal();
        }

        /**
         * The part a text edit names.
         *
         * @param part the text edit's part
         * @return the body's part of that name
         */
        public static Part of(final TextEdit.Part part) {
            return switch (part) {
                case COVERS -> COVERS;
                case WHY_THIS_SIZE -> WHY_THIS_SIZE;
                case WHAT_MUST_BE_TRUE -> WHAT_MUST_BE_TRUE;
                case GAIN -> GAIN;
                case TEST -> TEST;
                case PLAN -> PLAN;
                case ACCEPTANCE -> ACCEPTANCE;
                case OPEN_QUESTIONS -> OPEN_QUESTIONS;
                case TO_BE_CHECKED -> TO_BE_CHECKED;
                case FINDINGS -> FINDINGS;
            };
        }

        private String label() {
            return java.util.Objects.requireNonNull(label);
        }

        /** The label without its emphasis and stop, as a message names it. */
        private String named() {
            return label().replace("*", "").replace(".", "");
        }
    }

    /** The refusal of a body longer than the {@value #LONGEST} characters GitHub takes. */
    public static final class TooLong extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private TooLong() {
            super("The issue body is longer than the " + LONGEST + " characters GitHub takes");
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
     * Applies a person's edit to a body: the parts it removes, then the parts it writes; every byte
     * outside them stays.
     *
     * @param id the item's id, named in a refusal
     * @param held the body as the store holds it
     * @param edit the parts to remove and write
     * @return the new body
     * @throws TextEdit.Refused in fixed words, which never repeat the body, when it cannot be
     *     written as asked
     */
    public static String edited(final String id, final String held, final TextEdit edit) {
        try {
            BodySections body = read(held);
            for (final TextEdit.Part part : TextEdit.Part.values()) {
                if (edit.removed().contains(part)) {
                    body = body.without(Part.of(part));
                }
            }
            for (final TextEdit.Part part : TextEdit.Part.values()) {
                final String text = edit.written().get(part);
                if (text != null) {
                    body = body.withEdited(Part.of(part), text);
                }
            }
            return body.text();
        } catch (final IllegalArgumentException e) {
            // The words here, an ambiguous body's repair too, are fixed and never repeat it.
            throw new TextEdit.Refused(id, String.valueOf(e.getMessage()));
        }
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
                return part == Part.WHAT_MUST_BE_TRUE
                        ? section(WHAT_MUST_BE_TRUE_SECTION)
                        : Optional.empty();
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
     * @throws IllegalArgumentException when the text is blank, would open a part of its own, would
     *     leave the body's sections unclear, or the body would grow longer than GitHub takes
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
            return checked(out);
        }
        return withSection(part.label(), content, sectionPlace(part));
    }

    /**
     * Writes a second-level section: replaces its text, or adds it at a place.
     *
     * @param label its heading's name
     * @param content its lines, checked
     * @param place where it goes when the body has none
     */
    private BodySections withSection(
            final String label, final List<String> content, final int place) {
        final List<Line> out = new ArrayList<>(lines);
        final int heading = heading(label);
        if (heading < 0) {
            final List<String> section = new ArrayList<>();
            section.add("## " + label);
            section.add("");
            section.addAll(content);
            insert(out, place, section);
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
        return checked(out);
    }

    /**
     * Writes what must be true: as the lead's paragraph when it is one paragraph the lead holds,
     * else as a section of its own above every other section, where a body that has that section
     * keeps it.
     *
     * @param text its text
     * @return the body with it written
     * @throws Ambiguous when the body's sections cannot be told apart for certain
     * @throws IllegalArgumentException when even a section cannot hold the text, or the body would
     *     grow longer than GitHub takes
     */
    public BodySections withWhatMustBeTrue(final String text) {
        unambiguous();
        if (heading(WHAT_MUST_BE_TRUE_SECTION) < 0) {
            try {
                return with(Part.WHAT_MUST_BE_TRUE, text);
            } catch (final Ambiguous e) {
                throw e;
            } catch (final IllegalArgumentException e) {
                // A text the lead cannot hold goes into a section, unless a lead stands already.
                if (paragraph(Part.WHAT_MUST_BE_TRUE).length > 0) {
                    throw e;
                }
            }
        }
        return withSection(WHAT_MUST_BE_TRUE_SECTION, content(Part.PLAN, text), firstHeading());
    }

    /**
     * Removes a part with its lead or heading.
     *
     * @param part the part
     * @return the body without it, or as it was when it has none
     * @throws Ambiguous when the body's sections cannot be told apart for certain
     * @throws IllegalArgumentException when its removal would leave the body's sections unclear
     */
    public BodySections without(final Part part) {
        unambiguous();
        final List<Line> out = new ArrayList<>(lines);
        if (part.leading()) {
            final int[] span = paragraph(part);
            if (span.length == 0) {
                return this;
            }
            int start = span[0];
            int end = span[1];
            // The blank line that parted it from the next goes with it; a last one takes the
            // blank line above it, and the line before keeps the ending the body had.
            if (end < out.size() && out.get(end).blank()) {
                end++;
            } else if (end == out.size() && start > 0 && out.get(start - 1).blank()) {
                start--;
            }
            final String lastEnd = out.get(end - 1).end();
            out.subList(start, end).clear();
            if (end == lines.size() && start > 0) {
                out.set(start - 1, new Line(out.get(start - 1).text(), lastEnd));
            }
        } else {
            return withoutSection(part.label());
        }
        return checked(out);
    }

    /** Removes a second-level section with its heading, or answers the body when it has none. */
    private BodySections withoutSection(final String label) {
        final List<Line> out = new ArrayList<>(lines);
        final int heading = heading(label);
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
        return checked(out);
    }

    /**
     * Writes a part an edit names: what must be true in the form its text needs, the lead's
     * paragraph for one paragraph the lead holds, else its section above every other section, the
     * other form taken out; any other part as {@link #with(Part, String)} writes it.
     *
     * @param part the part
     * @param text its new text
     * @return the body with the part written
     * @throws Ambiguous when the body's sections cannot be told apart for certain
     * @throws IllegalArgumentException when neither form can hold the text, or the body would grow
     *     longer than GitHub takes
     */
    public BodySections withEdited(final Part part, final String text) {
        if (part != Part.WHAT_MUST_BE_TRUE) {
            return with(part, text);
        }
        unambiguous();
        try {
            return withoutSection(WHAT_MUST_BE_TRUE_SECTION).with(part, text);
        } catch (final Ambiguous e) {
            throw e;
        } catch (final IllegalArgumentException e) {
            // A text the lead cannot hold goes into the section.
        }
        final BodySections body = without(part);
        return body.withSection(
                WHAT_MUST_BE_TRUE_SECTION, content(Part.PLAN, text), body.firstHeading());
    }

    /**
     * Tells whether the body splits into sections unambiguously, as a write needs: every code block
     * closes; none runs over a heading-shaped line and a fence of its own kind at least as long
     * (the sign of a fence left open by hand that pairs with a later block's); none opened under a
     * section of the skeleton holds a line shaped as the heading of one; none opened above the
     * first section holds a heading-shaped line or a lead; every HTML comment, pre and details
     * block closes and holds no heading-shaped line; every line Markdown reads as a heading of the
     * first or second level is written as two hashes, a space and a name; no section of the
     * skeleton stands twice; and no leading paragraph stands twice. A read takes the body either
     * way.
     *
     * @return the first thing a person must repair, in fixed words and line numbers, or empty when
     *     the body can be written
     */
    public Optional<String> ambiguity() {
        final Optional<String> fences = fences();
        if (fences.isPresent()) {
            return fences;
        }
        for (final Html kind : HTML) {
            final Optional<String> html = html(kind);
            if (html.isPresent()) {
                return html;
            }
        }
        for (int at = 0; at < lines.size(); at++) {
            final Shape shape = code[at] ? null : shape(lines.get(at).text());
            if (shape != null && shape.level() <= 2 && !written(lines.get(at).text(), shape)) {
                return Optional.of(
                        "the heading in line "
                                + (at + 1)
                                + " is not written as two hashes, a space and a name, so whether"
                                + " it opens a section is unclear");
            }
        }
        final java.util.Map<String, Integer> headings = new java.util.HashMap<>();
        for (int at = 0; at < lines.size(); at++) {
            final String name = code[at] ? null : headingName(lines.get(at).text());
            // A person's own headings may repeat: the planner never writes under them.
            if (name == null || !known(name)) {
                continue;
            }
            final Integer first = headings.putIfAbsent(name, at);
            if (first != null) {
                return Optional.of(
                        "the section "
                                + name
                                + " in line "
                                + (at + 1)
                                + " repeats the one in line "
                                + (first + 1));
            }
        }
        final java.util.Set<Part> leads = java.util.EnumSet.noneOf(Part.class);
        for (final int[] span : paragraphs()) {
            final Part lead = lead(lines.get(span[0]).text());
            if (lead != null && !leads.add(lead)) {
                return Optional.of(
                        "the paragraph "
                                + lead.named()
                                + " in line "
                                + (span[0] + 1)
                                + " stands twice");
            }
        }
        final int[] lead = paragraph(Part.WHAT_MUST_BE_TRUE);
        final int section = heading(WHAT_MUST_BE_TRUE_SECTION);
        if (lead.length > 0 && section >= 0) {
            return Optional.of(
                    "What must be true stands as the paragraph in line "
                            + (lead[0] + 1)
                            + " and as the section in line "
                            + (section + 1));
        }
        return Optional.empty();
    }

    /** The first fault of the body's code blocks, or empty. */
    private Optional<String> fences() {
        final Fence fence = new Fence();
        int opened = -1;
        boolean heading = false;
        boolean section = false;
        boolean inner = false;
        boolean leads = false;
        for (int at = 0; at < lines.size(); at++) {
            final String text = lines.get(at).text();
            if (opened < 0) {
                if (fence.inside(text)) {
                    opened = at;
                    heading = false;
                    section = false;
                    inner = false;
                    leads = false;
                }
                continue;
            }
            final boolean sign = fence.ofItsKind(text);
            fence.inside(text);
            if (!fence.open()) {
                final String under = sectionAbove(opened);
                if (under == null && (heading || leads)) {
                    // Above the first section the leading paragraphs are the skeleton's too.
                    return Optional.of(
                            "the code block opened in "
                                    + place(opened)
                                    + " above the first section holds a heading-shaped line or a"
                                    + " lead, so where it closes is unclear");
                }
                if (section && under != null && known(under)) {
                    return Optional.of(
                            "the code block opened in "
                                    + place(opened)
                                    + " holds a line shaped as the heading of a section, so where"
                                    + " it closes is unclear");
                }
                if (heading && inner) {
                    return Optional.of(
                            "the code block opened in "
                                    + place(opened)
                                    + " runs over a heading and a fence of its own, so where it"
                                    + " closes is unclear");
                }
                opened = -1;
            } else {
                final Shape shape = shape(text);
                heading |= shape != null && shape.level() <= 2;
                section |= shape != null && shape.level() <= 2 && known(shape.name());
                inner |= sign;
                leads |= lead(text) != null;
            }
        }
        if (opened >= 0) {
            return Optional.of(
                    "the code block opened in " + place(stray(opened)) + " never closes");
        }
        return Optional.empty();
    }

    /**
     * The fence a person most likely left open: the first opening fence without which every block
     * closes and none runs over a second-level heading, else the one that never closes.
     */
    private int stray(final int unclosed) {
        final Fence fence = new Fence();
        for (int at = 0; at < lines.size(); at++) {
            final boolean was = fence.open();
            fence.inside(lines.get(at).text());
            if (!was && fence.open() && sound(at)) {
                return at;
            }
        }
        return unclosed;
    }

    /** Whether every code block closes and none holds a second-level heading, one line left out. */
    private boolean sound(final int skip) {
        final Fence fence = new Fence();
        boolean open = false;
        for (int at = 0; at < lines.size(); at++) {
            if (at == skip) {
                continue;
            }
            final String text = lines.get(at).text();
            fence.inside(text);
            if (open && fence.open()) {
                final Shape shape = shape(text);
                if (shape != null && shape.level() == 2) {
                    return false;
                }
            }
            open = fence.open();
        }
        return !open;
    }

    /** The first fault of one kind of HTML block, or empty. */
    private Optional<String> html(final Html kind) {
        int depth = 0;
        int opened = -1;
        for (int at = 0; at < lines.size(); at++) {
            if (code[at]) {
                continue;
            }
            final String text = lines.get(at).text();
            final Shape shape = shape(text);
            if (depth > 0 && shape != null && shape.level() <= 2) {
                return Optional.of(
                        "the "
                                + kind.named()
                                + " opened in "
                                + place(opened)
                                + " holds a heading-shaped line, so where its section ends is"
                                + " unclear");
            }
            int from = 0;
            while (true) {
                // Comments and pre blocks keep backticks literal until they close.
                final String prose =
                        depth == 0 || kind.nests() ? outsideCodeSpans(text, from) : text;
                final java.util.regex.Matcher open = kind.opener().matcher(prose);
                final java.util.regex.Matcher close = kind.closer().matcher(text);
                final boolean opens = (depth == 0 || kind.nests()) && open.find(from);
                final boolean closes = depth > 0 && close.find(from);
                if (opens && (!closes || open.start() < close.start())) {
                    if (depth == 0) {
                        opened = at;
                    }
                    depth++;
                    from = open.end();
                } else if (closes) {
                    depth--;
                    from = close.end();
                } else {
                    break;
                }
            }
        }
        if (depth > 0) {
            return Optional.of(
                    "the " + kind.named() + " opened in " + place(opened) + " never closes");
        }
        return Optional.empty();
    }

    /** Masks matched backtick spans so their HTML examples cannot open a block. */
    private static String outsideCodeSpans(final String text, final int from) {
        final StringBuilder prose = new StringBuilder(text);
        int at = from;
        while (at < text.length()) {
            if (text.charAt(at) == '\\') {
                at += 2;
                continue;
            }
            if (text.charAt(at) != '`') {
                at++;
                continue;
            }
            final int end = backticksEnd(text, at);
            final int length = end - at;
            int close = end;
            boolean matched = false;
            while (close < text.length()) {
                if (text.charAt(close) != '`') {
                    close++;
                    continue;
                }
                final int after = backticksEnd(text, close);
                if (after - close == length) {
                    for (int mask = at; mask < after; mask++) {
                        prose.setCharAt(mask, ' ');
                    }
                    at = after;
                    matched = true;
                    break;
                }
                close = after;
            }
            if (!matched) {
                at = end;
            }
        }
        return prose.toString();
    }

    /** The end of one complete backtick run; only an equally long run closes a code span. */
    private static int backticksEnd(final String text, final int start) {
        int end = start;
        while (end < text.length() && text.charAt(end) == '`') {
            end++;
        }
        return end;
    }

    /**
     * A kind of HTML block Markdown passes through as it stands, so a heading-shaped line in it is
     * no section for GitHub while the planner could read one.
     *
     * @param named how a repair names it
     * @param opener what opens it
     * @param closer what closes it
     * @param nests whether one opens inside another
     */
    private record Html(
            String named,
            java.util.regex.Pattern opener,
            java.util.regex.Pattern closer,
            boolean nests) {}

    private static final List<Html> HTML =
            List.of(
                    new Html(
                            "HTML comment",
                            java.util.regex.Pattern.compile("<!--"),
                            java.util.regex.Pattern.compile("-->"),
                            false),
                    new Html(
                            "pre block",
                            java.util.regex.Pattern.compile("(?i)<pre(?=[\\s>]|$)"),
                            java.util.regex.Pattern.compile("(?i)</pre\\s*>"),
                            false),
                    new Html(
                            "details block",
                            java.util.regex.Pattern.compile("(?i)<details(?=[\\s>]|$)"),
                            java.util.regex.Pattern.compile("(?i)</details\\s*>"),
                            true));

    private void unambiguous() {
        final Optional<String> repair = ambiguity();
        if (repair.isPresent()) {
            throw new Ambiguous(repair.get());
        }
    }

    /** A line by its number, with the section it stands in when that is one of the skeleton's. */
    private String place(final int at) {
        final String name = sectionAbove(at);
        // A person's own heading is their words, so it is not repeated.
        return "line " + (at + 1) + (name != null && known(name) ? " under " + name : "");
    }

    /** The name of the section a line stands in, or null above the first. */
    private @Nullable String sectionAbove(final int at) {
        for (int heading = at - 1; heading >= 0; heading--) {
            final String name = code[heading] ? null : headingName(lines.get(heading).text());
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    /** Whether a heading's name is a section of the skeleton, safe to repeat in a message. */
    private static boolean known(final String name) {
        if (WHAT_MUST_BE_TRUE_SECTION.equals(name)) {
            return true;
        }
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
            throw new TooLong();
        }
    }

    /**
     * A body written from one that could be told apart: refused when it grew past what GitHub
     * takes, or when what was written left a block open across its sections, since every later
     * write would then skip it.
     */
    private static BodySections checked(final List<Line> out) {
        final BodySections body = new BodySections(out);
        bounded(body.text());
        final Optional<String> repair = body.ambiguity();
        if (repair.isPresent()) {
            throw new IllegalArgumentException(
                    "A part of the body would leave its sections unclear: " + repair.get());
        }
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
        for (int at = 0; at < content.size(); at++) {
            final String line = content.get(at);
            // The line as it will stand: a leading part's first one follows its lead.
            final String standing =
                    at == 0 && part.leading() && part != Part.COVERS
                            ? part.label() + " " + line
                            : line;
            final boolean code = fence.inside(standing);
            final Shape shape = code ? null : shape(standing);
            if (shape != null && shape.level() <= 2
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
        for (int later = part.ordinal() + 1; later < Part.PLAN.ordinal(); later++) {
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
        for (final Part part : Part.values()) {
            if (part != Part.COVERS && part.leading() && text.startsWith(part.label())) {
                return part;
            }
        }
        return null;
    }

    /** The index of the first heading of the first or second level, or the line count. */
    private int firstHeading() {
        for (int at = 0; at < lines.size(); at++) {
            if (endsSection(at)) {
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

    /**
     * The index after a section's last line: the next heading of the first or second level outside
     * code blocks, or the end.
     */
    private int sectionEnd(final int heading) {
        for (int at = heading + 1; at < lines.size(); at++) {
            if (endsSection(at)) {
                return at;
            }
        }
        return lines.size();
    }

    /** Whether a line outside code blocks is a heading of the first or second level. */
    private boolean endsSection(final int at) {
        final Shape shape = code[at] ? null : shape(lines.get(at).text());
        return shape != null && shape.level() <= 2;
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

    /** A second-level heading's name as Markdown reads it, or null for any other line. */
    private static @Nullable String headingName(final String text) {
        final Shape shape = shape(text);
        return shape != null && shape.level() == 2 ? shape.name() : null;
    }

    /**
     * A heading as Markdown reads it: its level and its name.
     *
     * @param level the number of hashes
     * @param name the text between the hashes and an optional closing run of hashes
     */
    private record Shape(int level, String name) {}

    /**
     * Reads a line as Markdown reads a heading: indented by three spaces at most, one to six
     * hashes, then a space, a tab or the line's end, and an optional closing run of hashes; null
     * for any other line.
     */
    private static @Nullable Shape shape(final String text) {
        final int indent = Fence.indent(text);
        if (indent > 3) {
            return null;
        }
        int hashes = 0;
        while (indent + hashes < text.length() && text.charAt(indent + hashes) == '#') {
            hashes++;
        }
        final int after = indent + hashes;
        if (hashes == 0
                || hashes > 6
                || after < text.length()
                        && text.charAt(after) != ' '
                        && text.charAt(after) != '\t') {
            return null;
        }
        final String rest = text.substring(after).strip();
        final String name = rest.matches("#*") ? "" : rest.replaceFirst("[ \t]+#+$", "").strip();
        return new Shape(hashes, name);
    }

    /** Whether a heading is written as the planner writes one: two hashes, a space and a name. */
    private static boolean written(final String text, final Shape shape) {
        return shape.level() == 2
                && !shape.name().isEmpty()
                && text.stripTrailing().equals("## " + shape.name());
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
     * indented by three spaces at most (a tab reaches the fourth column), closed by a bare fence of
     * the same kind at least as long; a backtick fence whose words after it hold a backtick is
     * inline code, no fence.
     */
    static final class Fence {

        private char kind;

        private int length;

        /** Whether the line opens, closes or stands inside a code block. */
        boolean inside(final String text) {
            final int indent = indent(text);
            if (indent > 3) {
                return length > 0;
            }
            final String bare = text.substring(indent);
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

        /** Whether a line is a fence of the open block's kind, at least as long as its opener. */
        private boolean ofItsKind(final String text) {
            final int indent = indent(text);
            if (length == 0 || indent > 3) {
                return false;
            }
            final String bare = text.substring(indent);
            return run(bare) >= length && bare.charAt(0) == kind;
        }

        /**
         * A line's indent in columns as Markdown counts it, counting spaces; a tab in it reaches
         * the fourth column, so it counts as four.
         */
        private static int indent(final String text) {
            int spaces = 0;
            while (spaces < text.length() && text.charAt(spaces) == ' ') {
                spaces++;
            }
            return spaces < text.length() && text.charAt(spaces) == '\t' ? 4 : spaces;
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
