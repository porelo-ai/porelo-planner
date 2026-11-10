package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoopbackBindingTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0.0.0.0", "::", "192.0.2.1", "2001:db8::1", "localhost"})
    void anythingButALoopbackLiteralIsRefused(final String address) {
        // Listening on another address lets other computers on the network reach the planner; a
        // name could resolve to one.
        assertThatThrownBy(() -> LoopbackBinding.loopback(address))
                .as("server.address '%s'", address)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.address is '" + address + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1"})
    void aLoopbackLiteralIsTaken(final String address) {
        // Refusing the loopback address would leave the window without a planner.
        assertThat(LoopbackBinding.loopback(address).isLoopbackAddress())
                .as("the address bound for '%s'", address)
                .isTrue();
    }
}
