package org.fuin.porelo.planner.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.WorkRule;

/**
 * What a task of a stage that hands back its result is given and told besides its order: each
 * issue's text as the store holds it, the file to write, and that it pushes nothing. Every path
 * that builds a task passes through here, so none forgets them.
 */
public final class StageOrder {

    /** The most files one task is given. */
    public static final int MOST_FILES = 16;

    /** The most UTF-8 bytes of one given issue's text. */
    public static final int MOST_TEXT = 65536;

    /** The most UTF-8 bytes of all files one task is given. */
    public static final int MOST_ALL = 1 << 20;

    /** The name of the result format agreed with Porelo and the window. */
    public static final String FORMAT = "porelo-stage-result-1";

    private StageOrder() {}

    /**
     * Answers the plain name of the file an issue's text is given in: its id with {@code .md}, a
     * tracker reference's {@code #} written as {@code -}, so no file name Porelo mounts holds one.
     *
     * @param issue the issue's id
     * @return the file's name
     */
    public static String given(final String issue) {
        return issue.replace('#', '-') + ".md";
    }

    /**
     * Answers the task as its stage starts it: unchanged for work that pushes and for a reader,
     * else with the rule's result file, each owned issue's text as a given file and the stage's
     * instructions closing its prompt.
     *
     * @param board the board the order was read at
     * @param rule the rule of the working state the task works in
     * @param to the working state
     * @param task the task as its order built it
     * @return the task the window is handed
     * @throws WorkRefusal if an issue's text or all of them exceed what a task is given
     */
    public static WorkOrder.Task staged(
            final Board board, final WorkRule rule, final String to, final WorkOrder.Task task) {
        return staged(board, rule, to, task, task.issues());
    }

    /**
     * Answers the task as its stage starts it, given the texts of the issues it owns alone.
     *
     * @param board the board the order was read at
     * @param rule the rule of the working state the task works in
     * @param to the working state
     * @param task the task as its order built it
     * @param owned the task's issues it owns, whose texts it is given
     * @return the task the window is handed
     * @throws WorkRefusal if an issue's text or all of them exceed what a task is given
     */
    public static WorkOrder.Task staged(
            final Board board,
            final WorkRule rule,
            final String to,
            final WorkOrder.Task task,
            final List<String> owned) {
        final WorkRule.Result result = rule.result();
        if (result == null || task.role().equals("READER")) {
            return task;
        }
        final List<WorkOrder.Given> given = new ArrayList<>();
        int all = 0;
        for (final String id : owned) {
            final Issue issue = board.issue(id).orElseThrow();
            final int bytes = issue.text().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MOST_TEXT) {
                throw WorkRefusal.notAllowed(
                        issue.state(),
                        to,
                        id
                                + "'s text exceeds "
                                + MOST_TEXT
                                + " UTF-8 bytes, more than a task is given.");
            }
            all += bytes;
            given.add(new WorkOrder.Given(given(id), issue.text()));
        }
        if (given.size() > MOST_FILES || all > MOST_ALL) {
            throw WorkRefusal.notAllowed(
                    "",
                    to,
                    "The issues' texts exceed "
                            + MOST_FILES
                            + " files or "
                            + MOST_ALL
                            + " UTF-8 bytes, more than one task is given.");
        }
        final String prompt = task.prompt() + instructions(result, given);
        if (prompt.getBytes(StandardCharsets.UTF_8).length > WorkEnds.MOST_ANSWER) {
            throw WorkRefusal.notAllowed("", to, "A task prompt exceeds 65536 UTF-8 bytes.");
        }
        return new WorkOrder.Task(
                task.repository(),
                task.issues(),
                task.role(),
                task.agent(),
                task.model(),
                task.effort(),
                task.mode(),
                prompt,
                task.reads(),
                task.basis(),
                task.used(),
                result.file(),
                given);
    }

    /** Closes the prompt, after every quoted text, so no issue's text can reword it. */
    private static String instructions(
            final WorkRule.Result result, final List<WorkOrder.Given> given) {
        final String fields =
                result == WorkRule.Result.ANALYSIS
                        ? "plan (Markdown, the base design read in today's code), size, sizeWhy,"
                                + " toBeChecked and findings"
                        : "size, sizeWhy, estimateMinutes (whole minutes from 1 to "
                                + StageResult.MOST_MINUTES
                                + "), and optionally plan, toBeChecked and findings";
        final String bounds =
                ". The size is one of "
                        + String.join(", ", StageResult.SIZES)
                        + ". sizeWhy is one line of at most "
                        + HandedResults.MOST_LINE
                        + " characters. The plan has at most "
                        + HandedResults.MOST_PLAN
                        + " characters; it stands under the issue's own Plan section, so it holds"
                        + " no first- or second-level heading and starts at ###, and each code"
                        + " block opens with three backticks and a plain language name and closes"
                        + " with three backticks alone. toBeChecked and findings are lists of at"
                        + " most "
                        + HandedResults.MOST_ITEMS
                        + " items, each item one line of at most "
                        + HandedResults.MOST_ITEM
                        + " characters that does not start with #. No text holds a control or"
                        + " direction character, nor a line break outside the plan";
        return "\n\nThis stage hands back its result; the planner saves it and moves the issues."
                + " Read each issue as the planner holds it now from its given file, read only,"
                + " in Porelo's given directory: "
                + String.join(", ", given.stream().map(WorkOrder.Given::name).toList())
                + ". Do not read the issues from the repository's copy, which may be older."
                + " Write the result to "
                + result.file()
                + " in Porelo's result directory, one JSON object: format \""
                + FORMAT
                + "\", kind \""
                + result.name()
                + "\", and issues, one object per issue the order names with its id, "
                + fields
                + bounds
                + ". In the result, name an issue of this repository by its number, which the"
                + " planner links to its row, and name no other repository's issue."
                + " Edit nothing under issues/, commit nothing and push nothing.";
    }
}
