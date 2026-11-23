package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.fuin.porelo.forge.github.Conditional;
import org.fuin.porelo.forge.github.CursorPage;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.forge.github.ProjectItemSummary;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.cache.BoardCopies;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.CopyFiles;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A look for changes folded into the board through a poll, over GitHub's REST item list and its
 * reads by id, with the planner's own saves made beside it.
 */
class GitHubStoreLookTest {

    private static final String TOKEN = "invented-token-look-0123456789";

    private static final String OWNER = "example-org";

    private static final String REPOSITORY = OWNER + "/example-project";

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef(OWNER, 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of("example-project"),
                    Map.of(),
                    Map.of());

    private static final BoardCopy.Project PROJECT = new BoardCopy.Project("github.com", OWNER, 7);

    private static final GraphQlCost COST = new GraphQlCost(1, 4999, Instant.EPOCH);

    private static final GitHubGraphQl.PageInfo NONE = new GitHubGraphQl.PageInfo(false, null);

    private static final Instant BEFORE = Instant.parse("2026-11-20T10:00:00Z");

    private static final Instant SAVED = Instant.parse("2026-11-21T10:00:00Z");

    private static final Instant LATER = Instant.parse("2026-11-21T11:00:00Z");

    /** GitHub's own time on its answers. */
    private static final Instant DATE = Instant.parse("2026-11-21T12:00:00Z");

    /** The status field's numeric id, as REST takes it. */
    private static final long STATUS_ID = 11L;

    @TempDir Path directory;

    private final CopyFiles files =
            new CopyFiles() {
                @Override
                public FileAttribute<?>[] ownerOnlyFile() {
                    return new FileAttribute<?>[0];
                }

                @Override
                public void requireOwnFile(final Path file, final boolean alone) {
                    if (Files.isSymbolicLink(file)) {
                        throw new IllegalStateException("a link");
                    }
                }
            };

    /** One issue on GitHub and its item in the project, as GitHub holds it now. */
    private static final class Held {
        private final String item;
        private final String id;
        private final int number;
        private final boolean story;
        private String title;
        private Instant at = BEFORE;
        private Instant itemAt = BEFORE;
        private boolean archived;
        private boolean onProject = true;
        private String option = "O_idea";

        /** A value of a field the planner does not read, which moves only the item's time. */
        private String note = "";

        private @Nullable String parent;
        private final List<String> subs = new ArrayList<>();

        Held(final int number, final boolean story, final String title) {
            this.item = "PVTI_" + number;
            this.id = "I_" + number;
            this.number = number;
            this.story = story;
            this.title = title;
        }
    }

    /**
     * GitHub's project: its REST item list and its whole read may each lag what an issue read by id
     * shows, as GitHub's lists show a save a moment after it.
     */
    private static final class GitHub implements ProjectQueries {

        private final Map<String, Held> issues = new LinkedHashMap<>();

        private final List<String> calls = new ArrayList<>();

        /** The REST list as it lags, or null while it shows GitHub as it is. */
        private @Nullable List<ProjectItemSummary> lateList;

        /** The whole read as it lags, or null while it shows GitHub as it is. */
        private @Nullable List<GitHubGraphQl.ProjectItem> lateRead;

        /** GitHub's failure of the REST list, or null while it answers. */
        private @Nullable RuntimeException listFailure;

        /** GitHub's failure of a read by id of the issue #2, or null while it answers. */
        private @Nullable RuntimeException boardFailure;

        /** The reads by id of the issue #2 answered before the failure. */
        private int boardCallsBeforeFailure;

        /** The items the REST list does not carry yet, as GitHub's list lags an item just made. */
        private final java.util.Set<String> unlisted = new java.util.HashSet<>();

        /** Run as the list is asked, before it answers. */
        private Runnable onList = () -> {};

        /** Run as the whole read asks for the items, before they are answered. */
        private Runnable onItems = () -> {};

        /** Whether a page of the list was answered. */
        private final AtomicBoolean answered = new AtomicBoolean();

        Held add(final int number, final boolean story, final String title) {
            final Held held = new Held(number, story, title);
            issues.put(held.id, held);
            return held;
        }

        void lagList() {
            lateList = summaries();
        }

        void lagRead() {
            lateRead = items();
        }

