package org.fuin.porelo.planner.store.git;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.git.stories.IssueHead;
import org.fuin.porelo.planner.store.git.stories.IssueLinks;
import org.fuin.porelo.planner.store.git.stories.Workflow;

/**
 * Writes a stage's result into an issue's file: the head's state and size, the size line, an
 * estimate, the plan, what is still to be checked and the findings; every other line stays as it
 * was, its line endings too.
 */
public final class ResultText {

    private static final String SIZE = "**Size:**";

    private static final String ESTIMATE = "**Estimate:**";

    private static final String MUST = "**What must be true.**";

    private static final String PLAN = "## Plan";

    private static final String ACCEPTANCE = "## Acceptance";

    private static final String QUESTIONS = "## Open questions";

    private static final String CHECKED = "## To be checked";

    private static final String FINDINGS = "## Findings";

    /** A second-level heading and the lines below it up to the next one. */
    private static final class Section {
        private final String heading;
        private List<String> body;
        private final boolean added;

        private Section(final String heading, final List<String> body, final boolean added) {
            this.heading = heading;
            this.body = body;
            this.added = added;
        }

        private boolean is(final String name) {
            return heading.strip().equals(name);
        }
    }

    private ResultText() {}

    /**
     * Writes the result into the issue's file.
     *
     * @param text the issue's file
     * @param result the issue's result
     * @param workflow the project's workflow, which the written head is checked against
     * @return the file with the result
     * @throws IllegalArgumentException when the file mixes line endings, opens with no head the
     *     result can be written into, or its head would fail the work format's check
     */
    static String apply(
            final String text, final StageResult.ForIssue result, final Workflow workflow) {
        final boolean crlf = text.contains("\r\n");
        final String lf = crlf ? text.replace("\r\n", "\n") : text;
        if (lf.indexOf('\r') >= 0 || crlf && text.replace("\r\n", "").indexOf('\n') >= 0) {
            throw new IllegalArgumentException("the file mixes line endings");
        }
        final String edited =
                HeadEdit.issueState(
                        HeadEdit.issueSize(body(lf, result), result.size()), result.to());
        final IssueHead head = IssueHead.read(edited, workflow);
        if (head == null
                || !head.state().equals(result.to())
                || !head.size().equals(result.size())) {
            throw new IllegalArgumentException("its head does not read back with the result");
        }
        return crlf ? edited.replace("\n", "\r\n") : edited;
    }

    /**
     * Writes the result into the issue's file, every bare number of the repository's index in the
     * result's text written as the link to its row.
     *
     * @param text the issue's file
     * @param result the issue's result
     * @param workflow the project's workflow, which the written head is checked against
     * @param numbers the issue numbers of the repository's index
     * @return the file with the result
     * @throws IllegalArgumentException as {@link #apply(String, StageResult.ForIssue, Workflow)}
     */
    static String apply(
            final String text,
            final StageResult.ForIssue result,
            final Workflow workflow,
            final java.util.Set<String> numbers) {
        return apply(text, linked(result, numbers), workflow);
    }

    /**
     * The result with its plan, why it has its size, what is to be checked and its findings linking
     * their numbers, each read in the line it is written as, so the linker sees what the store's
     * check sees.
     */
    private static StageResult.ForIssue linked(
            final StageResult.ForIssue result, final java.util.Set<String> numbers) {
        final String plan = result.plan();
        return new StageResult.ForIssue(
                result.id(),
                result.from(),
                result.to(),
                plan == null ? null : IssueLinks.linked(plan.strip(), numbers),
                result.size(),
                linkedAs(SIZE + " " + result.size() + " - ", result.sizeWhy(), numbers),
                result.estimateMinutes(),
                result.toBeChecked().stream()
                        .map(item -> linkedAs("- ", item.strip(), numbers))
                        .toList(),
                result.findings().stream()
                        .map(item -> linkedAs("- ", item.strip(), numbers))
                        .toList());
    }

    /** A text linked as the line it is written in after a prefix, which names no number. */
    private static String linkedAs(
            final String prefix, final String text, final java.util.Set<String> numbers) {
        return IssueLinks.linked(prefix + text, numbers).substring(prefix.length());
    }

