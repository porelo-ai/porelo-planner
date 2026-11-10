package org.fuin.porelo.planner.app;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Who may reach the planner: its owner, on the loopback address, with the token. */
@Configuration(proxyBeanMethods = false)
public class AccessConfiguration {

    /**
     * The token of this start.
     *
     * @return A new token.
     */
    @Bean
    public AccessToken accessToken() {
        return AccessToken.random();
    }

    /**
     * The address file, its lock taken at once, so a second planner stops before it listens.
     *
     * @param runtimeDirectory The account's runtime directory.
     * @return The address file, written once the server listens and deleted when the planner stops.
     */
    @Bean(destroyMethod = "close")
    public AddressFile addressFile(
            @Value("${planner.runtime-directory:${XDG_RUNTIME_DIR:}}")
                    final String runtimeDirectory) {
        return AddressFile.open(runtimeDirectory);
    }

    /**
     * Writes the address file once the server listens, so a window reading it can call at once.
     *
     * @param file The address file.
     * @param token The token.
     * @return The listener.
     */
    @Bean
    public ApplicationListener<WebServerInitializedEvent> addressPublisher(
            final AddressFile file, final AccessToken token) {
        return event -> file.publish(event.getWebServer().getPort(), token);
    }

    /**
     * The token check, ahead of everything else, on every dispatch.
     *
     * @param token The token.
     * @return Its registration.
     */
    @Bean
    public FilterRegistrationBean<AccessFilter> accessFilter(final AccessToken token) {
        final FilterRegistrationBean<AccessFilter> registration =
                new FilterRegistrationBean<>(new AccessFilter(token));
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(
                DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * The loopback check of the server's address, before it binds.
     *
     * @param address {@code server.address}.
     * @return The check.
     */
    @Bean
    public LoopbackBinding loopbackBinding(@Value("${server.address:}") final String address) {
        return new LoopbackBinding(address);
    }
}