        @Override
        public GitHubGraphQl.ProjectFields projectFields(
                final String token, final String owner, final int number, final String after) {
            calls.add("projectFields");
            return new GitHubGraphQl.ProjectFields(
                    "PVT_7",
                    "Plan",
                    Instant.EPOCH,
                    List.of(
                            new GitHubGraphQl.ProjectField(
                                    "F_status",
                                    FieldNames.STATUS,
                                    "SINGLE_SELECT",
                                    List.of(
                                            new GitHubGraphQl.FieldOption(
                                                    "O_idea", "Idea", "GRAY", ""),
                                            new GitHubGraphQl.FieldOption(
                                                    "O_finished", "Finished", "GRAY", "")),
                                    STATUS_ID),
                            new GitHubGraphQl.ProjectField(
                                    "F_note", "Note", "TEXT", List.of(), 12L)),
                    NONE,
                    COST);
        }

        @Override
        public GitHubGraphQl.ProjectItems projectItems(
                final String token, final String owner, final int number, final String after) {
            calls.add("projectItems");
            onItems.run();
            final List<GitHubGraphQl.ProjectItem> items = lateRead != null ? lateRead : items();
            return new GitHubGraphQl.ProjectItems(items, NONE, COST, items.size());
        }

        @Override
        public GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            calls.add("issueBoard of " + issueIds);
            if (boardFailure != null
                    && issueIds.contains("I_2")
                    && boardCallsBeforeFailure-- <= 0) {
                throw boardFailure;
            }
            assertThat(projectId).as("the project's node id").isEqualTo("PVT_7");
            final List<GitHubGraphQl.@Nullable IssueOnBoard> on = new ArrayList<>();
            for (final String id : issueIds) {
                final Held held = issues.get(id);
                if (held == null || !held.onProject) {
                    on.add(null);
                    continue;
                }
                final GitHubGraphQl.ProjectItem item = item(held);
                on.add(
                        new GitHubGraphQl.IssueOnBoard(
                                item.issue(), item.id(), item.values(), NONE));
            }
            return new GitHubGraphQl.IssueBoard(on, COST);
        }

        /** The REST list: one page, its tag its content's, answered 304 to the tag it has. */
        GitHubChanges.ItemPages pages() {
            return (token, owner, number, after, perPage, fieldIds, etag) -> {
                calls.add("list");
                onList.run();
                final RuntimeException failure = listFailure;
                if (failure != null) {
                    throw failure;
                }
                final List<ProjectItemSummary> now = lateList != null ? lateList : summaries();
                final String tag = "\"" + now.hashCode() + "\"";
                answered.set(true);
                return tag.equals(etag)
                        ? new Conditional<>(null, tag, false, null, DATE)
                        : new Conditional<>(new CursorPage<>(now, null), tag, true, null, DATE);
            };
        }

        private List<GitHubGraphQl.ProjectItem> items() {
            return issues.values().stream().filter(held -> held.onProject).map(this::item).toList();
        }

        private List<ProjectItemSummary> summaries() {
            return issues.values().stream()
                    .filter(held -> held.onProject && !unlisted.contains(held.item))
                    .map(
                            held ->
                                    new ProjectItemSummary(
                                            held.item,
                                            held.itemAt,
                                            held.archived,
                                            "Issue",
                                            new ProjectItemSummary.IssueSummary(
                                                    held.id,
                                                    REPOSITORY,
                                                    held.number,
                                                    held.at,
                                                    "open",
                                                    held.subs.size(),
                                                    0),
                                            List.of(
                                                    new ProjectItemSummary.ValueSummary(
                                                            STATUS_ID,
                                                            "single_select",
                                                            held.option,
                                                            null,
                                                            null))))
                    .toList();
        }

        private GitHubGraphQl.ProjectItem item(final Held held) {
            final GitHubGraphQl.IssueRefs none = new GitHubGraphQl.IssueRefs(List.of(), NONE, 0);
            final List<GitHubGraphQl.IssueRef> subs = new ArrayList<>();
            for (final String sub : held.subs) {
                final Held child = Objects.requireNonNull(issues.get(sub));
                subs.add(
                        new GitHubGraphQl.IssueRef(child.id, key(child), child.number, REPOSITORY));
            }
            final List<GitHubGraphQl.ItemValue> values = new ArrayList<>();
            values.add(
                    new GitHubGraphQl.ItemValue(
                            "F_status",
                            held.option,
                            "O_idea".equals(held.option) ? "Idea" : "Finished",
                            null,
                            null));
            if (!held.note.isEmpty()) {
                values.add(new GitHubGraphQl.ItemValue("F_note", null, null, held.note, null));
            }
            return new GitHubGraphQl.ProjectItem(
                    held.item,
                    held.archived,
                    new GitHubGraphQl.BoardIssue(
                            held.id,
                            key(held),
                            held.number,
                            held.title,
                            "A body",
                            // Finished is a close on GitHub, as a person or the planner makes it.
                            "O_finished".equals(held.option) ? "CLOSED" : "OPEN",
                            null,
                            held.at,
                            REPOSITORY,
                            List.of(),
                            held.parent,
                            new GitHubGraphQl.IssueRefs(subs, NONE, subs.size()),
                            none,
                            NONE,
                            held.story ? IssueMapping.STORY_TYPE : null,
                            null,
                            List.of(new GitHubGraphQl.IssueFieldOption("Priority", "Medium"))),
                    values,
                    NONE);
        }

