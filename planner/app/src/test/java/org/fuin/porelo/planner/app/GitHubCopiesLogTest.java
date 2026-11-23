package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.TrackedProject;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.github.GitHubStoreOpener;
import org.fuin.porelo.planner.store.github.ProjectQueries;
import org.fuin.porelo.planner.store.github.ProjectWrites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The board copies' place failing is logged in the planner's own words alone. */
class GitHubCopiesLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(GitHubStoreOpener.class);

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void listen() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void close() {
        logger.detachAppender(lines);
    }

    @SuppressWarnings("unchecked")
    private static <T> T unused(final Class<T> type) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            throw new AssertionError("Opening called " + method.getName());
                        });
    }

    @Test
    void aCopiesPlaceThatCannotBeGivenIsLoggedWithoutTheEnvironmentOrThePath() {
        // Harm: the log names the person's account in a path, or carries a forged line from an
        // environment value, as the failure's own words are written into it.
        final GitHubStoreOpener opener =
                new GitHubStoreOpener(
                                origin -> "invented-token-copies-0123456789",
                                unused(ProjectQueries.class),
                                unused(ProjectWrites.class),
                                Duration.ofSeconds(1))
                        .keepingCopies(
                                () -> {
                                    throw new IllegalStateException(
                                            "LOCALAPPDATA is 'C:\\Users\\a-person\\AppData'\n"
                                                    + "forged line");
                                });
        final TrackedProject project =
                new TrackedProject(
                        URI.create("https://github.com"),
                        "example-org",
                        "github",
                        "7",
                        org.fuin.porelo.planner.store.ProjectRepository.named(
                                List.of("example-project")),
                        new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                        new Work(Map.of(), List.of()),
                        "c0ffee0",
                        states -> null);

        opener.open(project, () -> project);

        assertThat(lines.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .as("the opener's log lines")
                .anySatisfy(
                        line ->
                                assertThat(line)
                                        .contains("copies cannot be kept")
                                        .contains("IllegalStateException"))
                .noneSatisfy(line -> assertThat(line).contains("a-person"))
                .noneSatisfy(line -> assertThat(line).contains("LOCALAPPDATA"))
                .noneSatisfy(line -> assertThat(line).contains("forged"));
    }
}
