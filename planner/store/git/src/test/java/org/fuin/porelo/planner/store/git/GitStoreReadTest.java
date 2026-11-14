package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads a whole board from two repositories served over HTTPS, as the planner reads a project. */
class GitStoreReadTest {

    private static final String WORKFLOW =
            """
            states:
              - {name: Idea, closed: false}
              - {name: Backlog, closed: false}
              - {name: Review, closed: false}
              - {name: Finished, closed: true}
              - {name: Rejected, closed: true}
            phases:
              - {name: Open, states: [Idea, Backlog]}
              - {name: Build, states: [Review]}
              - {name: Done, states: [Finished, Rejected]}
            """;

    @TempDir Path dir;

    private GitHttpsServer server;

    private ServedRepositories repositories;

    private Map<String, String> person;

    @BeforeEach
    void serve() throws Exception {
        repositories = new ServedRepositories(dir);
        repositories.repository(
                "porelo-store",
                Map.of(
                        "stories/workflow.yml", WORKFLOW,
                        "stories/README.md", "# Stories\n",
                        "stories/ST1-Stated.md",
                                story(
                                        "ST1",
                                        "Stated",
                                        "\"state\": \"Idea\",",
                                        "\"porelo-store#AB01\""),
                        "stories/ST2-By-Its-Issues.md",
                                story(
                                        "ST2",
                                        "By its issues",
                                        "",
                                        "\"porelo-store#AB01\", \"porelo-other#CD01\""),
                        "issues/README.md", "# Issues\n",
                        "issues/AB01-Parent.md", issue("AB01", "Parent", "Backlog", ""),
                        "issues/AB01.1-Child.md",
                                issue("AB01.1", "Child", "Review", "\"parent\": \"AB01\",")));
        repositories.repository(
                "porelo-other",
                Map.of(
                        "issues/workflow.yml",
                        WORKFLOW,
                        "issues/base/CD01-Grouped.md",
                        issue("CD01", "Grouped", "Review", "")));
        person = new HashMap<>();
        person.put("PATH", "/usr/bin:/bin");
        person.put("HOME", dir.toString());
        person.put("GIT_CONFIG_NOSYSTEM", "1");
        person.put("XDG_CACHE_HOME", Files.createDirectories(dir.resolve("cache")).toString());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private GitStore store(final String login) throws Exception {
        server = GitHttpsServer.start(repositories.served(), dir, login);
        final Path config = dir.resolve("gitconfig");
        Files.writeString(config, "[http]\n\tsslCAInfo = " + server.ca() + "\n");
        person.put("GIT_CONFIG_GLOBAL", config.toString());
        final Path cache = CacheDirectory.of(person, "project");
        final GitRemote remote =
                new GitRemote(
                        cache,
                        person,
                        Duration.ofSeconds(5),
                        new GitRunner(Duration.ofSeconds(20), 1 << 20),
                        1 << 16);
        return new GitStore(
                List.of(
                        new Repository(
                                "porelo-store", new CloneUrl(server.url("porelo-store.git")), null),
                        new Repository(
                                "porelo-other",
                                new CloneUrl(server.url("porelo-other.git")),
                                "main")),
                remote,
                new StoreReader(remote, 1000),
                new FileProjectLock(cache),
                Clock.systemUTC(),
                Duration.ofSeconds(10),
                4,
                Runnable::run);
    }

    @Test
    void aProjectsWholeBoardIsReadFromItsRepositories() throws Exception {
        final Board board = store(null).board();
        assertThat(board.workflow().states())
                .as("the workflow's states in its order")
                .containsExactly("Idea", "Backlog", "Review", "Finished", "Rejected");
        assertThat(board.workflow().closed())
                .as("the closed states, which no work may start in")
                .containsExactly("Finished", "Rejected");
        assertThat(board.issues())
                .as("each repository's issues, groups and sub-issues among them")
                .extracting(Issue::id, Issue::repository, Issue::state)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("AB01", "porelo-store", "Backlog"),
                        org.assertj.core.groups.Tuple.tuple("AB01.1", "porelo-store", "Review"),
                        org.assertj.core.groups.Tuple.tuple("CD01", "porelo-other", "Review"));
        assertThat(board.issue("AB01.1").orElseThrow().parent())
                .as("the sub-issue's parent")
                .isEqualTo("AB01");
        assertThat(board.stories())
                .as(
                        "a stated state is the story's; otherwise the earliest open state of its issues")
                .extracting(Story::id, Story::state, Story::stateGiven, Story::issues)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ST1", "Idea", true, List.of("AB01")),
                        org.assertj.core.groups.Tuple.tuple(
                                "ST2", "Backlog", false, List.of("AB01", "CD01")));
        assertThat(board.revision().value())
                .as("the revision is read from both repositories' commits")
                .isEqualTo(
                        GitStore.revision(
                                List.of("porelo-store", "porelo-other"),
                                Map.of(
                                        "porelo-store", repositories.head("porelo-store"),
                                        "porelo-other", repositories.head("porelo-other"))));
    }

    @Test
    void itsTextsAreMarkdown() throws Exception {
        // Named plain, a story's description would show its Markdown signs on the screen.
        assertThat(store(null).textFormat())
                .as("the Git store's stories and issues are Markdown files")
                .isEqualTo(TextFormat.MARKDOWN);
    }

    @Test
    void theWorkRulesAreReadFromTheOwnRepositoryAndTheirAbsenceIsAFault() throws Exception {
        // Without its rules the board is still read; with them, work starts by the project's own.
        final GitStore store = store(null);
        assertThat(store.board().work().faults())
                .as("no stories/work.yml")
                .containsExactly("stories/work.yml is missing, so no work can be started");
        repositories.repository(
                "porelo-store",
                Map.of(
                        "stories/work.yml",
                        """
                        work:
                          Review:
                            from: [Idea]
                            done: Idea
                            per: story
                            modes: [UNATTENDED]
                            order: "Look at {story}."
                            models: {codex: gpt-6.1-sol}
                        """));
        final Board board = store.refresh();
        assertThat(board.work().faults()).as("the rules read").isEmpty();
        assertThat(board.work().rules().keySet())
                .as("the rule of the own repository")
                .containsExactly("Review");
    }

    @Test
    void anIssueWithoutAJsonHeadIsAFaultNamingItsFileAndTheBoardIsStillRead() throws Exception {
        // One file a person got wrong must not take the whole project's board away.
        repositories.repository(
                "porelo-other",
                Map.of("issues/CD02-Headless.md", "# CD02 — Headless\n\n**Status:** Idea\n"));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("the file is named with its repository and why it was left out")
                .contains("porelo-other: issues/CD02-Headless.md: it has no JSON head");
        assertThat(board.issues())
                .as("the other issues are read")
                .extracting(Issue::id)
                .containsExactlyInAnyOrder("AB01", "AB01.1", "CD01");
    }

    @Test
    void aStoryNamingAnIssueUnderAnotherRepositoryIsAFaultAndTheStoryIsShownWithout()
            throws Exception {
        // Read by its number alone, the story would show another repository's issue as its own.
        repositories.repository(
                "porelo-store",
                Map.of(
                        "stories/ST3-Wrong.md",
                        story(
                                "ST3",
                                "Wrong",
                                "\"state\": \"Idea\",",
                                "\"porelo-other#AB01\", \"porelo-other#CD01\"")));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("the reference is named and left out")
                .contains(
                        "ST3: porelo-other#AB01 is no issue of the project's repositories, and"
                                + " is left out");
        assertThat(board.stories())
                .as("the story keeps its sound references, and the other stories stand")
                .extracting(Story::id, Story::issues)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("ST3", List.of("CD01")),
                        org.assertj.core.groups.Tuple.tuple("ST1", List.of("AB01")));
    }

    @Test
    void aStoryWhoseFileCannotBeReadIsAFaultAndTheOtherStoriesAreShown() throws Exception {
        repositories.repository(
                "porelo-store", Map.of("stories/ST4-Broken.md", "# ST4 — Broken\n\nNo data.\n"));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("the story's file is named")
                .anyMatch(fault -> fault.startsWith("porelo-store: stories/ST4-Broken.md: "));
        assertThat(board.stories())
                .as("the other stories are read")
                .extracting(Story::id)
                .containsExactly("ST1", "ST2");
    }

    // Prevents a story's size from being lost between its head and the board, and a size outside
    // XS to XL from reaching the board.
    @Test
    void aStorysSizeIsTheOneItsHeadGivesAndOneOutsideTheSizesIsAFault() throws Exception {
        repositories.repository(
                "porelo-store",
                Map.of(
                        "stories/ST3-Sized.md",
                        story(
                                "ST3",
                                "Sized",
                                "\"state\": \"Idea\",\n  \"size\": \"M\",",
                                "\"porelo-store#AB01\""),
                        "stories/ST5-Oversized.md",
                        story(
                                "ST5",
                                "Oversized",
                                "\"state\": \"Idea\",\n  \"size\": \"XXL\",",
                                "\"porelo-store#AB01\"")));
        final Board board = store(null).board();
        assertThat(board.stories())
                .as("the size the head gives, and none for a head without one")
                .extracting(Story::id, Story::size)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ST1", null),
                        org.assertj.core.groups.Tuple.tuple("ST2", null),
                        org.assertj.core.groups.Tuple.tuple("ST3", "M"));
        assertThat(board.faults())
                .as("the story with a size outside XS to XL is named and left out")
                .anyMatch(
                        fault ->
                                fault.startsWith("porelo-store: stories/ST5-Oversized.md: ")
                                        && fault.contains("/size"));
    }

    @Test
    void anIssueWhoseHeadBrokeIsLeftOutAndNotShownFromItsIndexRow() throws Exception {
        // Shown from its row, the issue would hand an agent an empty brief and refuse every move.
        repositories.repository(
                "porelo-other",
                Map.of(
                        "issues/README.md",
                        "# Issues\n\n## Review\n\n| # | Size | Blocked by | What it covers |\n|---|---|---|---|\n"
                                + "| <a id=\"cd01\"></a>[CD01](base/CD01-Grouped.md) | S | - | Something. |\n",
                        "issues/base/CD01-Grouped.md",
                        "# CD01 — Grouped\n\n```json\n{ broken\n```\n"));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("the broken file is named, and the index itself is read")
                .anyMatch(fault -> fault.startsWith("porelo-other: issues/base/CD01-Grouped.md: "))
                .noneMatch(fault -> fault.contains("README.md"));
        assertThat(board.issues())
                .as("the issue is left out, not taken from its row")
                .extracting(Issue::id)
                .containsExactlyInAnyOrder("AB01", "AB01.1");
    }

    @Test
    void twoFilesOfOneIdAreAFaultAndBothAreLeftOut() throws Exception {
        // Kept as one, a move would write whichever file was read last.
        repositories.repository(
                "porelo-store",
                Map.of(
                        "stories/ST1-Copy.md",
                        story("ST1", "Copy", "\"state\": \"Idea\",", "\"porelo-store#AB01\""),
                        "issues/AB01-Copy.md",
                        issue("AB01", "Copy", "Backlog", "")));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("each id read from two files is named")
                .anyMatch(fault -> fault.contains("ST1") && fault.contains("two files"))
                .anyMatch(fault -> fault.contains("AB01") && fault.contains("two files"));
        assertThat(board.stories())
                .as("neither copy of the story is shown")
                .extracting(Story::id)
                .doesNotContain("ST1");
        assertThat(board.issues())
                .as("neither copy of the issue is shown")
                .extracting(Issue::id)
                .doesNotContain("AB01");
    }

    @Test
    void anIssueInAStateTheBoardDoesNotKnowIsAFault() throws Exception {
        repositories.repository(
                "porelo-other",
                Map.of(
                        "issues/workflow.yml",
                        WORKFLOW.replace("Backlog", "Waiting"),
                        "issues/CD02-Waiting.md",
                        issue("CD02", "Waiting", "Waiting", "")));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("the issue and its state are named")
                .anyMatch(fault -> fault.contains("CD02") && fault.contains("Waiting"));
        assertThat(board.issues()).as("the others stand").extracting(Issue::id).contains("CD01");
    }

    @Test
    void aRepositorysIssuesAreCheckedAgainstTheProjectsWorkflowAndNotItsOwn() throws Exception {
        // A repository's own workflow would keep issues in states the board cannot show, and
        // leave out issues in states it can.
        repositories.repository(
                "porelo-other",
                Map.of(
                        "issues/workflow.yml",
                        WORKFLOW.replace("Backlog", "Waiting"),
                        "issues/CD02-Waiting.md",
                        issue("CD02", "Waiting", "Waiting", ""),
                        "issues/CD03-Planned.md",
                        issue("CD03", "Planned", "Backlog", "")));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("the issue in a state only the repository's own workflow names is a fault")
                .anyMatch(fault -> fault.contains("CD02") && fault.contains("Waiting"))
                .noneMatch(fault -> fault.contains("CD03"));
        assertThat(board.issues())
                .as("the board is served, with the issue in a state of the project's workflow")
                .extracting(Issue::id)
                .containsExactlyInAnyOrder("AB01", "AB01.1", "CD01", "CD03");
    }

    @Test
    void aRepositorysOwnWorkflowFileIsNotReadEvenWhenItIsBroken() throws Exception {
        // A file no longer read must not take the whole project's board away.
        repositories.repository("porelo-other", Map.of("issues/workflow.yml", "states: [\n"));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("nothing is said of a file that is not read")
                .noneMatch(fault -> fault.contains("workflow.yml"));
        assertThat(board.issues())
                .as("the repository's issues are read against the project's workflow")
                .extracting(Issue::id)
                .containsExactlyInAnyOrder("AB01", "AB01.1", "CD01");
    }

    @Test
    void aLinkToAnIndexRowWithoutItsAnchorIsAFaultNamingThePageAndLine() throws Exception {
        // A link that reaches no row sends a reader nowhere once the issue's file is gone.
        final String linking = issue("CD02", "Linking", "Review", "");
        repositories.repository(
                "porelo-other",
                Map.of(
                        "issues/README.md",
                        "# Issues\n\n## Review\n\n| # | Size | Blocked by | What it covers |\n"
                                + "|---|---|---|---|\n| CD01 | S | - | Grouped. |\n"
                                + "| <a id=\"cd02\"></a>CD02 | S | - | Linking. |\n",
                        "issues/CD02-Linking.md",
                        linking + "After [CD01](README.md#cd01).\n"));
        final Board board = store(null).board();
        final int line = linking.split("\n", -1).length;
        assertThat(board.faults())
                .as("the link is named once, with its page and line")
                .filteredOn(fault -> fault.startsWith("porelo-other: issues/CD02-Linking.md:"))
                .containsExactly(
                        "porelo-other: issues/CD02-Linking.md:"
                                + line
                                + ": README.md#cd01 reaches no row's anchor");
        assertThat(board.faults())
                .as("the row without its anchor is named on its own line")
                .contains(
                        "porelo-other: issues/README.md:7: the row of CD01 does not open with its"
                                + " anchor <a id=\"cd01\"></a>");
        assertThat(board.issues())
                .as("the board is served")
                .extracting(Issue::id)
                .containsExactlyInAnyOrder("AB01", "AB01.1", "CD01", "CD02");
    }

    @Test
    void aLinkToAPageThatIsALinkIsAFaultAndTheBoardIsStillRead() throws Exception {
        // A page a link names is read only for its anchors; one that is no plain file must not
        // take the project's board away.
        Files.createSymbolicLink(
                repositories.work("porelo-other").resolve("issues/notes.md"),
                Path.of("/etc/hostname"));
        final String linking = issue("CD02", "Linking", "Review", "");
        repositories.repository(
                "porelo-other",
                Map.of("issues/CD02-Linking.md", linking + "Why: [the notes](notes.md#cd01).\n"));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("the link is named with its page and line")
                .contains(
                        "porelo-other: issues/CD02-Linking.md:"
                                + linking.split("\n", -1).length
                                + ": notes.md#cd01 reaches no row's anchor");
        assertThat(board.issues())
                .as("the board is served")
                .extracting(Issue::id)
                .contains("CD01", "CD02");
    }

    @Test
    void aLinkToAPathNoReadingTakesIsAFaultAndTheBoardIsStillRead() throws Exception {
        // A page's link is text anyone writes; a path the store will not read must not take the
        // project's board away.
        final String linking = issue("CD02", "Linking", "Review", "");
        repositories.repository(
                "porelo-other",
                Map.of("issues/CD02-Linking.md", linking + "Why: [hidden](.notes.md#cd01).\n"));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("the link is named with its page and line")
                .contains(
                        "porelo-other: issues/CD02-Linking.md:"
                                + linking.split("\n", -1).length
                                + ": .notes.md#cd01 reaches no row's anchor");
        assertThat(board.issues())
                .as("the board is served")
                .extracting(Issue::id)
                .contains("CD01", "CD02");
    }

    @Test
    void anIndexThatCannotBeReadIsAFaultAndTheIssueFilesAreStillRead() throws Exception {
        repositories.repository(
                "porelo-other",
                Map.of(
                        "issues/README.md",
                        "# Issues\n\n## Nowhere\n\n| # | Size | Blocked by | What it covers |\n|---|---|---|---|\n"
                                + "| <a id=\"cd09\"></a>CD09 | S | - | Something. |\n"));
        final Board board = store(null).board();
        assertThat(board.faults())
                .as("the index is named")
                .anyMatch(fault -> fault.startsWith("porelo-other: issues/README.md: "));
        assertThat(board.issues())
                .as("the issue files are read")
                .extracting(Issue::id)
                .contains("CD01");
    }

    @Test
    void anIssueThatIsALinkIsRefused() throws Exception {
        // A link in someone's repository would read a file of the person's into the board.
        Files.createSymbolicLink(
                repositories.work("porelo-other").resolve("issues/CD03-Link.md"),
                Path.of("/etc/hostname"));
        repositories.commit("porelo-other");
        assertThatThrownBy(() -> store(null).board())
                .as("a link among the issues")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("is not a plain file (mode 120000)");
    }

    @Test
    void aRefusedLoginReadsAsCredentialRefusedAtOnceWithNoCredentialInIt() throws Exception {
        final long start = System.nanoTime();
        assertThatThrownBy(() -> store("person:right").board())
                .as("a store whose remote wants a login the person has not")
                .isInstanceOf(StoreException.CredentialRefused.class)
                .hasMessageContaining("porelo-store")
                .hasMessageNotContaining("right");
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("at once")
                .isLessThan(Duration.ofSeconds(15));
    }

    private static String story(
            final String id, final String title, final String state, final String issues) {
        return "# "
                + id
                + " — "
                + title
                + "\n\nWhat a person wrote.\n\n## Data\n\n```json\n{\n  \"$schema\": \"story-2.json\",\n  \"id\": \""
                + id
                + "\",\n  \"title\": \""
                + title
                + "\",\n  "
                + state
                + "\n  \"gain\": \"Something.\",\n  \"test\": \"A test.\",\n  \"priority\": \"High\",\n"
                + "  \"issues\": ["
                + issues
                + "],\n  \"tested\": null\n}\n```\n";
    }

    private static String issue(
            final String id, final String title, final String state, final String parent) {
        return "# "
                + id
                + " — "
                + title
                + "\n\n```json\n{\n  \"$schema\": \"issue-1.json\",\n  \"id\": \""
                + id
                + "\",\n  \"title\": \""
                + title
                + "\",\n  \"state\": \""
                + state
                + "\",\n  \"size\": \"S\",\n  \"priority\": \"Medium\",\n  \"rank\": 1,\n  \"blockedBy\": [],\n  "
                + parent
                + "\n  \"covers\": \"Something.\"\n}\n```\n\n**What must be true.** Something.\n";
    }
}
