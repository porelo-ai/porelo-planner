package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.stories.IssueSource;
import org.fuin.porelo.stories.IssuesIndex;
import org.fuin.porelo.stories.StoriesIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Saves moves into two repositories served over HTTPS, as the planner saves a project's moves: one
 * commit per repository changed, pushed all or reported as partly saved.
 */
class GitStoreSaveTest {

    private static final String OWN = "porelo-store";

    private static final String OTHER = "porelo-other";

    private static final String WORKFLOW =
            """
            states:
              - {name: Idea, closed: false}
              - {name: Backlog, closed: false}
              - {name: Review, closed: false}
              - {name: Finished, closed: true}
              - {name: Rejected, closed: true}
            phases:
              - {name: Open, states: [Idea, Backlog]}
              - {name: Build, states: [Review]}
              - {name: Done, states: [Finished, Rejected]}
            """;

    @TempDir Path dir;

    private GitHttpsServer server;

    private GitRemote remote;

    private ServedRepositories repositories;

    private Map<String, String> person;

    @BeforeEach
    void serve() throws Exception {
        final Map<String, String> own = new LinkedHashMap<>();
        own.put(
                "project.yml",
                "project:\n  name: \"store\"\n  central: \"git@github.com:porelo-ai/porelo-store.git\"\n"
                        + "repositories:\n  other:\n"
                        + "    central: \"git@github.com:porelo-ai/porelo-other.git\"\n"
                        + "    issues: [\"CD\"]\n");
        own.put("stories/workflow.yml", WORKFLOW);
        own.put("issues/workflow.yml", WORKFLOW);
        own.put(
                "stories/ST1-Served.md",
                story("ST1", "", "\"porelo-store#AB01\", \"porelo-other#CD01\""));
        own.put("stories/ST2-Alone.md", story("ST2", "  \"state\": \"Idea\",\n", ""));
        // A head shown as an example in the body stands above the story's own, at the end.
        own.put(
                "stories/ST3-Shown.md",
                story("ST3", "  \"state\": \"Idea\",\n", "")
                        .replace(
                                "What a person wrote.\n",
                                "A head reads:\n\n## Data\n\n```json\n{\n  \"state\": \"Idea\",\n"
                                        + "  \"issues\": []\n}\n```\n"));
        own.put("issues/AB01-Own.md", issue("AB01", "Backlog"));
        own.put("issues/AB02-Kept.md", issue("AB02", "Backlog"));
        final Map<String, String> other = new LinkedHashMap<>();
        other.put("issues/workflow.yml", WORKFLOW);
        other.put("issues/CD01-Other.md", issue("CD01", "Backlog"));
        repositories = new ServedRepositories(dir);
        serveWithIndexes(own, other);
        person = new HashMap<>();
        person.put("PATH", "/usr/bin:/bin");
        person.put("HOME", dir.toString());
        person.put("GIT_CONFIG_NOSYSTEM", "1");
        person.put("XDG_CACHE_HOME", Files.createDirectories(dir.resolve("cache")).toString());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void anIssueMoveCommitsTheIssueAndBothIndexesAndPushesTheOthersFirst() throws Exception {
        // A save that left an index behind would show the board otherwise than the files say.
        // CD01 to Idea moves its story to Idea too, so the stories index changes.
        final GitStore store = store();
        final Board board = store.board();
        final String ownBefore = head(OWN);

        final Board saved =
                store.save(board.revision(), writes(Map.of("CD01", "Idea"), Map.of(), List.of()));

        assertThat(saved.issue("CD01").orElseThrow().state())
                .as("the board after the save shows the move")
                .isEqualTo("Idea");
        assertThat(saved.revision()).as("a new revision").isNotEqualTo(board.revision());
        assertThat(names(OTHER, head(OTHER)))
                .as("the other repository's commit changes its issue and its index alone")
                .containsExactlyInAnyOrder("issues/CD01-Other.md", "issues/README.md");
        assertThat(read(OTHER, "issues/CD01-Other.md"))
                .as("the head carries the new state")
                .contains("\"state\": \"Idea\"");
        assertThat(names(OWN, head(OWN)))
                .as("the own repository's commit rewrites the stories index and the story's text")
                .containsExactlyInAnyOrder("stories/README.md", "stories/ST1-Served.md");
        assertThat(indexFaults())
                .as("what is pushed is what the repositories' index check accepts")
                .isEmpty();
        assertThat(git(OWN, "rev-parse", head(OWN) + "^").strip())
                .as("the own commit follows the commit the board was read at")
                .isEqualTo(ownBefore);
        assertThat(log(OTHER, head(OTHER), "%an <%ae>%n%B"))
                .as("the person's own login and a subject naming no item, with no trailer")
                .containsExactly("A Person <person@example.org>", "Move an issue to Idea", "");
    }

    @Test
    void aSaveAgainstAnOlderRevisionIsRefusedAndPushesNothing() throws Exception {
        // A save over a board the window no longer shows would undo someone else's move.
        final GitStore store = store();
        final Board board = store.board();
        final String other = head(OTHER);
        final String own = head(OWN);
        Files.writeString(
                repositories.work(OTHER).resolve("issues/CD01-Other.md"), issue("CD01", "Idea"));
        repositories.commit(OTHER);
        final String moved = head(OTHER);

        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("AB01", "Review"), Map.of(), List.of())));

        assertThat(stale).as("refused as stale").isNotNull();
        assertThat(stale.current())
                .as("names the remote's revision, as a fresh read gives it")
                .isEqualTo(store.refresh().revision().value());
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
        assertThat(moved).isNotEqualTo(other);
    }

    @Test
    void aSaveAgainstARevisionTheStoreNeverShowedIsRefusedWhileTheRemotesStayPut()
            throws Exception {
        // A window's revision that is not the board's means it checked its moves on another board.
        final GitStore store = store();
        store.board();
        final String own = head(OWN);

        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                store.save(
                                        new org.fuin.porelo.planner.store.Revision("old"),
                                        writes(Map.of("AB01", "Review"), Map.of(), List.of())));

        assertThat(stale).as("refused as stale").isNotNull();
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
    }

    @Test
    void aPushOntoABranchRewoundSinceTheFetchIsRejectedAndRestoresNothing() throws Exception {
        // Pushed without a lease, a commit made on the dropped tip would bring dropped work back;
        // the clone knows the commit the branch is rewound to, as it does after an earlier fetch.
        final GitStore store = store();
        store.board();
        final String rewound = head(OTHER);
        Files.writeString(
                repositories.work(OTHER).resolve("issues/CD01-Other.md"), issue("CD01", "Idea"));
        repositories.commit(OTHER);
        final String fetched = head(OTHER);
        store.refresh();
        git(OTHER, "update-ref", "refs/heads/main", rewound);
        final Path root = Files.createDirectories(dir.resolve("push"));
        Files.createDirectories(root.resolve("issues"));
        Files.writeString(root.resolve("issues/CD01-Other.md"), issue("CD01", "Review"));
        final String commit =
                remote.commit(
                        OTHER, fetched, root, List.of("issues/CD01-Other.md"), "Move an issue");

        final GitFailure rejected =
                catchThrowableOfType(
                        GitFailure.class,
                        () ->
                                remote.push(
                                        OTHER,
                                        new CloneUrl(server.url(OTHER + ".git")),
                                        "main",
                                        commit,
                                        fetched));

        assertThat(rejected).as("the push is refused").isNotNull();
        assertThat(rejected.kind()).as("as a moved branch").isEqualTo(GitFailure.Kind.REJECTED);
        assertThat(head(OTHER)).as("the branch stays rewound").isEqualTo(rewound);
    }

    @Test
    void aFailedPushAfterAnotherIsReportedAsPartlySavedNamingBoth() throws Exception {
        // A save told as failed after one repository took it would hide a half-made move.
        final Path hook = repositories.served().resolve(OWN + ".git/hooks/pre-receive");
        Files.writeString(hook, "#!/bin/sh\necho refused by the test >&2\nexit 1\n");
        hook.toFile().setExecutable(true);
        final GitStore store = store();
        final Board board = store.board();

        final StoreException.PartlySaved partly =
                catchThrowableOfType(
                        StoreException.PartlySaved.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(
                                                Map.of("AB01", "Review", "CD01", "Review"),
                                                Map.of(),
                                                List.of())));

        assertThat(partly).as("partly saved").isNotNull();
        assertThat(partly.subject()).as("the repository that failed").isEqualTo(OWN);
        assertThat(partly.pushed()).as("the one that took it").containsExactly(OTHER);
        assertThat(store.board().issue("CD01").orElseThrow().state())
                .as("the board shows what was pushed")
                .isEqualTo("Review");
        assertThat(store.board().issue("AB01").orElseThrow().state())
                .as("and not what was refused")
                .isEqualTo("Backlog");
    }

    @Test
    void anIssueWithoutItsRecordWhereTheRepositoryRecordsStatsIsRefusedBeforeAnyWrite()
            throws Exception {
        // The repository's commit check would refuse the planner's commit once it is pushed.
        Files.createDirectories(repositories.work(OTHER).resolve("stats"));
        Files.writeString(repositories.work(OTHER).resolve("stats/seed.csv"), "id,stage\n");
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();
        final String other = head(OTHER);
        final String own = head(OWN);

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(
                                                Map.of("AB01", "Review", "CD01", "Review"),
                                                Map.of(),
                                                List.of())));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.subject()).as("names the issue").isEqualTo("CD01");
        assertThat(refused.getMessage())
                .isEqualTo("the repository records issue stats and this issue has none");
        assertThat(List.of(head(OWN), head(OTHER)))
                .as("nothing pushed")
                .containsExactly(own, other);
    }

    @Test
    void anIssueWhoseFileIsGoneIsRefusedBeforeAnyWrite() throws Exception {
        // Only an index row is left, and rewriting the index would drop the move again.
        Files.delete(repositories.work(OWN).resolve("issues/AB02-Kept.md"));
        repositories.commit(OWN);
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("AB02", "Review"), Map.of(), List.of())));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.subject()).isEqualTo("AB02");
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
    }

    @Test
    void aStorysStatedStateIsWrittenAndRemovedInItsHead() throws Exception {
        // A stated state left behind would hold the story where its issues no longer stand.
        final GitStore store = store();
        Board board = store.board();

        board =
                store.save(
                        board.revision(), writes(Map.of(), Map.of("ST1", "Rejected"), List.of()));
        assertThat(board.story("ST1").orElseThrow().state()).isEqualTo("Rejected");
        assertThat(read(OWN, "stories/ST1-Served.md")).contains("\"state\": \"Rejected\"");
        assertThat(log(OWN, head(OWN), "%s")).first().isEqualTo("Move a story to Rejected");

        board = store.save(board.revision(), writes(Map.of(), Map.of(), List.of("ST1")));
        assertThat(board.story("ST1").orElseThrow().stateGiven()).as("unstated again").isFalse();
        assertThat(read(OWN, "stories/ST1-Served.md")).doesNotContain("\"state\"");
    }

    @Test
    void thePlannersCommitsPassTheRepositorysCommitCheck() throws Exception {
        // A commit the repository's own check refuses would turn its next build red.
        final GitStore store = store();
        final String own = head(OWN);
        final String other = head(OTHER);
        Board board = store.board();
        board = store.save(board.revision(), writes(Map.of("AB01", "Review"), Map.of(), List.of()));
        store.save(
                board.revision(),
                writes(
                        Map.of("AB01", "Finished", "AB02", "Finished", "CD01", "Rejected"),
                        Map.of(),
                        List.of()));

        assertThat(checkCommits(OWN, own)).as("the own repository's check").isZero();
        assertThat(checkCommits(OTHER, other)).as("the other repository's check").isZero();
        assertThat(log(OWN, head(OWN), "%s"))
                .first()
                .as("a batch")
                .isEqualTo("Move 2 issues to Finished");
    }

    @Test
    void aMoveTheIndexRefusesIsRefusedNamingItAndPushesNothing() throws Exception {
        // A head pushed without its index would turn the repository's index check red.
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of(), Map.of("ST2", "Finished"), List.of())));

        assertThat(refused).as("refused, a finished story needs its tested day").isNotNull();
        assertThat(refused.subject()).as("naming the story").isEqualTo("ST2");
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
    }

    @Test
    void aStorysStateIsWrittenInTheHeadAtItsEndNotInAnExampleAbove() throws Exception {
        // An edit of the body's example would change the person's text and leave the state.
        final GitStore store = store();
        final Board board = store.board();

        final Board saved =
                store.save(board.revision(), writes(Map.of(), Map.of("ST3", "Review"), List.of()));

        assertThat(saved.story("ST3").orElseThrow().state())
                .as("the story's own head carries the state")
                .isEqualTo("Review");
        assertThat(read(OWN, "stories/ST3-Shown.md"))
                .as("the example keeps its state")
                .contains("A head reads:\n\n## Data\n\n```json\n{\n  \"state\": \"Idea\",");
    }

    @Test
    void aLinkAmongTheIssuesIsRefusedAsTheStoresFailure() throws Exception {
        // The board passes a file it does not read; the save must not fail on it unchecked.
        Files.createSymbolicLink(
                repositories.work(OTHER).resolve("issues/notes.txt"), Path.of("CD01-Other.md"));
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();
        final String other = head(OTHER);

        final Throwable thrown =
                org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("CD01", "Review"), Map.of(), List.of())));

        assertThat(thrown)
                .as("refused as the store's own failure")
                .isInstanceOf(StoreException.class);
        assertThat(head(OTHER)).as("nothing pushed").isEqualTo(other);
    }

    @Test
    void anIssueWhoseHeadCannotBeEditedIsRefusedNamingIt() throws Exception {
        // A refusal naming no item leaves the window unable to say which move stays unsaved.
        Files.writeString(
                repositories.work(OTHER).resolve("issues/CD01-Other.md"),
                issue("CD01", "Backlog").replace("\n", "\r\n"));
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("CD01", "Review"), Map.of(), List.of())));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.subject()).as("naming the issue").isEqualTo("CD01");
    }

    @Test
    void aPushRejectedAsTheBranchMovedNamesTheRevisionTheRemotesHoldNow() throws Exception {
        // Answered with the revision the window sent, the window would retry the same save forever.
        final GitStore store =
                store(
                        real ->
                                remote(
                                        real,
                                        "push",
                                        () -> {
                                            Files.writeString(
                                                    repositories
                                                            .work(OTHER)
                                                            .resolve("issues/CD01-Other.md"),
                                                    issue("CD01", "Idea"));
                                            repositories.commit(OTHER);
                                        }));
        final Board board = store.board();

        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("CD01", "Review"), Map.of(), List.of())));

        assertThat(stale).as("refused as stale").isNotNull();
        assertThat(stale.current())
                .as("naming the commits the repositories hold now")
                .isEqualTo(
                        GitStore.revision(
                                List.of(OWN, OTHER), Map.of(OWN, head(OWN), OTHER, head(OTHER))))
                .isNotEqualTo(board.revision().value());
    }

    @Test
    void aSaveWhosePushTookIsToldSavedWhenTheCloneCannotRecordIt() throws Exception {
        // A pushed save told as failed would have the window offer it again over its own result.
        final GitStore store =
                store(
                        real ->
                                remote(
                                        real,
                                        "advance",
                                        () -> {
                                            throw new GitFailure(
                                                    GitFailure.Kind.FAILED, "cannot record");
                                        }));
        final Board board = store.board();

        final Board saved =
                store.save(board.revision(), writes(Map.of("CD01", "Review"), Map.of(), List.of()));

        assertThat(saved.issue("CD01").orElseThrow().state())
                .as("the board shows the pushed move")
                .isEqualTo("Review");
        assertThat(read(OTHER, "issues/CD01-Other.md")).contains("\"state\": \"Review\"");
    }

    /** A step run before one of the remote's calls, to make the race or failure it needs. */
    @FunctionalInterface
    private interface Before {
        void run() throws Exception;
    }

    private static Remote remote(final Remote real, final String method, final Before before) {
        return (Remote)
                java.lang.reflect.Proxy.newProxyInstance(
                        Remote.class.getClassLoader(),
                        new Class<?>[] {Remote.class},
                        (proxy, called, arguments) -> {
                            if (called.getName().equals(method)) {
                                before.run();
                            }
                            try {
                                return called.invoke(real, arguments);
                            } catch (final java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    /** The faults the repositories' own index checks find in what the remotes hold. */
    private List<String> indexFaults() throws Exception {
        final Path checks = Files.createDirectories(dir.resolve("index-check"));
        for (final String repository : List.of(OWN, OTHER)) {
            git(
                    null,
                    "clone",
                    "--quiet",
                    repositories.served().resolve(repository + ".git").toString(),
                    checks.resolve(repository).toString());
        }
        final List<String> faults = new ArrayList<>();
        faults.addAll(IssuesIndex.check(checks.resolve(OWN).resolve("issues")).faults());
        faults.addAll(IssuesIndex.check(checks.resolve(OTHER).resolve("issues")).faults());
        faults.addAll(
                StoriesIndex.check(
                                checks.resolve(OWN).resolve("stories"),
                                IssueSource.local(checks.resolve(OWN)))
                        .faults());
        return faults;
    }

    private GitStore store() throws Exception {
        return store(real -> real);
    }

    private GitStore store(final java.util.function.UnaryOperator<Remote> wrap) throws Exception {
        server = GitHttpsServer.start(repositories.served(), dir, null);
        final Path config = dir.resolve("gitconfig");
        Files.writeString(
                config,
                "[http]\n\tsslCAInfo = "
                        + server.ca()
                        + "\n[user]\n\tname = A Person\n\temail = person@example.org\n");
        person.put("GIT_CONFIG_GLOBAL", config.toString());
        final Path cache = CacheDirectory.of(person, "project");
        remote =
                new GitRemote(
                        cache,
                        person,
                        Duration.ofSeconds(5),
                        new GitRunner(Duration.ofSeconds(20), 1 << 20),
                        1 << 16);
        return new GitStore(
                List.of(
                        new Repository(OWN, new CloneUrl(server.url(OWN + ".git")), null),
                        new Repository(OTHER, new CloneUrl(server.url(OTHER + ".git")), "main")),
                wrap.apply(remote),
                new StoreReader(remote, 1000),
                new FileProjectLock(cache),
                Clock.systemUTC(),
                Duration.ofSeconds(10),
                4,
                Runnable::run);
    }

    /** The fixture's indexes as the library writes them, so a save changes only what it moves. */
    private void serveWithIndexes(final Map<String, String> own, final Map<String, String> other)
            throws Exception {
        final Path layout = dir.resolve("layout");
        write(layout.resolve(OWN), own);
        write(layout.resolve(OTHER), other);
        IssuesIndex.write(layout.resolve(OWN).resolve("issues"));
        IssuesIndex.write(layout.resolve(OTHER).resolve("issues"));
        StoriesIndex.write(
                layout.resolve(OWN).resolve("stories"), IssueSource.local(layout.resolve(OWN)));
        repositories.repository(OWN, files(layout.resolve(OWN)));
        repositories.repository(OTHER, files(layout.resolve(OTHER)));
    }

    private static void write(final Path root, final Map<String, String> files) throws IOException {
        for (final Map.Entry<String, String> file : files.entrySet()) {
            final Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
    }

    private static Map<String, String> files(final Path root) throws IOException {
        final Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> all = Files.walk(root)) {
            for (final Path path : all.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(), Files.readString(path));
            }
        }
        return files;
    }

    private int checkCommits(final String repository, final String from) throws Exception {
        final Path clone = dir.resolve("check-" + repository);
        git(
                null,
                "clone",
                "--quiet",
                repositories.served().resolve(repository + ".git").toString(),
                clone.toString());
        final ProcessBuilder builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                "org.fuin.porelo.release.Main",
                                "check-commits",
                                from + "..HEAD")
                        .directory(clone.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final int code = process.waitFor();
        assertThat(out).as("what check-commits said, read when it fails").isNotNull();
        if (code != 0) {
            System.out.println(out);
        }
        return code;
    }

    private String head(final String repository) throws Exception {
        return git(repository, "rev-parse", "refs/heads/main").strip();
    }

    private String read(final String repository, final String path) throws Exception {
        return git(repository, "show", "refs/heads/main:" + path);
    }

    private List<String> names(final String repository, final String commit) throws Exception {
        return git(repository, "show", "--format=", "--name-only", commit)
                .lines()
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private List<String> log(final String repository, final String commit, final String format)
            throws Exception {
        return git(repository, "show", "--no-patch", "--format=" + format, commit).lines().toList();
    }

    private String git(final String repository, final String... arguments) throws Exception {
        final List<String> line = new ArrayList<>(List.of("git"));
        if (repository != null) {
            line.add("--git-dir=" + repositories.served().resolve(repository + ".git"));
        }
        line.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(line).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", arguments) + ": " + out);
        }
        return out;
    }

    private static Writes writes(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final List<String> unstated) {
        return new Writes(issues, stories, unstated);
    }

    private static String story(final String id, final String state, final String issues) {
        return "# "
                + id
                + " — A story\n\nWhat a person wrote.\n\n## Data\n\n```json\n{\n  \"$schema\": \"story-2.json\",\n  \"id\": \""
                + id
                + "\",\n  \"title\": \"A story\",\n"
                + state
                + "  \"gain\": \"Something.\",\n  \"test\": \"A test.\",\n  \"priority\": \"High\",\n"
                + "  \"issues\": ["
                + issues
                + "],\n  \"tested\": null\n}\n```\n";
    }

    private static String issue(final String id, final String state) {
        return "# "
                + id
                + " — An issue\n\n```json\n{\n  \"$schema\": \"issue-1.json\",\n  \"id\": \""
                + id
                + "\",\n  \"title\": \"An issue\",\n  \"state\": \""
                + state
                + "\",\n  \"size\": \"S\",\n  \"priority\": \"Medium\",\n  \"rank\": 1,\n  \"blockedBy\": [],\n"
                + "  \"covers\": \"Something.\"\n}\n```\n\n**What must be true.** Something.\n";
    }
}
