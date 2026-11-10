package org.fuin.porelo.planner.store;

import java.util.Collections;
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
 */
public record WorkRule(
        List<String> from,
        String done,
        @Nullable String then,
        @Nullable String blocked,
        Per per,
        List<String> modes,
        String order,
        Map<String, String> models) {

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
        if (from.isEmpty()) {
            throw new IllegalArgumentException("from names no state");
        }
        if (modes.isEmpty()) {
            throw new IllegalArgumentException("modes names no mode");
        }
        if (models.isEmpty()) {
            throw new IllegalArgumentException("models names no agent");
        }
        if (order.isBlank()) {
            throw new IllegalArgumentException("order is empty");
        }
    }
}
