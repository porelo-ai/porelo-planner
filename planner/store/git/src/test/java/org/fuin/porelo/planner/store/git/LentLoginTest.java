package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * A private repository read over the store's local HTTPS server with the login a stand-in for
 * Porelo's helper lends, answering in Git's credential protocol as Porelo's does.
 */
@EnabledOnOs(OS.LINUX)
class LentLoginTest {

    /**
     * Records each request but its password, and answers {@code get} with the token its environment
     * holds, so the token is never on disk; with {@code STAND_IN_SHUT} it fails as a shut vault
     * does.
     */
    private static final String STAND_IN =
            """
            #!/bin/sh
            [ "$1 $2 $3" = "vault credential --for" ] || exit 2
            request=$(grep -v '^password=')
            printf 'action=%s\\nfor=%s\\n%s\\n--\\n' "$5" "$4" "$request" >> "$(dirname "$0")/requests"
            if [ -n "$STAND_IN_SHUT" ]; then
              echo "the vault is shut" >&2
              exit 1
            fi
            if [ "$5" = get ]; then
              printf 'username=person\\npassword=%s\\n' "$STAND_IN_TOKEN"
            fi
            """;

    @TempDir Path dir;

    private final String token = "lent-" + UUID.randomUUID();

    private final List<GitHttpsServer> servers = new ArrayList<>();

    private final Logger logger = (Logger) LoggerFactory.getLogger(GitRunner.class);

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    private Level before;

    private Path served;

    private Path helper;

    private String commit;

    @BeforeEach
    void serveAPrivateStore() throws Exception {
        before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        lines.start();
        logger.addAppender(lines);
        final Path work = Files.createDirectories(dir.resolve("work"));
        git(work, "init", "--quiet", "--initial-branch=main");
        Files.createDirectories(work.resolve("issues"));
        Files.writeString(work.resolve("issues/README.md"), "# Issues\n");
        git(work, "add", "-A");
        git(
                work,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "commit",
                "-qm",
                "s");
        commit = git(work, "rev-parse", "HEAD").strip();
        served = Files.createDirectories(dir.resolve("served"));
        git(
                dir,
                "clone",
                "--quiet",
                "--bare",
                work.toString(),
                served.resolve("store.git").toString());
        // The stand-in lies in a directory only this account may change, as the check demands.
        final Path bin = Files.createDirectory(dir.resolve("bin"));
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwx------"));
        helper = Files.writeString(bin.resolve("porelo"), STAND_IN);
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"));
        helper = helper.toRealPath();
    }

    @AfterEach
    void stop() {
        logger.detachAppender(lines);
        logger.setLevel(before);
        servers.forEach(GitHttpsServer::close);
    }

    private GitHttpsServer server(final String login) throws Exception {
        final GitHttpsServer server =
                GitHttpsServer.start(
                        served,
                        Files.createDirectories(dir.resolve("tls-" + servers.size())),
                        login);
        servers.add(server);
        return server;
    }

    /**
     * The person's environment: their own configuration names a plaintext store helper and a trace
     * target of each kind, and trusts every server of the test.
     */
    private Map<String, String> person(final boolean lending) throws IOException {
        final StringBuilder authorities = new StringBuilder();
        for (final GitHttpsServer server : servers) {
            authorities.append(Files.readString(server.ca()));
        }
        final Path ca = Files.writeString(dir.resolve("ca-bundle.pem"), authorities);
        final Path config =
                Files.writeString(
                        dir.resolve("gitconfig"),
                        "[http]\n\tsslCAInfo = "
                                + ca
                                + "\n[credential]\n\thelper = store --file="
                                + plainStore()
                                + "\n[trace2]\n\teventTarget = "
                                + dir.resolve("trace-event")
                                + "\n\tnormalTarget = "
                                + dir.resolve("trace-normal")
                                + "\n\tperfTarget = "
                                + dir.resolve("trace-perf")
                                + "\n[user]\n\tname = A Person\n\temail = person@example.org\n");
        final Map<String, String> person = new HashMap<>();
        person.put("PATH", "/usr/bin:/bin");
        person.put("HOME", dir.toString());
        person.put("GIT_CONFIG_NOSYSTEM", "1");
        person.put("GIT_CONFIG_GLOBAL", config.toString());
        person.put("STAND_IN_TOKEN", token);
        if (lending) {
            person.put(LentLogin.VARIABLE, helper.toString());
        }
        return person;
    }

    private Path plainStore() {
        return dir.resolve("plain-store");
    }

    private GitRemote remote(final Map<String, String> person) throws IOException {
        return new GitRemote(
                Files.createDirectories(dir.resolve("cache")),
                person,
                Duration.ofSeconds(5),
                new GitRunner(Duration.ofSeconds(20), 1 << 20),
                1 << 16);
    }

    private String requests() throws IOException {
        final Path log = helper.getParent().resolve("requests");
        return Files.exists(log) ? Files.readString(log) : "";
    }

