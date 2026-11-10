package org.fuin.porelo.planner.app;

import java.net.InetAddress;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;

/**
 * Binds the server to the loopback address it is configured with, and refuses any other before the
 * server binds: an empty {@code server.address} would otherwise listen on every interface.
 */
public final class LoopbackBinding
        implements WebServerFactoryCustomizer<ConfigurableWebServerFactory>, Ordered {

    private final String address;

    /**
     * Makes the check.
     *
     * @param address {@code server.address} as configured, empty when it is not.
     */
    public LoopbackBinding(final String address) {
        this.address = address;
    }

    @Override
    public void customize(final ConfigurableWebServerFactory factory) {
        factory.setAddress(loopback(address));
    }

    /** After Spring's own customizer, so this setting is the last. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Reads a configured address, which must be a literal loopback address.
     *
     * @param configured {@code server.address}.
     * @return Its address.
     * @throws IllegalStateException When it is empty, a name or not a loopback address.
     */
    static InetAddress loopback(final String configured) {
        final InetAddress parsed;
        try {
            parsed = InetAddress.ofLiteral(configured.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "server.address is '" + configured + "', not a loopback address", e);
        }
        if (!parsed.isLoopbackAddress()) {
            throw new IllegalStateException(
                    "server.address is '"
                            + configured
                            + "'; the planner listens only on a loopback address");
        }
        return parsed;
    }
}
