package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Stories and issues a tracker keeps, known by their references. */
class TrackedItemsTest {

    private static Issue issue(
            final String id, final String repository, final @Nullable String parent) {
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
                Risk.ORDINARY);
    }

    @Test
    void aSubIssuesParentIsAReferenceInItsOwnRepository() {
        // Harm: a sub-issue read under a parent it does not stand under on the tracker.
        assertThat(issue("porelo-x#3", "porelo-x", "porelo-x#2").parent())
                .as("a sub-issue's parent, a reference in its repository")
                .isEqualTo("porelo-x#2");
    }

    @Test
    void anIssueReferenceIntoAnotherRepositoryIsRefused() {
        // Harm: an issue whose reference names another repository is read and written there.
        assertThatThrownBy(() -> issue("porelo-y#2", "porelo-x", null))
                .as("an issue of porelo-x named by a reference into porelo-y")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue("porelo-x#3", "porelo-x", "porelo-y#2"))
                .as("a sub-issue whose parent stands in another repository")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue("porelo-x#3", "porelo-x", "XY02"))
                .as("a sub-issue reference under a Git store parent")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue("XY02.1", "porelo-x", "porelo-x#2"))
                .as("a Git store sub-issue under a reference")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
