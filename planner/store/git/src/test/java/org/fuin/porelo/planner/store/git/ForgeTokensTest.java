package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;

/**
 * The token for a forge's API asked of a stand-in for Porelo's helper, which answers in Git's
 * credential protocol as Porelo's does.
 */
@EnabledOnOs(OS.LINUX)
class ForgeTokensTest {

    /**
     * Keeps its arguments and request beside itself, then answers as {@code STAND_IN_MODE} says;
     * every failing mode still writes a password, so only a refusal of the failure passes.
     */
    private static final String STAND_IN =
            """
            #!/bin/sh
            here=$(dirname "$0")
            printf '%s\\n' "$@" > "$here/arguments"
            cat > "$here/request"
            case "$STAND_IN_MODE" in
              fail)
                echo "the vault is shut, token $STAND_IN_TOKEN" >&2
                printf 'password=%s\\n' "$STAND_IN_TOKEN"
                exit 3 ;;
              flood)
                head -c 70000 /dev/zero | tr '\\000' 'a'
                printf '\\npassword=%s\\n' "$STAND_IN_TOKEN" ;;
              blank)
                printf 'username=person\\npassword=\\n' ;;
              none)
                printf 'username=person\\n' ;;
              slow)
                sleep 30
                printf 'password=%s\\n' "$STAND_IN_TOKEN" ;;
              *)
                printf 'username=person\\npassword=%s\\n' "$STAND_IN_TOKEN" ;;
            esac
            """;

    private static final URI ORIGIN = URI.create("https://forge.example.org/team");

    private final String token = "lent-" + UUID.randomUUID();

    private final Logger logger = (Logger) LoggerFactory.getLogger(LentLogin.class);

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    private Path helper;

