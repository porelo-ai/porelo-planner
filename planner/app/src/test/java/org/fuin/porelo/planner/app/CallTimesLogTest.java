package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.timed.FakeForge;
import org.fuin.porelo.planner.store.CallTimes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Guards the line a write logs about its time: it must name each forge call by the forge client's
 * method and never carry the call's address or its token into the log.
 */
class CallTimesLogTest {

    private static final String TOKEN = "ghp_" + "a".repeat(36);

    @TempDir Path directory;

    private final Logger logger = (Logger) LoggerFactory.getLogger(WorkRecord.class);

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    private HttpServer forge;

    @BeforeEach
    void listen() throws Exception {
        lines.start();
        logger.addAppender(lines);
        forge = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        forge.createContext(
                "/graphql",
                exchange -> {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        forge.start();
    }

    @AfterEach
    void stop() {
        forge.stop(0);
        logger.detachAppender(lines);
    }

    @Test
    void aWriteLogsOneLineNamingItsPhaseAndForgeCallWithoutAddressOrToken() throws Exception {
        final URI address =
                URI.create(
                        "http://127.0.0.1:"
                                + forge.getAddress().getPort()
                                + "/graphql?access_token="
                                + TOKEN);
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            records.open("project", "https://example.org/store.git", null)
                    .locked(
                            session ->
                                    CallTimes.phase(
                                            "write",
                                            () -> {
                                                try {
                                                    return new FakeForge(
                                                                    new TimedHttpClient(
                                                                            HttpClient
                                                                                    .newHttpClient()),
                                                                    address)
                                                            .issueBoard(TOKEN);
                                                } catch (final Exception e) {
                                                    throw new IllegalStateException(e);
                                                }
                                            }));
        }

        final List<String> timed =
                lines.list.stream()
                        .map(ILoggingEvent::getFormattedMessage)
                        .filter(line -> line.contains(" took "))
                        .toList();
        assertThat(timed).as("one line for the one write").hasSize(1);
        assertThat(timed.getFirst())
                .as("the call, its lock, its phase and the forge call by the client's method")
                .contains("of project project")
                .contains("waited for the work record ")
                .contains("write ")
                .contains("GitHub 1 calls: FakeForge.issueBoard 204 ");
        assertThat(timed.getFirst())
                .as("neither the address nor the token reaches the log")
                .doesNotContain(TOKEN)
                .doesNotContain("127.0.0.1")
                .doesNotContain("graphql");
    }

    @Test
    void aReadOfWhatIsHeldLogsNoTimes() {
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            records.open("project", "https://example.org/store.git", null)
                    .locked(session -> session.read());
        }

        assertThat(lines.list)
                .as("a board answer at every poll would push the log's history out")
                .noneMatch(event -> event.getFormattedMessage().contains(" took "));
    }
}
