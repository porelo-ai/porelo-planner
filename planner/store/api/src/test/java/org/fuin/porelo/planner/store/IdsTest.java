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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " t1",
                "t1 ",
                "a b",
                "t`1",
                "t1\n## Forged",
                "t1\u2028x",
                "t1\u0085x",
                "t1\u202ex",
                "*t1*",
                "-t1",
            })
    void aTaskNameThatCouldBreakOrMarkUpALineOfAnIssueFileIsRefused(final String task) {
        // A task's name is written into a committed issue file; a break or a mark there forges a
        // line.
        assertThat(Ids.isTask(task)).as("'%s' is no task name", task).isFalse();
        assertThatThrownBy(() -> new NewIssue("porelo-other", "T", "W", null, Worker.AGENT, task))
                .as("no issue is made naming '%s'", task)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRefusedTaskNameIsNeverRepeated() {
        // A forged name repeated into a log or an answer reads there as the planner's own words.
        assertThatThrownBy(() -> Ids.task("t1\u202eclaimed\u2028line"))
                .as("the refusal does not repeat what it refused")
                .hasMessageNotContaining("claimed")
                .hasMessageNotContaining("\u202e")
                .hasMessageNotContaining("\u2028");
    }

    @ParameterizedTest
    @ValueSource(strings = {"t1", "porelo-planner-task_7.2", "A"})
    void aTaskNameOfPorelosShapeIsTaken(final String task) {
        assertThat(new NewIssue("porelo-other", "T", "W", null, Worker.AGENT, task).task())
                .as("a real task name passes unchanged")
                .isEqualTo(task);
    }

    @ParameterizedTest
    @ValueSource(strings = {"porelo-planner#1", "porelo-ai.github.io#123456789", "a#9", "x_y-z#40"})
    void aTrackerReferenceIsTakenAsAStoryIdAndAnIssueId(final String id) {
        assertThat(Ids.story(id)).as("a tracker's story reference passes unchanged").isEqualTo(id);
        assertThat(Ids.issue(id)).as("a tracker's issue reference passes unchanged").isEqualTo(id);
        assertThat(Ids.repositoryOf(id))
                .as("the repository the reference names")
                .isEqualTo(id.substring(0, id.indexOf('#')));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "#1",
                "repo#",
                "repo#0",
                "repo#01",
                "repo#1234567890",
                "repo#-1",
                "repo#1#2",
                "repo#1.1",
                "Repo#1",
                "../repo#1",
                "a/b#1",
                "a..b#1",
                "..#1",
                ".repo#1",
                "-repo#1",
                "repo #1",
                "repo# 1",
                "repo#1 ",
                "repo#1\n",
                "repo\t#1",
                "re\u0000po#1",
                "repo#1/../x",
                "https://host/repo#1"
            })
    void aTrackerReferenceThatCouldReachAnotherPathOrItemIsRefused(final String id) {
        // Harm: a reference reaches a path and the tracker's API; one that leaves its repository or
        // reads as two items reads or writes another one.
        assertThat(Ids.isReference(id)).as("'%s' is no tracker reference", id).isFalse();
        assertThatThrownBy(() -> Ids.issue(id))
                .as("'%s' is no issue id", id)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ids.story(id))
                .as("'%s' is no story id", id)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTrackerReferenceWithARepositoryNameLongerThanANameIsRefused() {
        // Harm: a reference whose repository no project names reaches the tracker's API as given.
        assertThat(Ids.isReference("a".repeat(100) + "#1"))
                .as("a repository name of 100 characters")
                .isTrue();
        assertThatThrownBy(() -> Ids.issue("a".repeat(101) + "#1"))
                .as("a repository name of 101 characters")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"repo#1", "ST1.1", "xy1", "XY01#2", ""})
    void anAliasIsOnlyAGitStoreId(final String alias) {
        // Harm: an alias that is a reference names a second tracker item under the first one's id.
        assertThatThrownBy(() -> Ids.storyAlias(alias))
                .as("'%s' is no story alias", alias)
                .isInstanceOf(IllegalArgumentException.class);
        if (!"ST1.1".equals(alias)) {
            assertThatThrownBy(() -> Ids.issueAlias(alias))
                    .as("'%s' is no issue alias", alias)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Ids.storyAlias("ST12")).as("a story's old id").isEqualTo("ST12");
        assertThat(Ids.issueAlias("XY03.1")).as("an issue's old id").isEqualTo("XY03.1");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"ghp_secret0123456789", "https://user:secret@host/r#1", "XY\u001b[2Jsecret"})
    void aRefusedIdIsNeverShown(final String id) {
        // Harm: what a caller passed as an id may be a token, and a refusal reaches a log and an
        // answer.
        assertThatThrownBy(() -> Ids.issue(id))
                .as("the issue id's refusal does not repeat what it refused")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("\u001b");
        assertThatThrownBy(() -> Ids.story(id))
                .as("the story id's refusal does not repeat what it refused")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> Ids.issueAlias(id))
                .as("the alias's refusal does not repeat what it refused")
                .hasMessageNotContaining("secret");
    }
}
