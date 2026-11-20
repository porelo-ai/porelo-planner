package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Each board read is logged once per revision and faults, never once per poll. */
class BoardLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(BoardLog.class);

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

    @Test
    void aFaultThatComesAtTheSameRevisionIsLoggedOnce() {
        // Harm: GitHub's spent rate limit never reaches the log, as the board kept keeps its
        // revision, or is logged again at every poll.
        final String project = "board-log-fault-at-one-revision";
        BoardLog.read(project, board(List.of()));
        BoardLog.read(project, board(List.of("GitHub's rate limit is spent")));
        BoardLog.read(project, board(List.of("GitHub's rate limit is spent")));

        assertThat(lines.list)
                .as("the lines logged")
                .extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(line -> line.contains("rate limit is spent"))
                .hasSize(1);
    }

    private static Board board(final List<String> faults) {
        return new Board(
                new Revision("one"),
                new Workflow(List.of("Idea"), List.of(), List.of()),
                List.of(),
                List.of(),
                new Work(Map.of(), List.of()),
                List.of(),
                faults);
    }
}
