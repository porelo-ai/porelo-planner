package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Judges how a task the window started ended: a building task's success, a reader's findings, or an
 * end the operator must look at. An agent writes the answer, so nothing in it decides more than its
 * findings, and an answer that does not hold exactly one well-formed block counts no reading.
 */
public final class WorkEnds {

    /** The most findings one reading may give. */
    public static final int MOST_FINDINGS = 100;

    /** The most Unicode code points of one finding's text. */
    public static final int MOST_TEXT = 4000;

    /** The most characters of a finding's file label. */
    public static final int MOST_FILE = 512;

    /** The most bytes of an answer, in UTF-8. */
    public static final int MOST_ANSWER = 64 * 1024;

    private static final String BLOCK = "Findings:";

    private static final Pattern NUMBER = Pattern.compile("([1-9][0-9]{0,2})\\. (.*)");

    private static final Pattern LINE = Pattern.compile("[1-9][0-9]{0,9}");

    private static final Pattern RANGE = Pattern.compile("([1-9][0-9]{0,9})-([1-9][0-9]{0,9})");

    private WorkEnds() {}

    /** The outcome a task declared at its end, copied unchanged from Porelo's record. */
    public enum Declared {
        /** The task says its work is done. */
        DONE,
        /** The task says its work cannot go on; its why says what blocks it. */
        BLOCKED,
        /** The task says its work failed. */
        FAILED
    }

    /** How a task ended, read from Porelo's record of the agent's end. */
    public enum Outcome {
        /** The agent finished and left nothing unpushed. */
        DONE,
        /** The agent finished but left commits unpushed, so its work did not arrive. */
        UNPUSHED,
        /** The run did not finish. */
        FAILED,
        /** The task declared its work blocked. */
        BLOCKED;

        /**
         * Reads the outcome from Porelo's record, which says no more than this.
         *
         * @param finished whether the agent's run finished
         * @param unpushed how many commits it left unpushed
         * @return the outcome
         */
        public static Outcome of(final boolean finished, final int unpushed) {
            return of(finished, unpushed, null);
        }

        /**
         * Reads the outcome from Porelo's record and the outcome the task declared; a declared DONE
         * decides nothing the record does not already say.
         *
         * @param finished whether the agent's run finished
         * @param unpushed how many commits it left unpushed
         * @param declared the outcome the task declared, or null for none
         * @return the outcome
         */
        public static Outcome of(
                final boolean finished, final int unpushed, final @Nullable Declared declared) {
            if (declared == Declared.BLOCKED) {
                return BLOCKED;
            }
            if (!finished || declared == Declared.FAILED) {
                return FAILED;
            }
            return unpushed == 0 ? DONE : UNPUSHED;
        }
    }

    /** How grave a finding is. */
    public enum Severity {
        /** It must be fixed before the issue goes on. */
        BLOCKING,
        /** It goes with the issue for the person to read. */
        NOTE
    }

    /** Why an end needs the operator. */
    public enum Reason {
        /** The agent finished with work left unpushed. */
        UNPUSHED,
        /** The run did not finish. */
        FAILED,
        /** The task declared its work blocked. */
        BLOCKED,
        /** The window stopped the task. */
        STOPPED,
        /** A reader's answer held no single well-formed findings block. */
        INVALID_FINDINGS
    }

    /**
     * One finding of a reading.
     *
     * @param number its number, counted from one
     * @param severity how grave it is
     * @param file the file it concerns, shown only, or null
     * @param line the line in that file, or null
     * @param text what was found
     */
    public record Finding(
            int number,
            Severity severity,
            @Nullable String file,
            @Nullable Integer line,
            String text) {}

    /** What one end of a task comes to. */
    public sealed interface Judgment permits Done, Read, NeedsOperator {}

    /** A building or reworking task ended with success. */
    public record Done() implements Judgment {}

    /**
     * A reading ended with its findings, none for a clean reading.
     *
     * @param findings the findings in their order
     */
    public record Read(List<Finding> findings) implements Judgment {

        /**
         * Keeps the findings.
         *
         * @param findings the findings in their order
         */
        public Read {
            findings = List.copyOf(findings);
        }

        /**
         * Answers whether any finding blocks the issue.
         *
         * @return true when one does
         */
        public boolean blocking() {
            return findings.stream().anyMatch(f -> f.severity() == Severity.BLOCKING);
        }
    }

    /**
     * An end the operator must look at; nothing goes on and no reading counts.
     *
     * @param reason why
     */
    public record NeedsOperator(Reason reason) implements Judgment {}

