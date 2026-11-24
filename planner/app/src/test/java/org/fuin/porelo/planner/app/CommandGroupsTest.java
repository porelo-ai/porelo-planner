package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Version;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

/** A save split into one command per item, and what became of the group answered as today. */
class CommandGroupsTest {

    private static final Board BOARD =
            new Board(
                    new Revision("r1"),
                    new Workflow(
                            List.of("Backlog", "Review", "Finished"),
                            List.of(),
                            List.of("Finished")),
                    List.of(
                            new Story(
                                    "ST1",
                                    "A story",
                                    "Backlog",
                                    "High",
                                    null,
                                    List.of("AB01", "CD01"),
                                    "",
                                    null)),
                    List.of(issue("AB01", "porelo-store"), issue("CD01", "porelo-other")),
                    new Work(Map.of(), List.of()),
                    ProjectRepository.named(List.of("porelo-store", "porelo-other")));

    private static final PlanningStore STORE =
            new PlanningStore() {
                @Override
                public Board board() {
                    return BOARD;
                }

                @Override
                public Board refresh() {
                    return BOARD;
                }
            };

    private static final Writes.Reason REASON = new Writes.Reason("Finished", "It landed.");

    private static Issue issue(final String id, final String repository) {
        return new Issue(
                id,
                repository,
                "An issue",
                "Backlog",
                "S",
                List.of(),
                null,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY);
    }

    private static Version version(final String id) {
        return Version.of("seen", id);
    }

    private static CommandGroup closing() {
        return CommandGroups.of(
                new Writes(
                        Map.of("CD01", "Finished"),
                        Map.of("ST1", "Finished"),
                        Map.of("AB01", Worker.MANUAL),
                        Map.of(),
                        Map.of("CD01", REASON),
                        Map.of()),
                new Basis(
                        Map.of(
                                "AB01", version("AB01"),
                                "CD01", version("CD01"),
                                "ST1", version("ST1"),
                                "EF01", version("EF01"))),
                RECHECK);
    }

    private static final Recheck RECHECK =
            new Recheck(Set.of(Recheck.Need.STORY_ISSUES), Set.of("AB01"), dependencies -> {});

    private static Command.Write write(final Command command) {
        assertThat(command).isInstanceOf(Command.Write.class);
        return (Command.Write) command;
    }

    @Test
    void aSaveIsOneCommandPerItemTheIssuesBeforeTheirStory() {
        // Harm: a story closed before the issues it waits on, or an issue's part written with
        // another's.
        final List<Command> commands = closing().commands();

        assertThat(commands)
                .extracting(c -> write(c).target())
                .containsExactly("CD01", "AB01", "ST1");
        assertThat(write(commands.get(0)).writes().issues())
                .containsExactly(Map.entry("CD01", "Finished"));
        assertThat(write(commands.get(0)).writes().issueReasons())
                .containsExactly(Map.entry("CD01", REASON));
        assertThat(write(commands.get(1)).writes().workers())
                .containsExactly(Map.entry("AB01", Worker.MANUAL));
        assertThat(write(commands.get(2)).writes().stories())
                .containsExactly(Map.entry("ST1", "Finished"));
    }

    @Test
    void eachCommandIsJudgedOnTheVersionSeenOfItsOwnItemAlone() {
        // Harm: a command held by a version of an item it does not write.
        assertThat(closing().commands())
                .extracting(c -> c.basis().items())
                .containsExactly(
                        Map.of("CD01", version("CD01")),
                        Map.of("AB01", version("AB01")),
                        Map.of("ST1", version("ST1")));
    }

    @Test
    void onlyTheFirstCommandRechecksTheReadSet() {
        // Harm: a later command's recheck judging the group's own earlier writes and refusing it
        // midway, as a reason on an issue already closed is refused.
        final List<Command> commands = closing().commands();

        assertThat(commands.get(0).recheck()).isSameAs(RECHECK);
        assertThat(commands.subList(1, 3))
                .allSatisfy(
                        c -> {
                            assertThat(c.recheck().needs()).isEmpty();
                            assertThat(c.recheck().from()).isEmpty();
                        });
    }

