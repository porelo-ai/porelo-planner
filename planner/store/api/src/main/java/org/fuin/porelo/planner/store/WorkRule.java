package org.fuin.porelo.planner.store;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The work a working state stands for: from which states it starts, which state its success leads
 * to, and what the window starts for it.
 *
 * @param from the states a story or an issue may enter this work from
 * @param done the state the work's success leads to when no further work follows
 * @param then the working state success leads to instead, or null when it leads to {@code done}
 * @param blocked the working state blocking findings lead to, or null when none do
 * @param per whether one task runs per story or per repository
 * @param modes the modes a task of this work may be started in, the first the default
 * @param order the order the task is given, with {@code {story}}, {@code {issues}} and {@code
 *     {tasks}} filled in
 * @param models the model per agent, the first agent the default
 * @param risks what the rule chooses for an issue's risk instead of its own models, per risk it
 *     names
 */
public record WorkRule(
        List<String> from,
        String done,
        @Nullable String then,
        @Nullable String blocked,
        Per per,
        List<String> modes,
        String order,
        Map<String, String> models,
        Map<Risk, ForRisk> risks) {

    /** Longest agent, model or effort a rule names, as a task's settings allow. */
    public static final int LONGEST = 128;

    /**
     * What a rule chooses for one risk; what it leaves out comes from the {@code ordinary} entry,
     * then from the rule's own models and no effort.
     *
     * @param models the model per agent, the first the default, replacing the rule's whole; empty
     *     when the entry names none
     * @param effort the effort, a name passed on as it stands, or null when the entry names none
     */
    public record ForRisk(Map<String, String> models, @Nullable String effort) {

        /** Copies the models in their order and refuses an entry that names nothing. */
        public ForRisk {
            models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
            if (models.isEmpty() && effort == null) {
                throw new IllegalArgumentException("names neither models nor effort");
            }
            models.forEach(
                    (agent, model) -> {
                        name(agent, "an agent");
                        name(model, "a model");
                    });
            if (effort != null) {
                name(effort, "an effort");
            }
        }
    }

    /**
     * A rule that names no risk, so every task takes its own models and no effort.
     *
     * @param from the states a story or an issue may enter this work from
     * @param done the state the work's success leads to when no further work follows
     * @param then the working state success leads to instead, or null when it leads to {@code done}
     * @param blocked the working state blocking findings lead to, or null when none do
     * @param per whether one task runs per story or per repository
     * @param modes the modes a task of this work may be started in, the first the default
     * @param order the order the task is given
     * @param models the model per agent, the first agent the default
     */
    public WorkRule(
            final List<String> from,
            final String done,
            final @Nullable String then,
            final @Nullable String blocked,
            final Per per,
            final List<String> modes,
            final String order,
            final Map<String, String> models) {
        this(from, done, then, blocked, per, modes, order, models, Map.of());
    }

    /** Whether one task runs for the whole story or one for each of its repositories. */
    public enum Per {
        /** One task for the story. */
        STORY,
        /** One task for each repository the story's issues are in. */
        REPOSITORY
    }

    /** Copies the lists and the models, keeping their order, and refuses an empty one. */
    public WorkRule {
        from = List.copyOf(from);
        modes = List.copyOf(modes);
        models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
        risks =
                Collections.unmodifiableMap(
                        risks.isEmpty() ? new EnumMap<>(Risk.class) : new EnumMap<>(risks));
        if (from.isEmpty()) {
            throw new IllegalArgumentException("from names no state");
        }
        if (modes.isEmpty()) {
            throw new IllegalArgumentException("modes names no mode");
        }
        if (models.isEmpty()) {
            throw new IllegalArgumentException("models names no agent");
        }
        if (modes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("modes names a blank mode");
        }
        if (models.entrySet().stream()
                .anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("models names a blank agent or model");
        }
        if (order.isBlank()) {
            throw new IllegalArgumentException("order is empty");
        }
    }

    /**
     * Whether a name may reach a task as an agent, a model or an effort: not blank, at most {@link
     * #LONGEST} characters and without a control character or a line separator.
     *
     * @param name the name
     * @return whether it is sound
     */
    public static boolean sound(final String name) {
        return !name.isBlank()
                && name.length() <= LONGEST
                && name.codePoints()
                        .noneMatch(
                                c ->
                                        Character.isISOControl(c)
                                                || c == 0x85
                                                || c == 0x2028
                                                || c == 0x2029);
    }

    /** Refuses a name that is not sound, since it reaches a task. */
    private static void name(final String name, final String what) {
        if (!sound(name)) {
            throw new IllegalArgumentException(
                    "names "
                            + what
                            + " that is blank, longer than "
                            + LONGEST
                            + " characters or holds a control character");
        }
    }
}
