package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
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

    private Path served;

    private Map<String, String> person;

    @BeforeEach
    void serve() throws Exception {
        served = Files.createDirectories(dir.resolve("served"));
        repository(
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
        repository(
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
        server = GitHttpsServer.start(served, dir, login);
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
                .extracting(Story::id, Story::state, Story::issues)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ST1", "Idea", List.of("AB01")),
                        org.assertj.core.groups.Tuple.tuple(
                                "ST2", "Backlog", List.of("AB01", "CD01")));
        assertThat(board.revision().value())
                .as("the revision names both repositories")
                .contains("porelo-store@", " porelo-other@");
    }

    @Test
    void theWorkRulesAreReadFromTheOwnRepositoryAndTheirAbsenceIsAFault() throws Exception {
        // Without its rules the board is still read; with them, work starts by the project's own.
        final GitStore store = store(null);
        assertThat(store.board().work().faults())
                .as("no stories/work.yml")
                .containsExactly("stories/work.yml is missing, so no work can be started");
        repository(
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
    void anIssueWithoutAJsonHeadIsAFaultNamingItsFile() throws Exception {
        // A board that skipped it would show a project with an issue missing.
        repository(
                "porelo-other",
                Map.of("issues/CD02-Headless.md", "# CD02 — Headless\n\n**Status:** Idea\n"));
        assertThatThrownBy(() -> store(null).board())
                .as("an issue file with no head")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining("porelo-other")
                .hasMessageContaining("issues/CD02-Headless.md has no JSON head");
    }

    @Test
    void aStoryNamingAnIssueUnderAnotherRepositoryIsAFault() throws Exception {
        // Read by its number alone, the story would show another repository's issue as its own.
        repository(
                "porelo-store",
                Map.of("stories/ST3-Wrong.md", story("ST3", "Wrong", "", "\"porelo-other#AB01\"")));
        assertThatThrownBy(() -> store(null).board())
                .as("a story naming AB01 under the wrong repository")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageContaining(
                        "ST3: porelo-other#AB01 is no issue of the project's repositories");
    }

    @Test
    void anIssueThatIsALinkIsRefused() throws Exception {
        // A link in someone's repository would read a file of the person's into the board.
        final Path work = dir.resolve("work-porelo-other");
        Files.createSymbolicLink(work.resolve("issues/CD03-Link.md"), Path.of("/etc/hostname"));
        commit(work, "porelo-other");
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

    /** Writes a repository's files, commits them and serves the commit as {@code <name>.git}. */
    private void repository(final String name, final Map<String, String> files) throws Exception {
        final Path work = dir.resolve("work-" + name);
        if (!Files.exists(work)) {
            Files.createDirectories(work);
            git(work, "init", "--quiet", "--initial-branch=main");
        }
        for (final Map.Entry<String, String> file : files.entrySet()) {
            final Path path = work.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        commit(work, name);
    }

    private void commit(final Path work, final String name) throws Exception {
        git(work, "add", "-A");
        git(
                work,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "commit",
                "--quiet",
                "-m",
                "porelo-store");
        final Path bare = served.resolve(name + ".git");
        if (Files.exists(bare)) {
            git(work, "push", "--quiet", "--force", bare.toString(), "main");
        } else {
            git(dir, "clone", "--quiet", "--bare", work.toString(), bare.toString());
        }
    }

    private static String git(final Path where, final String... arguments) throws Exception {
        final List<String> line = new ArrayList<>(List.of("git"));
        line.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(line).directory(where.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", arguments) + ": " + out);
        }
        return out;
    }
}
