package org.fuin.porelo.planner.store.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Ids;

/**
 * The planner's clones of a project, under {@code $XDG_CACHE_HOME/porelo-planner/<project>/} on
 * Linux and {@code %LOCALAPPDATA%\porelo-planner\cache\<project>\} on Windows: directories of the
 * person's alone, reached through no link, so nobody else can read the clones or swap one for their
 * own.
 */
public final class CacheDirectory {

    private CacheDirectory() {}

    /**
     * Makes or checks a project's cache directory.
     *
     * @param environment the planner's environment, for {@code XDG_CACHE_HOME} and {@code HOME}, or
     *     {@code LOCALAPPDATA} on Windows
     * @param project the project's name
     * @return the directory, the person's alone
     * @throws IllegalStateException when it, or a directory on its way, could be changed by another
     *     account
     */
    public static Path of(final Map<String, String> environment, final String project) {
        return of(PrivateFiles.of(environment), environment, project);
    }

    /**
     * Makes or checks a project's cache directory with the given platform's files.
     *
     * @param files the platform's private files
     * @param environment the planner's environment
     * @param project the project's name
     * @return the directory, the person's alone
     */
    static Path of(
            final PrivateFiles files, final Map<String, String> environment, final String project) {
        Ids.name(project);
        final List<String> steps = new ArrayList<>(files.steps(PrivateFiles.Kind.CACHE));
        steps.add(project);
        return files.directories(
                files.base(PrivateFiles.Kind.CACHE, environment),
                true,
                PrivateFiles.Kind.CACHE,
                steps);
    }
}
