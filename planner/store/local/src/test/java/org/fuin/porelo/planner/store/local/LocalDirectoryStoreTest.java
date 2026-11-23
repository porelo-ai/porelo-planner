package org.fuin.porelo.planner.store.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.StageEntry;
import org.fuin.porelo.planner.store.StageMetrics;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.StoryFromIssues;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("linux")
@EnabledOnOs(OS.LINUX)
class LocalDirectoryStoreTest {

    @TempDir Path temp;

    private Path items;

    private final PrivateFiles files = PrivateFiles.of(Map.of());

    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Analyzing", "Analyzed", "Done"),
                    List.of(),
                    List.of("Done"));

    static final TrackedProject PROJECT =
            new TrackedProject(
                    null,
                    "",
                    "local",
                    "",
                    List.of("plans", "engine"),
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    "files",
                    states -> states.isEmpty() ? "Idea" : states.getFirst());

    @BeforeEach
    void directory() throws Exception {
        items =
                Files.createDirectory(
                        temp.resolve("items"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
    }

    private LocalDirectoryStore store() {
        return store(StageMetrics.NONE);
    }

    private LocalDirectoryStore store(final StageMetrics metrics) {
        return new LocalDirectoryStore(items, files, PROJECT, () -> PROJECT, metrics, CLOCK);
    }

    private LocalDirectoryStore store(final PrivateFiles hooked) {
        return new LocalDirectoryStore(
                items, hooked, PROJECT, () -> PROJECT, StageMetrics.NONE, CLOCK);
    }

    private static NewIssue.Added issue(final LocalDirectoryStore store) {
        return store.createIssue(
                store.board().revision(),
                new NewIssue("engine", "Keep the data", "The data survives", "S"));
    }

    private void edit(final String name, final UnaryOperator<String> edit) throws Exception {
        final Path file = items.resolve(name);
        Files.writeString(file, edit.apply(Files.readString(file)));
    }

    @Test
    void aBoardAndAMoveReadBackFromTheDirectory() throws Exception {
        // Harm: planning without a forge loses a new issue or its move, or sends a local id to one.
        final LocalDirectoryStore store = store();
        final NewIssue.Added added = issue(store);
        store.save(
                added.board().revision(),
                new Writes(Map.of(added.id(), "Backlog"), Map.of(), List.of()));

        final Board read = store().board();
        assertThat(added.id()).as("the repository's first number").isEqualTo("engine#1");
        assertThat(read.issue(added.id()).orElseThrow().state())
                .as("the move survives reopening")
                .isEqualTo("Backlog");
        assertThat(Files.readString(items.resolve("engine-1.md")))
                .as("the file holds the state")
                .contains("\"Status\" : \"Backlog\"");
        assertThat(read.owner()).as("no forge's owner for a local id").isNull();
        assertThat(read.issue(added.id()).orElseThrow().link()).as("no invented link").isNull();
    }

    @Test
    void pollingSeesContentsAndAStaleSaveWritesNothing() throws Exception {
        // Harm: a stale window overwrites a hand edit that kept the file's size and time.
        final LocalDirectoryStore store = store();
        final NewIssue.Added added = issue(store);
        final var modified = Files.getLastModifiedTime(items.resolve("engine-1.md"));
        edit("engine-1.md", text -> text.replace("Keep the data", "Save the data"));
        Files.setLastModifiedTime(items.resolve("engine-1.md"), modified);

        final Board polled = store.poll();
        assertThat(polled.issue(added.id()).orElseThrow().title())
                .as("the contents count in the poll's digest")
                .isEqualTo("Save the data");
        assertThatThrownBy(
                        () ->
                                store.save(
                                        added.board().revision(),
                                        new Writes(
                                                Map.of(added.id(), "Done"), Map.of(), List.of())))
                .as("a write reads the directory again")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(store().issue(added.id()).state())
                .as("the stale move wrote nothing")
                .isEqualTo("Idea");
    }

    @Test
    void twoWritersTakeDifferentNumbers() throws Exception {
        // Harm: two writers give two issues one file, and one overwrites the other.
        final LocalDirectoryStore first = store();
        final LocalDirectoryStore second = store();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Callable<String>> writers =
                    List.of(() -> createWithRetry(first), () -> createWithRetry(second));
            final var results = executor.invokeAll(writers);
            assertThat(Set.of(results.get(0).get(), results.get(1).get()))
                    .as("one number per writer")
                    .containsExactlyInAnyOrder("engine#1", "engine#2");
        }
        assertThat(store().board().issues()).as("both issues stand").hasSize(2);
    }

    private static String createWithRetry(final LocalDirectoryStore store) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return issue(store).id();
            } catch (final StoreException.StaleRevision stale) {
                store.refresh();
            }
        }
        throw new AssertionError("The writer could not retry a moved revision");
    }

    @Test
    void aStageSavedTwiceCountsOnce() throws Exception {
        // Harm: a stage handed in again counts its minutes, tokens and stage twice.
        final StageEntry stage =
                new StageEntry(
                        "task/1:2",
                        "plan",
                        "done",
                        "agent",
                        "model",
                        "high",
                        7,
                        2,
                        Map.of("codex_input_tokens", 100L));
        final LocalDirectoryStore store = store((id, result) -> Optional.of(stage));
        final NewIssue.Added added = issue(store);
        store.save(
                added.board().revision(),
                new Writes(Map.of(added.id(), "Analyzing"), Map.of(), List.of()));
        final StageResult result =
                new StageResult(
                        WorkRule.Result.ANALYSIS,
                        List.of(
                                new StageResult.ForIssue(
                                        added.id(),
                                        "Analyzing",
                                        "Analyzed",
                                        "Keep one copy.",
                                        "S",
                                        "One file",
                                        null,
                                        List.of(),
                                        List.of())));
        store.saveResult(result);
        // The issue is moved back by hand, so the same result can be handed in again.
        store.save(
                store.board().revision(),
                new Writes(Map.of(added.id(), "Analyzing"), Map.of(), List.of()));
        store.saveResult(result);

        final String text = Files.readString(items.resolve("engine-1.md"));
        assertThat(store().issue(added.id()).minutes())
                .as("the minutes are added once")
                .isEqualTo(7.0);
        assertThat(text)
                .as("the figures and one entry of the stage, with the end the store wrote")
                .contains("\"Stages\" : 1", "\"Tokens\" : 100", "1970-01-01T00:00:00Z");
        assertThat(text.split("task/1:2", -1)).as("the stage is kept once").hasSize(2);
    }

    @Test
    void badFieldsStayOnTheBoardWithWarnings() throws Exception {
        // Harm: a broken field or link makes a named item vanish from planning.
        final LocalDirectoryStore store = store();
        final NewIssue.Added added = issue(store);
        edit(
                "engine-1.md",
                text ->
                        text.replace("\"Idea\"", "\"Unknown state\"")
                                .replace("\"S\"", "\"Enormous\"")
                                .replace(
                                        "\"blockedBy\" : [ ]", "\"blockedBy\" : [ \"engine#9\" ]"));

        final Issue read = store.refresh().issue(added.id()).orElseThrow();
        assertThat(read.warnings())
                .as("a bad state, size and blocker are warnings on the item")
                .extracting(Warning::kind)
                .contains(
                        Warning.STATE_OUTSIDE_WORKFLOW,
                        Warning.UNKNOWN_SIZE,
                        Warning.BLOCKER_OUTSIDE_PROJECT);
    }

    @Test
    void linkedOversizedAndSecondNamedFilesAreFaultsAndOtherEntriesDoNotWakePolling()
            throws Exception {
        // Harm: a placed link or hard link reads another file, or a huge file is read whole.
        final LocalDirectoryStore store = store();
        issue(store);
        final Board before = store.board();
        Files.createDirectory(items.resolve("engine-9.md"));
        Files.writeString(items.resolve(".engine-1.md.swp"), "noise");
        Files.createDirectory(items.resolve(".git"));
        assertThat(store.poll())
                .as("entries of no item's name do not wake polling")
                .isSameAs(before);

        final Path secret = Files.writeString(temp.resolve("secret"), "a secret");
        Files.createSymbolicLink(items.resolve("engine-2.md"), secret);
        Files.writeString(items.resolve("engine-3.md"), "x".repeat((1 << 20) + 1));
        Files.createLink(items.resolve("engine-4.md"), secret);
        final Board read = store.poll();
        assertThat(read.issues()).as("no refused file is read").hasSize(1);
        assertThat(read.faults())
                .as("each refused name is told")
                .anyMatch(f -> f.startsWith("engine-2.md") && f.contains("link"))
                .anyMatch(f -> f.startsWith("engine-3.md") && f.contains("1 MiB"))
                .anyMatch(f -> f.startsWith("engine-4.md") && f.contains("second name"))
                .noneMatch(f -> f.contains(temp.toString()));
    }

    @Test
    void tooManyItemFilesAreRefusedUnread() throws Exception {
        // Harm: a directory flooded with item files is read whole on every poll.
        final LocalDirectoryStore store = store();
        for (int number = 1; number <= LocalReader.MOST_ITEMS + 1; number++) {
            Files.createFile(items.resolve("engine-" + number + ".md"));
        }

        final Board read = store.refresh();
        assertThat(read.faults())
                .as("the directory is named, no file is read")
                .containsExactly(
                        "The items directory holds more than "
                                + LocalReader.MOST_ITEMS
                                + " item files; none is read");
    }

    @Test
    void mismatchedHeadsAndCaseCollisionsAreFaults() throws Exception {
        // Harm: two case variants or a mismatched head redirect a save to the wrong item.
        final LocalDirectoryStore store = store();
        issue(store);
        Files.copy(items.resolve("engine-1.md"), items.resolve("engine-2.md"));
        Files.copy(items.resolve("engine-1.md"), items.resolve("Engine-1.md"));

        final Board read = store.refresh();
        assertThat(read.issues()).as("ambiguous names and mismatched heads are left out").isEmpty();
        assertThat(read.faults())
                .as("all three files are named")
                .anyMatch(f -> f.startsWith("engine-1.md"))
                .anyMatch(f -> f.startsWith("Engine-1.md"))
                .anyMatch(f -> f.startsWith("engine-2.md"));
    }

    @Test
    void everyReadAndWriteChecksTheDirectoryAgain() throws Exception {
        // Harm: a directory replaced after opening is made again or written through a link.
        final LocalDirectoryStore store = store();
        final NewIssue.Added added = issue(store);
        final Path elsewhere = temp.resolve("elsewhere");
        Files.move(items, elsewhere);

        assertThatThrownBy(store::refresh)
                .as("a missing directory is refused")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageNotContaining(temp.toString());
        assertThat(items).as("a read does not make it").doesNotExist();
        Files.createSymbolicLink(items, elsewhere);
        assertThatThrownBy(
                        () ->
                                store.setFields(
                                        added.board().revision(),
                                        added.id(),
                                        new Fields(
                                                Set.of(Fields.Field.SIZE), "M", null, null, null)))
                .as("a write does not follow a replaced directory")
                .isInstanceOf(StoreException.Unreadable.class);
        assertThat(Files.readString(elsewhere.resolve("engine-1.md")))
                .as("nothing was written through the link")
                .doesNotContain("\"M\"");
    }

    @Test
    void storiesFieldsLinksAndTextAreSavedTogether() throws Exception {
        // Harm: an edit by hand in the window is lost on reopening, or the stages are edited.
        final LocalDirectoryStore store = store((id, result) -> Optional.of(STAGE));
        final NewIssue.Added first = issue(store);
        final NewIssue.Added second = issue(store);
        store.save(
                second.board().revision(),
                new Writes(Map.of(first.id(), "Analyzing"), Map.of(), List.of()));
        store.saveResult(analysis(first.id()));
        final String stages = stages("engine-1.md");
        final NewIdea.Added story =
                store.createStoryFromIssues(
                        store.board().revision(),
                        new StoryFromIssues(
                                "A story", "Keep data", "Read it", "High", List.of(first.id())));
        store.setStoryIssues(
                story.board().revision(), story.story(), List.of(second.id(), first.id()));
        store.setBlockedBy(store.board().revision(), second.id(), List.of(first.id()));
        store.setFields(
                store.board().revision(),
                first.id(),
                new Fields(Set.of(Fields.Field.ESTIMATE), null, 20, null, null));
        store.setText(
                store.board().revision(),
                first.id(),
                new TextEdit(
                        TextEdit.Kind.ISSUE,
                        "A new title",
                        Map.of(TextEdit.Part.PLAN, "One safe write."),
                        Set.of()));

        final Board read = store().board();
        assertThat(story.story())
                .as("the story in the project's own repository")
                .isEqualTo("plans#1");
        assertThat(read.story(story.story()).orElseThrow().issues())
                .as("the story keeps its order")
                .containsExactly(second.id(), first.id());
        assertThat(read.issue(second.id()).orElseThrow().blockedBy())
                .as("the blocker stands")
                .containsExactly(first.id());
        assertThat(read.issue(first.id()).orElseThrow().estimate())
                .as("the estimate stands")
                .isEqualTo(20.0);
        assertThat(read.issue(first.id()).orElseThrow().text())
                .as("the edited section stands")
                .contains("One safe write.");
        assertThat(read.issue(first.id()).orElseThrow().warnings())
                .as("parent and list agree")
                .isEmpty();
        assertThat(stages("engine-1.md")).as("the stages are not edited").isEqualTo(stages);
    }

    @Test
    void aStoryFromIssuesTakesNoIssueAnotherStoryHolds() throws Exception {
        // Harm: an issue ends in two stories, and a work rule counts it under the wrong one.
        final LocalDirectoryStore store = store();
        final NewIssue.Added first = issue(store);
        final NewIdea.Added story =
                store.createStoryFromIssues(
                        first.board().revision(),
                        new StoryFromIssues("One", "Gain", "Test", "High", List.of(first.id())));

        assertThatThrownBy(
                        () ->
                                store.createStoryFromIssues(
                                        story.board().revision(),
                                        new StoryFromIssues(
                                                "Two",
                                                "Gain",
                                                "Test",
                                                "High",
                                                List.of(first.id()))))
                .as("the issue's story is named")
                .isInstanceOf(StoreException.InStory.class)
                .hasMessageContaining(story.story());
    }

    private static final StageEntry STAGE =
            new StageEntry(
                    "task/1:2",
                    "plan",
                    "done",
                    "agent",
                    "model",
                    "high",
                    7,
                    2,
                    Map.of("codex_input_tokens", 100L));

    private static StageResult analysis(final String id) {
        return new StageResult(
                WorkRule.Result.ANALYSIS,
                List.of(
                        new StageResult.ForIssue(
                                id,
                                "Analyzing",
                                "Analyzed",
                                "Keep one copy.",
                                "S",
                                "One file",
                                null,
                                List.of(),
                                List.of())));
    }

    private String stages(final String name) throws Exception {
        final String text = Files.readString(items.resolve(name));
        return text.substring(text.indexOf(LocalItem.STAGES_LINE));
    }

    @Test
    void unreadableStagesAreKeptThroughEveryWriteAndNoStageIsSavedOverThem() throws Exception {
        // Harm: a typo in the stages by hand, then a move, erases every recorded stage for good.
        final LocalDirectoryStore store = store((id, result) -> Optional.of(STAGE));
        final NewIssue.Added added = issue(store);
        final String broken =
                "\n"
                        + LocalItem.STAGES_LINE
                        + "\n## Stages\n\n```json\n[ { \"key\" : \"task/1:1\",\n```\n";
        edit("engine-1.md", text -> text + broken);
        store.save(
                store.refresh().revision(),
                new Writes(Map.of(added.id(), "Analyzing"), Map.of(), List.of()));
        store.setText(
                store.board().revision(),
                added.id(),
                new TextEdit(
                        TextEdit.Kind.ISSUE,
                        "A new title",
                        Map.of(TextEdit.Part.PLAN, "One safe write."),
                        Set.of()));

        final String written = Files.readString(items.resolve("engine-1.md"));
        assertThat(written)
                .as("the unread stages stand as they were written")
                .contains("One safe write.")
                .endsWith(broken);
        assertThat(store.board().issue(added.id()).orElseThrow().warnings())
                .as("the board still warns")
                .extracting(Warning::kind)
                .contains("LocalStagesUnclear");
        assertThatThrownBy(() -> store.saveResult(analysis(added.id())))
                .as("a stage is refused in fixed words")
                .isInstanceOf(StoreException.NotSavable.class)
                .hasMessageContaining("its stages cannot be read, so no stage is saved over them");
        assertThat(Files.readString(items.resolve("engine-1.md")))
                .as("nothing was written over them")
                .isEqualTo(written);
    }

    @Test
    void aFileGoneOrChangedWhileReadIsLeftOutOfThatReadAlone() throws Exception {
        // Harm: an editor saving a file while the board is polled makes the item a fault or the
        // whole project unreadable.
        final HookedFiles hooked = new HookedFiles();
        final LocalDirectoryStore store = store(hooked);
        issue(store);
        issue(store);
        hooked.afterCheckOf("engine-2.md", () -> Files.delete(items.resolve("engine-2.md")));
        final Board gone = store.poll();
        assertThat(gone.issue("engine#2")).as("the vanished file is no item").isEmpty();
        assertThat(gone.issue("engine#1")).as("the others are read").isPresent();
        assertThat(gone.faults()).as("a vanished file is no fault").isEmpty();

        final Path file = items.resolve("engine-1.md");
        final String text = Files.readString(file);
        hooked.afterCheckOf(
                "engine-1.md",
                () -> Files.writeString(file, text.replace("Keep the data", "Keep all data")));
        final Board changing = store.poll();
        assertThat(changing.issue("engine#1"))
                .as("a file changed while read is left out")
                .isEmpty();
        assertThat(changing.faults()).as("a changed file is no fault").isEmpty();
        assertThat(store.poll().issue("engine#1").orElseThrow().title())
                .as("the next poll reads it")
                .isEqualTo("Keep all data");
    }

    @Test
    void aSaveCutOffHalfWayAnswersWhatIsOnDisk() throws Exception {
        // Harm: after a partly written save the window is shown the board before it, and its next
        // save is refused or decided on items that changed.
        final HookedFiles hooked = new HookedFiles();
        final LocalDirectoryStore store = store(hooked);
        issue(store);
        issue(store);
        hooked.beforeNewFile(
                2,
                () -> {
                    final String first;
                    try (var names = Files.list(items)) {
                        first =
                                names.map(path -> path.getFileName().toString())
                                        .filter(name -> name.endsWith(".tmp"))
                                        .findFirst()
                                        .orElseThrow();
                    }
                    // The other item's file becomes a directory with an entry, so its move fails.
                    final Path other =
                            items.resolve(
                                    first.startsWith(".engine-1.md.")
                                            ? "engine-2.md"
                                            : "engine-1.md");
                    Files.delete(other);
                    Files.writeString(Files.createDirectory(other).resolve("entry"), "x");
                });
        final Board before = store.board();

        assertThatThrownBy(
                        () ->
                                store.save(
                                        before.revision(),
                                        new Writes(
                                                Map.of(
                                                        "engine#1",
                                                        "Backlog",
                                                        "engine#2",
                                                        "Backlog"),
                                                Map.of(),
                                                List.of())))
                .isInstanceOf(StoreException.PartlySaved.class);
        final Board shown = store.board();
        assertThat(shown.revision()).as("the revision on disk").isNotEqualTo(before.revision());
        assertThat(shown.issues())
                .as("the item written shows its move")
                .extracting(Issue::state)
                .containsExactly("Backlog");
        try (var names = Files.list(items)) {
            assertThat(names.map(path -> path.getFileName().toString()))
                    .as("no temporary file is left")
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void aFailedSaveRemovesTheEmptyFileItClaimedAndNothingElse() throws Exception {
        // Harm: a failed save leaves an empty item file that stays a fault on the board, or removes
        // a link put in its place, judging it by the file the link names.
        final HookedFiles hooked = new HookedFiles();
        final LocalDirectoryStore store = store(hooked);
        hooked.beforeNewFile(
                2,
                () -> {
                    throw new java.io.UncheckedIOException(
                            new java.io.IOException("the disk is full"));
                });
        assertThatThrownBy(() -> issue(store)).isInstanceOf(RuntimeException.class);
        assertThat(items.resolve("engine-1.md")).as("the claimed file is removed").doesNotExist();
        assertThat(store.refresh().faults()).as("no fault is left").isEmpty();

        final Path empty = Files.createFile(temp.resolve("empty"));
        hooked.beforeNewFile(
                2,
                () -> {
                    final Path claimed = items.resolve("engine-1.md");
                    Files.delete(claimed);
                    Files.createSymbolicLink(claimed, empty);
                    throw new java.io.UncheckedIOException(
                            new java.io.IOException("the disk is full"));
                });
        assertThatThrownBy(() -> issue(store)).isInstanceOf(RuntimeException.class);
        assertThat(items.resolve("engine-1.md"))
                .as("a link in the claimed file's place is no file the save made")
                .isSymbolicLink();

        Files.delete(items.resolve("engine-1.md"));
        Files.createFile(items.resolve("engine-1.md"));
        assertThat(store.refresh().faults())
                .as("an empty file a cut-off save left is named")
                .singleElement()
                .satisfies(fault -> assertThat(fault).startsWith("engine-1.md"));
    }

    @Test
    void aNameTakenSinceTheReadIsNeverWrittenOverAndTheNewRevisionIsTold() throws Exception {
        // Harm: a file placed by hand or by another planner after the read is overwritten by a new
        // issue, or the window retries with the revision that was already stale.
        final HookedFiles hooked = new HookedFiles();
        final LocalDirectoryStore store = store(hooked);
        issue(store);
        final Board shown = store.board();
        hooked.afterCheckOf(
                "engine-1.md",
                () -> Files.writeString(items.resolve("engine-2.md"), "written by hand"));

        assertThatThrownBy(
                        () ->
                                store.createIssue(
                                        shown.revision(),
                                        new NewIssue("engine", "Another", "More", "S")))
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale -> {
                            assertThat(stale.current())
                                    .as("the revision read anew")
                                    .isNotEqualTo(shown.revision().value())
                                    .isEqualTo(store.refresh().revision().value());
                            assertThat(stale.changed()).isEqualTo("engine#2");
                        });
        assertThat(Files.readString(items.resolve("engine-2.md")))
                .as("the file placed is kept")
                .isEqualTo("written by hand");
    }

    @Test
    void itemFilesPastTheTotalBoundAreRefusedUnread() throws Exception {
        // Harm: a poll holds gigabytes of item files at once and stalls the planner.
        final LocalDirectoryStore store = store();
        issue(store);
        final byte[] full = new byte[LocalReader.MOST_BYTES];
        java.util.Arrays.fill(full, (byte) 'x');
        for (int number = 2; number <= 65; number++) {
            Files.write(items.resolve("engine-" + number + ".md"), full);
        }

        final Board read = store.refresh();
        assertThat(read.faults())
                .as("the bound is named, nothing further is read")
                .containsExactly("The item files hold more than 64 MiB together; none is read");
        assertThat(read.issues()).isEmpty();
    }

    @Test
    void eachReadAndWriteChecksTheDirectoryOnce() throws Exception {
        // Harm: each poll on Windows starts its checks twice, and a save three times.
        final HookedFiles hooked = new HookedFiles();
        final LocalDirectoryStore store = store(hooked);
        final NewIssue.Added added = issue(store);

        hooked.directoryChecks.set(0);
        store.refresh();
        assertThat(hooked.directoryChecks).as("a read").hasValue(1);
        hooked.directoryChecks.set(0);
        store.save(
                store.board().revision(),
                new Writes(Map.of(added.id(), "Backlog"), Map.of(), List.of()));
        assertThat(hooked.directoryChecks).as("a write").hasValue(1);
    }
}
