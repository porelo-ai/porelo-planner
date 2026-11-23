package org.fuin.porelo.planner.store.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("linux")
@EnabledOnOs(OS.LINUX)
class ItemsDirectoriesTest {

    @TempDir Path temp;

    private ItemsDirectories directories() {
        final Map<String, String> environment =
                Map.of("HOME", temp.toString(), "XDG_STATE_HOME", temp.resolve("state").toString());
        return new ItemsDirectories(PrivateFiles.of(environment), environment);
    }

    private Path kept() {
        return temp.resolve("state/porelo-planner/planning/" + ItemsDirectories.FILE);
    }

    private Path own(final String name) throws Exception {
        return Files.createDirectory(
                temp.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static String refusedDirectory(final Throwable e) {
        return String.valueOf(((StoreException.ItemsDirectoryRefused) e).directory());
    }

    @Test
    void aChoiceIsKeptAcrossARestartAsTheAccountsAloneAndDroppedForTheDefault() throws Exception {
        // Harm: the choice is lost at the next start, or other accounts may read or change it.
        final Path own = own("own");
        assertThat(directories().kept("planning")).isEqualTo(new ItemsDirectories.Default());

        directories().keep("planning", own);

        assertThat(directories().kept("planning"))
                .as("a new instance reads it back")
                .isEqualTo(new ItemsDirectories.Chosen(own));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(kept())))
                .as("the kept file is the account's alone")
                .isEqualTo("rw-------");
        try (var listed = Files.list(kept().getParent())) {
            assertThat(listed.map(p -> p.getFileName().toString()).toList())
                    .as("no written file is left beside it")
                    .containsExactly(ItemsDirectories.FILE);
        }

        directories().keep("planning", null);

        assertThat(directories().kept("planning"))
                .as("null goes back to the default")
                .isEqualTo(new ItemsDirectories.Default());
    }

    @Test
    void aKeptChoiceThePlannerDidNotWriteIsUnusable() throws Exception {
        // Harm: a damaged or planted state file points the store at a directory nobody chose.
        directories().keep("planning", own("own"));
        for (final String text :
                new String[] {
                    "{",
                    "[]",
                    "{\"directory\":1}",
                    "{\"directory\":\"relative\"}",
                    "{\"directory\":\"/a/../b\"}",
                    "{\"directory\":\"/a\",\"more\":1}",
                    "{\"directory\":\"/" + "a".repeat(ItemsDirectories.MOST_BYTES) + "\"}"
                }) {
            Files.writeString(kept(), text);
            assertThat(directories().kept("planning"))
                    .as(text.length() > 40 ? "too long" : text)
                    .isInstanceOf(ItemsDirectories.Unusable.class);
        }
        Files.setPosixFilePermissions(kept(), PosixFilePermissions.fromString("rw-rw-rw-"));
        Files.writeString(kept(), "{\"directory\":\"" + temp + "\"}");
        assertThat(directories().kept("planning"))
                .as("a kept file others may write")
                .isInstanceOf(ItemsDirectories.Unusable.class);
    }

    @Test
    void aDirectoryThatCannotBeUsedIsRefusedNamingItInAFixedPhraseAndIsNeverMade()
            throws Exception {
        // Harm: items are kept through a link, open to others, or in a directory made by a typo;
        // or the refusal repeats the system's words or a link's target.
        final Path own = own("own");
        final Path missing = temp.resolve("missing");
        final Path link = Files.createSymbolicLink(temp.resolve("link"), own);
        final Path throughLink = link.resolve("below");
        Files.createDirectory(
                own.resolve("below"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final Path file = Files.createFile(temp.resolve("file"));
        final Path open =
                Files.createDirectory(
                        temp.resolve("open"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwxr-x---")));
        final Path underOpen =
                Files.createDirectory(
                        open.resolve("below"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThat(directories().checked(own.toString())).isEqualTo(own);
        final Map<Path, String> refusals =
                Map.of(
                        missing,
                        ItemsDirectories.MISSING,
                        missing.resolve("deeper"),
                        ItemsDirectories.MISSING,
                        link,
                        ItemsDirectories.LINK,
                        throughLink,
                        ItemsDirectories.LINK,
                        file,
                        ItemsDirectories.NOT_A_DIRECTORY,
                        open,
                        ItemsDirectories.NOT_OWN,
                        underOpen,
                        ItemsDirectories.NOT_OWN);
        refusals.forEach(
                (refused, phrase) ->
                        assertThatThrownBy(() -> directories().checked(refused.toString()))
                                .as(temp.relativize(refused).toString())
                                .isInstanceOf(StoreException.ItemsDirectoryRefused.class)
                                .hasMessage(phrase)
                                .satisfies(
                                        e ->
                                                assertThat(refusedDirectory(e))
                                                        .isEqualTo(refused.toString())));
        assertThat(missing).as("it is not made").doesNotExist();
    }

    @Test
    void textOutsideTheRuleIsRefusedNamingNone() {
        // Harm: a malformed path is echoed back to the window, taken in another form, or shown
        // with characters that hide what it names.
        final String longest = "/" + "a".repeat(ItemsDirectories.MOST_PATH_BYTES - 1);
        assertThat(ItemsDirectories.shapeFault(longest)).as("4095 bytes").isNull();
        assertThat(ItemsDirectories.shapeFault("/")).as("the root").isNull();
        assertThat(ItemsDirectories.shapeFault("/a b/\u00e4")).as("inner space, umlaut").isNull();
        final Map<String, String> refusals = new java.util.LinkedHashMap<>();
        refusals.put(longest + "a", ItemsDirectories.TOO_LONG);
        refusals.put("/" + "\u00e4".repeat(2048), ItemsDirectories.TOO_LONG);
        for (final String text :
                List.of(
                        "",
                        "relative/dir",
                        "/a/../b",
                        "/a/./b",
                        "/a/..",
                        "/a//b",
                        "//a",
                        "/a/",
                        " /a",
                        "/a ",
                        "/a\u00a0",
                        "/a\tb",
                        "/a\nb",
                        "/a\u0000b",
                        "/a\u007fb",
                        "/a\u0085b",
                        "/a\u009bb",
                        "/a\u2028b",
                        "/a\u2029b",
                        "/a\u202eb",
                        "/a\u2066b",
                        "/a\ud800b",
                        "/a\udc00")) {
            refusals.put(text, ItemsDirectories.NOT_NORMAL);
        }
        refusals.forEach(
                (text, phrase) ->
                        assertThatThrownBy(() -> directories().checked(text))
                                .as(text.length() > 40 ? "too long" : text)
                                .isInstanceOf(StoreException.ItemsDirectoryRefused.class)
                                .hasMessage(phrase)
                                .satisfies(e -> assertThat(refusedDirectory(e)).isEqualTo("null")));
    }
}
