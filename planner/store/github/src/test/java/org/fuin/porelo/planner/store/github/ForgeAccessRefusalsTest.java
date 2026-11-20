package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.fuin.porelo.forge.github.GitHubRefusals;
import org.fuin.porelo.planner.store.StoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgeAccessRefusalsTest {

    private static final String TOKEN = "invented-token-0123456789";

    private final ForgeAccessRefusals refusals =
            new ForgeAccessRefusals(URI.create("https://github.com"));

    private StoreException refusal(final RuntimeException failure) {
        return refusals.refusal("example-project", failure, TOKEN);
    }

    @Test
    void aRefusedTokenNamesTheForgeAndTheRightGitHubAccepts() {
        // Harm: the window offers no token, or one for another forge or right than GitHub asked.
        assertThat(
                        refusal(
                                GitHubRefusals.answered(
                                        403,
                                        "Resource not accessible by personal access token",
                                        false,
                                        "organization_projects=write; issues=write")))
                .as("a 403 naming the permission it accepts")
                .isInstanceOfSatisfying(
                        StoreException.NoForgeAccess.class,
                        refused -> {
                            assertThat(refused.subject())
                                    .as("the forge")
                                    .isEqualTo("https://github.com");
                            assertThat(refused.right())
                                    .as("the first right GitHub accepts")
                                    .isEqualTo("Organization projects");
                        });
        assertThat(refusal(GitHubRefusals.answered(401, "Bad credentials", false, null)))
                .as("a 401 naming no permission")
                .isInstanceOfSatisfying(
                        StoreException.NoForgeAccess.class,
                        refused -> assertThat(refused.right()).as("no right").isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"x\nforged line=write", "Issues=write", "issues rm=write", ""})
    void aPermissionThatIsNoPlainNameIsLeftOut(final String accepted) {
        // Harm: GitHub's words forging a line in the log or a message in the window.
        assertThat(refusal(GitHubRefusals.answered(403, "refused", false, accepted)))
                .as("a 403 accepting %s", accepted)
                .isInstanceOfSatisfying(
                        StoreException.NoForgeAccess.class,
                        refused -> assertThat(refused.right()).as("the right").isNull());
    }

    @Test
    void aRateLimitedRefusalIsNoMissingAccess() {
        // Harm: the person asked to hand on another token when GitHub only asked to wait.
        assertThat(refusal(GitHubRefusals.answered(403, "secondary rate limit", true, null)))
                .as("a 403 for the rate limit")
                .isInstanceOf(StoreException.Unreachable.class);
    }

    @Test
    void aRefusalAfterAPartOfTheSaveWasWrittenIsPartlySaved() {
        // Harm: the window says nothing was saved, and the save is made again once the token is
        // handed on, writing its first part twice.
        assertThat(
                        refusal(
                                new BoardWriter.AfterWriting(
                                        GitHubRefusals.answered(
                                                403, "refused", false, "issues=write"))))
                .as("a 403 after an earlier call of the save wrote")
                .isInstanceOf(StoreException.PartlySaved.class)
                .isNotInstanceOf(StoreException.NoForgeAccess.class);
        assertThat(
                        refusal(
                                new BoardWriter.AfterWriting(
                                        new IllegalStateException("reset for Bearer " + TOKEN))))
                .as("another failure after an earlier call of the save wrote")
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void anotherFailureIsGitHubUnreachableWithoutTheToken() {
        // Harm: the person's token shown in the window or written to the log.
        assertThat(
                        refusal(
                                GitHubRefusals.answered(
                                        502, "bad gateway for Bearer " + TOKEN, false, null)))
                .as("a 502 whose words name the token")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageNotContaining(TOKEN);
        assertThat(refusal(new StoreException.Busy("example-project")))
                .as("a refusal of the store's own")
                .isInstanceOf(StoreException.Busy.class);
    }

    @Test
    void aFailureAfterAPartWasWrittenNeverShowsItsWordsWhenNoTokenCouldBeTaken() {
        // Harm: the token in a failure's words shown in the window or the log once the vault
        // gives no token to scrub it with.
        assertThat(
                        refusals.refusal(
                                "example-project",
                                new BoardWriter.AfterWriting(
                                        new IllegalStateException("reset for Bearer " + TOKEN)),
                                ""))
                .as("a failure after a write, scrubbed with no token")
                .isInstanceOf(StoreException.PartlySaved.class)
                .hasMessageNotContaining(TOKEN);
    }
}
