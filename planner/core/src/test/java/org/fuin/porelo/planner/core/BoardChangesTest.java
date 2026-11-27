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
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
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
}