    @BeforeEach
    void placeTheStandIn() throws IOException {
        lines.start();
        logger.addAppender(lines);
        // The stand-in lies in a directory only this account may change, as the check demands; a
        // shared temporary directory is one others may write.
        final Path bin = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "tokens-");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwx------"));
        helper = Files.writeString(bin.resolve("porelo"), STAND_IN);
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"));
        helper = helper.toRealPath();
    }

    @AfterEach
    void stop() {
        logger.detachAppender(lines);
    }

    private Map<String, String> person(final String mode) {
        final Map<String, String> person = new HashMap<>();
        person.put("PATH", "/usr/bin:/bin");
        person.put("STAND_IN_TOKEN", token);
        person.put("STAND_IN_MODE", mode);
        person.put(LentLogin.VARIABLE, helper.toString());
        return person;
    }

    private Path beside(final String name) {
        return helper.getParent().resolve(name);
    }

    private void assertRefusedWithoutTheToken(final Map<String, String> person, final String why) {
        assertThatThrownBy(() -> ForgeTokens.of(person).token(ORIGIN, "team"))
                .as(why)
                .isInstanceOfSatisfying(
                        StoreException.NoForgeAccess.class,
                        e -> {
                            assertThat(e.subject())
                                    .as("the forge's origin, for which the window offers a token")
                                    .isEqualTo("https://forge.example.org");
                            assertThat(e.right()).as("no right is named").isNull();
                            assertThat(e.getMessage())
                                    .as("the refusal's words")
                                    .doesNotContain(token);
                        });
        for (final ILoggingEvent line : lines.list) {
            assertThat(line.getFormattedMessage()).as("a logged line").doesNotContain(token);
        }
    }

    @Test
    void theHelperIsHandedThePlannersLimitsLineBeforeItsVerb() throws IOException {
        // Harm: Porelo's helper waiting on a slow vault with the default limits while the planner
        // waits for it as long as the person's factors say, so it fails before its caller does.
        ForgeTokens.of(person("answer"), LimitFactors.parse("services=2.5,retries=3"))
                .token(ORIGIN, "team");
        assertThat(Files.readString(beside("arguments")))
                .as("the helper's arguments")
                .startsWith("--limits\nservices=2.5,machines=1.0,polling=1.0,retries=3.0\nvault\n");
    }

    @Test
    void theTokenIsThePasswordPorelosHelperAnswers() {
        // Harm: the GitHub store calls the API with no login, or with another line of the answer.
        assertThat(ForgeTokens.of(person("answer")).token(ORIGIN, "team"))
                .as("the password line's value")
                .isEqualTo(token);
    }

    @Test
    void theHelperIsAskedForTheAddressAsGitWouldAskItAndNeverSeesTheTokenOnItsLine()
            throws IOException {
        // Harm: a token for another address or host, or a token visible in the process list.
        ForgeTokens.of(person("answer")).token(ORIGIN, "team");
        assertThat(Files.readString(beside("arguments")))
                .as("the helper's arguments")
                .isEqualTo(
                        "--limits\n"
                                + LimitFactors.DEFAULT.line()
                                + "\nvault\ncredential\n--for\nhttps://forge.example.org/team\nget\n");
        assertThat(Files.readString(beside("request")))
                .as("the request on the helper's input")
                .isEqualTo("protocol=https\nhost=forge.example.org\npath=team\n\n");
    }

    @Test
    void withoutTheVariableNoTokenIsLent() {
        // Harm: an API call made with whatever login else the planner could find, or a missing
        // token told as a refused Git login, so the window offers no token for the forge.
        final Map<String, String> person = person("answer");
        person.remove(LentLogin.VARIABLE);
        assertRefusedWithoutTheToken(person, "a token without the variable");
        assertThat(beside("arguments")).as("the helper was not run").doesNotExist();
    }

    @Test
    void aHelperThatFailsLendsNoTokenThoughItWroteOne() {
        // Harm: a refusal of the vault's read as a login, and its error words shown with the token.
        assertRefusedWithoutTheToken(person("fail"), "a token from a helper that exited non-zero");
    }

    @Test
    void aHelperThatWritesMoreThanTheBoundIsRefused() {
        // Harm: a helper gone wrong fills the planner's memory.
        assertRefusedWithoutTheToken(person("flood"), "a token from an answer over the bound");
    }

    @Test
    void anAnswerWithoutAPasswordIsRefused() {
        // Harm: an API call made with an empty token, which a forge reads as no login at all.
        assertRefusedWithoutTheToken(person("none"), "a token from an answer without a password");
        assertRefusedWithoutTheToken(person("blank"), "a token from a blank password");
    }

    @Test
    void anAddressThatIsNotHttpsIsRefusedBeforeTheHelperRuns() {
        // Harm: the vault's token sent in clear text, or to an address with a login of its own.
        for (final String address :
                new String[] {
                    "http://forge.example.org/team",
                    "https://person@forge.example.org/team",
                    "ssh://forge.example.org/team"
                }) {
            assertThatThrownBy(
                            () ->
                                    ForgeTokens.of(person("answer"))
                                            .token(URI.create(address), "team"))
                    .as("a token for %s", address)
                    .isInstanceOf(StoreException.CredentialRefused.class);
        }
        assertThat(beside("arguments")).as("the helper was not run").doesNotExist();
    }

    @Test
    void anAddressWithAQueryOrFragmentIsRefusedNeverOfferedAToken() {
        // Harm: the window offers to hand on a token for an address the helper never takes.
        for (final String address :
                new String[] {
                    "https://forge.example.org/team?x=1", "https://forge.example.org/team#x"
                }) {
            assertThatThrownBy(
                            () ->
                                    ForgeTokens.of(person("answer"))
                                            .token(URI.create(address), "team"))
                    .as("a token for %s", address)
                    .isInstanceOf(StoreException.CredentialRefused.class);
        }
        assertThat(beside("arguments")).as("the helper was not run").doesNotExist();
    }

    @Test
    void aHelperThatDoesNotAnswerInTimeIsRefused() {
        // Harm: a hanging helper hangs the board.
        final long start = System.nanoTime();
        assertThatThrownBy(
                        () ->
                                LentLogin.of(person("slow"))
                                        .token(ORIGIN.toString(), "team", Duration.ofSeconds(1)))
                .as("a token from a helper that does not answer")
                .isInstanceOf(StoreException.CredentialRefused.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("the wait")
                .isLessThan(Duration.ofSeconds(10));
    }
}
