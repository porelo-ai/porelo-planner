package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.planner.app.WorkFixture.KEPT;
import static org.fuin.porelo.planner.app.WorkFixture.planner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.fuin.porelo.planner.app.WorkFixture.Answer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The work tools a repository's build runs, over a fixture the build tools indexed. */
class WorkCommandsTest {

    @TempDir Path dir;

    // Prevents a repository's check from passing an index the heads refuse.
    @Test
    void issuesCheckSaysEachFaultOfAnIndexTheHeadsRefuseAndExitsOne() throws IOException {
        final Path issues = WorkFixture.copy(dir).resolve("porelo-alpha/issues");
        final Path index = issues.resolve("README.md");
        Files.writeString(
                index,
                Files.readString(index)
                        .replace("The second step follows the first", "Edited by hand"));

        final Answer answer = planner("issues", "check", issues.toString());

        assertThat(answer.code()).as("a refusal, %s", answer.err()).isEqualTo(1);
        assertThat(answer.err().lines().toList())
                .as("the fault, then the count")
                .hasSize(2)
                .satisfies(
                        lines ->
                                assertThat(lines.getFirst())
                                        .as("the fault names the index and says to index it")
                                        .startsWith(issues + "/README.md:")
                                        .contains("never edited by hand"),
                        lines ->
                                assertThat(lines.get(1))
                                        .as("the count names the directory")
                                        .isEqualTo("1 fault(s) in the issues in " + issues));
        assertThat(answer.out()).as("no OK on a refusal").isEmpty();
    }

    // Prevents a check that cannot read the issues from reading as a pass or a refusal.
    @Test
    void issuesCheckOfADirectoryThatCannotBeReadIsNoAnswer() {
        final Answer answer = planner("issues", "check", dir.resolve("missing").toString());

        assertThat(answer.code()).as("no answer, %s", answer.err()).isEqualTo(2);
        assertThat(answer.err())
                .as("what could not be read")
                .startsWith("could not read " + dir.resolve("missing") + ": ");
        assertThat(answer.out()).as("no OK without an answer").isEmpty();
    }

    // Prevents an index written by the planner from differing from the one every check expects.
    @Test
    void issuesIndexWritesTheIndexTheBuildToolsWrite() throws IOException {
        final Path issues = WorkFixture.copy(dir).resolve("porelo-alpha/issues");
        Files.writeString(issues.resolve("README.md"), "# Issues\n\nThe issues of porelo-alpha.\n");

        final Answer answer = planner("issues", "index", issues.toString());

        assertThat(answer.code()).as("written, %s", answer.err()).isZero();
        assertThat(Files.readString(issues.resolve("README.md")))
                .as("the index the build tools wrote")
                .isEqualTo(Files.readString(KEPT.resolve("porelo-alpha/issues/README.md")));
        assertThat(planner("issues", "check", issues.toString()).code())
                .as("the written index passes its check")
                .isZero();
    }

    // Prevents a story that names an issue no index holds from passing the project's check.
    @Test
    void storiesCheckRefusesAStoryWhoseIssueTheSiblingsIndexLacks() throws IOException {
        final Path stories = WorkFixture.copy(dir).resolve("porelo-sample/stories");
        final Path story = stories.resolve("ST2-The-Second-Release.md");
        Files.writeString(
                story, Files.readString(story).replace("porelo-alpha#AB02", "porelo-alpha#AB09"));

        final Answer answer = planner("stories", "check", stories.toString());

        assertThat(answer.code()).as("a refusal, %s", answer.err()).isEqualTo(1);
        assertThat(answer.err())
                .as("the fault names the story and the missing issue")
                .contains(stories + "/ST2-The-Second-Release.md")
                .contains("AB09")
                .endsWith("fault(s) in the stories in " + stories + "\n");
    }

    // Prevents the project's check from refusing stories that the sibling checkouts confirm.
    @Test
    void storiesCheckPassesTheStoriesTheBuildToolsIndexed() throws IOException {
        final Path stories = WorkFixture.copy(dir).resolve("porelo-sample/stories");

        final Answer answer = planner("stories", "check", stories.toString());

        assertThat(answer.code()).as("a pass, %s", answer.err()).isZero();
        assertThat(answer.out()).as("the count").startsWith("OK    3 story head(s) in ");
    }

    // Prevents story texts and the stories index from differing from what every check expects.
    @Test
    void storiesIndexWritesTheTextsAndIndexTheBuildToolsWrite() throws IOException {
        final Path stories = WorkFixture.copy(dir).resolve("porelo-sample/stories");
        Files.delete(stories.resolve("README.md"));
        for (final Path file : storyFiles(stories)) {
            final String text = Files.readString(file);
            Files.writeString(
                    file,
                    text.substring(0, text.indexOf('\n') + 1)
                            + "\n"
                            + text.substring(text.indexOf("## Data")));
        }

        final Answer answer = planner("stories", "index", stories.toString());

        assertThat(answer.code()).as("written, %s", answer.err()).isZero();
        final Path kept = KEPT.resolve("porelo-sample/stories");
        assertThat(storyFiles(stories)).as("the fixture has stories to write").hasSize(3);
        for (final Path file : storyFiles(stories)) {
            assertThat(Files.readString(file))
                    .as("as the build tools wrote %s", file.getFileName())
                    .isEqualTo(Files.readString(kept.resolve(file.getFileName().toString())));
        }
        assertThat(Files.readString(stories.resolve("README.md")))
                .as("the stories index the build tools wrote")
                .isEqualTo(Files.readString(kept.resolve("README.md")));
    }