    /**
     * Writes the result into an issue's text as a board holds it, below where its head stood: the
     * size line, an estimate, the plan, what is still to be checked and the findings, each
     * replacing what stood there, as the Git store writes them into the file.
     *
     * @param text the issue's text without its head, with line feeds
     * @param result the issue's result
     * @return the text with the result
     */
    public static String withoutHead(final String text, final StageResult.ForIssue result) {
        return below(text, 0, result);
    }

    /** The file with the result's text below its head; the head itself is left as it was. */
    private static String body(final String text, final StageResult.ForIssue result) {
        final int head = IssueHead.lines(text);
        if (head == 0) {
            throw new IllegalArgumentException("the file does not open with its JSON head");
        }
        return below(text, head, result);
    }

    /** The text with the result's parts below its first lines, which stay as they were. */
    private static String below(
            final String text, final int head, final StageResult.ForIssue result) {
        final List<String> lines = List.of(text.split("\n", -1));
        final List<String> preamble = new ArrayList<>();
        final List<Section> sections = new ArrayList<>();
        split(lines.subList(head, lines.size()), preamble, sections);
        sizeLines(preamble, result);
        final String plan = result.plan();
        if (plan != null) {
            put(sections, PLAN, block(List.of(plan.strip().split("\n", -1))), ACCEPTANCE);
        }
        if (result.toBeChecked().isEmpty()) {
            sections.removeIf(section -> section.is(CHECKED));
        } else {
            putAfter(sections, CHECKED, bullets(result.toBeChecked()), PLAN, ACCEPTANCE, QUESTIONS);
        }
        if (!result.findings().isEmpty()) {
            putAfter(
                    sections,
                    FINDINGS,
                    bullets(result.findings()),
                    PLAN,
                    ACCEPTANCE,
                    QUESTIONS,
                    CHECKED);
        }
        return joined(lines.subList(0, head), preamble, sections);
    }

