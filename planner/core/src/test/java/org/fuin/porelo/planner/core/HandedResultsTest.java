package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HandedResultsTest {

    private static final String WORKING = "In analysis";

    private static final String DONE = "Analyzed";

    // Prevents a forged work record from answering the window, or writing a status line, with a
    // task name or words the planner would never have recorded.
    @Test
    void aRecordedOutcomeWithAForgedTaskOrHiddenCharactersIsRefusedWhenRead() {
        final String offer = "00000000-0000-0000-0000-000000000009";
        HandedResults.validate(
                offer,
                new HandedResults.Handed(
                        HandedResults.REFUSED,
                        "analyze",
                        List.of("AA1"),
                        List.of("plan: x"),
                        "",
                        1,
                        ""));
        final List<HandedResults.Handed> forged =
                List.of(
                        new HandedResults.Handed(
                                HandedResults.SAVED,
                                "bad task\n",
                                List.of("AA1"),
                                List.of(),
                                "",
                                1,
                                ""),
                        new HandedResults.Handed(
                                HandedResults.MISSING,
                                "analyze",
                                List.of("AA1"),
                                List.of(),
                                "gone\u202e",
                                1,
                                ""),
                        new HandedResults.Handed(
                                HandedResults.REFUSED,
                                "analyze",
                                List.of("AA1"),
                                List.of("plan: bad\nline"),
                                "",
                                1,
                                ""));
        for (final HandedResults.Handed handed : forged) {
            assertThat(
                            catchThrowableOfType(
                                    IllegalArgumentException.class,
                                    () -> HandedResults.validate(offer, handed)))
                    .as("the forged outcome %s", handed)
                    .isNotNull();
        }
    }

    @Test
    void aDoneEndOfAnAnalysisWithoutAResultLeavesTheIssueInItsWorkingState() {
        // Prevents an issue counted analyzed whose store holds no plan.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();

        f.end(offer);

        assertThat(f.store.state("AA1"))
                .as("an end without a result moves nothing")
                .isEqualTo(WORKING);
        assertThat(f.reason("AA1"))
                .as("the issue asks the person, who may start the stage again")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
        assertThat(f.told)
                .as("the one line why")
                .containsExactly(
                        "Issue AA1 of story ST1: its task ended without handing in a result");
        assertThat(f.store.results).as("nothing is saved as a result").isEmpty();
    }

    @Test
    void aValidHandInSavesOnceAndTheIssueStandsInItsDoneState() {
        // Prevents an issue counted analyzed whose store holds no plan, and one moved twice.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();

        final HandedResults.Answer answer = f.handIn(offer, valid());

        assertThat(answer.outcome()).as("the outcome").isEqualTo(HandedResults.SAVED);
        assertThat(f.store.results)
                .as("one save of the result, from the working state to the rule's done")
                .singleElement()
                .satisfies(
                        result ->
                                assertThat(result.issues())
                                        .as("the saved issue")
                                        .singleElement()
                                        .satisfies(
                                                issue -> {
                                                    assertThat(issue.from())
                                                            .as("from")
                                                            .isEqualTo(WORKING);
                                                    assertThat(issue.to()).as("to").isEqualTo(DONE);
                                                    assertThat(issue.plan())
                                                            .as("the plan")
                                                            .isEqualTo(
                                                                    "Read the code.\nChange it.");
                                                }));
        assertThat(f.store.state("AA1")).as("the issue after the save").isEqualTo(DONE);

        f.end(offer);

        assertThat(f.store.state("AA1")).as("the end moves nothing more").isEqualTo(DONE);
        assertThat(f.store.saves).as("only the start was saved besides the result").isEqualTo(1);
        assertThat(f.reason("AA1")).as("the saved issue asks nobody").isEqualTo("NONE");
        assertThat(f.told).as("no line of why").isEmpty();
    }

    @Test
    void aPlansFirstAndSecondLevelHeadingsOutsideCodeBlocksAreSavedAsThirdLevelOnes() {
        // Prevents a whole analysis lost because its plan opens with a heading of its own.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        final String plan =
                String.join(
                        "\n",
                        "## AA1: audit the docs",
                        "### Steps",
                        "  # Top",
                        "##",
                        "```text",
                        "## kept in the block",
                        "```",
                        "#hashtag stays");

        final HandedResults.Answer answer =
                f.handIn(offer, hand(file(entry("AA1", plan, "M", "One module.", null))));

        assertThat(answer.outcome()).as("the outcome").isEqualTo(HandedResults.SAVED);
        assertThat(f.store.results)
                .as("the saved plan")
                .singleElement()
                .satisfies(
                        result ->
                                assertThat(result.issues().getFirst().plan())
                                        .as("the headings lowered, the code block kept")
                                        .isEqualTo(
                                                String.join(
                                                        "\n",
                                                        "### AA1: audit the docs",
                                                        "### Steps",
                                                        "### Top",
                                                        "###",
                                                        "```text",
                                                        "## kept in the block",
                                                        "```",
                                                        "#hashtag stays")));
        assertThat(f.store.state("AA1")).as("the issue after the save").isEqualTo(DONE);
    }

    @Test
    void anItemOverItsBoundIsSplitAtSentenceEndsAndALineBreakBecomesASpace() {
        // Prevents a whole stage's result lost for one long finding or a wrapped line.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        final String first = "a".repeat(298) + ".";
        final String second = "b".repeat(199) + "!";
        final String third = "c".repeat(22) + "?";
        final String long524 = first + " " + second + " " + third;
        assertThat(long524.length()).as("the item as long as the one refused live").isEqualTo(524);

        final HandedResults.Answer answer =
                f.handIn(
                        offer,
                        hand(
                                file(
                                        new HandedResults.Entry(
                                                "AA1",
                                                "Plan.",
                                                "M",
                                                "One module.",
                                                null,
                                                List.of("Read\nthe log.", "Short."),
                                                List.of(long524)))));

        assertThat(answer.outcome()).as("the outcome").isEqualTo(HandedResults.SAVED);
        final StageResult.ForIssue saved = f.store.results.getFirst().issues().getFirst();
        assertThat(saved.findings())
                .as("the long finding split at its sentence ends, each part within 500")
                .containsExactly(first + " " + second, third);
        assertThat(saved.toBeChecked())
                .as("the line break as a space")
                .containsExactly("Read the log.", "Short.");
    }

    @Test
    void aReplayOfASavedHandInWritesNothingAndAnswersTheSame() {
        // Prevents a result saved twice when the window hands it in again.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        f.handIn(offer, valid());
        final WorkRun.Snapshot record = f.record.value;

        final HandedResults.Answer again =
                f.handIn(offer, hand(file(entry("AA1", null, "XL", "Other.", null))));

        assertThat(again.outcome()).as("the replay's outcome").isEqualTo(HandedResults.SAVED);
        assertThat(again.issues()).as("the replay's issues").containsExactly("AA1");
        assertThat(f.store.results).as("the replay saves nothing").hasSize(1);
        assertThat(f.record.value).as("the replay writes no record").isEqualTo(record);
    }

    @Test
    void aSavedOutcomeAnswersAReplayAfterItsRunIsGone() {
        // Prevents a second save once a new order replaced the run that held the offer.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        f.handIn(offer, valid());
        final WorkRun.Snapshot record = f.record.value;
        f.record.value =
                new WorkRun.Snapshot(
                        record.generation(), Map.of(), record.ideas(), record.results());

        final HandedResults.Answer again = f.handIn(offer, valid());

        assertThat(again.outcome()).as("the replay's outcome").isEqualTo(HandedResults.SAVED);
        assertThat(f.store.results).as("the replay saves nothing").hasSize(1);
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                Arguments.of(
                        "the wrong kind",
                        new HandedResults.HandIn(
                                new HandedResults.File(
                                        StageOrder.FORMAT,
                                        "ESTIMATE",
                                        List.of(entry("AA1", "Plan.", "M", "Why.", null))),
                                null,
                                List.of()),
                        "kind: "),
                Arguments.of(
                        "another issue",
                        hand(file(entry("AA2", "Plan.", "M", "Why.", null))),
                        "issues[0].id: "),
                Arguments.of(
                        "no plan",
                        hand(file(entry("AA1", null, "M", "Why.", null))),
                        "issues[0].plan: "),
                Arguments.of(
                        "a size of no workflow",
                        hand(file(entry("AA1", "Plan.", "XXL", "Why.", null))),
                        "issues[0].size: "),
                Arguments.of(
                        "an estimate in an analysis",
                        hand(file(entry("AA1", "Plan.", "M", "Why.", 5L))),
                        "issues[0].estimateMinutes: "),
                Arguments.of(
                        "a direction mark",
                        hand(file(entry("AA1", "Plan.", "M", "Why‮.", null))),
                        "issues[0].sizeWhy: "),
                Arguments.of(
                        "a zero-width character in the plan",
                        hand(file(entry("AA1", "Pl​an.", "M", "Why.", null))),
                        "issues[0].plan: "),
                Arguments.of(
                        "a plan leaving a code block open after a heading",
                        hand(file(entry("AA1", "## Top\n```text\n## x", "M", "Why.", null))),
                        "issues: "),
                Arguments.of(
                        "a plan with a tilde fence around a heading",
                        hand(file(entry("AA1", "~~~\n## x\n~~~", "M", "Why.", null))),
                        "issues: "),
                Arguments.of(
                        "one sentence over an item's bound",
                        hand(
                                file(
                                        new HandedResults.Entry(
                                                "AA1",
                                                "Plan.",
                                                "M",
                                                "Why.",
                                                null,
                                                null,
                                                List.of("x".repeat(400) + " " + "y".repeat(200))))),
                        "issues[0].findings[0]: "),
                Arguments.of(
                        "a split past the most items",
                        hand(
                                file(
                                        new HandedResults.Entry(
                                                "AA1",
                                                "Plan.",
                                                "M",
                                                "Why.",
                                                null,
                                                Stream.concat(
                                                                Stream.of(
                                                                        "x".repeat(300)
                                                                                + ". "
                                                                                + "y".repeat(300)
                                                                                + "."),
                                                                Stream.generate(() -> "Item.")
                                                                        .limit(19))
                                                        .toList(),
                                                null))),
                        "issues[0].toBeChecked: "),
                Arguments.of(
                        "a plan too long",
                        hand(file(entry("AA1", "x".repeat(20_001), "M", "Why.", null))),
                        "issues[0].plan: "),
                Arguments.of(
                        "a member the format does not name",
                        new HandedResults.HandIn(
                                file(entry("AA1", "Plan.", "M", "Why.", null)),
                                null,
                                List.of(
                                        "issues[0]: It holds a member the result format does not name.")),
                        "issues[0]: "),
                Arguments.of(
                        "a missing reason of two lines",
                        new HandedResults.HandIn(null, "no\nfile", List.of()),
                        "missing: "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void aMalformedResultIsRefusedWritesNothingAndItsReplayAnswersTheSameRefusal(
            final String what, final HandedResults.HandIn handIn, final String field) {
        // Prevents an issue counted analyzed on a result the store cannot hold or the order
        // did not ask for.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();

        final WorkRefusal refused =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, handIn));

        assertThat(refused).as("%s is refused", what).isNotNull();
        assertThat(refused.name()).as("the refusal").isEqualTo("ResultRefused");
        assertThat(faults(refused))
                .as("the faults of %s", what)
                .anySatisfy(fault -> assertThat(fault).as("a fault").startsWith(field));
        assertThat(f.store.results).as("a refused result saves nothing").isEmpty();
        assertThat(f.store.state("AA1")).as("the issue stays").isEqualTo(WORKING);

        final WorkRefusal again =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, valid()));

        assertThat(again).as("the replay of %s is refused again", what).isNotNull();
        assertThat(faults(again)).as("the replay's faults").isEqualTo(faults(refused));
        assertThat(f.store.results).as("the replay saves nothing").isEmpty();

        f.end(offer);

        assertThat(f.store.state("AA1")).as("the end moves nothing").isEqualTo(WORKING);
        assertThat(f.reason("AA1"))
                .as("the issue asks the person")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
        assertThat(f.told)
                .as("the line says the result was refused, or why it is missing")
                .singleElement()
                .satisfies(
                        line ->
                                assertThat(line)
                                        .as("the line")
                                        .startsWith(
                                                "Issue AA1 of story ST1: its result was refused: "));
    }

    @Test
    void aMissingResultThenTheEndLeavesTheIssueInItsWorkingStateWithTheReason() {
        // Prevents an issue counted analyzed whose task left no result.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();

        final HandedResults.Answer answer =
                f.handIn(
                        offer, new HandedResults.HandIn(null, "The task left no file.", List.of()));
        f.end(offer);

        assertThat(answer.outcome()).as("the outcome").isEqualTo(HandedResults.MISSING);
        assertThat(f.store.state("AA1")).as("the issue stays").isEqualTo(WORKING);
        assertThat(f.store.results).as("nothing is saved").isEmpty();
        assertThat(f.reason("AA1"))
                .as("the issue asks the person")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
        assertThat(f.told)
                .as("the reason")
                .containsExactly(
                        "Issue AA1 of story ST1: its task left no result: The task left no file.");
    }

    @Test
    void anIssueNoLongerInItsWorkingStateIsNotSavedAndAsksThePerson() {
        // Prevents a result saved over an issue a person took over or moved meanwhile.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        f.store.change("AA1", issue -> withWorker(issue, Worker.MANUAL));

        final WorkRefusal refused =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, valid()));

        assertThat(refused).as("the hand-in is refused").isNotNull();
        assertThat(faults(refused))
                .as("the faults")
                .singleElement()
                .satisfies(fault -> assertThat(fault).as("the fault").startsWith("issues: "));
        assertThat(f.store.results).as("nothing is saved").isEmpty();
        assertThat(f.reason("AA1"))
                .as("the issue asks the person")
                .isEqualTo("NEXT_STEP_UNAVAILABLE");
        final WorkRefusal again =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, valid()));
        assertThat(faults(again)).as("the replay's faults").isEqualTo(faults(refused));
    }

    @Test
    void aResultForAnIssueHeldBackMeanwhileIsNotSavedAndSaysWhy() {
        // A result saved over an issue whose parent a hand edit took out of the project would
        // build on what the planner cannot see.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        f.store.change(
                "AA1",
                issue ->
                        issue.withWarnings(
                                List.of(org.fuin.porelo.planner.store.Warning.wrongParent())));

        final WorkRefusal refused =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, valid()));

        assertThat(refused).as("the hand-in is refused").isNotNull();
        assertThat(faults(refused))
                .as("the faults")
                .containsExactly(
                        "issues: AA1: "
                                + org.fuin.porelo.planner.store.Warning.wrongParent().text());
        assertThat(f.store.results).as("nothing is saved").isEmpty();
    }

    @Test
    void aStageTaskOverAnIssueHeldBackIsRefusedBeforeItIsGivenItsText() {
        // A task handed the text of an issue the store could not fully read works from it.
        final Stage f = new Stage();
        final WorkOrder.Task task = f.order().tasks().getFirst().task();
        final org.fuin.porelo.planner.store.Board board =
                HeldBoards.warned(
                        f.store.board(),
                        "AA1",
                        org.fuin.porelo.planner.store.Warning.blockerOutsideProject());

        assertThatThrownBy(
                        () ->
                                StageOrder.staged(
                                        board, board.work().rules().get(WORKING), WORKING, task))
                .as("the stage is refused")
                .isInstanceOf(WorkRefusal.class)
                .hasMessage(
                        "AA1: "
                                + org.fuin.porelo.planner.store.Warning.blockerOutsideProject()
                                        .text());
    }

    @Test
    void aFailedSaveIsRecordedAsARefusalThatAReplayAnswersWithoutTheStore() {
        // Prevents a result saved twice after a save whose outcome was not seen.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        f.store.failure = new StoreException.Unreadable("alpha", "the push was not answered");

        final WorkRefusal refused =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, valid()));
        f.store.failure = null;
        final WorkRefusal again =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, valid()));

        assertThat(refused).as("the failed save is refused").isNotNull();
        assertThat(refused.name()).as("the refusal").isEqualTo("ResultRefused");
        assertThat(again).as("the replay is refused").isNotNull();
        assertThat(faults(again)).as("the replay's faults").isEqualTo(faults(refused));
        assertThat(f.store.attempts).as("the store was asked once").isEqualTo(1);
        assertThat(f.store.state("AA1")).as("the issue stays").isEqualTo(WORKING);
    }

    // Prevents a finished analysis from being refused for good because the store was busy, a
    // check swapped its board or the person's forge access was missing, when the store wrote
    // nothing and a later hand-in would save it.
    @Test
    void aSaveTheStoreDidNotStartIsAnsweredTryLaterAndALaterHandInSavesIt() {
        for (final StoreException passing :
                List.<StoreException>of(
                        new StoreException.Busy("alpha"),
                        new StoreException.StaleRevision("r2"),
                        new StoreException.NoForgeAccess("https://github.com", "Projects"))) {
            final Stage f = new Stage();
            final WorkRun.Offered offer = f.started();
            f.store.failure = passing;

            assertThat(catchThrowableOfType(StoreException.class, () -> f.handIn(offer, valid())))
                    .as("the store's own answer, which the window tries again later")
                    .isSameAs(passing);
            f.store.failure = null;
            f.handIn(offer, valid());

            assertThat(f.store.results).as("saved once, by the later hand-in").hasSize(1);
            assertThat(f.store.state("AA1")).as("the issue in its done state").isEqualTo(DONE);
        }
    }

    @Test
    void aResultRefusedForTheForgeAccessIsSavedByAHandInAfterItsTaskEnded() {
        // Harm: a finished analysis lost as missing because its task ended before the person
        // handed a token on, though the store wrote nothing and the result could still be saved.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        final StoreException refused = new StoreException.NoForgeAccess("https://github.com", null);
        f.store.failure = refused;
        assertThat(catchThrowableOfType(StoreException.class, () -> f.handIn(offer, valid())))
                .as("the forge's refusal, which the window answers with the offer of a token")
                .isSameAs(refused);
        f.end(offer);
        f.store.failure = null;

        final HandedResults.Answer answer = f.handIn(offer, valid());

        assertThat(answer.outcome())
                .as("the outcome of the hand-in after the end")
                .isEqualTo(HandedResults.SAVED);
        assertThat(f.store.results).as("saved once, by the later hand-in").hasSize(1);
        assertThat(f.store.state("AA1")).as("the issue in its done state").isEqualTo(DONE);
        assertThat(f.told).as("no line says the result is missing").isEmpty();
    }

    @Test
    void aResultHandedInAgainWhileItsSaveRunsIsRefusedAsInFlightAndWritesNothing() {
        // Harm: a result told lost, and recorded refused for good, while its own save is still
        // at the store.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        f.record.value =
                HandedResults.pending(
                        f.record.value,
                        offer.offer(),
                        "task",
                        List.of("AA1"),
                        java.util.UUID.randomUUID().toString());
        final WorkRun.Snapshot before = f.record.value;

        final WorkRefusal refused =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, valid()));

        assertThat(refused).as("the hand-in is refused").isNotNull();
        assertThat(faults(refused)).as("the fault").containsExactly(HandedResults.IN_FLIGHT);
        assertThat(f.store.attempts).as("the store is not asked").isZero();
        assertThat(f.record.value).as("nothing is written").isEqualTo(before);
    }

    @Test
    void anEndWhileItsResultsSaveRunsAsksNothingOfTheOwnerAccount() {
        // Harm: a stage's end, arriving while its result's save is at the store, stamps its
        // issues under Needs you as if no result came, though the save moves them.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        f.record.value =
                HandedResults.pending(
                        f.record.value,
                        offer.offer(),
                        "task",
                        List.of("AA1"),
                        java.util.UUID.randomUUID().toString());

        f.end(offer);

        assertThat(f.run.work("ST1").issues().get("AA1").needsYou())
                .as("the issue asks nothing while its result is saved")
                .isFalse();
        assertThat(f.told).as("no line says the result did not end").isEmpty();
    }

    @Test
    void atOpenAResultLeftPendingIsRefusedWithoutAskingTheStore() {
        // Prevents a result saved twice after the planner stopped during its save, and a
        // result kept pending for good.
        for (final String save : List.of(java.util.UUID.randomUUID().toString(), "")) {
            final Stage f = new Stage();
            final WorkRun.Offered offer = f.started();
            f.record.value =
                    HandedResults.pending(
                            f.record.value, offer.offer(), "task", List.of("AA1"), save);

            f.run.repair();

            final HandedResults.Handed handed = f.record.value.results().get(offer.offer());
            assertThat(handed.status())
                    .as("the pending save is recorded as refused, named or not")
                    .isEqualTo(HandedResults.REFUSED);
            assertThat(handed.faults()).as("the fault").containsExactly(HandedResults.NOT_ENDED);
            final WorkRefusal again =
                    catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, valid()));
            assertThat(faults(again))
                    .as("a later hand-in")
                    .containsExactly(HandedResults.NOT_ENDED);
            assertThat(f.store.attempts).as("the store is not asked").isZero();
        }
    }

    @Test
    void aResultSavesOutcomeLeavesAnotherOffersEntryAsItStands() {
        // Harm: a result's save, settling its outcome, writes back the record it read before the
        // store answered, so an entry written meanwhile is lost.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.started();
        final String other = java.util.UUID.randomUUID().toString();
        f.store.during =
                () ->
                        f.record.value =
                                HandedResults.with(
                                        f.record.value,
                                        other,
                                        HandedResults.MISSING,
                                        "other",
                                        List.of("AA1"),
                                        List.of(),
                                        "none");
        f.store.failure = new StoreException.Busy("alpha");

        catchThrowable(() -> f.handIn(offer, valid()));

        assertThat(f.record.value.results())
                .as("the entry written while the save ran")
                .containsKey(other);
        assertThat(f.record.value.results())
                .as("the refused save's own entry is gone")
                .doesNotContainKey(offer.offer());
    }

    @Test
    void aHandInForAnUnknownForeignUnstartedOrVoidedOfferIsRefusedAndWritesNothing() {
        // Prevents a result saved for work this task was never given.
        final Stage f = new Stage();
        final WorkRun.Offered offer = f.order().tasks().getFirst();
        final WorkRun.Snapshot record = f.record.value;

        final WorkRefusal unstarted =
                catchThrowableOfType(WorkRefusal.class, () -> f.handIn(offer, valid()));
        final WorkRefusal unknown =
                catchThrowableOfType(
                        WorkRefusal.class,
                        () ->
                                f.run.result(
                                        "task", "0b1d6f0e-6c43-4e8e-9a51-6f5cbb5b3a11", valid()));

        assertThat(unstarted).as("an offer not started is refused").isNotNull();
        assertThat(unstarted.name()).as("its refusal").isEqualTo("ResultRefused");
        assertThat(unknown).as("an unknown offer is refused").isNotNull();
        assertThat(unknown.name()).as("its refusal").isEqualTo("TaskNotAnswered");
        assertThat(f.record.value).as("nothing is recorded").isEqualTo(record);

        f.start(offer);
        final WorkRefusal foreign =
                catchThrowableOfType(
                        WorkRefusal.class, () -> f.run.result("other", offer.offer(), valid()));
        assertThat(foreign).as("another task's hand-in is refused").isNotNull();
        assertThat(foreign.name()).as("its refusal").isEqualTo("TaskNotAnswered");

        final Stage v = new Stage();
        final WorkRun.Offered voided = v.order().tasks().getFirst();
        v.run.stop("ST1");
        final WorkRefusal stopped =
                catchThrowableOfType(WorkRefusal.class, () -> v.handIn(voided, valid()));
        assertThat(stopped).as("a voided offer's hand-in is refused").isNotNull();
        assertThat(stopped.name()).as("its refusal").isEqualTo("TaskNotAnswered");
        assertThat(f.store.results).as("nothing is saved").isEmpty();
        assertThat(v.store.results).as("nothing is saved").isEmpty();
    }

    @Test
    void aReadersOrAPushingTasksOfferTakesNoResult() {
        // Prevents a reading or a build moving its issue by a result it was never asked for.
        final WorkRunTest.Fixture f = new WorkRunTest.Fixture();
        f.store.current = NextStepTest.board("Backlog", "Backlog");
        final WorkRun.Offered reader = f.readers().getFirst();
        final WorkRun.Snapshot record = f.record.value;

        final WorkRefusal refused =
                catchThrowableOfType(
                        WorkRefusal.class,
                        () ->
                                f.run.result(
                                        "reader-" + reader.task().issues().getFirst(),
                                        reader.offer(),
                                        valid()));

        assertThat(refused).as("the reader's hand-in is refused").isNotNull();
        assertThat(refused.name()).as("its refusal").isEqualTo("ResultRefused");
        assertThat(f.record.value).as("nothing is recorded").isEqualTo(record);
    }

    private static List<String> faults(final WorkRefusal refusal) {
        @SuppressWarnings("unchecked")
        final List<String> faults = (List<String>) refusal.parameters().get("faults");
        return faults;
    }

    static HandedResults.HandIn valid() {
        return hand(file(entry("AA1", "Read the code.\nChange it.", "M", "One module.", null)));
    }

    static HandedResults.HandIn hand(final HandedResults.File file) {
        return new HandedResults.HandIn(file, null, List.of());
    }

    static HandedResults.File file(final HandedResults.Entry... entries) {
        return new HandedResults.File(StageOrder.FORMAT, "ANALYSIS", List.of(entries));
    }

    static HandedResults.Entry entry(
            final String id,
            final @Nullable String plan,
            final String size,
            final String why,
            final @Nullable Long minutes) {
        return new HandedResults.Entry(id, plan, size, why, minutes, null, null);
    }

    private static Issue withWorker(final Issue issue, final Worker worker) {
        return new Issue(
                issue.id(),
                issue.repository(),
                issue.title(),
                issue.state(),
                issue.size(),
                issue.blockedBy(),
                issue.parent(),
                issue.link(),
                issue.text(),
                worker,
                issue.risk());
    }

    /** A project whose analysis stage hands back its result. */
    static final class Stage {
        final ResultStore store = new ResultStore();
        final WorkRunTest.Memory record = new WorkRunTest.Memory();
        final List<String> told = new ArrayList<>();
        final WorkRun run =
                new WorkRun(
                        store,
                        record,
                        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                        told::add);

        WorkRun.Order order() {
            return run.order(
                    "ST1",
                    WORKING,
                    new WorkOrder.Decision("AGENT", Basis.of(store.board()), null, "", 1, null),
                    WorkRun.Start.NONE);
        }

        void start(final WorkRun.Offered offer) {
            run.report(
                    "task",
                    new WorkRun.Report(
                            offer.offer(), WorkRun.Event.STARTED, "box", "workstation", null));
        }

        WorkRun.Offered started() {
            final WorkRun.Offered offer = order().tasks().getFirst();
            assertThat(offer.task().result())
                    .as("the stage asks for its result")
                    .isEqualTo("analysis.json");
            start(offer);
            assertThat(store.state("AA1")).as("the started issue").isEqualTo(WORKING);
            return offer;
        }

        HandedResults.Answer handIn(
                final WorkRun.Offered offer, final HandedResults.HandIn handIn) {
            return run.result("task", offer.offer(), handIn);
        }

        void end(final WorkRun.Offered offer) {
            run.report(
                    "task",
                    new WorkRun.Report(
                            offer.offer(),
                            WorkRun.Event.ENDED,
                            "box",
                            "workstation",
                            new WorkRun.Ended("2026-01-01T00:00:00Z", true, "", "Done", 0)));
        }

        String reason(final String issue) {
            final WorkRun.Run work = java.util.Objects.requireNonNull(run.work("ST1"));
            return java.util.Objects.requireNonNull(work.issues().get(issue)).reason();
        }
    }

    /** A store whose saves and saved results can be seen. */
    static final class ResultStore implements PlanningStore {
        Board current;
        int saves;
        int attempts;
        final List<StageResult> results = new ArrayList<>();
        @Nullable StoreException failure;

        /**
         * Runs once inside the next result save, as another call's change while it is at GitHub.
         */
        @Nullable Runnable during;

        ResultStore() {
            final WorkRule analysis =
                    new WorkRule(
                            List.of("Backlog"),
                            DONE,
                            null,
                            null,
                            WorkRule.Per.REPOSITORY,
                            List.of("AGENT"),
                            "Analyze {story}: {issues}; {tasks}",
                            Map.of("codex", "model"),
                            Map.of(),
                            WorkRule.Result.ANALYSIS);
            current =
                    new Board(
                            new Revision("board@1"),
                            new Workflow(
                                    List.of("Backlog", WORKING, DONE, "Finished"),
                                    List.of(),
                                    List.of("Finished")),
                            List.of(
                                    new Story(
                                            "ST1",
                                            "Story",
                                            "Backlog",
                                            "High",
                                            null,
                                            List.of("AA1"),
                                            "",
                                            null)),
                            List.of(
                                    new Issue(
                                            "AA1",
                                            "alpha",
                                            "Issue",
                                            "Backlog",
                                            null,
                                            List.of(),
                                            null,
                                            null,
                                            "**What must be true.** It works.",
                                            Worker.AGENT,
                                            Risk.ORDINARY)),
                            new Work(Map.of(WORKING, analysis), List.of()),
                            org.fuin.porelo.planner.store.ProjectRepository.named(
                                    List.of("alpha")));
        }

        String state(final String id) {
            return current.issue(id).orElseThrow().state();
        }

        void change(final String id, final UnaryOperator<Issue> change) {
            current =
                    new Board(
                            current.revision(),
                            current.workflow(),
                            current.stories(),
                            current.issues().stream()
                                    .map(i -> i.id().equals(id) ? change.apply(i) : i)
                                    .toList(),
                            current.work(),
                            current.repositories());
        }

        @Override
        public Board board() {
            return current;
        }

        @Override
        public Board refresh() {
            return current;
        }

        @Override
        public Board save(final Revision revision, final Writes writes) {
            saves++;
            writes.issues().forEach((id, state) -> change(id, i -> inState(i, state)));
            return current;
        }

        @Override
        public Board saveResult(final StageResult result) {
            attempts++;
            final Runnable meanwhile = during;
            if (meanwhile != null) {
                during = null;
                meanwhile.run();
            }
            if (failure != null) {
                throw failure;
            }
            for (final StageResult.ForIssue issue : result.issues()) {
                if (!state(issue.id()).equals(issue.from())) {
                    throw new StoreException.NotSavable(issue.id(), "moved");
                }
            }
            results.add(result);
            result.issues().forEach(issue -> change(issue.id(), i -> inState(i, issue.to())));
            return current;
        }

        @Override
        public String storyState(final List<String> states) {
            return states.getFirst();
        }

        private static Issue inState(final Issue issue, final String state) {
            return new Issue(
                    issue.id(),
                    issue.repository(),
                    issue.title(),
                    state,
                    issue.size(),
                    issue.blockedBy(),
                    issue.parent(),
                    issue.link(),
                    issue.text(),
                    issue.worker(),
                    issue.risk());
        }
    }
}
