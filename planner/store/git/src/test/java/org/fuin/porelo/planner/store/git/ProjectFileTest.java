package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.files.ProjectLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

        private String commit = "c1";

        private int fetches;

        @Override
        public String head(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            return commit;
        }

        @Override
        public String fetch(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            fetched.put(repository, url.value() + "@" + branch);
            fetches++;
            return commit;
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
                        "the own repository first, with the branch the window named, read in the"
                                + " project's name; the others on their default branch, each read"
                                + " in its key")
                .containsExactly(
                        new Repository("porelo-project", OWN, "main", "porelo"),
                        new Repository(
                                "porelo",
                                new CloneUrl("git@github.com:porelo-ai/porelo.git"),
                                null,
                                "core"),
                        new Repository(
                                "porelo-frontend",
                                new CloneUrl("https://github.com/porelo-ai/porelo-frontend"),
                                null,
                                "frontend"));
        assertThat(remote.fetched)
                .as("only the own repository fetched, on its branch")
                .containsExactly(Map.entry("porelo-project", OWN.value() + "@main"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "name: \"porelo\"|name: \"Porelo Project\"|project.yml project name",
                "name: \"porelo\"||missing required field name",
                "core:|Core:|project.yml repositories.Core",
                "core:|porelo:|the key is the project's name",
            })
    void aProjectWhoseFoldersCannotBeToldIsRefused(
            final String from, final @Nullable String to, final String words) {
        // Harm: a task's brief points at a folder Porelo never makes, or at another repository's.
        final Files remote = with(PROJECT.replace(from, to == null ? "" : to));
        assertThatThrownBy(() -> ProjectFile.repositories(remote, OWN, null, 10))
                .as("a project whose folders cannot be told")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("porelo-project")
                .hasMessageContaining(words);
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

    // Prevents a credential in a central project.yml names from reaching the window or the log.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "me:s3cret@git.example.org:porelo-ai/porelo.git | s3cret",
                "https://me:pa@ssword@git.example.org/porelo-ai/porelo.git | ssword",
                "https://me:pa/ssword@git.example.org/porelo-ai/porelo.git | ssword",
                "https://ghp_secretToken@git.example.org/porelo-ai/porelo.git | secretToken",
                "https://git.example.org/porelo-ai/porelo.git?access_token=s3cret | s3cret"
            })
    void aRefusedCentralsCredentialNeverReachesTheRefusal(
            final String central, final String secret) {
        final Files remote = with(PROJECT.replace("git@github.com:porelo-ai/porelo.git", central));

        assertThatThrownBy(() -> ProjectFile.repositories(remote, OWN, null, 10))
                .as("refused naming the key, never the credential %s", secret)
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("central")
                .hasMessageNotContaining(secret);
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

    private static final CloneUrl HTTPS_OWN =
            new CloneUrl("https://github.com/porelo-ai/porelo-project.git");

    private static final String TRACKED =
            """
            project:
                name: "porelo"
                central: "https://github.com/porelo-ai/porelo-project.git"
            repositories:
                frontend:
                    central: "https://github.com/porelo-ai/porelo-frontend"
                    issues: ["F"]
            tracker:
                kind: github
                project: porelo-ai/7
            """;

    private static Files tracked(final String project) throws java.io.IOException {
        final Files remote = with(project);
        remote.files.put(
                "porelo-project@c1:stories/workflow.yml",
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("src/test/resources/workflow.yml")));
        return remote;
    }

    @Test
    void aTrackedProjectIsHandedOnWithItsForgeOwnerAndFiles() throws Exception {
        // Harm: a project a tracker keeps opened as files, or asked of another forge than its own.
        final ProjectFile.Opened opened = ProjectFile.read(tracked(TRACKED), HTTPS_OWN, "main", 10);

        final org.fuin.porelo.planner.store.TrackedProject project = opened.tracked();
        assertThat(project).as("a tracker named").isNotNull();
        assertThat(project.forge())
                .as("the own repository's host")
                .isEqualTo(java.net.URI.create("https://github.com"));
        assertThat(
                        List.of(
                                project.owner(),
                                project.kind(),
                                project.project(),
                                project.filesCommit()))
                .as("the tracker as the file names it, and the commit its files were read at")
                .containsExactly("porelo-ai", "github", "7", "c1");
        assertThat(project.repositories())
                .as("the repositories, the own first, each with the folder Porelo reads it in")
                .containsExactly(
                        new ProjectRepository("porelo-project", "porelo"),
                        new ProjectRepository("porelo-frontend", "frontend"));
        assertThat(ProjectFile.read(with(PROJECT), OWN, null, 10).tracked())
                .as("a project without a tracker stays the Git store's")
                .isNull();
    }

    @Test
    void aLocalTrackerIsHandedOnWithoutForgeOrOwner() throws Exception {
        // Harm: a project kept in a local directory cannot open, or is asked of a forge.
        final String text =
                TRACKED.replace("kind: github\n    project: porelo-ai/7", "kind: local")
                        .replace(
                                "https://github.com/porelo-ai/porelo-frontend",
                                "https://forge.example.org/team/porelo-frontend");
        final org.fuin.porelo.planner.store.TrackedProject project =
                ProjectFile.read(tracked(text), HTTPS_OWN, "main", 10).tracked();

        assertThat(project).as("a tracker named").isNotNull();
        assertThat(project.forge()).as("no forge is asked").isNull();
        assertThat(List.of(project.kind(), project.owner(), project.project()))
                .as("the kind alone")
                .containsExactly("local", "", "");
        assertThat(project.repositories())
                .as("repositories on any host")
                .containsExactly(
                        new ProjectRepository("porelo-project", "porelo"),
                        new ProjectRepository("porelo-frontend", "frontend"));
    }

    @Test
    void aTrackedProjectNamingItsRepositoriesBySshIsAskedOfTheirHttpsHost() throws Exception {
        // Harm: a project whose remotes are ssh cannot be kept by a tracker, or its token is asked
        // of another host than the one the repositories stand on.
        final String text =
                TRACKED.replace(
                                "https://github.com/porelo-ai/porelo-project.git",
                                "git@github.com:porelo-ai/porelo-project.git")
                        .replace(
                                "https://github.com/porelo-ai/porelo-frontend",
                                "git@GitHub.com:Porelo-AI/porelo-frontend.git");
        final org.fuin.porelo.planner.store.TrackedProject project =
                ProjectFile.read(
                                tracked(text),
                                new CloneUrl("git@github.com:porelo-ai/porelo-project.git"),
                                null,
                                10)
                        .tracked();

        assertThat(project).as("a tracker named").isNotNull();
        assertThat(List.of(project.forge().toString(), project.owner()))
                .as("the https host and the owner the ssh addresses name")
                .containsExactly("https://github.com", "porelo-ai");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "project: other-org/7|another owner's project",
                "project: porelo-ai/7\\n    extra: 1|a key the planner does not know",
                "project: porelo-ai|no number",
                "project: porelo-ai/0|a project number GitHub never gives",
            })
    void aTrackerTheFileShapesWronglyOrPointsElsewhereIsRefused(
            final String tracker, final String why) throws Exception {
        // Harm: the person's token used on a project of another owner, or a tracker read halfway.
        final String text = TRACKED.replace("project: porelo-ai/7", tracker.replace("\\n", "\n"));
        assertThatThrownBy(() -> ProjectFile.read(tracked(text), HTTPS_OWN, null, 10))
                .as(why)
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageNotContaining("other-org");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "https://gitlab.example.org/porelo-ai/porelo-frontend|another host",
                "https://github.com/other-org/porelo-frontend|another owner",
                "git@gitlab.example.org:porelo-ai/porelo-frontend.git|another host by ssh",
                "git@github.com:other-org/porelo-frontend.git|another owner by ssh",
                "git@github.com:porelo-ai/team/porelo-frontend.git|a deeper path by ssh",
                "ssh://git@github.com/porelo-ai/porelo-frontend|an ssh URL",
                "https://github.com:8443/porelo-ai/porelo-frontend|a port",
            })
    void aTrackedProjectsRepositoryElsewhereThanItsOwnIsRefused(
            final String central, final String why) throws Exception {
        // Harm: the token of the own repository's forge sent along for a repository elsewhere.
        final String text =
                TRACKED.replace("https://github.com/porelo-ai/porelo-frontend", central);
        assertThatThrownBy(() -> ProjectFile.read(tracked(text), HTTPS_OWN, null, 10))
                .as(why)
                .isInstanceOf(StoreException.Unreadable.class);
    }

    @Test
    void aTrackerOfAKindThatIsNoLowerCaseNameIsRefused() throws Exception {
        // Harm: a kind no store keeps read on as if the file were well formed.
        final String text = TRACKED.replace("kind: github", "kind: UPPER");
        assertThatThrownBy(() -> ProjectFile.read(tracked(text), HTTPS_OWN, null, 10))
                .as("a kind in capitals")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("kind");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "https://gitlab.example.org/porelo-ai/porelo-project.git|another host",
                "https://github.com/other-org/porelo-project.git|another owner",
            })
    void aTrackedProjectWhoseOwnCentralStandsElsewhereIsRefused(
            final String central, final String why) throws Exception {
        // Harm: the file tells every other reader the stories live elsewhere than the tracker the
        // person's token is used on.
        final String text =
                TRACKED.replace("https://github.com/porelo-ai/porelo-project.git", central);
        assertThatThrownBy(() -> ProjectFile.read(tracked(text), HTTPS_OWN, null, 10))
                .as(why)
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("own repository's central");
    }

    private static final String SHORT_WORKFLOW =
            """
            states:
              - {name: Idea, closed: false}
              - {name: Finished, closed: true}
            phases:
              - {name: All, states: [Idea, Finished]}
            """;

    @Test
    void aTrackedProjectWhoseKeyIsNoFolderIsRefusedAtOpeningAndAtEveryReadNamingTheKey()
            throws Exception {
        // Harm: a tracked project opened with a folder Porelo never makes, so its discussions read
        // nothing; the planner refuses the project and names the key to set right.
        final String broken = TRACKED.replace("frontend:", "Core:");
        assertThatThrownBy(() -> ProjectFile.read(tracked(broken), HTTPS_OWN, null, 10))
                .as("opened with a key that is no folder name")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("porelo-project")
                .hasMessageContaining("project.yml repositories.Core");

        final Files remote = tracked(TRACKED);
        final org.fuin.porelo.planner.store.TrackedProject first =
                java.util.Objects.requireNonNull(
                        ProjectFile.read(remote, HTTPS_OWN, null, 10).tracked());
        final ProjectFile.Again again =
                new ProjectFile.Again(remote, () -> () -> {}, HTTPS_OWN, null, 10, first);
        remote.commit = "c2";
        remote.files.put("porelo-project@c2:project.yml", broken);
        assertThatThrownBy(again::current)
                .as("the opened project's read once its head names such a key")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("project.yml repositories.Core");
    }

    @Test
    void aTrackedProjectsFilesAreReadAgainOnlyWhenTheOwnHeadMoved() throws Exception {
        // Harm: a workflow changed in the own repository never seen until the project is opened
        // anew, or the files fetched on every look at the board.
        final Files remote = tracked(TRACKED);
        final ProjectLock lock = () -> () -> {};
        final org.fuin.porelo.planner.store.TrackedProject first =
                java.util.Objects.requireNonNull(
                        ProjectFile.read(remote, HTTPS_OWN, null, 10).tracked());
        final ProjectFile.Again again =
                new ProjectFile.Again(remote, lock, HTTPS_OWN, null, 10, first);
        final int fetchesAtOpening = remote.fetches;

        assertThat(again.current()).as("the head where it was").isSameAs(first);
        assertThat(remote.fetches).as("nothing fetched again").isEqualTo(fetchesAtOpening);

        remote.commit = "c2";
        remote.files.put("porelo-project@c2:project.yml", TRACKED);
        remote.files.put("porelo-project@c2:stories/workflow.yml", SHORT_WORKFLOW);
        final org.fuin.porelo.planner.store.TrackedProject moved = again.current();

        assertThat(moved.filesCommit()).as("the commit the files are read at").isEqualTo("c2");
        assertThat(moved.workflow().states())
                .as("the workflow of the moved head")
                .containsExactly("Idea", "Finished");
        assertThat(again.current()).as("kept while the head stays").isSameAs(moved);

        // Harm: a tracked project's own repository that dropped its stories/ stops every read and
        // every save of a board whose stories and issues live in the tracker.
        remote.commit = "c3";
        remote.files.put("porelo-project@c3:project.yml", TRACKED);
        assertThat(again.current().workflow().states())
                .as("the workflow the planner carries, at a head without stories/workflow.yml")
                .startsWith("New", "Accepted")
                .endsWith("Finished", "Rejected")
                .hasSize(14);
        assertThat(again.current().work().faults())
                .as("the work rules the planner carries, at a head without stories/work.yml")
                .isEmpty();
        assertThat(again.current().work().rules())
                .as("the working states the carried rules start")
                .containsKeys(
                        "In analysis", "Under estimation", "Implementing", "Reading", "Reworking");

        remote.commit = "c4";
        remote.files.put("porelo-project@c4:project.yml", "project: [");
        assertThatThrownBy(again::current)
                .as("files that cannot be read at the moved head")
                .isInstanceOf(StoreException.class);
        assertThatThrownBy(again::current)
                .as("never the project read before, shown as the head's")
                .isInstanceOf(StoreException.class);
    }
}
