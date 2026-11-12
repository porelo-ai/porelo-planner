package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSavesTest {

    private static final String OWN = "porelo-store";

    private static final String OTHER = "porelo-other";

    @TempDir Path dir;

    private GitHttpsServer server;

    private ServedRepositories served;

    private Path cache;

    private Map<String, String> person;

    private GitRemote remote;

    private GitLocalRefs refs;

    private List<Repository> repositories;

    @BeforeEach
    void serve() throws Exception {
        served = new ServedRepositories(dir);
        served.repository(OWN, Map.of("stories/README.md", "# Stories\n", "a.md", "a\n"));
        served.repository(OTHER, Map.of("issues/README.md", "# Issues\n", "a.md", "a\n"));
        server = GitHttpsServer.start(served.served(), dir, null);
        final Path config = dir.resolve("gitconfig");
        Files.writeString(
                config,
                "[http]\n\tsslCAInfo = "
                        + server.ca()
                        + "\n[user]\n\tname = A Person\n\temail = person@example.org\n");
        person = new HashMap<>();
        person.put("PATH", "/usr/bin:/bin");
        person.put("HOME", dir.toString());
        person.put("GIT_CONFIG_NOSYSTEM", "1");
        person.put("GIT_CONFIG_GLOBAL", config.toString());
        cache = Files.createDirectories(dir.resolve("cache"));
        final GitRunner runner = new GitRunner(Duration.ofSeconds(20), 1 << 20);
        remote = new GitRemote(cache, person, Duration.ofSeconds(5), runner, 1 << 16);
        refs = new GitLocalRefs(cache, person, Duration.ofSeconds(5), runner);
        repositories =
                List.of(
                        new Repository(OWN, new CloneUrl(server.url(OWN + ".git")), null),
                        new Repository(OTHER, new CloneUrl(server.url(OTHER + ".git")), "main"));
        for (final Repository repository : repositories) {
            remote.fetch(repository.name(), repository.url(), repository.branch());
        }
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    // Prevents a save leaving the planner before it stands, or a save answered and then lost.
    @Test
    void aSaveStandsInTheCloneAndLeavesTheRemoteAlone() throws Exception {
        final List<Runnable> later = new ArrayList<>();
        final LocalSaves saves = new LocalSaves(repositories, remote, refs, later::add);
        final String before = remoteHead(OTHER);

        final String saved = save(saves, OTHER, Map.of("a.md", "mine\n"));
        saves.push();

        assertThat(saves.tip(OTHER)).as("the board reads the save").contains(saved);
        assertThat(saves.pending(OTHER)).as("waits to be pushed").isTrue();
        assertThat(remoteHead(OTHER)).as("nothing pushed yet").isEqualTo(before);
        assertThat(later).as("the push waits for the background").hasSize(1);
    }

    // Prevents a save answered to the person and lost when the planner stops before its push.
    @Test
    void aNewStartOverTheSameClonesPushesASaveLeftUnpushed() throws Exception {
        final String saved =
                save(
                        new LocalSaves(repositories, remote, refs, task -> {}),
                        OTHER,
                        Map.of("a.md", "mine\n"));
        final LocalSaves started = new LocalSaves(repositories, remote, refs, Runnable::run);

        started.push();

        assertThat(remoteHead(OTHER)).as("the save reached the remote").isEqualTo(saved);
        assertThat(started.pending(OTHER)).as("nothing waits").isFalse();
        assertThat(remote.fetched(OTHER))
                .as("the pushed commit is the one fetched")
                .contains(saved);
        assertThat(started.state().get(OTHER).kind()).as("told").isEqualTo(LocalSaves.Kind.PUSHED);
    }

    // Prevents a save lost because someone else pushed a change to other files first.
    @Test
    void aPushTheRemoteRejectsIsMadeAgainOnItsNewCommitWhenItChangedOtherFiles() throws Exception {
        final LocalSaves saves = new LocalSaves(repositories, remote, refs, Runnable::run);
        save(saves, OTHER, Map.of("a.md", "mine\n"));
        final String theirs = change(OTHER, "b.md", "theirs\n");

        saves.push();

        final String head = remoteHead(OTHER);
        assertThat(served("rev-parse", OTHER, head + "^").strip())
                .as("the save follows the remote's change")
                .isEqualTo(theirs);
        assertThat(served("show", OTHER, head + ":a.md")).as("the save's file").isEqualTo("mine\n");
        assertThat(served("show", OTHER, head + ":b.md"))
                .as("the other's change kept")
                .isEqualTo("theirs\n");
        assertThat(served("log", OTHER, "-1", "--format=%s", head).strip())
                .as("the save's subject kept")
                .isEqualTo("Save a.md");
        assertThat(saves.pending(OTHER)).as("nothing waits").isFalse();
    }

    // Prevents a save written over another's change to the same file.
    @Test
    void aPushOverAChangeToTheSameFileDropsTheSaveAndPushesNothing() throws Exception {
        final LocalSaves saves = new LocalSaves(repositories, remote, refs, Runnable::run);
        save(saves, OTHER, Map.of("a.md", "mine\n", "c.md", "new\n"));
        final String theirs = change(OTHER, "a.md", "theirs\n");

        saves.push();

        assertThat(remoteHead(OTHER)).as("the other's change stays the remote's").isEqualTo(theirs);
        assertThat(saves.pending(OTHER)).as("the save is dropped").isFalse();
        assertThat(saves.tip(OTHER)).as("the board reads the remote's change").contains(theirs);
        assertThat(saves.state().get(OTHER))
                .as("told with the paths dropped")
                .isEqualTo(
                        new LocalSaves.PushState(
                                LocalSaves.Kind.DROPPED, "", List.of("a.md", "c.md")));
    }

    // Prevents two stories under one number when both sides added a story under other names.
    @Test
    void aPushOverAStoryAddedElsewhereDropsTheSaveThatAddedOneToo() throws Exception {
        final LocalSaves saves = new LocalSaves(repositories, remote, refs, Runnable::run);
        save(saves, OWN, Map.of("stories/ST2-Mine.md", "mine\n"));
        final String theirs = change(OWN, "stories/ST2-Theirs.md", "theirs\n");

        saves.push();

        assertThat(remoteHead(OWN)).as("the other's story stays the remote's").isEqualTo(theirs);
        assertThat(saves.pending(OWN)).as("the save is dropped").isFalse();
        assertThat(saves.state().get(OWN))
                .as("told with the story dropped")
                .isEqualTo(
                        new LocalSaves.PushState(
                                LocalSaves.Kind.DROPPED, "", List.of("stories/ST2-Mine.md")));
    }

    // Prevents two stories under one number when one side added it as ST<n>.md, without a title.
    @Test
    void aPushOverAStoryAddedElsewhereWithoutATitleDropsTheSaveThatAddedOneToo() throws Exception {
        final LocalSaves saves = new LocalSaves(repositories, remote, refs, Runnable::run);
        save(saves, OWN, Map.of("stories/ST2-Mine.md", "mine\n"));
        final String theirs = change(OWN, "stories/ST2.md", "theirs\n");

        saves.push();

        assertThat(remoteHead(OWN)).as("the other's story stays the remote's").isEqualTo(theirs);
        assertThat(saves.state().get(OWN))
                .as("told with the story dropped, not made again beside the other")
                .isEqualTo(
                        new LocalSaves.PushState(
                                LocalSaves.Kind.DROPPED, "", List.of("stories/ST2-Mine.md")));
    }

    // Prevents a stories index pushed naming states its repositories do not hold.
    @Test
    void aPushOverAFileWithoutANumberAddedElsewhereIsMadeAgainNotDropped() throws Exception {
        // Prevents a person's save from being thrown away over a file whose name no number can
        // collide in.
        final LocalSaves saves = new LocalSaves(repositories, remote, refs, Runnable::run);
        save(saves, OWN, Map.of("stories/ST2-Mine.md", "mine\n"));
        change(OWN, "stories/NOTES.md", "theirs\n");

        saves.push();

        assertThat(saves.pending(OWN)).as("the save is pushed").isFalse();
        assertThat(saves.state().get(OWN).kind())
                .as("made again on the remote's commit, not dropped")
                .isEqualTo(LocalSaves.Kind.PUSHED);
    }

    @Test
    void theOwnRepositoryIsPushedLastAndNotWhileAnotherIsNot() throws Exception {
        final List<String> order = new ArrayList<>();
        final AtomicBoolean refuse = new AtomicBoolean(true);
        final Remote watched =
                new Delegate(remote) {
                    @Override
                    public void push(
                            final String repository,
                            final CloneUrl url,
                            final @Nullable String branch,
                            final String commit,
                            final String expected) {
                        order.add(repository);
                        if (repository.equals(OTHER) && refuse.get()) {
                            throw new GitFailure(GitFailure.Kind.UNREACHABLE, "no answer");
                        }
                        super.push(repository, url, branch, commit, expected);
                    }
                };
        final LocalSaves saves = new LocalSaves(repositories, watched, refs, Runnable::run);
        final String own = remoteHead(OWN);
        save(saves, OWN, Map.of("stories/README.md", "# Stories\n\nmoved\n"));
        save(saves, OTHER, Map.of("a.md", "mine\n"));

        saves.push();

        assertThat(order).as("the own waits for the other").containsExactly(OTHER);
        assertThat(remoteHead(OWN)).as("the own not pushed").isEqualTo(own);
        assertThat(saves.state().get(OTHER))
                .as("the failure told")
                .satisfies(
                        state -> {
                            assertThat(state.kind()).as("kind").isEqualTo(LocalSaves.Kind.FAILED);
                            assertThat(state.words()).as("words").contains("no answer");
                        });

        refuse.set(false);
        saves.push();

        assertThat(order).as("the other first, the own last").containsExactly(OTHER, OTHER, OWN);
        assertThat(saves.pending(OWN) || saves.pending(OTHER)).as("both pushed").isFalse();
    }

    // Prevents a save made while another is pushed staying in the clone and never pushed.
    @Test
    void aSaveMadeDuringAPushIsPushedByTheNextRound() throws Exception {
        final AtomicBoolean first = new AtomicBoolean(true);
        final LocalSaves[] holder = new LocalSaves[1];
        final String[] second = new String[1];
        final Remote racing =
                new Delegate(remote) {
                    @Override
                    public void push(
                            final String repository,
                            final CloneUrl url,
                            final @Nullable String branch,
                            final String commit,
                            final String expected) {
                        if (first.getAndSet(false)) {
                            try {
                                second[0] = save(holder[0], OTHER, Map.of("b.md", "later\n"));
                            } catch (final IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        }
                        super.push(repository, url, branch, commit, expected);
                    }
                };
        holder[0] = new LocalSaves(repositories, racing, refs, Runnable::run);
        save(holder[0], OTHER, Map.of("a.md", "mine\n"));

        holder[0].push();

        assertThat(remoteHead(OTHER)).as("the later save pushed too").isEqualTo(second[0]);
        assertThat(holder[0].pending(OTHER)).as("nothing waits").isFalse();
    }

    // Prevents a save made on a board older than what the clone holds, which would undo a move.
    @Test
    void aSaveOnAnOlderCommitIsRefusedAndMovesNothing() throws Exception {
        final LocalSaves saves = new LocalSaves(repositories, remote, refs, task -> {});
        final String fetched = remote.fetched(OTHER).orElseThrow();
        final String saved = save(saves, OTHER, Map.of("a.md", "mine\n"));
        final String stale = commit(OTHER, fetched, Map.of("b.md", "old board\n"));

        final boolean recorded = saves.record(Map.of(OTHER, fetched), Map.of(OTHER, stale));

        assertThat(recorded).as("refused").isFalse();
        assertThat(saves.tip(OTHER)).as("the save before stays").contains(saved);
    }

    // Prevents a save that reached the remote being dropped and told to be made again.
    @Test
    void aPushThatLandedButWasNotRecordedIsNotTakenForAConflict() throws Exception {
        final AtomicBoolean first = new AtomicBoolean(true);
        final Remote forgetful =
                new Delegate(remote) {
                    @Override
                    public void advance(final String repository, final String commit) {
                        if (first.getAndSet(false)) {
                            throw new GitFailure(GitFailure.Kind.FAILED, "the clone is busy");
                        }
                        super.advance(repository, commit);
                    }
                };
        final LocalSaves saves = new LocalSaves(repositories, forgetful, refs, Runnable::run);
        final String saved = save(saves, OTHER, Map.of("a.md", "mine\n"));
        saves.push();
        assertThat(remoteHead(OTHER)).as("the push landed").isEqualTo(saved);
        final String later = save(saves, OTHER, Map.of("a.md", "mine again\n"));

        saves.push();

        assertThat(saves.state().get(OTHER).kind())
                .as("told pushed, not dropped")
                .isEqualTo(LocalSaves.Kind.PUSHED);
        assertThat(remoteHead(OTHER)).as("the later save pushed on the first").isEqualTo(later);
        assertThat(saves.pending(OTHER)).as("nothing waits").isFalse();
    }

    // Prevents a stories index pushed naming a state of a save another repository has not taken.
    @Test
    void theOwnRepositoryWaitsForASaveOfAnotherMadeDuringTheRound() throws Exception {
        final AtomicBoolean first = new AtomicBoolean(true);
        final List<Boolean> otherWaiting = new ArrayList<>();
        final LocalSaves[] holder = new LocalSaves[1];
        final Remote racing =
                new Delegate(remote) {
                    @Override
                    public void push(
                            final String repository,
                            final CloneUrl url,
                            final @Nullable String branch,
                            final String commit,
                            final String expected) {
                        if (repository.equals(OTHER) && first.getAndSet(false)) {
                            try {
                                final String own = holder[0].tip(OWN).orElseThrow();
                                final String other = holder[0].tip(OTHER).orElseThrow();
                                holder[0].record(
                                        Map.of(OWN, own, OTHER, other),
                                        Map.of(
                                                OWN,
                                                LocalSavesTest.this.commit(
                                                        OWN, own, Map.of("a.md", "both\n")),
                                                OTHER,
                                                LocalSavesTest.this.commit(
                                                        OTHER, other, Map.of("b.md", "both\n"))));
                            } catch (final IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        }
                        if (repository.equals(OWN)) {
                            otherWaiting.add(holder[0].pending(OTHER));
                        }
                        super.push(repository, url, branch, commit, expected);
                    }
                };
        holder[0] = new LocalSaves(repositories, racing, refs, Runnable::run);
        save(holder[0], OTHER, Map.of("a.md", "mine\n"));

        holder[0].push();

        assertThat(otherWaiting)
                .as("the own pushed only once the other's saves are")
                .isNotEmpty()
                .containsOnly(false);
        assertThat(holder[0].pending(OWN) || holder[0].pending(OTHER)).as("both pushed").isFalse();
    }

    // Prevents a push the remote refuses for good being made again and again under new commits.
    @Test
    void aPushRefusedWhileTheRemoteStaysPutFailsWithItsWordsAndIsNotMadeAgain() throws Exception {
        final List<String> pushes = new ArrayList<>();
        final Remote refusing =
                new Delegate(remote) {
                    @Override
                    public void push(
                            final String repository,
                            final CloneUrl url,
                            final @Nullable String branch,
                            final String commit,
                            final String expected) {
                        pushes.add(commit);
                        throw new GitFailure(GitFailure.Kind.REJECTED, "[rejected] by a rule");
                    }
                };
        final LocalSaves saves = new LocalSaves(repositories, refusing, refs, Runnable::run);
        final String saved = save(saves, OTHER, Map.of("a.md", "mine\n"));

        saves.push();

        assertThat(pushes).as("pushed once").containsExactly(saved);
        assertThat(saves.tip(OTHER)).as("the save kept as it was").contains(saved);
        assertThat(saves.state().get(OTHER))
                .as("told with the remote's words")
                .satisfies(
                        state -> {
                            assertThat(state.kind()).as("kind").isEqualTo(LocalSaves.Kind.FAILED);
                            assertThat(state.words()).as("words").contains("by a rule");
                        });
    }

    /** Commits files on a repository's tip and records it as a save. */
    private String save(final LocalSaves saves, final String name, final Map<String, String> files)
            throws IOException {
        final String parent = saves.tip(name).orElseThrow();
        final String commit = commit(name, parent, files);
        assertThat(saves.record(Map.of(name, parent), Map.of(name, commit)))
                .as("recorded on its tip")
                .isTrue();
        return commit;
    }

    private String commit(final String name, final String parent, final Map<String, String> files)
            throws IOException {
        final Path root = Files.createTempDirectory(dir, "save-");
        for (final Map.Entry<String, String> file : files.entrySet()) {
            final Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
        }
        return remote.commit(
                name,
                parent,
                root,
                files.keySet().stream().sorted().toList(),
                "Save " + String.join(" ", files.keySet().stream().sorted().toList()));
    }

    /** The commit the served repository's branch holds now. */
    private String remoteHead(final String name) throws Exception {
        return served("rev-parse", name, "refs/heads/main").strip();
    }

    /** Commits a change to the served repository, as someone else's push. */
    private String change(final String name, final String path, final String text)
            throws Exception {
        Files.writeString(served.work(name).resolve(path), text, StandardCharsets.UTF_8);
        served.commit(name);
        return remoteHead(name);
    }

    private String served(final String command, final String name, final String... rest)
            throws Exception {
        final List<String> line =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--git-dir=" + served.served().resolve(name + ".git"),
                                command));
        line.addAll(List.of(rest));
        final ProcessBuilder builder = new ProcessBuilder(line).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", line) + ": " + out);
        }
        return out;
    }

    /** A remote that hands every call on, for a test to change one. */
    private static class Delegate implements Remote {

        private final Remote real;

        Delegate(final Remote real) {
            this.real = real;
        }

        @Override
        public String head(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            return real.head(repository, url, branch);
        }

        @Override
        public String fetch(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            return real.fetch(repository, url, branch);
        }

        @Override
        public java.util.Optional<String> fetched(final String repository) {
            return real.fetched(repository);
        }

        @Override
        public java.util.Optional<String> read(
                final String repository, final String commit, final String path) {
            return real.read(repository, commit, path);
        }

        @Override
        public List<String> list(
                final String repository, final String commit, final String directory) {
            return real.list(repository, commit, directory);
        }

        @Override
        public List<String> directories(
                final String repository, final String commit, final String directory) {
            return real.directories(repository, commit, directory);
        }

        @Override
        public List<Found> grep(
                final String repository,
                final String commit,
                final java.util.Collection<String> words) {
            return real.grep(repository, commit, words);
        }

        @Override
        public void push(
                final String repository,
                final CloneUrl url,
                final @Nullable String branch,
                final String commit,
                final String expected) {
            real.push(repository, url, branch, commit, expected);
        }

        @Override
        public void advance(final String repository, final String commit) {
            real.advance(repository, commit);
        }
    }
}
