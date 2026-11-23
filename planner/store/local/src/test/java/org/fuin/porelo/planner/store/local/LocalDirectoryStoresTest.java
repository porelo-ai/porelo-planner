package org.fuin.porelo.planner.store.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.planner.store.github.FieldNames;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("linux")
@EnabledOnOs(OS.LINUX)
class LocalDirectoryStoresTest {

    @TempDir Path temp;

    /** The data directory's base, which a group may not write, as a home's is not. */
    private Path data() throws java.io.IOException {
        return Files.createDirectory(
                temp.resolve("data"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
    }

    private LocalDirectoryStores stores(final Path data) {
        final Map<String, String> environment =
                Map.of("HOME", temp.toString(), "XDG_DATA_HOME", data.toString());
        return new LocalDirectoryStores(
                PrivateFiles.of(environment), environment, Clock.systemUTC());
    }

    private static PlanningStore open(final LocalDirectoryStores stores, final Path chosen) {
        return stores.open(
                "planning",
                LocalDirectoryStoreTest.PROJECT,
                () -> LocalDirectoryStoreTest.PROJECT,
                chosen);
    }

    @Test
    void theDefaultDirectoryIsMadeOnceAsTheAccountsAlone() throws Exception {
        // Harm: a project's items lie open to other accounts, or a second opening loses them.
        final Path data = data();
        final PlanningStore store =
                stores(data)
                        .open(
                                "planning",
                                LocalDirectoryStoreTest.PROJECT,
                                () -> LocalDirectoryStoreTest.PROJECT);
        store.createIssue(store.board().revision(), new NewIssue("engine", "Kept", "Kept", "S"));

        final Path directory = data.resolve("porelo-planner/items/planning");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
                .as("the default directory is the account's alone")
                .isEqualTo("rwx------");
        final PlanningStore again =
                stores(data)
                        .open(
                                "planning",
                                LocalDirectoryStoreTest.PROJECT,
                                () -> LocalDirectoryStoreTest.PROJECT);
        assertThat(again.board().issues()).as("a second opening keeps the items").hasSize(1);
    }

    @Test
    void aChosenDirectoryIsNeverMade() throws Exception {
        // Harm: a mistyped or removed directory is made anew and the items seem lost.
        final Path data = data();
        final Path missing = temp.resolve("missing");

        assertThatThrownBy(() -> open(stores(data), missing))
                .as("a missing chosen directory is refused")
                .isInstanceOf(StoreException.Unreadable.class)
                .hasMessageNotContaining(temp.toString());
        assertThat(missing).as("it is not made").doesNotExist();
    }

    @Test
    void aChosenDirectoryThroughALinkOrOpenToOthersIsRefused() throws Exception {
        // Harm: items are written through a link elsewhere, or read by another account.
        final Path data = data();
        final Path own =
                Files.createDirectory(
                        temp.resolve("own"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        final Path link = Files.createSymbolicLink(temp.resolve("link"), own);
        final Path open =
                Files.createDirectory(
                        temp.resolve("open"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwxr-x---")));

        assertThat(open(stores(data), own).board().issues()).as("the account's own").isEmpty();
        assertThatThrownBy(() -> open(stores(data), link))
                .as("a link")
                .isInstanceOf(StoreException.Unreadable.class);
        assertThatThrownBy(() -> open(stores(data), open))
                .as("a directory the group may read")
                .isInstanceOf(StoreException.Unreadable.class);
    }

    @Test
    void theSchemaNamesEveryKeyAHeadMayHoldAndNoOther() throws Exception {
        // Harm: the head's documented form drifts from the keys the store reads and writes.
        final JsonNode schema;
        try (InputStream input =
                Objects.requireNonNull(
                        LocalItem.class.getResourceAsStream("/" + LocalItem.FORMAT + ".json"))) {
            schema = LocalItem.JSON.readTree(input);
        }
        final Set<String> named = new HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(named::add);
        final Set<String> known = new HashSet<>(LocalItem.STRUCTURE);
        known.addAll(FieldNames.ALL.keySet());
        // GitHub keeps Priority in the organization's issue field; a head keeps it as a field.
        known.add(FieldNames.PRIORITY);

        assertThat(schema.path("additionalProperties").asBoolean(true))
                .as("no key beside them")
                .isFalse();
        assertThat(named).as("the keys the store knows").isEqualTo(known);
        assertThat(List.of(schema.path("properties").path("format").path("const").asText()))
                .as("the form's name")
                .containsExactly(LocalItem.FORMAT);
    }
}
