package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.GraphQlCost;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.cache.BoardCopies;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.CopyFiles;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubStoreCopyTest {

    private static final String TOKEN = "invented-token-copy-0123456789";

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

    private static final Instant FIRST = Instant.parse("2026-11-21T10:00:00Z");

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

    private final AtomicReference<Instant> now = new AtomicReference<>(FIRST);

    /** GitHub's project as the fake answers it, and every read sent to it. */
    private static final class GitHub implements ProjectQueries {

        private final List<String> calls = new ArrayList<>();

        private String title = "One";

        private @Nullable RuntimeException failure;

        @Override
        public GitHubGraphQl.ProjectFields projectFields(
                final String token, final String owner, final int number, final String after) {
            answer("projectFields");
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
                                    1L)),
                    new GitHubGraphQl.PageInfo(false, null),
                    COST);
        }

        @Override
        public GitHubGraphQl.ProjectItems projectItems(
                final String token, final String owner, final int number, final String after) {
            answer("projectItems");
            return new GitHubGraphQl.ProjectItems(
                    List.of(
                            item(),
                            new GitHubGraphQl.ProjectItem(
                                    "PVTI_draft",
                                    false,
                                    null,
                                    List.of(),
                                    new GitHubGraphQl.PageInfo(false, null))),
                    new GitHubGraphQl.PageInfo(false, null),
                    COST,
                    2);
        }

        @Override
        public GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            answer("issueBoard of " + issueIds);
            final GitHubGraphQl.ProjectItem item = item();
            return new GitHubGraphQl.IssueBoard(
                    List.of(
                            new GitHubGraphQl.IssueOnBoard(
                                    item.issue(),
                                    item.id(),
                                    item.values(),
                                    new GitHubGraphQl.PageInfo(false, null))),
                    COST);
        }

        /** The project's one item, a story. */
        private GitHubGraphQl.ProjectItem item() {
            final GitHubGraphQl.IssueRefs none =
                    new GitHubGraphQl.IssueRefs(
                            List.of(), new GitHubGraphQl.PageInfo(false, null), 0);
            return new GitHubGraphQl.ProjectItem(
                    "PVTI_1",
                    false,
                    new GitHubGraphQl.BoardIssue(
                            "I_1",
                            11L,
                            1,
                            title,
                            "A body",
                            "OPEN",
                            null,
                            Instant.parse("2026-11-20T10:00:00Z"),
                            OWNER + "/example-project",
                            List.of(),
                            null,
                            none,
                            none,
                            new GitHubGraphQl.PageInfo(false, null),
                            IssueMapping.STORY_TYPE,
                            null,
                            List.of(new GitHubGraphQl.IssueFieldOption("Priority", "Medium"))),
                    List.of(new GitHubGraphQl.ItemValue("F_status", "O_idea", "Idea", null, null)),
                    new GitHubGraphQl.PageInfo(false, null));
        }

        private void answer(final String call) {
            calls.add(call);
            final RuntimeException refused = failure;
            if (refused != null) {
                throw refused;
            }
        }
    }

    private BoardCopies copies() {
        return new BoardCopies(directory, files);
    }

    private GitHubStore store(
            final GitHub github, final BoardWriter writer, final AccessRefusals refusals) {
        return store(github, writer, refusals, TrackerChanges.NONE);
    }

    private GitHubStore store(
            final GitHub github,
            final BoardWriter writer,
            final AccessRefusals refusals,
            final TrackerChanges changes) {
        return store(github, writer, refusals, changes, new Touched());
    }

    private GitHubStore store(
            final GitHub github,
            final BoardWriter writer,
            final AccessRefusals refusals,
            final TrackerChanges changes,
            final Touched touched) {
        final StoryStates states = issueStates -> issueStates.isEmpty() ? null : "Idea";
        final ItemsBoardReader reader =
                new ItemsBoardReader(
                        github,
                        origin -> TOKEN,
                        refusals,
                        states,
                        now::get,
                        ItemsBoardReader.DEADLINE,
                        length -> {});
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(reader, states)
                        .writer(writer)
                        .refusals(refusals)
                        .copies(copies())
                        .changes(changes),
                origin -> TOKEN,
                Duration.ofSeconds(2),
                touched,
                Runnable::run);
    }

    private GitHubStore store(final GitHub github) {
        return store(github, BoardWriter.NONE, AccessRefusals.DEFAULT);
    }

    /** A copy written by a store that read the board from GitHub before. */
    private Board heldBefore() {
        final Board board = store(new GitHub()).refresh();
        assertThat(copies().read(PROJECT, CONFIG.commit()))
                .as("the copy written after the whole read")
                .isPresent();
        return board;
    }

    @Test
    void aStartWithACopyAnswersTheBoardWithoutAGraphQlRead() {
        // Harm: a start that waits minutes for a whole read while the board is on disk.
        final Board before = heldBefore();
        final GitHub github = new GitHub();
        final GitHubStore store = store(github);

        final Board board = store.board();

        assertThat(board).as("the board from the copy").isEqualTo(before);
        assertThat(github.calls).as("GitHub's reads at start").isEmpty();
    }

    @Test
    void theFirstPollAfterAStartFromTheCopyReadsGitHubWhole() {
        // Harm: a change made on GitHub while the planner was stopped never shown.
        heldBefore();
        final GitHub github = new GitHub();
        github.title = "One, changed on GitHub";
        final GitHubStore store = store(github);
        store.board();

        final Board polled = store.poll();

        assertThat(github.calls)
                .as("the first poll's reads")
                .containsExactly("projectFields", "projectItems");
        assertThat(polled.stories().get(0).title())
                .as("the change made on GitHub")
                .isEqualTo("One, changed on GitHub");
    }

    @Test
    void aSaveFailingBeforeAnyWriteKeepsTheCopy() {
        // Harm: a save GitHub refused before writing anything removes the copy, so the next start
        // waits for a whole read of the project. A save that failed halfway reads again what it
        // touched, or forgets the board and its copy where that read fails.
        heldBefore();
        final GitHubStore store =
                store(
                        new GitHub(),
                        new BoardWriter() {
                            @Override
                            public void save(
                                    final Snapshot current,
                                    final Revision revision,
                                    final Writes writes) {
                                throw new IllegalStateException("GitHub failed halfway");
                            }
                        },
                        AccessRefusals.DEFAULT);
        final Board board = store.board();

        assertThatThrownBy(() -> store.save(board.revision(), new Writes(Map.of(), Map.of())))
                .as("the failed save")
                .isInstanceOf(StoreException.class);
        assertThat(copies().read(PROJECT, CONFIG.commit()))
                .as("the copy after the failed save")
                .isPresent();
    }

    @Test
    void aRefusedAccessRemovesTheCopy() {
        // Harm: a board shown at start from a copy while GitHub refuses the person's access.
        heldBefore();
        final GitHub github = new GitHub();
        final GitHubStore store =
                store(
                        github,
                        BoardWriter.NONE,
                        (repository, failure, token) ->
                                new StoreException.NoForgeAccess("https://github.com", null));
        store.board();
        github.failure = new IllegalStateException("401 Bad credentials");

        assertThatThrownBy(store::poll)
                .as("the poll GitHub refuses")
                .isInstanceOf(StoreException.NoForgeAccess.class);
        assertThat(copies().read(PROJECT, CONFIG.commit()))
                .as("the copy after the refusal")
                .isEmpty();
    }

    @Test
    void aSaveWritesTheCopyAtOnce() {
        // Harm: a save made just before the planner stops missing from the board at its start.
        heldBefore();
        final GitHub github = new GitHub();
        final Touched touched = new Touched();
        final ProjectWrites recorded =
                touched.recording(
                        (ProjectWrites)
                                java.lang.reflect.Proxy.newProxyInstance(
                                        ProjectWrites.class.getClassLoader(),
                                        new Class<?>[] {ProjectWrites.class},
                                        (proxy, method, args) -> null));
        final GitHubStore store =
                store(
                        github,
                        new BoardWriter() {
                            @Override
                            public void save(
                                    final Snapshot current,
                                    final Revision revision,
                                    final Writes writes) {
                                recorded.updateIssue(
                                        TOKEN, OWNER, "example-project", 1, "Saved", null, null);
                                github.title = "Saved";
                            }
                        },
                        AccessRefusals.DEFAULT,
                        TrackerChanges.NONE,
                        touched);
        final Board board = store.board();
        final Instant saved = FIRST.plusSeconds(60);
        now.set(saved);

        store.save(board.revision(), new Writes(Map.of(), Map.of()));

        final BoardCopy copy = copies().read(PROJECT, CONFIG.commit()).orElseThrow();
        assertThat(copy.takenAt()).as("when the copy was taken").isEqualTo(saved);
        assertThat(copy.items())
                .as("the saved item in the copy")
                .filteredOn(item -> item.id().equals("PVTI_1"))
                .extracting(item -> java.util.Objects.requireNonNull(item.issue()).title())
                .containsExactly("Saved");
        final GitHub later = new GitHub();
        assertThat(store(later).board().stories())
                .as("the board at the next start, from the copy")
                .extracting(org.fuin.porelo.planner.store.Story::title)
                .containsExactly("Saved");
        assertThat(later.calls).as("GitHub's reads at the next start").isEmpty();
    }

    private static final ChangeCursor LOOKED =
            new ChangeCursor(
                    Instant.parse("2026-11-21T10:30:00Z"),
                    List.of(new ChangeCursor.Page(null, "W/\"page-1\"", false, List.of("PVTI_1"))));

    @Test
    void aPollAfterAStartFromTheCopyReadsAgainOnlyWhatTheTrackerSaysChanged() {
        // Harm: a start from the copy that still reads the whole project at its first poll, or
        // folds a change in without keeping where the look stood.
        heldBefore();
        final GitHub github = new GitHub();
        github.title = "One, changed on GitHub";
        final List<ChangeCursor> asked = new ArrayList<>();
        final GitHubStore store =
                store(
                        github,
                        BoardWriter.NONE,
                        AccessRefusals.DEFAULT,
                        (config, token, cursor, known) -> {
                            asked.add(cursor);
                            assertThat(known).as("the items the copy holds").containsKey("PVTI_1");
                            return Optional.of(
                                    new TrackerChanges.Changed(
                                            Set.of("PVTI_1"), Set.of(), Set.of(), LOOKED));
                        });
        store.board();

        final Board polled = store.poll();

        assertThat(asked)
                .as("the cursors the look was asked from")
                .containsExactly(ChangeCursor.NONE);
        assertThat(github.calls)
                .as("GitHub's reads at the first poll")
                .containsExactly("issueBoard of [I_1]");
        assertThat(polled.stories().get(0).title())
                .as("the change folded in")
                .isEqualTo("One, changed on GitHub");
        assertThat(copies().read(PROJECT, CONFIG.commit()))
                .as("the copy after the fold")
                .map(BoardCopy::cursor)
                .contains(LOOKED);
    }

    @Test
    void aGoneItemIsDroppedAndANewOneReadsTheBoardWhole() {
        // Harm: an item removed on GitHub still shown, or a new one never shown.
        heldBefore();
        final GitHub github = new GitHub();
        final GitHubStore gone =
                store(
                        github,
                        BoardWriter.NONE,
                        AccessRefusals.DEFAULT,
                        (config, token, cursor, known) ->
                                Optional.of(
                                        new TrackerChanges.Changed(
                                                Set.of(), Set.of(), Set.of("PVTI_draft"), LOOKED)));
        gone.board();
        gone.poll();

        assertThat(github.calls).as("GitHub's reads for a gone item").isEmpty();
        assertThat(copies().read(PROJECT, CONFIG.commit()))
                .as("the copy's items after the fold")
                .map(copy -> copy.items().stream().map(TrackedItem::id).toList())
                .contains(List.of("PVTI_1"));

        heldBefore();
        final GitHub again = new GitHub();
        final GitHubStore added =
                store(
                        again,
                        BoardWriter.NONE,
                        AccessRefusals.DEFAULT,
                        (config, token, cursor, known) ->
                                Optional.of(
                                        new TrackerChanges.Changed(
                                                Set.of(), Set.of("PVTI_2"), Set.of(), LOOKED)));
        added.board();
        added.poll();

        assertThat(again.calls)
                .as("the reads for an item the copy lacks")
                .containsExactly("projectFields", "projectItems");
    }
}
