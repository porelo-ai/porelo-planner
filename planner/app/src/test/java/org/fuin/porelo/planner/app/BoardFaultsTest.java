package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.UnlistedIssue;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
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

        assertThat(BoardController.workflowOf(board, false).faults())
                .as("the board's faults and the work rules' faults")
                .containsExactly(
                        "ST3: a reference names no issue, and is left out",
                        "stories/work.yml is missing");
    }

    @Test
    void theBoardEventCarriesAnIssueOutsideTheWorkflowUnderItsStoryWarnedAndNoListApart() {
        // Harm: the window shows an issue outside the workflow apart from its story, or not at
        // all, so its story is judged and shown without it.
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
                        List.of("porelo-store#2"),
                        "",
                        null);
        final Issue accepted =
                new Issue(
                                "porelo-store#2",
                                "porelo-store",
                                "An issue",
                                "Accepted",
                                null,
                                List.of(),
                                null,
                                URI.create("https://github.com/example-org/porelo-store/issues/2"),
                                "")
                        .withWarnings(List.of(Warning.stateOutsideWorkflow()));
        final Board tracked =
                new Board(
                        new Revision("a".repeat(64)),
                        workflow,
                        List.of(story),
                        List.of(accepted),
                        new Work(Map.of(), List.of()),
                        List.of("porelo-store"),
                        List.of(),
                        "example-org");

        final JsonNode event = mapper().valueToTree(boardOf(tracked));

        assertThat(event.has("outside")).as("no list apart").isFalse();
        final JsonNode issue = event.path("stories").path(0).path("issues").path(0);
        assertThat(issue.path("id").asText())
                .as("the issue under its story")
                .isEqualTo("porelo-store#2");
        assertThat(issue.path("state").asText()).as("in its own state").isEqualTo("Accepted");
        assertThat(issue.path("warnings").path(0).path("kind").asText())
                .as("warned")
                .isEqualTo("StateOutsideWorkflow");
    }

    @Test
    void theBoardEventCarriesTheIssuesOutsideTheProjectByKindAndAGitBoardNone() {
        // Harm: the window never shows an issue made by hand on the tracker, shows a story as an
        // issue, or reads a missing list as an old planner's answer.
        final Workflow workflow =
                new Workflow(
                        List.of("Idea", "Finished"),
                        List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                        List.of("Finished"));
        final Board tracked =
                new Board(
                        new Revision("a".repeat(64)),
                        workflow,
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of()),
                        List.of("porelo-store", "porelo-other"),
                        List.of(),
                        "example-org",
                        List.of(
                                new UnlistedIssue(
                                        "porelo-store#4",
                                        "porelo-store",
                                        "A story by hand",
                                        UnlistedIssue.Kind.STORY,
                                        URI.create(
                                                "https://github.com/example-org/porelo-store/issues/4"),
                                        "a-person",
                                        "FIRST_TIMER"),
                                new UnlistedIssue(
                                        "porelo-other#2",
                                        "porelo-other",
                                        "An issue by hand",
                                        UnlistedIssue.Kind.ISSUE,
                                        URI.create(
                                                "https://github.com/example-org/porelo-other/issues/2"))));
        final Board git =
                new Board(
                        new Revision("b".repeat(64)),
                        workflow,
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of()),
                        List.of("porelo-store"));

        final JsonNode event = mapper().valueToTree(boardOf(tracked));

        assertThat(event.path("unlisted").toString())
                .as("the issues outside the project as the event's data carries them, in order")
                .isEqualTo(
                        "[{\"id\":\"porelo-store#4\",\"repository\":\"porelo-store\","
                                + "\"title\":\"A story by hand\",\"kind\":\"story\",\"link\":"
                                + "\"https://github.com/example-org/porelo-store/issues/4\","
                                + "\"author\":\"a-person\",\"authorAssociation\":\"FIRST_TIMER\"},"
                                + "{\"id\":\"porelo-other#2\",\"repository\":\"porelo-other\","
                                + "\"title\":\"An issue by hand\",\"kind\":\"issue\",\"link\":"
                                + "\"https://github.com/example-org/porelo-other/issues/2\"}]");
        assertThat(mapper().valueToTree(boardOf(git)).path("unlisted").toString())
                .as("a Git-kept board's, sent empty")
                .isEqualTo("[]");
    }

    @Test
    void theBoardEventCarriesEachBreachOfTheStoriesRulesAndTheIssuesPriorities() {
        // Harm: a story breaking the stories' rules on a GitHub board is never seen in the
        // window, or a priority the rules read never reaches it.
        final Workflow workflow =
                new Workflow(
                        List.of("Idea", "Backlog", "Finished"),
                        List.of(new Workflow.Phase("All", List.of("Idea", "Backlog", "Finished"))),
                        List.of("Finished"));
        final Story empty =
                new Story("ST1", "A story", "Idea", false, "High", null, List.of(), "", null);
        final Story low =
                new Story(
                        "ST2",
                        "A story",
                        "Backlog",
                        false,
                        "Low",
                        null,
                        List.of("porelo-store#1"),
                        "",
                        null);
        final Issue urgent = issue("porelo-store#1", "Highest");
        final Board board =
                new Board(
                        new Revision("a".repeat(64)),
                        workflow,
                        List.of(empty, low),
                        List.of(urgent, issue("porelo-store#2", null)),
                        new Work(Map.of(), List.of()),
                        List.of("porelo-store"));

        final JsonNode event = mapper().valueToTree(boardOf(board));

        assertThat(event.path("rules").toString())
                .as("each breach with its item, its rule and the planner's words")
                .isEqualTo(
                        "[{\"item\":\"ST1\",\"rule\":\"StoryWithoutIssueOrState\","
                                + "\"words\":\"ST1 has no issue and no given state.\"},"
                                + "{\"item\":\"ST2\",\"rule\":\"StoryPriorityBelowIssue\","
                                + "\"words\":\"ST2 has a lower priority than its open issue"
                                + " porelo-store#1.\"},"
                                + "{\"item\":\"porelo-store#2\",\"rule\":\"IssueInNoStory\","
                                + "\"words\":\"porelo-store#2 stands past the workflow's first state in"
                                + " no story.\"}]");
        assertThat(
                        mapper().valueToTree(
                                        WorkAnswers.issue(
                                                board, urgent, new WorkRun.Snapshot(0, Map.of())))
                                .path("priority")
                                .asString())
                .as("the issue's priority as the board answers it")
                .isEqualTo("Highest");
    }

    private static Issue issue(final String id, final @Nullable String priority) {
        return new Issue(
                id,
                "porelo-store",
                "An issue",
                "Backlog",
                "S",
                List.of(),
                null,
                null,
                "",
                Worker.AGENT,
                Risk.ORDINARY,
                null,
                null,
                priority,
                List.of(),
                List.of());
    }

    private static org.fuin.porelo.planner.app.answers.Board boardOf(final Board board) {
        return BoardController.boardOf(
                board,
                new WorkRun.Snapshot(0, Map.of()),
                states -> states.isEmpty() ? null : "Idea",
                false);
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
