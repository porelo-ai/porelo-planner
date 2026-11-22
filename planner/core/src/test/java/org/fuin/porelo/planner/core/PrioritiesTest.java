package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.WorkRule;
import org.junit.jupiter.api.Test;

class PrioritiesTest {

    @Test
    void aPriorityOfAStoryHeldBackOrNamedTwiceIsRefusedWithItsWords() {
        // A priority written onto a story the store could not fully read builds on a hand edit,
        // and one written for an id naming two stories lands in whichever file is found.
        final Board plain = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        final Story story = plain.stories().getFirst();

        final Board held = with(plain, List.of(story.withWarnings(List.of(Warning.wrongParent()))));
        assertThatThrownBy(() -> Priorities.check(held, "ST1", "Low"))
                .as("a story held back")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage("ST1: " + Warning.wrongParent().text())
                .satisfies(
                        error ->
                                assertThat(((Moves.Refused) error).name())
                                        .isEqualTo("MoveNotAllowed"));

        final Story copy = story.withWarnings(List.of(Warning.idTwice()));
        final Board twice = with(plain, List.of(copy, copy));
        assertThatThrownBy(() -> Priorities.check(twice, "ST1", "Low"))
                .as("an id naming two stories")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage(
                        "ST1 names two stories; it can be neither worked nor moved until one id"
                                + " is changed.")
                .satisfies(
                        error ->
                                assertThat(((Moves.Refused) error).name())
                                        .isEqualTo("MoveNotAllowed"));

        final Story warned = story.withWarnings(List.of(Warning.bodyNotSectioned()));
        assertThat(Priorities.check(with(plain, List.of(warned)), "ST1", "Low").none())
                .as("a warning that holds nothing leaves the priority free")
                .isFalse();
    }

    private static Board with(final Board board, final List<Story> stories) {
        return new Board(
                board.revision(),
                board.workflow(),
                stories,
                board.issues(),
                board.work(),
                board.repositories());
    }
}
