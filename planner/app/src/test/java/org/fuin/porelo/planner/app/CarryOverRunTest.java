package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.git.PrivateFiles;
import org.fuin.porelo.planner.store.git.stories.Project;
import org.fuin.porelo.planner.store.github.CarryOverMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The executable's real carry-over, over the work fixture's two checkouts and GitHub in memory. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the map's file is checked by POSIX modes")
class CarryOverRunTest {

    @TempDir Path dir;

    private Path root;

    private Path state;

    private Map<String, String> environment;

    private CarryOverGitHub github;

    private int remotes;

    @BeforeEach
    void checkouts() throws Exception {
        root = WorkFixture.copy(dir.resolve("work"));
        Files.createDirectories(root.resolve("porelo-alpha/stats"));
        Files.writeString(
                root.resolve("porelo-alpha/stats/AB02.jsonl"), CarryOverCommandTest.RECORDS);
        for (final String repository : List.of("porelo-sample", "porelo-alpha")) {
            final Path tree = root.resolve(repository);
            git(tree, "init", "-q");
            git(tree, "add", ".");
            git(
                    tree,
                    "-c",
                    "user.name=Test",
                    "-c",
                    "user.email=test@example.org",
                    "commit",
                    "-qm",
                    "x");
        }
        state =
                Files.createDirectories(
                        dir.resolve("state"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        environment =
                Map.of(
                        "XDG_STATE_HOME",
                        state.toString(),
                        "HOME",
                        dir.toString(),
                        "GIT_CONFIG_GLOBAL",
                        "/dev/null",
                        "GIT_CONFIG_NOSYSTEM",
                        "1",
                        "PATH",
                        System.getenv("PATH"));
        github = new CarryOverGitHub();
    }

    private static void git(final Path where, final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(where.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git %s: %s", command, out).isZero();
    }

    private WorkFixture.Answer carry(final String... more) {
        final List<String> args =
                new ArrayList<>(
                        List.of(
                                "--project",
                                root.resolve("porelo-sample").toString(),
                                "--to",
                                "porelo-ai/7"));
        args.addAll(List.of(more));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int code =
                CarryOverCommand.run(
                        args,
                        environment,
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        PrivateFiles.of(environment),
                        () -> {
                            remotes++;
                            return new CarryOverCommand.Remote(
                                    github,
                                    github,
                                    github,
                                    origin -> CarryOverGitHub.TOKEN,
                                    InstantSource.fixed(CarryOverGitHub.NOW));
                        });
        return new WorkFixture.Answer(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path map() {
        return state.resolve("porelo-planner/carry-over/porelo-ai-7.json");
    }

    private String projectFile() throws IOException {
        return Files.readString(root.resolve("porelo-sample/project.yml"), StandardCharsets.UTF_8);
    }

    private Path seed(final String text) throws IOException {
        return Files.writeString(dir.resolve("seed.csv"), text);
    }

    @Test
    void aWholeRunCarriesEveryItemAndWritesProjectYmlUncommitted() throws Exception {
        // Harm: a run that leaves the tracker or the seed out, commits for the person, or leaves
        // an old id on GitHub.
        final String before = projectFile();
        final WorkFixture.Answer answer =
                carry("--seed", seed("repository,size,minutes\nporelo-alpha,S,42\n").toString());
        assertThat(answer.code()).as("the exit code; it said %s", answer.err()).isZero();
        assertThat(github.issues).as("the issues made").hasSize(2);
        assertThat(github.issues.getFirst().labels)
                .as("the story's labels")
                .containsExactly("story");
        assertThat(github.issues.get(1).comments)
                .as("AB02's stage comments")
                .singleElement()
                .asString()
                .contains("\"key\" : \"carry:implement:1\"");
        assertThat(github.everything())
                .as("what GitHub holds")
                .doesNotContain("ST2")
                .doesNotContain("AB02");
        final String after = projectFile();
        assertThat(after).as("project.yml").startsWith(before);
        assertThat(Project.tracker(after))
                .as("the tracker project.yml names")
                .hasValueSatisfying(
                        tracker -> {
                            assertThat(tracker.kind()).isEqualTo("github");
                            assertThat(tracker.owner() + "/" + tracker.project())
                                    .isEqualTo("porelo-ai/7");
                        });
        assertThat(after)
                .as("the seed")
                .contains(
                        "velocity:\n    seed:\n        \"porelo-alpha\":\n            \"S\": 42\n");
        assertThat(Project.read(after, java.util.Set.of()).own())
                .as("the project read from the written file")
                .isEqualTo("porelo-sample");
        assertThat(root.resolve("porelo-alpha/stats"))
                .as("the stage records' directory")
                .isDirectoryContaining(path -> path.getFileName().toString().equals("AB02.jsonl"));
        try (var listed = Files.list(root.resolve("porelo-alpha/stats"))) {
            assertThat(listed.count()).as("the files under stats").isEqualTo(1);
        }
        assertThat(map()).as("the map after a whole run").doesNotExist();
        final List<String> lines = answer.out().lines().toList();
        assertThat(lines.get(lines.size() - 2))
                .as("the line before the last")
                .contains("was removed");
        assertThat(lines.getLast()).as("the last line").contains("porelo project sign");
        final ProcessBuilder status =
                new ProcessBuilder("git", "status", "--porcelain")
                        .directory(root.resolve("porelo-sample").toFile());
        status.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        final Process process = status.start();
        assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .as("the own checkout's changes, nothing committed")
                .isEqualTo(" M project.yml\n");
    }

    @Test
    void aStoppedRunKeepsItsMapAndARunAgainMakesNothingTwice() throws Exception {
        // Harm: a stop loses the map, doubles an item when run again, or writes project.yml
        // before every item is carried.
        final String before = projectFile();
        github.failingIssue = 2;
        github.failure = "refused for " + CarryOverGitHub.TOKEN;
        final WorkFixture.Answer stopped = carry();
        assertThat(stopped.code())
                .as("the exit code of the stop")
                .isEqualTo(WorkCommands.UNANSWERED);
        assertThat(stopped.err())
                .as("what the stop says")
                .contains("run the same command again")
                .doesNotContain(CarryOverGitHub.TOKEN);
        assertThat(projectFile()).as("project.yml after the stop").isEqualTo(before);
        assertThat(map()).as("the map after the stop").exists();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(map())))
                .as("the map's mode")
                .isEqualTo("rw-------");
        github.failingIssue = 0;
        final WorkFixture.Answer again = carry();
        assertThat(again.code()).as("the run again; it said %s", again.err()).isZero();
        assertThat(github.issues).as("the issues after the run again").hasSize(2);
        assertThat(Project.tracker(projectFile())).as("the tracker").isPresent();
        assertThat(map()).as("the map after the run again").doesNotExist();
    }

    @Test
    void anIssueMadeWhoseAnswerWasLostIsFoundByARunAgain() throws Exception {
        // Harm: an issue GitHub made while its answer was lost is made a second time.
        github.failingIssue = 2;
        github.madeBeforeFailure = true;
        assertThat(carry().code()).as("the stop").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(github.issues).as("the issues after the stop").hasSize(2);
        github.failingIssue = 0;
        final WorkFixture.Answer again = carry();
        assertThat(again.code()).as("the run again; it said %s", again.err()).isZero();
        assertThat(github.issues).as("the issues after the run again").hasSize(2);
        assertThat(github.items).as("the project's items").hasSize(2);
    }

    /** Stops a run, then writes project.yml and records it as a run would just before its stop. */
    private String stoppedAfterProjectYml(final String block) throws Exception {
        github.failingIssue = 2;
        assertThat(carry().code()).as("the stop").isEqualTo(WorkCommands.UNANSWERED);
        github.failingIssue = 0;
        final String written =
                projectFile()
                        + "\n# The tracker that keeps the stories and issues since the carry-over.\n"
                        + "tracker:\n    kind: \"github\"\n    project: \"porelo-ai/7\"\n"
                        + block;
        Files.writeString(root.resolve("porelo-sample/project.yml"), written);
        final CarryOverMap kept = CarryOverMap.read(Files.readAllBytes(map()));
        kept.projectFile(
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(written.getBytes(StandardCharsets.UTF_8))));
        Files.write(map(), kept.json(), StandardOpenOption.TRUNCATE_EXISTING);
        return written;
    }

    @Test
    void aRunAgainWithAnotherSeedThanProjectYmlWasWrittenWithIsRefused() throws Exception {
        // Harm: a seed given or changed for the run again is dropped while the run says
        // project.yml names it.
        final String written = stoppedAfterProjectYml("");
        final WorkFixture.Answer again =
                carry("--seed", seed("repository,size,minutes\nporelo-alpha,S,42\n").toString());
        assertThat(again.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(again.err()).as("the refusal").contains("another seed");
        assertThat(again.out()).as("what the run said").doesNotContain("now names");
        assertThat(projectFile()).as("project.yml").isEqualTo(written);
        assertThat(map()).as("the map").exists();
        final WorkFixture.Answer same = carry();
        assertThat(same.code()).as("the run again as before; it said %s", same.err()).isZero();
        assertThat(projectFile()).as("project.yml after it").isEqualTo(written);
    }

    @Test
    void aRunAgainAfterProjectYmlWasWrittenTakesItAsItsOwn() throws Exception {
        // Harm: a run that stopped after writing project.yml can never end, since it refuses
        // the tracker it wrote itself.
        final String written = stoppedAfterProjectYml("");
        final WorkFixture.Answer again = carry();
        assertThat(again.code()).as("the run again; it said %s", again.err()).isZero();
        assertThat(projectFile()).as("project.yml, not written twice").isEqualTo(written);
        assertThat(github.issues).as("the issues").hasSize(2);
    }

    @Test
    void aProjectYmlWithChangesNotCommittedIsRefusedBeforeAnyCall() throws Exception {
        // Harm: the run writes over a person's change to project.yml, or carries a project its
        // committed file does not describe.
        Files.writeString(
                root.resolve("porelo-sample/project.yml"),
                "\n# a note\n",
                StandardOpenOption.APPEND);
        final WorkFixture.Answer answer = carry();
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.err()).as("the refusal").contains("changes not committed");
        assertThat(remotes).as("the GitHub clients made").isZero();
    }

    @Test
    void aProjectAlreadyNamingATrackerWithoutItsMapIsRefused() throws Exception {
        // Harm: a project carried once is carried again and every item doubled.
        Files.writeString(
                root.resolve("porelo-sample/project.yml"),
                "\ntracker:\n    kind: github\n    project: porelo-ai/7\n",
                StandardOpenOption.APPEND);
        git(
                root.resolve("porelo-sample"),
                "-c",
                "user.name=T",
                "-c",
                "user.email=t@example.org",
                "commit",
                "-qam",
                "y");
        final WorkFixture.Answer answer = carry();
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.err()).as("the refusal").contains("names a tracker already");
        assertThat(remotes).as("the GitHub clients made").isZero();
    }

    @Test
    void aSeedOfAnotherShapeIsRefusedBeforeAnyCall() throws Exception {
        // Harm: a seed that cannot be read, or one for another project, ends in project.yml.
        final List<String> wrong =
                List.of(
                        "repo,size,minutes\nporelo-alpha,S,42\n",
                        "repository,size,minutes\nporelo-alpha,S,0\n",
                        "repository,size,minutes\nporelo-alpha,S,100001\n",
                        "repository,size,minutes\nporelo-alpha,S,1.5\n",
                        "repository,size,minutes\nporelo-other,S,42\n",
                        "repository,size,minutes\nporelo-alpha,XXL,42\n",
                        "repository,size,minutes\nporelo-alpha,S,42\nporelo-alpha,S,43\n",
                        "repository,size,minutes\nporelo-alpha,S\n");
        for (final String text : wrong) {
            final WorkFixture.Answer answer = carry("--seed", seed(text).toString());
            assertThat(answer.code())
                    .as("the exit code for %s", text)
                    .isEqualTo(WorkCommands.UNANSWERED);
            assertThat(answer.err()).as("the refusal for %s", text).contains("--seed");
        }
        final Path link = dir.resolve("linked.csv");
        Files.createSymbolicLink(link, seed("repository,size,minutes\nporelo-alpha,S,42\n"));
        assertThat(carry("--seed", link.toString()).code())
                .as("the exit code for a linked seed")
                .isEqualTo(WorkCommands.UNANSWERED);
        assertThat(remotes).as("the GitHub clients made").isZero();
        assertThat(github.calls).as("the calls").isEmpty();
    }

    @Test
    void aRepositoryElsewhereThanTheTargetOwnerIsRefusedBeforeAnyCall() {
        // Harm: issues made in the target owner's repositories of the same name, not the project's.
        final List<String> args =
                List.of(
                        "--project",
                        root.resolve("porelo-sample").toString(),
                        "--to",
                        "elsewhere/7");
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int code =
                CarryOverCommand.run(
                        args,
                        environment,
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        PrivateFiles.of(environment),
                        () -> {
                            remotes++;
                            return null;
                        });
        assertThat(code).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .as("the refusal")
                .contains("not on github.com under elsewhere");
        assertThat(remotes).as("the GitHub clients made").isZero();
    }

    @Test
    void aCentralATrackedProjectMayNotNameIsRefusedBeforeAnyCall() throws Exception {
        // Harm: a project carried over whose project.yml names a repository by an address the
        // tracked project refuses, so it never opens again once the tracker is written.
        assertThat(projectFile())
                .as("the fixture's ssh address")
                .contains("git@github.com:porelo-ai/porelo-alpha.git");
        assertThat(carry("--dry-run").code()).as("a dry run with ssh addresses").isZero();
        final Path file = root.resolve("porelo-sample/project.yml");
        Files.writeString(
                file,
                projectFile()
                        .replace(
                                "git@github.com:porelo-ai/porelo-alpha.git",
                                "ssh://git@github.com/porelo-ai/porelo-alpha.git"));
        git(
                root.resolve("porelo-sample"),
                "-c",
                "user.name=T",
                "-c",
                "user.email=t@example.org",
                "commit",
                "-qam",
                "y");
        for (final List<String> more : List.of(List.of("--dry-run"), List.<String>of())) {
            final WorkFixture.Answer answer = carry(more.toArray(String[]::new));
            assertThat(answer.code())
                    .as("the exit code of %s", more)
                    .isEqualTo(WorkCommands.UNANSWERED);
            assertThat(answer.err())
                    .as("the refusal of %s", more)
                    .contains("not on github.com under porelo-ai")
                    .contains("alpha");
        }
        assertThat(remotes).as("the GitHub clients made").isZero();
        assertThat(github.calls).as("the calls").isEmpty();
        assertThat(map()).as("the map").doesNotExist();
    }

    @Test
    void aMapMadeForAnotherCheckoutIsRefused() throws Exception {
        // Harm: a map of another project's run is taken, and its items are taken as this one's.
        for (final Path step : List.of(map().getParent().getParent(), map().getParent())) {
            Files.createDirectory(
                    step,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        }
        Files.write(
                map(),
                CarryOverMap.begin("/elsewhere", "porelo-ai/7", Instant.EPOCH).json(),
                StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(map(), PosixFilePermissions.fromString("rw-------"));
        final WorkFixture.Answer answer = carry();
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.err()).as("the refusal").contains("another checkout or GitHub project");
        assertThat(remotes).as("the GitHub clients made").isZero();
    }

    @Test
    void aStateFileInADirectoryOthersCanWriteIsRefusedBeforeAnyCall() throws Exception {
        // Harm: a map named by --state in a shared directory is read or swapped by another
        // account, which steers the run onto issues it never made.
        final Path shared =
                Files.createDirectory(
                        dir.resolve("shared"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwxrwx---")));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwx---"));
        final WorkFixture.Answer answer = carry("--state", shared.resolve("map.json").toString());
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.err()).as("the refusal").contains("shared");
        assertThat(remotes).as("the GitHub clients made").isZero();
        assertThat(github.calls).as("the calls").isEmpty();
        final Path own =
                Files.createDirectory(
                        dir.resolve("own"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        assertThat(carry("--state", own.resolve("map.json").toString()).code())
                .as("the exit code with a directory the account's alone")
                .isZero();
    }

    @Test
    void aMapOpenToOthersIsRefused() throws Exception {
        // Harm: a map another account could change steers the run onto issues it never made.
        github.failingIssue = 2;
        assertThat(carry().code()).as("the stop").isEqualTo(WorkCommands.UNANSWERED);
        Files.setPosixFilePermissions(map(), PosixFilePermissions.fromString("rw-rw-rw-"));
        final int calls = github.calls.size();
        final WorkFixture.Answer answer = carry();
        assertThat(answer.code()).as("the exit code").isEqualTo(WorkCommands.UNANSWERED);
        assertThat(answer.err()).as("the refusal").contains("is refused");
        assertThat(github.calls).as("the calls").hasSize(calls);
    }
}
