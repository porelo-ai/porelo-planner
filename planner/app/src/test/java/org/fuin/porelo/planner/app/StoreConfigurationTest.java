package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The sources the planner refuses before it touches Git or the cache. */
class StoreConfigurationTest {

    private final Projects projects =
            StoreConfiguration.fromGit(StoreConfiguration.gitProjects(Map.of()));

    @Test
    void aSourceThatIsNoCloneUrlIsRefusedByName() {
        // Git would read a local path or an option where the planner expects a remote.
        assertThatThrownBy(() -> projects.put("alpha", "/home/person/git/p", null))
                .as("a local path")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal -> {
                            assertThat(refusal.name()).as("its name").isEqualTo("NotACloneUrl");
                            assertThat(refusal.parameters())
                                    .as("what it names")
                                    .containsEntry("project", "alpha");
                        });
        assertThatThrownBy(() -> projects.put("alpha", "https://github.com/porelo-ai/My.git", null))
                .as("a URL whose last part is no repository name")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal ->
                                assertThat(refusal.name())
                                        .as("its name")
                                        .isEqualTo("NotACloneUrl"));
    }

    @Test
    void aBranchGitWouldReadAsSomethingElseIsRefusedByName() {
        // A branch like '--upload-pack=x' or '../x' would reach git's command line.
        assertThatThrownBy(
                        () ->
                                projects.put(
                                        "alpha",
                                        "https://github.com/porelo-ai/porelo-project.git",
                                        "--upload-pack=x"))
                .as("a branch that is an option")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal -> {
                            assertThat(refusal.name()).as("its name").isEqualTo("InvalidName");
                            assertThat(refusal.parameters())
                                    .as("what it names")
                                    .containsEntry("project", "alpha");
                        });
    }
}
