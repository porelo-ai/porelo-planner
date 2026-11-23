package org.fuin.porelo.planner.app;

import jakarta.servlet.DispatcherType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.app.mock.MockMode;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.planner.store.git.LogText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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
     * Logs each bean that takes 100 ms or more to make while the planner starts, so a slow start
     * names its step; static, so it exists before the beans it times.
     *
     * @return The start timer.
     */
    @Bean
    static StartSteps startSteps() {
        return new StartSteps(System::nanoTime);
    }

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
     * The platform's private files, shared by the start's beans; on Windows the directories they
     * will ask for are checked in one PowerShell call ahead of them, since each call is slow.
     *
     * @param runtimeDirectory The account's runtime directory.
     * @param logBase A base for the log directory instead of the platform's.
     * @param stateBase A base for the state directory instead of the platform's.
     * @param mode Present only in the mock mode, which makes none of these directories.
     * @return The files.
     */
    @Bean
    public PrivateFiles privateFiles(
            @Value("${planner.runtime-directory:${XDG_RUNTIME_DIR:}}")
                    final String runtimeDirectory,
            @Value("${planner.log.base:}") final String logBase,
            @Value("${planner.state.base:}") final String stateBase,
            final ObjectProvider<MockMode> mode) {
        final Map<String, String> environment = System.getenv();
        final PrivateFiles files = PrivateFiles.of(environment);
        if (!files.windows()) {
            return files;
        }
        final List<PrivateFiles.Request> requests;
        try {
            requests =
                    startRequests(
                            files,
                            environment,
                            runtimeDirectory,
                            logBase,
                            stateBase,
                            mode.getIfAvailable() != null);
        } catch (final IllegalStateException e) {
            // Each bean then fails on its own call, with its own words.
            LOG.warn("The start's directories are checked one kind at a time: {}", e.getMessage());
            return files;
        }
        files.prepare(requests);
        return files;
    }

    /**
     * Exactly the calls of {@link PrivateFiles#directories} the start's beans make, so a base given
     * instead is checked on its own; none in the mock mode, which must not make the person's own
     * directories.
     *
     * @param files The platform's files.
     * @param environment The planner's environment.
     * @param runtimeDirectory The runtime directory given, or empty.
     * @param logBase The log base given, or empty.
     * @param stateBase The state base given, or empty.
     * @param mock Whether the planner runs in the mock mode.
     * @return The calls.
     */
    static List<PrivateFiles.Request> startRequests(
            final PrivateFiles files,
            final Map<String, String> environment,
            final String runtimeDirectory,
            final String logBase,
            final String stateBase,
            final boolean mock) {
        final List<PrivateFiles.Request> requests = new ArrayList<>();
        if (mock) {
            return requests;
        }
        if (runtimeDirectory.isBlank()) {
            requests.add(request(files, environment, false, PrivateFiles.Kind.RUNTIME));
        }
        if (logBase.isBlank()) {
            requests.add(request(files, environment, true, PrivateFiles.Kind.LOGS));
        }
        if (stateBase.isBlank()) {
            requests.add(request(files, environment, true, PrivateFiles.Kind.STATE));
        }
        return requests;
    }

    private static PrivateFiles.Request request(
            final PrivateFiles files,
            final Map<String, String> environment,
            final boolean create,
            final PrivateFiles.Kind kind) {
        return new PrivateFiles.Request(
                files.base(kind, environment), create, kind, files.steps(kind));
    }

    /**
     * Drops what the start checked ahead, once it has started, so no later call takes a check made
     * at the start.
     *
     * @param files The start's files.
     * @return The listener.
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> startChecksForgotten(
            final PrivateFiles files) {
        return event -> files.forget();
    }

    /**
     * The address file, its lock taken at once, so a second planner stops before it listens.
     *
     * @param runtimeDirectory The account's runtime directory.
     * @param mode Present only in the mock mode, which never writes where the person's window
     *     looks.
     * @param files The start's files.
     * @return The address file, written once the server listens and deleted when the planner stops.
     */
    @Bean(destroyMethod = "close")
    public AddressFile addressFile(
            @Value("${planner.runtime-directory:${XDG_RUNTIME_DIR:}}")
                    final String runtimeDirectory,
            final ObjectProvider<MockMode> mode,
            final PrivateFiles files) {
        if (mode.getIfAvailable() != null) {
            AddressFile.requireApartFromTheAccount(runtimeDirectory, Path.of("/run/user"));
        }
        return AddressFile.open(runtimeDirectory, Path.of("/run/user"), files);
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
     * @param files The start's files.
     * @return The open log file.
     */
    @Bean(destroyMethod = "close")
    public LogFile logFile(
            final AddressFile address,
            final ObjectProvider<MockMode> mode,
            @Value("${planner.log.base:}") final String base,
            @Value("${planner.log.max-file-size:10MB}") final DataSize maxFileSize,
            @Value("${planner.log.max-history:3}") final int history,
            final PrivateFiles files) {
        if (mode.getIfAvailable() != null) {
            return LogFile.none();
        }
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
     * @param files The start's files.
     * @return The level in force.
     */
    @Bean
    public LogLevel logLevel(
            final LogFile log,
            final ObjectProvider<MockMode> mode,
            @Value("${planner.state.base:}") final String base,
            final PrivateFiles files) {
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
