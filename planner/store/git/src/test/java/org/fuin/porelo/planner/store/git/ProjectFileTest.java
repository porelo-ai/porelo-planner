package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.planner.store.StoreException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ProjectFileTest {

    private static final CloneUrl OWN = new CloneUrl("git@github.com:porelo-ai/porelo-project.git");

    private static final String PROJECT =
            """
            project:
                name: "porelo"
                central: "git@github.com:porelo-ai/porelo-project.git"
            repositories:
                core:
                    central: "git@github.com:porelo-ai/porelo.git"
                    issues: ["B", "P"]
                frontend:
                    central: "https://github.com/porelo-ai/porelo-frontend"
                    issues: ["F"]
            """;

    /** Serves the own repository's files at one commit and records what was fetched. */
    private static final class Files implements Remote {

        private final Map<String, String> files = new HashMap<>();

        private final Map<String, String> fetched = new HashMap<>();

        @Override
        public String head(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            return "c1";
        }

        @Override
        public String fetch(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            fetched.put(repository, url.value() + "@" + branch);
            return "c1";
        }

        @Override
        public Optional<String> fetched(final String repository) {
            return Optional.empty();
        }

        @Override
        public Optional<String> read(
                final String repository, final String commit, final String path) {
            return Optional.ofNullable(files.get(repository + "@" + commit + ":" + path));
        }

        @Override
        public List<String> list(
                final String repository, final String commit, final String directory) {
            return List.of();
        }

        @Override
        public List<String> directories(
                final String repository, final String commit, final String directory) {
            return List.of();
        }

        @Override
        public List<Found> grep(
                final String repository,
                final String commit,
                final java.util.Collection<String> words) {
            throw new UnsupportedOperationException();
        }
    }

    private static Files with(final String project) {
        final Files remote = new Files();
        remote.files.put("porelo-project@c1:project.yml", project);
        return remote;
    }

    @Test
    void theOwnRepositoryComesFirstAndTheOthersFollowInTheFilesOrder() {
        final Files remote = with(PROJECT);
        final List<Repository> repositories = ProjectFile.repositories(remote, OWN, "main", 10);
        assertThat(repositories)
                .as(
                        "the own repository first, with the branch the window named; the others on"
                                + " their default branch")
                .containsExactly(
                        new Repository("porelo-project", OWN, "main"),
                        new Repository(
                                "porelo",
                                new CloneUrl("git@github.com:porelo-ai/porelo.git"),
                                null),
                        new Repository(
                                "porelo-frontend",
                                new CloneUrl("https://github.com/porelo-ai/porelo-frontend"),
                                null));
        assertThat(remote.fetched)
                .as("only the own repository fetched, on its branch")
                .containsExactly(Map.entry("porelo-project", OWN.value() + "@main"));
    }

    @Test
    void aMissingProjectFileIsRefusedNamingIt() {
        // Without it the planner would show a project of one repository as if it were whole.
        assertThatThrownBy(() -> ProjectFile.repositories(new Files(), OWN, null, 10))
                .as("no project.yml")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("porelo-project")
                .hasMessageContaining("project.yml is missing");
    }

    @Test
    void aProjectFileNamingAnotherOwnRepositoryIsRefused() {
        // The window named one repository; a file naming another would build a board the window
        // did not ask for.
        final Files remote =
                with(PROJECT.replace("porelo-ai/porelo-project.git", "porelo-ai/porelo-other.git"));
        assertThatThrownBy(() -> ProjectFile.repositories(remote, OWN, null, 10))
                .as("another own repository")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining(
                        "project.yml names its own repository 'porelo-other', not 'porelo-project'");
    }

    @Test
    void aProjectFileTheLibraryRefusesIsUnreadableNotACrash() {
        final Files remote = with("project: [not, a, mapping]\n");
        assertThatThrownBy(() -> ProjectFile.repositories(remote, OWN, null, 10))
                .as("a broken project.yml")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("porelo-project");
    }

    @Test
    void moreRepositoriesThanTheBoundAreRefused() {
        // Every repository is a clone and a fetch on each check; a file naming thousands would fill
        // the disk and the network.
        assertThatThrownBy(() -> ProjectFile.repositories(with(PROJECT), OWN, null, 2))
                .as("three repositories over a bound of two")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("project.yml names 3 repositories, more than 2");
    }

    @Test
    void aUrlWhoseLastPartIsNoRepositoryNameIsRefusedBeforeAnythingIsFetched() {
        // A fetch would run with the person's login for a name the store cannot even report.
        final Files remote = with(PROJECT);
        assertThatThrownBy(
                        () ->
                                ProjectFile.repositories(
                                        remote,
                                        new CloneUrl("https://github.com/porelo-ai/MyRepo.git"),
                                        null,
                                        10))
                .as("an upper-case repository name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no repository name");
        assertThat(remote.fetched).as("nothing fetched").isEmpty();
    }
}
