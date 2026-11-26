package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.planner.app.answers.HeldChange;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a held command would change, field by field, beside the value read last. */
class HeldChangesTest {

    private static final Instant READ = Instant.parse("2026-05-06T07:08:09Z");

    private static final Optional<Instant> AT = Optional.of(READ);

    @TempDir Path directory;

    private static Issue issue(
            final String id, final @org.jspecify.annotations.Nullable String size) {
        return new Issue(
                id,
                "porelo-lib",
                "Title of " + id,
                "Ready",
                size,
                List.of("AB2"),
                null,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    private static final Board BOARD =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Ready", "Building", "Done"), List.of(), List.of()),
                    List.of(
                            new Story(
                                    "ST1",
                                    "A story",
                                    "Ready",
                                    "High",
                                    null,
                                    List.of("AA1"),
                                    "",
                                    null)),
                    List.of(issue("AA1", null), issue("AB2", "S")),
                    new Work(Map.of(), List.of()));

    /** The command as the outbox tells it after keeping it, its changes as data. */
    private CommandOutbox.Told told(final Command command) {
        final Path own = directory.resolve(java.util.UUID.randomUUID().toString());
        try {
            java.nio.file.Files.createDirectory(own);
        } catch (final java.io.IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", own.toString());
        final CommandOutbox outbox =
                new CommandOutbox(
                        environment,
                        own,
                        "planning",
                        "porelo-lib",
                        "main",
                        PrivateFiles.of(environment),
                        LimitFactors.DEFAULT);
        outbox.open(() -> BOARD);
        outbox.enqueue(new CommandGroup("g1", List.of(command)));
        final CommandOutbox.Told told = outbox.told("g1").getFirst();
        outbox.close();
        return told;
    }

    private static HeldChange change(
            final String target,
            final String field,
            final @org.jspecify.annotations.Nullable String now,
            final @org.jspecify.annotations.Nullable Boolean unset,
            final @org.jspecify.annotations.Nullable String at,
            final @org.jspecify.annotations.Nullable String saved) {
        return new HeldChange(target, field, now, unset, at, saved);
    }

    @Test
    void aMoveTellsEachFieldWithTheValueReadAndWhenItWasRead() {
        // Harm: the person resends a move without seeing what it overwrites on GitHub.
        final Command.Write issue =
                new Command.Write(
                        "c1",
                        "AA1",
                        new Writes(
                                Map.of("AA1", "Building"),
                                Map.of(),
                                Map.of("AA1", Worker.MANUAL),
                                Map.of(),
                                Map.of("AA1", new Writes.Reason("Building", "Started by hand.")),
                                Map.of()),
                        Basis.of(BOARD),
                        Recheck.none());
        final Command.Write story =
                new Command.Write(
                        "c2",
                        "ST1",
                        new Writes(
                                Map.of(),
                                Map.of("ST1", "Building"),
                                Map.of(),
                                Map.of("ST1", "Low"),
                                Map.of(),
                                Map.of()),
                        Basis.of(BOARD),
                        Recheck.none());

        assertThat(HeldChanges.of(told(issue), BOARD, AT))
                .containsExactly(
                        change("AA1", "state", "Ready", null, READ.toString(), "Building"),
                        change("AA1", "worker", "AGENT", null, READ.toString(), "MANUAL"),
                        change("AA1", "reason", null, null, null, "Started by hand."));
        assertThat(HeldChanges.of(told(story), BOARD, AT))
                .containsExactly(
                        change("ST1", "state", "Ready", null, READ.toString(), "Building"),
                        change("ST1", "priority", "High", null, READ.toString(), "Low"));
    }

    @Test
    void aValueIsReadSetReadEmptyOrNeverRead() {
        // Harm: a value never read shown as GitHub's, or as read and empty.
        final Command.SetFields sizes =
                new Command.SetFields(
                        "c1",
                        "AA1",
                        new Fields(EnumSet.of(Fields.Field.SIZE), "M", null, null, null),
                        Basis.of(BOARD),
                        Recheck.none());
        final Command.SetFields other =
                new Command.SetFields(
                        "c1",
                        "AZ9",
                        new Fields(EnumSet.of(Fields.Field.SIZE), "M", null, null, null),
                        Basis.of(BOARD),
                        Recheck.none());
        final Board withSize =
                new Board(
                        BOARD.revision(),
                        BOARD.workflow(),
                        BOARD.stories(),
                        List.of(issue("AA1", "S")),
                        BOARD.work());

        assertThat(HeldChanges.of(told(sizes), withSize, AT))
                .as("read")
                .containsExactly(change("AA1", "size", "S", null, READ.toString(), "M"));
        assertThat(HeldChanges.of(told(sizes), BOARD, AT))
                .as("read empty")
                .containsExactly(change("AA1", "size", null, true, READ.toString(), "M"));
        assertThat(HeldChanges.of(told(other), BOARD, AT))
                .as("an item the board does not hold")
                .containsExactly(change("AZ9", "size", null, null, null, "M"));
        assertThat(HeldChanges.of(told(sizes), withSize, Optional.empty()))
                .as("a board whose read has no time")
                .containsExactly(change("AA1", "size", null, null, null, "M"));
    }

    @Test
    void aValueTheBoardDoesNotHoldIsToldSavedOnlyAndARemovalWithoutIt() {
        // Harm: an item's text told with a value now the planner never read.
        final Command.SetText text =
                new Command.SetText(
                        "c1",
                        "AA1",
                        new TextEdit(
                                TextEdit.Kind.ISSUE,
                                "New title",
                                Map.of(TextEdit.Part.WHAT_MUST_BE_TRUE, "It holds."),
                                Set.of(TextEdit.Part.PLAN)),
                        Basis.of(BOARD),
                        Recheck.none());

        assertThat(HeldChanges.of(told(text), BOARD, AT))
                .containsExactly(
                        change("AA1", "title", "Title of AA1", null, READ.toString(), "New title"),
                        change("AA1", "whatMustBeTrue", null, null, null, "It holds."),
                        change("AA1", "plan", null, null, null, null));
    }

    @Test
    void aListIsJoinedAndACommentChangesNoField() {
        // Harm: a blocker list told unreadably, or a comment told as a field it never changes.
        final Command.SetBlockers blockers =
                new Command.SetBlockers(
                        "c1", "AA1", List.of("AB2", "AC3"), Basis.of(BOARD), Recheck.none());

        assertThat(HeldChanges.of(told(blockers), BOARD, AT))
                .containsExactly(
                        change("AA1", "blockedBy", "AB2", null, READ.toString(), "AB2, AC3"));
        assertThat(HeldChanges.of(told(new Command.Comment("c1", "AA1", "Hello")), BOARD, AT))
                .isEmpty();
    }

    @Test
    void labelsAreToldAsTheTopicLabelsTheWriteSetsAndKeepsTheOthers() {
        // Harm: a person's own label, which a write of topic labels keeps, looks removed, so the
        // person drops a save that would have harmed nothing.
        final Board labelled =
                new Board(
                        BOARD.revision(),
                        BOARD.workflow(),
                        BOARD.stories(),
                        List.of(
                                new Issue(
                                        "AA1",
                                        "porelo-lib",
                                        "Title of AA1",
                                        "Ready",
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        "",
                                        Worker.AGENT,
                                        Risk.ORDINARY,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        List.of(),
                                        List.of("team-blue", "security"))),
                        BOARD.work());
        final Command.SetText unlabel =
                new Command.SetText(
                        "c1",
                        "AA1",
                        new TextEdit(
                                TextEdit.Kind.ISSUE,
                                null,
                                Map.of(TextEdit.Part.WHAT_MUST_BE_TRUE, "It holds."),
                                Set.of(),
                                List.of()),
                        Basis.of(labelled),
                        Recheck.none());

        assertThat(HeldChanges.of(told(unlabel), labelled, AT))
                .containsExactly(
                        change("AA1", "whatMustBeTrue", null, null, null, "It holds."),
                        change("AA1", "labels", "security", null, READ.toString(), null));
    }

    @Test
    void aStorysIssuesReadInPartHaveNoValueNow() {
        // Harm: a story's issues read in part shown as the whole list GitHub held, so an issue
        // the store left out looks as if the save added it.
        final Board part =
                new Board(
                        BOARD.revision(),
                        BOARD.workflow(),
                        List.of(
                                new Story(
                                        "ST1",
                                        "A story",
                                        "Ready",
                                        "High",
                                        null,
                                        List.of("AA1"),
                                        "",
                                        null,
                                        false)),
                        BOARD.issues(),
                        BOARD.work());
        final Command.SetStoryIssues issues =
                new Command.SetStoryIssues(
                        "c1", "ST1", List.of("AA1", "AB2"), Basis.of(part), Recheck.none());

        assertThat(HeldChanges.of(told(issues), part, AT))
                .containsExactly(change("ST1", "issues", null, null, null, "AA1, AB2"));
    }

    private static final String SAVE = "5a0c2d1e-3b4f-4a6e-8c7d-9e0f1a2b3c4d";

    private static List<org.fuin.porelo.planner.app.answers.HeldSave> answers(
            final CommandOutbox.Group group) {
        return HeldSavesController.answers(
                "alpha",
                new HeldSaves() {
                    @Override
                    public List<CommandOutbox.Group> groups() {
                        return List.of(group);
                    }

                    @Override
                    public org.fuin.porelo.planner.store.CommandOutcome resend(final String id) {
                        throw new IllegalStateException(id);
                    }

                    @Override
                    public void drop(final String id) {
                        throw new IllegalStateException(id);
                    }

                    @Override
                    public org.fuin.porelo.planner.core.RefusedEventResave runs() {
                        return HeldSaves.NO_RUNS;
                    }
                },
                BOARD,
                AT);
    }

    private CommandOutbox.Told refused(final String subject) {
        final Command.Write move =
                new Command.Write(
                        "c1",
                        "AA1",
                        new Writes(
                                Map.of("AA1", "Building"),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of()),
                        Basis.of(BOARD),
                        Recheck.none());
        return new CommandOutbox.Told(
                SAVE + "/0",
                "write",
                "AA1",
                CommandOutbox.State.REFUSED,
                new org.fuin.porelo.planner.core.WorkRun.SaveFailure("Changed", subject, List.of()),
                List.of(),
                told(move).changes());
    }

    @Test
    void aSaveKeptFromAnotherSourceIsToldWithoutTheShownBoardsValues() {
        // Harm: a save meant for another repository or branch shows this board's value of an item
        // of the same id as the value it would overwrite, which it never would.
        final CommandOutbox.Source elsewhere =
                new CommandOutbox.Source("https://example.org/other.git", "");

        assertThat(
                        answers(
                                        new CommandOutbox.Group(
                                                elsewhere, SAVE, List.of(refused("AA1")), true, ""))
                                .getFirst()
                                .changes())
                .containsExactly(change("AA1", "state", null, null, null, "Building"));
    }

    @Test
    void aReasonAndItsSubjectStayWithinTheirBoundsInUtf16() {
        // Harm: a reason within 512 characters as code points but past 512 as UTF-16 units, which
        // a reader counting those refuses, and with it the whole board.
        final String wide = "\uD835\uDC00".repeat(256);
        final org.fuin.porelo.planner.app.answers.HeldSave save =
                answers(
                                new CommandOutbox.Group(
                                        new CommandOutbox.Source("porelo-lib", ""),
                                        SAVE,
                                        List.of(refused(wide)),
                                        false,
                                        ""))
                        .getFirst();

        assertThat(save.reason().length())
                .as("the reason's UTF-16 length")
                .isLessThanOrEqualTo(512);
        assertThat(save.commands().getFirst().subject().length())
                .as("the subject's UTF-16 length")
                .isLessThanOrEqualTo(256);
    }
}