        private static long key(final Held held) {
            return 10L + held.number;
        }
    }

    /** GitHub's writes as a save sends them: each does nothing but is recorded as touched. */
    private static ProjectWrites writes() {
        return (ProjectWrites)
                Proxy.newProxyInstance(
                        ProjectWrites.class.getClassLoader(),
                        new Class<?>[] {ProjectWrites.class},
                        (proxy, method, args) -> null);
    }

    private BoardCopies copies() {
        return new BoardCopies(directory, files);
    }

    /** The store over the GitHub given, its save doing what the test sets on GitHub. */
    private GitHubStore store(
            final GitHub github, final AtomicReference<Consumer<ProjectWrites>> save) {
        return store(github, save, null);
    }

    /** The store over the GitHub given, reading the repositories' open issues from the pages. */
    private GitHubStore store(
            final GitHub github,
            final AtomicReference<Consumer<ProjectWrites>> save,
            final RepositoryIssues.@Nullable Pages openIssues) {
        final StoryStates states = issueStates -> issueStates.isEmpty() ? null : "Idea";
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github,
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        states,
                        () -> DATE,
                        ItemsBoardReader.DEADLINE,
                        length -> {},
                        openIssues);
        final Touched touched = new Touched();
        final ProjectWrites recorded = touched.recording(writes());
        final BoardWriter writer =
                new BoardWriter() {
                    @Override
                    public void save(
                            final Snapshot current, final Revision revision, final Writes changes) {
                        save.get().accept(recorded);
                    }

                    @Override
                    public String createIssue(
                            final Snapshot current, final Revision revision, final NewIssue issue) {
                        throw new AssertionError("No issue is made here");
                    }

                    @Override
                    public void comment(
                            final Snapshot current, final String issue, final String text) {
                        save.get().accept(recorded);
                    }
                };
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(reader, states)
                        .writer(writer)
                        .copies(copies())
                        .changes(new GitHubChanges(github.pages(), github))
                        // The repositories' issues unchanged, as the probe tells.
                        .probe(current -> true),
                origin -> TOKEN,
                Duration.ofSeconds(2),
                touched,
                Runnable::run);
    }

    /** A save that retitles the story #1 on GitHub, as a read of it by id shows at once. */
    private static Consumer<ProjectWrites> retitle(final GitHub github) {
        return writes -> {
            writes.updateIssue(TOKEN, OWNER, "example-project", 1, "Saved", null, null);
            final Held story = Objects.requireNonNull(github.issues.get("I_1"));
            story.title = "Saved";
            story.at = SAVED;
        };
    }

    private static void save(final GitHubStore store) {
        store.save(store.board().revision(), new Writes(Map.of(), Map.of()));
    }

    private List<String> heldInCopy() {
        return copy().held().stream().map(TrackedItem::id).toList();
    }

    private BoardCopy copy() {
        return copies().read(PROJECT, CONFIG.commit()).orElseThrow();
    }

    private static @Nullable Issue issue(final Board board, final int number) {
        return board.issues().stream()
                .filter(issue -> issue.id().equals("example-project#" + number))
                .findFirst()
                .orElse(null);
    }

    private static Story story(final Board board) {
        return board.stories().stream().findFirst().orElseThrow();
    }

    @Test
    void anItemArchivedOnGitHubLeavesTheBoardAfterOnePoll() {
        // Harm: an item archived on GitHub stays on the board for ever, as the look takes the
        // page's new tag while the item is read again by id as not archived.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held archived = github.add(2, false, "Two");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();

        archived.archived = true;
        archived.itemAt = LATER;
        final Board polled = store.poll();

        assertThat(issue(polled, 2)).as("the archived issue after one poll").isNull();
        assertThat(issue(store.poll(), 2)).as("the archived issue after the next poll").isNull();
    }

    @Test
    void aSubIssueOfAParentGoneFromTheProjectStaysOnTheBoard() {
        // Harm: a sub-issue unlinked from a story that then left the project drops off the board
        // as having a parent outside it, since only the story is named by the look.
        final GitHub github = new GitHub();
        final Held parent = github.add(1, true, "Story");
        final Held child = github.add(2, false, "Child");
        parent.subs.add(child.id);
        child.parent = parent.id;
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();

        parent.onProject = false;
        parent.subs.clear();
        child.parent = null;
        final Board polled = store.poll();

        assertThat(issue(polled, 2)).as("the sub-issue after its story left").isNotNull();
        assertThat(polled.faults()).noneMatch(fault -> fault.contains("parent outside"));
    }

    @Test
    void aSubIssueTakenUnderAnIssueNewOnTheBoardShowsUnderIt() {
        // Harm: an issue made the sub-issue of one just added to the project still shows without
        // its parent, as its own item on the list does not change.
        final GitHub github = new GitHub();
        final Held parent = github.add(1, false, "Parent");
        parent.onProject = false;
        final Held child = github.add(2, false, "Child");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();

        parent.onProject = true;
        parent.subs.add(child.id);
        child.parent = parent.id;
        final Board polled = store.poll();

        assertThat(issue(polled, 1)).as("the new parent").isNotNull();
        assertThat(Objects.requireNonNull(issue(polled, 2)).parent())
                .as("the sub-issue's parent")
                .isEqualTo("example-project#1");
    }

    @Test
    void aChangeOnlyTheItemsOwnTimeShowsIsReadAgainAndTheTimeKeptInTheCopy() {
        // Harm: a change of an item that moves no issue time, count or planner field never
        // shows, since the item's own time the list gives is never kept to compare with.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();
        assertThat(copy().items())
                .as("the items' times in the copy after the first look")
                .extracting(TrackedItem::updatedAt)
                .containsOnly(BEFORE);

        other.note = "changed";
        other.itemAt = LATER;
        github.calls.clear();
        store.poll();

        assertThat(github.calls).as("GitHub's reads").contains("issueBoard of [I_2]");
        assertThat(copy().items())
                .as("the changed item's time in the copy")
                .filteredOn(item -> item.id().equals("PVTI_2"))
                .extracting(TrackedItem::updatedAt)
                .containsExactly(LATER);
    }

    @Test
    void aLookThatReadsAChangedItemKeepsTheIssuesOutsideTheProject() {
        // Harm: every look that names an item empties the list of the issues made outside the
        // project, on the board and in its copy, and tells the window of a change that is none.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final OpenIssues open =
                new OpenIssues()
                        .holds(
                                "example-project",
                                List.of(
                                        OpenIssues.issue(
                                                "I_9",
                                                "example-project",
                                                9,
                                                "By hand",
                                                null,
                                                false,
                                                List.of())));
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}), open);
        store.refresh();
        store.poll();

        other.note = "changed";
        other.itemAt = LATER;
        github.calls.clear();
        final Board looked = store.poll();

        assertThat(github.calls).as("GitHub's reads").contains("issueBoard of [I_2]");
        assertThat(looked.unlisted())
                .as("the issues outside the project after the look")
                .extracting(org.fuin.porelo.planner.store.UnlistedIssue::id)
                .containsExactly("example-project#9");
        assertThat(copy().unlisted())
                .as("the issues outside the project in the copy")
                .extracting(org.fuin.porelo.planner.store.cache.TrackedUnlisted::id)
                .containsExactly("I_9");
        assertThat(looked.revision())
                .as("the look's revision, as a whole read of the same board gives it")
                .isEqualTo(
                        store(github, new AtomicReference<>(writes -> {}), open)
                                .refresh()
                                .revision());
    }

    @Test
    void aLookGitHubCannotAnswerReadsTheBoardWhole() {
        // Harm: a look that fails leaves the change check to the probe alone, which within its
        // wait after a save takes another's change for the save's own, and the board stays old.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();

        github.listFailure = GitHubExceptions.refused(502, "Bad gateway");
        github.calls.clear();
        store.poll();

        assertThat(github.calls).as("GitHub's reads").contains("projectItems");
    }

    @Test
    void aSaveDuringALookIsKept() {
        // Harm: a look that began before a save releases or overwrites it with the board from
        // before the save, at its fold or at the next poll while GitHub's lists still lag it.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(retitle(github));
        final GitHubStore store = store(github, saving);
        store.refresh();
        store.poll();

        // Another moves the other issue, so the look names it and is folded in.
        other.option = "O_finished";
        final AtomicBoolean once = new AtomicBoolean();
        final AtomicBoolean told = new AtomicBoolean();
        github.onList =
                () -> {
                    if (!once.getAndSet(true)) {
                        told.set(store.reading().isPresent());
                        // GitHub's lists from before the save, which is made as the list answers.
                        github.lagList();
                        github.lagRead();
                        save(store);
                    }
                };
        final Board polled = store.poll();

        assertThat(told).as("the look's progress told to the window").isTrue();
        assertThat(Objects.requireNonNull(issue(polled, 2)).state())
                .as("the issue the look named")
                .isEqualTo("Finished");
        assertThat(story(polled).title()).as("the title after the look").isEqualTo("Saved");
        assertThat(heldInCopy()).as("the save after the look").containsExactly("PVTI_1");

        final Board again = store.poll();
        assertThat(story(again).title()).as("the title after the next poll").isEqualTo("Saved");
        assertThat(heldInCopy()).as("the save after the next poll").containsExactly("PVTI_1");
        assertThat(story(store.refresh()).title())
                .as("the title after a whole read whose list lags")
                .isEqualTo("Saved");
    }

    @Test
    void aSaveHoldingTheLockWhenALookEndsIsKept() throws Exception {
        // Harm: a save still holding the board as a look's answer comes is missed by the look's
        // fold, which then releases it, and the board from before the save comes back.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final CountDownLatch inSave = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Consumer<ProjectWrites> retitle = retitle(github);
        final GitHubStore store =
                store(
                        github,
                        new AtomicReference<>(
                                writes -> {
                                    retitle.accept(writes);
                                    inSave.countDown();
                                    try {
                                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(e);
                                    }
                                }));
        store.refresh();
        store.poll();
        other.option = "O_finished";
        github.lagList();
        github.lagRead();
        github.answered.set(false);

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread saver = new Thread(() -> run(() -> save(store), failed), "saver");
        saver.start();
        assertThat(inSave.await(5, TimeUnit.SECONDS)).as("the save under way").isTrue();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        // The look's list answered and its fold waiting for the save's lock.
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!(github.answered.get() && poller.getState() == Thread.State.TIMED_WAITING)) {
            assertThat(System.nanoTime()).as("the look waiting for the save").isLessThan(until);
            Thread.onSpinWait();
        }
        release.countDown();
        saver.join(5000);
        poller.join(5000);

        assertThat(failed.get()).as("a failure of the save or the poll").isNull();
        assertThat(heldInCopy()).as("the save after the look").containsExactly("PVTI_1");
        store.poll();
        assertThat(heldInCopy()).as("the save after the next poll").containsExactly("PVTI_1");
        assertThat(story(store.refresh()).title())
                .as("the title after a whole read whose list lags")
                .isEqualTo("Saved");
    }

    @Test
    void aLookWaitingBehindSavesTellsWhatItListedAndThatItWaitsUntilTheLastSaveEnds()
            throws Exception {
        // Harm: a look that waits for a save, as long as GitHub's 502s make it, shows "0 of all"
        // as a whole read, or stops saying it waits when the first of two saves in a row ends.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final CountDownLatch firstIn = new CountDownLatch(1);
        final CountDownLatch firstOut = new CountDownLatch(1);
        final CountDownLatch secondIn = new CountDownLatch(1);
        final CountDownLatch secondOut = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger saves =
                new java.util.concurrent.atomic.AtomicInteger();
        final GitHubStore store =
                store(
                        github,
                        new AtomicReference<>(
                                writes -> {
                                    final boolean first = saves.incrementAndGet() == 1;
                                    (first ? firstIn : secondIn).countDown();
                                    try {
                                        assertThat(
                                                        (first ? firstOut : secondOut)
                                                                .await(5, TimeUnit.SECONDS))
                                                .isTrue();
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(e);
                                    }
                                }));
        store.refresh();
        store.poll();
        other.option = "O_finished";
        github.answered.set(false);

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread first = new Thread(() -> run(() -> save(store), failed), "first saver");
        first.start();
        assertThat(firstIn.await(5, TimeUnit.SECONDS)).as("the first save under way").isTrue();
        final Thread second = new Thread(() -> run(() -> save(store), failed), "second saver");
        second.start();
        // The second save queued for the lock first, so it takes the lock when the first ends.
        final long queued = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (second.getState() != Thread.State.TIMED_WAITING) {
            assertThat(System.nanoTime()).as("the second save waiting").isLessThan(queued);
            Thread.onSpinWait();
        }
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!(github.answered.get() && poller.getState() == Thread.State.TIMED_WAITING)) {
            assertThat(System.nanoTime()).as("the look waiting for the save").isLessThan(until);
            Thread.onSpinWait();
        }
        final org.fuin.porelo.planner.store.ReadProgress waiting = store.reading().orElseThrow();
        firstOut.countDown();
        assertThat(secondIn.await(5, TimeUnit.SECONDS)).as("the second save under way").isTrue();
        final org.fuin.porelo.planner.store.ReadProgress stillWaiting =
                store.reading().orElseThrow();
        secondOut.countDown();
        first.join(5000);
        second.join(5000);
        poller.join(5000);

        assertThat(failed.get()).as("a failure of a save or the poll").isNull();
        assertThat(waiting.kind())
                .as("a look, not a whole read")
                .isEqualTo(org.fuin.porelo.planner.store.ReadProgress.Kind.LOOK);
        assertThat(waiting.read()).as("the items the look's list showed").isEqualTo(2);
        assertThat(waiting.waitsFor()).as("what it waits for").isEqualTo("save");
        assertThat(stillWaiting.waitsFor())
                .as("what it waits for while the second save runs")
                .isEqualTo("save");
        assertThat(store.reading()).as("after the look").isEmpty();
    }

    @Test
    void aWholeReadIsToldOverALookRunningBesideIt() throws Exception {
        // Harm: a Reload's whole read is shown as the look beside it, so its count stands still.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();
        final CountDownLatch looking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        github.onList =
                () -> {
                    looking.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        assertThat(looking.await(5, TimeUnit.SECONDS)).as("the look under way").isTrue();
        final AtomicReference<org.fuin.porelo.planner.store.ReadProgress.@Nullable Kind> told =
                new AtomicReference<>();
        github.onItems =
                () -> told.set(store.reading().map(progress -> progress.kind()).orElse(null));
        try {
            store.refresh();
        } finally {
            release.countDown();
            poller.join(5000);
        }

        assertThat(told.get())
                .as("the read told while both run")
                .isEqualTo(org.fuin.porelo.planner.store.ReadProgress.Kind.WHOLE);
    }

    @Test
    void aReloadAskedWhileALookRunsIsToldAsAWholeReadUntilItEnds() throws Exception {
        // Harm: a Reload waiting behind the planner's own look is told as that look, so the
        // window offers Reload again when the look ends, while the read asked for has not begun.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();
        final CountDownLatch looking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        github.onList =
                () -> {
                    looking.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        assertThat(looking.await(5, TimeUnit.SECONDS)).as("the look under way").isTrue();
        final org.fuin.porelo.planner.store.ReadProgress asked;
        final org.fuin.porelo.planner.store.ReadProgress ended;
        try {
            store.reloadAsked();
            asked = store.reading().orElseThrow();
            store.reloadEnded();
            ended = store.reading().orElseThrow();
        } finally {
            release.countDown();
            poller.join(5000);
        }

        assertThat(failed.get()).as("a failure of the poll").isNull();
        assertThat(asked.kind())
                .as("the read told while the Reload waits behind the look")
                .isEqualTo(org.fuin.porelo.planner.store.ReadProgress.Kind.WHOLE);
        assertThat(asked.read()).as("the items the Reload read").isZero();
        assertThat(ended.kind())
                .as("the read told once the Reload ended")
                .isEqualTo(org.fuin.porelo.planner.store.ReadProgress.Kind.LOOK);
    }

    @Test
    void aWholeReadBesideALookWaitingForASaveIsNotToldAsWaiting() throws Exception {
        // Harm: a Reload's read that runs says it waits for a save, because a look beside it
        // waits for one.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final CountDownLatch inSave = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GitHubStore store = store(github, new AtomicReference<>(held(inSave, release)));
        store.refresh();
        store.poll();
        other.option = "O_finished";
        github.answered.set(false);

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread saver = new Thread(() -> run(() -> save(store), failed), "saver");
        saver.start();
        assertThat(inSave.await(5, TimeUnit.SECONDS)).as("the save under way").isTrue();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!(github.answered.get() && poller.getState() == Thread.State.TIMED_WAITING)) {
            assertThat(System.nanoTime()).as("the look waiting for the save").isLessThan(until);
            Thread.onSpinWait();
        }
        final CountDownLatch toldWhole = new CountDownLatch(1);
        final AtomicReference<org.fuin.porelo.planner.store.@Nullable ReadProgress> told =
                new AtomicReference<>();
        github.onItems =
                () -> {
                    told.set(store.reading().orElse(null));
                    toldWhole.countDown();
                };
        final Thread reader = new Thread(() -> run(store::refresh, failed), "reader");
        reader.start();
        try {
            assertThat(toldWhole.await(5, TimeUnit.SECONDS)).as("the whole read").isTrue();
        } finally {
            release.countDown();
            saver.join(5000);
            poller.join(5000);
            reader.join(5000);
        }

        assertThat(failed.get()).as("a failure of the save, the poll or the read").isNull();
        assertThat(told.get())
                .as("the whole read told while it reads")
                .satisfies(
                        progress -> {
                            assertThat(progress.kind())
                                    .isEqualTo(
                                            org.fuin.porelo.planner.store.ReadProgress.Kind.WHOLE);
                            assertThat(progress.waitsFor()).as("what it waits for").isNull();
                        });
    }

    @Test
    void aWholeReadWaitingBehindASaveTellsTheTimeItBegan() throws Exception {
        // Harm: a whole read that read its items and waits for a save to end says it began just
        // now, so the window's time of the read starts over.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final CountDownLatch inSave = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GitHubStore store = store(github, new AtomicReference<>(held(inSave, release)));
        store.refresh();
        final AtomicReference<java.time.@Nullable Instant> listing = new AtomicReference<>();
        github.onItems =
                () -> {
                    final java.time.Instant at = java.time.Instant.now();
                    try {
                        Thread.sleep(20);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // Told after the pause, so the read is seen waiting only for the lock.
                    listing.set(at);
                };

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread saver = new Thread(() -> run(() -> save(store), failed), "saver");
        saver.start();
        assertThat(inSave.await(5, TimeUnit.SECONDS)).as("the save under way").isTrue();
        final Thread reader = new Thread(() -> run(store::refresh, failed), "reader");
        reader.start();
        final org.fuin.porelo.planner.store.ReadProgress waiting;
        try {
            final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(listing.get() != null && reader.getState() == Thread.State.TIMED_WAITING)) {
                assertThat(System.nanoTime()).as("the read waiting for the save").isLessThan(until);
                Thread.onSpinWait();
            }
            waiting = store.reading().orElseThrow();
        } finally {
            release.countDown();
            saver.join(5000);
            reader.join(5000);
        }

        assertThat(failed.get()).as("a failure of the save or the read").isNull();
        assertThat(waiting.waitsFor()).as("what the read waits for").isEqualTo("save");
        assertThat(waiting.since())
                .as("when the read began, before it listed the items")
                .isBefore(listing.get());
    }

    @Test
    void aLookWaitingBehindACommentTellsThatItWaits() throws Exception {
        // Harm: a look waiting while a comment holds the project shows a count that stands still
        // without saying why.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final CountDownLatch inComment = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GitHubStore store = store(github, new AtomicReference<>(held(inComment, release)));
        store.refresh();
        store.poll();
        other.option = "O_finished";
        github.answered.set(false);

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread commenter =
                new Thread(
                        () -> run(() -> store.comment("example-project#1", "A note"), failed),
                        "commenter");
        commenter.start();
        assertThat(inComment.await(5, TimeUnit.SECONDS)).as("the comment under way").isTrue();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        final org.fuin.porelo.planner.store.ReadProgress waiting;
        try {
            final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(github.answered.get() && poller.getState() == Thread.State.TIMED_WAITING)) {
                assertThat(System.nanoTime())
                        .as("the look waiting for the comment")
                        .isLessThan(until);
                Thread.onSpinWait();
            }
            waiting = store.reading().orElseThrow();
        } finally {
            release.countDown();
            commenter.join(5000);
            poller.join(5000);
        }

        assertThat(failed.get()).as("a failure of the comment or the poll").isNull();
        assertThat(waiting.waitsFor()).as("what the look waits for").isEqualTo("save");
    }

    /** A write that says it began and holds the lock until released. */
    private static Consumer<ProjectWrites> held(
            final CountDownLatch began, final CountDownLatch release) {
        return writes -> {
            began.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
    }

    private static void run(
            final Runnable work, final AtomicReference<@Nullable Throwable> failed) {
        try {
            work.run();
        } catch (final RuntimeException | AssertionError e) {
            failed.set(e);
        }
    }

    @Test
    void aSubIssueASaveLinksDuringALookIsReadWithTheStoryTheLookNames() {
        // Harm: a sub-issue a save linked by its database id during a look is not read again
        // with the story the look reads again, so the two stand on the board at other moments.
        final GitHub github = new GitHub();
        final Held parent = github.add(1, true, "Story");
        final Held child = github.add(2, false, "Child");
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(
                        writes -> {
                            writes.addSubIssue(TOKEN, OWNER, "example-project", 1, 12L);
                            parent.subs.add(child.id);
                            child.parent = parent.id;
                        });
        final GitHubStore store = store(github, saving);
        store.refresh();
        store.poll();

        // Another moves the story, so the look names it.
        parent.option = "O_finished";
        parent.itemAt = LATER;
        final AtomicBoolean once = new AtomicBoolean();
        github.onList =
                () -> {
                    if (!once.getAndSet(true)) {
                        github.lagList();
                        save(store);
                    }
                };
        github.calls.clear();
        store.poll();

        final List<String> byId =
                github.calls.stream().filter(call -> call.startsWith("issueBoard")).toList();
        assertThat(byId).as("the reads by id, the save's and the fold's").hasSize(2);
        assertThat(byId.get(1)).as("the fold's read by id").isEqualTo("issueBoard of [I_1, I_2]");
    }

    @Test
    void aSaveOfOnlyLinksIsHeldUntilAWholeReadReadsItById() {
        // Harm: sub-issues put in another order show their old order again, as a changed page
        // releases the save although the list's counts and times cannot show the order.
        final GitHub github = new GitHub();
        final Held parent = github.add(1, true, "Story");
        final Held first = github.add(2, false, "First");
        final Held second = github.add(3, false, "Second");
        final Held other = github.add(4, false, "Other");
        parent.subs.addAll(List.of(first.id, second.id));
        first.parent = parent.id;
        second.parent = parent.id;
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(
                        writes -> {
                            writes.reprioritizeSubIssue(
                                    TOKEN, OWNER, "example-project", 1, 13L, null, 12L);
                            parent.subs.clear();
                            parent.subs.addAll(List.of(second.id, first.id));
                        });
        final GitHubStore store = store(github, saving);
        store.refresh();
        store.poll();

        // The whole read lags the save; the REST list cannot show it.
        github.lagRead();
        save(store);
        other.option = "O_finished";
        final Board polled = store.poll();

        assertThat(story(polled).issues())
                .as("the order after the look")
                .containsExactly("example-project#3", "example-project#2");
        assertThat(heldInCopy()).as("the save after a changed page").containsExactly("PVTI_1");

        final Board read = store.refresh();
        assertThat(story(read).issues())
                .as("the order after a whole read whose list lags")
                .containsExactly("example-project#3", "example-project#2");
        assertThat(heldInCopy()).as("the save after the whole read").isEmpty();
    }

    @Test
    void aSaveOfAnItemTheListDoesNotCarryYetIsReadByIdAtTheNextLook() {
        // Harm: a change GitHub took to an issue the planner just saved stays hidden behind the
        // save until a whole read, since the lagging list neither names nor shows that issue.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(
                        writes -> {
                            writes.updateIssue(
                                    TOKEN, OWNER, "example-project", 2, "Saved", null, null);
                            final Held made = Objects.requireNonNull(github.issues.get("I_2"));
                            made.title = "Saved";
                            made.at = SAVED;
                        });
        final GitHubStore store = store(github, saving);
        store.refresh();
        store.poll();
        // The list from before the new issue, which GitHub's list shows only a moment later.
        github.lagList();
        github.add(2, false, "Two");
        store.refresh();
        save(store);
        assertThat(heldInCopy()).as("the save of the issue the list lacks").contains("PVTI_2");

        final Held two = Objects.requireNonNull(github.issues.get("I_2"));
        two.title = "Changed on GitHub";
        two.at = LATER;
        github.calls.clear();
        final Board polled = store.poll();

        assertThat(Objects.requireNonNull(issue(polled, 2)).title())
                .as("the issue after the look")
                .isEqualTo("Changed on GitHub");
        assertThat(github.calls)
                .as("the look's read by id of the save the list lacks")
                .anyMatch(call -> call.startsWith("issueBoard") && call.contains("I_2"));
        assertThat(heldInCopy())
                .as("the save after the look, until the list shows it")
                .contains("PVTI_2");
    }

    @Test
    void aFailedReadByIdOfASaveKeepsWhatTheLookNamed() {
        // Harm: the look's cursor moves past a change it named, so the change is never shown until
        // a whole read, because the read by id of a held save failed after it.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(3, false, "Three");
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(
                        writes -> {
                            writes.updateIssue(
                                    TOKEN, OWNER, "example-project", 2, "Saved", null, null);
                            final Held made = Objects.requireNonNull(github.issues.get("I_2"));
                            made.title = "Saved";
                            made.at = SAVED;
                        });
        final GitHubStore store = store(github, saving);
        store.refresh();
        store.poll();
        github.add(2, false, "Two");
        github.unlisted.add("PVTI_2");
        store.refresh();
        save(store);
        other.option = "O_finished";
        other.itemAt = LATER;
        // The look's own question whether the unlisted issue is gone is answered; the read again
        // fails.
        github.boardFailure = GitHubExceptions.refused(502, "Bad gateway");
        github.boardCallsBeforeFailure = 1;

        final Board polled = store.poll();

        assertThat(Objects.requireNonNull(issue(polled, 3)).state())
                .as("the change the look named")
                .isEqualTo("Finished");
        assertThat(heldInCopy()).as("the save whose read by id failed").contains("PVTI_2");
    }
}