    @Test
    void aReasonSentAgainAloneIsACommandOfItsIssue() {
        // Harm: a reason whose comment failed never written when the same move is sent again.
        final CommandGroup group =
                CommandGroups.of(
                        new Writes(
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of("AB01", REASON),
                                Map.of()),
                        new Basis(Map.of("AB01", version("AB01"))),
                        Recheck.none());

        assertThat(group.commands()).extracting(c -> write(c).target()).containsExactly("AB01");
    }

    @Test
    void aGroupWrittenWholeAnswersTheBoardAfterIt() {
        assertThat(CommandGroups.saved(new CommandOutcome.Done(BOARD, Map.of()), STORE))
                .isSameAs(BOARD);
    }

    @Test
    void aRefusalBeforeAnyWriteIsTheStoresOwnSoTheAnswerStaysTodays() {
        // Harm: the window told something was saved when nothing was, or a store's own partial
        // save losing the repositories it names.
        final List<Command> commands = closing().commands();
        final StoreException.Changed changed = new StoreException.Changed(List.of("CD01"));
        final StoreException.PartlySaved partly =
                new StoreException.PartlySaved("porelo-store", List.of("porelo-other"), "refused");

        assertThatThrownBy(
                        () ->
                                CommandGroups.saved(
                                        new CommandOutcome.Refused(
                                                commands.get(0),
                                                changed,
                                                List.of(),
                                                commands.subList(1, 3)),
                                        STORE))
                .isSameAs(changed);
        assertThatThrownBy(
                        () ->
                                CommandGroups.saved(
                                        new CommandOutcome.Refused(
                                                commands.get(0),
                                                partly,
                                                List.of(),
                                                commands.subList(1, 3)),
                                        STORE))
                .isSameAs(partly);
    }

    @Test
    void aRefusalAfterACommandWasWrittenIsAPartlySavedGroupNamingItsRepositories() {
        // Harm: a partly written group told as refused whole, or as saved whole.
        final List<Command> commands = closing().commands();

        assertThatThrownBy(
                        () ->
                                CommandGroups.saved(
                                        new CommandOutcome.Refused(
                                                commands.get(1),
                                                new StoreException.Changed(List.of("AB01")),
                                                commands.subList(0, 1),
                                                commands.subList(2, 3)),
                                        STORE))
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        e -> {
                            assertThat(e.subject()).isEqualTo("porelo-store");
                            assertThat(e.pushed()).containsExactly("porelo-other");
                        });
    }

    @Test
    void anErrorIsThrownAsItselfAndACheckedCauseKeptUnderAnUncheckedOne() {
        // Harm: a refusal the answer cannot be made from, or one whose cause is lost.
        final List<Command> commands = closing().commands();
        final AssertionError error = new AssertionError("broken");
        final Exception checked = new java.io.IOException("unread");

        assertThatThrownBy(
                        () ->
                                CommandGroups.saved(
                                        new CommandOutcome.Refused(
                                                commands.get(0),
                                                error,
                                                false,
                                                List.of(),
                                                commands.subList(1, 3)),
                                        STORE))
                .isSameAs(error);
        assertThatThrownBy(
                        () ->
                                CommandGroups.saved(
                                        new CommandOutcome.Refused(
                                                commands.get(0),
                                                checked,
                                                false,
                                                List.of(),
                                                commands.subList(1, 3)),
                                        STORE))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(checked);
    }

    @Test
    void aGroupWrittenWithoutABoardAnswersTheBoardTheStoreHolds() {
        // Harm: a group written whole answered as failed because its last command gave no board.
        assertThat(CommandGroups.saved(new CommandOutcome.Done(null, Map.of()), STORE))
                .isSameAs(BOARD);
    }

    @Test
    void aGroupHeldUnsentIsBusy() {
        // Harm: a move nothing wrote answered as saved.
        assertThatThrownBy(
                        () ->
                                CommandGroups.saved(
                                        new CommandOutcome.Held(
                                                List.of(), closing().commands(), "held"),
                                        STORE))
                .isInstanceOf(StoreException.Busy.class);
    }
}
