package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.git.stories.IssueHead;
import org.fuin.porelo.planner.store.git.stories.IssueSource;
import org.fuin.porelo.planner.store.git.stories.IssuesIndex;
import org.fuin.porelo.planner.store.git.stories.StageRecords;
import org.fuin.porelo.planner.store.git.stories.StoriesIndex;
import org.fuin.porelo.planner.store.git.stories.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Saves moves into two repositories served over HTTPS, as the planner saves a project's moves: one
 * commit per repository changed, pushed all or reported as partly saved.
 */
class GitStoreSaveTest {

    private static final String OWN = "porelo-store";

    private static final String OTHER = "porelo-other";

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

    /** A closed line as the build tools' stage command writes it for an agent's work. */
    private static final String AGENT_LINE =
            "{\"issue\":\"CD01\",\"stage\":\"implement\",\"n\":1,\"actor\":\"agent\","
                    + "\"model\":null,\"effort\":null,\"size\":\"S\",\"status_before\":\"Backlog\","
                    + "\"status_after\":\"Backlog\",\"start\":\"2026-01-01T10:00:00Z\","
                    + "\"end\":\"2026-01-01T10:04:00Z\",\"wall_min\":4.0,\"vm_min\":0,"
                    + "\"estimate_min\":null,\"result\":\"done\",\"wait_reason\":null,"
                    + "\"note\":\"no-session\",\"claude_input_tokens\":null,"
                    + "\"claude_output_tokens\":null,\"claude_cache_read_tokens\":null,"
                    + "\"claude_cache_write_tokens\":null,\"claude_messages\":null,"
                    + "\"codex_input_tokens\":null,\"codex_cached_tokens\":null,"
                    + "\"codex_output_tokens\":null,\"codex_reasoning_tokens\":null,"
                    + "\"agy_input_tokens\":null,\"agy_cache_read_tokens\":null,"
                    + "\"agy_output_tokens\":null}\n";

    @TempDir Path dir;

    private GitHttpsServer server;

    private GitRemote remote;

    private ServedRepositories repositories;

    private Map<String, String> person;

