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
     * Checks the rules: each working state, {@code from}, {@code done}, {@code then} and {@code
     * blocked} a state of the workflow; {@code then} and {@code blocked} working states with a rule
     * of their own; {@code done} no working state.
     *
     * @param workflow the board's workflow
     * @param work the rules as the store read them
     * @return the sound rules and every fault
     */
    public static Checked check(final Workflow workflow, final Work work) {
        final List<String> faults = new ArrayList<>(work.faults());
        final Map<String, WorkRule> sound = new LinkedHashMap<>();
        for (final Map.Entry<String, WorkRule> entry : work.rules().entrySet()) {
            final List<String> own = faults(workflow, work, entry.getKey(), entry.getValue());
            if (own.isEmpty()) {
                sound.put(entry.getKey(), entry.getValue());
            } else {
                faults.addAll(own);
            }
        }
        return new Checked(sound, faults);
    }

    private static List<String> faults(
            final Workflow workflow, final Work work, final String state, final WorkRule rule) {
        final String at = "stories/work.yml: " + state + ": ";
        if (!workflow.states().contains(state)) {
            return List.of(at + "no state of the workflow");
        }
        final List<String> faults = new ArrayList<>();
        for (final String from : rule.from()) {
            known(workflow, at, "from", from, faults);
        }
        if (known(workflow, at, "done", rule.done(), faults)
                && work.rules().containsKey(rule.done())) {
            faults.add(at + "done '" + rule.done() + "' is a working state");
        }
        working(workflow, work, at, "then", rule.then(), faults);
        working(workflow, work, at, "blocked", rule.blocked(), faults);
        return faults;
    }

    /** A next state of work must be work the rules know how to start. */
    private static void working(
            final Workflow workflow,
            final Work work,
            final String at,
            final String key,
            final @Nullable String state,
            final List<String> faults) {
        if (state != null
                && known(workflow, at, key, state, faults)
                && !work.rules().containsKey(state)) {
            faults.add(at + key + " '" + state + "' is a working state without a rule");
        }
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
