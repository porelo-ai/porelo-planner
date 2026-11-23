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
import org.fuin.porelo.planner.store.Tracker;
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

    private Map<String, String> environment(final Path data) {
        return Map.of(
                "HOME",
                temp.toString(),
                "XDG_DATA_HOME",
                data.toString(),
                "XDG_STATE_HOME",
                temp.resolve("state").toString());
    }

    private ItemsDirectories directories(final Path data) {
        final Map<String, String> environment = environment(data);
        return new ItemsDirectories(PrivateFiles.of(environment), environment);
    }

    private LocalDirectoryStores stores(final Path data) {
        final Map<String, String> environment = environment(data);
        return new LocalDirectoryStores(
                PrivateFiles.of(environment), environment, Clock.systemUTC(), directories(data));
    }

    private static PlanningStore openKept(final LocalDirectoryStores stores) {
        return stores.open(
                "planning", LocalDirectoryStoreTest.PROJECT, () -> LocalDirectoryStoreTest.PROJECT);
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
        final PlanningStore store = open(stores(data), missing);

        assertThatThrownBy(store::board)
                .as("a missing chosen directory is refused at the read, naming it")
                .isInstanceOf(StoreException.ItemsDirectoryRefused.class)
                .satisfies(
                        e ->
                                assertThat(((StoreException.ItemsDirectoryRefused) e).directory())
                                        .isEqualTo(missing.toString()))
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
        assertThatThrownBy(() -> open(stores(data), link).board())
                .as("a link")
                .isInstanceOf(StoreException.ItemsDirectoryRefused.class);
        assertThatThrownBy(() -> open(stores(data), open).board())
                .as("a directory the group may read")
                .isInstanceOf(StoreException.ItemsDirectoryRefused.class);
    }

    @Test
    void aKeptDirectoryIsOpenedAndAnsweredAsTheTracker() throws Exception {
        // Harm: the person's choice is lost at the next start, or the window shows the wrong one.
        final Path data = data();
        final Path own =
                Files.createDirectory(
                        temp.resolve("own"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        directories(data).keep("planning", own);

        final PlanningStore store = openKept(stores(data));
        store.createIssue(store.board().revision(), new NewIssue("engine", "Kept", "Kept", "S"));

        assertThat(store.tracker())
                .isEqualTo(
                        Tracker.local(
                                own.toString(),
                                data.resolve("porelo-planner/items/planning").toString()));
        try (var listed = Files.list(own)) {
            assertThat(listed.count()).as("the item lies in the chosen directory").isPositive();
        }
        assertThat(data.resolve("porelo-planner/items/planning"))
                .as("the default is not made")
                .doesNotExist();
    }

    @Test
    void aKeptDirectoryMovedAwayIsRefusedAtEveryReadAndTheDefaultIsNotUsed() throws Exception {
        // Harm: a moved directory silently falls back to the default and the items seem lost.
        final Path data = data();
        final Path own =
                Files.createDirectory(
                        temp.resolve("own"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        directories(data).keep("planning", own);
        final PlanningStore store = openKept(stores(data));
        assertThat(store.board().issues()).isEmpty();

        Files.move(own, temp.resolve("moved"));

        assertThatThrownBy(store::board)
                .as("the held board is refused once the directory is gone")
                .isInstanceOf(StoreException.ItemsDirectoryRefused.class);
        assertThatThrownBy(() -> openKept(stores(data)).board())
                .as("a store opened anew is refused too")
                .isInstanceOf(StoreException.ItemsDirectoryRefused.class);
        assertThat(data.resolve("porelo-planner/items/planning"))
                .as("the default is not made")
                .doesNotExist();
    }

    @Test
    void anUnreadableKeptChoiceKeepsTheProjectButRefusesItsReads() throws Exception {
        // Harm: a damaged state file drops the project, or its items are read from the default.
        final Path data = data();
        final Path own =
                Files.createDirectory(
                        temp.resolve("own"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        directories(data).keep("planning", own);
        Files.writeString(
                temp.resolve("state/porelo-planner/planning/" + ItemsDirectories.FILE), "{");

        final PlanningStore store = openKept(stores(data));

        assertThat(store.tracker().kind()).isEqualTo(Tracker.Kind.LOCAL);
        assertThatThrownBy(store::board)
                .isInstanceOf(StoreException.ItemsDirectoryRefused.class)
                .satisfies(
                        e ->
                                assertThat(((StoreException.ItemsDirectoryRefused) e).directory())
                                        .isNull());
        assertThat(data.resolve("porelo-planner/items/planning"))
                .as("the default is not made")
                .doesNotExist();
    }

    @Test
    void aDefaultDirectoryOutsideTheRuleIsRefusedAndNeverTold() throws Exception {
        // Harm: the window is shown a directory whose characters hide what it names.
        final Path data =
                Files.createDirectory(
                        temp.resolve("data\u202e"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwxr-xr-x")));

        final PlanningStore store = openKept(stores(data));

        assertThat(store.tracker()).isEqualTo(Tracker.LOCAL_REFUSED);
        assertThatThrownBy(store::board)
                .isInstanceOf(StoreException.ItemsDirectoryRefused.class)
                .hasMessage(ItemsDirectories.NOT_NORMAL)
                .satisfies(
                        e ->
                                assertThat(((StoreException.ItemsDirectoryRefused) e).directory())
                                        .isNull());
        assertThat(data.resolve("porelo-planner")).as("nothing is made").doesNotExist();
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
