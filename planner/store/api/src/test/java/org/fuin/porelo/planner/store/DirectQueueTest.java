package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A command group written through the store's own calls, one per command, in order. */
class DirectQueueTest {

    private static final Basis SEEN = new Basis(Map.of("AA1", Version.of("v1")));

    private static Board boardAt(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea"), List.of(), List.of()),
                List.of(),
                List.of(),
                new Work(Map.of(), List.of()));
    }

    /** A store that records each call and its arguments, and can refuse one call. */
    private static final class Recording implements PlanningStore {
        final List<String> calls = new ArrayList<>();
        final List<Object> given = new ArrayList<>();
        int refuseAt = -1;
        @Nullable Throwable refusal;
        @Nullable RuntimeException boardFails;

        private Board called(final String call, final Object... arguments) {
            final int index = calls.size();
            calls.add(call);
            given.add(List.of(arguments));
            if (index == refuseAt && refusal != null) {
                if (refusal instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) refusal;
            }
            return boardAt("after-" + index);
        }

        @Override
        public Board board() {
            if (boardFails != null) {
                throw boardFails;
            }
            return boardAt("held");
        }

        @Override
        public Board setText(
                final Basis basis, final String id, final TextEdit edit, final Recheck recheck) {
            return called("setText", basis, id, edit, recheck);
        }

        @Override
        public Board setBlockedBy(
                final Basis basis,
                final String issue,
                final List<String> blockedBy,
                final Recheck recheck) {
            return called("setBlockedBy", basis, issue, blockedBy, recheck);
        }

        @Override
        public Board setStoryIssues(
                final Basis basis,
                final String story,
                final List<String> issues,
                final Recheck recheck) {
            return called("setStoryIssues", basis, story, issues, recheck);
        }

        @Override
        public NewIdea.Added createIdea(
                final Basis basis, final NewIdea idea, final Recheck recheck) {
            final Board board = called("createIdea", basis, idea, recheck);
            return new NewIdea.Added("ST7", List.of("AA7", "AB7"), board);
        }

        @Override
        public NewIdea.Added createStoryFromIssues(
                final Basis basis, final StoryFromIssues story, final Recheck recheck) {
            final Board board = called("createStoryFromIssues", basis, story, recheck);
            return new NewIdea.Added("ST8", story.existing(), board);
        }

        @Override
        public Board addUnlistedIssue(
                final Basis basis, final String issue, final Recheck recheck) {
            return called("addUnlistedIssue", basis, issue, recheck);
        }

        @Override
        public Board refresh() {
            return boardAt("read");
        }

        @Override
        public Board save(final Basis basis, final Writes writes, final Recheck recheck) {
            return called("save", basis, writes, recheck);
        }

        @Override
        public Board setFields(
                final Basis basis, final String issue, final Fields fields, final Recheck recheck) {
            return called("setFields", basis, issue, fields, recheck);
        }

        @Override
        public Board correctLabels(
                final String repository, final List<RepositoryWarning.Call> calls) {
            return called("correctLabels", repository, calls);
        }

        @Override
        public void comment(final String issue, final String text) {
            called("comment", issue, text);
        }

        @Override
        public NewIssue.Added createIssue(
                final Basis basis, final NewIssue issue, final Recheck recheck) {
            final Board board = called("createIssue", basis, issue, recheck);
            return new NewIssue.Added("AA9", board);
        }
    }

    private static Command.Write move(final String id, final String issue, final String state) {
        return new Command.Write(
                id,
                issue,
                new Writes(Map.of(issue, state), Map.of()),
                SEEN.with(issue, Version.of("v-" + issue)),
                Recheck.none());
    }

    @Test
    void aGroupIsWrittenThroughTheStoresOwnCallsInOrderAndDoneWithTheLastBoard() {
        // Harm: a group's commands written out of order, through other calls than today's, or told
        // done with a board from before its last write.
        final Recording store = new Recording();
        final Recheck rule = Recheck.none();
        final Fields size = new Fields(Set.of(Fields.Field.SIZE), "M", null, null, null);
        final NewIssue issue =
                new NewIssue(
                        "porelo-lib",
                        "A new one",
                        "It works.",
                        null,
                        Worker.AGENT,
                        null,
                        null,
                        null,
                        null);
        final List<RepositoryWarning.Call> labels =
                List.of(
                        new RepositoryWarning.Call(
                                RepositoryWarning.Call.Action.DELETE, "bug", null, null));
        final CommandGroup group =
                new CommandGroup(
                        "g1",
                        List.of(
                                move("c1", "AA1", "Building"),
                                new Command.SetFields("c2", "AA1", size, SEEN, rule),
                                new Command.Comment("c3", "AA1", "Why it moved."),
                                new Command.CreateIssue("c4", issue, SEEN, rule),
                                new Command.CorrectLabels("c5", "porelo-lib", labels)));

        final CommandOutcome outcome = new DirectQueue(store).submit(group);

        assertThat(store.calls)
                .as("one call of today's per command, in order")
                .containsExactly("save", "setFields", "comment", "createIssue", "correctLabels");
        assertThat(store.given.get(0))
                .as("the write's own basis, writes and recheck")
                .isEqualTo(
                        List.of(
                                SEEN.with("AA1", Version.of("v-AA1")),
                                new Writes(Map.of("AA1", "Building"), Map.of()),
                                Recheck.none()));
        assertThat(store.given.get(1)).as("the fields").isEqualTo(List.of(SEEN, "AA1", size, rule));
        assertThat(outcome)
                .as("done")
                .isInstanceOfSatisfying(
                        CommandOutcome.Done.class,
                        done -> {
                            assertThat(done.board().revision().value())
                                    .as("the board after the last call")
                                    .isEqualTo("after-4");
                            assertThat(done.created())
                                    .as("what the create made")
                                    .isEqualTo(
                                            Map.of(
                                                    "c4",
                                                    new CommandOutcome.Created("AA9", List.of())));
                        });
    }

    @Test
    void aRefusalMidwayNamesTheRefusedCommandAndLeavesTheRestUnsent() {
        // Harm: a group written in part without saying which issues stand.
        final Recording store = new Recording();
        final StoreException refusal = new StoreException.Changed(List.of("AB2"));
        store.refuseAt = 1;
        store.refusal = refusal;
        final Command first = move("c1", "AA1", "Building");
        final Command second = new Command.Comment("c2", "AB2", "Why.");
        final Command third = move("c3", "AC3", "Building");

        final CommandOutcome outcome =
                new DirectQueue(store)
                        .submit(new CommandGroup("g1", List.of(first, second, third)));

        assertThat(outcome)
                .as("refused")
                .isInstanceOfSatisfying(
                        CommandOutcome.Refused.class,
                        refused -> {
                            assertThat(refused.refused()).as("the command named").isEqualTo(second);
                            assertThat(refused.cause()).as("the store's refusal").isSameAs(refusal);
                            assertThat(refused.done()).as("written").containsExactly(first);
                            assertThat(refused.unsent()).as("unsent").containsExactly(third);
                        });
        assertThat(store.calls).as("nothing sent after the refusal").hasSize(2);
    }

    @Test
    void aRefusalOfTheFirstCommandWritesNothing() {
        // Harm: a group told as written in part when its first command was refused.
        final Recording store = new Recording();
        store.refuseAt = 0;
        store.refusal = new StoreException.NotSavable("AA1", "failed");
        final Command first = move("c1", "AA1", "Building");
        final Command second = new Command.Comment("c2", "AB2", "Why.");

        final CommandOutcome outcome =
                new DirectQueue(store).submit(new CommandGroup("g1", List.of(first, second)));

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Refused.class,
                        refused -> {
                            assertThat(refused.done()).as("nothing written").isEmpty();
                            assertThat(refused.unsent()).as("unsent").containsExactly(second);
                        });
        assertThat(store.calls).as("one call").hasSize(1);
    }

    @Test
    void aGroupOfCommentsAloneIsDoneWithTheBoardHeld() {
        // Harm: a group whose calls answer no board told done with none.
        final Recording store = new Recording();

        final CommandOutcome outcome =
                new DirectQueue(store)
                        .submit(
                                new CommandGroup(
                                        "g1", List.of(new Command.Comment("c1", "AA1", "Seen."))));

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Done.class,
                        done ->
                                assertThat(done.board().revision().value())
                                        .as("the board the store holds")
                                        .isEqualTo("held"));
    }

    @Test
    void aWriteCommandWritesItsTargetAlone() {
        // Harm: one command writing several issues, so a refusal of it hides which issues stand.
        assertThatThrownBy(
                        () ->
                                new Command.Write(
                                        "c1",
                                        "AA1",
                                        new Writes(
                                                Map.of("AA1", "Building", "AB2", "Building"),
                                                Map.of()),
                                        SEEN,
                                        Recheck.none()))
                .as("two issues in one command")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Command.Write(
                                        "c1",
                                        "AA1",
                                        new Writes(Map.of("AB2", "Building"), Map.of()),
                                        SEEN,
                                        Recheck.none()))
                .as("another issue than its target")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGroupNamesEachCommandOnce() {
        // Harm: a refusal naming a command id two commands share, so the wrong one is held.
        assertThatThrownBy(
                        () ->
                                new CommandGroup(
                                        "g1",
                                        List.of(
                                                move("c1", "AA1", "Building"),
                                                move("c1", "AB2", "Building"))))
                .as("one id twice")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandGroup("g1", List.of()))
                .as("no command")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOtherCommandsGoThroughTheirOwnCallsWithTheirArgumentsAndTellWhatTheyMade() {
        // Harm: a command written through the wrong call or with another's arguments, or a create
        // whose new items are not told, so the window cannot name them.
        final Recording store = new Recording();
        final Recheck rule = Recheck.none();
        final TextEdit text =
                new TextEdit(
                        TextEdit.Kind.ISSUE,
                        "A title",
                        Map.of(TextEdit.Part.PLAN, "The plan."),
                        Set.of());
        final NewIdea idea = new NewIdea("An idea", "A gain", "A test", "Medium", List.of());
        final StoryFromIssues story =
                new StoryFromIssues("A story", "A gain", "A test", "High", List.of("AA1", "AB2"));
        final CommandGroup group =
                new CommandGroup(
                        "g1",
                        List.of(
                                new Command.SetText("c1", "AA1", text, SEEN, rule),
                                new Command.SetBlockers("c2", "AA1", List.of("AB2"), SEEN, rule),
                                new Command.SetStoryIssues(
                                        "c3", "ST1", List.of("AA1", "AB2"), SEEN, rule),
                                new Command.CreateIdea("c4", idea, SEEN, rule),
                                new Command.CreateStory("c5", story, SEEN, rule),
                                new Command.AddUnlisted("c6", "AC3", SEEN, rule)));

        final CommandOutcome outcome = new DirectQueue(store).submit(group);

        assertThat(store.calls)
                .as("one own call per command, in order")
                .containsExactly(
                        "setText",
                        "setBlockedBy",
                        "setStoryIssues",
                        "createIdea",
                        "createStoryFromIssues",
                        "addUnlistedIssue");
        assertThat(store.given)
                .as("each call's own arguments")
                .containsExactly(
                        List.of(SEEN, "AA1", text, rule),
                        List.of(SEEN, "AA1", List.of("AB2"), rule),
                        List.of(SEEN, "ST1", List.of("AA1", "AB2"), rule),
                        List.of(SEEN, idea, rule),
                        List.of(SEEN, story, rule),
                        List.of(SEEN, "AC3", rule));
        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Done.class,
                        done -> {
                            assertThat(done.board())
                                    .as("the board after the last call")
                                    .isNotNull();
                            assertThat(done.board().revision().value()).isEqualTo("after-5");
                            assertThat(done.created())
                                    .as("what each create made, by its command")
                                    .isEqualTo(
                                            Map.of(
                                                    "c4",
                                                    new CommandOutcome.Created(
                                                            "ST7", List.of("AA7", "AB7")),
                                                    "c5",
                                                    new CommandOutcome.Created(
                                                            "ST8", List.of("AA1", "AB2")),
                                                    "c6",
                                                    new CommandOutcome.Created("AC3", List.of())));
                        });
    }

    @Test
    void blankCommandAndGroupIdsAreRefused() {
        // Harm: a command or a group with no name, which an outcome or a refusal cannot point at.
        assertThatThrownBy(() -> move(" ", "AA1", "Building"))
                .as("a blank command id")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandGroup(" ", List.of(move("c1", "AA1", "Building"))))
                .as("a blank group id")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anErrorFromAStoreCallEndsTheGroupRefusedAndMayHaveWritten() {
        // Harm: an Error escaping with earlier commands written and no outcome told, or the
        // command it hit told as having written nothing.
        final Recording store = new Recording();
        final Error error = new StackOverflowError("deep");
        store.refuseAt = 1;
        store.refusal = error;
        final Command first = move("c1", "AA1", "Building");
        final Command second = new Command.Comment("c2", "AB2", "Why.");
        final Command third = move("c3", "AC3", "Building");

        final CommandOutcome outcome =
                new DirectQueue(store)
                        .submit(new CommandGroup("g1", List.of(first, second, third)));

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Refused.class,
                        refused -> {
                            assertThat(refused.refused()).isEqualTo(second);
                            assertThat(refused.cause()).as("the Error kept").isSameAs(error);
                            assertThat(refused.mayHaveWritten()).as("may have written").isTrue();
                            assertThat(refused.done()).containsExactly(first);
                            assertThat(refused.unsent()).containsExactly(third);
                        });
    }

    @Test
    void onlyARefusalBeforeWritingSaysTheCommandWroteNothing() {
        // Harm: a command that wrote in part told as unwritten, so it is sent again and written
        // twice; or one refused before writing held for the owner account without need.
        final List<RuntimeException> before =
                List.of(
                        new StoreException.Changed(List.of("AA1")),
                        new StoreException.BasisIncomplete(List.of("AA1")),
                        new StoreException.StaleRevision("r2"),
                        new StoreException.NotSavable("AA1", "failed"),
                        new StoreException.NoForgeAccess("https://github.com", "issues"));
        final List<RuntimeException> maybe =
                List.of(
                        new StoreException.PartlySaved("porelo-lib", List.of(), "failed"),
                        new StoreException.Unreachable("porelo-lib", "timed out"),
                        new StoreException.Busy("porelo-lib"),
                        new IllegalStateException("failed after the write"));
        for (final RuntimeException cause : before) {
            assertThat(refusedBy(cause).mayHaveWritten()).as(cause.toString()).isFalse();
        }
        for (final RuntimeException cause : maybe) {
            assertThat(refusedBy(cause).mayHaveWritten()).as(cause.toString()).isTrue();
        }
    }

    private static CommandOutcome.Refused refusedBy(final RuntimeException cause) {
        final Recording store = new Recording();
        store.refuseAt = 0;
        store.refusal = cause;
        return (CommandOutcome.Refused)
                new DirectQueue(store)
                        .submit(new CommandGroup("g1", List.of(move("c1", "AA1", "Building"))));
    }

    @Test
    void aFailedLastReadStillTellsTheGroupDoneWithNoBoard() {
        // Harm: every command written but the caller told of a failure, so the group is sent again
        // and written twice.
        final Recording store = new Recording();
        store.boardFails = new StoreException.Unreachable("porelo-lib", "timed out");

        final CommandOutcome outcome =
                new DirectQueue(store)
                        .submit(
                                new CommandGroup(
                                        "g1", List.of(new Command.Comment("c1", "AA1", "Seen."))));

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Done.class,
                        done -> assertThat(done.board()).as("no board: read again").isNull());
        assertThat(store.calls).as("the comment written once").containsExactly("comment");
    }

    @Test
    void consecutiveWritesMakeOneSaveWithTheFirstRecheck() {
        // Harm: one window move made as several saves, losing today's all or none, or the read set
        // judged by another command's rule than the first's.
        final Recording store = new Recording();
        final Recheck first =
                new Recheck(Set.of(Recheck.Need.BLOCKERS), Set.of("AA1"), dependencies -> {});
        final Command.Write one =
                new Command.Write(
                        "c1",
                        "AA1",
                        new Writes(Map.of("AA1", "Building"), Map.of()),
                        new Basis(Map.of("AA1", Version.of("v1"))),
                        first);
        final Command.Write two =
                new Command.Write(
                        "c2",
                        "AB2",
                        new Writes(Map.of("AB2", "Building"), Map.of()),
                        new Basis(Map.of("AB2", Version.of("v2"))),
                        Recheck.none());
        final Command.Write three =
                new Command.Write(
                        "c3",
                        "ST3",
                        new Writes(Map.of(), Map.of("ST3", "Building")),
                        new Basis(Map.of("ST3", Version.of("v3"))),
                        Recheck.none());

        final CommandOutcome outcome =
                new DirectQueue(store).submit(new CommandGroup("g1", List.of(one, two, three)));

        assertThat(store.calls).as("one save").containsExactly("save");
        assertThat(store.given.getFirst())
                .as("the joined basis, the joined writes and the first recheck")
                .isEqualTo(
                        List.of(
                                new Basis(
                                        Map.of(
                                                "AA1", Version.of("v1"),
                                                "AB2", Version.of("v2"),
                                                "ST3", Version.of("v3"))),
                                new Writes(
                                        Map.of("AA1", "Building", "AB2", "Building"),
                                        Map.of("ST3", "Building")),
                                first));
        assertThat(outcome).isInstanceOf(CommandOutcome.Done.class);
    }

    @Test
    void aCommentBetweenWritesSplitsThemIntoTwoSavesAroundIt() {
        // Harm: a comment told before the move it explains, or after a later one.
        final Recording store = new Recording();
        final CommandOutcome outcome =
                new DirectQueue(store)
                        .submit(
                                new CommandGroup(
                                        "g1",
                                        List.of(
                                                move("c1", "AA1", "Building"),
                                                new Command.Comment("c2", "AA1", "Why."),
                                                move("c3", "AB2", "Building"))));

        assertThat(store.calls).as("in order").containsExactly("save", "comment", "save");
        assertThat(outcome).isInstanceOf(CommandOutcome.Done.class);
    }

    @Test
    void aRefusedFoldedSaveNamesItsFirstCommandAndCountsNoneOfItsRunDone() {
        // Harm: a run told written in part when its one save was refused whole, or a partly saved
        // run told as having written nothing.
        final Recording store = new Recording();
        store.refuseAt = 0;
        store.refusal = new StoreException.PartlySaved("porelo-lib", List.of(), "failed");
        final Command first = move("c1", "AA1", "Building");
        final Command second = move("c2", "AB2", "Building");
        final Command third = new Command.Comment("c3", "AB2", "Why.");

        final CommandOutcome outcome =
                new DirectQueue(store)
                        .submit(new CommandGroup("g1", List.of(first, second, third)));

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        CommandOutcome.Refused.class,
                        refused -> {
                            assertThat(refused.refused()).isEqualTo(first);
                            assertThat(refused.mayHaveWritten()).isTrue();
                            assertThat(refused.done()).as("none of the run").isEmpty();
                            assertThat(refused.unsent()).containsExactly(second, third);
                        });
    }
}
