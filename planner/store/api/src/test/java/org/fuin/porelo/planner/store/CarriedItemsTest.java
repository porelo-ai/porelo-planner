package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Stories and issues a tracker keeps: their references and the old ids they carry. */
class CarriedItemsTest {

    private static Issue issue(
            final String id,
            final String repository,
            final @Nullable String parent,
            final @Nullable String alias) {
        return new Issue(
                id,
                repository,
                "An issue",
                "Backlog",
                null,
                List.of(),
                parent,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY,
                alias);
    }

    private static Story story(final String id, final @Nullable String alias) {
        return new Story(
                id,
                "A story",
                "Backlog",
                false,
                "High",
                null,
                List.of("porelo-x#2"),
                "",
                null,
                true,
                alias);
    }

    @Test
    void aCarriedOverItemKeepsItsOldIdBesideItsReference() {
        assertThat(story("porelo-x#1", "ST1").alias()).as("the story's old id").isEqualTo("ST1");
        assertThat(issue("porelo-x#2", "porelo-x", null, "XY02").alias())
                .as("the issue's old id")
                .isEqualTo("XY02");
        assertThat(issue("porelo-x#3", "porelo-x", "porelo-x#2", "XY02.1").parent())
                .as("a sub-issue's parent, a reference in its repository")
                .isEqualTo("porelo-x#2");
        assertThat(
                        new Issue(
                                        "XY02",
                                        "porelo-x",
                                        "An issue",
                                        "Backlog",
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        "",
                                        Worker.AGENT,
                                        Risk.ORDINARY)
                                .alias())
                .as("an issue made without an alias")
                .isNull();
    }

    @Test
    void anAliasOnAnItemTheTrackerDoesNotKeepIsRefused() {
        // Harm: a Git store item with an alias answers under a second id and could take another's
        // moves.
        assertThatThrownBy(() -> story("ST1", "ST2"))
                .as("a story of the Git store with an alias")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue("XY02", "porelo-x", null, "XY03"))
                .as("an issue of the Git store with an alias")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> story("porelo-x#1", "porelo-x#4"))
                .as("a story whose alias is a reference")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue("porelo-x#2", "porelo-x", null, "porelo-x#4"))
                .as("an issue whose alias is a reference")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIssueReferenceIntoAnotherRepositoryIsRefused() {
        // Harm: an issue whose reference names another repository is read and written there.
        assertThatThrownBy(() -> issue("porelo-y#2", "porelo-x", null, null))
                .as("an issue of porelo-x named by a reference into porelo-y")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue("porelo-x#3", "porelo-x", "porelo-y#2", null))
                .as("a sub-issue whose parent stands in another repository")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue("porelo-x#3", "porelo-x", "XY02", null))
                .as("a sub-issue reference under a Git store parent")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue("XY02.1", "porelo-x", "porelo-x#2", null))
                .as("a Git store sub-issue under a reference")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
