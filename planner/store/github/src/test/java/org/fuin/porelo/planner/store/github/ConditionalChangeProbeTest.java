package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A GitHub project is read whole only once GitHub answers a conditional ask that it changed, and
 * not asked at all in the background while GitHub's rate limit is spent.
 */
class ConditionalChangeProbeTest {

    private static final String TOKEN = "invented-token-0123456789";

    private static final List<String> REPOSITORIES =
            List.of("example-project", "example-two", "example-three");

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef("example-org", 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    org.fuin.porelo.planner.store.ProjectRepository.named(REPOSITORIES),
                    Map.of(),
                    Map.of());

    private static final Duration FETCHED = Duration.ofMinutes(3);

    private static final Duration PROBED = Duration.ofSeconds(30);

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-11-20T10:00:00Z"));

    private final InstantSource clock = now::get;

    private final GitHubPace pace = new GitHubPace(clock);

    private final FakeGitHub github = new FakeGitHub();

    private final AtomicInteger reads = new AtomicInteger();

    private volatile @Nullable RuntimeException readFailure;

    /** The probes' monotonic clock, in nanoseconds; a test moves it on by hand. */
    private final AtomicLong nanos = new AtomicLong();

    private ConditionalChangeProbe probe(final ConditionalReads reads) {
        return new ConditionalChangeProbe(
                reads,
                origin -> TOKEN,
                pace,
                Runnable::run,
                ConditionalChangeProbe.DEADLINE,
                nanos::get);
    }

    private void pastTheSavesOwnWindow() {
        nanos.addAndGet(ConditionalChangeProbe.ADOPT_FOR.toNanos() + 1);
    }

    /** A store whose save changes nothing GitHub shows until a test sets the tag after it. */
    private GitHubStore savingStore() {
        return store(
                probe(github),
                new BoardWriter() {
                    @Override
                    public void save(
                            final Snapshot current, final Revision revision, final Writes writes) {
                        // GitHub's lists show the save only a moment later.
                    }
                });
    }

    private void saveNothing(final GitHubStore store) {
        store.save(store.board().revision(), new Writes(Map.of(), Map.of()));
    }

    private GitHubStore store(final ChangeProbe probe) {
        return store(probe, BoardWriter.NONE);
    }

