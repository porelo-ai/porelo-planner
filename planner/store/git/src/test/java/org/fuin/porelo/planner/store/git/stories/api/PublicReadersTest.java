package org.fuin.porelo.planner.store.git.stories.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.git.stories.Project;
import org.fuin.porelo.planner.store.git.stories.Story;
import org.fuin.porelo.planner.store.git.stories.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Reads a project and a story from outside the library's package, through its public types alone,
 * as a program that links the library does: a second parser there would drift from the index's.
 */
class PublicReadersTest {

    private static final Path CHECKOUT = Path.of("src/test/resources/checkouts/porelo-project");

    private static Workflow workflow() throws IOException {
        return Workflow.read(Path.of("src/test/resources/workflow.yml"));
    }

    private static String story(String text) {
        return """
                # ST1 — A title

                Written by a person.

                ## Data

                ```json
                %s
                ```
                """
                .formatted(text);
    }

    // Prevents a board from ordering its columns other than the workflow file does.
    @Test
    void aWorkflowIsReadFromTheTextOfItsFile() throws IOException {
        final String text =
                Files.readString(
                        Path.of("src/test/resources/workflow.yml"), StandardCharsets.UTF_8);

        final Workflow workflow = Workflow.read(text);

        assertThat(workflow.states())
                .as("the states in board order, as the path form reads them")
                .isEqualTo(workflow().states())
                .startsWith("Idea", "Accepted")
                .endsWith("Finished", "Rejected");
        assertThat(workflow.phases())
                .as("the phases, as the path form reads them")
                .isEqualTo(workflow().phases());
    }

    // Prevents a malformed workflow from being read as a board with fewer states.
    @Test
    void aMalformedWorkflowIsRefusedInThePathFormsWords() {
        assertThatThrownBy(() -> Workflow.read("states: []\nphases: []\n"))
                .as("the path form's refusal of an empty list of states, named for the file")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workflow.yml:1: states must not be empty");
        assertThatThrownBy(() -> Workflow.read("states: [\n  {name: Idea\n"))
                .as("a YAML syntax error, named for the file and its line")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("workflow.yml:");
    }

    // Prevents a board from showing repositories other than those project.yml names.
    @Test
    void aProjectsRepositoriesAreReadFromTheTextOfItsProjectFile() throws IOException {
        final String text =
                Files.readString(CHECKOUT.resolve("project.yml"), StandardCharsets.UTF_8);

        final Project project = Project.read(text, Set.of("ST"));

        assertThat(project.own()).as("the project's own repository").isEqualTo("porelo-project");
        assertThat(project.repositories())
                .as("the own repository with the prefixes given, then each one named")
                .containsEntry("porelo-project", Set.of("ST"))
                .containsEntry("porelo", Set.of("B", "P"))
                .containsKey("porelo-frontend");
    }

