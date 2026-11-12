package org.fuin.porelo.planner.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/**
 * The agent, model and effort a task is proposed, chosen by its working state's rule for the
 * highest risk among the issues it works, with where the choice came from.
 *
 * @param agent the proposed agent, the first the risk offers
 * @param model the proposed agent's model
 * @param effort the proposed effort, or null when the rule names none for the risk
 * @param basis where the choice came from in words, for example {@code Reading, risk harm}
 * @param offered every agent with its model that may be chosen for the risk, the first the proposal
 */
public record Choice(
        String agent,
        String model,
        @Nullable String effort,
        String basis,
        Map<String, String> offered) {

    /** Copies the offered models in their order. */
    public Choice {
        offered = Collections.unmodifiableMap(new LinkedHashMap<>(offered));
    }

    /**
     * Chooses for a task of a rule over some issues: a risk's own entry first, then {@code
     * ordinary}'s, then the rule's own models and no effort.
     *
     * @param state the working state the rule stands for
     * @param rule the rule
     * @param issues the issues the task works; none is ordinary
     * @return the choice
     */
    public static Choice of(
            final String state, final WorkRule rule, final Collection<Issue> issues) {
        return of(state, rule, Risk.highest(issues.stream().map(Issue::risk).toList()));
    }

    /**
     * Chooses for a task of a rule at a risk.
     *
     * @param state the working state the rule stands for
     * @param rule the rule
     * @param risk the task's risk
     * @return the choice
     */
    public static Choice of(final String state, final WorkRule rule, final Risk risk) {
        final WorkRule.ForRisk own = rule.risks().get(risk);
        final WorkRule.ForRisk ordinary = rule.risks().get(Risk.ORDINARY);
        Map<String, String> models = rule.models();
        if (ordinary != null && !ordinary.models().isEmpty()) {
            models = ordinary.models();
        }
        if (own != null && !own.models().isEmpty()) {
            models = own.models();
        }
        String effort = ordinary == null ? null : ordinary.effort();
        if (own != null && own.effort() != null) {
            effort = own.effort();
        }
        final Map.Entry<String, String> first = models.entrySet().iterator().next();
        return new Choice(
                first.getKey(),
                first.getValue(),
                effort,
                state + ", risk " + risk.spelled(),
                models);
    }

    /**
     * Whether an agent and model may be chosen for the risk.
     *
     * @param agent the chosen agent
     * @param model the chosen model
     * @return whether the risk offers the agent with that model
     */
    public boolean offers(final String agent, final String model) {
        return !agent.isBlank() && !model.isBlank() && model.equals(offered.get(agent));
    }
}
