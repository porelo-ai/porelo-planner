package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A new issue's fields checked before the store writes a file from them. */
class NewIssuesTest {

    private final Board board =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                    List.of(),
                    List.of(),
                    new Work(Map.of(), List.of()),
                    List.of("porelo-store", "porelo-other"));

    @Test
    void anIssueWithinEveryBoundIsTakenWithItsTitleTrimmed() {
        // A bound refused one character early would refuse what the window allows.
        final NewIssue issue =
                NewIssues.check(
                        board, "porelo-other", " " + "T".repeat(120) + " ", "W".repeat(4000), "XL");

        assertThat(issue)
                .as("the title without the spaces around it")
                .isEqualTo(new NewIssue("porelo-other", "T".repeat(120), "W".repeat(4000), "XL"));
        assertThat(NewIssues.check(board, "porelo-store", "T", "W", null).size())
                .as("a size is optional")
                .isNull();
    }

    @Test
    void eachFieldOutOfBoundsIsRefusedNamingThatField() {
        // A title with a line break would break the issue's heading and the head the index reads.
        assertThat(field("porelo-elsewhere", "T", "W", null)).isEqualTo("repository");
        assertThat(field(null, "T", "W", null)).isEqualTo("repository");
        assertThat(field("porelo-store", "A\nB", "W", null)).isEqualTo("title");
        assertThat(field("porelo-store", "A\u0007", "W", null)).isEqualTo("title");
        assertThat(field("porelo-store", "   ", "W", null)).isEqualTo("title");
        assertThat(field("porelo-store", "T".repeat(121), "W", null)).isEqualTo("title");
        assertThat(field("porelo-store", "T", "", null)).isEqualTo("mustBeTrue");
        assertThat(field("porelo-store", "T", " \n ", null)).isEqualTo("mustBeTrue");
        assertThat(field("porelo-store", "T", "W".repeat(4001), null)).isEqualTo("mustBeTrue");
        assertThat(field("porelo-store", "T", "W", "XXL")).isEqualTo("size");
    }

    @Test
    void aRepositoryOutsideTheProjectIsNotRepeatedInTheRefusal() {
        // A refusal repeating the body would show whatever a caller put there, a secret too.
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () -> NewIssues.check(board, "token123secret", "T", "W", null));

        assertThat(refused.name()).as("the refusal's name").isEqualTo("IssueNotAllowed");
        assertThat(refused.getMessage() + refused.parameters())
                .as("the words and parameters")
                .doesNotContain("token123secret");
    }

    private String field(
            final @Nullable String repository,
            final @Nullable String title,
            final @Nullable String mustBeTrue,
            final @Nullable String size) {
        final Moves.Refused refused =
                catchThrowableOfType(
                        Moves.Refused.class,
                        () -> NewIssues.check(board, repository, title, mustBeTrue, size));
        assertThat(refused).as("refused: %s %s %s", repository, title, size).isNotNull();
        return refused.parameters().get("field");
    }
}