    // Prevents a release's notes from leaving out or adding a story tested after the last one.
    @Test
    void storiesCutWritesTheNotesTheBuildToolsWrite() throws IOException {
        final Path stories = WorkFixture.copy(dir).resolve("porelo-sample/stories");

        final Answer answer =
                planner("stories", "cut", "1.2.0", stories.toString(), "--after", "2026-10-31");

        assertThat(answer.code()).as("cut, %s", answer.err()).isZero();
        assertThat(answer.out()).as("the notes").isEqualTo(WorkFixture.NOTES);
    }

    // Prevents a cut from linking the stories under an owner other than the project's own.
    @Test
    void storiesCutLinksOnTheHostAndOwnerOfTheProject() throws IOException {
        final Path root = WorkFixture.copy(dir);
        final Path project = root.resolve("porelo-sample/project.yml");
        Files.writeString(
                project,
                Files.readString(project)
                        .replace(
                                "git@github.com:porelo-ai/porelo-sample.git",
                                "https://git.example.org/team/porelo-sample.git"));

        final Answer answer =
                planner(
                        "stories",
                        "cut",
                        "1.2.0",
                        root.resolve("porelo-sample/stories").toString(),
                        "--after",
                        "2026-10-31");

        assertThat(answer.code()).as("cut, %s", answer.err()).isZero();
        assertThat(answer.out())
                .as("the link on the project's own host")
                .contains("(https://git.example.org/team/porelo-sample/blob/main/stories/ST1-");
    }

    // Prevents a release's notes from taking a story from a file outside the repository's
    // stories, through a link a clone checked out, when the index cannot be read.
    @Test
    void storiesCutRefusesAStoryFileThatIsALink() throws IOException {
        final Path stories = WorkFixture.copy(dir.resolve("tree")).resolve("porelo-sample/stories");
        final Path story;
        try (Stream<Path> files = Files.list(stories)) {
            story =
                    files.filter(file -> file.getFileName().toString().startsWith("ST1-"))
                            .findFirst()
                            .orElseThrow();
        }
        final Path outside = Files.copy(story, dir.resolve("outside.md"));
        Files.delete(story);
        Files.createSymbolicLink(story, outside);
        // An index that cannot be read sends the cut to check a copy of the inputs instead.
        Files.setPosixFilePermissions(stories.resolve("README.md"), Set.of());

        final Answer answer =
                planner("stories", "cut", "1.2.0", stories.toString(), "--after", "2026-10-31");

        assertThat(answer.code()).as("a refusal, %s", answer.err()).isEqualTo(1);
        assertThat(answer.out()).as("no notes").isEmpty();
        assertThat(answer.err()).as("the link is named").contains(story.getFileName().toString());
    }

    // Prevents a misspelt or dropped command from passing a build as if it had checked.
    @Test
    void anythingElseIsAUsageAndNoAnswer() {
        for (final List<String> args :
                List.of(
                        List.of("issues", "convert", "issues"),
                        List.of("issues", "check"),
                        List.of("stories", "cut", "1.0", "stories", "--after", "2026-02-30"),
                        List.of("stories"))) {
            final Answer answer = planner(args.toArray(String[]::new));
            assertThat(answer.code()).as("no answer to %s", args).isEqualTo(2);
            assertThat(answer.err()).as("the usage for %s", args).startsWith("usage: ");
        }
    }

    // Prevents a file name from a repository from steering the terminal that reads the faults.
    @Test
    void aControlCharacterFromARepositoryReachesNoTerminal() throws IOException {
        final Path issues = WorkFixture.copy(dir).resolve("porelo-alpha/issues");
        Files.copy(
                issues.resolve("AB02-The-Second-Step.md"),
                issues.resolve("AB04-Other\u001b]0;title\u0007.md"));

        final Answer answer = planner("issues", "check", issues.toString());

        assertThat(answer.code()).as("a refusal, %s", answer.err()).isEqualTo(1);
        assertThat(answer.err())
                .as("the file is named with its characters escaped")
                .contains("AB04-Other\\u001b]0;title\\u0007.md")
                .doesNotContain("\u001b")
                .doesNotContain("\u0007");
    }

    // Prevents a repository's check from starting a server, opening a port or reading the
    // planner's own files.
    @Test
    void mainRunsACheckAndExitsWithoutStartingThePlanner() throws Exception {
        final Path issues = WorkFixture.copy(dir.resolve("tree")).resolve("porelo-alpha/issues");
        final Path home = Files.createDirectory(dir.resolve("home"));
        final ProcessBuilder builder =
                new ProcessBuilder(
                                ProcessHandle.current().info().command().orElseThrow(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                PlannerApplication.class.getName(),
                                "issues",
                                "check",
                                issues.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(dir.resolve("output").toFile());
        builder.environment().put("HOME", home.toString());
        builder.environment().put("XDG_RUNTIME_DIR", home.toString());
        builder.environment().put("XDG_STATE_HOME", home.resolve("state").toString());
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("the check ends by itself, as a server would not")
                    .isTrue();
            final String output = Files.readString(dir.resolve("output"), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("a pass, %s", output).isZero();
            assertThat(output)
                    .as("only the check's answer")
                    .isEqualTo(
                            "OK    issue heads in "
                                    + issues
                                    + ", each by issue-1.json, each wait and parent an issue,"
                                    + " each index as they give it\n");
            try (Stream<Path> written = Files.list(home)) {
                assertThat(written.toList())
                        .as("no address file, log or state of a planner")
                        .isEmpty();
            }
        } finally {
            process.destroyForcibly();
        }
    }

    private static List<Path> storyFiles(final Path stories) throws IOException {
        try (Stream<Path> files = Files.list(stories)) {
            return files.filter(file -> file.getFileName().toString().startsWith("ST"))
                    .sorted()
                    .toList();
        }
    }
}
