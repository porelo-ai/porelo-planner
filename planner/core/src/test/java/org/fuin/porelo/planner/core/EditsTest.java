package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextEdit;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** The edits a person keeps the board with, checked against the board the caller read. */
class EditsTest {

    /** The link of an issue made outside the project, a fixture of an invented repository. */
    private static final String MADE_LINK =
            "https://github.com/porelo-ai/porelo-store/issues/7"; // not-a-citation

    /**
     * ST1 holds AB1 and AB2, ST2 holds AB3; AB1.1 is a sub-issue of AB1; AB2 waits on AB3, which
     * waits on AB4.
     */
    private final Board board =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                    List.of(
                            story("ST1", List.of("AB1", "AB2")),
                            story("ST2", List.of("AB3")),
                            story("ST3", List.of())),
                    List.of(
                            issue("AB1", null, List.of()),
                            issue("AB1.1", "AB1", List.of()),
                            issue("AB2", null, List.of("AB3")),
                            issue("AB3", null, List.of("AB4")),
                            issue("AB4", null, List.of()),
                            issue("AB5", null, List.of())),
                    new Work(Map.of(), List.of()));

    private static final Fields SIZE = new Fields(Set.of(Fields.Field.SIZE), "M", null, null, null);

    private static Story story(final String id, final List<String> issues) {
        return new Story(id, "Story " + id, "Idea", "High", null, issues, "", null);
    }

    private static Issue issue(final String id, final String parent, final List<String> blockedBy) {
        return new Issue(
                id, "porelo-store", "Issue " + id, "Idea", null, blockedBy, parent, null, "");
    }

    @Test
    void anEditAgainstAnotherRevisionIsRefusedAsStaleNamingTheBoardsRevision() {
        // An edit planned on an old board would undo what someone saved since.
        for (final Runnable edit :
                List.<Runnable>of(
                        () -> Edits.fields(board, "r0", "AB1", SIZE),
                        () -> Edits.storyIssues(board, "r0", "ST1", List.of("AB1")),
                        () -> Edits.blockedBy(board, "r0", "AB1", List.of()))) {
            final StoreException.StaleRevision stale =
                    catchThrowableOfType(StoreException.StaleRevision.class, edit::run);
            assertThat(stale).as("an edit against r0").isNotNull();
            assertThat(stale.current()).as("the revision named").isEqualTo("r1");
        }
    }

    @Test
    void anItemTheBoardLacksIsRefusedAsNotFound() {
        // An edit of an item nobody can see would write to whatever the store finds by that id.
        assertThat(refusal(() -> Edits.fields(board, "r1", "AB9", SIZE)).name())
                .as("the fields of an unknown issue")
                .isEqualTo("NoSuchIssue");
        assertThat(refusal(() -> Edits.storyIssues(board, "r1", "ST9", List.of())).name())
                .as("the issues of an unknown story")
                .isEqualTo("NoSuchStory");
        assertThat(refusal(() -> Edits.storyIssues(board, "r1", "ST3", List.of("AB9"))).name())
                .as("an unknown issue hung into a story")
                .isEqualTo("NoSuchIssue");
        assertThat(refusal(() -> Edits.blockedBy(board, "r1", "AB5", List.of("AB9"))).name())
                .as("an unknown blocker")
                .isEqualTo("NoSuchIssue");
        assertThat(refusal(() -> Edits.comment(board, "AB9", "A comment.")).name())
                .as("a comment on an unknown issue")
                .isEqualTo("NoSuchIssue");
    }

    @Test
    void anIssueOfAnotherStoryIsRefusedAsASecondParentNamingTheStory() {
        // An issue has one story on a tracker that allows one parent; a second would be lost.
        final Moves.Refused refused =
                refusal(() -> Edits.storyIssues(board, "r1", "ST3", List.of("AB3")));

        assertThat(refused.name()).as("the refusal").isEqualTo(Edits.REFUSED);
        assertThat(refused.parameters())
                .as("the story edited and the member refused")
                .containsEntry("item", "ST3")
                .containsEntry("field", "issues");
        assertThat(refused.getMessage()).as("the story that holds it").contains("ST2");
    }

    @Test
    void aSubIssueOfAnIssueOrAnIssueNamedTwiceIsRefused() {
        // A sub-issue hung into a story would have two parents; a doubled one two places.
        assertThat(refusal(() -> Edits.storyIssues(board, "r1", "ST3", List.of("AB1.1"))).name())
                .as("a sub-issue of an issue")
                .isEqualTo(Edits.REFUSED);
        assertThat(
                        refusal(() -> Edits.storyIssues(board, "r1", "ST3", List.of("AB5", "AB5")))
                                .name())
                .as("an issue named twice")
                .isEqualTo(Edits.REFUSED);
        assertThat(
                        refusal(
                                        () ->
                                                Edits.storyIssues(
                                                        board,
                                                        "r1",
                                                        "ST3",
                                                        Collections.nCopies(
                                                                Edits.MOST_ISSUES + 1, "AB5")))
                                .name())
                .as("a list longer than a story holds")
                .isEqualTo(Edits.REFUSED);
    }

    @Test
    void theStorysOwnIssuesReorderedOrAFreeIssueHungInIsTaken() {
        // A list a story may hold must be written, and one it holds already must write nothing.
        assertThat(Edits.storyIssues(board, "r1", "ST1", List.of("AB2", "AB1")))
                .as("the story's issues in another order")
                .isTrue();
        assertThat(Edits.storyIssues(board, "r1", "ST1", List.of("AB1", "AB2", "AB5")))
                .as("a free issue hung in")
                .isTrue();
        assertThat(Edits.storyIssues(board, "r1", "ST1", List.of("AB1", "AB2")))
                .as("the list the story holds")
                .isFalse();
    }

    @Test
    void blockersLeadingBackToTheIssueAreRefusedBeforeAnythingIsWritten() {
        // A circle of blockers leaves every issue in it waiting forever, and the board unreadable.
        final Moves.Refused circle =
                refusal(() -> Edits.blockedBy(board, "r1", "AB4", List.of("AB2")));
        assertThat(circle.name()).as("a circle through AB2 and AB3").isEqualTo(Edits.REFUSED);
        assertThat(circle.parameters())
                .as("the issue and the member refused")
                .containsEntry("item", "AB4")
                .containsEntry("field", "blockedBy");
        assertThat(refusal(() -> Edits.blockedBy(board, "r1", "AB4", List.of("AB4"))).name())
                .as("an issue blocking itself")
                .isEqualTo(Edits.REFUSED);
        assertThat(refusal(() -> Edits.blockedBy(board, "r1", "AB4", List.of("AB5", "AB5"))).name())
                .as("a blocker named twice")
                .isEqualTo(Edits.REFUSED);
        assertThat(Edits.blockedBy(board, "r1", "AB2", List.of("AB4")))
                .as("a blocker replaced by one further down, no circle")
                .isTrue();
        assertThat(Edits.blockedBy(board, "r1", "AB2", List.of("AB3")))
                .as("the blockers the issue has")
                .isFalse();
    }

    @Test
    void theIssuesAnIssueWaitsOnOutsideTheBoardCountTowardItsMostBlockers() {
        // Harm: a list GitHub refuses past its limit passes the check, and the write fails after
        // some of its links were written.
        final List<Issue> issues = new java.util.ArrayList<>();
        final List<String> blockers = new java.util.ArrayList<>();
        for (int number = 10; number < 10 + Edits.MOST_BLOCKERS - 1; number++) {
            issues.add(issue("AB" + number, null, List.of()));
            blockers.add("AB" + number);
        }
        issues.add(
                new Issue(
                        "AB1",
                        "porelo-store",
                        "Issue AB1",
                        "Idea",
                        null,
                        List.of(),
                        null,
                        null,
                        "",
                        org.fuin.porelo.planner.store.Worker.AGENT,
                        org.fuin.porelo.planner.store.Risk.ORDINARY,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(
                                new org.fuin.porelo.planner.store.Wait(
                                        "other-org/elsewhere#7", false, null),
                                org.fuin.porelo.planner.store.Wait.unreadable())));
        final Board waiting =
                new Board(
                        new Revision("r1"),
                        new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                        List.of(),
                        issues,
                        new Work(Map.of(), List.of()));

        final Moves.Refused refused =
                refusal(() -> Edits.blockedBy(waiting, "r1", "AB1", blockers));
        assertThat(refused.name()).as("one more than GitHub takes").isEqualTo(Edits.REFUSED);
        assertThat(Edits.blockedBy(waiting, "r1", "AB1", blockers.subList(1, blockers.size())))
                .as("as many as GitHub takes, the waits included")
                .isTrue();
    }

    @Test
    void aBlankOrTooLongCommentIsRefused() {
        // A blank comment adds noise; one past GitHub's limit fails after the check passed.
        assertThat(refusal(() -> Edits.comment(board, "AB1", " \n")).name())
                .as("a blank comment")
                .isEqualTo(Edits.REFUSED);
        assertThat(
                        refusal(
                                        () ->
                                                Edits.comment(
                                                        board,
                                                        "AB1",
                                                        "x".repeat(Edits.MOST_COMMENT + 1)))
                                .name())
                .as("a comment past the most")
                .isEqualTo(Edits.REFUSED);
        Edits.comment(board, "AB1", "😀".repeat(Edits.MOST_COMMENT));
    }

    @Test
    void aChangeOfFieldsThatNamesNoneIsRefused() {
        // A change of nothing would still write and announce a save nobody made.
        assertThat(
                        refusal(
                                        () ->
                                                Edits.fields(
                                                        board,
                                                        "r1",
                                                        "AB1",
                                                        new Fields(
                                                                Set.of(), null, null, null, null)))
                                .name())
                .as("no field named")
                .isEqualTo(Edits.REFUSED);
        Edits.fields(board, "r1", "AB1", SIZE);
    }

    @Test
    void aStoryTheBoardShowsInPartIsRefusedAWholeList() {
        // An issue the board left out of the story would be dropped by a list written whole.
        final Board partial =
                new Board(
                        board.revision(),
                        board.workflow(),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story ST1",
                                        "Idea",
                                        "High",
                                        null,
                                        List.of("AB1"),
                                        "",
                                        null,
                                        false)),
                        board.issues(),
                        board.work());
        assertThat(refusal(() -> Edits.storyIssues(partial, "r1", "ST1", List.of("AB1"))).name())
                .as("a story whose issues the board does not all show")
                .isEqualTo(Edits.REFUSED);
    }

    @Test
    void aCommentOpeningWithThePlannersOwnMarkerIsRefused() {
        // The planner reads its stage records from such comments: a forged one would change an
        // issue's sums or hide a real stage.
        assertThat(
                        refusal(
                                        () ->
                                                Edits.comment(
                                                        board,
                                                        "AB1",
                                                        "  <!-- porelo:stage -->\n```json\n{}\n```"))
                                .name())
                .as("a comment posing as a stage record")
                .isEqualTo(Edits.REFUSED);
        Edits.comment(board, "AB1", "A note on <!-- porelo:stage --> in the middle.");
    }

    private static TextEdit title(final TextEdit.Kind kind, final String title) {
        return new TextEdit(kind, title, Map.of(), Set.of());
    }

    @Test
    void aTextEditAgainstAnotherRevisionIsStaleAndOneOfAnItemOfTheOtherKindIsNotFound() {
        // An edit planned on an old board would write over what someone saved since; a story's
        // text written to an issue of the same id would change the wrong item.
        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                Edits.text(
                                        board, "r0", "AB1", title(TextEdit.Kind.ISSUE, "A title")));
        assertThat(stale).as("a text edit against r0").isNotNull();
        assertThat(
                        refusal(
                                        () ->
                                                Edits.text(
                                                        board,
                                                        "r1",
                                                        "AB1",
                                                        title(TextEdit.Kind.STORY, "A title")))
                                .name())
                .as("an issue's id edited as a story")
                .isEqualTo("NoSuchStory");
        assertThat(
                        refusal(
                                        () ->
                                                Edits.text(
                                                        board,
                                                        "r1",
                                                        "ST1",
                                                        title(TextEdit.Kind.ISSUE, "A title")))
                                .name())
                .as("a story's id edited as an issue")
                .isEqualTo("NoSuchIssue");
    }

    @Test
    void aTitleWithALineBreakOrOutOfBoundsAndABlankOrTooLongPartAreRefused() {
        // A line break in a title breaks the tracker's one-line head; a blank part would leave a
        // lead or a heading with nothing under it, a part past the tracker's limit is cut off.
        // Harm: an item made with a title a forge takes cannot be given another as long.
        assertThatCode(
                        () ->
                                Edits.text(
                                        board,
                                        "r1",
                                        "AB1",
                                        title(TextEdit.Kind.ISSUE, "😀".repeat(256))))
                .as("a title of 256 characters")
                .doesNotThrowAnyException();
        for (final String title :
                List.of("One\nTwo", "One\rTwo", "One\u2028Two", "  ", "x".repeat(257))) {
            final Moves.Refused refused =
                    refusal(
                            () ->
                                    Edits.text(
                                            board, "r1", "AB1", title(TextEdit.Kind.ISSUE, title)));
            assertThat(refused.name()).as("the title refused").isEqualTo(Edits.REFUSED);
            assertThat(refused.parameters()).as("its member").containsEntry("field", "title");
        }
        for (final String text : List.of(" \n ", "x".repeat(Edits.MOST_PART + 1))) {
            final Moves.Refused refused =
                    refusal(
                            () ->
                                    Edits.text(
                                            board,
                                            "r1",
                                            "AB1",
                                            new TextEdit(
                                                    TextEdit.Kind.ISSUE,
                                                    null,
                                                    Map.of(TextEdit.Part.PLAN, text),
                                                    Set.of())));
            assertThat(refused.parameters()).as("the part refused").containsEntry("field", "plan");
        }
        assertThat(
                        refusal(
                                        () ->
                                                Edits.text(
                                                        board,
                                                        "r1",
                                                        "ST1",
                                                        new TextEdit(
                                                                TextEdit.Kind.STORY,
                                                                null,
                                                                Map.of(),
                                                                Set.of())))
                                .parameters())
                .as("an edit that names nothing")
                .containsEntry("reason", "The body names no title and no part to write.");
    }

    @Test
    void aTitleIsTakenWithoutTheSpacesAroundItAndThePartsAsGiven() {
        // Spaces kept around a title would show on the tracker as a title nobody wrote.
        final TextEdit checked =
                Edits.text(
                        board,
                        "r1",
                        "ST1",
                        new TextEdit(
                                TextEdit.Kind.STORY,
                                "  A story  ",
                                Map.of(TextEdit.Part.GAIN, "More."),
                                Set.of(TextEdit.Part.TEST)));

        assertThat(checked)
                .as("the change as checked")
                .isEqualTo(
                        new TextEdit(
                                TextEdit.Kind.STORY,
                                "A story",
                                Map.of(TextEdit.Part.GAIN, "More."),
                                Set.of(TextEdit.Part.TEST)));
    }

    @Test
    void anEditOfAnIdOnTwoIssuesOrOfAnIssueHeldBackIsRefusedWithItsWords() {
        // Saved by id, the edit would write whichever copy the store found first, or build on a
        // hand edit the planner cannot hold.
        final Board twice =
                new Board(
                        new Revision("r1"),
                        new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                        List.of(story("ST1", List.of("AB1"))),
                        List.of(
                                issue("AB1", null, List.of())
                                        .withWarnings(List.of(Warning.idTwice())),
                                new Issue(
                                                "AB1",
                                                "porelo-other",
                                                "Issue AB1",
                                                "Idea",
                                                null,
                                                List.of(),
                                                null,
                                                null,
                                                "")
                                        .withWarnings(List.of(Warning.idTwice())),
                                issue("AB2", null, List.of())
                                        .withWarnings(List.of(Warning.parentOutsideProject()))),
                        new Work(Map.of(), List.of()),
                        List.of("porelo-store", "porelo-other"));
        final String words =
                "AB1 names an issue in porelo-store and porelo-other; it can be neither worked nor"
                        + " moved until one id is changed.";
        for (final Runnable edit :
                List.<Runnable>of(
                        () -> Edits.fields(twice, "r1", "AB1", SIZE),
                        () -> Edits.blockedBy(twice, "r1", "AB1", List.of()),
                        () -> Edits.text(twice, "r1", "AB1", title(TextEdit.Kind.ISSUE, "New")),
                        () -> Edits.storyIssues(twice, "r1", "ST1", List.of("AB1")),
                        () -> Edits.comment(twice, "AB1", "A note."))) {
            final Moves.Refused refused = refusal(edit);
            assertThat(refused.name())
                    .as("an edit refusal, not a missing issue")
                    .isEqualTo(Edits.REFUSED);
            assertThat(refused.getMessage()).as("naming both repositories").isEqualTo(words);
        }
        assertThat(refusal(() -> Edits.fields(twice, "r1", "AB2", SIZE)).getMessage())
                .as("an issue warned about its parent")
                .isEqualTo("AB2: " + Warning.parentOutsideProject().text());
    }

    @Test
    void anEditThatAddsOrRemovesAHeldIssueIsRefusedWithItsWords() {
        // Harm: a story's list or an issue's blockers written over an issue held back, so the
        // planner's own link builds on a hand edit it cannot hold, against the contract.
        final Board held =
                HeldBoards.warned(
                        HeldBoards.warned(
                                HeldBoards.warned(board, "AB2", Warning.dependsOnUnplaced()),
                                "AB4",
                                Warning.stateOutsideWorkflow()),
                        "AB5",
                        Warning.blockedByStory());
        final List<Map.Entry<String, Runnable>> edits =
                List.of(
                        Map.entry(
                                "AB5", () -> Edits.storyIssues(held, "r1", "ST3", List.of("AB5"))),
                        Map.entry(
                                "AB2", () -> Edits.storyIssues(held, "r1", "ST1", List.of("AB1"))),
                        Map.entry("AB5", () -> Edits.blockedBy(held, "r1", "AB1", List.of("AB5"))),
                        Map.entry("AB4", () -> Edits.blockedBy(held, "r1", "AB3", List.of())));
        for (final Map.Entry<String, Runnable> edit : edits) {
            final String id = edit.getKey();
            final Moves.Refused refused = refusal(edit.getValue());
            assertThat(refused.name()).as("the edit naming " + id).isEqualTo(Edits.REFUSED);
            assertThat(refused.getMessage())
                    .as("the held issue's warning, for " + id)
                    .isEqualTo(id + ": " + held.issue(id).orElseThrow().warnings().get(0).text());
        }
        assertThat(Edits.storyIssues(held, "r1", "ST1", List.of("AB2", "AB1")))
                .as("a held issue kept in the list, only reordered, is neither added nor removed")
                .isTrue();
    }

    @Test
    void anEditOfAnIssueCarryingAnyKindTheGitHubStoreWarnsWithIsRefused() {
        // Harm: a hand edit GitHub's store shows warned - a story type in another repository, a
        // cut parent or blocker, an item read in part - built on by the planner's own write.
        for (final Warning warning : HeldBoards.HAND_EDITS) {
            final Board held = HeldBoards.warned(board, "AB5", warning);
            assertThat(refusal(() -> Edits.fields(held, "r1", "AB5", SIZE)).getMessage())
                    .as("an edit of an issue warned " + warning.kind())
                    .isEqualTo("AB5: " + warning.text());
        }
    }

    @Test
    void anAddOfAnIssueTheBoardDoesNotOfferIsRefusedAndAnOfferedOneIsTaken() {
        // Harm: the add took any issue the window named - one on the board, one never offered -
        // and wrote it to the project, or one against an old board.
        final org.fuin.porelo.planner.store.UnlistedIssue made =
                new org.fuin.porelo.planner.store.UnlistedIssue(
                        "porelo-store#7",
                        "porelo-store",
                        "Made by hand",
                        org.fuin.porelo.planner.store.UnlistedIssue.Kind.ISSUE,
                        java.net.URI.create(MADE_LINK));
        final Board offering =
                new Board(
                        new Revision("r1"),
                        new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                        List.of(),
                        List.of(
                                new Issue(
                                        "porelo-store#3",
                                        "porelo-store",
                                        "On the board",
                                        "Idea",
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        "")),
                        new Work(Map.of(), List.of()),
                        List.of("porelo-store"),
                        List.of(),
                        "porelo-ai",
                        List.of(made));

        assertThat(Edits.unlisted(offering, "r1", "porelo-store#7"))
                .as("the offered issue")
                .isEqualTo(made);
        for (final String id : List.of("porelo-store#3", "porelo-store#8")) {
            final Moves.Refused refused = refusal(() -> Edits.unlisted(offering, "r1", id));
            assertThat(refused.name()).as("the refusal of %s", id).isEqualTo("NotUnlisted");
            assertThat(refused.parameters()).as("what it names").isEqualTo(Map.of("issue", id));
        }
        assertThat(
                        catchThrowableOfType(
                                StoreException.StaleRevision.class,
                                () -> Edits.unlisted(offering, "r0", "porelo-store#7")))
                .as("an add against an old board")
                .isNotNull();
    }

    private static Moves.Refused refusal(final Runnable edit) {
        final Moves.Refused refused = catchThrowableOfType(Moves.Refused.class, edit::run);
        assertThat(refused).as("the edit is refused").isNotNull();
        return refused;
    }
}
