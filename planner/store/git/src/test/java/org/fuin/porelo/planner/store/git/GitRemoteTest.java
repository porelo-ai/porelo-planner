package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRemoteTest {

    @TempDir Path dir;

    private GitHttpsServer server;

    private Path served;

    private Path cache;

    private Map<String, String> person;

    private String commit;

    @BeforeEach
    void serveAStore() throws Exception {
        final Path work = Files.createDirectories(dir.resolve("work"));
        git(work, "init", "--quiet", "--initial-branch=main");
        write(work, "stories/workflow.yml", "states: [Idea]\n");
        write(work, "issues/README.md", "# Issues\n");
        write(work, "issues/XY01-One.md", "# XY01 — One\n");
        write(work, "issues/big.md", "x".repeat(2000));
        write(work, "issues/base/XY02-Two.md", "# XY02 — Two\n");
        Files.createSymbolicLink(work.resolve("issues/link.md"), Path.of("/etc/hostname"));
        write(work, "stories/run.sh", "#!/bin/sh\n");
        Files.setPosixFilePermissions(
                work.resolve("stories/run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
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
                "store");
        commit = git(work, "rev-parse", "HEAD").strip();
        served = Files.createDirectories(dir.resolve("served"));
        git(
                dir,
                "clone",
                "--quiet",
                "--bare",
                work.toString(),
                served.resolve("store.git").toString());
        cache = Files.createDirectories(dir.resolve("cache"));
        person = new HashMap<>();
        person.put("PATH", "/usr/bin:/bin");
        person.put("HOME", dir.toString());
        person.put("GIT_CONFIG_NOSYSTEM", "1");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private GitRemote remote(final String login) throws Exception {
        server = GitHttpsServer.start(served, dir, login);
        final Path config = dir.resolve("gitconfig");
        Files.writeString(
                config,
                "[http]\n\tsslCAInfo = "
                        + server.ca()
                        + "\n[user]\n\tname = A Person\n\temail = person@example.org\n");
        person.put("GIT_CONFIG_GLOBAL", config.toString());
        return new GitRemote(
                cache,
                person,
                Duration.ofSeconds(5),
                new GitRunner(Duration.ofSeconds(20), 1 << 20),
                1000);
    }

    private CloneUrl url() {
        return new CloneUrl(server.url("store.git"));
    }

    @Test
    void aStoreIsFetchedAndReadAtTheCommitItsRemoteNames() throws Exception {
        final GitRemote remote = remote(null);
        assertThat(remote.head("store", url(), "main")).as("the remote's branch").isEqualTo(commit);
        assertThat(remote.head("store", url(), null))
                .as("the remote's default branch")
                .isEqualTo(commit);
        assertThat(remote.fetch("store", url(), null)).as("the commit fetched").isEqualTo(commit);
        assertThat(remote.read("store", commit, "issues/XY01-One.md"))
                .as("a file")
                .hasValue("# XY01 — One\n");
        assertThat(remote.read("store", commit, "issues/XY99.md"))
                .as("a file the commit lacks")
                .isEmpty();
        assertThat(remote.list("store", commit, "issues"))
                .as("the directory's files and links, never what is below it")
                .containsExactly(
                        "issues/README.md",
                        "issues/XY01-One.md",
                        "issues/big.md",
                        "issues/link.md");
    }

    @Test
    void aSearchOfTheTreeNamesEachLineOfAWordAndNothingWhenNoneHoldsIt() throws Exception {
        // A search that finds nothing must answer nothing, not fail the reading of the board.
        final GitRemote remote = remote(null);
        remote.fetch("store", url(), "main");
        assertThat(remote.grep("store", commit, List.of("XY01", "XY02")))
                .as("each line holding a word, with its file and line")
                .containsExactlyInAnyOrder(
                        new Remote.Found("issues/XY01-One.md", 1, "# XY01 — One"),
                        new Remote.Found("issues/base/XY02-Two.md", 1, "# XY02 — Two"));
        assertThat(remote.grep("store", commit, List.of("XY0", "XY99")))
                .as("no line holds these as whole words")
                .isEmpty();
    }

    @Test
    void aLinkOrAnOversizedFileIsRefused() throws Exception {
        // A link in the store points at a file of the person's; a huge file fills the planner's
        // memory.
        final GitRemote remote = remote(null);
        remote.fetch("store", url(), "main");
        assertThat(remote.read("store", commit, "issues/README.md"))
                .as("a plain file beside them, read with them")
                .hasValue("# Issues\n");
        assertThatThrownBy(() -> remote.read("store", commit, "issues/link.md"))
                .as("a link")
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("is not a plain file (mode 120000)");
        assertThatThrownBy(() -> remote.read("store", commit, "issues/big.md"))
                .as("a file over the bound")
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("is larger than 1000 bytes");
    }

    @Test
    void eachCommitIsReadAsItHoldsItsFilesAfterAnotherCommitToo() throws Exception {
        // A board or a save built from another commit's files would show or push what is not
        // there.
        final GitRemote remote = remote(null);
        remote.fetch("store", url(), "main");
        assertThat(remote.read("store", commit, "issues/XY01-One.md"))
                .as("the first commit's file")
                .hasValue("# XY01 — One\n");
        final String second = change(remote, commit, "issues/XY01-One.md", "# XY01 — Second\n");
        final String third = change(remote, second, "issues/XY04-Four.md", "# XY04 — Four\n");
        for (int round = 0; round < 2; round++) {
            assertThat(remote.read("store", second, "issues/XY01-One.md"))
                    .as("the second commit's text, round %d", round)
                    .hasValue("# XY01 — Second\n");
            assertThat(remote.read("store", commit, "issues/XY01-One.md"))
                    .as("the first commit's text, round %d", round)
                    .hasValue("# XY01 — One\n");
            assertThat(remote.read("store", commit, "issues/XY04-Four.md"))
                    .as("a file only a later commit holds, round %d", round)
                    .isEmpty();
            assertThat(remote.read("store", third, "issues/XY04-Four.md"))
                    .as("the third commit's new file, round %d", round)
                    .hasValue("# XY04 — Four\n");
            assertThat(remote.list("store", third, "issues"))
                    .as("the third commit's files, round %d", round)
                    .containsExactly(
                            "issues/README.md",
                            "issues/XY01-One.md",
                            "issues/XY04-Four.md",
                            "issues/big.md",
                            "issues/link.md");
            assertThat(remote.list("store", commit, "issues"))
                    .as("the first commit's files, round %d", round)
                    .containsExactly(
                            "issues/README.md",
                            "issues/XY01-One.md",
                            "issues/big.md",
                            "issues/link.md");
            assertThat(remote.directories("store", commit, "issues"))
                    .as("the first commit's subdirectories, round %d", round)
                    .containsExactly("issues/base");
            assertThat(remote.list("store", commit, "issues/base"))
                    .as("a subdirectory's files, round %d", round)
                    .containsExactly("issues/base/XY02-Two.md");
            assertThat(remote.read("store", commit, "project.yml"))
                    .as("a planning file the commit lacks, round %d", round)
                    .isEmpty();
        }
    }

    @Test
    void aCommitHoldsExactlyTheChangesOfTheSaveAndKeepsAFilesMode() throws Exception {
        // A save writing other files than it changed, or dropping a script's mode, damages the
        // store.
        final GitRemote remote = remote(null);
        remote.fetch("store", url(), "main");
        final Path root = remote.scratch();
        write(root, "issues/README.md", "# Issues, changed\n");
        write(root, "issues/XY01-One.md", "# XY01 — One, changed\n");
        write(root, "stories/run.sh", "#!/bin/sh\nexit 0\n");
        write(root, "issues/XY03-Three.md", "# XY03 — Three\n");
        final String made =
                remote.commit(
                        "store",
                        commit,
                        root,
                        List.of(
                                "issues/README.md",
                                "issues/XY01-One.md",
                                "stories/run.sh",
                                "issues/XY03-Three.md",
                                "issues/base/XY02-Two.md"),
                        "Change the store");
        final Path clone = cache.resolve("store.git");
        assertThat(git(clone, "diff-tree", "-r", "--no-commit-id", "--name-status", commit, made))
                .as("the commit's changes against its parent")
                .isEqualTo(
                        "M\tissues/README.md\n"
                                + "M\tissues/XY01-One.md\n"
                                + "A\tissues/XY03-Three.md\n"
                                + "D\tissues/base/XY02-Two.md\n"
                                + "M\tstories/run.sh\n");
        assertThat(git(clone, "show", made + ":issues/README.md"))
                .as("a changed file's text")
                .isEqualTo("# Issues, changed\n");
        assertThat(git(clone, "show", made + ":issues/XY01-One.md"))
                .as("another changed file's text")
                .isEqualTo("# XY01 — One, changed\n");
        assertThat(git(clone, "show", made + ":issues/XY03-Three.md"))
                .as("the new file's text")
                .isEqualTo("# XY03 — Three\n");
        assertThat(git(clone, "show", made + ":stories/run.sh"))
                .as("the script's text")
                .isEqualTo("#!/bin/sh\nexit 0\n");
        assertThat(
                        git(
                                clone,
                                "ls-tree",
                                "--format=%(objectmode) %(path)",
                                made,
                                "--",
                                "stories/run.sh",
                                "issues/XY03-Three.md",
                                "issues/README.md"))
                .as("each file's mode")
                .isEqualTo(
                        "100644 issues/README.md\n"
                                + "100644 issues/XY03-Three.md\n"
                                + "100755 stories/run.sh\n");
        assertThat(git(clone, "log", "--format=%P %s", "-1", made))
                .as("its parent and subject")
                .isEqualTo(commit + " Change the store\n");
    }

    private static String change(
            final GitRemote remote, final String parent, final String path, final String text)
            throws IOException {
        final Path root = remote.scratch();
        write(root, path, text);
        return remote.commit("store", parent, root, List.of(path), "Change " + path);
    }

    @Test
    void aPathOutOfTheRepositoryIsRefusedBeforeGitRuns() throws Exception {
        final GitRemote remote = remote(null);
        remote.fetch("store", url(), "main");
        for (final String path :
                List.of(
                        "../store.git/config",
                        "/etc/hostname",
                        "-p",
                        "issues/../../x",
                        ":(top)x")) {
            assertThatThrownBy(() -> remote.read("store", commit, path))
                    .as("'%s'", path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theCloneKeepsNoUrlAndRunsNoHook() throws Exception {
        // A URL kept in the clone may carry a login; a hook placed in the clone would run as the
        // person.
        final GitRemote remote = remote(null);
        remote.fetch("store", url(), "main");
        final Path hook = cache.resolve("store.git/hooks/reference-transaction");
        final Path marker = dir.resolve("hook-ran");
        Files.writeString(hook, "#!/bin/sh\ntouch " + marker + "\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwx------"));
        remote.fetch("store", url(), "main");
        assertThat(marker).as("the hook did not run").doesNotExist();
        assertThat(Files.readString(cache.resolve("store.git/config")))
                .as("the clone's configuration names no remote")
                .doesNotContain("localhost")
                .doesNotContain("[remote");
    }

    @Test
    void aMissingLoginIsRefusedAtOnceWithoutAPrompt() throws Exception {
        // Without a working login the planner must answer at once, not wait on a prompt nobody
        // sees.
        final GitRemote remote = remote("person:right");
        final long start = System.nanoTime();
        assertThatThrownBy(() -> remote.fetch("store", url(), "main"))
                .as("a fetch without a login")
                .isInstanceOf(GitFailure.class)
                .satisfies(
                        e ->
                                assertThat(((GitFailure) e).kind())
                                        .as("the kind of: %s", e.getMessage())
                                        .isEqualTo(GitFailure.Kind.LOGIN));
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("refused at once")
                .isLessThan(Duration.ofSeconds(10));
    }

    private static void write(final Path root, final String path, final String text)
            throws IOException {
        final Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
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
