package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessFilterTest {

    private final AccessToken token = AccessToken.random();

    @Test
    void theRightTokenIsLetThrough() throws Exception {
        // The owner's window must get through, or the planner is of no use.
        final MockFilterChain chain = new MockFilterChain();
        new AccessFilter(token)
                .doFilter(request("Bearer " + token.text()), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("the request handed on with the right token").isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "Bearer wrong", "same length", "Basic", "longer", "lower case"})
    void aCallWithoutTheRightTokenIsRefusedAndHandedOnNowhere(final String kind) throws Exception {
        // Any program of any account on this computer can reach the loopback address.
        final MockFilterChain chain = new MockFilterChain();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        new AccessFilter(token).doFilter(request(header(kind)), response, chain);
        assertThat(chain.getRequest()).as("the request handed on with %s", kind).isNull();
        assertThat(response.getStatus()).as("the status with %s", kind).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("the challenge with %s", kind)
                .isEqualTo("Bearer");
        assertThat(response.getContentAsByteArray())
                .as("the body with %s, which must say nothing", kind)
                .isEmpty();
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .as("a CORS header with %s", kind)
                .isNull();
    }

    private @Nullable String header(final String kind) {
        final String text = token.text();
        return switch (kind) {
            case "none" -> null;
            case "same length" ->
                    "Bearer " + (text.charAt(0) == 'A' ? "B" : "A") + text.substring(1);
            case "Basic" -> "Basic " + text;
            case "longer" -> "Bearer " + text + "x";
            case "lower case" -> "bearer " + text;
            default -> kind;
        };
    }

    private static MockHttpServletRequest request(final @Nullable String authorization) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader("Origin", "http://example.org");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
