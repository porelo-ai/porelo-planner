package org.fuin.porelo.planner.store;

import java.util.List;

/**
 * One repository of a project: the name the planner and the window know it by, and the folder a
 * task that reads the whole project finds it in under {@code /workspace}.
 *
 * @param name its name, the last part of its central address, which an issue's repository carries
 * @param folder its folder: {@code project.name} for the project's own repository, its key under
 *     {@code repositories} in {@code project.yml} for the others; its name where a store knows no
 *     {@code project.yml}
 */
public record ProjectRepository(String name, String folder) {

    /** Checks the name and the folder. */
    public ProjectRepository {
        Ids.name(name);
        Ids.name(folder);
    }

    /**
     * A repository whose folder is its name, for a store that knows no {@code project.yml}.
     *
     * @param name its name
     * @return the repository
     */
    public static ProjectRepository named(final String name) {
        return new ProjectRepository(name, name);
    }

    /**
     * Repositories whose folders are their names, for a store that knows no {@code project.yml}.
     *
     * @param names their names, in order
     * @return the repositories, in the same order
     */
    public static List<ProjectRepository> named(final List<String> names) {
        return names.stream().map(ProjectRepository::named).toList();
    }

    /**
     * The repositories' names.
     *
     * @param repositories the repositories
     * @return their names, in the same order
     */
    public static List<String> names(final List<ProjectRepository> repositories) {
        return repositories.stream().map(ProjectRepository::name).toList();
    }
}
