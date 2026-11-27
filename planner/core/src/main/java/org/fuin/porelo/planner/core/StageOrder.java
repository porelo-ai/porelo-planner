package org.fuin.porelo.planner.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;

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
     * Answers the task as its stage starts it: naming the tracker's issue and the stage's phase for
     * a project a tracker keeps; else unchanged for work that pushes and for a reader, and with the
     * rule's result file, each owned issue's text as a given file and the stage's instructions
     * closing its prompt for a stage that hands back its result.
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
     * Answers the task as its stage starts it, given the texts of the issues it owns alone, the
     * first of which it names to Start as the tracker's issue.
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
        final WorkOrder.Task working = task.working(tracked(board, to, task, owned));
        final WorkRule.Result result = rule.result();
        if (result == null || task.reading()) {
            return working;
        }
        final List<WorkOrder.Given> given = new ArrayList<>();
        int all = 0;
        for (final String id : owned) {
            WorkRefusal.unlessFree(board, id, "", to);
            final Issue issue = board.issue(id).orElseThrow();
            final String text =
                    result == WorkRule.Result.READINESS
                            ? fields(issue) + issue.text()
                            : issue.text();
            final int bytes = text.getBytes(StandardCharsets.UTF_8).length;
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
            given.add(new WorkOrder.Given(given(id), text));
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
                task.readLanded(),
                task.basis(),
                task.used(),
                result.file(),
                given,
                working.work(),
                task.lead(),
                task.again(),
                task.place());
    }

    /**
     * The tracker's issue a task names to Start, the first it owns with the tracker's owner, and
     * the phase its working state stands in, the state itself where no phase names it; null for a
     * project no tracker keeps, for a discussion and for a task that owns no issue.
     */
    private static WorkOrder.@Nullable Tracked tracked(
            final Board board,
            final String to,
            final WorkOrder.Task task,
            final List<String> owned) {
        final String owner = board.owner();
        if (owner == null
                || task.discussion()
                || owned.isEmpty()
                || !Ids.isReference(owned.getFirst())) {
            return null;
        }
        final String phase =
                board.workflow().phases().stream()
                        .filter(candidate -> candidate.states().contains(to))
                        .map(Workflow.Phase::name)
                        .findFirst()
                        .orElse(to);
        try {
            return new WorkOrder.Tracked(owner + "/" + owned.getFirst(), phase);
        } catch (final IllegalArgumentException e) {
            throw WorkRefusal.notAllowed(
                    "", to, "The stage's phase name is not one line of at most 64 characters.");
        }
    }

    /**
     * The board's fields a readiness check needs beside an issue's text, which a tracker keeps
     * apart from it: its size, what it is blocked by and what it waits on outside the project.
     *
     * @param issue the issue
     * @return the lines, ending in an empty line
     */
    static String fields(final Issue issue) {
        final String size = issue.size();
        final List<String> waits = new ArrayList<>();
        for (final org.fuin.porelo.planner.store.Wait wait : issue.waitsOn()) {
            final String reference = wait.reference();
            waits.add(
                    (reference == null ? "an issue outside the project" : reference)
                            + (Boolean.TRUE.equals(wait.done()) ? " (done)" : ""));
        }
        return "The board's fields of "
                + issue.id()
                + ", beside its text below:\n"
                + "- Size: "
                + (size == null ? "none" : size)
                + "\n- Blocked by: "
                + (issue.blockedBy().isEmpty() ? "none" : String.join(", ", issue.blockedBy()))
                + "\n- Waits on: "
                + (waits.isEmpty() ? "none" : String.join(", ", waits))
                + "\n\n";
    }

    /** Closes the prompt, after every quoted text, so no issue's text can reword it. */
    private static String instructions(
            final WorkRule.Result result, final List<WorkOrder.Given> given) {
        final String fields =
                switch (result) {
                    case ANALYSIS ->
                            "plan (Markdown, the base design read in today's code), size, sizeWhy,"
                                    + " toBeChecked and findings";
                    case ESTIMATE ->
                            "size, sizeWhy, estimateMinutes (whole minutes from 1 to "
                                    + StageResult.MOST_MINUTES
                                    + "), and optionally plan, toBeChecked and findings";
                    case READINESS ->
                            "ready (true or false: whether the issue names a plan, a size and its"
                                    + " blockers, or that it has none), and for an issue not ready"
                                    + " lacks (a list of "
                                    + String.join(", ", HandedResults.LACKS)
                                    + ", each once) and why (one line why)";
                };
        final String bounds =
                result == WorkRule.Result.READINESS
                        ? ". Each given file opens with the board's fields of its issue: its size,"
                                + " what blocks it and what it waits on, which count as named. A"
                                + " ready issue names no lacks and no why. why is one line of at"
                                + " most "
                                + HandedResults.MOST_WHY
                                + " characters. No other member is named, and no text holds a"
                                + " control or direction character"
                        : ". The size is one of "
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
                + ". In the result, name an issue of this repository by its id as the order"
                + " names it, and name no other repository's issue."
                + " Edit nothing under issues/, commit nothing and push nothing.";
    }
}
