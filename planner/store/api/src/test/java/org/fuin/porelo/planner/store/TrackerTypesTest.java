package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrackerTypesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://github.com",
                "https://user@github.com",
                "https://github.com:8443",
                "https://github.com/org",
                "https://github.com?x=1",
                "https://github.com#x",
                "https://GitHub.com",
                "file:///etc"
            })
    void aTokenIsAskedForAnHttpsOriginAlone(final String address) {
        // Harm: the person's token asked for, or sent to, an address a changed file slipped in.
        assertThatThrownBy(() -> TokenSource.checkedOrigin(URI.create(address)))
                .as("the address %s", address)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theForgesOwnOriginIsTaken() {
        // Harm: no board at all for the one address a token is meant for.
        assertThat(TokenSource.checkedOrigin(URI.create("https://github.com")))
                .as("GitHub's own origin")
                .hasToString("https://github.com");
    }

    @Test
    void aStageWithANumberThatWouldSpoilTheSumsIsRefused() {
        // Harm: a hand-edited or broken stage turning the velocity's minutes negative or infinite.
        for (final double minutes :
                new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, 100_001}) {
            assertThatThrownBy(
                            () ->
                                    new StageEntry(
                                            "task-1:1",
                                            "implement",
                                            "done",
                                            "agent",
                                            null,
                                            null,
                                            minutes,
                                            0,
                                            Map.of()))
                    .as("a stage of %s minutes", minutes)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () ->
                                new StageEntry(
                                        "task-1:1",
                                        "implement",
                                        "done",
                                        "agent",
                                        null,
                                        null,
                                        1,
                                        0,
                                        Map.of("claude_input", -5L)))
                .as("a stage with negative tokens")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