    private String basic() {
        return Base64.getEncoder()
                .encodeToString(("person:" + token).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aPrivateRepositoryIsReadWithTheLoginPorelosHelperLends() throws Exception {
        // Harm: without it, a person whose project is private sees no board.
        final GitHttpsServer server = server("person:" + token);
        final CloneUrl url = new CloneUrl(server.url("store.git"));
        assertThat(remote(person(true)).fetch("store", url, "main"))
                .as("the private repository's commit")
                .isEqualTo(commit);
        assertThat(server.authorizations())
                .as("the login the helper lent reached the central")
                .contains("Basic " + basic());
        assertThat(requests())
                .as("the helper was asked for this repository by its path")
                .contains("action=get\nfor=" + url.value() + "\n")
                .contains("protocol=https\n")
                .contains("path=store.git\n");
    }

    @Test
    void aPlaintextHelperOfThePersonsConfigurationIsNeverToldTheLentLogin() throws Exception {
        // Harm: the vault's token written in plain text to a file of the person's.
        final GitHttpsServer server = server("person:" + token);
        remote(person(true)).fetch("store", new CloneUrl(server.url("store.git")), "main");
        assertThat(plainStore())
                .as("the person's store helper was neither asked nor told")
                .doesNotExist();
        assertThat(requests())
                .as("Porelo's helper alone was told the login worked")
                .contains("action=store\n");
    }

    @Test
    void noTraceTargetOfThePersonsConfigurationIsWrittenByACallCarryingTheHelper()
            throws Exception {
        // Harm: a trace of the helper's exchange kept in a file the person's configuration names.
        final GitHttpsServer server = server("person:" + token);
        remote(person(true)).fetch("store", new CloneUrl(server.url("store.git")), "main");
        // The local calls around the fetch carry no helper and may trace as the person asked.
        for (final String name : List.of("trace-event", "trace-normal", "trace-perf")) {
            final Path trace = dir.resolve(name);
            assertThat(Files.exists(trace) ? Files.readString(trace) : "")
                    .as("what %s holds of the call carrying the helper", name)
                    .doesNotContain("vault credential")
                    .doesNotContain("followRedirects");
        }
    }

    @Test
    void withoutTheVariableAPrivateRepositoryIsRefused() throws Exception {
        // Harm: a test of the lent login that passes without one proves nothing.
        final GitHttpsServer server = server("person:" + token);
        assertThatThrownBy(
                        () ->
                                remote(person(false))
                                        .fetch(
                                                "store",
                                                new CloneUrl(server.url("store.git")),
                                                "main"))
                .as("the private repository without a login")
                .isInstanceOfSatisfying(
                        GitFailure.class,
                        e ->
                                assertThat(e.kind())
                                        .as(e.getMessage())
                                        .isEqualTo(GitFailure.Kind.LOGIN));
        assertThat(requests()).as("Porelo's helper was not asked").isEmpty();
    }

    @Test
    void aCentralThatRedirectsIsRefusedAndTheHostItNamesGetsNoCredential() throws Exception {
        // Harm: the vault's login sent to whatever host a central's answer names.
        final GitHttpsServer target = server("person:" + token);
        final String to = target.url("").substring(0, target.url("").length() - 1);
        final GitHttpsServer moved =
                GitHttpsServer.redirecting(Files.createDirectories(dir.resolve("tls-moved")), to);
        servers.add(moved);
        final CloneUrl url = new CloneUrl(moved.url("store.git"));
        assertThatThrownBy(() -> remote(person(true)).fetch("store", url, "main"))
                .as("a fetch from a central that redirects")
                .isInstanceOf(GitFailure.class);
        assertThat(target.authorizations())
                .as("no request, and so no credential, reached the host the redirect named")
                .isEmpty();
        assertThat(requests())
                .as("Porelo's helper was asked for nothing but the central")
                .doesNotContain(to.substring("https://".length()));
    }

    @Test
    void aShutVaultIsALoginRefusal() throws Exception {
        // Harm: a shut vault read as a broken store sends the person looking in the wrong place.
        final GitHttpsServer server = server("person:" + token);
        final Map<String, String> person = person(true);
        person.put("STAND_IN_SHUT", "1");
        assertThatThrownBy(
                        () ->
                                remote(person)
                                        .fetch(
                                                "store",
                                                new CloneUrl(server.url("store.git")),
                                                "main"))
                .as("a fetch while the vault is shut")
                .isInstanceOfSatisfying(
                        GitFailure.class,
                        e ->
                                assertThat(e.kind())
                                        .as(e.getMessage())
                                        .isEqualTo(GitFailure.Kind.LOGIN));
    }

    @Test
    void noTokenReachesAFailureOrALogLine() throws Exception {
        // Harm: the vault's token shown on the board or kept in the log for days.
        final GitHttpsServer server = server("person:another-token");
        final Map<String, String> person = person(true);
        // Every trace the person's environment could ask for, curl's headers unredacted too.
        person.put("GIT_TRACE", "2");
        person.put("GIT_TRACE_CURL", "2");
        person.put("GIT_TRACE_REDACT", "0");
        person.put("GIT_CURL_VERBOSE", "1");
        final GitFailure failure =
                (GitFailure)
                        assertThatThrownBy(
                                        () ->
                                                remote(person)
                                                        .fetch(
                                                                "store",
                                                                new CloneUrl(
                                                                        server.url("store.git")),
                                                                "main"))
                                .as("a fetch the central refuses the lent login for")
                                .isInstanceOf(GitFailure.class)
                                .actual();
        assertThat(server.authorizations())
                .as("the lent login was tried")
                .contains("Basic " + basic());
        assertThat(failure.kind()).as(failure.getMessage()).isEqualTo(GitFailure.Kind.LOGIN);
        assertThat(failure.getMessage())
                .as("the failure's words")
                .doesNotContain(token)
                .doesNotContain(basic());
        assertThat(lines.list).as("the lines the calls logged at DEBUG").isNotEmpty();
        for (final ILoggingEvent line : lines.list) {
            assertThat(line.getFormattedMessage())
                    .as("a line at DEBUG")
                    .doesNotContain(token)
                    .doesNotContain(basic());
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
