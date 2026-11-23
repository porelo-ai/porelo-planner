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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.forge.github.model.IssuesCreateRequestIssueFieldValuesInner;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.cache.BoardCopies;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.CopyFiles;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The planner's own saves held until GitHub's list shows them, and looks folded beside saves. */
class GitHubStoreHeldTest {

    private static final String TOKEN = "invented-token-held-0123456789";

    private static final String OWNER = "example-org";

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

    /**
     * GitHub's project: its item list may lag what an issue read by id shows, as GitHub's lists
     * show a save a moment after it.
     */
    private static final class GitHub implements ProjectQueries {

        private final List<String> calls = new ArrayList<>();

        private final List<String> issueReads = new ArrayList<>();

        /** The story's title and time as an issue read by id gives them. */
        private String title = "One";

        private Instant updatedAt = BEFORE;

        /** The story's title and time as the item list gives them. */
        private String listedTitle = "One";

        private Instant listedAt = BEFORE;

        /** Whether GitHub holds a second issue, and whether its list shows it yet. */
        private boolean second;

        private boolean secondListed;

        private boolean recentIssues;

        @Override
        public GitHubGraphQl.ProjectFields projectFields(
                final String token, final String owner, final int number, final String after) {
            calls.add("projectFields");
            final List<GitHubGraphQl.ProjectField> fields = new ArrayList<>();
            fields.add(
                    new GitHubGraphQl.ProjectField(
                            "F_status",
                            FieldNames.STATUS,
                            "SINGLE_SELECT",
                            List.of(
                                    new GitHubGraphQl.FieldOption("O_idea", "Idea", "GRAY", ""),
                                    new GitHubGraphQl.FieldOption(
                                            "O_finished", "Finished", "GRAY", "")),
                            11L));
            if (recentIssues) {
                final Map<String, List<String>> options =
                        Map.of(
                                FieldNames.WORKER, List.of("Agent", "Manual"),
                                FieldNames.SIZE, IssueMapping.SIZES,
                                FieldNames.PRIORITY, Story.PRIORITIES);
                options.forEach(
                        (name, values) ->
                                fields.add(
                                        new GitHubGraphQl.ProjectField(
                                                "F_" + name,
                                                name,
                                                "SINGLE_SELECT",
                                                values.stream()
                                                        .map(
                                                                value ->
                                                                        new GitHubGraphQl
                                                                                .FieldOption(
                                                                                "O_" + value,
                                                                                value,
                                                                                "GRAY",
                                                                                ""))
                                                        .toList(),
                                                12L)));
                fields.add(
                        new GitHubGraphQl.ProjectField(
                                "F_rank", FieldNames.RANK, "NUMBER", List.of(), 13L));
            }
            return new GitHubGraphQl.ProjectFields(
                    "PVT_7", "Plan", Instant.EPOCH, fields, NONE, COST);
        }

        @Override
        public GitHubGraphQl.ProjectItems projectItems(
                final String token, final String owner, final int number, final String after) {
            calls.add("projectItems");
            final List<GitHubGraphQl.ProjectItem> items = new ArrayList<>();
            items.add(story(listedTitle, listedAt));
            if (second && secondListed) {
                items.add(issue2());
            }
            if (recentIssues) {
                items.add(item("PVTI_3", "I_3", 3, "Recent three", BEFORE, null));
                items.add(item("PVTI_4", "I_4", 4, "Recent four", BEFORE, null));
            }
            return new GitHubGraphQl.ProjectItems(items, NONE, COST, items.size());
        }

        @Override
        public GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            calls.add("issueBoard of " + issueIds);
            issueReads.addAll(issueIds);
            final List<GitHubGraphQl.@Nullable IssueOnBoard> on = new ArrayList<>();
            for (final String id : issueIds) {
                final GitHubGraphQl.ProjectItem item =
                        "I_1".equals(id)
                                ? story(title, updatedAt)
                                : "I_3".equals(id)
                                        ? item("PVTI_3", "I_3", 3, "Recent three", BEFORE, null)
                                        : "I_4".equals(id)
                                                ? item(
                                                        "PVTI_4",
                                                        "I_4",
                                                        4,
                                                        "Recent four",
                                                        BEFORE,
                                                        null)
                                                : second ? issue2() : null;
                on.add(
                        item == null
                                ? null
                                : new GitHubGraphQl.IssueOnBoard(
                                        item.issue(), item.id(), item.values(), NONE));
            }
            return new GitHubGraphQl.IssueBoard(on, COST);
        }