    private GitHubStore store(final ChangeProbe probe, final BoardWriter writer) {
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(
                                config -> {
                                    final RuntimeException failure = readFailure;
                                    if (failure != null) {
                                        readFailure = null;
                                        throw failure;
                                    }
                                    return snapshot("read-" + reads.incrementAndGet());
                                },
                                states -> null)
                        .writer(writer)
                        .probe(probe),
                origin -> TOKEN,
                Duration.ofMillis(500));
    }

    /** The store after its first read and the look that learns GitHub's tags. */
    private GitHubStore warmed(final ChangeProbe probe) {
        final GitHubStore store = store(probe);
        store.refresh();
        store.poll();
        return store;
    }

    @Test
    void threeRepositoriesAndTheProjectAnsweringNotModifiedLeadToNoWholeRead() {
        // Harm: every look at the board spends the person's rate limit on a whole read.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();
        final int asksBefore = github.asks.get();

        final Board board = store.poll();
        store.poll();

        assertThat(reads.get()).as("whole reads while GitHub answers 304").isEqualTo(before);
        assertThat(github.asks.get() - asksBefore)
                .as("conditional asks: three repositories and two pages of items, twice")
                .isEqualTo(10);
        assertThat(board.revision().value()).as("the board kept").isEqualTo("read-" + before);
    }

    @Test
    void oneChangedAnswerLeadsToOneWholeRead() {
        // Harm: a change made on GitHub never reaches the window, or is read again at every look.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();

        github.issueTags.put("example-two", "\"two-2\"");
        final Board changed = store.poll();
        store.poll();

        assertThat(reads.get())
                .as("whole reads after one repository changed")
                .isEqualTo(before + 1);
        assertThat(changed.revision().value())
                .as("the board read anew")
                .isEqualTo("read-" + (before + 1));

        github.pageTags.set(1, "\"items-1b\"");
        store.poll();
        store.poll();

        assertThat(reads.get())
                .as("whole reads after a later page of the project's items changed")
                .isEqualTo(before + 2);
    }

    @Test
    void anItemPageAnsweredAnewThatTheItemListFindsUnchangedStartsNoWholeRead() {
        // Harm: GitHub answers a page of the project's items anew at every look though no item on
        // it changed, and the whole project is read again at every poll, minutes each time.
        final GitHubStore store = listingStore(nothingListed());
        store.refresh();
        store.poll();
        store.poll();
        final int before = reads.get();

        for (int look = 0; look < 3; look++) {
            github.pageTags.set(0, "\"items-0-" + look + "\"");
            store.poll();
        }

        assertThat(reads.get())
                .as("whole reads while only the item pages' tags move and the list shows nothing")
                .isEqualTo(before);

        github.issueTags.put("example-two", "\"two-2\"");
        store.poll();

        assertThat(reads.get())
                .as("whole reads after an issue outside the item list changed")
                .isEqualTo(before + 1);
    }

    @Test
    void anItemPageMoreOrFewerThatTheItemListFindsUnchangedStartsNoWholeRead() {
        // Harm: GitHub's item list grows by a page whose items the list finds unchanged, and the
        // whole project is read again, minutes long, though the board shows nothing new.
        final GitHubStore store = listingStore(nothingListed());
        store.refresh();
        store.poll();
        store.poll();
        final int before = reads.get();

        // The last page now links a next one, so GitHub gives it a new tag too.
        github.pageTags.set(1, "\"items-1b\"");
        github.pageTags.add("\"items-2\"");
        store.poll();
        store.poll();

        assertThat(reads.get())
                .as("whole reads after the item pages grew while the list shows nothing")
                .isEqualTo(before);
    }

    /** A look at GitHub's item list that names no item changed. */
    private static TrackerChanges nothingListed() {
        return (config, token, cursor, known) ->
                Optional.of(
                        new TrackerChanges.Changed(
                                Set.of(), Set.of(), Set.of(), ChangeCursor.NONE));
    }

    /** A store that looks at GitHub's item list beside the probe, as a GitHub project does. */
    private GitHubStore listingStore(final TrackerChanges changes) {
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(
                                config -> snapshot("read-" + reads.incrementAndGet()),
                                states -> null)
                        .changes(changes)
                        .probe(probe(github)),
                origin -> TOKEN,
                Duration.ofMillis(500));
    }

    @Test
    void aWholeReadThatFailedAfterAChangeIsReadAgainAtTheNextLook() {
        // Harm: a change GitHub reported is lost for good when the read after it failed, as the
        // next ask answers 304 to the tags of the failed look.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();

        github.issueTags.put("example-project", "\"project-2\"");
        readFailure = new StoreException.Unreachable("example-project", "timed out");
        assertThatThrownBy(store::poll)
                .as("the read after the change")
                .isInstanceOf(StoreException.Unreachable.class);
        final Board after = store.poll();

        assertThat(after.revision().value())
                .as("the board at the next look")
                .isEqualTo("read-" + (before + 1));
    }

    @Test
    void aPersonsReloadReadsTheWholeProjectEvenWhenGitHubAnswersUnchanged() {
        // Harm: an option or field a person adds on GitHub changes no issue and no item, so a
        // reload answered by the change check never clears the board's fault about it.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();
        final int asksBefore = github.asks.get();

        final Board reloaded = store.refresh();
        final int asksOfTheReload = github.asks.get() - asksBefore;
        store.poll();
        final int asksOfALook = github.asks.get() - asksBefore - asksOfTheReload;

        assertThat(reads.get()).as("whole reads of the reload").isEqualTo(before + 1);
        assertThat(asksOfTheReload)
                .as("conditional asks of the reload: only the look that takes its board")
                .isEqualTo(asksOfALook);
        assertThat(reloaded.revision().value())
                .as("the board of the reload")
                .isEqualTo("read-" + (before + 1));
    }

    @Test
    void aPollAfterAWriteReadsWholeRatherThanTakeTagsFromBeforeTheWrite() {
        // Harm: tags from before the planner's write are taken for the board after it, so a
        // person undoing the write on GitHub answers 304 and the window never shows it undone.
        final GitHubStore store =
                store(
                        probe(github),
                        new BoardWriter() {
                            @Override
                            public void save(
                                    final Snapshot current,
                                    final Revision revision,
                                    final Writes writes) {
                                github.issueTags.put("example-two", "\"two-3\"");
                            }
                        });
        store.refresh();
        store.poll();
        store.poll();
        github.issueTags.put("example-two", "\"two-2\"");
        store.poll();
        store.save(store.board().revision(), new Writes(Map.of(), Map.of()));
        pastTheSavesOwnWindow();
        // A person undoes the planner's write on GitHub.
        github.issueTags.put("example-two", "\"two-2\"");
        final int before = reads.get();

        final Board after = store.poll();

        assertThat(reads.get()).as("whole reads at the poll after the undo").isEqualTo(before + 1);
        assertThat(after.revision().value())
                .as("the board after the undo")
                .isEqualTo("read-" + (before + 1));
    }

    @Test
    void aSaveThatGitHubShowsOnlyAtALaterLookStartsNoWholeRead() {
        // Harm: GitHub's lists show the planner's own save a moment after it, so the look that
        // first sees it reads the whole board for a change the planner made itself.
        final GitHubStore store = savingStore();
        store.refresh();
        store.poll();
        saveNothing(store);
        final int before = reads.get();

        store.poll();
        github.issueTags.put("example-two", "\"two-2\"");
        final Board after = store.poll();
        store.poll();

        assertThat(reads.get()).as("whole reads once GitHub shows the save").isEqualTo(before);
        assertThat(after.revision().value())
                .as("the board after the save shows on GitHub")
                .isEqualTo("read-" + before);
    }

    @Test
    void aChangeSeenWithinTheSavesOwnWindowIsTakenAsTheSave() {
        // Harm: none to guard; this pins the price of taking a save's change as its own. A change
        // another makes within the window shows at the next change or reload, never at once.
        final GitHubStore store = savingStore();
        store.refresh();
        store.poll();
        saveNothing(store);
        final int before = reads.get();

        github.issueTags.put("example-two", "\"two-2\"");
        store.poll();
        final int withinTheWindow = reads.get();
        pastTheSavesOwnWindow();
        github.issueTags.put("example-two", "\"two-3\"");
        store.poll();

        assertThat(withinTheWindow).as("whole reads within the window").isEqualTo(before);
        assertThat(reads.get())
                .as("whole reads at the next change after the window")
                .isEqualTo(before + 1);
    }

    @Test
    void theAsksOfOneLookRunSideBySide() throws Exception {
        // Harm: a look at a project of many repositories takes as long as all its asks one after
        // another.
        final CountDownLatch all = new CountDownLatch(REPOSITORIES.size() + 1);
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final GitHubStore store =
                store(
                        new ConditionalChangeProbe(
                                meeting(github, all, inFlight, most, Duration.ofSeconds(2)),
                                origin -> TOKEN,
                                pace,
                                task -> Thread.ofVirtual().start(task)));
        store.refresh();
        store.poll();

        assertThat(most.get())
                .as("asks in flight at once: three repositories and the project")
                .isEqualTo(REPOSITORIES.size() + 1);
    }

    @Test
    void theAsksOfOneLookRunAtMostFourAtATime() throws Exception {
        // Harm: a project of many repositories sends GitHub a burst of asks at once, which its
        // secondary rate limit refuses.
        final List<String> many =
                List.of(
                        "example-a",
                        "example-b",
                        "example-c",
                        "example-d",
                        "example-e",
                        "example-f");
        final FakeGitHub wide = new FakeGitHub(many);
        final CountDownLatch never = new CountDownLatch(many.size() + 1);
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final ConditionalChangeProbe probe =
                new ConditionalChangeProbe(
                        meeting(wide, never, inFlight, most, Duration.ofMillis(300)),
                        origin -> TOKEN,
                        pace,
                        task -> Thread.ofVirtual().start(task));

        probe.unchanged(snapshot("read-1", configOf(many)));

        assertThat(most.get())
                .as("asks in flight at once")
                .isEqualTo(ConditionalChangeProbe.PARALLEL);
        assertThat(wide.asks.get())
                .as("asks of the look: six repositories and two pages of items")
                .isEqualTo(many.size() + 2);
    }

    @Test
    void aLowRateLimitLengthensTheIntervalAndTheBoardSaysSo() {
        // Harm: the planner spends the last of the person's rate limit at the usual pace, leaving
        // nothing for the person's own work, unseen.
        final GitHubStore store = warmed(probe(github));
        assertThat(store.pollDelay(FETCHED, PROBED)).as("the usual wait").isEqualTo(PROBED);

        github.remaining = 400L;
        final Board board = store.poll();

        assertThat(store.pollDelay(FETCHED, PROBED))
                .as("the wait with 400 of 5000 left")
                .isEqualTo(PROBED.multipliedBy(GitHubPace.LENGTHENED));
        assertThat(board.faults())
                .as("the board's faults")
                .anySatisfy(fault -> assertThat(fault).contains("rate limit is low (400 of 5000"));
    }

    @Test
    void aSpentRateLimitHoldsBackgroundReadsAndServesTheBoardKeptUntilTheWaitEnds() {
        // Harm: the planner asks GitHub again and again while it refuses, the window loses its
        // board, and the person is never told when GitHub is read again.
        final GitHubStore store = warmed(probe(github));
        final int asksBefore = github.asks.get();
        final String kept = store.board().revision().value();

        github.issueTags.put("example-two", "\"two-2\"");
        github.failure =
                GitHubExceptions.rateLimited(403, "rate limit exceeded", Duration.ofSeconds(120));
        final Board first = store.poll();
        final int asksAtTheRefusal = github.asks.get();
        now.set(now.get().plusSeconds(60));
        final Board held = store.poll();
        store.poll();

        assertThat(first.revision().value()).as("the board at the refusal").isEqualTo(kept);
        assertThat(held.revision().value()).as("the board while held").isEqualTo(kept);
        assertThat(github.asks.get())
                .as("asks while held")
                .isEqualTo(asksAtTheRefusal)
                .isGreaterThan(asksBefore);
        assertThat(held.faults())
                .as("the board's faults while held")
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .contains("rate limit is spent")
                                        .contains("2026-11-20T10:02:00Z"));
        assertThat(store.pollDelay(FETCHED, PROBED))
                .as("the wait while held")
                .isEqualTo(Duration.ofSeconds(60));

        github.failure = null;
        now.set(now.get().plusSeconds(61));
        final Board after = store.poll();

        assertThat(github.asks.get())
                .as("asks once the wait ended")
                .isGreaterThan(asksAtTheRefusal);
        assertThat(after.faults()).as("the board's faults once the wait ended").isEmpty();
    }

    @Test
    void aRateLimitAnyAskMetHoldsThoughAnEarlierAskFailedOtherwise() {
        // Harm: a refusal of the first repository hides GitHub's spent rate limit on another,
        // so the whole read is sent at once and GitHub is asked again at every poll.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();
        final String kept = store.board().revision().value();

        github.failures.put("example-project", GitHubExceptions.refused(502, "Bad Gateway"));
        github.failures.put(
                "example-three",
                GitHubExceptions.rateLimited(403, "rate limit exceeded", Duration.ofSeconds(120)));
        final Board board = store.poll();

        assertThat(reads.get()).as("whole reads after the look").isEqualTo(before);
        assertThat(pace.hold()).as("the hold after the look").isNotNull();
        assertThat(board.revision().value()).as("the board served").isEqualTo(kept);
    }

    @Test
    void noWholeReadIsSentWhileAnotherProjectFoundTheRateLimitSpent() {
        // Harm: a look that failed while another project of the same token met the spent limit
        // sends the whole read GitHub refuses, spending what is left of the person's limit.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();

        github.during = () -> pace.spent(Duration.ofSeconds(90));
        github.failures.put("example-two", GitHubExceptions.refused(502, "Bad Gateway"));
        store.poll();

        assertThat(reads.get()).as("whole reads after the look").isEqualTo(before);
        assertThat(pace.hold()).as("the hold another project set").isNotNull();
    }

    @Test
    void aLookPastItsDeadlineAsksNoMorePages() throws Exception {
        // Harm: asks keep going out to GitHub in the background long after their look gave up.
        final FakeGitHub slow = new FakeGitHub(REPOSITORIES);
        for (int page = 2; page < 8; page++) {
            slow.pageTags.add("\"items-" + page + "\"");
        }
        slow.pageWait = Duration.ofMillis(100);
        final ConditionalChangeProbe probe =
                new ConditionalChangeProbe(
                        slow,
                        origin -> TOKEN,
                        pace,
                        task -> Thread.ofVirtual().start(task),
                        Duration.ofMillis(250));

        final boolean unchanged = probe.unchanged(snapshot("read-1"));
        final int pagesAtTheEnd = slow.pageAsks.get();
        Thread.sleep(600);

        assertThat(unchanged).as("the look past its deadline").isFalse();
        assertThat(slow.pageAsks.get())
                .as("pages asked after the look ended")
                .isLessThanOrEqualTo(pagesAtTheEnd + 1);
        assertThat(slow.pageAsks.get()).as("pages asked of eight").isLessThan(8);
    }

    @Test
    void theBoardsFaultsAreAnsweredWhileALookWaitsOnGitHub() throws Exception {
        // Harm: every read of the board kept, by every window, waits as long as GitHub takes to
        // answer a background look.
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch asking = new CountDownLatch(1);
        final ConditionalChangeProbe probe = probe(github);
        final GitHubStore store = warmed(probe);
        github.during =
                () -> {
                    asking.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        final Thread look = Thread.ofVirtual().start(store::poll);
        try {
            assertThat(asking.await(5, TimeUnit.SECONDS)).as("the look asking GitHub").isTrue();
            final java.util.concurrent.CompletableFuture<List<String>> faults =
                    java.util.concurrent.CompletableFuture.supplyAsync(probe::faults);

            assertThat(faults.get(1, TimeUnit.SECONDS))
                    .as("the faults while the look waits")
                    .isEmpty();
        } finally {
            release.countDown();
            look.join();
        }
    }

    @Test
    void aNotModifiedWithoutATagKeepsTheTagSent() {
        // Harm: an answer of 304 that names no tag makes the next look send none, so GitHub
        // answers in full and the whole project is read for nothing.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();

        github.tagOnNotModified = false;
        store.poll();
        store.poll();

        assertThat(reads.get()).as("whole reads over two unchanged looks").isEqualTo(before);
    }

    @Test
    void aPageAfterAFullLastPageIsSeenThoughThatPageAnswersUnchanged() {
        // Harm: an item added behind a full last page starts a page the probe never asks for, as
        // the full page's own tag stays, so the board never shows the item.
        github.fullPages.add(1);
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();

        store.poll();
        assertThat(reads.get())
                .as("whole reads while the full last page is all there is")
                .isEqualTo(before);

        github.pageTags.add("\"items-2\"");
        store.poll();

        assertThat(reads.get())
                .as("whole reads once a page follows the full last page")
                .isEqualTo(before + 1);
    }

    @Test
    void theFoldersTheProjectFileNamesReachADiscussionsBriefThroughTheBoard() {
        // Harm: a discussion over a tracked project sends its agent to folders Porelo never made.
        final GitHubStore store =
                overGitHub(
                        null,
                        List.of(
                                new org.fuin.porelo.planner.store.ProjectRepository(
                                        "example-project", "example"),
                                new org.fuin.porelo.planner.store.ProjectRepository(
                                        "example-two", "lib"),
                                new org.fuin.porelo.planner.store.ProjectRepository(
                                        "example-three", "three")),
                        new Work(
                                Map.of(
                                        "Idea",
                                        new org.fuin.porelo.planner.store.WorkRule(
                                                List.of("Idea"),
                                                "Finished",
                                                null,
                                                null,
                                                org.fuin.porelo.planner.store.WorkRule.Per
                                                        .REPOSITORY,
                                                List.of("AGENT"),
                                                "Build {story}: {issues}; {tasks}",
                                                Map.of("codex", "model-a"),
                                                Roles.AUTHOR)),
                                List.of()));

        assertThat(
                        org.fuin.porelo.planner.core.IdeaDiscussion.offer(
                                        store.refresh(), "An idea")
                                .prompt())
                .as("each repository beside the folder the project file names")
                .contains(
                        "`example-project`, read at `/workspace/example/`",
                        "`example-two`, read at `/workspace/lib/`");
    }

    @Test
    void aSpentRateLimitOfTheWholeReadHoldsTheBackgroundReads() {
        // Harm: GitHub's GraphQL refusal of a spent limit is asked again at every poll.
        final GitHubStore store = overGitHub();
        store.refresh();
        final int asked = graphQl.get();
        final int probed = github.asks.get();

        graphQlFailure = GitHubExceptions.rateLimited(403, "spent", Duration.ofSeconds(90));
        github.issueTags.put("example-two", "\"two-2\"");
        final Board first = store.poll();
        final int askedAtTheRefusal = graphQl.get();
        store.poll();
        store.poll();

        assertThat(askedAtTheRefusal).as("whole read asks at the refusal").isGreaterThan(asked);
        assertThat(graphQl.get()).as("whole read asks while held").isEqualTo(askedAtTheRefusal);
        assertThat(github.asks.get() - probed)
                .as("conditional asks: three repositories and two pages of items, once")
                .isEqualTo(REPOSITORIES.size() + 2);
        assertThat(first.faults())
                .as("the board's faults at the refusal")
                .anySatisfy(fault -> assertThat(fault).contains("rate limit is spent"));

        graphQlFailure = null;
        now.set(now.get().plusSeconds(91));
        store.poll();

        assertThat(graphQl.get())
                .as("whole read asks once the wait ended")
                .isGreaterThan(askedAtTheRefusal);
    }

    @Test
    void aRateLimitOneProjectsReadWaitsOutHoldsNoOtherProjectOfTheOpener() {
        // Harm: the opener's reader wired to note every rate limit it meets, so a short wait one
        // project's read takes and goes on from tells every other project's board that it is the
        // one read before, and holds its looks, as before the reader held the pace itself.
        final GitHubStoreOpener opener = opener();
        final List<org.fuin.porelo.planner.store.ProjectRepository> repositories =
                org.fuin.porelo.planner.store.ProjectRepository.named(REPOSITORIES);
        final TrackedProject first = tracked("7", repositories, new Work(Map.of(), List.of()));
        final TrackedProject second = tracked("8", repositories, new Work(Map.of(), List.of()));
        final GitHubStore one = (GitHubStore) opener.open(first, () -> first);
        final GitHubStore other = (GitHubStore) opener.open(second, () -> second);
        other.refresh();
        final List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        graphQlOnce.set(
                GitHubExceptions.rateLimited(403, "secondary rate limit", Duration.ofSeconds(1)));
        onFields = () -> seen.addAll(other.board().faults());

        one.refresh();

        assertThat(graphQlOnce.get()).as("the short rate limit met").isNull();
        assertThat(seen)
                .as("the other project's board while the read goes on after its wait")
                .noneSatisfy(fault -> assertThat(fault).contains("rate limit is spent"));
    }

    @Test
    void aSpentRateLimitOfTheItemLookHoldsThePolls() {
        // Harm: GitHub's REST refusal of a spent limit fails every poll and is asked again at
        // each, as the look did not hold the token's pace.
        final AtomicInteger listed = new AtomicInteger();
        final GitHubStore store =
                overGitHub(
                        (token, owner, number, after, perPage, fieldIds, etag) -> {
                            listed.incrementAndGet();
                            throw GitHubExceptions.rateLimited(
                                    403, "spent", Duration.ofSeconds(90));
                        });
        store.refresh();

        final Board first = store.poll();
        store.poll();

        assertThat(first.faults())
                .as("the board's faults at the refusal")
                .anySatisfy(fault -> assertThat(fault).contains("rate limit is spent"));
        assertThat(listed.get()).as("asks of the item list").isEqualTo(1);
    }

    @Test
    void aPersonsReloadWhileHeldAsksOnceAndGetsTheSameRefusal() {
        // Harm: the person's own reload silently answered from the board kept, as GitHub answers
        // 304 to the change check even while its rate limit is spent, or asking without end.
        final GitHubStore store = overGitHub();
        store.refresh();
        store.poll();
        store.poll();
        graphQlFailure =
                GitHubExceptions.rateLimited(403, "rate limit exceeded", Duration.ofSeconds(120));
        github.failure = graphQlFailure;
        final int asked = graphQl.get();

        assertThatThrownBy(store::refresh)
                .as("the person's reload")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageContaining("GitHub's rate limit is spent");
        final int askedAtTheRefusal = graphQl.get();
        store.poll();
        assertThatThrownBy(store::refresh)
                .as("the person's reload while held")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageContaining("GitHub's rate limit is spent");

        assertThat(askedAtTheRefusal - asked)
                .as("whole read asks of the first reload")
                .isEqualTo(1);
        assertThat(graphQl.get() - askedAtTheRefusal)
                .as("whole read asks of the poll and the reload while held")
                .isEqualTo(1);
    }

    @Test
    void aChangeCheckGitHubRefusesReadsWholeAndNamesNoToken() {
        // Harm: the person's token shown on the board, or a refused change check answered as
        // unchanged so the board never moves.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();

        github.failure = GitHubExceptions.refused(404, "Not Found for Bearer " + TOKEN);
        final Board board = store.poll();

        assertThat(reads.get()).as("whole reads after the refused check").isEqualTo(before + 1);
        assertThat(board.faults())
                .as("the board's faults")
                .anySatisfy(fault -> assertThat(fault).contains("HTTP 404"))
                .allSatisfy(fault -> assertThat(fault).doesNotContain(TOKEN));
    }

    @Test
    void theBoardAfterAWriteIsReadWholeEvenWhenGitHubAnswersUnchanged() {
        // Harm: a field value the planner wrote that the change check does not see leaves the
        // window showing the move undone.
        final GitHubStore store =
                store(
                        current -> true,
                        new BoardWriter() {
                            @Override
                            public void save(
                                    final Snapshot current,
                                    final Revision revision,
                                    final Writes writes) {
                                // Written; GitHub's answer to the change check is unchanged.
                            }
                        });
        store.refresh();
        final int before = reads.get();

        final Board saved = store.save(new Revision("read-1"), new Writes(Map.of(), Map.of()));

        assertThat(reads.get()).as("whole reads after the write").isEqualTo(before + 1);
        assertThat(saved.revision().value()).as("the board after the write").isEqualTo("read-2");
    }

    @Test
    void theBoardOfAWriteTellsTheSpentRateLimit() {
        // Harm: the board a write answers drops the spent rate limit, so the window shows no
        // fault while GitHub holds the background reads.
        final GitHubStore store =
                store(
                        probe(github),
                        new BoardWriter() {
                            @Override
                            public void save(
                                    final Snapshot current,
                                    final Revision revision,
                                    final Writes writes) {
                                // Written.
                            }
                        });
        store.refresh();
        pace.spent(Duration.ofSeconds(120));

        final Board saved = store.save(new Revision("read-1"), new Writes(Map.of(), Map.of()));

        assertThat(saved.faults())
                .as("the faults of the board the write answers")
                .anySatisfy(fault -> assertThat(fault).contains("rate limit is spent"));
    }

    /** Conditional reads that count how many asks are in flight at once, each waiting a while. */
    private static ConditionalReads meeting(
            final ConditionalReads github,
            final CountDownLatch all,
            final AtomicInteger inFlight,
            final AtomicInteger most,
            final Duration wait) {
        return new ConditionalReads() {
            @Override
            public Answer issues(
                    final String token,
                    final String owner,
                    final String repository,
                    final @Nullable String etag) {
                meet();
                return github.issues(token, owner, repository, etag);
            }

            @Override
            public Answer projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after,
                    final @Nullable String etag) {
                if (after == null) {
                    meet();
                }
                return github.projectItems(token, owner, number, after, etag);
            }

            private void meet() {
                most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                all.countDown();
                try {
                    all.await(wait.toMillis(), TimeUnit.MILLISECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
            }
        };
    }

    /** GraphQL reads of an empty project, counted, refused as {@link #graphQlFailure} says. */
    private final AtomicInteger graphQl = new AtomicInteger();

    private volatile @Nullable RuntimeException graphQlFailure;

    /** The next GraphQL read's refusal, once. */
    private final java.util.concurrent.atomic.AtomicReference<@Nullable RuntimeException>
            graphQlOnce = new java.util.concurrent.atomic.AtomicReference<>();

    /** Runs at each GraphQL read of a project's fields that is answered. */
    private volatile Runnable onFields = () -> {};

    private ProjectQueries queries() {
        return new ProjectQueries() {
            @Override
            public GitHubGraphQl.ProjectFields projectFields(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                graphQl.incrementAndGet();
                final RuntimeException refused = graphQlFailure;
                if (refused != null) {
                    throw refused;
                }
                final RuntimeException once = graphQlOnce.getAndSet(null);
                if (once != null) {
                    throw once;
                }
                onFields.run();
                return new GitHubGraphQl.ProjectFields(
                        "PVT_1",
                        "Plan",
                        Instant.EPOCH,
                        List.of(),
                        new GitHubGraphQl.PageInfo(false, null),
                        COST);
            }

            @Override
            public GitHubGraphQl.ProjectItems projectItems(
                    final String token,
                    final String owner,
                    final int number,
                    final @Nullable String after) {
                graphQl.incrementAndGet();
                return new GitHubGraphQl.ProjectItems(
                        List.of(), new GitHubGraphQl.PageInfo(false, null), COST, 0);
            }

            @Override
            public GitHubGraphQl.IssueBoard issueBoard(
                    final String token, final List<String> issueIds, final String projectId) {
                throw new AssertionError("An empty project has no sub-issue to look up");
            }
        };
    }

    /** A store opened as the planner opens one, over GraphQL reads and the fake's 304s. */
    private GitHubStore overGitHub() {
        return overGitHub(null);
    }

    /**
     * A store opened as the planner opens one, looking at the item list given for changes.
     *
     * @param pages the REST item list, or null to look at none
     */
    private GitHubStore overGitHub(final GitHubChanges.@Nullable ItemPages pages) {
        return overGitHub(
                pages,
                org.fuin.porelo.planner.store.ProjectRepository.named(REPOSITORIES),
                new Work(Map.of(), List.of()));
    }

    /**
     * A store opened as the planner opens one, for a project's repositories and work rules.
     *
     * @param pages the REST item list, or null to look at none
     * @param repositories the repositories as the project's files name them
     * @param work the work rules
     */
    private GitHubStore overGitHub(
            final GitHubChanges.@Nullable ItemPages pages,
            final List<org.fuin.porelo.planner.store.ProjectRepository> repositories,
            final Work work) {
        final TrackedProject project = tracked("7", repositories, work);
        final GitHubStoreOpener opener = opener();
        return (GitHubStore)
                (pages == null ? opener : opener.lookingForChanges(pages, queries()))
                        .open(project, () -> project);
    }

    /** A project of the fake GitHub, by its number. */
    private static TrackedProject tracked(
            final String number,
            final List<org.fuin.porelo.planner.store.ProjectRepository> repositories,
            final Work work) {
        return new TrackedProject(
                URI.create("https://github.com"),
                "example-org",
                "github",
                number,
                repositories,
                WORKFLOW,
                work,
                "c0ffee0",
                states -> null);
    }

    /** The opener as the planner makes one, its projects sharing one pace of the token. */
    private GitHubStoreOpener opener() {
        return new GitHubStoreOpener(
                origin -> TOKEN,
                queries(),
                (ProjectWrites)
                        java.lang.reflect.Proxy.newProxyInstance(
                                ProjectWrites.class.getClassLoader(),
                                new Class<?>[] {ProjectWrites.class},
                                (proxy, method, args) -> {
                                    // The organization's issue fields are a read.
                                    if (method.getName().equals("issueFields")) {
                                        return List.of();
                                    }
                                    throw new AssertionError("A read called " + method.getName());
                                }),
                github,
                // Every repository holds no open issue outside the project.
                (token, owner, repository, page, etag) ->
                        new org.fuin.porelo.forge.github.Conditional<>(
                                new org.fuin.porelo.forge.github.Page<
                                        org.fuin.porelo.forge.github.model.Issue>(List.of(), false),
                                null,
                                true,
                                null,
                                Instant.EPOCH),
                Runnable::run,
                clock,
                Duration.ofMillis(500));
    }

    private static Snapshot snapshot(final String revision) {
        return snapshot(revision, CONFIG);
    }

    private static Snapshot snapshot(final String revision, final ProjectConfig config) {
        return new Snapshot(
                new Board(
                        new Revision(revision),
                        WORKFLOW,
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of()),
                        config.repositories()),
                Map.of(),
                config);
    }

    private static ProjectConfig configOf(final List<String> repositories) {
        return new ProjectConfig(
                CONFIG.forge(),
                CONFIG.project(),
                CONFIG.commit(),
                WORKFLOW,
                CONFIG.work(),
                org.fuin.porelo.planner.store.ProjectRepository.named(repositories),
                Map.of(),
                Map.of());
    }

    /**
     * GitHub's conditional answers: each repository's issues and each page of the project's items
     * hold a tag, answered 304 when it is the one sent, even while the rate limit is spent.
     */
    private static final class FakeGitHub implements ConditionalReads {

        private final Map<String, String> issueTags = new ConcurrentHashMap<>();

        private final List<String> pageTags =
                new CopyOnWriteArrayList<>(List.of("\"items-0\"", "\"items-1\""));

        /** The pages that hold as many items as a page asks for. */
        private final java.util.Set<Integer> fullPages = ConcurrentHashMap.newKeySet();

        private final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();

        private final AtomicInteger asks = new AtomicInteger();

        private final AtomicInteger pageAsks = new AtomicInteger();

        private volatile @Nullable RuntimeException failure;

        private volatile @Nullable Runnable during;

        private volatile Duration pageWait = Duration.ZERO;

        private volatile boolean tagOnNotModified = true;

        private volatile long remaining = 4900L;

        FakeGitHub() {
            this(REPOSITORIES);
        }

        FakeGitHub(final List<String> repositories) {
            repositories.forEach(name -> issueTags.put(name, "\"" + name + "-1\""));
        }

        @Override
        public Answer issues(
                final String token,
                final String owner,
                final String repository,
                final @Nullable String etag) {
            assertThat(token).as("the token sent").isEqualTo(TOKEN);
            assertThat(owner).as("the owner asked").isEqualTo("example-org");
            final Runnable meanwhile = during;
            if (meanwhile != null) {
                meanwhile.run();
            }
            final RuntimeException refused = failures.get(repository);
            if (refused != null) {
                asks.incrementAndGet();
                throw refused;
            }
            return answer(issueTags.get(repository), etag, null, false);
        }

        @Override
        public Answer projectItems(
                final String token,
                final String owner,
                final int number,
                final @Nullable String after,
                final @Nullable String etag) {
            assertThat(number).as("the project asked").isEqualTo(7);
            pageAsks.incrementAndGet();
            if (!pageWait.isZero()) {
                try {
                    Thread.sleep(pageWait.toMillis());
                } catch (final InterruptedException e) {
                    // A cancelled ask is not interrupted; the fake answers as GitHub would.
                }
            }
            final int page = after == null ? 0 : Integer.parseInt(after.substring(1));
            final String next = page + 1 < pageTags.size() ? "p" + (page + 1) : null;
            return answer(pageTags.get(page), etag, next, fullPages.contains(page));
        }

        private Answer answer(
                final @Nullable String current,
                final @Nullable String sent,
                final @Nullable String next,
                final boolean full) {
            asks.incrementAndGet();
            final boolean same = sent != null && sent.equals(current);
            final RuntimeException refused = failure;
            // GitHub answers 304 to a tag it holds even while the rate limit is spent.
            if (refused != null
                    && !(same
                            && refused instanceof org.fuin.porelo.forge.github.GitHubException g
                            && g.rateLimited())) {
                throw refused;
            }
            if (same) {
                return new Answer(false, tagOnNotModified ? current : null, null, remaining, 5000L);
            }
            return new Answer(true, current, next, remaining, 5000L, full);
        }
    }

    /** GitHub's issue lists of repositories outside the project, by their names with owners. */
    private static final class WaitedRepositories implements ConditionalReads {

        private final Map<String, String> tags = new ConcurrentHashMap<>();

        private final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();

        private final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public Answer issues(
                final String token,
                final String owner,
                final String repository,
                final @Nullable String etag) {
            assertThat(token).as("the token sent").isEqualTo(TOKEN);
            asked.add(owner + "/" + repository + " " + etag);
            final RuntimeException refused = failures.get(owner + "/" + repository);
            if (refused != null) {
                throw refused;
            }
            final String tag = tags.get(owner + "/" + repository);
            return new Answer(tag == null || !tag.equals(etag), tag, null, 4900L, 5000L);
        }

        @Override
        public Answer projectItems(
                final String token,
                final String owner,
                final int number,
                final @Nullable String after,
                final @Nullable String etag) {
            throw new AssertionError("A look at the waits asked the project's items");
        }
    }

    /** A board whose issues wait on issues of the repositories named, with their owners. */
    private static Snapshot waitingOn(final String... repositories) {
        final Map<String, Snapshot.Waited> waits = new java.util.LinkedHashMap<>();
        for (int at = 0; at < repositories.length; at++) {
            waits.put("I_wait_" + at, new Snapshot.Waited(repositories[at], at + 1, false));
        }
        final Snapshot plain = snapshot("waiting");
        return new Snapshot(
                plain.board(),
                plain.items(),
                plain.config(),
                plain.raw(),
                plain.fieldFaults(),
                plain.fieldNames(),
                plain.restFieldIds(),
                waits,
                plain.unlisted());
    }

    @Test
    void eachRepositoryOutsideTheProjectAWaitStandsInIsAskedOnceALookWithItsTag() {
        // Harm: a blocker closed in another repository is never seen, so its issue is never
        // offered; or every look reads the waits again though nothing there changed.
        final WaitedRepositories waited = new WaitedRepositories();
        waited.tags.put("other-org/elsewhere", "\"e-1\"");
        final ConditionalChangeProbe probe = probe(waited);
        final Snapshot board =
                waitingOn("other-org/elsewhere", "other-org/elsewhere", "example-org/example-two");

        assertThat(probe.waitsChanged(board)).as("the first answer, with no tag").isTrue();
        probe.waitsRead();
        assertThat(probe.waitsChanged(board)).as("answered 304").isFalse();
        nanos.addAndGet(ConditionalChangeProbe.WAITS_EVERY.toNanos());
        waited.tags.put("other-org/elsewhere", "\"e-2\"");
        assertThat(probe.waitsChanged(board)).as("changed there").isTrue();
        assertThat(probe.waitsChanged(board))
                .as("asked again while the waits were not read after the change")
                .isTrue();
        probe.waitsRead();
        assertThat(probe.waitsChanged(board)).as("read after the change").isFalse();
        assertThat(waited.asked)
                .as("one ask a look, never one for a repository of the project")
                .containsExactly(
                        "other-org/elsewhere null",
                        "other-org/elsewhere \"e-1\"",
                        "other-org/elsewhere \"e-1\"",
                        "other-org/elsewhere \"e-1\"",
                        "other-org/elsewhere \"e-2\"");
    }

    @Test
    void moreRepositoriesThanALookAsksAboutReadTheWaitsAgainOnceAMinute() {
        // Harm: a look that asks about only some repositories takes the rest as unchanged, or
        // every look reads every wait and spends the person's rate limit.
        final WaitedRepositories waited = new WaitedRepositories();
        final String[] many = new String[ConditionalChangeProbe.MOST_WAIT_REPOSITORIES + 1];
        for (int at = 0; at < many.length; at++) {
            many[at] = "other-org/lib-" + at;
        }
        final ConditionalChangeProbe probe = probe(waited);
        final Snapshot board = waitingOn(many);

        assertThat(probe.waitsChanged(board)).as("the waits read again").isTrue();
        probe.waitsRead();
        nanos.addAndGet(ConditionalChangeProbe.WAITS_EVERY.toNanos() - 1);
        assertThat(probe.waitsChanged(board)).as("not again within the minute").isFalse();
        nanos.addAndGet(1);
        assertThat(probe.waitsChanged(board)).as("again once the minute passed").isTrue();
        assertThat(waited.asked).as("no repository asked").isEmpty();
    }

    @Test
    void aRepositoryTheTokenCannotReadIsAskedOnceAWholeReadAndIsNoChange() {
        // Harm: a repository the person cannot read seen as changed at every look, so every look
        // reads the waits again, or a failed look.
        final WaitedRepositories waited = new WaitedRepositories();
        waited.failures.put("other-org/private", GitHubExceptions.refused(404, "Not Found"));
        final ConditionalChangeProbe probe = probe(waited);
        final Snapshot board = waitingOn("other-org/private");

        assertThat(probe.waitsChanged(board)).as("the first look").isFalse();
        assertThat(probe.waitsChanged(board)).as("the next look").isFalse();
        probe.readWhole();
        assertThat(probe.waitsChanged(board)).as("the look after a whole read").isFalse();
        assertThat(waited.asked)
                .as("asked once, then again after the whole read")
                .containsExactly("other-org/private null", "other-org/private null");
    }

    @Test
    void aChangedRepositoryIsTakenAtMostOnceAMinute() {
        // Harm: a repository whose issues change all the time has every look read the waits
        // again and spends the person's rate limit.
        final WaitedRepositories waited = new WaitedRepositories();
        waited.tags.put("other-org/busy", "\"b-1\"");
        final ConditionalChangeProbe probe = probe(waited);
        final Snapshot board = waitingOn("other-org/busy");
        assertThat(probe.waitsChanged(board)).as("the first look").isTrue();
        probe.waitsRead();

        waited.tags.put("other-org/busy", "\"b-2\"");
        assertThat(probe.waitsChanged(board)).as("changed within the minute").isFalse();
        nanos.addAndGet(ConditionalChangeProbe.WAITS_EVERY.toNanos());
        assertThat(probe.waitsChanged(board)).as("the same change once the minute passed").isTrue();
    }

    @Test
    void aLookOfUpToTenPagesOfItemsHasTheDeadline() {
        // Harm: a look on a small board given more time than it needs.
        final ConditionalChangeProbe probe = probe(github);

        assertThat(probe.budget(0)).as("an empty board").isEqualTo(ConditionalChangeProbe.DEADLINE);
        final int tenPages = ConditionalReads.PAGE_SIZE * ConditionalChangeProbe.PAGES_IN_DEADLINE;
        assertThat(probe.budget(tenPages))
                .as("exactly ten pages")
                .isEqualTo(ConditionalChangeProbe.DEADLINE);
    }

    @Test
    void aLookBeyondTenPagesOfItemsGetsAShareOfTheDeadlineForEachPage() {
        // Harm: a look on a large board can never finish in time, keeps no tags, and so every
        // look after it reads the whole board.
        final ConditionalChangeProbe probe = probe(github);
        final int pageSize = ConditionalReads.PAGE_SIZE;

        assertThat(probe.budget(pageSize * ConditionalChangeProbe.PAGES_IN_DEADLINE + 1))
                .as("one item past ten pages, eleven pages")
                .isEqualTo(ConditionalChangeProbe.DEADLINE.multipliedBy(11).dividedBy(10));
        assertThat(probe.budget(1750))
                .as("1750 items, eighteen pages")
                .isEqualTo(ConditionalChangeProbe.DEADLINE.multipliedBy(18).dividedBy(10))
                .isEqualTo(Duration.ofSeconds(54));
    }
}
