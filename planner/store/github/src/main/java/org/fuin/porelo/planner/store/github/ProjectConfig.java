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
 * @param workflow the project's workflow
 * @param work the project's work rules
 * @param repositories the project's repositories by name, its own first
 * @param issueFloor the highest number each prefix used before the cut-over, {@code ST} included
 * @param fieldIds the GitHub ids of the project's fields, by field name; empty until they are read
 * @param optionIds the GitHub ids of each single-select field's options, by field name and option
 *     name
 */
public record ProjectConfig(
        URI forge,
        ProjectRef project,
        Workflow workflow,
        Work work,
        List<String> repositories,
        Map<String, Integer> issueFloor,
        Map<String, String> fieldIds,
        Map<String, Map<String, String>> optionIds) {

    /** Checks the forge, the names and the floors, and copies the lists and maps. */
    public ProjectConfig {
        TokenSource.checkedOrigin(forge);
        repositories = List.copyOf(repositories);
        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("A project holds its own repository");
        }
        repositories.forEach(Ids::name);
        issueFloor.forEach(
                (prefix, floor) -> {
                    if (!prefix.matches("[A-Z]{1,4}") || floor < 0) {
                        throw new IllegalArgumentException(
                                "An issue floor is a prefix of capital letters and a whole number of at least 0");
                    }
                });
        issueFloor = Collections.unmodifiableMap(new LinkedHashMap<>(issueFloor));
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
