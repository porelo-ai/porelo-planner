package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** What the planner logs of a read and a save, each value from outside on one line. */
class LogLinesTest {

    /** A project name of this test alone, so no other test's read was logged for it first. */
    private static final String PROJECT = "logged-lines";

    private static final String FORGED = "ERROR forged by a repository";

    private final Logger logger = (Logger) LoggerFactory.getLogger(LogFile.LOGGER);

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    private @Nullable Level before;

    private Board current = board("r1", "Backlog");

    private MockMvc mvc;

    private ChangesRegistry changes;

    private static Board board(final String revision, final String state) {
        return new Board(
                new Revision(revision),
                new Workflow(
                        List.of("Backlog", "Review", "Finished"), List.of(), List.of("Finished")),
                List.of(
                        new Story(
                                "ST1", "A story", state, false, "High", List.of("AB01"), "", null)),
                List.of(
                        new Issue(
                                "AB01",
                                "porelo-store",
                                "An issue",
                                state,
                                "S",
                                List.of(),
                                null,
                                null,
                                "")),
                new Work(Map.of(), List.of()),
                List.of("porelo-store"),
                List.of("issues/AB02-Broken.md has no head\n" + FORGED));
    }

    @BeforeEach
    void serve() throws Exception {
        before = logger.getLevel();
        logger.setLevel(Level.INFO);
        lines.start();
        logger.addAppender(lines);
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return current;
                    }

                    @Override
                    public Board refresh() {
                        return current;
                    }

                    @Override
                    public Board save(final Revision revision, final Writes writes) {
                        current = LogLinesTest.board("r2", "Review");
                        return current;
                    }

                    @Override
                    public @Nullable String storyState(final List<String> states) {
                        return states.isEmpty() ? null : states.get(0);
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store, 8);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects, changes))
                        .setControllerAdvice(new Problems())
                        .build();
        mvc.perform(
                        put("/projects/" + PROJECT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"repository\": \"git@example.org:a/porelo-store.git\"}"))
                .andExpect(status().isNoContent());
    }

    @AfterEach
    void close() {
        changes.close();
        logger.detachAppender(lines);
        logger.setLevel(before);
    }

    @Test
    void aReadWithAFaultAndASaveEachLeaveALineAndAForgedLineStaysInsideItsEntry() throws Exception {
        // Without the lines a person cannot tell why a board lacked an issue or when a save went
        // out; a fault's own line break would let a repository write lines of its own.
        mvc.perform(get("/projects/" + PROJECT + "/stories")).andExpect(status().isOk());
        mvc.perform(
                        post("/projects/" + PROJECT + "/moves")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"revision\": \"r1\", \"moves\": [{\"issue\": \"AB01\","
                                                + " \"to\": \"Review\"}]}"))
                .andExpect(status().isOk());
        final List<String> messages =
                lines.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages)
                .as("a line naming the project's read revision")
                .anyMatch(line -> line.contains(PROJECT) && line.contains("r1"));
        assertThat(messages)
                .as("a line naming the fault left out")
                .anyMatch(line -> line.contains("AB02-Broken.md") && line.contains(FORGED));
        assertThat(messages)
                .as("a line naming the saved revision")
                .anyMatch(line -> line.contains(PROJECT) && line.contains("r2"));
        assertThat(messages)
                .as("lines that break into more than one")
                .allMatch(line -> line.lines().count() == 1);
    }
}
