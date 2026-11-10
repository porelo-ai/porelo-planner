package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdsTest {

    @ParameterizedTest
    @ValueSource(strings = {"XY03", "Z08", "QZ63", "XY03.2", "ABCD12345"})
    void anIssueIdOfPrefixAndNumberIsTaken(final String id) {
        assertThat(Ids.issue(id)).as("a real issue id passes unchanged").isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "../XY03",
                "XY03/..",
                "pl03",
                "XY03.",
                "PL",
                "-XY03",
                "XY03\n",
                "XY03.1.2"
            })
    void anIssueIdThatCouldReachAnotherPathIsRefused(final String id) {
        // An id reaches a file name in the clone; one that leaves it reads or writes someone else's
        // file.
        assertThatThrownBy(() -> Ids.issue(id))
                .as("'%s' is no issue id", id)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ST", "st1", "ST1/../x", "XY03", "ST1.2"})
    void aStoryIdThatIsNotStAndANumberIsRefused(final String id) {
        assertThatThrownBy(() -> Ids.story(id))
                .as("'%s' is no story id", id)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"porelo-planner", "porelo-ai.github.io", "a"})
    void aRepositoryNameIsTaken(final String name) {
        assertThat(Ids.name(name)).as("a real repository name passes unchanged").isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "..",
                "a..b",
                "../x",
                "a/b",
                "-upload-pack=x",
                ".hidden",
                "Porelo",
                "user:secret@host",
                "a b",
                "a\u0000b"
            })
    void aNameThatCouldLeaveTheCacheOrReadAsAnOptionIsRefused(final String name) {
        // A name becomes a directory under the cache and an argument to git.
        assertThatThrownBy(() -> Ids.name(name))
                .as("'%s' is no name", name)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user:secret@host/r.git", "ssh://git@host/secret"})
    void aRefusedNameIsNeverShown(final String name) {
        // What is passed as a name may be a clone URL, and its user info a password.
        assertThatThrownBy(() -> Ids.name(name))
                .as("the refusal does not repeat what it refused")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("host");
    }

    @Test
    void aRefusedIdIsShownShortAndPrintable() {
        // A refusal reaches a log; a control character there rewrites the person's terminal.
        assertThatThrownBy(() -> Ids.issue("PL\u001b[2J" + "x".repeat(60)))
                .as("the refusal quotes a short, printable start of what it refused")
                .hasMessageNotContaining("\u001b")
                .hasMessageEndingWith("...'");
    }
}
