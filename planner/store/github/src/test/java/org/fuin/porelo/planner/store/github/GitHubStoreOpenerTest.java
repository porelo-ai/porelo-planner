package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl.PageInfo;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectFields;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectItems;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GitHubStoreOpenerTest {

    private static final Workflow WORKFLOW =
            new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished"));

    private final List<URI> asked = new ArrayList<>();

    private final List<ProjectConfig> read = new ArrayList<>();

    private GitHubStoreOpener opener() {
        return new GitHubStoreOpener(
                origin -> {
                    asked.add(origin);
                    return "a-token";
                },
                states ->
                        config -> {
                            read.add(config);
                            throw new StoreException.Unreachable(config.own(), "read in this test");
                        },
                () -> BoardWriter.NONE,
                Duration.ofSeconds(1));
    }

    private static TrackedProject project(final String forge, final String kind) {
        return project(forge, kind, WORKFLOW, "c1");
    }

    private static TrackedProject project(
            final String forge, final String kind, final Workflow workflow, final String commit) {
        return new TrackedProject(
                URI.create(forge),
                "example-org",
                kind,
                "7",
                List.of("example-project", "example-lib"),
                workflow,
                new Work(java.util.Map.of(), List.of()),
                commit,
                states -> null);
    }

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    /** GitHub's answers for an empty project, every call counted; any write fails the test. */
    private final List<String> calls = new ArrayList<>();

    private ProjectQueries queries() {
        return new ProjectQueries() {
            @Override
            public ProjectFields projectFields(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                calls.add("projectFields");
                return new ProjectFields(
                        "PVT_1", "Plan", Instant.EPOCH, List.of(), new PageInfo(false, null), COST);
            }

            @Override
            public ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                calls.add("projectItems");
                return new ProjectItems(List.of(), new PageInfo(false, null), COST);
            }
        };
    }

    private static ProjectWrites noWrites() {
        return (ProjectWrites)
                Proxy.newProxyInstance(
                        ProjectWrites.class.getClassLoader(),
                        new Class<?>[] {ProjectWrites.class},
                        (proxy, method, args) -> {
                            throw new AssertionError("A read called " + method.getName());
                        });
    }

    private GitHubStoreOpener overGitHub() {
        return new GitHubStoreOpener(
                origin -> {
                    asked.add(origin);
                    return "a-token";
                },
                queries(),
                noWrites(),
                Duration.ofSeconds(1));
    }

    @Test
    void aGitHubProjectOpensItsStoreAndSendsGitHubNothingWhileOpening() {
        // Harm: opening a project already calls GitHub, or calls it for another project.
        final TrackedProject project = project("https://github.com", "github");
        final var store = opener().open(project, () -> project);

        assertThat(store).as("the GitHub store").isInstanceOf(GitHubStore.class);
        assertThat(read).as("nothing read while opening").isEmpty();
        assertThatThrownBy(store::board)
                .as("the first board reads")
                .isInstanceOf(StoreException.class);
        assertThat(read)
                .as("the project the file names, on its forge")
                .extracting(c -> c.project(), c -> c.forge(), c -> c.repositories())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                new ProjectRef("example-org", 7),
                                URI.create("https://github.com"),
                                List.of("example-project", "example-lib")));
    }

    @Test
    void aTrackerOfAnotherKindOrOnAnotherForgeIsRefusedWithoutAToken() {
        // Harm: the person's GitHub token sent to a server that only says it keeps a GitHub
        // project.
        final TrackedProject gitlab = project("https://github.com", "gitlab");
        final TrackedProject elsewhere = project("https://forge.example.org", "github");
        assertThatThrownBy(() -> overGitHub().open(gitlab, () -> gitlab))
                .as("another kind")
                .isInstanceOf(StoreException.Unreadable.class);
        assertThatThrownBy(() -> overGitHub().open(elsewhere, () -> elsewhere))
                .as("another forge")
                .isInstanceOf(StoreException.Unreadable.class);
        assertThat(asked).as("no token asked").isEmpty();
        assertThat(calls).as("nothing sent to GitHub").isEmpty();
    }

    @Test
    void theTokenIsAskedForOnlyWhenTheBoardIsFirstRead() {
        // Harm: the person's token taken from the vault for a project merely opened, or the
        // board read with no token at all.
        final TrackedProject project = project("https://github.com", "github");
        final var store = overGitHub().open(project, () -> project);

        assertThat(asked).as("no token asked while opening").isEmpty();
        assertThat(calls).as("nothing sent while opening").isEmpty();

        store.board();

        assertThat(asked)
                .as("the token asked for GitHub at the first board read")
                .containsExactly(URI.create("https://github.com"));
        assertThat(calls)
                .as("the project's fields and items read")
                .containsExactly("projectFields", "projectItems");
    }

    @Test
    void aWorkflowChangedInTheOwnRepositoryIsSeenOnRefreshWithAnotherRevision() {
        // Harm: a workflow or work rule changed in the own repository not seen until the project
        // is opened anew, and the window told nothing changed.
        final TrackedProject first = project("https://github.com", "github");
        final AtomicReference<TrackedProject> files = new AtomicReference<>(first);
        final var store = overGitHub().open(first, files::get);
        final Board before = store.board();

        files.set(
                project(
                        "https://github.com",
                        "github",
                        new Workflow(
                                List.of("Idea", "Doing", "Finished"),
                                List.of(),
                                List.of("Finished")),
                        "c2"));
        final Board after = store.refresh();

        assertThat(after.workflow().states())
                .as("the workflow of the moved head")
                .containsExactly("Idea", "Doing", "Finished");
        assertThat(after.revision())
                .as("the revision after the files changed")
                .isNotEqualTo(before.revision());
    }

    @Test
    void aRefusalOfThePersonsAccessOutsideTheReadersCallsNamesTheMissingAccess() {
        // Harm: a refused token told as GitHub unreachable, so the window never offers to hand on
        // a token with the rights the call needs.
        final GitHubStoreOpener opener =
                new GitHubStoreOpener(
                        origin -> "a-token",
                        states ->
                                config ->
                                        new Snapshot(
                                                new Board(
                                                        new Revision("r1"),
                                                        WORKFLOW,
                                                        List.of(),
                                                        List.of(),
                                                        new Work(java.util.Map.of(), List.of()),
                                                        config.repositories()),
                                                java.util.Map.of(),
                                                config),
                        () ->
                                new BoardWriter() {
                                    @Override
                                    public void save(
                                            final Snapshot current,
                                            final Revision revision,
                                            final Writes writes) {
                                        throw GitHubExceptions.refused(
                                                403, "Resource not accessible");
                                    }
                                },
                        Duration.ofSeconds(1));
        final TrackedProject project = project("https://github.com", "github");
        final var store = opener.open(project, () -> project);

        assertThatThrownBy(
                        () ->
                                store.save(
                                        new Revision("r1"),
                                        new Writes(
                                                java.util.Map.of("PL1", "Idea"),
                                                java.util.Map.of(),
                                                List.of())))
                .as("GitHub's refusal of the token")
                .isInstanceOf(StoreException.NoForgeAccess.class);
    }

    @Test
    void anOpenedGitHubProjectWritesAMoveThroughItsWriter() {
        // Harm: a move on a GitHub project refused as not offered, or answered as saved while
        // nothing reached GitHub.
        final List<Writes> written = new ArrayList<>();
        final GitHubStoreOpener opener =
                new GitHubStoreOpener(
                        origin -> "a-token",
                        states ->
                                config ->
                                        new Snapshot(
                                                new Board(
                                                        new Revision("r1"),
                                                        WORKFLOW,
                                                        List.of(),
                                                        List.of(),
                                                        new Work(java.util.Map.of(), List.of()),
                                                        config.repositories()),
                                                java.util.Map.of(),
                                                config),
                        () ->
                                new BoardWriter() {
                                    @Override
                                    public void save(
                                            final Snapshot current,
                                            final Revision revision,
                                            final Writes writes) {
                                        written.add(writes);
                                    }
                                },
                        Duration.ofSeconds(1));
        final TrackedProject project = project("https://github.com", "github");
        final var store = opener.open(project, () -> project);
        final Writes move =
                new Writes(java.util.Map.of("PL1", "Idea"), java.util.Map.of(), List.of());

        store.save(new Revision("r1"), move);

        assertThat(written).as("the writes the writer was given").containsExactly(move);
    }

    @Test
    void aReadFailingAfterAMoveWasWrittenIsPartlySaved() {
        // Harm: a move GitHub saved answered as GitHub unreachable, so the window says nothing
        // was saved and makes it again.
        final int[] reads = new int[1];
        final GitHubStoreOpener opener =
                new GitHubStoreOpener(
                        origin -> "a-token",
                        states ->
                                config -> {
                                    if (reads[0]++ > 0) {
                                        throw new StoreException.Unreachable(
                                                config.own(), "read after the write in this test");
                                    }
                                    return new Snapshot(
                                            new Board(
                                                    new Revision("r1"),
                                                    WORKFLOW,
                                                    List.of(),
                                                    List.of(),
                                                    new Work(java.util.Map.of(), List.of()),
                                                    config.repositories()),
                                            java.util.Map.of(),
                                            config);
                                },
                        () ->
                                new BoardWriter() {
                                    @Override
                                    public void save(
                                            final Snapshot current,
                                            final Revision revision,
                                            final Writes writes) {
                                        // GitHub took the move.
                                    }
                                },
                        Duration.ofSeconds(1));
        final TrackedProject project = project("https://github.com", "github");
        final var store = opener.open(project, () -> project);
        store.board();

        assertThatThrownBy(
                        () ->
                                store.save(
                                        new Revision("r1"),
                                        new Writes(
                                                java.util.Map.of("PL1", "Idea"),
                                                java.util.Map.of(),
                                                List.of())))
                .as("a move written whose following read failed")
                .isInstanceOf(StoreException.PartlySaved.class);
    }
}
