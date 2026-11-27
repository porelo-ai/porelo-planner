package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;

/** Checks the work rules against the workflow, keeping only the rules that are sound. */
public final class WorkRules {

    /**
     * The rules work may start by, and every fault of the rest, in words.
     *
     * @param rules the sound rule per working state, in the file's order
     * @param faults what the store could not read and why each dropped rule was dropped
     */
    public record Checked(Map<String, WorkRule> rules, List<String> faults) {

        /** Copies the rules, keeping their order, and the faults. */
        public Checked {
            rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
            faults = List.copyOf(faults);
        }
    }

    private WorkRules() {}

    /**
     * Checks the rules: each working state an open state of the workflow, {@code from}, {@code
     * done}, {@code then} and {@code blocked} a state of the workflow; {@code then} and {@code
     * blocked} working states with a rule of their own; {@code done} no working state.
     *
     * @param workflow the board's workflow
     * @param work the rules as the store read them
     * @return the sound rules and every fault
     */
    public static Checked check(final Workflow workflow, final Work work) {
        final List<String> faults = new ArrayList<>(work.faults());
        final Map<String, WorkRule> sound = new LinkedHashMap<>();
        for (final Map.Entry<String, WorkRule> entry : work.rules().entrySet()) {
            final List<String> own = own(workflow, entry.getKey(), entry.getValue());
            if (own.isEmpty()) {
                sound.put(entry.getKey(), entry.getValue());
            } else {
                faults.addAll(own);
            }
        }
        // A dropped rule can leave another leading into it, so drop until nothing changes.
        boolean dropped = true;
        while (dropped) {
            dropped = false;
            final Map<String, List<String>> leading = new LinkedHashMap<>();
            for (final Map.Entry<String, WorkRule> entry : sound.entrySet()) {
                final List<String> found = leading(work, sound, entry.getKey(), entry.getValue());
                if (!found.isEmpty()) {
                    leading.put(entry.getKey(), found);
                }
            }
            for (final Map.Entry<String, List<String>> entry : leading.entrySet()) {
                sound.remove(entry.getKey());
                faults.addAll(entry.getValue());
                dropped = true;
            }
        }
        return new Checked(sound, faults);
    }

    /** The faults a rule has on its own: its states unknown, or its working state closed. */
    private static List<String> own(
            final Workflow workflow, final String state, final WorkRule rule) {
        final String at = "stories/work.yml: " + state + ": ";
        if (!workflow.states().contains(state)) {
            return List.of(at + "no state of the workflow");
        }
        if (workflow.closed().contains(state)) {
            return List.of(at + "a closed state");
        }
        final List<String> faults = new ArrayList<>();
        for (final String from : rule.from()) {
            known(workflow, at, "from", from, faults);
        }
        known(workflow, at, "done", rule.done(), faults);
        if (rule.then() != null) {
            known(workflow, at, "then", rule.then(), faults);
        }
        if (rule.blocked() != null) {
            known(workflow, at, "blocked", rule.blocked(), faults);
        }
        // A saved result moves the issues to done itself, past any reading or blocked state.
        if (rule.result() != null && (rule.then() != null || rule.blocked() != null)) {
            faults.add(at + "a rule with a result names no then and no blocked");
        }
        // A reading that finds nothing moves its issue to done, so it leads nowhere else.
        if (rule.role().reads() && rule.then() != null) {
            faults.add(
                    at
                            + "the role '"
                            + rule.role().name()
                            + "' "
                            + rule.role().end().words()
                            + ", so the rule names no then");
        }
        // What a reading finds sends the work back to rework, so it needs somewhere to send it,
        // and work that is built has no findings to send anywhere.
        if (rule.role().reads() != (rule.blocked() != null)) {
            faults.add(
                    at
                            + (rule.role().reads()
                                    ? "the role '"
                                            + rule.role().name()
                                            + "' "
                                            + rule.role().end().words()
                                            + ", so the rule names blocked"
                                    : "the rule names blocked, and the role '"
                                            + rule.role().name()
                                            + "' "
                                            + rule.role().end().words()));
        }
        // An issue not ready goes back where it came from, so there is one place, open and idle.
        if (rule.result() == WorkRule.Result.READINESS) {
            if (rule.from().size() != 1) {
                faults.add(at + "a readiness check names exactly one from state");
            } else if (workflow.closed().contains(rule.from().getFirst())
                    || rule.from().getFirst().equals(state)) {
                faults.add(at + "a readiness check's from state is open and no working state");
            }
        }
        return faults;
    }

    /** The faults of where a rule leads, against the rules still sound. */
    private static List<String> leading(
            final Work work,
            final Map<String, WorkRule> sound,
            final String state,
            final WorkRule rule) {
        final String at = "stories/work.yml: " + state + ": ";
        final List<String> faults = new ArrayList<>();
        if (sound.containsKey(rule.done())) {
            faults.add(at + "done '" + rule.done() + "' is a working state");
        }
        if (rule.result() == WorkRule.Result.READINESS
                && sound.containsKey(rule.from().getFirst())) {
            faults.add(at + "from '" + rule.from().getFirst() + "' is a working state");
        }
        working(work, sound, at, "then", rule.then(), faults);
        working(work, sound, at, "blocked", rule.blocked(), faults);
        final WorkRule next = rule.then() == null ? null : sound.get(rule.then());
        if (next != null && rule.role().pushesWork() && !next.role().reads()) {
            // Work built and worked on again by itself, with nothing read between, would start
            // agent tasks without end.
            faults.add(
                    at
                            + "then '"
                            + rule.then()
                            + "' reads no work: its role '"
                            + next.role().name()
                            + "' "
                            + next.role().end().words());
        }
        final WorkRule rework = rule.blocked() == null ? null : sound.get(rule.blocked());
        if (rework != null && rework.role().reads()) {
            // What a reading finds is worked on, so the rule it leads to never reads again.
            faults.add(
                    at
                            + "blocked '"
                            + rule.blocked()
                            + "' is no work on what was found: its role '"
                            + rework.role().name()
                            + "' "
                            + rework.role().end().words());
        }
        return faults;
    }

    /** A next state of work must be work the rules know how to start. */
    private static void working(
            final Work work,
            final Map<String, WorkRule> sound,
            final String at,
            final String key,
            final @Nullable String state,
            final List<String> faults) {
        if (state == null || sound.containsKey(state)) {
            return;
        }
        faults.add(
                at
                        + key
                        + " '"
                        + state
                        + (work.rules().containsKey(state)
                                ? "' has no rule work can start by"
                                : "' is a working state without a rule"));
    }

    private static boolean known(
            final Workflow workflow,
            final String at,
            final String key,
            final String state,
            final List<String> faults) {
        if (workflow.states().contains(state)) {
            return true;
        }
        faults.add(at + key + " '" + state + "' is no state of the workflow");
        return false;
    }
}
