package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoreExceptionTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://github.com",
                "https://user:secret@github.com",
                "https://user:se cret@github.com",
                "https://github.com/org",
                "https://github.com?token=x",
                "https://github.com#x",
                "https://github.com:8443",
                "https://GitHub.com",
                "github.com"
            })
    void aForgeAccessRefusalNamesAPlainHttpsOriginAlone(final String address) {
        // Harm: a credential in an address shown to the window, or a token handed on for another
        // address.
        assertThatThrownBy(() -> new StoreException.NoForgeAccess(address, null))
                .as("the address %s", address)
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(
                        refused -> {
                            for (Throwable t = refused; t != null; t = t.getCause()) {
                                assertThat(String.valueOf(t.getMessage()))
                                        .as("the words of the refusal and its causes")
                                        .doesNotContain("secret")
                                        .doesNotContain("cret@");
                            }
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "Projects\nforged line",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "1Issues",
                "Issues; rm"
            })
    void aForgeAccessRefusalNamesAPlainRightAlone(final String right) {
        // Harm: words of the forge's answer forging a log line or a message in the window.
        assertThatThrownBy(() -> new StoreException.NoForgeAccess("https://github.com", right))
                .as("the right %s", right)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Projects", "Issues", "Organization projects"})
    void aForgeAccessRefusalKeepsTheAddressAndTheRight(final String right) {
        // Harm: the window offered a token for another forge or right than the one refused.
        final StoreException.NoForgeAccess refusal =
                new StoreException.NoForgeAccess("https://github.com", right);
        assertThat(refusal.subject()).as("the forge's address").isEqualTo("https://github.com");
        assertThat(refusal.right()).as("the right named").isEqualTo(right);
    }
}
