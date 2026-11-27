package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.CompletableFuture;
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
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.cache.BoardCopies;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.CopyFiles;
import org.fuin.porelo.planner.store.cache.OverlaidItem;
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
                    org.fuin.porelo.planner.store.ProjectRepository.named(
                            List.of("example-project")),
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

        /** Run as a read by id is asked, with the issues it names, before it is answered. */
        private Consumer<List<String>> onBoard = ids -> {};

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
            onBoard.accept(issueIds);
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
                                item.issue(), item.id(), item.values(), NONE, item.valueCount()));
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
                            List.of(new GitHubGraphQl.IssueFieldOption("Priority", "Medium")),
                            0),
                    values,
                    NONE,
                    0);
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
        return copy().held().stream().map(OverlaidItem::id).toList();
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
        assertThat(heldInCopy()).as("the save after the look").contains("PVTI_1");

        final Board again = store.poll();
        assertThat(story(again).title()).as("the title after the next poll").isEqualTo("Saved");
        assertThat(heldInCopy()).as("the save after the next poll").contains("PVTI_1");
        assertThat(story(store.refresh()).title())
                .as("the title after a whole read whose list lags")
                .isEqualTo("Saved");
    }

    @Test
    void aSaveStillAtGitHubWhenALookEndsIsKept() throws Exception {
        // Harm: a save still at GitHub as a look's answer comes is missed by the look's fold,
        // which then releases it, and the board from before the save comes back.
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
        // The look folds its answer while the save is still at GitHub.
        poller.join(5000);
        assertThat(github.answered).as("the look's list answered").isTrue();
        release.countDown();
        saver.join(5000);
        poller.join(5000);

        assertThat(failed.get()).as("a failure of the save or the poll").isNull();
        assertThat(heldInCopy()).as("the save after the look").contains("PVTI_1");
        store.poll();
        assertThat(heldInCopy()).as("the save after the next poll").contains("PVTI_1");
        assertThat(story(store.refresh()).title())
                .as("the title after a whole read whose list lags")
                .isEqualTo("Saved");
    }

    @Test
    void aReaderJoiningAWholeReadLongerThanASavesWaitGetsItsBoard() throws Exception {
        // Harm: a second window opened during a long first read is refused as busy and its
        // stream ends, while the read goes on.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        final CountDownLatch reading = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean first =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        github.onItems =
                () -> {
                    if (first.getAndSet(false)) {
                        reading.countDown();
                        try {
                            // Longer than the store's two seconds a save waits for a read.
                            Thread.sleep(3000);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
        final CompletableFuture<Board> firstRead = CompletableFuture.supplyAsync(store::refresh);
        assertThat(reading.await(5, TimeUnit.SECONDS)).as("the first read under way").isTrue();

        final Board joined = store.board();

        assertThat(joined.revision())
                .as("the joining reader's board, the first read's")
                .isEqualTo(firstRead.get(5, TimeUnit.SECONDS).revision());
    }

    @Test
    void aReaderJoiningAWholeReadThatDiesWithAnErrorIsAnsweredAndTheNextReadStartsAnew()
            throws Exception {
        // Harm: a read that dies with an error leaves every window joining it waiting forever.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch joined = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean first =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        github.onItems =
                () -> {
                    if (first.getAndSet(false)) {
                        reading.countDown();
                        try {
                            joined.await(5, TimeUnit.SECONDS);
                            // The joiner is waiting on the read when it dies.
                            Thread.sleep(200);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new StackOverflowError("the read died");
                    }
                };
        final CompletableFuture<Board> dying = CompletableFuture.supplyAsync(store::refresh);
        assertThat(reading.await(5, TimeUnit.SECONDS)).as("the first read under way").isTrue();
        final CompletableFuture<Board> joiner =
                CompletableFuture.supplyAsync(
                        () -> {
                            joined.countDown();
                            return store.board();
                        });

        assertThatThrownBy(() -> joiner.get(5, TimeUnit.SECONDS))
                .as("the joining reader answered with the read's end")
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThatThrownBy(() -> dying.get(5, TimeUnit.SECONDS))
                .as("the dying read")
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(store.refresh().revision())
                .as("the next read starts anew and gives a board")
                .isNotNull();
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
    void aSaveGoesToGitHubWhileALooksReadByIdWaitsForItsAnswer() throws Exception {
        // Harm: every save waits for GitHub's answer to a poll's reads by id, up to the store's
        // lock wait, and is then refused as busy.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final GitHubStore store = store(github, new AtomicReference<>(retitle(github)));
        store.refresh();
        store.poll();
        other.option = "O_finished";
        final CountDownLatch looking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        github.onBoard =
                ids -> {
                    if (ids.contains("I_2") && looking.getCount() > 0) {
                        looking.countDown();
                        await(release);
                    }
                };

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        assertThat(looking.await(5, TimeUnit.SECONDS)).as("the look's read by id asked").isTrue();
        final Thread saver = new Thread(() -> run(() -> save(store), failed), "saver");
        saver.start();
        saver.join(2000);
        final boolean saved = !saver.isAlive();
        release.countDown();
        saver.join(5000);
        poller.join(5000);

        assertThat(saved).as("the save ended while the look's read waited").isTrue();
        assertThat(failed.get()).as("a failure of the save or the poll").isNull();
        final Board board = store.board();
        assertThat(Objects.requireNonNull(issue(board, 2)).state())
                .as("the issue the look named")
                .isEqualTo("Finished");
        assertThat(story(board).title()).as("the title the save wrote").isEqualTo("Saved");
    }

    @Test
    void aLookWhoseItemASaveWroteMeanwhileKeepsTheSavesValue() throws Exception {
        // Harm: a look that read an item before a save of it lays its older answer over the
        // save, and the board shows the item as before the save.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(
                        writes -> {
                            writes.updateIssue(
                                    TOKEN, OWNER, "example-project", 2, "Two saved", null, null);
                            other.title = "Two saved";
                            other.at = SAVED;
                        });
        final GitHubStore store = store(github, saving);
        store.refresh();
        store.poll();
        other.option = "O_finished";
        final CountDownLatch looking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        github.onBoard =
                ids -> {
                    if (ids.contains("I_2") && first.getAndSet(false)) {
                        looking.countDown();
                        await(release);
                        // The look's answer as GitHub gave it before the save's write.
                        other.title = "Two";
                    }
                };

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        assertThat(looking.await(5, TimeUnit.SECONDS)).as("the look's read by id asked").isTrue();
        final Thread saver = new Thread(() -> run(() -> save(store), failed), "saver");
        saver.start();
        saver.join(5000);
        release.countDown();
        poller.join(5000);

        assertThat(failed.get()).as("a failure of the save or the poll").isNull();
        assertThat(Objects.requireNonNull(issue(store.board(), 2)).title())
                .as("the item's title after the look landed")
                .isEqualTo("Two saved");
    }

    @Test
    void aWholeReadAskedWhileALookReadsWaitsUntilTheLookIsLaidOn() throws Exception {
        // Harm: a whole read begun before a look lands after it and lays its older items over the
        // look's, and the look's cursor has moved past the change, so it is never shown again.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final GitHubStore store = store(github, new AtomicReference<>(retitle(github)));
        store.refresh();
        store.poll();
        other.option = "O_finished";
        final CountDownLatch looking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        github.onBoard =
                ids -> {
                    if (ids.contains("I_2") && looking.getCount() > 0) {
                        looking.countDown();
                        await(release);
                    }
                };
        final AtomicBoolean wholeDuringLook = new AtomicBoolean();
        github.onItems = () -> wholeDuringLook.set(release.getCount() > 0);

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        assertThat(looking.await(5, TimeUnit.SECONDS)).as("the look's read by id asked").isTrue();
        final Thread reader = new Thread(() -> run(store::refresh, failed), "reader");
        reader.start();
        reader.join(500);
        release.countDown();
        poller.join(5000);
        reader.join(5000);

        assertThat(failed.get()).as("a failure of the look or the whole read").isNull();
        assertThat(wholeDuringLook).as("the whole read began while the look read").isFalse();
        assertThat(Objects.requireNonNull(issue(store.board(), 2)).state())
                .as("the issue the look named")
                .isEqualTo("Finished");
    }

    @Test
    void aParentReadAgainAfterAChangeBringsItsNewSubIssueOntoTheBoard() {
        // Harm: a sub-issue added to the project under a parent since the board was read is missed
        // when the parent is read again by id after a refusal, so a task's retry judged on that
        // board advances the parent over its open child.
        final GitHub github = new GitHub();
        github.add(1, true, "Story");
        final AtomicReference<Consumer<ProjectWrites>> saving = new AtomicReference<>(writes -> {});
        final GitHubStore store = store(github, saving);
        store.refresh();
        store.poll();
        final Held parent = Objects.requireNonNull(github.issues.get("I_1"));
        final Held child = github.add(2, false, "Child");
        parent.subs.add(child.id);
        child.parent = parent.id;
        final String story = story(store.board()).id();
        saving.set(
                writes -> {
                    throw new org.fuin.porelo.planner.store.StoreException.Changed(List.of(story));
                });

        assertThatThrownBy(() -> save(store))
                .as("the save refused as changed")
                .isInstanceOf(org.fuin.porelo.planner.store.StoreException.Changed.class);

        final Board board = store.board();
        final Issue placed = issue(board, 2);
        assertThat(placed).as("the new sub-issue on the board").isNotNull();
        assertThat(story(board).issues())
                .as("the story's issues")
                .contains(Objects.requireNonNull(placed).id());
    }

    @Test
    void anIssueASaveMovedToAnotherStoryShowsOnlyUnderTheNewStory() {
        // Harm: the story an issue was moved from is not read again after the save, so the board
        // holds the issue under both stories until the next whole read.
        final GitHub github = new GitHub();
        final Held oldStory = github.add(1, true, "Old");
        final Held child = github.add(2, false, "Child");
        final Held newStory = github.add(3, true, "New");
        oldStory.subs.add(child.id);
        child.parent = oldStory.id;
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(
                        writes -> {
                            writes.moveSubIssue(TOKEN, OWNER, "example-project", 3, 12L);
                            oldStory.subs.remove(child.id);
                            newStory.subs.add(child.id);
                            child.parent = newStory.id;
                        });
        final GitHubStore store = store(github, saving);
        store.refresh();
        store.poll();
        final Board before = store.board();
        assertThat(storyNumbered(before, 1).issues())
                .as("the issue under its old story before the save")
                .contains("example-project#2");

        save(store);

        final Board after = store.board();
        assertThat(storyNumbered(after, 1).issues())
                .as("the old story no longer lists the moved issue")
                .doesNotContain("example-project#2");
        assertThat(storyNumbered(after, 3).issues())
                .as("the new story lists the moved issue")
                .containsExactly("example-project#2");
    }

    private static Story storyNumbered(final Board board, final int number) {
        return board.stories().stream()
                .filter(story -> story.id().equals("example-project#" + number))
                .findFirst()
                .orElseThrow();
    }

    /** A write that says it began and stays at GitHub until released. */
    private static Consumer<ProjectWrites> held(
            final CountDownLatch began, final CountDownLatch release) {
        return writes -> {
            began.countDown();
            await(release);
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

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("released").isTrue();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
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
        final Board polled = store.poll();

        // The look's older answer for the story gives way to the save's read, which reads the
        // story and its new sub-issue at one moment.
        assertThat(github.calls).as("GitHub's reads").contains("issueBoard of [I_1, I_2]");
        assertThat(story(polled).issues())
                .as("the sub-issue under the story the look named")
                .contains(Objects.requireNonNull(issue(polled, 2)).id());
    }

    @Test
    void aSaveOfOnlyLinksIsHeldUntilAWholeListShowsIt() {
        // Harm: sub-issues put in another order show their old order again, as a changed page
        // or a whole list that lags releases the save although it does not show the order.
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
        assertThat(heldInCopy()).as("the save after a changed page").contains("PVTI_1");

        final Board read = store.refresh();
        assertThat(story(read).issues())
                .as("the order after a whole read whose list lags")
                .containsExactly("example-project#3", "example-project#2");
        assertThat(heldInCopy()).as("the save after a whole read that lags").contains("PVTI_1");

        github.lateRead = null;
        final Board caughtUp = store.refresh();
        assertThat(story(caughtUp).issues())
                .as("the order after a whole read whose list shows the save")
                .containsExactly("example-project#3", "example-project#2");
        assertThat(heldInCopy()).as("the save after a whole read that shows it").isEmpty();
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

    /** A save that retitles the story #1 on GitHub to the title given, at the time given. */
    private static Consumer<ProjectWrites> retitleTo(
            final GitHub github, final String title, final Instant at) {
        return writes -> {
            writes.updateIssue(TOKEN, OWNER, "example-project", 1, title, null, null);
            final Held story = Objects.requireNonNull(github.issues.get("I_1"));
            story.title = title;
            story.at = at;
        };
    }

    /** Another's change of the story #1 on GitHub, which no list shows yet if it lags. */
    private static void retitledOutside(final GitHub github, final String title) {
        final Held story = Objects.requireNonNull(github.issues.get("I_1"));
        story.title = title;
        story.at = LATER;
    }

    @Test
    void aLookWhoseReadByIdLandsWhileBothListsLagDoesNotLetALaggingWholeReadUndoTheSave() {
        // Harm: a read undoes an own write: the look reads the save by id and releases it, and a
        // whole read whose list still lags then shows the story from before the save.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        github.add(2, false, "Two");
        final GitHubStore store = store(github, new AtomicReference<>(retitle(github)));
        store.refresh();
        github.lagList();
        github.lagRead();

        save(store);
        github.calls.clear();
        // The first look after a whole read asks each page whole, so it compares the story.
        final Board looked = store.poll();

        assertThat(github.calls)
                .as("the look's read by id of the saved story")
                .contains("issueBoard of [I_1]");
        assertThat(story(looked).title()).as("the title after the look").isEqualTo("Saved");
        assertThat(story(store.refresh()).title())
                .as("the title after a whole read whose list still lags")
                .isEqualTo("Saved");
    }

    @Test
    void aWholeReadWhoseListShowsTheVersionTheSaveWasJudgedOnKeepsTheSave() {
        // Harm: a read undoes an own write: a whole read right after a save shows the story from
        // before it, as GitHub's list lags the save.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(retitle(github)));
        store.refresh();
        github.lagRead();

        save(store);
        final Board read = store.refresh();

        assertThat(story(read).title())
                .as("the title after a lagging whole read")
                .isEqualTo("Saved");
        assertThat(heldInCopy()).as("the save held while the list lags").containsExactly("PVTI_1");
    }

    @Test
    void aWholeReadWhoseListShowsAnOutsideChangeAfterTheSaveShowsThatChange() {
        // Harm: a stale own value hides someone's change: the save stays over a list that shows
        // what another wrote after it.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(retitle(github)));
        store.refresh();

        save(store);
        retitledOutside(github, "Theirs");
        final Board read = store.refresh();

        assertThat(story(read).title()).as("the title after the whole read").isEqualTo("Theirs");
        assertThat(heldInCopy()).as("the save after a list that moved past it").isEmpty();
    }

    @Test
    void aWholeReadShowingTheSaveReleasesItSoALaterListIsTakenAsItIs() {
        // Harm: an entry never leaves: once the list showed the save, another's later return to
        // the old title is hidden behind it for ever.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(retitle(github)));
        store.refresh();

        save(store);
        assertThat(story(store.refresh()).title())
                .as("the title after a whole read showing the save")
                .isEqualTo("Saved");
        assertThat(heldInCopy()).as("the save after the list showed it").isEmpty();
        retitledOutside(github, "One");

        assertThat(story(store.refresh()).title())
                .as("the title after another set it back")
                .isEqualTo("One");
    }

    @Test
    void aLooksReadByIdOfAnOutsideChangeAfterASaveIsKeptOverAListThatShowsTheSave() {
        // Harm: a stale own value hides someone's change: a whole list that shows the save, but
        // not yet the change a look read by id after it, puts the save back.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(retitle(github)));
        store.refresh();
        github.lagList();

        save(store);
        github.lagRead();
        retitledOutside(github, "Theirs");
        // The first look after a whole read asks each page whole, so it compares the story.
        final Board looked = store.poll();
        assertThat(story(looked).title()).as("the title after the look").isEqualTo("Theirs");

        assertThat(story(store.refresh()).title())
                .as("the title after a whole read whose list shows only the save")
                .isEqualTo("Theirs");
    }

    @Test
    void aSaveThatMovesNoVersionOfTheItemHoldsNothing() {
        // Harm: a stale entry later hides a change: a save that changed nothing of the item, such
        // as a comment, would be held over the list for nothing.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store =
                store(github, new AtomicReference<>(retitleTo(github, "One", SAVED)));
        store.refresh();

        save(store);

        assertThat(heldInCopy()).as("what a save that changed nothing holds").isEmpty();
    }

    @Test
    void aRestartFromTheCopyKeepsASaveTheListStillLagsAndLetsItGoOnceTheListShowsIt() {
        // Harm: a restart loses an entry, so a lagging whole read undoes the save; or keeps one
        // the list caught up with.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        store(github, new AtomicReference<>(retitle(github))).refresh();
        github.lagRead();
        final GitHubStore before = store(github, new AtomicReference<>(retitle(github)));
        before.board();
        save(before);

        final GitHubStore after = store(github, new AtomicReference<>(writes -> {}));
        assertThat(story(after.board()).title()).as("the title from the copy").isEqualTo("Saved");
        assertThat(story(after.refresh()).title())
                .as("the title after a whole read that lags, after the restart")
                .isEqualTo("Saved");

        github.lateRead = null;
        assertThat(story(after.refresh()).title())
                .as("the title after a whole read that shows the save")
                .isEqualTo("Saved");
        assertThat(heldInCopy()).as("the save after the list showed it").isEmpty();
    }

    @Test
    void threeSavesInARowStayOverAListThatShowsTheFirstOrTheSecond() {
        // Harm: a read undoes an own write: after saves A, B and C a list still at A, or at B,
        // puts an older title back.
        final GitHub github = new GitHub();
        github.add(1, true, "A");
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(retitleTo(github, "B", SAVED));
        final GitHubStore store = store(github, saving);
        store.refresh();
        github.lagRead();

        save(store);
        final List<GitHubGraphQl.ProjectItem> atB = github.items();
        saving.set(retitleTo(github, "C", LATER));
        save(store);

        assertThat(story(store.refresh()).title())
                .as("the title after a whole read whose list is at A")
                .isEqualTo("C");
        github.lateRead = atB;
        assertThat(story(store.refresh()).title())
                .as("the title after a whole read whose list is at B")
                .isEqualTo("C");
    }

    @Test
    void anItemALookFoundGoneStaysGoneAfterAWholeReadThatLags() {
        // Harm: a read undoes what a look saw: an item another took off the project comes back
        // with a whole read whose list still holds it.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held two = github.add(2, false, "Two");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();
        github.lagRead();

        two.onProject = false;
        assertThat(issue(store.poll(), 2)).as("the issue after the look").isNull();

        assertThat(issue(store.refresh(), 2))
                .as("the issue after a whole read whose list still holds it")
                .isNull();
    }

    @Test
    void aWholeReadASaveAsksForWaitsForALookReadingSoTheLooksOlderAnswerNeverStays()
            throws Exception {
        // Harm: a stale value stays for good: a whole read a save asks for runs beside a look,
        // releases the item, and the look's older read by id lands after it over every list that
        // shows the item as it is.
        final GitHub github = new GitHub();
        final Held story = github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(retitle(github)));
        store.refresh();
        store.poll();
        github.lagRead();
        save(store);
        retitledOutside(github, "Theirs");
        final CountDownLatch looking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        github.onBoard =
                ids -> {
                    if (ids.contains("I_1") && first.getAndSet(false)) {
                        looking.countDown();
                        await(release);
                        // The look's answer as GitHub gave it when the look asked, before the
                        // change.
                        story.title = "Saved";
                        story.at = SAVED;
                    }
                };
        final AtomicBoolean wholeDuringLook = new AtomicBoolean();
        github.onItems =
                () -> {
                    wholeDuringLook.compareAndSet(false, release.getCount() > 0);
                    // GitHub as it is now, after the change the look's older answer lacks.
                    retitledOutside(github, "Theirs");
                };

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        assertThat(looking.await(5, TimeUnit.SECONDS)).as("the look's read by id asked").isTrue();
        github.lateRead = null;
        // A stage result for an issue the board lacks reads the board whole within the save.
        final Thread result =
                new Thread(
                        () ->
                                run(
                                        () ->
                                                assertThatThrownBy(
                                                                () ->
                                                                        store.saveResult(
                                                                                resultFor(
                                                                                        "example-project#9"))) // not-a-citation
                                                        .as("the result for an issue on no board")
                                                        .isInstanceOf(
                                                                org.fuin.porelo.planner.store
                                                                        .StoreException.NoSuchIssue
                                                                        .class),
                                        failed),
                        "result");
        result.start();
        // The save waits for the reads the look holds before the look is let go.
        awaitWaitingIn(result, "readAlone");
        release.countDown();
        poller.join(5000);
        result.join(5000);

        assertThat(failed.get()).as("a failure of the look").isNull();
        assertThat(wholeDuringLook).as("the save's whole read ran while the look read").isFalse();
        assertThat(story(store.board()).title())
                .as("the title after the look and the whole read")
                .isEqualTo("Theirs");
        assertThat(story(store.refresh()).title())
                .as("the title after a whole read showing the change")
                .isEqualTo("Theirs");
    }

    /** A save that retitles the issue given on GitHub, as a read of it by id shows at once. */
    private static Consumer<ProjectWrites> retitled(
            final GitHub github, final int number, final String title) {
        return writes -> {
            writes.updateIssue(TOKEN, OWNER, "example-project", number, title, null, null);
            final Held held = Objects.requireNonNull(github.issues.get("I_" + number));
            held.title = title;
            held.at = SAVED;
        };
    }

    /** Runs a save on a thread of the name given. */
    private static Thread saving(
            final GitHubStore store,
            final String name,
            final AtomicReference<@Nullable Throwable> failed) {
        final Thread thread = new Thread(() -> run(() -> save(store), failed), name);
        thread.start();
        return thread;
    }

    @Test
    void twoSavesOfDifferentIssuesAreAtGitHubAtOnce() throws Exception {
        // Harm: a save waits for another save's GitHub round trip, and is refused as busy once
        // that takes longer than the store's wait.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        github.add(2, false, "Two");
        final CountDownLatch firstIn = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GitHubStore store =
                store(
                        github,
                        new AtomicReference<>(
                                writes -> {
                                    if ("first".equals(Thread.currentThread().getName())) {
                                        retitled(github, 1, "One saved").accept(writes);
                                        firstIn.countDown();
                                        await(release);
                                    } else {
                                        retitled(github, 2, "Two saved").accept(writes);
                                    }
                                }));
        store.refresh();

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread first = saving(store, "first", failed);
        assertThat(firstIn.await(5, TimeUnit.SECONDS)).as("the first save at GitHub").isTrue();
        final Thread second = saving(store, "second", failed);
        second.join(1000);
        final boolean ended = !second.isAlive();
        release.countDown();
        first.join(5000);
        second.join(5000);

        assertThat(failed.get()).as("a failure of a save").isNull();
        assertThat(ended).as("the second save ended while the first was at GitHub").isTrue();
        assertThat(Objects.requireNonNull(issue(store.board(), 2)).title())
                .as("the second save's issue")
                .isEqualTo("Two saved");
        assertThat(story(store.board()).title())
                .as("the first save's story")
                .isEqualTo("One saved");
    }

    @Test
    void twoSavesAtOnceBothStandOnTheBoardWhateverOrderTheirReadsLandIn() throws Exception {
        // Harm: a lost update: two saves of different items each lay their read after the write
        // on the board they began with, and the one landing last undoes the other.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        github.add(2, false, "Two");
        final GitHubStore store =
                store(
                        github,
                        new AtomicReference<>(
                                writes -> {
                                    if ("first".equals(Thread.currentThread().getName())) {
                                        retitled(github, 1, "One saved").accept(writes);
                                    } else {
                                        retitled(github, 2, "Two saved").accept(writes);
                                    }
                                }));
        store.refresh();
        github.lagRead();
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean hold = new AtomicBoolean(true);
        github.onBoard =
                ids -> {
                    if (ids.contains("I_1") && hold.getAndSet(false)) {
                        reading.countDown();
                        await(release);
                    }
                };

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread first = saving(store, "first", failed);
        assertThat(reading.await(5, TimeUnit.SECONDS))
                .as("the first save reading after its write")
                .isTrue();
        final Thread second = saving(store, "second", failed);
        second.join(5000);
        release.countDown();
        first.join(5000);

        assertThat(failed.get()).as("a failure of a save").isNull();
        final Board board = store.board();
        assertThat(story(board).title()).as("the first save's story").isEqualTo("One saved");
        assertThat(Objects.requireNonNull(issue(board, 2)).title())
                .as("the second save's issue")
                .isEqualTo("Two saved");
        final Board read = store.refresh();
        assertThat(story(read).title())
                .as("the first save's story after a whole read that lags")
                .isEqualTo("One saved");
        assertThat(Objects.requireNonNull(issue(read, 2)).title())
                .as("the second save's issue after a whole read that lags")
                .isEqualTo("Two saved");
    }

    @Test
    void eachOfTwoSavesAtOnceReadsAgainOnlyWhatItWrote() throws Exception {
        // Harm: a save's read after its write takes or misses another save's items, so a save
        // still at GitHub is read half written and its item shown from before it.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        github.add(2, false, "Two");
        final CountDownLatch firstIn = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GitHubStore store =
                store(
                        github,
                        new AtomicReference<>(
                                writes -> {
                                    if ("first".equals(Thread.currentThread().getName())) {
                                        retitled(github, 1, "One saved").accept(writes);
                                        firstIn.countDown();
                                        await(release);
                                    } else {
                                        retitled(github, 2, "Two saved").accept(writes);
                                    }
                                }));
        store.refresh();

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread first = saving(store, "first", failed);
        assertThat(firstIn.await(5, TimeUnit.SECONDS)).as("the first save at GitHub").isTrue();
        github.calls.clear();
        final Thread second = saving(store, "second", failed);
        second.join(5000);
        final List<String> secondReads =
                github.calls.stream().filter(call -> call.startsWith("issueBoard")).toList();
        github.calls.clear();
        release.countDown();
        first.join(5000);

        assertThat(failed.get()).as("a failure of a save").isNull();
        assertThat(secondReads)
                .as("the second save's read after its write")
                .containsExactly("issueBoard of [I_2]");
        assertThat(github.calls.stream().filter(call -> call.startsWith("issueBoard")))
                .as("the first save's read after its write")
                .containsExactly("issueBoard of [I_1]");
    }

    @Test
    void aWholeReadWhileASaveIsBetweenItsWriteAndItsReadAfterKeepsTheSave() throws Exception {
        // Harm: a read undoes an own write: a whole list that lags releases the item a save is
        // writing, and the save's own read after its write, begun before that list, is then left
        // out, so the board shows the item from before the save until the list catches up.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(retitleTo(github, "Saved", SAVED));
        final GitHubStore store = store(github, saving);
        store.refresh();
        save(store);
        // The whole list shows the first save from now on, and lags every later one.
        github.lagRead();
        saving.set(retitleTo(github, "Saved again", LATER));
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean hold = new AtomicBoolean(true);
        github.onBoard =
                ids -> {
                    if (ids.contains("I_1") && hold.getAndSet(false)) {
                        reading.countDown();
                        await(release);
                    }
                };

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread saver = saving(store, "saver", failed);
        assertThat(reading.await(5, TimeUnit.SECONDS))
                .as("the save reading after its write")
                .isTrue();
        store.refresh();
        release.countDown();
        saver.join(5000);

        assertThat(failed.get()).as("a failure of the save").isNull();
        assertThat(story(store.board()).title())
                .as("the title once the save's read landed")
                .isEqualTo("Saved again");
        assertThat(story(store.refresh()).title())
                .as("the title after another whole read that lags")
                .isEqualTo("Saved again");
    }

    @Test
    void aLookAndAWholeReadEndWhileASaveIsAtGitHub() throws Exception {
        // Harm: a poll and a Reload stand still while a save waits for GitHub, as long as
        // GitHub's answer takes, and say they wait for a save.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final CountDownLatch inSave = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GitHubStore store = store(github, new AtomicReference<>(held(inSave, release)));
        store.refresh();
        store.poll();
        other.option = "O_finished";

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread saver = saving(store, "saver", failed);
        assertThat(inSave.await(5, TimeUnit.SECONDS)).as("the save at GitHub").isTrue();
        final Thread reader =
                new Thread(
                        () ->
                                run(
                                        () -> {
                                            store.poll();
                                            store.refresh();
                                        },
                                        failed),
                        "reader");
        reader.start();
        reader.join(1000);
        final boolean ended = !reader.isAlive();
        release.countDown();
        saver.join(5000);
        reader.join(5000);

        assertThat(failed.get()).as("a failure of the save or the reads").isNull();
        assertThat(ended).as("the look and the whole read ended during the save").isTrue();
        assertThat(Objects.requireNonNull(issue(store.board(), 2)).state())
                .as("the change the look took")
                .isEqualTo("Finished");
    }

    @Test
    void aStageResultWaitsForTheSavesAtGitHubAndWritesAlone() throws Exception {
        // Harm: a run's stage result, which reaches the store past the runner's claim on its
        // issue, is written beside a move of that issue and the one written last undoes the other.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final CountDownLatch inSave = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GitHubStore store = store(github, new AtomicReference<>(held(inSave, release)));
        store.refresh();

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread saver = saving(store, "saver", failed);
        assertThat(inSave.await(5, TimeUnit.SECONDS)).as("the save at GitHub").isTrue();
        final AtomicReference<@Nullable Throwable> refused = new AtomicReference<>();
        final Thread result =
                new Thread(
                        () ->
                                run(
                                        () ->
                                                store.saveResult(
                                                        resultFor(
                                                                "example-project#9")), // not-a-citation
                                        refused),
                        "result");
        result.start();
        result.join(1000);
        final boolean waited = result.isAlive();
        release.countDown();
        saver.join(5000);
        result.join(5000);

        assertThat(failed.get()).as("a failure of the save").isNull();
        assertThat(waited).as("the stage result waiting while the save is at GitHub").isTrue();
        assertThat(refused.get())
                .as("the stage result for an issue on no board, once it wrote")
                .isInstanceOf(org.fuin.porelo.planner.store.StoreException.NoSuchIssue.class);
    }

    /** A stage result of an analysis of the issue named. */
    private static StageResult resultFor(final String issue) {
        return new StageResult(
                WorkRule.Result.ANALYSIS,
                List.of(
                        new StageResult.ForIssue(
                                issue,
                                "Analysis",
                                "Backlog",
                                "- a step",
                                "S",
                                "small",
                                null,
                                List.of(),
                                List.of())));
    }

    @Test
    void anItemTheLooksNoLongerReadByIdLeavesTheBoardOnceTakenOffOnGitHub() {
        // Harm: a ghost stays: an item new on the board while the whole list lags is read by id
        // only a few times, so once someone takes it off no look tells, and the list keeps it.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();
        github.lagRead();
        final Held made = github.add(2, false, "Two");
        assertThat(issue(store.poll(), 2)).as("the new issue after the look").isNotNull();
        github.unlisted.add("PVTI_2");
        for (int look = 0; look < OwnSaves.MOST_READS_BY_ID; look++) {
            store.poll();
        }
        github.calls.clear();
        store.poll();
        assertThat(github.calls)
                .as("the reads by id of the issue once the looks stopped reading it again")
                .containsOnlyOnce("issueBoard of [I_2]");

        made.onProject = false;
        assertThat(issue(store.poll(), 2)).as("the issue after it was taken off").isNull();

        assertThat(issue(store.refresh(), 2))
                .as("the issue after a whole read whose list lacks it")
                .isNull();
    }

    @Test
    void anOutsideChangeAChangedRefusalReadsAgainIsNotHiddenByAnEarlierSave() {
        // Harm: a stale own value hides someone's change: the save held from before keeps its
        // title over the change a refused save read by id, once a lagging list shows the save.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(retitle(github));
        final GitHubStore store = store(github, saving);
        store.refresh();
        save(store);
        github.lagRead();
        retitledOutside(github, "Theirs");
        final String story = story(store.board()).id();
        saving.set(
                writes -> {
                    throw new org.fuin.porelo.planner.store.StoreException.Changed(List.of(story));
                });

        assertThatThrownBy(() -> save(store))
                .as("the save refused as changed")
                .isInstanceOf(org.fuin.porelo.planner.store.StoreException.Changed.class);
        assertThat(story(store.board()).title())
                .as("the title after the refusal")
                .isEqualTo("Theirs");

        assertThat(story(store.refresh()).title())
                .as("the title after a whole read whose list shows the earlier save")
                .isEqualTo("Theirs");
    }

    @Test
    void aSaveThatLandsWhileALookReadsAnItemTheListLacksStaysOnTheBoardAndInItsCopy()
            throws Exception {
        // Harm: a look lays the board it began from over a save that landed while it read an item
        // the list lacks, so the board and its copy show the saved item as before the save.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final AtomicReference<Consumer<ProjectWrites>> saving =
                new AtomicReference<>(retitled(github, 2, "Two saved"));
        final GitHubStore store = store(github, saving);
        store.refresh();
        store.poll();
        // The list from before the new issue, so each look reads that issue again by id.
        github.lagList();
        github.add(2, false, "Two");
        store.refresh();
        save(store);
        saving.set(retitle(github));
        final CountDownLatch looking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        github.onBoard =
                ids -> {
                    // Held in the fold, where the look reads the item its list lacks.
                    if (ids.contains("I_2")
                            && looking.getCount() > 0
                            && java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                                    .anyMatch(
                                            frame -> frame.getMethodName().equals("readUnshown"))) {
                        looking.countDown();
                        await(release);
                    }
                };

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread poller = new Thread(() -> run(store::poll, failed), "poller");
        poller.start();
        assertThat(looking.await(5, TimeUnit.SECONDS))
                .as("the look's read by id of the item the list lacks asked")
                .isTrue();
        try {
            save(store);
        } finally {
            release.countDown();
        }
        poller.join(5000);

        assertThat(failed.get()).as("a failure of the look").isNull();
        assertThat(story(store.board()).title())
                .as("the title the save wrote while the look read")
                .isEqualTo("Saved");
        assertThat(copy().items())
                .as("the story's title in the copy after the look")
                .filteredOn(item -> item.id().equals("PVTI_1"))
                .extracting(item -> Objects.requireNonNull(item.issue()).title())
                .containsExactly("Saved");
    }

    @Test
    void aSaveWhoseReadAfterItsWriteFailsWhileAWholeReadFoldsIsReadAgainAfterThatRead()
            throws Exception {
        // Harm: a whole read that took the saves made during it before a save's read after its
        // write failed forgets what that save wrote unread, so the board shows the item as before
        // the save, with no fault, until GitHub's list moves.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final Held other = github.add(2, false, "Two");
        final GitHubStore store =
                store(
                        github,
                        new AtomicReference<>(
                                writes -> {
                                    writes.updateIssue(
                                            TOKEN,
                                            OWNER,
                                            "example-project",
                                            2,
                                            "Two saved",
                                            null,
                                            null);
                                    // The title alone: GitHub's list shows no change.
                                    other.title = "Two saved";
                                }));
        store.refresh();
        store.poll();
        final Revision revision = store.board().revision();

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread reader = new Thread(() -> run(store::refresh, failed), "reader");
        final Throwable refused;
        synchronized (holding(store)) {
            reader.start();
            // The whole read has read GitHub and taken the saves made during it; it waits to hold.
            awaitWaitingIn(reader, "heldWhole");
            github.boardFailure = GitHubExceptions.refused(502, "Bad gateway");
            refused =
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> store.save(revision, new Writes(Map.of(), Map.of())));
        }
        reader.join(5000);
        github.boardFailure = null;
        github.calls.clear();
        final Board polled = store.poll();

        assertThat(refused).as("the save whose read after its write failed").isNotNull();
        assertThat(failed.get()).as("a failure of the whole read").isNull();
        assertThat(github.calls)
                .as("the poll's read by id of what the save wrote")
                .anyMatch(call -> call.startsWith("issueBoard") && call.contains("I_2"));
        assertThat(Objects.requireNonNull(issue(polled, 2)).title())
                .as("the title the save wrote, after the poll")
                .isEqualTo("Two saved");
    }

    @Test
    void aStageResultThatReadsTheBoardWholeWhileAWholeReadRunsAnswersBusyWithinTheWait()
            throws Exception {
        // Harm: a save waits without a bound for a whole read GitHub keeps answering, so the
        // window's call and the run that hands in its result hang as long as GitHub takes.
        final GitHub github = new GitHub();
        github.add(1, true, "One");
        final GitHubStore store = store(github, new AtomicReference<>(writes -> {}));
        store.refresh();
        store.poll();
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        github.onItems =
                () -> {
                    if (reading.getCount() > 0) {
                        reading.countDown();
                        await(release);
                    }
                };

        final AtomicReference<@Nullable Throwable> failed = new AtomicReference<>();
        final Thread reader = new Thread(() -> run(store::refresh, failed), "reader");
        reader.start();
        assertThat(reading.await(5, TimeUnit.SECONDS)).as("the whole read asked").isTrue();
        // An issue the board lacks has the result read the board whole within the save.
        final CompletableFuture<Board> result =
                CompletableFuture.supplyAsync(
                        () -> store.saveResult(resultFor("example-project#9"))); // not-a-citation
        try {
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .as("the result's answer while the whole read runs")
                    .hasCauseInstanceOf(org.fuin.porelo.planner.store.StoreException.Busy.class);
        } finally {
            release.countDown();
        }
        reader.join(5000);

        assertThat(failed.get()).as("a failure of the whole read").isNull();
    }

    /** The monitor the store swaps its board under, held by a test to stop a swap at its door. */
    static Object holding(final GitHubStore store) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = GitHubStore.class.getDeclaredField("holding");
        field.setAccessible(true);
        return Objects.requireNonNull(field.get(store));
    }

    /** Waits until the thread given waits for a lock or a monitor inside the method named. */
    static void awaitWaitingIn(final Thread thread, final String method) {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            final Thread.State state = thread.getState();
            if (state != Thread.State.RUNNABLE
                    && state != Thread.State.NEW
                    && state != Thread.State.TERMINATED
                    && java.util.Arrays.stream(thread.getStackTrace())
                            .anyMatch(frame -> frame.getMethodName().contains(method))) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError(thread.getName() + " never waited in " + method);
    }
}
