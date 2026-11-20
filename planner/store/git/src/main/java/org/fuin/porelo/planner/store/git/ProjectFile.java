package org.fuin.porelo.planner.store.git;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.git.stories.Project;
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
        return read(remote, own, branch, most).repositories();
    }

    /**
     * What the own repository's files say of the project: its repositories and the tracker that
     * keeps its stories and issues, if one does.
     *
     * @param repositories the project's repositories, its own first
     * @param tracked the project as its tracker's store opens it, or null when its files keep the
     *     stories and issues
     */
    public record Opened(List<Repository> repositories, @Nullable TrackedProject tracked) {

        /** Copies the list. */
        public Opened {
            repositories = List.copyOf(repositories);
        }
    }

    /**
     * Fetches the project's own repository and reads its {@code project.yml}; for a project a
     * tracker keeps, also its workflow and work rules, after checking that every repository and the
     * tracker's project stand on the own repository's host under its owner.
     *
     * @param remote reaches the repositories
     * @param own the own repository's clone URL, as the window names it
     * @param branch the own repository's branch, or null for the remote's default
     * @param most the most repositories a project may have, its own included
     * @return what the files say
     * @throws IllegalArgumentException if the URL's last part is no repository name
     */
    public static Opened read(
            final Remote remote,
            final CloneUrl own,
            final @Nullable String branch,
            final int most) {
        final String name = checkedName(own);
        final String text;
        final String commit;
        try {
            commit = remote.fetchToOpen(name, own, branch);
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
        final Project.Tracker tracker;
        try {
            tracker = Project.tracker(text).orElse(null);
        } catch (final IllegalArgumentException e) {
            throw new StoreException.Unreadable(name, GitRunner.scrubbed(e.getMessage()));
        }
        if (tracker == null) {
            return new Opened(repositories, null);
        }
        return new Opened(repositories, tracked(remote, name, commit, repositories, tracker));
    }

    private static TrackedProject tracked(
            final Remote remote,
            final String name,
            final String commit,
            final List<Repository> repositories,
            final Project.Tracker tracker) {
        final URI home = home(name, repositories.get(0).url());
        for (final Repository repository : repositories) {
            if (!home.equals(home(name, repository.url()))) {
                throw new StoreException.Unreadable(
                        name,
                        FILE
                                + ": repository "
                                + repository.name()
                                + " stands elsewhere than the own repository, which a tracker"
                                + " needs");
            }
        }
        final String owner = home.getPath().substring(1);
        if (!tracker.owner().equalsIgnoreCase(owner)) {
            throw new StoreException.Unreadable(
                    name, FILE + ": the tracker's project belongs to another owner");
        }
        final org.fuin.porelo.planner.store.git.stories.Workflow workflow;
        final String work;
        try {
            workflow =
                    org.fuin.porelo.planner.store.git.stories.Workflow.read(
                            remote.read(name, commit, "stories/workflow.yml")
                                    .orElseThrow(() -> new StoreException.WorkflowMissing(name)));
            work = remote.read(name, commit, WorkReader.FILE).orElse(null);
        } catch (final GitFailure | IllegalArgumentException e) {
            throw GitStore.refusal(name, e);
        }
        return new TrackedProject(
                URI.create("https://" + home.getHost()),
                tracker.owner(),
                tracker.kind(),
                tracker.project(),
                repositories.stream().map(Repository::name).toList(),
                new Workflow(
                        workflow.states(),
                        workflow.phases().stream()
                                .map(phase -> new Workflow.Phase(phase.name(), phase.states()))
                                .toList(),
                        workflow.states().stream().filter(workflow::closed).toList()),
                WorkReader.read(work),
                commit,
                issueStates -> workflow.storyState(null, issueStates));
    }

    /**
     * The https host and owner a repository stands under, {@code https://<host>/<owner>}: a tracker
     * is asked with the token of the own repository's host, so every repository must stand there.
     */
    private static URI home(final String own, final CloneUrl url) {
        final URI uri;
        try {
            uri = new URI(url.value());
        } catch (final java.net.URISyntaxException e) {
            throw new StoreException.Unreadable(
                    own,
                    FILE + ": a project a tracker keeps names its repositories by https address");
        }
        final String path = uri.getPath() == null ? "" : uri.getPath();
        final String[] parts = path.split("/", -1);
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getQuery() != null
                || uri.getFragment() != null
                || parts.length != 3
                || parts[1].isEmpty()) {
            throw new StoreException.Unreadable(
                    own,
                    FILE + ": a project a tracker keeps names its repositories by https address");
        }
        return URI.create(
                "https://"
                        + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                        + "/"
                        + parts[1].toLowerCase(java.util.Locale.ROOT));
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
