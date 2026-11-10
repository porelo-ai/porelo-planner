package org.fuin.porelo.planner.store.git;

import java.util.List;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.stories.Story;
import org.fuin.porelo.stories.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * What one repository adds to a board at one commit.
 *
 * @param workflow the board's workflow, from the project's own repository alone
 * @param stories the stories as their files give them, from the project's own repository alone
 * @param issues the repository's issues
 * @param work the work rules, from the project's own repository alone
 */
public record Part(
        @Nullable Workflow workflow, List<Story> stories, List<Issue> issues, @Nullable Work work) {

    /** Copies the lists. */
    public Part {
        stories = List.copyOf(stories);
        issues = List.copyOf(issues);
    }
}
