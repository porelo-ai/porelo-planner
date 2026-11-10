package org.fuin.porelo.planner.store.git;

import java.util.regex.Pattern;

/**
 * The paths the store reads inside a repository: plain names joined by {@code /}, never leaving it.
 */
final class RepoPath {

    private static final Pattern PATH =
            Pattern.compile(
                    "[A-Za-z0-9_][A-Za-z0-9._-]{0,127}(/[A-Za-z0-9_][A-Za-z0-9._-]{0,127}){0,7}");

    private RepoPath() {}

    /**
     * Refuses a path that could leave the repository or read as an option.
     *
     * @param path the path
     * @return the path
     */
    static String checked(final String path) {
        if (!PATH.matcher(path).matches() || path.contains("..")) {
            throw new IllegalArgumentException(
                    "Not a path inside a repository: '" + path.replaceAll("\\p{Cntrl}", "?") + "'");
        }
        return path;
    }
}
