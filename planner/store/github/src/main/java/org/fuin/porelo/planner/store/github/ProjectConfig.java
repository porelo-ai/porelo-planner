package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TokenSource;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;

/**
 * What a GitHub project is read and written with: where it is, the project's own files as the Git
 * store read them, and the GitHub ids of its fields.
 *
 * @param forge the forge's origin, {@code https://github.com} or an enterprise host, which the
 *     token is asked for
 * @param project the GitHub project that holds the fields
 * @param commit the commit of the own repository the project's files were read from, which the
 *     board's revision covers
 * @param workflow the project's workflow
 * @param work the project's work rules
 * @param repositories the project's repositories, its own first, each with its folder
 * @param fieldIds the GitHub ids of the project's fields, by field name; empty until they are read
 * @param optionIds the GitHub ids of each single-select field's options, by field name and option
 *     name
 * @param projectId the GitHub project's node id, which a field value is written to; empty until it
 *     is read
 * @param priorities the priorities the organization's issue field Priority holds, the highest
 *     first, which a correction of a story's priority offers; empty until it is read, and when the
 *     owner has no such field or it could not be read
 */
public record ProjectConfig(
        URI forge,
        ProjectRef project,
        String commit,
        Workflow workflow,
        Work work,
        List<ProjectRepository> repositories,
        Map<String, String> fieldIds,
        Map<String, Map<String, String>> optionIds,
        String projectId,
        List<String> priorities) {

    /**
     * Makes the configuration before the organization's issue field Priority is read.
     *
     * @param forge the forge's origin
     * @param project the GitHub project
     * @param commit the commit the project's files were read from
     * @param workflow the project's workflow
     * @param work the project's work rules
     * @param repositories the project's repositories, its own first, each with its folder
     * @param fieldIds the GitHub ids of the project's fields, by field name
     * @param optionIds the GitHub ids of each single-select field's options
     * @param projectId the GitHub project's node id
     */
    public ProjectConfig(
            final URI forge,
            final ProjectRef project,
            final String commit,
            final Workflow workflow,
            final Work work,
            final List<ProjectRepository> repositories,
            final Map<String, String> fieldIds,
            final Map<String, Map<String, String>> optionIds,
            final String projectId) {
        this(
                forge,
                project,
                commit,
                workflow,
                work,
                repositories,
                fieldIds,
                optionIds,
                projectId,
                List.of());
    }

    /**
     * Makes the configuration before the project's node id is read.
     *
     * @param forge the forge's origin
     * @param project the GitHub project
     * @param commit the commit the project's files were read from
     * @param workflow the project's workflow
     * @param work the project's work rules
     * @param repositories the project's repositories, its own first, each with its folder
     * @param fieldIds the GitHub ids of the project's fields, by field name
     * @param optionIds the GitHub ids of each single-select field's options
     */
    public ProjectConfig(
            final URI forge,
            final ProjectRef project,
            final String commit,
            final Workflow workflow,
            final Work work,
            final List<ProjectRepository> repositories,
            final Map<String, String> fieldIds,
            final Map<String, Map<String, String>> optionIds) {
        this(forge, project, commit, workflow, work, repositories, fieldIds, optionIds, "");
    }

    /**
     * Checks the forge, the names and the priorities, and copies the lists and maps.
     *
     * @throws IllegalArgumentException for a priority none of the planner's
     */
    public ProjectConfig {
        TokenSource.checkedOrigin(forge);
        if (commit.isBlank()) {
            throw new IllegalArgumentException("A project's files are read from a commit");
        }
        repositories = List.copyOf(repositories);
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("A project holds its own repository");
        }
        fieldIds = Collections.unmodifiableMap(new LinkedHashMap<>(fieldIds));
        final Map<String, Map<String, String>> options = new LinkedHashMap<>();
        optionIds.forEach(
                (field, byName) ->
                        options.put(
                                field, Collections.unmodifiableMap(new LinkedHashMap<>(byName))));
        optionIds = Collections.unmodifiableMap(options);
        priorities = List.copyOf(priorities);
        if (!Story.PRIORITIES.containsAll(priorities)) {
            throw new IllegalArgumentException("A priority is none of " + Story.PRIORITIES);
        }
    }

    /**
     * Answers this configuration with the priorities the organization's issue field holds.
     *
     * @param held the priorities, the highest first
     * @return the configuration
     */
    public ProjectConfig withPriorities(final List<String> held) {
        return new ProjectConfig(
                forge,
                project,
                commit,
                workflow,
                work,
                repositories,
                fieldIds,
                optionIds,
                projectId,
                held);
    }

    /**
     * Answers the project's own repository, which holds its stories.
     *
     * @return its name
     */
    public String own() {
        return repositories.get(0).name();
    }

    /**
     * Answers the names of the project's repositories, by which GitHub knows them.
     *
     * @return their names, its own first
     */
    public List<String> names() {
        return ProjectRepository.names(repositories);
    }
}
