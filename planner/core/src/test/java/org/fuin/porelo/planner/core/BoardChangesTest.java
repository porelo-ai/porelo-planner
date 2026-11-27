package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BoardChangesTest {

    /** ST1 holds AB1; AB2 is loose. */
    private static final Board BOARD =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                    List.of(
                            new Story(
                                    "ST1",
                                    "A story",
                                    "Idea",
                                    "High",
                                    null,
                                    List.of("AB1"),
                                    "",
                                    null)),
                    List.of(issue("AB1"), issue("AB2")),
                    new Work(Map.of(), List.of()),
                    ProjectRepository.named(List.of("porelo-store")));

    private static Issue issue(final String id) {
        return new Issue(
                id,
                "porelo-store",
                "Title of " + id,
                "Idea",
                null,
                List.of(),
                null,
                null,
                "Text.",
                Worker.AGENT,
                Risk.ORDINARY);
    }

    @Test
    void aKeyOfTheChangeNamesItsNewIssueAndAnIdTheBoardHoldsNamesThatItem() {
        // Harm: a blocker named by key written as a literal id, or an existing item refused.
        final Set<String> keys = Set.of("first", "AB2");

        assertThat(BoardChanges.target(BOARD, keys, "", "first"))
                .as("a key of the change")
                .isEqualTo(new BoardChanges.Key("first"));
        assertThat(BoardChanges.target(BOARD, keys, "", "AB2"))
                .as("a key wins over an id it spells, as the caller chose it for its new issue")
                .isEqualTo(new BoardChanges.Key("AB2"));
        assertThat(BoardChanges.target(BOARD, keys, "", "AB1"))
                .as("an issue the board holds")
                .isEqualTo(new BoardChanges.Existing("AB1"));
        assertThat(BoardChanges.target(BOARD, keys, "", "ST1"))
                .as("a story the board holds")
                .isEqualTo(new BoardChanges.Existing("ST1"));
    }

    @Test
    void aReferenceNamingNothingIsRefusedNamingTheItemAndTheReference() {
        // Harm: a typo in a key made into a blocker nobody can find, or a refusal that does not
        // say which reference to repair.
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () -> BoardChanges.target(BOARD, Set.of("first"), "AB1", "frist"));

        assertThat(refused).as("the refusal").isNotNull();
        assertThat(refused.name()).as("its name").isEqualTo(BoardChanges.NO_SUCH_KEY);
        assertThat(refused.parameters())
                .as("the item and the reference")
                .containsEntry("item", "AB1")
                .containsEntry("key", "frist");
    }

    @Test
    void aKeyResolvesToTheIdMadeForItAndAKeyOfARefusedNewIssueIsRefusedNamingIt() {
        // Harm: a blocker of a new issue that was never made written as a dangling link.
        assertThat(BoardChanges.id(new BoardChanges.Key("first"), Map.of("first", "AB3"), "AB1"))
                .as("the id made for the key")
                .isEqualTo("AB3");
        assertThat(BoardChanges.id(new BoardChanges.Existing("AB2"), Map.of(), "AB1"))
                .as("an existing item's id as it is")
                .isEqualTo("AB2");

        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () -> BoardChanges.id(new BoardChanges.Key("first"), Map.of(), "AB1"));

        assertThat(refused).as("the refusal").isNotNull();
        assertThat(refused.name()).as("its name").isEqualTo(BoardChanges.KEY_REFUSED);
        assertThat(refused.parameters())
                .as("the item and the key")
                .containsEntry("item", "AB1")
                .containsEntry("key", "first");
    }

    /** AB2 waits on AB1, as AB1 is the blocker the board holds. */
    private static final Board CHAIN =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                    List.of(),
                    List.of(issue("AB1"), waiting("AB2", "AB1")),
                    new Work(Map.of(), List.of()),
                    ProjectRepository.named(List.of("porelo-store")));

    private static Issue waiting(final String id, final String blocker) {
        return new Issue(
                id,
                "porelo-store",
                "Title of " + id,
                "Idea",
                null,
                List.of(blocker),
                null,
                null,
                "Text.",
                Worker.AGENT,
                Risk.ORDINARY);
    }

    private static BoardChanges.Item created(final String key, final String... blockers) {
        return new BoardChanges.Item(key, "", null, null, List.of(blockers), null);
    }

    private static BoardChanges.Item createdIn(final String key, final String state) {
        return new BoardChanges.Item(key, "", null, null, List.of(), state);
    }

    private static BoardChanges.Item changed(final String issue, final String... blockers) {
        return new BoardChanges.Item(null, issue, null, null, List.of(blockers), null);
    }

    private static BoardChanges.Item hung(
            final @Nullable String key, final String issue, final String story) {
        return new BoardChanges.Item(key, key == null ? issue : "", null, story, null, null);
    }

    private static String name(final Map<Integer, RuntimeException> refusals, final int at) {
        final RuntimeException refusal = refusals.get(at);
        assertThat(refusal).as("the refusal of item " + at).isInstanceOf(Moves.Refused.class);
        return ((Moves.Refused) refusal).name();
    }

    @Test
    void anIssueBlockingItselfAStoryAsBlockerOrABlockerNamedTwiceIsRefusedBeforeAnyWrite() {
        // Harm: a blocker the write refuses found only after other items were written.
        final Map<Integer, RuntimeException> refusals =
                BoardChanges.judge(
                        BOARD,
                        List.of(
                                changed("AB1", "AB1"),
                                changed("AB2", "ST1"),
                                changed("AB2", "AB1", "AB1"),
                                created("first", "first"),
                                created("second", "AB1")));

        assertThat(refusals.keySet())
                .as("the items refused, the last one stands")
                .containsExactly(0, 1, 2, 3);
        assertThat(name(refusals, 0)).as("an issue blocking itself").isEqualTo(Edits.REFUSED);
        assertThat(name(refusals, 1)).as("a story as blocker is no issue").isEqualTo("NoSuchIssue");
        assertThat(name(refusals, 2)).as("a blocker named twice").isEqualTo(Edits.REFUSED);
        assertThat(name(refusals, 3)).as("a new issue blocking itself").isEqualTo(Edits.REFUSED);
    }

    @Test
    void aCircleThroughTheBoardsBlockersAndTheBatchsIsRefusedForTheItemThatClosesIt() {
        // Harm: two issues waiting on each other, so neither can ever start.
        final Map<Integer, RuntimeException> onBoard =
                BoardChanges.judge(CHAIN, List.of(changed("AB1", "AB2")));
        final Map<Integer, RuntimeException> inBatch =
                BoardChanges.judge(CHAIN, List.of(created("c", "AB1"), changed("AB1", "c")));

        assertThat(name(onBoard, 0))
                .as("AB2 waits on AB1 on the board, so AB1 cannot wait on AB2")
                .isEqualTo(Edits.REFUSED);
        assertThat(inBatch.keySet())
                .as("the new issue stands, the change that closes the circle is refused")
                .containsExactly(1);
        assertThat(name(inBatch, 1)).as("its refusal").isEqualTo(Edits.REFUSED);
    }

    @Test
    void anItemWaitingOnARefusedNewIssueIsRefusedWhereverItIsListedAndThroughAChain() {
        // Harm: a new issue made without the blocker the agent planned, as its blocker is listed
        // after it and was not yet refused when it was judged.
        final Map<Integer, RuntimeException> refusals =
                BoardChanges.judge(
                        BOARD,
                        List.of(
                                created("first", "second"),
                                created("second", "third"),
                                createdIn("third", "Nowhere"),
                                created("fourth")));

        assertThat(refusals.keySet()).as("all but the last").containsExactly(0, 1, 2);
        assertThat(name(refusals, 2)).as("the state is no state").isEqualTo("StateNotAllowed");
        for (final int at : List.of(0, 1)) {
            assertThat(name(refusals, at)).as("a waiting item " + at).isEqualTo("KeyRefused");
        }
        assertThat(((Moves.Refused) refusals.get(0)).parameters())
                .as("the key it waits on")
                .containsEntry("key", "second");
    }

    @Test
    void aCircleOfNewIssuesRefusesTheSecondAndThereforeTheFirstNamingIt() {
        // Harm: a circle written in part.
        final Map<Integer, RuntimeException> refusals =
                BoardChanges.judge(BOARD, List.of(created("a", "b"), created("b", "a")));

        assertThat(name(refusals, 1)).as("the item that closes it").isEqualTo(Edits.REFUSED);
        assertThat(name(refusals, 0)).as("the item waiting on it").isEqualTo("KeyRefused");
    }

    @Test
    void aStoryThatIsNoStoryOfTheBoardOrAKeyIsRefusedWithTheNameTheWriteWouldGive() {
        // Harm: an issue hung into an issue's id, or into a new issue, after others were written.
        final Map<Integer, RuntimeException> refusals =
                BoardChanges.judge(
                        BOARD,
                        List.of(
                                created("first"),
                                hung(null, "AB2", "AB1"),
                                hung(null, "AB2", "first"),
                                hung("second", "", "AB1"),
                                hung(null, "AB2", "ST1")));

        assertThat(refusals.keySet()).as("all but the last").containsExactly(1, 2, 3);
        assertThat(name(refusals, 1)).as("an update into an issue").isEqualTo("NoSuchStory");
        assertThat(name(refusals, 2)).as("a story named by key").isEqualTo(Edits.REFUSED);
        assertThat(name(refusals, 3)).as("a new issue into an issue").isEqualTo("IssueNotAllowed");
    }

    @Test
    void aStateTheWorkflowDoesNotNameIsRefusedAsTheMoveAndTheNewIssueWouldRefuseIt() {
        // Harm: an issue changed and then left unmoved with only a late word of it.
        final Map<Integer, RuntimeException> refusals =
                BoardChanges.judge(
                        BOARD,
                        List.of(
                                new BoardChanges.Item(null, "AB1", null, null, null, "Nowhere"),
                                createdIn("first", "Nowhere"),
                                new BoardChanges.Item(null, "AB2", null, null, null, "Finished")));

        assertThat(refusals.keySet()).as("the two with no such state").containsExactly(0, 1);
        assertThat(((Moves.Refused) refusals.get(0)).parameters())
                .as("the move's refusal names the item and the state it stands in")
                .containsEntry("item", "AB1")
                .containsEntry("from", "Idea")
                .containsEntry("to", "Nowhere");
        assertThat(name(refusals, 1)).as("the new issue's refusal").isEqualTo("StateNotAllowed");
    }

    @Test
    void aBlankPartOrNoTextAtAllIsRefusedAsTheTextEditWouldRefuseIt() {
        // Harm: a new issue made and its text part left out for being blank.
        final Map<Integer, RuntimeException> refusals =
                BoardChanges.judge(
                        BOARD,
                        List.of(
                                new BoardChanges.Item(
                                        "first",
                                        "",
                                        new TextEdit(
                                                TextEdit.Kind.ISSUE,
                                                null,
                                                Map.of(TextEdit.Part.PLAN, " "),
                                                Set.of()),
                                        null,
                                        List.of(),
                                        null),
                                new BoardChanges.Item(
                                        null,
                                        "AB1",
                                        new TextEdit(TextEdit.Kind.ISSUE, null, Map.of(), Set.of()),
                                        null,
                                        null,
                                        null),
                                new BoardChanges.Item(
                                        null,
                                        "AB2",
                                        new TextEdit(
                                                TextEdit.Kind.ISSUE,
                                                "A new title",
                                                Map.of(TextEdit.Part.PLAN, "Do it."),
                                                Set.of()),
                                        null,
                                        null,
                                        null)));

        assertThat(refusals.keySet()).as("the blank part and the empty edit").containsExactly(0, 1);
        assertThat(((Moves.Refused) refusals.get(0)).parameters())
                .as("the part and its fault")
                .containsEntry("part", "Plan")
                .containsEntry("fault", "BLANK_LINE");
    }

    @Test
    void anUpdateOfAnIssueTheBoardLacksIsRefusedAsNoSuchIssue() {
        // Harm: an update of nothing counted as written.
        assertThat(name(BoardChanges.judge(BOARD, List.of(changed("AB9"))), 0))
                .as("the refusal")
                .isEqualTo("NoSuchIssue");
    }
}
