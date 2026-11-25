package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectTest {
    @TempDir Path work;

    private static final String PROJECT =
            "project:\n  central: git@github.com:porelo-ai/porelo-project.git\nrepositories: {}\n";

    private Path write(byte[]... parts) throws IOException {
        final Path file = work.resolve("project.yml");
        try (var out = Files.newOutputStream(file)) {
            for (final byte[] part : parts) out.write(part);
        }
        return work.resolve("stories/README.md");
    }

    // Prevents a project.yml saved by an editor that writes a byte order mark from being refused.
    @Test
    void aProjectFileOpeningWithAByteOrderMarkIsRead() throws IOException {
        final Path plan =
                write(
                        new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                        PROJECT.getBytes(StandardCharsets.UTF_8));

        assertThat(Project.read(plan, repository -> Map.of()).own())
                .as("the own repository read past the mark")
                .isEqualTo("porelo-project");
    }

    // Prevents a project.yml that is no UTF-8 from escaping the refusal a malformed project gets.
    @Test
    void aProjectFileThatIsNoUtf8IsRefusedAsInvalidYaml() throws IOException {
        final Path plan =
                write(
                        "project:\n  central: \"git@github.com:porelo-ai/porelo-"
                                .getBytes(StandardCharsets.UTF_8),
                        new byte[] {(byte) 0xFF},
                        ".git\"\nrepositories: {}\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> Project.read(plan, repository -> Map.of()))
                .as("the configuration refusal it got before, naming the file")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(work.resolve("project.yml") + ": invalid YAML: ")
                .hasMessageContaining("Invalid UTF-8");
    }

    // Prevents a new issue from taking another prefix than the first its repository names.
    @Test
    void aRepositorysIssuePrefixesKeepTheOrderProjectYmlGivesThem() {
        final Project project =
                Project.read(
                        "project:\n  central: git@github.com:porelo-ai/porelo-project.git\n"
                                + "repositories:\n  other:\n"
                                + "    central: git@github.com:porelo-ai/porelo-other.git\n"
                                + "    issues: [ZZ, AA, MM, BB, QQ, CC, YY]\n",
                        java.util.Set.of());

        assertThat(project.repositories().get("porelo-other"))
                .as("the prefixes in the file's order, the first one first")
                .containsExactly("ZZ", "AA", "MM", "BB", "QQ", "CC", "YY");
    }

    private Project readCentral(String central) throws IOException {
        Files.writeString(
                work.resolve("project.yml"),
                "project:\n  central: git@git.example.org:acme/plans.git\n"
                        + "repositories:\n  other:\n    central: \""
                        + central
                        + "\"\n    issues: [AB]\n");
        return Project.read(work.resolve("stories/README.md"), repository -> Map.of());
    }

    // Prevents a project whose repositories live on another host and owner from losing its board.
    @Test
    void aProjectWhoseCentralsLiveOnAnotherHostAndOwnerIsReadWithTheirNames() throws IOException {
        Files.writeString(
                work.resolve("project.yml"),
                """
                project:
                  central: "git@git.example.org:acme/plans.git"
                repositories:
                  api:
                    central: "https://git.example.org/acme/widget-api.git"
                    issues: [WA]
                  ui:
                    central: "ssh://git@git.example.org:2222/acme/team/widget-ui"
                    issues: [WU]
                """);

        final Project project =
                Project.read(work.resolve("stories/README.md"), repository -> Map.of());

        assertThat(project.repositories().keySet())
                .as("each repository named by its central's last path part, without .git")
                .containsExactly("plans", "widget-api", "widget-ui");
        assertThat(project.web("widget-ui"))
                .as("the pages linked on the central's own host, under its own owner")
                .isEqualTo("https://git.example.org/acme/team/widget-ui");
        assertThat(project.web("plans"))
                .as("the own repository's pages on its own host")
                .isEqualTo("https://git.example.org/acme/plans");
    }

    // Prevents a central in one of the clone URL shapes Git takes from being refused or misnamed.
    @ParameterizedTest
    @CsvSource({
        "https://git.example.org/acme/widget.git, widget, https://git.example.org/acme/widget",
        "https://git.example.org:8443/acme/tools/widget, widget,"
                + " https://git.example.org:8443/acme/tools/widget",
        "ssh://git@git.example.org:2222/acme/widget.git, widget,"
                + " https://git.example.org/acme/widget",
        "ssh://git.example.org/acme/widget.js.git, widget.js,"
                + " https://git.example.org/acme/widget.js",
        "git@git.example.org:acme/widget.git, widget, https://git.example.org/acme/widget",
        "git@git.example.org:/srv/acme/widget, widget, https://git.example.org/srv/acme/widget"
    })
    void eachCloneUrlShapeIsReadWithItsNameAndHost(String central, String name, String web)
            throws IOException {
        final Project project = readCentral(central);

        assertThat(project.repositories().keySet())
                .as("the repository named by the central's last path part")
                .containsExactly("plans", name);
        assertThat(project.central(name)).as("the central kept as written").isEqualTo(central);
        assertThat(project.web(name)).as("the pages on the central's host").isEqualTo(web);
    }

    // Prevents a central that is no clone URL from reaching Git, a file name or a link.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "file:///x",
                "https://git.example.org/",
                "https://git.example.org",
                "https://git.example.org/acme/.git",
                "git@git.example.org:../x",
                "https://git.example.org/acme/../x.git",
                "ssh://git@git.example.org/acme/a..b.git",
                "git@git.example.org:acme/.hidden",
                "git.example.org:acme/x.git",
                "ftp://git.example.org/acme/x.git",
                "https://git.example.org/acme/x y.git",
                "https://git.example.org/acme/x\\u0007.git",
                "-oProxyCommand@git.example.org:acme/x.git",
                "https://git.example.org/acme/-x.git",
                "https://git.example.org/acme/./x.git"
            })
    void aCentralThatIsNoCloneUrlIsRefusedNamingItsKeyAndValue(String central) throws IOException {
        Files.writeString(
                work.resolve("project.yml"),
                "project:\n  central: git@git.example.org:acme/plans.git\n"
                        + "repositories:\n  other:\n    central: \""
                        + central
                        + "\"\n    issues: [AB]\n");

        assertThatThrownBy(
                        () ->
                                Project.read(
                                        work.resolve("stories/README.md"), repository -> Map.of()))
                .as("the refusal names where the central stands and what it says")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("project.yml repositories.other: central ")
                .hasMessageContaining(central.substring(central.lastIndexOf('@') + 1))
                .hasMessageNotContaining(String.valueOf((char) 7));
    }

    // Prevents a credential in a refused central of any shape from being shown in the refusal.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "https://ghp_secretToken@git.example.org/acme/x.git | ghp_secretToken",
                "https://my-token@git.example.org/acme/x.git | my-token",
                "me:s3cret@git.example.org:acme/x.git | s3cret",
                "ssh://me:s3cret@git.example.org/acme/x.git | s3cret",
                "https://me:pa@ssword@git.example.org/acme/x.git | ssword",
                "https://me:pa@ssword@git.example.org/acme/x.git | pa@",
                "https://me:pa/ssword@git.example.org/acme/x.git | ssword",
                "https://git.example.org/acme/x.git?access_token=s3cret | s3cret",
                "https://git.example.org/acme/x.git#s3cret | s3cret",
                "https://me:pa#ssword@git.example.org/acme/x.git | ssword",
                "https://git.example.org/acme/x.git?access_token=s3c@ret | ret"
            })
    void aRefusedCentralNamesItsKeyAndNeverItsCredential(String central, String secret) {
        assertThatThrownBy(() -> readCentral(central))
                .as("the key is named, the credential %s is not", secret)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("project.yml repositories.other: central ")
                .hasMessageNotContaining(secret);
    }

    // Prevents a central that cannot be split safely from being shown in part.
    @Test
    void aCentralWithAnAtAfterItsQueryIsNotShownAtAll() {
        assertThatThrownBy(
                        () ->
                                readCentral(
                                        "https://git.example.org/acme/x.git?access_token=s3c@ret"))
                .as("neither the part before the '@' nor the part after it is shown")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("project.yml repositories.other: central <not shown> ");
    }

    // Prevents a central whose repository name Ids refuses from failing later without its key.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://git.example.org/acme/Widget.git",
                "git@git.example.org:acme/WIDGET",
                "git@git.example.org:-acme/x.git",
                "git@git.example.org:x",
                "https://git.example.org:0/acme/x.git",
                "https://git.example.org:65536/acme/x.git",
                "ssh://git@git.example.org:99999/acme/x.git"
            })
    void aCentralGitOrTheStoreWouldRefuseLaterIsRefusedNamingItsKey(String central) {
        assertThatThrownBy(() -> readCentral(central))
                .as("refused while project.yml is read, naming the key and the value")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("project.yml repositories.other: central ")
                .hasMessageContaining(central.substring(central.lastIndexOf('@') + 1) + " ");
    }

    // Prevents a central whose path is longer than the store's clone URL takes from failing later.
    @Test
    void aCentralWithAPathLongerThanACloneUrlTakesIsRefusedNamingItsKey() {
        final String central = "https://git.example.org/acme/" + "d/".repeat(300) + "x.git";

        assertThatThrownBy(() -> readCentral(central))
                .as("refused while project.yml is read, naming the key")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("project.yml repositories.other: central https://");
    }

    // Prevents a link with an uppercase host from differing from the same repository's other links.
    @Test
    void aCentralsHostIsLinkedInLowerCaseWithItsPort() throws IOException {
        final Project project = readCentral("https://Git.Example.Org:65535/acme/widget.git");

        assertThat(project.web("widget"))
                .as("the host lower-cased, the port kept")
                .isEqualTo("https://git.example.org:65535/acme/widget");
    }

    // Prevents a huge value from filling a log line and a separator from breaking it.
    @Test
    void aRefusalShowsALongValueCutAndEveryLineSeparatorAndFormatCharacterEscaped() {
        final String central = "https://git.example.org/acme/" + "y ".repeat(5000);
        final String escaped = "https://git.example.org/acme/x\\u2028y\\U000E0001z.git";

        assertThatThrownBy(() -> readCentral(central))
                .as("the value cut")
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .hasSizeLessThan(400);
        assertThatThrownBy(() -> readCentral(escaped))
                .as("a line separator and a supplementary format character escaped")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x\\u2028y\\udb40\\udc01z")
                .hasMessageNotContaining("\u2028")
                .hasMessageNotContaining(new String(Character.toChars(0xE0001)));
    }

    // Prevents the own repository's refusal from naming another key than the file says.
    @Test
    void aRefusedOwnCentralIsNamedUnderProjectYmlsProjectKey() throws IOException {
        Files.writeString(
                work.resolve("project.yml"), "project:\n  central: file:///x\nrepositories: {}\n");

        assertThatThrownBy(
                        () ->
                                Project.read(
                                        work.resolve("stories/README.md"), repository -> Map.of()))
                .as("the refusal names the file and the key")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("project.yml project: central file:///x ");
    }

    // Prevents a name the caller passes from writing a line separator into a log line.
    @Test
    void anUnknownRepositoryIsNamedEscaped() throws IOException {
        final Project project = readCentral("https://git.example.org/acme/widget.git");

        assertThatThrownBy(() -> project.web("a\u2028b"))
                .as("the name escaped")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a\\u2028b")
                .hasMessageNotContaining("\u2028");
    }

    // Prevents a password in a refused central from being shown in the refusal.
    @Test
    void aRefusedCentralWithAPasswordIsNamedWithoutIt() {
        assertThatThrownBy(() -> readCentral("https://me:s3cret@git.example.org/acme/x.git"))
                .as("the key and the address are named, the password is not")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("project.yml repositories.other: central ")
                .hasMessageContaining("git.example.org/acme/x.git")
                .hasMessageNotContaining("s3cret");
    }
}
