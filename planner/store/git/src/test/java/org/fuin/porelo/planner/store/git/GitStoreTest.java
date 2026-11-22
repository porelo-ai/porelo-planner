package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.git.stories.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GitStoreTest {

    private static final String WORKFLOW_TEXT =
            "states:\n  - {name: Idea, closed: false}\n  - {name: Backlog, closed: false}\n"
                    + "  - {name: Finished, closed: true}\nphases:\n"
                    + "  - {name: Open, states: [Idea, Backlog]}\n";

    private static final Workflow WORKFLOW = Workflow.read(WORKFLOW_TEXT);

    private static final Repository OWN =
            new Repository("store", new CloneUrl("https://git.example.org/store"), null);

    private static final Repository OTHER =
            new Repository("other", new CloneUrl("https://git.example.org/other"), null);

    /** A remote whose branches the test moves, counting what the store asks of it. */
    private static final class FakeRemote implements Remote {
        final Map<String, String> heads =
                new ConcurrentHashMap<>(Map.of("store", "a".repeat(40), "other", "b".repeat(40)));
        final Map<String, String> clones = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> fetches = new ConcurrentHashMap<>();
        final AtomicInteger asked = new AtomicInteger();
        volatile @Nullable CountDownLatch hold;
        volatile GitFailure.@Nullable Kind failing;

        @Override
        public String head(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            asked.incrementAndGet();
            final CountDownLatch latch = hold;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            final GitFailure.Kind kind = failing;
            if (kind != null) {
                throw new GitFailure(kind, "git exited with 128: fatal: as the test asked");
            }
            return heads.get(repository);
        }

        @Override
        public String fetch(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            fetches.computeIfAbsent(repository, r -> new AtomicInteger()).incrementAndGet();
            clones.put(repository, heads.get(repository));
            return heads.get(repository);
        }

        @Override
        public Optional<String> fetched(final String repository) {
            return Optional.ofNullable(clones.get(repository));
        }

        @Override
        public Optional<String> read(
                final String repository, final String commit, final String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> list(
                final String repository, final String commit, final String directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> directories(
                final String repository, final String commit, final String directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Found> grep(
                final String repository,
                final String commit,
                final java.util.Collection<String> words) {
            throw new UnsupportedOperationException();
        }

        int fetched(final Repository repository) {
            return fetches.getOrDefault(repository.name(), new AtomicInteger()).get();
        }
    }

    /** Reads a part whose one issue's title is the commit it was read at. */
    private static final PartReader READER =
            (repository, commit, project) ->
                    new Part(
                            project == null ? WORKFLOW : null,
                            List.of(),
                            List.of(
                                    new Issue(
                                            project == null ? "XY1" : "QZ1",
                                            repository.name(),
                                            commit,
                                            "Idea",
                                            null,
                                            List.of(),
                                            null,
                                            null,
                                            "")),
                            null);

    private static final class ManualClock extends Clock {
        volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Queue implements Executor {
        final List<Runnable> tasks = new CopyOnWriteArrayList<>();

        @Override
        public void execute(final Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            final List<Runnable> now = new ArrayList<>(tasks);
            tasks.clear();
            now.forEach(Runnable::run);
        }
    }

    private final FakeRemote remote = new FakeRemote();

    private final ManualClock clock = new ManualClock();

    private final Queue queue = new Queue();

    private static ProjectLock inProcess() {
        final ReentrantLock lock = new ReentrantLock();
        return () -> {
            lock.lock();
            return lock::unlock;
        };
    }

    private GitStore store(final Executor background) {
        return new GitStore(
                List.of(OWN, OTHER),
                remote,
                READER,
                inProcess(),
                clock,
                Duration.ofSeconds(10),
                4,
                background);
    }

    private static String title(final Board board, final String id) {
        return board.issue(id).orElseThrow().title();
    }

    /**
     * A store over parts the test gives: the own repository's stories and issues, the other's
     * issues.
     */
    private GitStore store(
            final List<org.fuin.porelo.planner.store.git.stories.Story> stories,
            final List<Issue> own,
            final List<Issue> other) {
        return new GitStore(
                List.of(OWN, OTHER),
                remote,
                (repository, commit, project) ->
                        project == null
                                ? new Part(WORKFLOW, stories, own, null)
                                : new Part(null, List.of(), other, null),
                inProcess(),
                clock,
                Duration.ofSeconds(10),
                4,
                queue);
    }

    private static Issue issue(final String id, final String repository, final String state) {
        return new Issue(id, repository, "Issue " + id, state, null, List.of(), null, null, "");
    }

    private static org.fuin.porelo.planner.store.git.stories.Story story(
            final String id, final @Nullable String state, final String... issues) {
        return new org.fuin.porelo.planner.store.git.stories.Story(
                id, "Story " + id, state, "High", null, List.of(issues), "");
    }

    @Test
    void anIssueTheBoardCannotPlaceIsShownWithWhyAndLeftOutOfNoBoard() {
        // A hand edit that puts an issue in a state the workflow lacks, or gives two repositories
        // one id, would otherwise hide that issue from the person, who then cannot set it right.
        final Board board =
                store(
                                List.of(),
                                List.of(
                                        issue("XY1", "store", "Parked"),
                                        issue("XY2", "store", "Idea")),
                                List.of(issue("XY2", "other", "Backlog")))
                        .board();

        assertThat(board.faults()).as("nothing named as left out").isEmpty();
        assertThat(board.issues())
                .as("every issue read, each with what is wrong with it")
                .extracting(Issue::id, Issue::repository, Issue::state, Issue::warnings)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "XY1",
                                "store",
                                "Parked",
                                List.of(
                                        org.fuin.porelo.planner.store.Warning
                                                .stateOutsideWorkflow())),
                        org.assertj.core.groups.Tuple.tuple(
                                "XY2",
                                "store",
                                "Idea",
                                List.of(org.fuin.porelo.planner.store.Warning.idTwice())),
                        org.assertj.core.groups.Tuple.tuple(
                                "XY2",
                                "other",
                                "Backlog",
                                List.of(org.fuin.porelo.planner.store.Warning.idTwice())));
        assertThat(org.fuin.porelo.planner.store.Warning.stateOutsideWorkflow().text())
                .as("the words the person reads")
                .isEqualTo("Its state is none of the workflow's states, so no work starts on it.");
    }

    @Test
    void aStoryTheBoardCannotFullyReadIsShownWithWhyAndItsStateFromItsKnownIssues() {
        // A story left out of the board takes its issues' context away from the person; one that
        // took a state from an issue in doubt would stand in a column no one chose.
        final Board board =
                store(
                                List.of(
                                        story("ST1", null, "store#XY1", "store#XY2"),
                                        story("ST2", null, "store#XY1"),
                                        story("ST3", null, "store#XY2", "store#ZZ9"),
                                        story("ST4", "Parked"),
                                        story("ST5", "Idea"),
                                        story("ST5", "Idea")),
                                List.of(
                                        issue("XY1", "store", "Parked"),
                                        issue("XY2", "store", "Backlog")),
                                List.of())
                        .board();

        assertThat(board.faults()).as("nothing named as left out").isEmpty();
        assertThat(board.stories())
                .as("every story, its state from its known issues, each with why")
                .extracting(
                        org.fuin.porelo.planner.store.Story::id,
                        org.fuin.porelo.planner.store.Story::state,
                        org.fuin.porelo.planner.store.Story::stateGiven,
                        org.fuin.porelo.planner.store.Story::issues,
                        org.fuin.porelo.planner.store.Story::issuesWhole,
                        org.fuin.porelo.planner.store.Story::warnings)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "ST1", "Backlog", false, List.of("XY1", "XY2"), true, List.of()),
                        org.assertj.core.groups.Tuple.tuple(
                                "ST2",
                                "",
                                false,
                                List.of("XY1"),
                                true,
                                List.of(org.fuin.porelo.planner.store.Warning.noState())),
                        org.assertj.core.groups.Tuple.tuple(
                                "ST3",
                                "Backlog",
                                false,
                                List.of("XY2"),
                                false,
                                List.of(
                                        org.fuin.porelo.planner.store.Warning
                                                .issueOutsideProject())),
                        org.assertj.core.groups.Tuple.tuple(
                                "ST4",
                                "Parked",
                                true,
                                List.of(),
                                true,
                                List.of(
                                        org.fuin.porelo.planner.store.Warning
                                                .stateOutsideWorkflow())),
                        org.assertj.core.groups.Tuple.tuple(
                                "ST5",
                                "Idea",
                                true,
                                List.of(),
                                true,
                                List.of(org.fuin.porelo.planner.store.Warning.idTwice())),
                        org.assertj.core.groups.Tuple.tuple(
                                "ST5",
                                "Idea",
                                true,
                                List.of(),
                                true,
                                List.of(org.fuin.porelo.planner.store.Warning.idTwice())));
    }

    @Test
    void aStoryIsNeverFinishedByItsIssuesWhileOneOfThemStandsOutsideTheWorkflow() {
        // Harm: a story shows as finished over an issue in doubt, which a person's own move to
        // Finished is refused for, or shows one state here and another on a GitHub board.
        final Board board =
                store(
                                List.of(
                                        story("ST1", null, "store#XY1", "store#XY3"),
                                        story("ST2", null, "store#XY1", "store#XY2")),
                                List.of(
                                        issue("XY1", "store", "Parked"),
                                        issue("XY2", "store", "Backlog"),
                                        issue("XY3", "store", "Finished")),
                                List.of())
                        .board();

        assertThat(board.stories())
                .as("no state its issues give while one is in doubt, an open one still told")
                .extracting(
                        org.fuin.porelo.planner.store.Story::id,
                        org.fuin.porelo.planner.store.Story::state,
                        org.fuin.porelo.planner.store.Story::warnings)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "ST1",
                                "",
                                List.of(org.fuin.porelo.planner.store.Warning.noState())),
                        org.assertj.core.groups.Tuple.tuple("ST2", "Backlog", List.of()));
    }

    @Test
    void aDroppedSaveThatAddedAStoryWithoutATitleNamesTheStory() {
        // Prevents a story added as ST<n>.md and dropped by a push from being told by a path
        // instead of the story the person planned, so the person can tell what is lost.
        assertThat(GitStore.dropped(null, List.of("stories/ST7.md", "stories/ST8-Titled.md")))
                .as("each added story named by its id, the untitled one as the titled one")
                .isEqualTo("ST7, ST8");
    }

    @Test
    void theBoardKeepsTheOwnRepositoryEvenWhenItsPartHasNoIssues() {
        // Story work must not pick another repository just because the own checkout has no issues.
        final PartReader reader =
                (repository, commit, project) ->
                        project == null
                                ? new Part(WORKFLOW, List.of(), List.of(), null)
                                : READER.read(repository, commit, project);
        final GitStore store =
                new GitStore(
                        List.of(OWN, OTHER),
                        remote,
                        reader,
                        inProcess(),
                        clock,
                        Duration.ofSeconds(10),
                        4,
                        queue);
        assertThat(store.board().repositories())
                .as("the store's declared repository order")
                .containsExactly("store", "other");
    }

    @Test
    void aRetiredStoreTouchesItsClonesNoMoreAndSaysWhy() {
        // A store replaced by a new source would fetch the old one into the new one's clones.
        final GitStore store = store(queue);
        store.board();
        store.retire();
        remote.heads.put("other", "c".repeat(40));
        assertThatThrownBy(store::refresh)
                .as("a refresh after the store was retired")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("given anew");
        assertThat(remote.fetched(OTHER)).as("nothing fetched after retiring").isEqualTo(1);
    }

    @Test
    void anotherRepositoryIsReadAgainOnlyWhenTheProjectsWorkflowChanged() {
        // Kept from before, its issues would stay checked against states the project no longer
        // has; read again on every own commit, a large project's check would be slow.
        final Workflow wider = Workflow.read(WORKFLOW_TEXT.replace("Backlog", "Waiting"));
        final List<List<String>> checkedAgainst = new CopyOnWriteArrayList<>();
        final PartReader reader =
                (repository, commit, project) -> {
                    if (project == null) {
                        return new Part(
                                commit.startsWith("d") ? wider : Workflow.read(WORKFLOW_TEXT),
                                List.of(),
                                List.of(),
                                null);
                    }
                    checkedAgainst.add(project.states());
                    return READER.read(repository, commit, project);
                };
        final GitStore store =
                new GitStore(
                        List.of(OWN, OTHER),
                        remote,
                        reader,
                        inProcess(),
                        clock,
                        Duration.ofSeconds(10),
                        4,
                        queue);
        store.board();
        remote.heads.put("store", "c".repeat(40));
        store.refresh();
        assertThat(checkedAgainst)
                .as("an own commit with the same workflow leaves the other's part as it was read")
                .containsExactly(WORKFLOW.states());
        remote.heads.put("store", "d".repeat(40));
        store.refresh();
        assertThat(checkedAgainst)
                .as("a changed workflow reads the other repository again against it")
                .containsExactly(WORKFLOW.states(), wider.states());
    }

    @Test
    void anUnmovedRemoteIsNotFetchedAgainAndAMovedOneIs() {
        // Fetching every repository on every check is what makes a large project slow.
        final GitStore store = store(queue);
        store.board();
        remote.heads.put("other", "c".repeat(40));
        final Board board = store.refresh();
        assertThat(remote.fetched(OWN)).as("the unmoved repository is fetched once").isEqualTo(1);
        assertThat(remote.fetched(OTHER)).as("the moved repository is fetched again").isEqualTo(2);
        assertThat(title(board, "QZ1"))
                .as("the moved repository read at its new commit")
                .isEqualTo("c".repeat(40));
        assertThat(board.revision().value())
                .as("the revision names every commit the board was read from")
                .isEqualTo(
                        GitStore.revision(
                                List.of("store", "other"),
                                Map.of("store", "a".repeat(40), "other", "c".repeat(40))));
    }

    @Test
    void aRequestIsAnsweredFromMemoryWhileACheckWaitsOnTheNetwork() throws Exception {
        // A slow or hanging remote must never hold the window's requests.
        final GitStore store = store(task -> Thread.ofVirtual().start(task));
        final Board first = store.board();
        remote.hold = new CountDownLatch(1);
        clock.now = clock.now.plusSeconds(11);
        final long start = System.nanoTime();
        final Board answered = store.board();
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("answered without waiting for the remote")
                .isLessThan(Duration.ofSeconds(2));
        assertThat(answered).as("the board in memory").isSameAs(first);
        remote.hold.countDown();
    }

    @Test
    void requestsDuringACheckShareIt() {
        // Concurrent readers must not multiply remote fetches or race their board updates.
        final GitStore store = store(queue);
        store.board();
        final int before = remote.asked.get();
        clock.now = clock.now.plusSeconds(11);
        store.board();
        store.board();
        store.board();
        assertThat(queue.tasks).as("three stale requests start one check").hasSize(1);
        queue.runAll();
        assertThat(remote.asked.get() - before).as("each remote asked once").isEqualTo(2);
    }

    @Test
    void noCheckStartsWithinTheInterval() {
        // Checking on every request would saturate the repository's remote service.
        final GitStore store = store(queue);
        store.board();
        clock.now = clock.now.plusSeconds(9);
        store.board();
        assertThat(queue.tasks).as("a recent check is trusted").isEmpty();
    }

    @Test
    void aReaderSeesTheOldBoardOrTheNewOneNeverAMix() throws Exception {
        // A board mixing two checks would show issues from commits that never stood together.
        final GitStore store = store(queue);
        store.board();
        final List<String> mixed = new CopyOnWriteArrayList<>();
        final Thread reader =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    while (!Thread.currentThread().isInterrupted()) {
                                        final Board board = store.board();
                                        final String own = title(board, "XY1");
                                        final String other = title(board, "QZ1");
                                        if (own.charAt(0) != other.charAt(0) - 1) {
                                            mixed.add(board.revision().value());
                                        }
                                    }
                                });
        for (char c = 'c'; c < 'y'; c += 2) {
            remote.heads.put("store", String.valueOf(c).repeat(40));
            remote.heads.put("other", String.valueOf((char) (c + 1)).repeat(40));
            store.refresh();
        }
        reader.interrupt();
        reader.join();
        assertThat(mixed).as("no board mixes two checks").isEmpty();
    }

    @Test
    void aRefusedLoginNamesTheRepositoryAndAFailedCheckKeepsTheBoard() {
        // A refused login must not erase the board or send credentials to the window.
        final GitStore store = store(queue);
        final Board first = store.board();
        remote.failing = GitFailure.Kind.LOGIN;
        assertThatThrownBy(store::refresh)
                .as("a refresh asked for directly")
                .isInstanceOf(StoreException.CredentialRefused.class)
                .hasMessageContaining("store");
        clock.now = clock.now.plusSeconds(11);
        assertThat(store.board()).as("a request still gets the board").isSameAs(first);
        queue.runAll();
        assertThat(store.board())
                .as("a failed check in the background keeps the board")
                .isSameAs(first);
        assertThat(store.lastFailure())
                .as("the background failure is told")
                .get()
                .isInstanceOf(StoreException.CredentialRefused.class);
    }

    @Test
    void anUnexpectedFailureOfACheckInTheBackgroundIsToldNotSwallowed() {
        // Swallowed, it would leave a stale board that looks as fresh as a checked one.
        final GitStore store =
                new GitStore(
                        List.of(OWN, OTHER),
                        remote,
                        (repository, commit, project) -> {
                            if (commit.equals("c".repeat(40))) {
                                throw new IllegalStateException("a fault nobody foresaw");
                            }
                            return READER.read(repository, commit, project);
                        },
                        inProcess(),
                        clock,
                        Duration.ofSeconds(10),
                        4,
                        queue);
        final Board first = store.board();
        remote.heads.put("other", "c".repeat(40));
        clock.now = clock.now.plusSeconds(11);
        store.board();
        queue.runAll();
        assertThat(store.board()).as("the board is kept").isSameAs(first);
        assertThat(store.lastFailure())
                .as("the failure is told")
                .get()
                .isInstanceOf(StoreException.Unreadable.class)
                .extracting(Throwable::getMessage)
                .asString()
                .contains("a fault nobody foresaw");
    }

    @Test
    void anUnreachableRemoteAndAFaultyStoreAreToldApart() {
        // A transport failure must not be mistaken for invalid stored planning data.
        final GitStore store = store(queue);
        remote.failing = GitFailure.Kind.UNREACHABLE;
        assertThatThrownBy(store::board)
                .as("no answer")
                .isInstanceOf(StoreException.Unreachable.class);
        remote.failing = GitFailure.Kind.FAILED;
        assertThatThrownBy(store::board)
                .as("a fault")
                .isInstanceOf(StoreException.Unreadable.class);
    }

    @Test
    void afterARestartTheBoardIsReadFromTheClonesAndCheckedAtOnce() {
        // The window must see its board at once after a restart, even before the network answers.
        store(queue).board();
        final int asked = remote.asked.get();
        final GitStore restarted = store(queue);
        final Board board = restarted.board();
        assertThat(remote.asked.get())
                .as("the clones answer without asking a remote")
                .isEqualTo(asked);
        assertThat(title(board, "XY1")).as("read at the fetched commit").isEqualTo("a".repeat(40));
        assertThat(queue.tasks).as("a check starts at once").hasSize(1);
    }

    @Test
    void aListenerIsToldOfAChangedBoardAlone() {
        // Unchanged boards must not produce duplicate change events for the window.
        final GitStore store = store(queue);
        final List<Board> told = new CopyOnWriteArrayList<>();
        store.listen(told::add);
        store.board();
        store.refresh();
        remote.heads.put("store", "e".repeat(40));
        store.refresh();
        assertThat(told)
                .as("the first board and the changed one, not the unchanged one")
                .hasSize(2);
    }
}
