package org.fuin.porelo.planner.store.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.stories.Project;
import org.jspecify.annotations.Nullable;

/** Reads which repositories a project has from {@code project.yml} in its own repository. */
public final class ProjectFile {

    /** The file, at the root of the project's own repository. */
    public static final String FILE = "project.yml";

    private ProjectFile() {}

    /**
     * Fetches the project's own repository and reads the others from its {@code project.yml}.
     *
     * @param remote reaches the repositories
     * @param own the own repository's clone URL, as the window names it
     * @param branch the own repository's branch, or null for the remote's default
     * @param most the most repositories a project may have, its own included
     * @return the project's repositories, its own first, the others in the file's order
     * @throws IllegalArgumentException if the URL's last part is no repository name
     */
    public static List<Repository> repositories(
            final Remote remote,
            final CloneUrl own,
            final @Nullable String branch,
            final int most) {
        final String name = checkedName(own);
        final String text;
        try {
            final String commit = remote.fetch(name, own, branch);
            text =
                    remote.read(name, commit, FILE)
                            .orElseThrow(
                                    () ->
                                            new StoreException.Unreadable(
                                                    name, FILE + " is missing"));
        } catch (final GitFailure | IllegalArgumentException e) {
            throw GitStore.refusal(name, e);
        }
        final Project project;
        try {
            project = Project.read(text, Set.of());
        } catch (final IllegalArgumentException e) {
            throw new StoreException.Unreadable(name, GitRunner.scrubbed(e.getMessage()));
        }
        if (!project.own().equals(name)) {
            throw new StoreException.Unreadable(
                    name,
                    FILE + " names its own repository '" + project.own() + "', not '" + name + "'");
        }
        if (project.centrals().size() > most) {
            throw new StoreException.Unreadable(
                    name,
                    FILE
                            + " names "
                            + project.centrals().size()
                            + " repositories, more than "
                            + most);
        }
        final List<Repository> repositories = new ArrayList<>();
        repositories.add(new Repository(name, own, branch));
        for (final Map.Entry<String, String> central : project.centrals().entrySet()) {
            if (!central.getKey().equals(name)) {
                repositories.add(repository(name, central.getKey(), central.getValue()));
            }
        }
        return List.copyOf(repositories);
    }

    private static Repository repository(
            final String own, final String name, final String central) {
        try {
            return new Repository(name, new CloneUrl(central), null);
        } catch (final IllegalArgumentException e) {
            throw new StoreException.Unreadable(
                    own, FILE + ": repository " + name + ": " + GitRunner.scrubbed(e.getMessage()));
        }
    }

    /**
     * Answers the own repository's name, its URL's last part, checked before anything is fetched.
     *
     * @param own the own repository's clone URL
     * @return its name
     * @throws IllegalArgumentException if the last part is no repository name
     */
    static String checkedName(final CloneUrl own) {
        final String name = name(own);
        try {
            return Ids.name(name);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "The clone URL's last part is no repository name (" + e.getMessage() + ")", e);
        }
    }

    /** A repository's name as {@code project.yml}'s readers give it: its URL's last part. */
    private static String name(final CloneUrl url) {
        final String value = url.value();
        final String last =
                value.substring(Math.max(value.lastIndexOf('/'), value.lastIndexOf(':')) + 1);
        return last.endsWith(".git") ? last.substring(0, last.length() - ".git".length()) : last;
    }
}
