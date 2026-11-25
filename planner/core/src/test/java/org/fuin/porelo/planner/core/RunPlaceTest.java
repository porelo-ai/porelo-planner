package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A run's place holds exactly one place, each named as Start takes it. */
class RunPlaceTest {

    // Prevents a place naming none or two places from reaching Start, which refuses it.
    @Test
    void aPlaceNamingNoneOrTwoPlacesOrAFalseOwnPullRequestIsRefused() {
        assertThatThrownBy(() -> new RunPlace(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunPlace(new RunPlace.GatePush(null, "cart"), "b", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunPlace(null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Prevents a value Porelo's command line would read as an option, or one the window's
    // contract does not allow, from being handed on.
    @ParameterizedTest
    @ValueSource(strings = {"-p", "--json", "a..b", "b/", "x.lock", "a b", "a~1", ""})
    void aBranchThatIsNoBranchIsRefused(final String branch) {
        assertThatThrownBy(() -> new RunPlace(null, branch, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-p", "--json", "a/b", "a b", ""})
    void aTaskNameThatIsNoTaskLabelIsRefused(final String name) {
        assertThatThrownBy(() -> RunPlace.gate(null, name))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Shop", "-shop", "shop docs", ""})
    void aRepositoryThatIsNoRepositoryNameIsRefused(final String repository) {
        assertThatThrownBy(() -> RunPlace.gate(repository, "cart"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void soundPlacesAreKept() {
        assertThat(RunPlace.gate("shop-docs", "porelo_cart.2").gatePush())
                .isEqualTo(new RunPlace.GatePush("shop-docs", "porelo_cart.2"));
        assertThat(new RunPlace(null, "feature/ä-x", null).pullRequest()).isEqualTo("feature/ä-x");
    }
}
