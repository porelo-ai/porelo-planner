package org.fuin.porelo.planner.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The clean-up's choice as the workflow acts on it: the lines of the answer file, each a folder the
 * workflow deletes, or no file at all after a refusal.
 */
class MainTest {

    /** The day the choice is made; every fixture's times are read against it. */
    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC);

    private static final String PREFIX = "porelo-files/porelo-planner/";

    @TempDir Path dir;

    private Path checkouts;

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void everyRepositoryCheckedOutWithoutAPin() throws IOException {
        checkouts = dir.resolve("checkouts");
        for (final String name : List.of("example-project", "example-core", "example-planner")) {
            Files.createDirectories(checkouts.resolve(name).resolve(".git"));
            Files.writeString(checkouts.resolve(name).resolve("README.md"), "# " + name + "\n");
        }
    }

    @Test
    void theNewestTenStayEvenWhenAllAreOld() throws Exception {
        // Harm: the builds every repository's next pin is taken from deleted, leaving none to pin.
        assertThat(choose("old-builds.json"))
                .as("of 13 builds older than 30 days only the three oldest go")
                .containsExactly(
                        PREFIX + "0.1.0~snapshot.3/",
                        PREFIX + "0.1.0~snapshot.2/",
                        PREFIX + "0.1.0~snapshot.1/");
    }

    @Test
    void aBuildYoungerThanThirtyDaysStays() throws Exception {
        // Harm: a build a repository may pin today, within the seven days it is new, deleted.
        assertThat(choose("young-builds.json"))
                .as(
                        "13 builds of the last 29 days all stay, though three are not among the newest 10")
                .isEmpty();
    }

    @Test
    void aBuildARepositoryPinsStays() throws Exception {
        // Harm: a pinned executable deleted under a repository's CI, which then cannot run.
        pin(
                "example-core",
                ".github/workflows/build.yml",
                "url: porelo-files/porelo-planner/0.1.0~snapshot.2/porelo-planner-0.1.0~snapshot.2-linux-x86_64\n");
        pin("example-project", "tool/planner.properties", "planner=0.1.0%7Esnapshot.1\n");
        assertThat(choose("old-builds.json"))
                .as("the two pinned builds stay, as written plain and percent-encoded")
                .containsExactly(PREFIX + "0.1.0~snapshot.3/");
    }

    @Test
    void aBuildAUtf16ScriptPinsStays() throws Exception {
        // Harm: a pinned executable deleted under a repository's CI, its pin in a UTF-16 script.
        final Path little = checkouts.resolve("example-core").resolve("pin.ps1");
        final byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        final byte[] text = "$planner = '0.1.0~snapshot.1'\r\n".getBytes(StandardCharsets.UTF_16LE);
        final byte[] withBom = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(text, 0, withBom, bom.length, text.length);
        Files.write(little, withBom);
        Files.write(
                checkouts.resolve("example-project").resolve("pin.ps1"),
                "$planner = '0.1.0~snapshot.2'\r\n".getBytes(StandardCharsets.UTF_16BE));
        assertThat(choose("old-builds.json"))
                .as("builds pinned in UTF-16LE with a BOM and UTF-16BE without one stay")
                .containsExactly(PREFIX + "0.1.0~snapshot.3/");
    }

    @Test
    void aBuildASymbolicLinkPinsStays() throws Exception {
        // Harm: a pinned executable deleted under a repository's CI, its pin a tracked link's
        // target.
        Files.createSymbolicLink(
                checkouts.resolve("example-core").resolve("porelo-planner"),
                Path.of("tool/porelo-planner-0.1.0~snapshot.1-linux-x86_64"));
        assertThat(choose("old-builds.json"))
                .as("the build a link's target names stays, though the target does not exist")
                .containsExactly(PREFIX + "0.1.0~snapshot.3/", PREFIX + "0.1.0~snapshot.2/");
    }

    @Test
    void aReleaseIsNeverChosen() throws Exception {
        // Harm: a release, which a release build depends on and nothing publishes again, deleted.
        assertThat(choose("strangers.json"))
                .as("the old releases 0.1.0 and 0.0.9 stay")
                .doesNotContain(PREFIX + "0.1.0/", PREFIX + "0.0.9/")
                .containsExactly(PREFIX + "0.1.0~snapshot.1/");
    }

    @Test
    void aPathOutsideThePrefixIsNeverChosen() throws Exception {
        // Harm: another repository's files deleted with a token that reaches them.
        assertThat(choose("strangers.json"))
                .as("only the one old snapshot build stays chosen, every answer under the prefix")
                .containsExactly(PREFIX + "0.1.0~snapshot.1/")
                .allMatch(
                        line ->
                                line.matches(
                                        "porelo-files/porelo-planner/[0-9.]+~snapshot\\.[0-9]+/"));
    }

    @Test
    void aListingOfAnotherFolderChoosesNothing() throws Exception {
        // Harm: another folder's old builds deleted as if they were the planner's.
        assertThat(run("other-folder.json"))
                .as("a listing of another folder is refused, though its builds are old")
                .isEqualTo(Main.UNREADABLE);
        assertThat(answer()).as("a refused listing leaves nothing to delete").doesNotExist();
    }

    @Test
    void aVersionNotXyzSnapshotNIsNeverChosen() throws Exception {
        // Harm: a folder the Build did not publish as a snapshot - a copy, a backup, an odd name -
        // deleted.
        assertThat(choose("strangers.json"))
                .as("-SNAPSHOT, .bak, v-prefixed, two-part and run-less versions all stay")
                .containsExactly(PREFIX + "0.1.0~snapshot.1/");
    }

    @Test
    void aListingCutShortChoosesNothing() throws Exception {
        // Harm: a listing missing its newest builds counts old ones among the newest 10 wrongly,
        // and deletes builds that should stay.
        assertThat(run("cut-short.json")).as("a cut listing is refused").isEqualTo(Main.UNREADABLE);
        assertThat(answer()).as("a refused listing leaves nothing to delete").doesNotExist();
        assertThat(err.toString(StandardCharsets.UTF_8))
                .as("the refusal says what could not be read")
                .contains("the listing is no whole JSON document");
    }

    @Test
    void anUnreadableListingChoosesNothing() throws Exception {
        // Harm: a listing read as empty or partial chooses builds that should stay.
        Files.writeString(
                dir.resolve("no-time.json"),
                resource("listings/old-builds.json")
                        .replaceFirst("\"lastModified\": \"[^\"]*\"", "\"lastModified\": null"));
        assertThat(
                        new Main(NOW, quiet(), new PrintStream(err, true, StandardCharsets.UTF_8))
                                .run(
                                        "choose",
                                        dir.resolve("no-time.json").toString(),
                                        project().toString(),
                                        checkouts.toString(),
                                        answer().toString()))
                .as("a file without a time is refused")
                .isEqualTo(Main.UNREADABLE);
        assertThat(answer()).as("a refused listing leaves nothing to delete").doesNotExist();
    }

    @Test
    void aRepositoryWhosePinsCannotBeReadChoosesNothing() throws Exception {
        // Harm: a repository left unread, whose pinned build is then deleted under its CI.
        try (Stream<Path> paths = Files.walk(checkouts.resolve("example-core"))) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
        assertThat(run("old-builds.json"))
                .as("a named repository without a checkout is refused")
                .isEqualTo(Main.UNREADABLE);
        assertThat(answer()).as("a refused pin listing leaves nothing to delete").doesNotExist();
        assertThat(err.toString(StandardCharsets.UTF_8))
                .as("the refusal names the repository")
                .contains("repository example-core has no checkout");
    }

    @Test
    void everyRepositoryOfTheProjectFileIsListedForItsPins() throws Exception {
        // Harm: a repository the clean-up does not clone has its pins unread, and its pinned build
        // deleted.
        final Path out = dir.resolve("repositories.txt");
        assertThat(
                        new Main(NOW, quiet(), quiet())
                                .run("repositories", project().toString(), out.toString()))
                .as("the project file reads")
                .isZero();
        assertThat(Files.readAllLines(out))
                .as("the own repository and every named one, each by an https:// clone URL")
                .containsExactly(
                        "example-project https://github.com/example-org/example-project.git",
                        "example-core https://github.com/example-org/example-core.git",
                        "example-planner https://github.com/example-org/example-planner.git");
    }

    private List<String> choose(final String listing) throws Exception {
        assertThat(run(listing)).as("%s is chosen from: %s", listing, err).isZero();
        return Files.readAllLines(answer());
    }

    private int run(final String listing) throws Exception {
        return new Main(NOW, quiet(), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(
                        "choose",
                        path("listings/" + listing).toString(),
                        project().toString(),
                        checkouts.toString(),
                        answer().toString());
    }

    private void pin(final String repository, final String file, final String text)
            throws IOException {
        final Path path = checkouts.resolve(repository).resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private Path answer() {
        return dir.resolve("chosen.txt");
    }

    private static Path project() throws URISyntaxException {
        return path("project.yml");
    }

    private static Path path(final String resource) throws URISyntaxException {
        return Path.of(
                Objects.requireNonNull(MainTest.class.getResource("/" + resource), resource)
                        .toURI());
    }

    private static String resource(final String resource) throws Exception {
        return Files.readString(path(resource));
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }
}