    /**
     * Judges a task's end once.
     *
     * @param role the task's role, READER for a reading
     * @param outcome how it ended
     * @param answer the agent's last message
     * @return what the end comes to
     */
    public static Judgment judge(final String role, final Outcome outcome, final String answer) {
        Objects.requireNonNull(answer, "answer");
        return switch (outcome) {
            case UNPUSHED -> new NeedsOperator(Reason.UNPUSHED);
            case FAILED -> new NeedsOperator(Reason.FAILED);
            case BLOCKED -> new NeedsOperator(Reason.BLOCKED);
            case DONE -> {
                if (!"READER".equals(role)) {
                    yield new Done();
                }
                final List<Finding> findings = findings(answer);
                yield findings == null
                        ? new NeedsOperator(Reason.INVALID_FINDINGS)
                        : new Read(findings);
            }
        };
    }

    /**
     * Reads the one findings block at the end of a reader's answer.
     *
     * @param answer the reader's last message
     * @return the findings, empty for none, or null when the answer holds no single well-formed
     *     block
     */
    static @Nullable List<Finding> findings(final String answer) {
        if (answer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MOST_ANSWER) {
            return null;
        }
        final List<String> lines = List.of(answer.replace("\r\n", "\n").split("\n", -1));
        int block = -1;
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i).strip();
            if (line.equals(BLOCK) || line.startsWith(BLOCK + " ")) {
                if (block >= 0) {
                    // A second block could hide the reader's own behind one written by another.
                    return null;
                }
                block = i;
            }
        }
        if (block < 0) {
            return null;
        }
        final String after = lines.get(block).strip().substring(BLOCK.length()).strip();
        final List<String> rows = new ArrayList<>(lines.subList(block + 1, lines.size()));
        while (!rows.isEmpty() && rows.get(rows.size() - 1).isBlank()) {
            rows.remove(rows.size() - 1);
        }
        // A reader may say "none" for its empty block, but never beside a finding.
        if (!after.isEmpty()) {
            return none(after) && rows.isEmpty() ? List.of() : null;
        }
        if (rows.size() == 1 && none(rows.get(0).strip())) {
            return List.of();
        }
        if (rows.size() > MOST_FINDINGS) {
            return null;
        }
        final List<Finding> findings = new ArrayList<>();
        for (final String row : rows) {
            final Finding finding = finding(row, findings.size() + 1);
            if (finding == null) {
                return null;
            }
            findings.add(finding);
        }
        return findings;
    }

    private static boolean none(final String words) {
        return words.equalsIgnoreCase("none") || words.equalsIgnoreCase("(none)");
    }

    private static @Nullable Finding finding(final String row, final int expected) {
        final Matcher numbered = NUMBER.matcher(row);
        if (!numbered.matches() || Integer.parseInt(numbered.group(1)) != expected) {
            return null;
        }
        // Only the first three separators split, so a pipe in the text stays text.
        final String[] parts = numbered.group(2).split(" \\| ", 4);
        if (parts.length != 4) {
            return null;
        }
        final Severity severity =
                switch (parts[0]) {
                    case "blocking" -> Severity.BLOCKING;
                    case "note" -> Severity.NOTE;
                    default -> null;
                };
        final String file = parts[1].equals("-") ? null : parts[1];
        final String text = parts[3].strip();
        if (severity == null
                || parts[1].isBlank()
                || file != null && (file.length() > MOST_FILE || !printable(file))
                || text.isEmpty()
                || text.codePointCount(0, text.length()) > MOST_TEXT) {
            return null;
        }
        if (parts[2].equals("-")) {
            return new Finding(expected, severity, file, null, text);
        }
        if (file == null) {
            return null;
        }
        final Matcher range = RANGE.matcher(parts[2]);
        if (range.matches()) {
            final Integer first = line(range.group(1));
            final Integer last = line(range.group(2));
            if (first == null || last == null || first > last) {
                return null;
            }
            // The line field holds one line, so the text keeps the range for the person.
            return new Finding(expected, severity, file, first, "lines " + parts[2] + ": " + text);
        }
        final Integer line = line(parts[2]);
        return line == null ? null : new Finding(expected, severity, file, line, text);
    }

    /** A line number from one to the largest int, or null. */
    private static @Nullable Integer line(final String column) {
        return LINE.matcher(column).matches() && Long.parseLong(column) <= Integer.MAX_VALUE
                ? Integer.valueOf(column)
                : null;
    }

    /** A label shown as a file: no control character, no root, no step up. */
    private static boolean printable(final String text) {
        return text.codePoints().noneMatch(Character::isISOControl)
                && !text.startsWith("/")
                && !text.startsWith("\\")
                && java.util.Arrays.stream(text.split("[/\\\\]")).noneMatch(".."::equals);
    }
}