    // Prevents a board from cloning a repository from an address project.yml does not give.
    @Test
    void aProjectsCentralsAreReadAsWritten() {
        final Project project =
                Project.read(
                        """
                        project:
                          central: "https://github.com/porelo-ai/porelo-project"
                        repositories:
                          core:
                            central: "git@github.com:porelo-ai/porelo.git"
                            issues: [B]
                          frontend:
                            upstream: "https://github.com/porelo-ai/porelo-frontend.git"
                            issues: [F]
                        """,
                        Set.of("ST"));

        assertThat(project.centrals())
                .as("each repository's central address as written, the own one first")
                .containsExactly(
                        Map.entry("porelo-project", "https://github.com/porelo-ai/porelo-project"),
                        Map.entry("porelo", "git@github.com:porelo-ai/porelo.git"),
                        Map.entry(
                                "porelo-frontend",
                                "https://github.com/porelo-ai/porelo-frontend.git"));
        assertThat(project.central("porelo-frontend"))
                .as("a repository's central address, given by a not yet renamed upstream")
                .isEqualTo("https://github.com/porelo-ai/porelo-frontend.git");
        assertThat(project.central("porelo-project"))
                .as("the own repository's central address")
                .isEqualTo("https://github.com/porelo-ai/porelo-project");
        assertThatThrownBy(() -> project.central("porelo-x"))
                .as("a repository the project does not name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("porelo-x is not among the repositories");
    }

    // Prevents a board from showing a story's state by a rule other than the index's.
    @Test
    void aStorysStateIsGivenByItsIssuesAsTheIndexGivesIt() throws IOException {
        final Workflow workflow = workflow();

        assertThat(workflow.storyState("Idea", List.of()))
                .as("no issues: the state stated")
                .isEqualTo("Idea");
        assertThat(workflow.storyState(null, List.of()))
                .as("no issues and none stated: none")
                .isNull();
        assertThat(workflow.storyState("Idea", List.of("Review", "Finished", "Analyzed")))
                .as("the earliest open issue state in board order")
                .isEqualTo("Analyzed");
        assertThat(workflow.storyState(null, List.of("Rejected", "Rejected")))
                .as("every issue rejected: the first rejected one")
                .isEqualTo("Rejected");
        assertThat(
                        Workflow.read(
                                        """
                                        states:
                                          - {name: Idea, closed: false}
                                          - {name: Done, closed: true}
                                          - {name: Shipped, closed: true}
                                          - {name: Rejected, closed: true}
                                        phases: []
                                        """)
                                .storyState("Idea", List.of("Shipped", "Rejected")))
                .as("every issue closed, not all rejected: the workflow's first completed state")
                .isEqualTo("Done");
        assertThatThrownBy(() -> workflow.storyState("Idea", List.of("Nowhere")))
                .as("an issue state the workflow does not hold")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("src/test/resources/workflow.yml: unknown state: Nowhere");
    }

    // Prevents a malformed project file from being read as a project with no repositories.
    @Test
    void aMalformedProjectFileIsRefusedInTheIndexsWords() {
        assertThatThrownBy(() -> Project.read("project: []\nrepositories: {}\n", Set.of()))
                .as("the index's own refusal of a project without a central remote")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("project: missing required field upstream");
        assertThatThrownBy(
                        () ->
                                Project.read(
                                        """
                                        project:
                                          central: git@github.com:porelo-ai/porelo-project.git
                                        repositories:
                                          core:
                                            central: git@github.com:porelo-ai/porelo.git
                                            issues: [b]
                                        """,
                                        Set.of()))
                .as("the index's own refusal of a prefix that is not upper case")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("project.yml repositories.core: issues must name uppercase prefixes");
    }

    // Prevents a caller's missing prefixes from being read as a project whose own repository has
    // none.
    @Test
    void aProjectWithoutTheOwnPrefixesIsRefused() {
        assertThatThrownBy(
                        () ->
                                Project.read(
                                        "project:\n  central: git@github.com:porelo-ai/porelo-project.git\nrepositories: {}\n",
                                        null))
                .as("the own prefixes are required, and named")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("ownPrefixes");
    }

    // Prevents a project built by hand from naming an own repository it does not hold.
    @Test
    void aProjectIsBuiltOnlyWhole() {
        final String central = "git@github.com:porelo-ai/porelo.git";
        assertThatThrownBy(() -> new Project(null, Map.of(), Map.of()))
                .as("no own repository")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("own");
        assertThatThrownBy(() -> new Project("porelo-project", null, Map.of()))
                .as("no repositories")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("repositories");
        assertThatThrownBy(() -> new Project("porelo", Map.of("porelo", Set.of("B")), null))
                .as("no centrals")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("centrals");
        assertThatThrownBy(
                        () ->
                                new Project(
                                        "porelo-project",
                                        Map.of("porelo", Set.of("B")),
                                        Map.of("porelo", central)))
                .as("an own repository the repositories do not hold")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("own repository porelo-project is not among the repositories");
        assertThatThrownBy(
                        () ->
                                new Project(
                                        "porelo",
                                        Map.of("porelo", Set.of("B")),
                                        Map.of("porelo-frontend", central)))
                .as("centrals named other than the repositories")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "centrals [porelo-frontend] are not those of the repositories [porelo]");
    }

