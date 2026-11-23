package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An item's version: its own stored values, nothing it takes from elsewhere. */
class VersionTest {

    private static Issue issue(final String title, final String text) {
        return new Issue(
                "PL1", "porelo-store", title, "Analyzed", null, List.of(), null, null, text);
    }

    private static Story story(final String state, final boolean given) {
        return new Story(
                "ST1",
                "A story",
                state,
                given,
                "High",
                null,
                List.of("PL1"),
                "Its text.",
                null,
                true,
                null,
                List.of());
    }

    @Test
    void valuesThatOnlyShiftTheirBordersAreTwoVersions() {
        // Harm: two different items share a version, so a change to one goes unseen at a write.
        assertThat(Version.of("ab", "c")).isNotEqualTo(Version.of("a", "bc"));
        assertThat(Version.of((Object) null)).isNotEqualTo(Version.of("null"));
        assertThat(Version.of(List.of("a", "b"))).isNotEqualTo(Version.of("a", "b"));
        assertThat(Version.of(List.of())).isNotEqualTo(Version.of(""));
        assertThat(Version.of("a", 1, List.of("b"))).isEqualTo(Version.of("a", 1, List.of("b")));
        assertThat(Version.of("a").value()).matches("[0-9a-f]{64}");
    }

    @Test
    void anEmptyVersionIsRefused() {
        assertThatThrownBy(() -> new Version(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIssuesVersionFollowsItsOwnStoredStateOnly() {
        // Harm: a write is refused for a change the issue never had, or let through over one it
        // had.
        final Issue issue = issue("A title", "Its text.");
        assertThat(issue("A title", "Its text.").version()).isEqualTo(issue.version());
        assertThat(issue("Another title", "Its text.").version()).isNotEqualTo(issue.version());
        assertThat(issue("A title", "Other text.").version()).isNotEqualTo(issue.version());
        assertThat(issue.withWorkerAndRisk(Worker.MANUAL, Risk.HARM).version())
                .as("a worker and risk taken from its story")
                .isEqualTo(issue.version());
        assertThat(issue.withWarnings(List.of(Warning.unknownWorker())).version())
                .as("a warning the read adds")
                .isEqualTo(issue.version());
        final Version stored = Version.of("as the store keeps it");
        assertThat(issue.withVersion(stored).withWarnings(List.of()).version()).isEqualTo(stored);
    }

    @Test
    void aStorysStateItsIssuesGiveIsNoPartOfItsVersion() {
        // Harm: a story's write is refused because one of its issues moved, which the design
        // forbids.
        assertThat(story("Implementing", false).version())
                .isEqualTo(story("Analyzed", false).version());
        assertThat(story("Implementing", true).version())
                .isNotEqualTo(story("Analyzed", true).version());
        assertThat(story("Analyzed", true).version())
                .isNotEqualTo(story("Analyzed", false).version());
    }
}
