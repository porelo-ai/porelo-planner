package org.fuin.porelo.planner.app;

import jakarta.servlet.DispatcherType;
import java.nio.file.Path;
import org.fuin.porelo.planner.app.mock.MockMode;
import org.fuin.porelo.planner.store.git.LogText;
import org.fuin.porelo.planner.store.git.PrivateFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;

/** Who may reach the planner: its owner, on the loopback address, with the token. */
@Configuration(proxyBeanMethods = false)
public class AccessConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(AccessConfiguration.class);

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
     * @param mode Present only in the mock mode, which never writes where the person's window
     *     looks.
     * @return The address file, written once the server listens and deleted when the planner stops.
     */
    @Bean(destroyMethod = "close")
    public AddressFile addressFile(
            @Value("${planner.runtime-directory:${XDG_RUNTIME_DIR:}}")
                    final String runtimeDirectory,
            final ObjectProvider<MockMode> mode) {
        if (mode.getIfAvailable() != null) {
            AddressFile.requireApartFromTheAccount(runtimeDirectory, Path.of("/run/user"));
        }
        return AddressFile.open(runtimeDirectory);
    }

    /**
     * The log file, opened only once this planner holds the address file's lock, so a second
     * planner that fails to start never writes the first one's log; none in the mock mode, which
     * writes nothing of the person's.
     *
     * @param address The address file, its lock taken.
     * @param mode Present only in the mock mode.
     * @param base A base for {@code porelo/logs} instead of the platform's, such as a test's own.
     * @param maxFileSize The size at which the file is rolled over.
     * @param history How many rolled-over files are kept.
     * @return The open log file.
     */
    @Bean(destroyMethod = "close")
    public LogFile logFile(
            final AddressFile address,
            final ObjectProvider<MockMode> mode,
            @Value("${planner.log.base:}") final String base,
            @Value("${planner.log.max-file-size:10MB}") final DataSize maxFileSize,
            @Value("${planner.log.max-history:3}") final int history) {
        if (mode.getIfAvailable() != null) {
            return LogFile.none();
        }
        final PrivateFiles files = PrivateFiles.of(System.getenv());
        return LogFile.open(
                LogFile.directory(files, System.getenv(), base),
                files,
                maxFileSize.toBytes(),
                history);
    }

    /**
     * The level of the planner's log, set once its file is open: the one kept in the state
     * directory, or INFO; in the mock mode kept nowhere.
     *
     * @param log The log file, open before its level is set.
     * @param mode Present only in the mock mode.
     * @param base A base for the state directory instead of the platform's, such as a test's own.
     * @return The level in force.
     */
    @Bean
    public LogLevel logLevel(
            final LogFile log,
            final ObjectProvider<MockMode> mode,
            @Value("${planner.state.base:}") final String base) {
        final PrivateFiles files = PrivateFiles.of(System.getenv());
        final LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        if (mode.getIfAvailable() != null) {
            return LogLevel.inMemory(logging, files);
        }
        return LogLevel.kept(logging, files, System.getenv(), base);
    }

    /**
     * Writes the address file once the server listens, so a window reading it can call at once.
     *
     * @param file The address file.
     * @param token The token.
     * @param log The log file, whose path the window reads from the address file.
     * @return The listener.
     */
    @Bean
    public ApplicationListener<WebServerInitializedEvent> addressPublisher(
            final AddressFile file, final AccessToken token, final LogFile log) {
        return event -> {
            file.publish(event.getWebServer().getPort(), token, log.path());
            LOG.info(
                    "The planner listens on port {}; its address file is {}",
                    event.getWebServer().getPort(),
                    LogText.of(file.path()));
        };
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