    /** The lines below the head split into those before the first section and the sections. */
    private static void split(
            final List<String> lines, final List<String> preamble, final List<Section> sections) {
        boolean fenced = false;
        for (final String line : lines) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            }
            if (!fenced && line.startsWith("## ")) {
                sections.add(new Section(line, new ArrayList<>(), false));
            } else if (sections.isEmpty()) {
                preamble.add(line);
            } else {
                sections.getLast().body.add(line);
            }
        }
    }

    /** The head, the lines before the first section and the sections joined again. */
    private static String joined(
            final List<String> head, final List<String> preamble, final List<Section> sections) {
        final List<String> out = new ArrayList<>(head);
        out.addAll(preamble);
        for (final Section section : sections) {
            // An added section needs a blank line above it to read as a heading.
            if (section.added && !out.getLast().isEmpty()) {
                out.add("");
            }
            out.add(section.heading);
            out.addAll(section.body);
        }
        return String.join("\n", out);
    }

    /**
     * Writes a readiness check's verdict into the issue's file: its new state in the head and, for
     * an issue not ready, why as one finding added below those that stand, never twice, every bare
     * number of the repository's index written as the link to its row.
     *
     * @param text the issue's file
     * @param verdict the issue's verdict
     * @param workflow the project's workflow, which the written head is checked against
     * @param numbers the issue numbers of the repository's index
     * @return the file with the verdict
     * @throws IllegalArgumentException when the file mixes line endings, opens with no head the
     *     state can be written into, or its head would fail the work format's check
     */
    static String verdict(
            final String text,
            final StageResult.Verdict verdict,
            final Workflow workflow,
            final java.util.Set<String> numbers) {
        final boolean crlf = text.contains("\r\n");
        final String lf = crlf ? text.replace("\r\n", "\n") : text;
        if (lf.indexOf('\r') >= 0 || crlf && text.replace("\r\n", "").indexOf('\n') >= 0) {
            throw new IllegalArgumentException("the file mixes line endings");
        }
        String edited = HeadEdit.issueState(lf, verdict.to());
        final String reason = verdict.reason();
        if (reason != null) {
            final int head = IssueHead.lines(edited);
            if (head == 0) {
                throw new IllegalArgumentException("the file does not open with its JSON head");
            }
            final List<String> lines = List.of(edited.split("\n", -1));
            final List<String> preamble = new ArrayList<>();
            final List<Section> sections = new ArrayList<>();
            split(lines.subList(head, lines.size()), preamble, sections);
            final String bullet = "- " + linkedAs("- ", reason.strip(), numbers);
            final int at = index(sections, FINDINGS);
            if (at < 0) {
                putAfter(
                        sections,
                        FINDINGS,
                        block(List.of(bullet)),
                        PLAN,
                        ACCEPTANCE,
                        QUESTIONS,
                        CHECKED);
            } else if (sections.get(at).body.stream().noneMatch(line -> line.equals(bullet))) {
                final List<String> body = sections.get(at).body;
                int end = body.size();
                while (end > 0 && body.get(end - 1).isBlank()) {
                    end--;
                }
                if (end == 0) {
                    // An empty heading: the finding goes in as a fresh section's first, with the
                    // blank line below the heading.
                    body.clear();
                    body.addAll(block(List.of(bullet)));
                } else {
                    body.add(end, bullet);
                }
            }
            edited = joined(lines.subList(0, head), preamble, sections);
        }
        final IssueHead read = IssueHead.read(edited, workflow);
        if (read == null || !read.state().equals(verdict.to())) {
            throw new IllegalArgumentException("its head does not read back with the verdict");
        }
        return crlf ? edited.replace("\n", "\r\n") : edited;
    }

    /** Writes the size line, and the estimate's right after it when the result gives one. */
    private static void sizeLines(final List<String> preamble, final StageResult.ForIssue result) {
        final String size = SIZE + " " + result.size() + " - " + result.sizeWhy();
        int at = find(preamble, SIZE);
        if (at >= 0) {
            replaceParagraph(preamble, at, size);
        } else {
            final int must = find(preamble, MUST);
            at = must < 0 ? 0 : paragraphEnd(preamble, must);
            preamble.addAll(at, List.of("", size));
            at++;
        }
        final Integer minutes = result.estimateMinutes();
        if (minutes == null) {
            return;
        }
        final String estimate = ESTIMATE + " " + minutes + " min";
        final int found = find(preamble, ESTIMATE);
        if (found >= 0) {
            replaceParagraph(preamble, found, estimate);
        } else {
            preamble.addAll(at + 1, List.of("", estimate));
        }
    }

    private static int find(final List<String> lines, final String start) {
        for (int at = 0; at < lines.size(); at++) {
            if (lines.get(at).startsWith(start)) {
                return at;
            }
        }
        return -1;
    }

    /** Replaces the paragraph opening at a line, up to a blank line or an estimate, by one line. */
    private static void replaceParagraph(
            final List<String> lines, final int at, final String line) {
        final int end = paragraphEnd(lines, at);
        lines.subList(at, end).clear();
        lines.add(at, line);
    }

    private static int paragraphEnd(final List<String> lines, final int at) {
        int end = at + 1;
        while (end < lines.size()
                && !lines.get(end).isBlank()
                && !lines.get(end).startsWith(ESTIMATE)) {
            end++;
        }
        return end;
    }

    /** Replaces a section's body, or adds the section before another, or at the end. */
    private static void put(
            final List<Section> sections,
            final String name,
            final List<String> body,
            final String before) {
        if (replaced(sections, name, body)) {
            return;
        }
        final int at = index(sections, before);
        sections.add(at < 0 ? sections.size() : at, new Section(name, body, true));
    }

    /**
     * Replaces a section's body, or adds the section right after the last of the ones the skeleton
     * puts before it, or at the end.
     */
    private static void putAfter(
            final List<Section> sections,
            final String name,
            final List<String> body,
            final String... after) {
        if (replaced(sections, name, body)) {
            return;
        }
        int at = -1;
        for (final String earlier : after) {
            at = Math.max(at, index(sections, earlier));
        }
        sections.add(at < 0 ? sections.size() : at + 1, new Section(name, body, true));
    }

    private static boolean replaced(
            final List<Section> sections, final String name, final List<String> body) {
        final int at = index(sections, name);
        if (at < 0) {
            return false;
        }
        sections.get(at).body = body;
        return true;
    }

    private static int index(final List<Section> sections, final String name) {
        for (int at = 0; at < sections.size(); at++) {
            if (sections.get(at).is(name)) {
                return at;
            }
        }
        return -1;
    }

    private static List<String> bullets(final List<String> items) {
        return block(items.stream().map(item -> "- " + item.strip()).toList());
    }

    /** A section's body: a blank line, the lines, and the line ending the last one. */
    private static List<String> block(final List<String> lines) {
        final List<String> body = new ArrayList<>();
        body.add("");
        body.addAll(lines);
        body.add("");
        return body;
    }
}
