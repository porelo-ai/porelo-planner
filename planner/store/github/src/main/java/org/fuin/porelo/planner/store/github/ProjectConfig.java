package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Ids;
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
 * @param repositories the project's repositories by name, its own first
 * @param fieldIds the GitHub ids of the project's fields, by field name; empty until they are read
 * @param optionIds the GitHub ids of each single-select field's options, by field name and option
 *     name
 * @param projectId the GitHub project's node id, which a field value is written to; empty until it
 *     is read
 */
public record ProjectConfig(
        URI forge,
        ProjectRef project,
        String commit,
        Workflow workflow,
        Work work,
        List<String> repositories,
        Map<String, String> fieldIds,
        Map<String, Map<String, String>> optionIds,
        String projectId) {

    /**
     * Makes the configuration before the project's node id is read.
     *
     * @param forge the forge's origin
     * @param project the GitHub project
     * @param commit the commit the project's files were read from
     * @param workflow the project's workflow
     * @param work the project's work rules
     * @param repositories the project's repositories by name, its own first
     * @param fieldIds the GitHub ids of the project's fields, by field name
     * @param optionIds the GitHub ids of each single-select field's options
     */
    public ProjectConfig(
            final URI forge,
            final ProjectRef project,
            final String commit,
            final Workflow workflow,
            final Work work,
            final List<String> repositories,
            final Map<String, String> fieldIds,
            final Map<String, Map<String, String>> optionIds) {
        this(forge, project, commit, workflow, work, repositories, fieldIds, optionIds, "");
    }

    /** Checks the forge and the names, and copies the lists and maps. */
    public ProjectConfig {
        TokenSource.checkedOrigin(forge);
        if (commit.isBlank()) {
            throw new IllegalArgumentException("A project's files are read from a commit");
        }
        repositories = List.copyOf(repositories);
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("A project holds its own repository");
        }
        repositories.forEach(Ids::name);
        fieldIds = Collections.unmodifiableMap(new LinkedHashMap<>(fieldIds));
        final Map<String, Map<String, String>> options = new LinkedHashMap<>();
        optionIds.forEach(
                (field, byName) ->
                        options.put(
                                field, Collections.unmodifiableMap(new LinkedHashMap<>(byName))));
        optionIds = Collections.unmodifiableMap(options);
    }

    /**
     * Answers the project's own repository, which holds its stories.
     *
     * @return its name
     */
    public String own() {
        return repositories.get(0);
    }
}
