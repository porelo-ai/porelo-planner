package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.OutsideIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BoardFaultsTest {

    @Test
    void whatTheStoreLeftOutIsAnsweredBesideTheBoard() {
        // A story or issue left out without a word would read as lost data.
        final Board board =
                new Board(
                        new Revision("a".repeat(64)),
                        new Workflow(
                                List.of("Idea", "Finished"),
                                List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                                List.of("Finished")),
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of("stories/work.yml is missing")),
                        List.of("porelo-store"),
                        List.of("ST3: a reference names no issue, and is left out"));

        assertThat(BoardController.workflowOf(board).faults())
                .as("the board's faults and the work rules' faults")
                .containsExactly(
                        "ST3: a reference names no issue, and is left out",
                        "stories/work.yml is missing");
    }

    @Test
    void theBoardEventCarriesTheIssuesLeftOutOfTheWorkflowAndAGitBoardNone() {
        // Harm: the window never hears of an issue outside the workflow, so it offers no move
        // that brings it back, or reads a missing list as an old planner's answer.
        final Workflow workflow =
                new Workflow(
                        List.of("Idea", "Finished"),
                        List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                        List.of("Finished"));
        final Story story =
                new Story(
                        "porelo-store#1",
                        "A story",
                        "Idea",
                        false,
                        "High",
                        null,
                        List.of(),
                        "",
                        null);
        final Board tracked =
                new Board(
                        new Revision("a".repeat(64)),
                        workflow,
                        List.of(story),
                        List.of(),
                        new Work(Map.of(), List.of()),
                        List.of("porelo-store"),
                        List.of(),
                        "example-org",
                        List.of(
                                new OutsideIssue(
                                        "porelo-store#2",
                                        "porelo-store",
                                        "An issue",
                                        "Accepted",
                                        "porelo-store#1",
                                        null,
                                        URI.create(
                                                "https://github.com/example-org/porelo-store/issues/2"),
                                        Worker.AGENT)));
        final Board git =
                new Board(
                        new Revision("b".repeat(64)),
                        workflow,
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of()),
                        List.of("porelo-store"));

        final JsonNode event = mapper().valueToTree(boardOf(tracked));

        assertThat(event.path("outside").toString())
                .as("the issue outside the workflow as the event's data carries it")
                .isEqualTo(
                        "[{\"id\":\"porelo-store#2\",\"repository\":\"porelo-store\","
                                + "\"title\":\"An issue\",\"status\":\"Accepted\","
                                + "\"story\":\"porelo-store#1\",\"link\":"
                                + "\"https://github.com/example-org/porelo-store/issues/2\"}]");
        assertThat(mapper().valueToTree(boardOf(git)).path("outside").toString())
                .as("a Git-kept board's, sent empty")
                .isEqualTo("[]");
    }

    private static org.fuin.porelo.planner.app.answers.Board boardOf(final Board board) {
        return BoardController.boardOf(
                board,
                new WorkRun.Snapshot(0, Map.of()),
                states -> states.isEmpty() ? null : "Idea");
    }

    /** The mapper the app's Spring Boot sets up, which writes the stream's events. */
    private static JsonMapper mapper() {
        final JsonMapper[] found = new JsonMapper[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> found[0] = context.getBean(JsonMapper.class));
        return found[0];
    }
}
