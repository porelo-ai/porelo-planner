package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
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
                project ->
                        config -> {
                            read.add(config);
                            throw new StoreException.Unreachable(config.own(), "read in this test");
                        },
                project -> BoardWriter.NONE,
                Duration.ofSeconds(1));
    }

    private static TrackedProject project(final String forge, final String kind) {
        return new TrackedProject(
                URI.create(forge),
                "example-org",
                kind,
                "7",
                List.of("example-project", "example-lib"),
                WORKFLOW,
                new Work(java.util.Map.of(), List.of()),
                "c1",
                states -> null);
    }

    @Test
    void aGitHubProjectOpensItsStoreAndSendsGitHubNothingWhileOpening() {
        // Harm: opening a project already calls GitHub, or calls it for another project.
        final var store = opener().open(project("https://github.com", "github"));

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
        assertThatThrownBy(() -> opener().open(project("https://github.com", "gitlab")))
                .as("another kind")
                .isInstanceOf(StoreException.Unreadable.class);
        assertThatThrownBy(() -> opener().open(project("https://forge.example.org", "github")))
                .as("another forge")
                .isInstanceOf(StoreException.Unreadable.class);
        assertThat(asked).as("no token asked").isEmpty();
    }

    @Test
    void anOpenedGitHubProjectWritesAMoveThroughItsWriter() {
        // Harm: a move on a GitHub project refused as not offered, or answered as saved while
        // nothing reached GitHub.
        final List<Writes> written = new ArrayList<>();
        final GitHubStoreOpener opener =
                new GitHubStoreOpener(
                        origin -> "a-token",
                        project ->
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
                        project ->
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
        final var store = opener.open(project("https://github.com", "github"));
        final Writes move =
                new Writes(java.util.Map.of("PL1", "Idea"), java.util.Map.of(), List.of());

        store.save(new Revision("r1"), move);

        assertThat(written).as("the writes the writer was given").containsExactly(move);
    }
}