    @BeforeEach
    void serve() throws Exception {
        final Map<String, String> own = new LinkedHashMap<>();
        own.put(
                "project.yml",
                "project:\n  name: \"store\"\n  central: \"git@github.com:porelo-ai/porelo-store.git\"\n"
                        + "repositories:\n  other:\n"
                        + "    central: \"git@github.com:porelo-ai/porelo-other.git\"\n"
                        + "    issues: [\"CD\"]\n");
        own.put("stories/workflow.yml", WORKFLOW);
        own.put("issues/workflow.yml", WORKFLOW);
        own.put(
                "stories/ST1-Served.md",
                story("ST1", "", "\"porelo-store#AB01\", \"porelo-other#CD01\""));
        own.put("stories/ST2-Alone.md", story("ST2", "  \"state\": \"Idea\",\n", ""));
        // A head shown as an example in the body stands above the story's own, at the end.
        own.put(
                "stories/ST3-Shown.md",
                story("ST3", "  \"state\": \"Idea\",\n", "")
                        .replace(
                                "What a person wrote.\n",
                                "A head reads:\n\n## Data\n\n```json\n{\n  \"state\": \"Idea\",\n"
                                        + "  \"issues\": []\n}\n```\n"));
        own.put("issues/AB01-Own.md", issue("AB01", "Backlog"));
        own.put("issues/AB02-Kept.md", issue("AB02", "Backlog"));
        final Map<String, String> other = new LinkedHashMap<>();
        other.put("issues/workflow.yml", WORKFLOW);
        other.put("issues/CD01-Other.md", issue("CD01", "Backlog"));
        repositories = new ServedRepositories(dir);
        serveWithIndexes(own, other);
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

    @Test
    void anIssueMoveCommitsTheIssueAndBothIndexesAndPushesTheOthersFirst() throws Exception {
        // A save that left an index behind would show the board otherwise than the files say.
        // CD01 to Idea moves its story to Idea too, so the stories index changes.
        final GitStore store = store();
        final Board board = store.board();
        final String ownBefore = head(OWN);

        final Board saved =
                store.save(board.revision(), writes(Map.of("CD01", "Idea"), Map.of(), List.of()));

        assertThat(saved.issue("CD01").orElseThrow().state())
                .as("the board after the save shows the move")
                .isEqualTo("Idea");
        assertThat(saved.revision()).as("a new revision").isNotEqualTo(board.revision());
        assertThat(names(OTHER, head(OTHER)))
                .as(
                        "the other repository's commit changes its issue, its index and its record alone")
                .containsExactlyInAnyOrder(
                        "issues/CD01-Other.md", "issues/README.md", "stats/CD01.jsonl");
        assertThat(read(OTHER, "issues/CD01-Other.md"))
                .as("the head carries the new state")
                .contains("\"state\": \"Idea\"");
        assertThat(names(OWN, head(OWN)))
                .as("the own repository's commit rewrites the stories index and the story's text")
                .containsExactlyInAnyOrder("stories/README.md", "stories/ST1-Served.md");
        assertThat(indexFaults())
                .as("what is pushed is what the repositories' index check accepts")
                .isEmpty();
        assertThat(git(OWN, "rev-parse", head(OWN) + "^").strip())
                .as("the own commit follows the commit the board was read at")
                .isEqualTo(ownBefore);
        assertThat(log(OTHER, head(OTHER), "%an <%ae>%n%B"))
                .as("the person's own login and a subject naming no item, with no trailer")
                .hasSize(3)
                .satisfies(
                        lines -> {
                            assertThat(lines.get(0))
                                    .as("the person's own login")
                                    .isEqualTo("A Person <person@example.org>");
                            assertThat(lines.get(1)).as("no build started").endsWith(" [skip ci]");
                            assertThat(lines.get(2)).as("no trailer").isEmpty();
                        });
    }

    @Test
    void anIssueMovedToFinishedIsSettledAndAMentionOutsideTheIssuesIsAFaultAfterwards()
            throws Exception {
        // A finished issue's file left behind, or its number left in a document, sends a reader
        // to an issue nobody keeps any more.
        repositories.repository(OTHER, Map.of("doc/guide.md", "How it works.\nAs CD01 asked.\n"));
        final GitStore store = store();
        final Board board = store.board();
        assertThat(board.faults())
                .as("an open issue's number is not judged here")
                .noneMatch(fault -> fault.contains("doc/guide.md"));

        final Board saved =
                store.save(
                        board.revision(), writes(Map.of("CD01", "Finished"), Map.of(), List.of()));

        assertThat(names(OTHER, head(OTHER)))
                .as("one commit deletes the file, rewrites the index and records the move")
                .containsExactlyInAnyOrder(
                        "issues/CD01-Other.md", "issues/README.md", "stats/CD01.jsonl");
        assertThat(
                        StageRecords.read(
                                read(OTHER, "stats/CD01.jsonl"),
                                "CD01.jsonl",
                                true,
                                Workflow.read(WORKFLOW)))
                .as("the settled issue's record stays, its last line the move to Finished")
                .last()
                .satisfies(
                        record ->
                                assertThat(record)
                                        .as("the move")
                                        .containsEntry("stage", "move")
                                        .containsEntry("status_after", "Finished"));
        assertThat(git(OTHER, "ls-tree", "-r", "--name-only", head(OTHER)))
                .as("the issue's file is gone")
                .doesNotContain("issues/CD01-Other.md");
        assertThat(read(OTHER, "issues/README.md"))
                .as(
                        "its row is kept under Finished, anchored, with its title and no link to the file")
                .contains("## Finished")
                .contains("| <a id=\"cd01\"></a>CD01 | An issue | Something. |")
                .doesNotContain("CD01-Other.md");
        assertThat(saved.issue("CD01").orElseThrow())
                .as("the board answers the settled issue from its row")
                .satisfies(
                        issue -> {
                            assertThat(issue.state()).as("state").isEqualTo("Finished");
                            assertThat(issue.title()).as("title").isEqualTo("An issue");
                            assertThat(issue.repository()).as("repository").isEqualTo(OTHER);
                            assertThat(issue.text()).as("no text").isEmpty();
                        });
        assertThat(saved.faults())
                .as("the document still naming the settled number, with its line")
                .contains("porelo-other: doc/guide.md:2: CD01 names an issue that is settled");
        assertThat(indexFaults())
                .as("what is pushed is what the repositories' index check accepts")
                .isEmpty();
        assertThat(freshBoard().issue("CD01").orElseThrow().state())
                .as("a fresh clone reads the settled issue back from its row")
                .isEqualTo("Finished");
    }

    @Test
    void aSaveChecksARepositorysIssuesAgainstTheProjectsWorkflowAndNotItsOwnFile()
            throws Exception {
        // A file the board no longer reads must not refuse a move the board offers.
        repositories.repository(OTHER, Map.of("issues/workflow.yml", "states: [\n"));
        final GitStore store = store();
        final Board board = store.board();

        final Board saved =
                store.save(board.revision(), writes(Map.of("CD01", "Idea"), Map.of(), List.of()));

        assertThat(saved.issue("CD01").orElseThrow().state())
                .as("the move is saved against the project's workflow")
                .isEqualTo("Idea");
    }

    // Prevents a credential in a central the saved project.yml names from reaching the window.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "me:s3cret@git.example.org:porelo-ai/porelo-other.git | s3cret",
                "https://me:pa@ssword@git.example.org/porelo-ai/porelo-other.git | ssword",
                "https://ghp_secretToken@git.example.org/porelo-ai/porelo-other.git | secretToken",
                "https://git.example.org/porelo-ai/porelo-other.git?access_token=s3cret | s3cret"
            })
    void aSaveOverACentralWithACredentialIsRefusedWithoutIt(
            final String central, final String secret) throws Exception {
        final Path file = repositories.work(OWN).resolve("project.yml");
        Files.writeString(
                file,
                Files.readString(file)
                        .replace("git@github.com:porelo-ai/porelo-other.git", central));
        repositories.commit(OWN);
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);

        final StoreException refused =
                catchThrowableOfType(
                        StoreException.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("AB01", "Review"), Map.of(), List.of())));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.getMessage())
                .as("the refusal never shows the credential %s", secret)
                .contains("central")
                .doesNotContain(secret);
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
    }

    @Test
    void aSaveAgainstAnOlderRevisionIsRefusedAndPushesNothing() throws Exception {
        // A save over a board the window no longer shows would undo someone else's move.
        final GitStore store = store();
        final Board board = store.board();
        final String other = head(OTHER);
        final String own = head(OWN);
        Files.writeString(
                repositories.work(OTHER).resolve("issues/CD01-Other.md"), issue("CD01", "Idea"));
        repositories.commit(OTHER);
        final String moved = head(OTHER);

        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("AB01", "Review"), Map.of(), List.of())));

        assertThat(stale).as("refused as stale").isNotNull();
        assertThat(stale.current())
                .as("names the remote's revision, as a fresh read gives it")
                .isEqualTo(store.refresh().revision().value());
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
        assertThat(moved).isNotEqualTo(other);
    }

    @Test
    void aSaveAgainstARevisionTheStoreNeverShowedIsRefusedWhileTheRemotesStayPut()
            throws Exception {
        // A window's revision that is not the board's means it checked its moves on another board.
        final GitStore store = store();
        store.board();
        final String own = head(OWN);

        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                store.save(
                                        new org.fuin.porelo.planner.store.Revision("old"),
                                        writes(Map.of("AB01", "Review"), Map.of(), List.of())));

        assertThat(stale).as("refused as stale").isNotNull();
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
    }

    @Test
    void aPushOntoABranchRewoundSinceTheFetchIsRejectedAndRestoresNothing() throws Exception {
        // Pushed without a lease, a commit made on the dropped tip would bring dropped work back;
        // the clone knows the commit the branch is rewound to, as it does after an earlier fetch.
        final GitStore store = store();
        store.board();
        final String rewound = head(OTHER);
        Files.writeString(
                repositories.work(OTHER).resolve("issues/CD01-Other.md"), issue("CD01", "Idea"));
        repositories.commit(OTHER);
        final String fetched = head(OTHER);
        store.refresh();
        git(OTHER, "update-ref", "refs/heads/main", rewound);
        final Path root = Files.createDirectories(dir.resolve("push"));
        Files.createDirectories(root.resolve("issues"));
        Files.writeString(root.resolve("issues/CD01-Other.md"), issue("CD01", "Review"));
        final String commit =
                remote.commit(
                        OTHER, fetched, root, List.of("issues/CD01-Other.md"), "Move an issue");

        final GitFailure rejected =
                catchThrowableOfType(
                        GitFailure.class,
                        () ->
                                remote.push(
                                        OTHER,
                                        new CloneUrl(server.url(OTHER + ".git")),
                                        "main",
                                        commit,
                                        fetched));

        assertThat(rejected).as("the push is refused").isNotNull();
        assertThat(rejected.kind()).as("as a moved branch").isEqualTo(GitFailure.Kind.REJECTED);
        assertThat(head(OTHER)).as("the branch stays rewound").isEqualTo(rewound);
    }

    @Test
    void aFailedPushAfterAnotherIsReportedAsPartlySavedNamingBoth() throws Exception {
        // A save told as failed after one repository took it would hide a half-made move.
        final Path hook = repositories.served().resolve(OWN + ".git/hooks/pre-receive");
        Files.writeString(hook, "#!/bin/sh\necho refused by the test >&2\nexit 1\n");
        hook.toFile().setExecutable(true);
        final GitStore store = store();
        final Board board = store.board();

        final StoreException.PartlySaved partly =
                catchThrowableOfType(
                        StoreException.PartlySaved.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(
                                                Map.of("AB01", "Review", "CD01", "Review"),
                                                Map.of(),
                                                List.of())));

        assertThat(partly).as("partly saved").isNotNull();
        assertThat(partly.subject()).as("the repository that failed").isEqualTo(OWN);
        assertThat(partly.pushed()).as("the one that took it").containsExactly(OTHER);
        assertThat(store.board().issue("CD01").orElseThrow().state())
                .as("the board shows what was pushed")
                .isEqualTo("Review");
        assertThat(store.board().issue("AB01").orElseThrow().state())
                .as("and not what was refused")
                .isEqualTo("Backlog");
    }

    @Test
    void aMoveOfAnIssueWithoutARecordWritesAMoveLineTheRecordsReaderTakesBack() throws Exception {
        // A move left out of the issue's records loses when its state changed, and a repository
        // that records stats would refuse the planner's commit without them.
        Files.createDirectories(repositories.work(OTHER).resolve("stats"));
        Files.writeString(
                repositories.work(OTHER).resolve("stats/seed.csv"),
                "size,minutes\nXS,8\nS,27\nM,53\nL,80\n");
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();
        final Instant before = Instant.now();

        store.save(board.revision(), writes(Map.of("CD01", "Review"), Map.of(), List.of()));

        final Instant after = Instant.now();
        assertThat(names(OTHER, head(OTHER)))
                .as("the record is written in the move's own commit")
                .containsExactlyInAnyOrder(
                        "issues/CD01-Other.md", "issues/README.md", "stats/CD01.jsonl");
        final List<Map<String, @Nullable Object>> records =
                StageRecords.read(
                        read(OTHER, "stats/CD01.jsonl"),
                        "CD01.jsonl",
                        true,
                        Workflow.read(WORKFLOW));
        assertThat(records).as("one closed line, read back by the records' reader").hasSize(1);
        final Map<String, @Nullable Object> record = records.get(0);
        assertThat(record)
                .as("a person's move with the old and new state and the issue's size")
                .containsEntry("issue", "CD01")
                .containsEntry("stage", "move")
                .containsEntry("n", 1)
                .containsEntry("actor", "person")
                .containsEntry("size", "S")
                .containsEntry("status_before", "Backlog")
                .containsEntry("status_after", "Review")
                .containsEntry("result", "done")
                .containsEntry("wall_min", null)
                .containsEntry("vm_min", null)
                .containsEntry("note", null)
                .doesNotContainKey("session");
        assertThat(record.get("end"))
                .as("closed the moment it starts")
                .isEqualTo(record.get("start"));
        assertThat(Instant.parse((String) record.get("start")))
                .as("the time of the move")
                .isBetween(before, after);
        assertThat(read(OTHER, "stats/CD01.jsonl"))
                .as("no local path, account or machine in the public record")
                .doesNotContain(dir.toString())
                .doesNotContain("person@example.org")
                .doesNotContain("A Person");
    }

    @Test
    void aMoveIsAppendedToAnIssuesRecordKeepingEveryEarlierLineAndNumberedAfterItsMoves()
            throws Exception {
        // A move that rewrote or dropped an agent's line would lose measured work.
        Files.createDirectories(repositories.work(OTHER).resolve("stats"));
        Files.writeString(repositories.work(OTHER).resolve("stats/CD01.jsonl"), AGENT_LINE);
        repositories.commit(OTHER);
        final GitStore store = store();
        Board board = store.board();

        board = store.save(board.revision(), writes(Map.of("CD01", "Review"), Map.of(), List.of()));
        store.save(board.revision(), writes(Map.of("CD01", "Idea"), Map.of(), List.of()));

        final String text = read(OTHER, "stats/CD01.jsonl");
        assertThat(text).as("the agent's line is kept as it was, first").startsWith(AGENT_LINE);
        final List<Map<String, @Nullable Object>> records =
                StageRecords.read(text, "CD01.jsonl", true, Workflow.read(WORKFLOW));
        assertThat(records.stream().map(record -> record.get("stage")).toList())
                .as("the agent's stage and then the two moves")
                .containsExactly("implement", "move", "move");
        assertThat(records.stream().map(record -> record.get("n")).toList())
                .as("moves numbered among themselves, after the agent's own")
                .containsExactly(1, 1, 2);
        assertThat(records.get(2))
                .as("the second move leaves the state the first one set")
                .containsEntry("status_before", "Review")
                .containsEntry("status_after", "Idea");
    }

    @Test
    void aMoveOfAnIssueWhoseRecordIsBrokenIsRefusedNamingTheFileAndPushesNothing()
            throws Exception {
        // A line added to a record nobody can read would hide the fault under one more commit.
        Files.createDirectories(repositories.work(OTHER).resolve("stats"));
        Files.writeString(repositories.work(OTHER).resolve("stats/CD01.jsonl"), "{\"issue\":\n");
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);
        final String other = head(OTHER);

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(
                                                Map.of("AB01", "Review", "CD01", "Review"),
                                                Map.of(),
                                                List.of())));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.subject()).as("names the issue").isEqualTo("CD01");
        assertThat(refused.getMessage())
                .as("names the record's file and why")
                .startsWith("stats/CD01.jsonl cannot take the move: ");
        assertThat(List.of(head(OWN), head(OTHER)))
                .as("nothing pushed")
                .containsExactly(own, other);
    }

    @Test
    void theVelocityIsTheSeedsAndFinishedWorksMedianAndAMoveLeavesItAsItWas() throws Exception {
        // A velocity counting a person's move, or taken from no seed, would mislead an estimate.
        Files.createDirectories(repositories.work(OTHER).resolve("stats"));
        Files.writeString(
                repositories.work(OTHER).resolve("stats/seed.csv"),
                "size,minutes\nXS,8\nS,27\nM,53\nL,80\n");
        Files.writeString(
                repositories.work(OTHER).resolve("stats/CD01.jsonl"),
                AGENT_LINE.replace(
                        "\"status_after\":\"Backlog\"", "\"status_after\":\"Finished\""));
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();

        assertThat(store.velocity(OTHER, "S"))
                .as("the median of the seed's 27 and the finished issue's 4")
                .contains(15.5);
        store.save(board.revision(), writes(Map.of("CD01", "Review"), Map.of(), List.of()));
        assertThat(store.velocity(OTHER, "S"))
                .as("the move is recorded but counts for nothing")
                .contains(15.5);
        assertThat(store.velocity(OWN, "S"))
                .as("a repository without a seed has no velocity yet")
                .isEmpty();
    }

    @Test
    void anIssueSavedInTheStateItHasRecordsNoMoveAndCommitsNothing() throws Exception {
        // A line for a move nobody made would stand in the issue's history as a state change.
        final GitStore store = store();
        final Board board = store.board();
        final String other = head(OTHER);

        store.save(board.revision(), writes(Map.of("CD01", "Backlog"), Map.of(), List.of()));

        assertThat(head(OTHER)).as("no commit, so no record").isEqualTo(other);
    }

    @Test
    void aSubIssueIsMovedWithoutARecordSinceNoRecordFileCanNameIt() throws Exception {
        // Stage records name top-level issues only, and refusing the move would strand a sub-issue.
        final Path issues = repositories.work(OTHER).resolve("issues");
        Files.writeString(
                issues.resolve("CD01.1-Part.md"),
                issue("CD01.1", "Backlog")
                        .replace(
                                "\"Something.\"\n}",
                                "\"Something.\",\n  \"parent\": \"CD01\",\n  \"role\": \"builder\"\n}"));
        IssuesIndex.write(issues);
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();

        store.save(board.revision(), writes(Map.of("CD01.1", "Review"), Map.of(), List.of()));

        assertThat(names(OTHER, head(OTHER)))
                .as("the move is committed, with no record file")
                .containsExactlyInAnyOrder("issues/CD01.1-Part.md", "issues/README.md");
        assertThat(read(OTHER, "issues/CD01.1-Part.md")).contains("\"state\": \"Review\"");
    }

    @Test
    void anIssueWhoseFileIsGoneIsRefusedBeforeAnyWrite() throws Exception {
        // Only an index row is left, and rewriting the index would drop the move again.
        Files.delete(repositories.work(OWN).resolve("issues/AB02-Kept.md"));
        repositories.commit(OWN);
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("AB02", "Review"), Map.of(), List.of())));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.subject()).isEqualTo("AB02");
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
    }

    @Test
    void aStorysStatedStateIsWrittenAndRemovedInItsHead() throws Exception {
        // A stated state left behind would hold the story where its issues no longer stand.
        final GitStore store = store();
        Board board = store.board();

        board =
                store.save(
                        board.revision(), writes(Map.of(), Map.of("ST1", "Rejected"), List.of()));
        assertThat(board.story("ST1").orElseThrow().state()).isEqualTo("Rejected");
        assertThat(read(OWN, "stories/ST1-Served.md")).contains("\"state\": \"Rejected\"");

        board = store.save(board.revision(), writes(Map.of(), Map.of(), List.of("ST1")));
        assertThat(board.story("ST1").orElseThrow().stateGiven()).as("unstated again").isFalse();
        assertThat(read(OWN, "stories/ST1-Served.md")).doesNotContain("\"state\"");
    }

    @Test
    void thePlannersCommitsPassTheRepositorysCommitCheck() throws Exception {
        // A commit the repository's own check refuses would turn its next build red.
        final GitStore store = store();
        final String own = head(OWN);
        final String other = head(OTHER);
        Board board = store.board();
        board = store.save(board.revision(), writes(Map.of("AB01", "Review"), Map.of(), List.of()));
        store.save(
                board.revision(),
                writes(
                        Map.of("AB01", "Finished", "AB02", "Finished", "CD01", "Rejected"),
                        Map.of(),
                        List.of()));

        assertThat(checkCommits(OWN, own)).as("the own repository's check").isZero();
        assertThat(checkCommits(OTHER, other)).as("the other repository's check").isZero();
        assertThat(log(OWN, head(OWN), "%s"))
                .first()
                .as("a batch of planning files starts no build")
                .asString()
                .endsWith(" [skip ci]");
    }

    @Test
    void aMoveTheIndexRefusesIsRefusedNamingItAndPushesNothing() throws Exception {
        // A head pushed without its index would turn the repository's index check red.
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of(), Map.of("ST2", "Finished"), List.of())));

        assertThat(refused).as("refused, a finished story needs its tested day").isNotNull();
        assertThat(refused.subject()).as("naming the story").isEqualTo("ST2");
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
    }

    @Test
    void aStorysStateIsWrittenInTheHeadAtItsEndNotInAnExampleAbove() throws Exception {
        // An edit of the body's example would change the person's text and leave the state.
        final GitStore store = store();
        final Board board = store.board();

        final Board saved =
                store.save(board.revision(), writes(Map.of(), Map.of("ST3", "Review"), List.of()));

        assertThat(saved.story("ST3").orElseThrow().state())
                .as("the story's own head carries the state")
                .isEqualTo("Review");
        assertThat(read(OWN, "stories/ST3-Shown.md"))
                .as("the example keeps its state")
                .contains("A head reads:\n\n## Data\n\n```json\n{\n  \"state\": \"Idea\",");
    }

    @Test
    void aLinkAmongTheIssuesIsRefusedAsTheStoresFailure() throws Exception {
        // The board passes a file it does not read; the save must not fail on it unchecked.
        Files.createSymbolicLink(
                repositories.work(OTHER).resolve("issues/notes.txt"), Path.of("CD01-Other.md"));
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();
        final String other = head(OTHER);

        final Throwable thrown =
                org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("CD01", "Review"), Map.of(), List.of())));

        assertThat(thrown)
                .as("refused as the store's own failure")
                .isInstanceOf(StoreException.class);
        assertThat(head(OTHER)).as("nothing pushed").isEqualTo(other);
    }

    @Test
    void anIssueWhoseHeadCannotBeEditedIsRefusedNamingIt() throws Exception {
        // A refusal naming no item leaves the window unable to say which move stays unsaved.
        Files.writeString(
                repositories.work(OTHER).resolve("issues/CD01-Other.md"),
                issue("CD01", "Backlog").replace("\n", "\r\n"));
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("CD01", "Review"), Map.of(), List.of())));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.subject()).as("naming the issue").isEqualTo("CD01");
    }

    @Test
    void aPushRejectedAsTheBranchMovedNamesTheRevisionTheRemotesHoldNow() throws Exception {
        // Answered with the revision the window sent, the window would retry the same save forever.
        final GitStore store =
                store(
                        real ->
                                remote(
                                        real,
                                        "push",
                                        () -> {
                                            Files.writeString(
                                                    repositories
                                                            .work(OTHER)
                                                            .resolve("issues/CD01-Other.md"),
                                                    issue("CD01", "Idea"));
                                            repositories.commit(OTHER);
                                        }));
        final Board board = store.board();

        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        writes(Map.of("CD01", "Review"), Map.of(), List.of())));

        assertThat(stale).as("refused as stale").isNotNull();
        assertThat(stale.current())
                .as("naming the commits the repositories hold now")
                .isEqualTo(
                        GitStore.revision(
                                List.of(OWN, OTHER), Map.of(OWN, head(OWN), OTHER, head(OTHER))))
                .isNotEqualTo(board.revision().value());
    }

    @Test
    void aSaveWhosePushTookIsToldSavedWhenTheCloneCannotRecordIt() throws Exception {
        // A pushed save told as failed would have the window offer it again over its own result.
        final GitStore store =
                store(
                        real ->
                                remote(
                                        real,
                                        "advance",
                                        () -> {
                                            throw new GitFailure(
                                                    GitFailure.Kind.FAILED, "cannot record");
                                        }));
        final Board board = store.board();

        final Board saved =
                store.save(board.revision(), writes(Map.of("CD01", "Review"), Map.of(), List.of()));

        assertThat(saved.issue("CD01").orElseThrow().state())
                .as("the board shows the pushed move")
                .isEqualTo("Review");
        assertThat(read(OTHER, "issues/CD01-Other.md")).contains("\"state\": \"Review\"");
    }

    @Test
    void anIdeaIsReadBackFromAFreshCloneWithItsHeadItsIndexRowAndANumberNoIndexRowHolds()
            throws Exception {
        // A number taken twice would give two issues one id, and the finished one lives on only
        // in its index row once its file is deleted.
        Files.writeString(
                repositories.work(OTHER).resolve("issues/CD02-Done.md"), issue("CD02", "Finished"));
        IssuesIndex.write(repositories.work(OTHER).resolve("issues"));
        Files.delete(repositories.work(OTHER).resolve("issues/CD02-Done.md"));
        repositories.commit(OTHER);
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);

        final NewIssue.Added added =
                store.createIssue(
                        board.revision(),
                        new NewIssue(OTHER, "A new idea's words", "It is there.\n", "S"));

        assertThat(added.id()).as("the number after the one only a row holds").isEqualTo("CD03");
        assertThat(added.board().issue("CD03").orElseThrow().state())
                .as("the board after the save holds it in Idea")
                .isEqualTo("Idea");
        assertThat(names(OTHER, head(OTHER)))
                .as("one commit adds the issue's file and rewrites the index")
                .containsExactlyInAnyOrder("issues/CD03-A-new-ideas-words.md", "issues/README.md");
        assertThat(head(OWN)).as("the own repository is left alone").isEqualTo(own);
        final Path clone = dir.resolve("fresh");
        git(
                null,
                "clone",
                "--quiet",
                repositories.served().resolve(OTHER + ".git").toString(),
                clone.toString());
        final String text = Files.readString(clone.resolve("issues/CD03-A-new-ideas-words.md"));
        final IssueHead head =
                IssueHead.read(
                        text,
                        Workflow.read(Files.readString(clone.resolve("issues/workflow.yml"))));
        assertThat(head).as("the file opens with a head the schema takes").isNotNull();
        assertThat(List.of(head.id(), head.title(), head.state(), head.size()))
                .as("its id, title, state and size")
                .containsExactly("CD03", "A new idea's words", "Idea", "S");
        assertThat(text)
                .as("the heading and what must be true")
                .startsWith("# CD03 — A new idea's words\n")
                .contains("\n**What must be true.** It is there.\n");
        assertThat(Files.readString(clone.resolve("issues/README.md")))
                .as("the index has its row")
                .contains("<a id=\"cd03\"></a>");
        assertThat(indexFaults()).as("what is pushed is what the index check accepts").isEmpty();
        assertThat(log(OTHER, head(OTHER), "%s"))
                .as("a subject starting no build")
                .singleElement()
                .satisfies(subject -> assertThat(subject).as("marked").endsWith(" [skip ci]"));
    }

    @Test
    void anIdeaInTheOwnRepositoryTakesThePrefixItsIssuesUse() throws Exception {
        // project.yml names no prefix for the own repository; a wrong one would file it elsewhere.
        final GitStore store = store();
        final Board board = store.board();

        final NewIssue.Added added =
                store.createIssue(board.revision(), new NewIssue(OWN, "Own", "Own.", null));

        assertThat(added.id()).as("the own issues' prefix, the next number").isEqualTo("AB03");
        assertThat(read(OWN, "issues/AB03-Own.md")).as("no size yet").contains("\"size\": \"-\"");
    }

    @Test
    void anIdeaForARepositoryOutsideTheProjectWritesNothingAnywhere() throws Exception {
        // Written to a repository the project does not name, the idea would land where nobody
        // plans.
        final java.util.concurrent.atomic.AtomicBoolean begun =
                new java.util.concurrent.atomic.AtomicBoolean();
        final GitStore store = store(real -> remote(real, "scratch", () -> begun.set(true)));
        final Board board = store.board();
        final String own = head(OWN);
        final String other = head(OTHER);

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () ->
                                store.createIssue(
                                        board.revision(),
                                        new NewIssue("porelo-elsewhere", "Idea", "Text.", null)));

        assertThat(refused).as("refused").isNotNull();
        assertThat(begun.get()).as("refused before a save begins to write").isFalse();
        assertThat(List.of(head(OWN), head(OTHER)))
                .as("nothing pushed")
                .containsExactly(own, other);
    }

    @Test
    void anIdeaAgainstAnOlderRevisionIsRefusedAndWritesNothing() throws Exception {
        // An idea numbered on an old board could take a number someone has taken since.
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);
        Files.writeString(
                repositories.work(OTHER).resolve("issues/CD01-Other.md"), issue("CD01", "Idea"));
        repositories.commit(OTHER);
        final String other = head(OTHER);

        final StoreException.StaleRevision stale =
                catchThrowableOfType(
                        StoreException.StaleRevision.class,
                        () ->
                                store.createIssue(
                                        board.revision(),
                                        new NewIssue(OTHER, "Idea", "Text.", null)));

        assertThat(stale).as("refused as stale").isNotNull();
        assertThat(List.of(head(OWN), head(OTHER)))
                .as("nothing pushed")
                .containsExactly(own, other);
    }

    @Test
    void aWorkerSavedAloneCommitsItsHeadAloneAndIsReadBackFromAFreshClone() throws Exception {
        // A worker lost, or written with an index rewritten around it, would start work nobody
        // chose or change files nobody touched.
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);
        final String other = head(OTHER);

        final Board saved =
                store.save(
                        board.revision(),
                        new Writes(Map.of(), Map.of(), List.of(), Map.of("CD01", Worker.MANUAL)));

        assertThat(saved.issue("CD01").orElseThrow().worker())
                .as("the board after the save shows the worker")
                .isEqualTo(Worker.MANUAL);
        assertThat(git(OTHER, "rev-parse", head(OTHER) + "^").strip())
                .as("one commit on the one read")
                .isEqualTo(other);
        assertThat(names(OTHER, head(OTHER)))
                .as("the issue's file alone, the index as it was")
                .containsExactly("issues/CD01-Other.md");
        assertThat(read(OTHER, "issues/CD01-Other.md"))
                .as("the worker written last in the head")
                .contains("  \"covers\": \"Something.\",\n  \"worker\": \"Manual\"\n}\n```");
        assertThat(head(OWN)).as("the own repository is left alone").isEqualTo(own);
        assertThat(log(OTHER, head(OTHER), "%s"))
                .as("a worker's subject starting no build")
                .singleElement()
                .satisfies(subject -> assertThat(subject).as("marked").endsWith(" [skip ci]"));
        assertThat(freshBoard().issue("CD01").orElseThrow().worker())
                .as("a fresh clone reads the worker back")
                .isEqualTo(Worker.MANUAL);
        assertThat(indexFaults()).as("what is pushed is what the index check accepts").isEmpty();

        final String after = head(OTHER);
        store.save(
                saved.revision(),
                new Writes(Map.of(), Map.of(), List.of(), Map.of("CD01", Worker.MANUAL)));

        assertThat(head(OTHER)).as("an unchanged worker commits nothing").isEqualTo(after);
    }

    @Test
    void aWorkerSavedInAnotherRepositoryLeavesTheOwnRepositoryAloneThoughItsStoriesIndexIsStale()
            throws Exception {
        // A worker's save that rewrote a stale stories index would push a commit nobody asked for.
        Files.writeString(
                repositories.work(OWN).resolve("stories/ST4-Unindexed.md"),
                story("ST4", "  \"state\": \"Idea\",\n", ""));
        repositories.commit(OWN);
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);
        final String other = head(OTHER);

        final Board saved =
                store.save(
                        board.revision(),
                        new Writes(Map.of(), Map.of(), List.of(), Map.of("CD01", Worker.MANUAL)));

        assertThat(saved.issue("CD01").orElseThrow().worker())
                .as("the worker is saved")
                .isEqualTo(Worker.MANUAL);
        assertThat(git(OTHER, "rev-parse", head(OTHER) + "^").strip())
                .as("one commit on the repository whose head changed")
                .isEqualTo(other);
        assertThat(head(OWN))
                .as("the own repository's stale stories index is left as it was")
                .isEqualTo(own);
    }

    @Test
    void aWorkerAndAMoveOfOneSaveGoInOneCommitWhoseSubjectSaysBoth() throws Exception {
        // Split over two commits, a push failing between them would save half of what was asked.
        final GitStore store = store();
        final Board board = store.board();
        final String other = head(OTHER);

        final Board saved =
                store.save(
                        board.revision(),
                        new Writes(
                                Map.of("CD01", "Idea"),
                                Map.of(),
                                List.of(),
                                Map.of("CD01", Worker.MANUAL)));

        assertThat(git(OTHER, "rev-parse", head(OTHER) + "^").strip())
                .as("one commit holds both")
                .isEqualTo(other);
        assertThat(read(OTHER, "issues/CD01-Other.md"))
                .as("the head carries the state and the worker")
                .contains("\"state\": \"Idea\"")
                .contains("\"worker\": \"Manual\"");
        assertThat(saved.issue("CD01").orElseThrow())
                .as("the board shows both")
                .extracting(
                        org.fuin.porelo.planner.store.Issue::state,
                        org.fuin.porelo.planner.store.Issue::worker)
                .containsExactly("Idea", Worker.MANUAL);
        assertThat(log(OTHER, head(OTHER), "%s"))
                .as("a move's and worker's subject starting no build")
                .singleElement()
                .satisfies(subject -> assertThat(subject).as("marked").endsWith(" [skip ci]"));
        assertThat(indexFaults()).as("what is pushed is what the index check accepts").isEmpty();
    }

    @Test
    void aWorkerOnASubIssueIsRefusedNamingItAndPushesNothing() throws Exception {
        // A sub-issue is worked as its parent is; its own worker would be saved but never read.
        final Path issues = repositories.work(OTHER).resolve("issues");
        Files.writeString(
                issues.resolve("CD01.1-Part.md"),
                issue("CD01.1", "Backlog")
                        .replace(
                                "\"Something.\"\n}",
                                "\"Something.\",\n  \"parent\": \"CD01\",\n  \"role\": \"builder\"\n}"));
        IssuesIndex.write(issues);
        repositories.commit(OTHER);

        refusedPushingNothing(
                new Writes(
                        Map.of("CD01", "Idea"),
                        Map.of(),
                        List.of(),
                        Map.of("CD01.1", Worker.MANUAL)),
                "CD01.1");
    }

    @Test
    void aWorkerOnAnIssueTheSameSaveClosesIsRefusedNamingItAndPushesNothing() throws Exception {
        // A closed issue's file is deleted, so the worker would be written into a file that goes.
        refusedPushingNothing(
                new Writes(
                        Map.of("AB01", "Finished"),
                        Map.of(),
                        List.of(),
                        Map.of("AB01", Worker.MANUAL)),
                "AB01");
    }

    @Test
    void aWorkerOnAnIssueNotOnTheBoardIsRefusedNamingItAndPushesNothing() throws Exception {
        // A worker for an issue nobody reads would be dropped while the rest of the save went out.
        refusedPushingNothing(
                new Writes(
                        Map.of("CD01", "Idea"), Map.of(), List.of(), Map.of("CD09", Worker.MANUAL)),
                "CD09");
    }

    @Test
    void aWorkerTheHeadEditCannotPlaceIsRefusedNamingTheIssueAndPushesNothing() throws Exception {
        // A head with the worker twice no longer reads, and the issue would drop off every board.
        final Path issues = repositories.work(OTHER).resolve("issues");
        Files.writeString(
                issues.resolve("CD01-Other.md"),
                issue("CD01", "Backlog")
                        .replace("\"Something.\"\n}", "\"Something.\", \"worker\": \"Agent\"\n}"));
        repositories.commit(OTHER);

        refusedPushingNothing(
                new Writes(Map.of(), Map.of(), List.of(), Map.of("CD01", Worker.MANUAL)), "CD01");
    }

    @Test
    void anIdeaWorkedByAPersonIsReadBackManualAndOneWorkedByAnAgentNamesNoWorker()
            throws Exception {
        // A new issue started under the wrong worker would be worked by whom nobody chose, and a
        // head naming Agent would fail the build tools' schema until they publish the field.
        final GitStore store = store();
        final Board board = store.board();

        final NewIssue.Added manual =
                store.createIssue(
                        board.revision(),
                        new NewIssue(OTHER, "By hand", "A person works it.", null, Worker.MANUAL));
        final NewIssue.Added agent =
                store.createIssue(
                        manual.board().revision(),
                        new NewIssue(OWN, "By an agent", "An agent works it.", null, Worker.AGENT));

        assertThat(read(OTHER, "issues/CD02-By-hand.md"))
                .as("the person's worker written last in the head")
                .contains("\"covers\": \"By hand\",\n  \"worker\": \"Manual\"\n}");
        assertThat(read(OWN, "issues/AB03-By-an-agent.md"))
                .as("an agent's issue names no worker")
                .doesNotContain("worker");
        assertThat(freshBoard())
                .as("a fresh clone reads both back")
                .satisfies(
                        fresh -> {
                            assertThat(fresh.issue("CD02").orElseThrow().worker())
                                    .as("the person's")
                                    .isEqualTo(Worker.MANUAL);
                            assertThat(fresh.issue("AB03").orElseThrow().worker())
                                    .as("the agent's")
                                    .isEqualTo(Worker.AGENT);
                        });
        assertThat(agent.id()).as("the own issue's id").isEqualTo("AB03");
    }

    @Test
    void aPrioritySavedAloneCommitsTheStorysHeadAndTheStoriesIndexInOneCommitStartingNoBuild()
            throws Exception {
        // A priority pushed without its index row turns the stories index check red, and one
        // without the marker starts a build for a planning file.
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);
        final String other = head(OTHER);

        final Board saved =
                store.save(
                        board.revision(),
                        new Writes(
                                Map.of(), Map.of(), List.of(), Map.of(), Map.of("ST2", "Lowest")));

        assertThat(saved.story("ST2").orElseThrow().priority())
                .as("the board after the save shows the priority")
                .isEqualTo("Lowest");
        assertThat(git(OWN, "rev-parse", head(OWN) + "^").strip())
                .as("one commit on the one read")
                .isEqualTo(own);
        assertThat(names(OWN, head(OWN)))
                .as("the story's head and the stories index, nothing else")
                .containsExactly("stories/README.md", "stories/ST2-Alone.md");
        // The text above the data the stories index writes; the head below is the save's.
        assertThat(data(read(OWN, "stories/ST2-Alone.md")))
                .as("the head carries the new priority in its place")
                .isEqualTo(
                        data(
                                story("ST2", "  \"state\": \"Idea\",\n", "")
                                        .replace(
                                                "\"priority\": \"High\"",
                                                "\"priority\": \"Lowest\"")));
        assertThat(log(OWN, head(OWN), "%s"))
                .as("a priority's subject")
                .singleElement()
                .satisfies(
                        subject ->
                                assertThat(subject)
                                        .as("marked, within the subject's limit")
                                        .endsWith(" [skip ci]")
                                        .hasSizeLessThanOrEqualTo(72));
        assertThat(head(OTHER)).as("the other repository is left alone").isEqualTo(other);
        assertThat(checkCommits(OWN, own)).as("the own repository's commit check").isZero();
        assertThat(indexFaults()).as("what is pushed is what the index check accepts").isEmpty();
        assertThat(freshBoard().story("ST2").orElseThrow().priority())
                .as("a fresh clone reads the priority back")
                .isEqualTo("Lowest");

        final String after = head(OWN);
        store.save(
                saved.revision(),
                new Writes(Map.of(), Map.of(), List.of(), Map.of(), Map.of("ST2", "Lowest")));

        assertThat(head(OWN)).as("an unchanged priority commits nothing").isEqualTo(after);
    }

    @Test
    void aStoryMovedAndGivenAPriorityInOneSaveHasBothInItsHeadInOneCommit() throws Exception {
        // Split over two edits or commits, one would overwrite the other or a failed push would
        // save half of what was asked.
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);

        final Board saved =
                store.save(
                        board.revision(),
                        new Writes(
                                Map.of(),
                                Map.of("ST2", "Review"),
                                List.of(),
                                Map.of(),
                                Map.of("ST2", "Highest")));

        assertThat(git(OWN, "rev-parse", head(OWN) + "^").strip())
                .as("one commit holds both")
                .isEqualTo(own);
        // The text above the data the stories index writes; the head below is the save's.
        assertThat(data(read(OWN, "stories/ST2-Alone.md")))
                .as("the head carries the state and the priority, the rest as it was")
                .isEqualTo(
                        data(
                                story("ST2", "  \"state\": \"Review\",\n", "")
                                        .replace(
                                                "\"priority\": \"High\"",
                                                "\"priority\": \"Highest\"")));
        assertThat(saved.story("ST2").orElseThrow())
                .as("the board shows both")
                .extracting(
                        org.fuin.porelo.planner.store.Story::state,
                        org.fuin.porelo.planner.store.Story::priority)
                .containsExactly("Review", "Highest");
        assertThat(log(OWN, head(OWN), "%s"))
                .as("a move's and priority's subject")
                .singleElement()
                .satisfies(subject -> assertThat(subject).as("marked").endsWith(" [skip ci]"));
        assertThat(indexFaults()).as("what is pushed is what the index check accepts").isEmpty();
    }

    @Test
    void aPriorityForAStoryTheBoardLacksIsRefusedAndPushesNothing() throws Exception {
        // A priority for no story would be dropped while the rest of the save went out.
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);

        final StoreException.NoSuchStory refused =
                catchThrowableOfType(
                        StoreException.NoSuchStory.class,
                        () ->
                                store.save(
                                        board.revision(),
                                        new Writes(
                                                Map.of(),
                                                Map.of(),
                                                List.of(),
                                                Map.of(),
                                                Map.of("ST9", "Low"))));

        assertThat(refused).as("refused as no such story").isNotNull();
        assertThat(refused.subject()).as("naming the story").isEqualTo("ST9");
        assertThat(head(OWN)).as("nothing pushed").isEqualTo(own);
    }

    /** Saves the writes, expects a refusal naming the issue, and checks nothing was pushed. */
    private void refusedPushingNothing(final Writes writes, final String issue) throws Exception {
        final GitStore store = store();
        final Board board = store.board();
        final String own = head(OWN);
        final String other = head(OTHER);

        final StoreException.NotSavable refused =
                catchThrowableOfType(
                        StoreException.NotSavable.class,
                        () -> store.save(board.revision(), writes));

        assertThat(refused).as("refused").isNotNull();
        assertThat(refused.subject()).as("naming the issue").isEqualTo(issue);
        assertThat(List.of(head(OWN), head(OTHER)))
                .as("nothing pushed to either repository")
                .containsExactly(own, other);
    }

    /** A step run before one of the remote's calls, to make the race or failure it needs. */
    @FunctionalInterface
    private interface Before {
        void run() throws Exception;
    }

    private static Remote remote(final Remote real, final String method, final Before before) {
        return (Remote)
                java.lang.reflect.Proxy.newProxyInstance(
                        Remote.class.getClassLoader(),
                        new Class<?>[] {Remote.class},
                        (proxy, called, arguments) -> {
                            if (called.getName().equals(method)) {
                                before.run();
                            }
                            try {
                                return called.invoke(real, arguments);
                            } catch (final java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    /** The faults the repositories' own index checks find in what the remotes hold. */
    private List<String> indexFaults() throws Exception {
        final Path checks = Files.createDirectories(dir.resolve("index-check"));
        for (final String repository : List.of(OWN, OTHER)) {
            git(
                    null,
                    "clone",
                    "--quiet",
                    repositories.served().resolve(repository + ".git").toString(),
                    checks.resolve(repository).toString());
        }
        final List<String> faults = new ArrayList<>();
        faults.addAll(IssuesIndex.check(checks.resolve(OWN).resolve("issues")).faults());
        faults.addAll(IssuesIndex.check(checks.resolve(OTHER).resolve("issues")).faults());
        faults.addAll(
                StoriesIndex.check(
                                checks.resolve(OWN).resolve("stories"),
                                IssueSource.local(checks.resolve(OWN)))
                        .faults());
        return faults;
    }

    private GitStore store() throws Exception {
        return store(real -> real);
    }

    private GitStore store(final java.util.function.UnaryOperator<Remote> wrap) throws Exception {
        server = GitHttpsServer.start(repositories.served(), dir, null);
        final Path config = dir.resolve("gitconfig");
        Files.writeString(
                config,
                "[http]\n\tsslCAInfo = "
                        + server.ca()
                        + "\n[user]\n\tname = A Person\n\temail = person@example.org\n");
        person.put("GIT_CONFIG_GLOBAL", config.toString());
        final Path cache = CacheDirectory.of(person, "project");
        remote =
                new GitRemote(
                        cache,
                        person,
                        Duration.ofSeconds(5),
                        new GitRunner(Duration.ofSeconds(20), 1 << 20),
                        1 << 16);
        return new GitStore(
                List.of(
                        new Repository(OWN, new CloneUrl(server.url(OWN + ".git")), null),
                        new Repository(OTHER, new CloneUrl(server.url(OTHER + ".git")), "main")),
                wrap.apply(remote),
                new StoreReader(remote, 1000),
                new FileProjectLock(cache),
                Clock.systemUTC(),
                Duration.ofSeconds(10),
                4,
                Runnable::run);
    }

    /** The board as a store with a cache of its own reads it from the served repositories. */
    private Board freshBoard() throws Exception {
        final Map<String, String> other = new HashMap<>(person);
        other.put("XDG_CACHE_HOME", Files.createDirectories(dir.resolve("fresh-cache")).toString());
        final Path cache = CacheDirectory.of(other, "project");
        final GitRemote fresh =
                new GitRemote(
                        cache,
                        other,
                        Duration.ofSeconds(5),
                        new GitRunner(Duration.ofSeconds(20), 1 << 20),
                        1 << 16);
        return new GitStore(
                        List.of(
                                new Repository(OWN, new CloneUrl(server.url(OWN + ".git")), null),
                                new Repository(
                                        OTHER, new CloneUrl(server.url(OTHER + ".git")), "main")),
                        fresh,
                        new StoreReader(fresh, 1000),
                        new FileProjectLock(cache),
                        Clock.systemUTC(),
                        Duration.ofSeconds(10),
                        4,
                        Runnable::run)
                .board();
    }

    /** The fixture's indexes as the library writes them, so a save changes only what it moves. */
    private void serveWithIndexes(final Map<String, String> own, final Map<String, String> other)
            throws Exception {
        final Path layout = dir.resolve("layout");
        write(layout.resolve(OWN), own);
        write(layout.resolve(OTHER), other);
        IssuesIndex.write(layout.resolve(OWN).resolve("issues"));
        IssuesIndex.write(layout.resolve(OTHER).resolve("issues"));
        StoriesIndex.write(
                layout.resolve(OWN).resolve("stories"), IssueSource.local(layout.resolve(OWN)));
        repositories.repository(OWN, files(layout.resolve(OWN)));
        repositories.repository(OTHER, files(layout.resolve(OTHER)));
    }

    private static void write(final Path root, final Map<String, String> files) throws IOException {
        for (final Map.Entry<String, String> file : files.entrySet()) {
            final Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
    }

    private static Map<String, String> files(final Path root) throws IOException {
        final Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> all = Files.walk(root)) {
            for (final Path path : all.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(), Files.readString(path));
            }
        }
        return files;
    }

    private int checkCommits(final String repository, final String from) throws Exception {
        final Path clone = dir.resolve("check-" + repository);
        git(
                null,
                "clone",
                "--quiet",
                repositories.served().resolve(repository + ".git").toString(),
                clone.toString());
        final ProcessBuilder builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                "org.fuin.porelo.release.Main",
                                "check-commits",
                                from + "..HEAD")
                        .directory(clone.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final int code = process.waitFor();
        assertThat(out).as("what check-commits said, read when it fails").isNotNull();
        if (code != 0) {
            System.out.println(out);
        }
        return code;
    }

    private String head(final String repository) throws Exception {
        return git(repository, "rev-parse", "refs/heads/main").strip();
    }

    private String read(final String repository, final String path) throws Exception {
        return git(repository, "show", "refs/heads/main:" + path);
    }

    private List<String> names(final String repository, final String commit) throws Exception {
        return git(repository, "show", "--format=", "--name-only", commit)
                .lines()
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private List<String> log(final String repository, final String commit, final String format)
            throws Exception {
        return git(repository, "show", "--no-patch", "--format=" + format, commit).lines().toList();
    }

    private String git(final String repository, final String... arguments) throws Exception {
        final List<String> line = new ArrayList<>(List.of("git"));
        if (repository != null) {
            line.add("--git-dir=" + repositories.served().resolve(repository + ".git"));
        }
        line.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(line).redirectErrorStream(true);
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

    private static Writes writes(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final List<String> unstated) {
        return new Writes(issues, stories, unstated);
    }

    private static String story(final String id, final String state, final String issues) {
        return "# "
                + id
                + " — A story\n\nWhat a person wrote.\n\n## Data\n\n```json\n{\n  \"$schema\": \"story-2.json\",\n  \"id\": \""
                + id
                + "\",\n  \"title\": \"A story\",\n"
                + state
                + "  \"gain\": \"Something.\",\n  \"test\": \"A test.\",\n  \"priority\": \"High\",\n"
                + "  \"issues\": ["
                + issues
                + "],\n  \"tested\": null\n}\n```\n";
    }

    /** A story's text from its last data heading on: its head, below what the index writes. */
    private static String data(final String story) {
        return story.substring(story.lastIndexOf("## Data"));
    }

    private static String issue(final String id, final String state) {
        return "# "
                + id
                + " — An issue\n\n```json\n{\n  \"$schema\": \"issue-1.json\",\n  \"id\": \""
                + id
                + "\",\n  \"title\": \"An issue\",\n  \"state\": \""
                + state
                + "\",\n  \"size\": \"S\",\n  \"priority\": \"Medium\",\n  \"rank\": 1,\n  \"blockedBy\": [],\n"
                + "  \"covers\": \"Something.\"\n}\n```\n\n**What must be true.** Something.\n";
    }
}