    // Prevents a story built by hand from carrying a missing field to a board.
    @Test
    void aStoryIsBuiltOnlyWhole() {
        final List<String> none = List.of();
        assertThatThrownBy(() -> new Story(null, "A title", null, "High", none, ""))
                .as("no id")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("id");
        assertThatThrownBy(() -> new Story("ST1", null, null, "High", none, ""))
                .as("no title")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("title");
        assertThatThrownBy(() -> new Story("ST1", "A title", null, null, none, ""))
                .as("no priority")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("priority");
        assertThatThrownBy(() -> new Story("ST1", "A title", null, "High", null, ""))
                .as("no issues")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("issues");
        assertThatThrownBy(
                        () ->
                                new Story(
                                        "ST1",
                                        "A title",
                                        null,
                                        "High",
                                        Arrays.asList("porelo#B10", null),
                                        ""))
                .as("an issue that is null")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Story("ST1", "A title", null, "High", none, null))
                .as("no body")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("body");
    }

    // Prevents a board from showing a story other than its file states.
    @Test
    void aStoryIsReadFromItsFilesText() throws IOException {
        final String text =
                Files.readString(
                        CHECKOUT.resolve("stories/ST203-Porelo-Agy.md"), StandardCharsets.UTF_8);

        final Story story = Story.read(text, workflow());

        assertThat(story.id()).as("the id").isEqualTo("ST203");
        assertThat(story.title()).as("the title").isEqualTo("porelo-agy");
        assertThat(story.state()).as("the state the head states").isEqualTo("Idea");
        assertThat(story.priority()).as("the priority").isEqualTo("Low");
        assertThat(story.issues()).as("the issues").containsExactly("porelo-agy#AG06");
        assertThat(story.body())
                .as("the text between the heading and the head")
                .startsWith("**Priority:** 🔽 Low · **State:** Idea")
                .endsWith(
                        "- [porelo-agy#AG06](https://github.com/porelo-ai/porelo-agy/blob/main/issues/README.md#ag06) (Idea)")
                .doesNotContain("## Data");
    }

    // Prevents a story whose state is left to its issues from being given one it does not state.
    @Test
    void aStoryThatLeavesItsStateToItsIssuesHasNone() throws IOException {
        final Story story =
                Story.read(
                        story(
                                """
                                {"$schema":"story-2.json","id":"ST1","title":"A title","priority":"High","gain":"A gain.","test":"A test.","issues":["porelo#B10"],"tested":null}"""),
                        workflow());

        assertThat(story.state()).as("no state stated").isNull();
        assertThat(story.body()).as("the person's text").isEqualTo("Written by a person.");
    }

    // Prevents a body that opens with an indented block from losing the indentation that makes it
    // one.
    @Test
    void aStorysBodyKeepsTheIndentationOfItsFirstLine() throws IOException {
        final Story story =
                Story.read(
                        """
                        # ST1 — A title


                            an indented first line

                        a second paragraph


                        ## Data

                        ```json
                        {"id":"ST1","title":"A title","state":"Idea","priority":"High","gain":"A gain.","test":"A test.","issues":[],"tested":null}
                        ```
                        """,
                        workflow());

        assertThat(story.body())
                .as("only the blank lines around the text dropped")
                .isEqualTo("    an indented first line\n\na second paragraph");
    }

    // Prevents a faulty story from reaching a board the index would refuse it from.
    @Test
    void aFaultyStoryIsRefusedInTheIndexsWords() {
        assertThatThrownBy(
                        () ->
                                Story.read(
                                        story(
                                                """
                                                {"id":"ST1","title":"A title","state":"Idea","priority":"Urgent","gain":"A gain.","test":"A test.","issues":[],"tested":null}"""),
                                        workflow()))
                .as("the schema's fault as the index reports it")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "/priority: does not have a value in the enumeration [\"Highest\", \"High\", \"Medium\", \"Low\", \"Lowest\"]");
        assertThatThrownBy(
                        () ->
                                Story.read(
                                        story(
                                                """
                                                {"id":"ST1","title":"A title","state":"Idea","priority":"Urgent","gain":"A gain.","issues":[],"tested":null}"""),
                                        workflow()))
                .as("each fault as the index reports it, in its order, joined")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "/priority: does not have a value in the enumeration [\"Highest\", \"High\", \"Medium\", \"Low\", \"Lowest\"]"
                                + "; /: required property 'test' not found");
        assertThatThrownBy(
                        () ->
                                Story.read(
                                        story(
                                                """
                                                {"id":"ST1","title":"Another title","state":"Idea","priority":"High","gain":"A gain.","test":"A test.","issues":[],"tested":null}"""),
                                        workflow()))
                .as("a heading the head does not give, as the index reports it")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the heading is not '# ST1 — Another title'");
        assertThatThrownBy(() -> Story.read("# ST1 — A title\n\nNo head.\n", workflow()))
                .as("a file without a head")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no ```json head under ## Data at the end");
        assertThatThrownBy(
                        () ->
                                Story.read(
                                        story(
                                                """
                                                {"id":"ST1","title":"A title","state":"Nowhere","priority":"High","gain":"A gain.","test":"A test.","issues":[],"tested":null}"""),
                                        workflow()))
                .as("a state the workflow does not hold")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("/state: src/test/resources/workflow.yml: unknown state: Nowhere");
    }

    // Prevents the reader's answer from changing under its caller.
    @Test
    void theAnswersCannotBeChanged() throws IOException {
        final Story story =
                Story.read(
                        Files.readString(
                                CHECKOUT.resolve("stories/ST203-Porelo-Agy.md"),
                                StandardCharsets.UTF_8),
                        workflow());
        final Project project =
                Project.read(
                        Files.readString(CHECKOUT.resolve("project.yml"), StandardCharsets.UTF_8),
                        Set.of());

        assertThatThrownBy(() -> story.issues().add("porelo#B1"))
                .as("the story's issues")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> project.repositories().put("porelo-x", Set.of()))
                .as("the project's repositories")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> project.centrals().put("porelo-x", "git@github.com:porelo-ai/x"))
                .as("the project's centrals")
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
