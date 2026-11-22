package org.fuin.porelo.planner.store.git;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.git.stories.Story;
import org.fuin.porelo.planner.store.git.stories.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * What one repository adds to a board at one commit.
 *
 * @param workflow the board's workflow, from the project's own repository alone
 * @param stories the stories as their files give them, from the project's own repository alone
 * @param issues the repository's issues
 * @param work the work rules, from the project's own repository alone
 * @param paths the file of each issue with a head, by its id, and of each story, by {@link
 *     #story(String)}
 * @param faults each file left out because it could not be read, naming it and why
 * @param copies the files of each id two or more files carry, by the keys of {@code paths}, which
 *     holds none of them
 */
public record Part(
        @Nullable Workflow workflow,
        List<Story> stories,
        List<Issue> issues,
        @Nullable Work work,
        Map<String, String> paths,
        List<String> faults,
        Map<String, List<String>> copies) {

    /** Copies the lists and the maps. */
    public Part {
        stories = List.copyOf(stories);
        issues = List.copyOf(issues);
        paths = Collections.unmodifiableMap(new LinkedHashMap<>(paths));
        faults = List.copyOf(faults);
        final Map<String, List<String>> copied = new LinkedHashMap<>();
        copies.forEach((key, files) -> copied.put(key, List.copyOf(files)));
        copies = Collections.unmodifiableMap(copied);
    }

    /**
     * Makes a part without an id carried twice.
     *
     * @param workflow the board's workflow
     * @param stories the stories
     * @param issues the issues
     * @param work the work rules
     * @param paths the files by id
     * @param faults each file left out
     */
    public Part(
            final @Nullable Workflow workflow,
            final List<Story> stories,
            final List<Issue> issues,
            final @Nullable Work work,
            final Map<String, String> paths,
            final List<String> faults) {
        this(workflow, stories, issues, work, paths, faults, Map.of());
    }

    /**
     * Answers the files a story or an issue is kept in.
     *
     * @param key the issue's id, or a story's {@link #story(String)}
     * @return its file, each of its files when two or more carry its id, or none when only an index
     *     row holds it
     */
    public List<String> files(final String key) {
        final String path = paths.get(key);
        return path != null ? List.of(path) : copies.getOrDefault(key, List.of());
    }

    /**
     * Makes a part with no faults.
     *
     * @param workflow the board's workflow
     * @param stories the stories
     * @param issues the issues
     * @param work the work rules
     * @param paths the files by id
     */
    public Part(
            final @Nullable Workflow workflow,
            final List<Story> stories,
            final List<Issue> issues,
            final @Nullable Work work,
            final Map<String, String> paths) {
        this(workflow, stories, issues, work, paths, List.of());
    }

    /**
     * Answers the key of a story's file in {@link #paths()}, apart from any issue's id.
     *
     * @param id the story's id
     * @return the key
     */
    public static String story(final String id) {
        return "story " + id;
    }

    /**
     * Makes a part whose files are not known, as a reader that saves nothing makes it.
     *
     * @param workflow the board's workflow
     * @param stories the stories
     * @param issues the issues
     * @param work the work rules
     */
    public Part(
            final @Nullable Workflow workflow,
            final List<Story> stories,
            final List<Issue> issues,
            final @Nullable Work work) {
        this(workflow, stories, issues, work, Map.of());
    }
}
