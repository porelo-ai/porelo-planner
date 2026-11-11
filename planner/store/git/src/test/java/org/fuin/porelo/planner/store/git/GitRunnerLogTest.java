package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class GitRunnerLogTest {

    private static final String ENVIRONMENT_SECRET = "env-secret-4711";

    private static final String URL_PASSWORD = "url-password-0815";

    private final Logger logger = (Logger) LoggerFactory.getLogger(GitRunner.class);

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    private Level before;

    @BeforeEach
    void capture() {
        before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void release() {
        logger.detachAppender(lines);
        logger.setLevel(before);
    }

    @Test
    void aCredentialInTheEnvironmentOrARemotesAddressReachesNoLineAtDebug() {
        // The log is kept for days; a credential written there outlives the call that used it.
        final String url = "https://person:" + URL_PASSWORD + "@git.example.org/porelo.git";
        final GitCall call =
                new GitCall(
                        List.of(
                                "/bin/sh",
                                "-c",
                                "echo \"fatal: unable to access '$1'\" >&2; exit 128",
                                "ls-remote",
                                url),
                        Map.of("PATH", "/usr/bin:/bin", "TOKEN", ENVIRONMENT_SECRET));
        assertThatThrownBy(() -> new GitRunner(Duration.ofSeconds(5), 1024).run(call, null))
                .as("a call that fails naming its address")
                .isInstanceOf(GitFailure.class);
        final GitCall echoing =
                new GitCall(
                        List.of("/bin/sh", "-c", "echo \"$1 $TOKEN\"", "fetch", url),
                        Map.of("PATH", "/usr/bin:/bin", "TOKEN", ENVIRONMENT_SECRET));
        new GitRunner(Duration.ofSeconds(5), 1024).run(echoing, null);
        assertThat(lines.list)
                .as("the lines the calls logged at DEBUG")
                .hasSizeGreaterThanOrEqualTo(2);
        for (final ILoggingEvent line : lines.list) {
            assertThat(line.getFormattedMessage())
                    .as("a line at DEBUG")
                    .doesNotContain(ENVIRONMENT_SECRET)
                    .doesNotContain(URL_PASSWORD);
        }
    }
}
