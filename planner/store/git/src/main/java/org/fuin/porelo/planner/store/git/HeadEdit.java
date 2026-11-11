package org.fuin.porelo.planner.store.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.fuin.porelo.planner.store.Worker;
import org.jspecify.annotations.Nullable;

/**
 * Edits the state in an issue's or a story's JSON head, or an issue's worker, and nothing else in
 * its file.
 */
final class HeadEdit {

    private static final String FENCE = "```json";

    private static final String DATA = "## Data";

    /** A top-level state member, as the heads are written: two spaces, one member a line. */
    private static final Pattern STATE = Pattern.compile("  \"state\": \"[^\"\\\\]*\",?");

    /** A top-level worker member, as the heads are written. */
    private static final Pattern WORKER = Pattern.compile("  \"worker\": \"[^\"\\\\]*\",?");

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}");

    private HeadEdit() {}

    /**
     * Sets an issue's state in the head its file opens with.
     *
     * @param text the issue's file
     * @param state the new state
     * @return the file with the new state
     * @throws IllegalArgumentException when the file opens with no head holding one state
     */
    static String issueState(final String text, final String state) {
        final String[] lines = text.split("\n", -1);
        return edit(lines, issueFence(lines), plain(state), false);
    }

    /** The line of the fence an issue's head opens with. */
    private static int issueFence(final String[] lines) {
        int at = 0;
        // Only the heading and blank lines may stand before the head the file opens with.
        while (at < lines.length && !lines[at].equals(FENCE)) {
            if (!lines[at].isBlank() && !(at == 0 && lines[at].startsWith("# "))) {
                throw new IllegalArgumentException("the file does not open with its JSON head");
            }
            at++;
        }
        return at;
    }

    /** The line of the fence that closes the head opening at the given line, checked as written. */
    private static int close(final String[] lines, final int fence) {
        if (fence >= lines.length || !lines[fence].equals(FENCE)) {
            throw new IllegalArgumentException("the file has no JSON head where it is read");
        }
        int end = fence + 1;
        while (end < lines.length && !lines[end].equals("```")) {
            end++;
        }
        if (end >= lines.length || !lines[fence + 1].equals("{") || !lines[end - 1].equals("}")) {
            throw new IllegalArgumentException("the JSON head is not closed as it is written");
        }
        return end;
    }

    /**
     * Sets who works an issue in the head its file opens with: replaced where the head names one,
     * else written as its last member.
     *
     * @param text the issue's file
     * @param worker who works it
     * @return the file with the worker
     * @throws IllegalArgumentException when the file opens with no head, or one naming more than
     *     one worker
     */
    static String issueWorker(final String text, final Worker worker) {
        final String[] lines = text.split("\n", -1);
        final int fence = issueFence(lines);
        final int end = close(lines, fence);
        final String value = "  \"worker\": \"" + spelled(worker) + "\"";
        final List<Integer> found = new ArrayList<>();
        for (int line = fence + 2; line < end - 1; line++) {
            if (WORKER.matcher(lines[line]).matches()) {
                found.add(line);
            }
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException(
                    "the JSON head holds " + found.size() + " worker members, not one");
        }
        final List<String> out = new ArrayList<>(List.of(lines));
        if (found.size() == 1) {
            final int line = found.get(0);
            out.set(line, value + (lines[line].endsWith(",") ? "," : ""));
            return String.join("\n", out);
        }
        final int last = end - 2;
        if (last <= fence + 1 || lines[last].endsWith(",")) {
            throw new IllegalArgumentException("the JSON head is not written one member a line");
        }
        out.set(last, lines[last] + ",");
        out.add(last + 1, value);
        return String.join("\n", out);
    }

    /** A worker as a head spells it. */
    private static String spelled(final Worker worker) {
        return switch (worker) {
            case AGENT -> "Agent";
            case MANUAL -> "Manual";
        };
    }

    /**
     * Sets, or removes, a story's stated state in the head under its data heading.
     *
     * @param text the story's file
     * @param state the new state, or null to remove it
     * @return the file with the new state
     * @throws IllegalArgumentException when the file has no such head
     */
    static String storyState(final String text, final @Nullable String state) {
        final String[] lines = text.split("\n", -1);
        // The head is the last data section, as the stories index reads it.
        int at = List.of(lines).lastIndexOf(DATA);
        if (at < 0) {
            throw new IllegalArgumentException("the file has no " + DATA + " section");
        }
        at++;
        while (at < lines.length && lines[at].isBlank()) {
            at++;
        }
        return edit(lines, at, state == null ? null : plain(state), true);
    }

    /** Edits the one state line of the head whose fence opens at the given line. */
    private static String edit(
            final String[] lines,
            final int fence,
            final @Nullable String state,
            final boolean story) {
        final int end = close(lines, fence);
        final List<Integer> found = new ArrayList<>();
        int issues = -1;
        for (int line = fence + 2; line < end - 1; line++) {
            if (STATE.matcher(lines[line]).matches()) {
                found.add(line);
            }
            if (lines[line].startsWith("  \"issues\":")) {
                issues = line;
            }
        }
        final List<String> out = new ArrayList<>(List.of(lines));
        if (found.size() > 1 || found.isEmpty() && !story) {
            throw new IllegalArgumentException(
                    "the JSON head holds " + found.size() + " state members, not one");
        }
        if (found.size() == 1) {
            final int line = found.get(0);
            if (state != null) {
                out.set(
                        line,
                        "  \"state\": \"" + state + "\"" + (lines[line].endsWith(",") ? "," : ""));
            } else if (lines[line].endsWith(",")) {
                out.remove(line);
            } else {
                // The last member goes, so the one before it loses its comma.
                out.remove(line);
                final String before = out.get(line - 1);
                if (!before.endsWith(",")) {
                    throw new IllegalArgumentException(
                            "the JSON head is not written one member a line");
                }
                out.set(line - 1, before.substring(0, before.length() - 1));
            }
        } else if (state != null) {
            if (issues < 0) {
                throw new IllegalArgumentException("the story's JSON head names no issues");
            }
            out.add(issues, "  \"state\": \"" + state + "\",");
        }
        return String.join("\n", out);
    }

    /** A state as a workflow names it: a word or a few, never a quote, a backslash or a break. */
    private static String plain(final String state) {
        if (!PLAIN.matcher(state).matches()) {
            throw new IllegalArgumentException("the state is no plain name");
        }
        return state;
    }
}
