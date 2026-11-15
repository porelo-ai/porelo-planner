package org.fuin.porelo.planner.store;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a stage hands back for its issues, which the store saves with each issue's new state in one
 * save, so no task writes the store's format itself.
 *
 * @param kind what the stage hands back
 * @param issues each issue's result, one per issue
 */
public record StageResult(WorkRule.Result kind, List<ForIssue> issues) {

    /** The sizes a stage may give; a dash means none was given and is no result. */
    public static final List<String> SIZES = List.of("XS", "S", "M", "L", "XL");

    /** The most minutes an estimate may name. */
    public static final int MOST_MINUTES = 100_000;

    /** A first- or second-level heading, which would open a section of its own. */
    private static final Pattern HEADING = Pattern.compile("#{1,2}( .*)?");

    /**
     * Copies the issues and refuses none, one issue twice, and an estimate without minutes.
     *
     * @throws IllegalArgumentException when the result is not one the store can save
     */
    public StageResult {
        issues = List.copyOf(issues);
        if (issues.isEmpty()) {
            throw new IllegalArgumentException("The result names no issue");
        }
        final Set<String> ids = new HashSet<>();
        for (final ForIssue issue : issues) {
            if (!ids.add(issue.id())) {
                throw new IllegalArgumentException("The result names " + issue.id() + " twice");
            }
            if (kind == WorkRule.Result.ESTIMATE && issue.estimateMinutes() == null) {
                throw new IllegalArgumentException(
                        "The estimate of " + issue.id() + " names no minutes");
            }
        }
    }

    /**
     * One issue's result.
     *
     * @param id the issue's id
     * @param from the working state the stage completes, in which the issue must still stand
     * @param to the open state it moves to
     * @param plan the plan's text in the store's format, or null when the stage writes none
     * @param size its size, one of {@link #SIZES}
     * @param sizeWhy one line on why it has that size
     * @param estimateMinutes the minutes it is estimated at, or null when the stage gives none
     * @param toBeChecked what is still to be checked, one line each
     * @param findings what the stage found, one line each
     */
    public record ForIssue(
            String id,
            String from,
            String to,
            @Nullable String plan,
            String size,
            String sizeWhy,
            @Nullable Integer estimateMinutes,
            List<String> toBeChecked,
            List<String> findings) {

        /**
         * Checks the id, the size, the minutes and each text, and copies the lists; a plan's line
         * breaks are made line feeds.
         *
         * @throws IllegalArgumentException for a value the store cannot write as it stands
         */
        public ForIssue {
            Ids.issue(id);
            line(from, "Its from state");
            line(to, "Its to state");
            if (!SIZES.contains(size)) {
                throw new IllegalArgumentException(id + ": the size is none of " + SIZES);
            }
            line(sizeWhy, id + ": why it has its size");
            if (estimateMinutes != null
                    && (estimateMinutes < 1 || estimateMinutes > MOST_MINUTES)) {
                throw new IllegalArgumentException(
                        id + ": the estimate is not between 1 and " + MOST_MINUTES + " minutes");
            }
            toBeChecked = List.copyOf(toBeChecked);
            findings = List.copyOf(findings);
            toBeChecked.forEach(item -> item(item, id + ": a line to be checked"));
            findings.forEach(item -> item(item, id + ": a finding"));
            if (plan != null) {
                plan = plan.replace("\r\n", "\n");
                checkPlan(plan, id);
            }
        }
    }

    /** Refuses a blank text and one that breaks its line. */
    private static void line(final String text, final String what) {
        if (text.isBlank() || text.chars().anyMatch(StageResult::breaks)) {
            throw new IllegalArgumentException(what + " is blank or not one line");
        }
    }

    /** Refuses an item that is not one line or that would read as a heading. */
    private static void item(final String text, final String what) {
        line(text, what);
        if (text.startsWith("#")) {
            throw new IllegalArgumentException(what + " starts with #");
        }
    }

    /**
     * Refuses a plan that is blank, holds a lone carriage return, opens a section of its own
     * outside a code block, or leaves a code block open, which would swallow the sections below.
     */
    private static void checkPlan(final String plan, final String id) {
        if (plan.isBlank() || plan.chars().anyMatch(c -> breaks(c) && c != '\n')) {
            throw new IllegalArgumentException(id + ": the plan is blank or breaks lines oddly");
        }
        boolean fenced = false;
        for (final String line : plan.split("\n", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced && HEADING.matcher(line).matches()) {
                throw new IllegalArgumentException(
                        id + ": the plan holds a heading that would open a section of its own");
            }
        }
        if (fenced) {
            throw new IllegalArgumentException(id + ": the plan leaves a code block open");
        }
    }

    private static boolean breaks(final int c) {
        return c == '\n' || c == '\r' || c == 0x85 || c == 0x2028 || c == 0x2029;
    }
}
