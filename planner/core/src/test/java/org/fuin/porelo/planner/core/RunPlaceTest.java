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
    void aValuePoreloWouldReadAsAnOptionIsRefused(final String value) {
        assertThatThrownBy(() -> new RunPlace(null, value, null))
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
        assertThat(new RunPlace(null, "https://github.com/owner/repo/pull/12", null).pullRequest())
                .as("a pull request's https address is its place")
                .isEqualTo("https://github.com/owner/repo/pull/12");
    }

    /** Porelo refuses any other address at Start; the planner never offers one. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "feature/x",
                "http://github.com/owner/repo/pull/12",
                "https://user@github.com/owner/repo/pull/12",
                "https://github.com/owner/repo/pull/12?x=1",
                "https://github.com/owner/repo/pull/12#a",
                "https://github.com/owner/repo/pull/ä",
                "https:///owner/repo/pull/12",
                "https://github.com/owner/repo/pull/1 2",
                ""
            })
    void aPullRequestThatIsNoHttpsAddressIsRefused(final String address) {
        assertThatThrownBy(() -> new RunPlace(null, address, null))
                .as("%s is no pull request address Porelo takes", address)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPullRequestAddressLongerThanPoreloTakesIsRefused() {
        final String base = "https://github.com/owner/repo/pull/";
        final String fits = base + "1".repeat(RunPlace.MOST_ADDRESS - base.length());
        assertThat(new RunPlace(null, fits, null).pullRequest())
                .as("an address of the most characters is kept")
                .isEqualTo(fits);
        assertThatThrownBy(() -> new RunPlace(null, fits + "1", null))
                .as("one character more is refused")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
