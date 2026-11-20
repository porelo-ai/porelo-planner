package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanningStoreTest {

    private static final Board BOARD =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea"), List.of(), List.of()),
                    List.of(
                            new Story(
                                    "ST1", "A story", "Idea", true, "High", null, List.of(), "",
                                    null)),
                    List.of(
                            new Issue(
                                    "XY03",
                                    "porelo-planner",
                                    "An issue",
                                    "Idea",
                                    null,
                                    List.of(),
                                    null,
                                    null,
                                    "")),
                    new Work(Map.of(), List.of()));

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

    @Test
    void aStoryOrIssueIsAnsweredFromTheBoard() {
        assertThat(STORE.story("ST1").title()).as("the board's story").isEqualTo("A story");
        assertThat(STORE.issue("XY03").title()).as("the board's issue").isEqualTo("An issue");
    }

    @Test
    void anIdTheBoardDoesNotHaveIsItsOwnRefusal() {
        // The window answers 404 for these, and must not mistake them for a store that is down.
        assertThatThrownBy(() -> STORE.story("ST2"))
                .as("a missing story")
                .isInstanceOf(StoreException.NoSuchStory.class)
                .hasMessage("No story ST2");
        assertThatThrownBy(() -> STORE.issue("XY04"))
                .as("a missing issue")
                .isInstanceOf(StoreException.NoSuchIssue.class)
                .hasMessage("No issue XY04");
    }

    @Test
    void aRefusedLoginNamesTheRepositoryAndCannotCarryACredential() {
        // A clone URL with user info must never reach the window's answer or a log.
        assertThat(new StoreException.CredentialRefused("porelo-planner"))
                .as("the refusal names the repository")
                .hasMessageContaining("porelo-planner");
        assertThatThrownBy(() -> new StoreException.CredentialRefused("user:secret@host"))
                .as("a URL with user info is no repository name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret@");
    }

    @Test
    void anEditAStoreDoesNotOfferIsRefusedAsNotSavableNamingTheItem() {
        // A store that does not offer an edit must say so, never answer as if it were written.
        final Fields size = new Fields(java.util.Set.of(Fields.Field.SIZE), "M", null, null, null);
        for (final Runnable edit :
                List.<Runnable>of(
                        () -> STORE.setFields(BOARD.revision(), "XY03", size),
                        () -> STORE.setStoryIssues(BOARD.revision(), "ST1", List.of("XY03")),
                        () -> STORE.setBlockedBy(BOARD.revision(), "XY03", List.of()),
                        () -> STORE.comment("XY03", "A comment."))) {
            assertThatThrownBy(edit::run)
                    .as("an edit the store does not offer")
                    .isInstanceOfSatisfying(
                            StoreException.NotSavable.class,
                            refusal ->
                                    assertThat(refusal.subject())
                                            .as("the item named")
                                            .isIn("XY03", "ST1"));
        }
    }
}