        private static GitHubGraphQl.ProjectItem story(final String title, final Instant at) {
            return item("PVTI_1", "I_1", 1, title, at, IssueMapping.STORY_TYPE);
        }

        private static GitHubGraphQl.ProjectItem issue2() {
            return item("PVTI_2", "I_2", 2, "Two", SAVED, null);
        }

        private static GitHubGraphQl.ProjectItem item(
                final String itemId,
                final String issueId,
                final int number,
                final String title,
                final Instant at,
                final @Nullable String type) {
            final GitHubGraphQl.IssueRefs none = new GitHubGraphQl.IssueRefs(List.of(), NONE, 0);
            return new GitHubGraphQl.ProjectItem(
                    itemId,
                    false,
                    new GitHubGraphQl.BoardIssue(
                            issueId,
                            10L + number,
                            number,
                            title,
                            "A body",
                            "OPEN",
                            null,
                            at,
                            OWNER + "/example-project",
                            List.of(),
                            null,
                            none,
                            none,
                            NONE,
                            type,
                            null,
                            List.of(new GitHubGraphQl.IssueFieldOption("Priority", "Medium"))),
                    List.of(new GitHubGraphQl.ItemValue("F_status", "O_idea", "Idea", null, null)),
                    NONE);
        }
    }

    /** GitHub's writes as a save sends them: each does nothing but is recorded as touched. */
    private static ProjectWrites writes(final GitHub github) {
        return (ProjectWrites)
                Proxy.newProxyInstance(
                        ProjectWrites.class.getClassLoader(),
                        new Class<?>[] {ProjectWrites.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "createIssue" -> {
                                        github.second = true;
                                        yield new ProjectWrites.Created(
                                                "I_2",
                                                12L,
                                                2,
                                                null,
                                                OrganizationFields.answered(
                                                        (List<
                                                                        IssuesCreateRequestIssueFieldValuesInner>)
                                                                args[6]));
                                    }
                                    case "issuesCreatedSince" ->
                                            new ProjectWrites.FoundPage(
                                                    List.of(
                                                            new ProjectWrites.Found(
                                                                    new ProjectWrites.Created(
                                                                            "I_3", 13L, 3, null),
                                                                    "Recent three",
                                                                    "A body"),
                                                            new ProjectWrites.Found(
                                                                    new ProjectWrites.Created(
                                                                            "I_4", 14L, 4, null),
                                                                    "Recent four",
                                                                    "A body")),
                                                    false);
                                    case "issueFields" -> OrganizationFields.fields();
                                    case "issueFieldValues" -> List.of();
                                    case "addProjectItems" ->
                                            new Batch<>(
                                                    List.of(Batch.Outcome.of("PVTI_2")),
                                                    null,
                                                    GitHubExceptions.WITHOUT_A_WORD);
                                    case "setFieldValues" -> {
                                        final List<?> values = (List<?>) args[2];
                                        yield new Batch<>(
                                                values.stream()
                                                        .map(value -> Batch.Outcome.of("PVTI_2"))
                                                        .toList(),
                                                null,
                                                GitHubExceptions.WITHOUT_A_WORD);
                                    }
                                    default -> null;
                                });
    }

    private BoardCopies copies() {
        return new BoardCopies(directory, files);
    }

    /** A writer that retitles the story, or makes a second issue, on the GitHub given. */
    private static BoardWriter writer(final GitHub github, final Touched touched) {
        final ProjectWrites recorded = touched.recording(writes(github));
        if (github.recentIssues) {
            return new FieldsBoardWriter(
                    recorded,
                    origin -> TOKEN,
                    AccessRefusals.DEFAULT,
                    () -> SAVED,
                    duration -> {},
                    LimitFactors.DEFAULT);
        }
        return new BoardWriter() {
            @Override
            public void save(
                    final Snapshot current, final Revision revision, final Writes changes) {
                recorded.updateIssue(TOKEN, OWNER, "example-project", 1, "Saved", null, null);
                github.title = "Saved";
                github.updatedAt = SAVED;
            }

            @Override
            public String createIssue(
                    final Snapshot current, final Revision revision, final NewIssue issue) {
                recorded.createIssue(TOKEN, OWNER, "example-project", "Two", "", null);
                if (issue.story() != null) {
                    recorded.addSubIssue(TOKEN, OWNER, "example-project", 1, 12L);
                }
                github.second = true;
                return "example-project#2";
            }
        };
    }

    private GitHubStore store(final GitHub github, final TrackerChanges changes) {
        // The repositories' issues unchanged, as the probe tells.
        return store(github, changes, current -> true);
    }

    private GitHubStore store(
            final GitHub github, final TrackerChanges changes, final ChangeProbe probe) {
        final StoryStates states = issueStates -> issueStates.isEmpty() ? null : "Idea";
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github,
                        origin -> TOKEN,
                        AccessRefusals.DEFAULT,
                        states,
                        () -> SAVED,
                        ItemsBoardReader.DEADLINE,
                        length -> {});
        final Touched touched = new Touched();
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(reader, states)
                        .writer(writer(github, touched))
                        .copies(copies())
                        .changes(changes)
                        .probe(probe),
                origin -> TOKEN,
                Duration.ofSeconds(2),
                touched,
                Runnable::run);
    }

    private List<String> heldInCopy() {
        return copies().read(PROJECT, CONFIG.commit()).orElseThrow().held().stream()
                .map(TrackedItem::id)
                .toList();
    }

    private static String storyTitle(final Board board) {
        return board.stories().stream().map(Story::title).findFirst().orElseThrow();
    }

    /** A look's cursor that lists the story on one page with the tag given. */
    private static ChangeCursor cursor(final String tag) {
        return new ChangeCursor(
                SAVED, List.of(new ChangeCursor.Page(null, tag, false, List.of("PVTI_1"))));
    }

    /** A look that names nothing changed and answers the cursors given, one per look. */
    private static TrackerChanges nothingChanged(final ChangeCursor... cursors) {
        final List<ChangeCursor> next = new ArrayList<>(List.of(cursors));
        return (config, token, cursor, known) ->
                Optional.of(
                        new TrackerChanges.Changed(Set.of(), Set.of(), Set.of(), next.remove(0)));
    }

    @Test
    void aCreateReadsOnlyItsNewIssueAndStoryEvenWhenThePersonFiledOtherIssuesToday() {
        // Harm: each create grows slower with every issue the person filed that day.
        final GitHub github = new GitHub();
        github.recentIssues = true;
        final GitHubStore store = store(github, TrackerChanges.NONE);
        final Board board = store.refresh();
        assertThat(board.issues()).as("the other recent issues already on the board").hasSize(2);
        github.calls.clear();

        final NewIssue.Added added =
                store.createIssue(
                        board.revision(),
                        new NewIssue(
                                "example-project",
                                "Two",
                                "A body",
                                null,
                                Worker.AGENT,
                                null,
                                null,
                                "example-project#1"));

        assertThat(github.calls).as("the reads by id after the save").hasSize(1);
        assertThat(github.issueReads)
                .as("only the new issue and its story are read")
                .containsExactlyInAnyOrder("I_1", "I_2");
        assertThat(added.board().issues())
                .as("the new issue beside the unchanged recent issues")
                .extracting(org.fuin.porelo.planner.store.Issue::id)
                .containsExactlyInAnyOrder(
                        "example-project#2", "example-project#3", "example-project#4");
        assertThat(heldInCopy())
                .as("only this save held while GitHub's list lags")
                .containsExactlyInAnyOrder("PVTI_1", "PVTI_2");
    }

    @Test
    void anOwnSaveStaysOnTheBoardWhileGitHubsListLagsIt() {
        // Harm: a whole read right after a save shows the board from before it, and the person's
        // move seems lost.
        final GitHub github = new GitHub();
        final GitHubStore store = store(github, TrackerChanges.NONE);
        final Board board = store.refresh();

        store.save(board.revision(), new Writes(Map.of(), Map.of()));
        final Board read = store.refresh();

        assertThat(heldInCopy()).as("the save held in the copy").containsExactly("PVTI_1");
        assertThat(storyTitle(read)).as("the title after a whole read").isEqualTo("Saved");
    }

    @Test
    void anOwnNewItemTheListLacksStays() {
        // Harm: an issue just made drops off the board at the next whole read.
        final GitHub github = new GitHub();
        final GitHubStore store = store(github, TrackerChanges.NONE);
        final Board board = store.refresh();

        store.createIssue(
                board.revision(),
                new NewIssue(
                        "example-project",
                        "Two",
                        "- it works",
                        "S",
                        Worker.AGENT,
                        null,
                        null,
                        null));
        github.calls.clear();
        final Board read = store.refresh();

        assertThat(read.issues()).as("the issues after a whole read").hasSize(1);
        assertThat(github.calls).contains("issueBoard of [I_2]");
        assertThat(heldInCopy()).contains("PVTI_2");
    }

    @Test
    void aHeldSaveIsReleasedOnlyOnceTheListShowsItAtItsTime() {
        // Harm: a save released at a changed page while GitHub's list still shows the item from
        // before it, so a whole read puts that back; or a save held for ever, its later change on
        // GitHub hidden behind it.
        final GitHub github = new GitHub();
        final List<ChangeCursor> next =
                new ArrayList<>(List.of(cursor("t1"), cursor("t2"), cursor("t3")));
        final AtomicReference<@Nullable Instant> shown = new AtomicReference<>(BEFORE);
        final class Look implements TrackerChanges, ItemIssues {
            @Override
            public Optional<Changed> since(
                    final ProjectConfig config,
                    final String token,
                    final ChangeCursor cursor,
                    final Map<String, TrackedItem> known) {
                return Optional.of(new Changed(Set.of(), Set.of(), Set.of(), next.remove(0)));
            }

            @Override
            public @Nullable String issueOf(final String itemId) {
                return null;
            }

            @Override
            public @Nullable Listed listed(final String itemId) {
                final Instant at = shown.get();
                return at == null ? null : new Listed(false, at, at);
            }
        }
        final GitHubStore store = store(github, new Look());
        final Board board = store.refresh();
        store.poll();

        store.save(board.revision(), new Writes(Map.of(), Map.of()));
        store.poll();
        assertThat(heldInCopy())
                .as("held while its page, answered anew, shows the item from before it")
                .containsExactly("PVTI_1");

        shown.set(SAVED);
        store.poll();
        assertThat(heldInCopy()).as("held once the list shows the save's time").isEmpty();
    }

    @Test
    void anItemNewOnTheBoardIsReadByItsIssueNotTheWholeBoard() {
        // Harm: every issue made on GitHub reads the whole board, minutes on a large project.
        final GitHub github = new GitHub();
        final class Look implements TrackerChanges, ItemIssues {
            @Override
            public Optional<Changed> since(
                    final ProjectConfig config,
                    final String token,
                    final ChangeCursor cursor,
                    final Map<String, TrackedItem> known) {
                return Optional.of(new Changed(Set.of(), Set.of("PVTI_2"), Set.of(), cursor("t1")));
            }

            @Override
            public @Nullable String issueOf(final String itemId) {
                return "PVTI_2".equals(itemId) ? "I_2" : null;
            }
        }
        final GitHubStore store = store(github, new Look());
        store.refresh();
        github.second = true;
        github.calls.clear();

        final Board polled = store.poll();

        assertThat(github.calls).as("GitHub's reads").containsExactly("issueBoard of [I_2]");
        assertThat(polled.issues()).as("the new issue").hasSize(1);
    }

    @Test
    void aChangeToAnIssueTheItemListLacksIsSeenAtTheNextPollByAWholeRead() {
        // Harm: an issue of the project's repositories that is no item of the board's list, such
        // as a story's sub-issue there, changes on GitHub and the board never shows it, since the
        // item list the look asks does not carry it.
        final GitHub github = new GitHub();
        final java.util.concurrent.atomic.AtomicBoolean outside =
                new java.util.concurrent.atomic.AtomicBoolean();
        final GitHubStore store =
                store(
                        github,
                        nothingChanged(cursor("t1"), cursor("t1")),
                        current -> !outside.getAndSet(false));
        store.refresh();
        github.calls.clear();
        store.poll();
        assertThat(github.calls).as("GitHub's reads while nothing changed").isEmpty();

        outside.set(true);
        github.listedTitle = "One, read whole";
        final Board polled = store.poll();

        assertThat(github.calls)
                .as("GitHub's reads after the probe saw the change")
                .containsExactly("projectFields", "projectItems");
        assertThat(storyTitle(polled)).as("the board read whole").isEqualTo("One, read whole");
    }
}
