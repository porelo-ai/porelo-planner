package org.fuin.porelo.planner.store.git;

import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.TrackedStores;
import org.fuin.porelo.planner.store.files.ProjectLock;
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
        return new Opened(
                repositories,
                tracked(
                        remote,
                        name,
                        commit,
                        repositories,
                        // The file names its own repository, so its centrals hold it.
                        repository(
                                name,
                                name,
                                java.util.Objects.requireNonNull(project.centrals().get(name))),
                        tracker));
    }

    private static TrackedProject tracked(
            final Remote remote,
            final String name,
            final String commit,
            final List<Repository> repositories,
            final Repository central,
            final Project.Tracker tracker) {
        URI home = null;
        if (!Project.Tracker.LOCAL.equals(tracker.kind())) {
            // A local tracker asks no forge, so its repositories may stand anywhere.
            home = home(name, repositories.get(0).url());
            // The file's own central names where the stories live for every other reader of it.
            if (!home.equals(home(name, central.url()))) {
                throw new StoreException.Unreadable(
                        name,
                        FILE
                                + ": the own repository's central stands elsewhere than the own"
                                + " repository, which a tracker needs");
            }
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
        }
        final org.fuin.porelo.planner.store.git.stories.Workflow workflow;
        final String work;
        try {
            workflow =
                    org.fuin.porelo.planner.store.git.stories.Workflow.read(
                            remote.read(name, commit, "stories/workflow.yml")
                                    .orElse(TRACKED_WORKFLOW));
            work = remote.read(name, commit, WorkReader.FILE).orElse(TRACKED_WORK);
        } catch (final GitFailure | IllegalArgumentException e) {
            throw GitStore.refusal(name, e);
        }
        return new TrackedProject(
                home == null ? null : URI.create("https://" + home.getHost()),
                tracker.owner(),
                tracker.kind(),
                tracker.project(),
                repositories.stream().map(Repository::name).toList(),
                workflow.board(),
                WorkReader.read(work),
                commit,
                new TrackedProject.StoryStates() {
                    @Override
                    public @org.jspecify.annotations.Nullable String storyState(
                            final List<String> issueStates) {
                        return workflow.storyState(null, issueStates);
                    }

                    @Override
                    public java.util.Optional<org.fuin.porelo.planner.store.Warning> behind(
                            final String storyState,
                            final List<String> ids,
                            final List<String> issueStates) {
                        return workflow.storyRule()
                                .flatMap(rule -> rule.behind(storyState, ids, issueStates));
                    }

                    @Override
                    public java.util.Optional<String> fault() {
                        return workflow.storyRule().isEmpty()
                                ? java.util.Optional.of(
                                        org.fuin.porelo.planner.core.StoryState.NO_RULE)
                                : java.util.Optional.empty();
                    }
                });
    }

    /**
     * The workflow of a tracked project whose own repository keeps no {@code stories/workflow.yml}:
     * its stories and issues live in the tracker, and the tracker's Status options are checked
     * against these states at every read, so a state the tracker lacks is named as a fault.
     */
    static final String TRACKED_WORKFLOW =
            """
            states:
              - {name: New, closed: false}
              - {name: Accepted, closed: false}
              - {name: In analysis, closed: false}
              - {name: Analyzed, closed: false}
              - {name: Under estimation, closed: false}
              - {name: Estimated, closed: false}
              - {name: Backlog, closed: false}
              - {name: Implementing, closed: false, measured: true}
              - {name: Reading, closed: false, measured: true}
              - {name: Reworking, closed: false, measured: true}
              - {name: Review, closed: false, measured: true}
              - {name: Rework, closed: false}
              - {name: Finished, closed: true, measured: true}
              - {name: Rejected, closed: true}
            phases:
              - {name: Ideas, states: [New, Accepted]}
              - {name: Analysis, states: [In analysis, Analyzed]}
              - {name: Estimation, states: [Under estimation, Estimated]}
              - {name: Backlog, states: [Backlog]}
              - {name: Build, states: [Implementing, Reading, Reworking, Review, Rework]}
              - {name: Done, states: [Finished, Rejected]}
            """;

    /**
     * The work rules of a tracked project whose own repository keeps no {@code stories/work.yml}:
     * which states work starts from, where it leads, its modes, orders and models.
     */
    static final String TRACKED_WORK =
            """
            work:
              In analysis:
                from: [New, Accepted]
                done: Analyzed
                per: repository
                modes: [UNATTENDED, AGENT]
                result: ANALYSIS
                order: >-
                  Analyze the issue of {story} given below: read the code it names and give its plan, its size and what only the
                  operator can decide; then end with your outcome.
                models: {claude: claude-opus-5-5, codex: gpt-6.1-sol}
              Under estimation:
                from: [Analyzed]
                done: Estimated
                per: repository
                modes: [UNATTENDED, AGENT]
                result: ESTIMATE
                order: >-
                  Estimate the issue of {story} given below: read its plan and the code it names, give its size (XS to XL) against
                  the measured velocity, name what makes it uncertain; then end with your outcome.
                models: {claude: claude-opus-5-5, codex: gpt-6.1-sol}
              Implementing:
                from: [Backlog, Rework]
                done: Review
                then: Reading
                per: repository
                modes: [UNATTENDED, AGENT]
                order: >-
                  Build {issues} of {story}, each issue in a sub-agent of its own named by its id, as the issue's plan says: each
                  test red first, then green; review each, commit, and end with your outcome.
                models: {codex: gpt-6.1-sol, claude: claude-opus-5-5}
              Reading:
                from: [Implementing, Reworking, Review]
                done: Review
                blocked: Reworking
                per: repository
                modes: [UNATTENDED]
                order: >-
                  Read the changes {tasks} made for {issues} of {story}, change nothing, and end with one Findings: block, one
                  finding a line: <n>. <blocking|note> | <file or -> | <line or -> | <text>.
                models: {agy: gemini-4-argon-high}
              Reworking:
                from: [Reading]
                done: Review
                then: Reading
                per: repository
                modes: [UNATTENDED, AGENT]
                order: >-
                  Fix the blocking findings the reading of {issues} of {story} gave, each issue's in a sub-agent of its own named by
                  its id, each with a test seen to fail first; commit, and end with your outcome.
                models: {codex: gpt-6.1-sol, claude: claude-opus-5-5}
            """;

    /**
     * The ssh form {@code <user>@<host>:<owner>/<repository>}, whose host is asked over https like
     * an https address's.
     */
    private static final java.util.regex.Pattern SCP_HOME =
            java.util.regex.Pattern.compile(
                    "[A-Za-z0-9._-]+@([A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?):"
                            + "([A-Za-z0-9][A-Za-z0-9._-]*)/[A-Za-z0-9._-]+");

    /**
     * The https host and owner a repository stands under, {@code https://<host>/<owner>}: a tracker
     * is asked with the token of the own repository's host, so every repository must stand there.
     * The one rule for an address a tracked project may name, also for whatever makes a project
     * tracked.
     *
     * @param own the own repository's name, for a refusal
     * @param url a repository's clone URL
     * @return its host and owner, in lower case
     * @throws StoreException.Unreadable when a tracked project may not name it
     */
    public static URI home(final String own, final CloneUrl url) {
        final java.util.regex.Matcher scp = SCP_HOME.matcher(url.value());
        if (scp.matches()) {
            return URI.create(
                    "https://"
                            + scp.group(1).toLowerCase(java.util.Locale.ROOT)
                            + "/"
                            + scp.group(3).toLowerCase(java.util.Locale.ROOT));
        }
        final URI uri;
        try {
            uri = new URI(url.value());
        } catch (final java.net.URISyntaxException e) {
            throw new StoreException.Unreadable(
                    own,
                    FILE
                            + ": a project a tracker keeps names its repositories by https or"
                            + " ssh address <user>@<host>:<owner>/<repository>");
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
                    FILE
                            + ": a project a tracker keeps names its repositories by https or"
                            + " ssh address <user>@<host>:<owner>/<repository>");
        }
        return URI.create(
                "https://"
                        + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                        + "/"
                        + parts[1].toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Gives a tracked project again at each read of its board: the own repository's head is asked
     * for, and its files are read anew only when it moved.
     */
    static final class Again implements TrackedStores.Source {

        private final Remote remote;

        private final ProjectLock lock;

        private final CloneUrl own;

        private final @Nullable String branch;

        private final int most;

        private TrackedProject kept;

        /**
         * Makes the source.
         *
         * @param remote reaches the repositories
         * @param lock holds the project's clones while its files are fetched
         * @param own the own repository's clone URL, as the window names it
         * @param branch the own repository's branch, or null for the remote's default
         * @param most the most repositories a project may have, its own included
         * @param first the project as it was read at opening
         */
        Again(
                final Remote remote,
                final ProjectLock lock,
                final CloneUrl own,
                final @Nullable String branch,
                final int most,
                final TrackedProject first) {
            this.remote = remote;
            this.lock = lock;
            this.own = own;
            this.branch = branch;
            this.most = most;
            this.kept = first;
        }

        @Override
        public synchronized TrackedProject current() {
            final String name = checkedName(own);
            try (ProjectLock.Held held = lock.acquire()) {
                final String head;
                try {
                    head = remote.head(name, own, branch);
                } catch (final GitFailure | IllegalArgumentException e) {
                    throw GitStore.refusal(name, e);
                }
                if (head.equals(kept.filesCommit())) {
                    return kept;
                }
                final TrackedProject read = read(remote, own, branch, most).tracked();
                if (read == null) {
                    throw new StoreException.Unreadable(
                            name, FILE + " names no tracker any more; open the project again");
                }
                kept = read;
                return read;
            } catch (final UncheckedIOException | IllegalStateException e) {
                // The cache directory or its lock refused: told as the store's fault, free of
                // paths.
                throw new StoreException.Unreadable(name, GitRunner.scrubbed(e.getMessage()));
            }
        }
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
