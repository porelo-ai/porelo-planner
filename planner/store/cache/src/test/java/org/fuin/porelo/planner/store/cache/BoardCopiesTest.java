package org.fuin.porelo.planner.store.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardCopiesTest {

    private static final BoardCopy.Project PROJECT =
            new BoardCopy.Project("github.com", "porelo-ai", 1);

    private static final BoardCopy.Project OTHER =
            new BoardCopy.Project("github.com", "porelo-ai-test", 1);

    @TempDir Path directory;

    private final List<Path> checked = new ArrayList<>();

    private final List<Path> refused = new ArrayList<>();

    private final CopyFiles files =
            new CopyFiles() {
                @Override
                public FileAttribute<?>[] ownerOnlyFile() {
                    return new FileAttribute<?>[0];
                }

                @Override
                public void requireOwnFile(final Path file, final boolean alone) {
                    checked.add(file);
                    if (!alone || refused.contains(file) || Files.isSymbolicLink(file)) {
                        throw new IllegalStateException("not the account's own");
                    }
                }
            };

    private BoardCopies copies() {
        return new BoardCopies(directory, files);
    }

    private static BoardCopy copy(final BoardCopy.Project project, final String commit) {
        final TrackedItem.Issue issue =
                new TrackedItem.Issue(
                        "I_one",
                        11L,
                        1,
                        "One",
                        "# One\n\nText.",
                        "OPEN",
                        null,
                        Instant.parse("2026-11-21T10:00:00Z"),
                        "porelo-ai/porelo-planner",
                        List.of("story"),
                        null,
                        new TrackedItem.Links(
                                List.of(
                                        new TrackedItem.Link(
                                                "I_two", 12L, 2, "porelo-ai/porelo-planner")),
                                true,
                                "cursor-1",
                                3),
                        new TrackedItem.Links(List.of(), false, null, 0));
        return new BoardCopy(
                project,
                commit,
                List.of("porelo-planner"),
                Instant.parse("2026-11-21T10:05:00Z"),
                new TrackerBoard(
                        "PVT_one",
                        Map.of("Status", "PVTSSF_status"),
                        Map.of("PVTSSF_status", "Status", "PVTF_estimate", "Estimate"),
                        Map.of("Status", Map.of("Backlog", "opt-1")),
                        List.of("The field Size is missing"),
                        Map.of("PVTSSF_status", 11L, "PVTF_estimate", 12L)),
                List.of(
                        new TrackedItem(
                                "PVTI_one",
                                false,
                                issue,
                                List.of(
                                        new TrackedItem.Value(
                                                "PVTSSF_status", "opt-1", "Backlog", null, null),
                                        new TrackedItem.Value(
                                                "PVTF_estimate", null, null, null, 82.0)),
                                Instant.parse("2026-11-21T10:01:00Z"),
                                true),
                        new TrackedItem("PVTI_draft", true, null, List.of(), null, false)),
                new ChangeCursor(
                        Instant.parse("2026-11-21T10:04:00Z"),
                        List.of(
                                new ChangeCursor.Page(
                                        null, "W/\"etag-1\"", true, List.of("PVTI_one")),
                                new ChangeCursor.Page("c1", null, false, List.of()))),
                List.of(new TrackedItem("PVTI_held", false, null, List.of(), null, false)));
    }

    @Test
    void aCopyWrittenIsReadBackAsItWas() {
        // Harm: a board shown at start that differs from the one the planner held.
        final BoardCopy written = copy(PROJECT, "c0ffee0");
        copies().write(written);

        assertThat(copies().read(PROJECT, "c0ffee0")).as("the copy read back").contains(written);
        assertThat(checked).as("files checked as the account's own").isNotEmpty();
    }

    @Test
    void aCopyHeldWithOtherProjectFilesIsNotUsed() {
        // Harm: a board read under an old workflow shown after the project's files changed.
        copies().write(copy(PROJECT, "c0ffee0"));

        assertThat(copies().read(PROJECT, "beef001")).as("the copy for other files").isEmpty();
    }

    @Test
    void anotherProjectsCopyIsNeverServed() throws IOException {
        // Harm: one project's board shown as another's, its stories moved in the wrong project.
        copies().write(copy(OTHER, "c0ffee0"));
        Files.move(directory.resolve(OTHER.file()), directory.resolve(PROJECT.file()));

        assertThat(copies().read(PROJECT, "c0ffee0")).as("another project's copy").isEmpty();
        assertThat(copies().read(OTHER, "c0ffee0")).as("a project without a copy").isEmpty();
    }

    @Test
    void aCopyOfAnotherFormatIsNotRead() throws IOException {
        // Harm: a copy written by another planner version read into records it was not made of.
        copies().write(copy(PROJECT, "c0ffee0"));
        final Path file = directory.resolve(PROJECT.file());
        final byte[] bytes = Files.readAllBytes(file);
        final byte[] head = head("fory").getBytes(StandardCharsets.US_ASCII);
        final byte[] other =
                head("fory")
                        .replace(BoardCopies.FORMAT, "0000000000000000")
                        .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(other, 0, bytes, 0, head.length);
        Files.write(file, bytes);

        assertThat(copies().read(PROJECT, "c0ffee0")).as("a copy of another format").isEmpty();
    }

    @Test
    void aBrokenOrStrangeCopyIsIgnoredNeverFatal() throws IOException {
        // Harm: a planner that no longer starts because its copy was cut short or tampered with.
        final Path file = directory.resolve(PROJECT.file());
        final String head = head("fory");
        for (final String text :
                List.of(
                        "",
                        "{}",
                        head.strip(),
                        head,
                        head + "{",
                        head + "\u0000",
                        head("json") + "{}",
                        head("other") + "{}")) {
            Files.writeString(file, text, StandardCharsets.UTF_8);

            assertThat(copies().read(PROJECT, "c0ffee0")).as("the copy %s", text).isEmpty();
        }
    }

    @Test
    void randomBytesAfterAValidHeadAreIgnoredNeverFatal() throws IOException {
        // Harm: a tampered copy that stops the planner, or is read as a board it never held.
        final Path file = directory.resolve(PROJECT.file());
        final byte[] head = head("fory").getBytes(StandardCharsets.US_ASCII);
        final Random random = new Random(94);
        for (int round = 0; round < 500; round++) {
            final byte[] body = new byte[random.nextInt(4096)];
            random.nextBytes(body);
            Files.write(file, joined(head, body));

            assertThat(copies().read(PROJECT, "c0ffee0"))
                    .as("random bytes, round %d", round)
                    .isEmpty();
        }
        copies().write(copy(PROJECT, "c0ffee0"));
        assertThat(copies().read(PROJECT, "c0ffee0"))
                .as("a copy written after the random ones")
                .contains(copy(PROJECT, "c0ffee0"));
    }

    @Test
    void aCopyCutShortOrWithBytesAfterItIsIgnored() throws IOException {
        // Harm: a copy cut short by a full disk or a crash read as a board with items missing.
        copies().write(copy(PROJECT, "c0ffee0"));
        final Path file = directory.resolve(PROJECT.file());
        final byte[] whole = Files.readAllBytes(file);
        final int head = head("fory").length();
        for (int length = head; length < whole.length; length++) {
            Files.write(file, Arrays.copyOf(whole, length));

            assertThat(copies().read(PROJECT, "c0ffee0"))
                    .as("the copy cut at %d of %d bytes", length, whole.length)
                    .isEmpty();
        }
        Files.write(file, joined(whole, new byte[] {0}));

        assertThat(copies().read(PROJECT, "c0ffee0")).as("a copy with a byte after it").isEmpty();
    }

    /** Huge lengths as Fory reads them, 2^31 among them. */
    private static final long[] HUGE = {1L << 31, (1L << 31) - 1, 1L << 30, 1L << 33, 1L << 35};

    /**
     * The bytes with a huge length written over {@code cut} of them at {@code at}, as a tampered
     * file holds it.
     */
    private static byte[] tampered(
            final byte[] whole, final int at, final int cut, final byte[] length) {
        final byte[] tampered = new byte[whole.length - cut + length.length];
        System.arraycopy(whole, 0, tampered, 0, at);
        System.arraycopy(length, 0, tampered, at, length.length);
        System.arraycopy(whole, at + cut, tampered, at + length.length, whole.length - at - cut);
        return tampered;
    }

    @Test
    void aLengthOfTwoToTheThirtyFirstAnywhereInACopyIsRefusedNeverAnError() throws IOException {
        // Harm: a small tampered copy whose one length asks for gigabytes, and a planner that
        // stops at start with OutOfMemoryError rather than reading the board from its tracker.
        copies().write(copy(PROJECT, "c0ffee0"));
        final Path file = directory.resolve(PROJECT.file());
        final byte[] whole = Files.readAllBytes(file);
        final int head = head("fory").length();
        int refused = 0;
        int tried = 0;
        for (final long value : HUGE) {
            for (int at = head; at < whole.length; at++) {
                for (int cut = 0; cut <= 5 && at + cut <= whole.length; cut++) {
                    Files.write(file, tampered(whole, at, cut, varint(value)));
                    final AtomicReference<Optional<BoardCopy>> read = new AtomicReference<>();

                    assertThatCode(() -> read.set(copies().read(PROJECT, "c0ffee0")))
                            .as("the copy with a length of %d at byte %d", value, at)
                            .doesNotThrowAnyException();
                    tried++;
                    refused += read.get().isEmpty() ? 1 : 0;
                }
            }
        }
        // A few places hold no length but a number, which a copy with another number takes.
        assertThat(refused)
                .as("the tampered copies refused of %d", tried)
                .isGreaterThan(tried * 9 / 10);
        copies().write(copy(PROJECT, "c0ffee0"));
        assertThat(copies().read(PROJECT, "c0ffee0"))
                .as("a copy read after the tampered ones, in the same JVM")
                .contains(copy(PROJECT, "c0ffee0"));
    }

    @Test
    void aFileOfAnotherTypeWithAHugeLengthIsRefusedNeverAnError() throws IOException {
        // Harm: a file that names an array or a collection of the JDK in place of the copy, its
        // length set so the planner allocates gigabytes for it.
        final Fory other =
                Fory.builder()
                        .withLanguage(Language.JAVA)
                        .requireClassRegistration(true)
                        .withRefTracking(false)
                        .withStringCompressed(true)
                        .build();
        final Path file = directory.resolve(PROJECT.file());
        final byte[] head = head("fory").getBytes(StandardCharsets.US_ASCII);
        for (final Object sample :
                List.of(
                        new long[] {1},
                        new byte[] {1},
                        new char[] {'a'},
                        new String[] {"a"},
                        new Object[] {"a"},
                        new ArrayList<>(List.of("a")),
                        new HashMap<>(Map.of("a", "b")),
                        "a")) {
            final byte[] whole = joined(head, other.serialize(sample));
            for (int at = head.length; at < whole.length; at++) {
                for (int cut = 0; cut <= 5 && at + cut <= whole.length; cut++) {
                    Files.write(file, tampered(whole, at, cut, varint(1L << 31)));

                    assertThat(copies().read(PROJECT, "c0ffee0"))
                            .as("a %s with a length of 2^31 at byte %d", sample.getClass(), at)
                            .isEmpty();
                }
            }
        }
    }

    @Test
    void aCopyLargerThanTheBoundIsRefusedBeforeForyReadsIt() throws Exception {
        // Harm: a file of tens of megabytes decoded whole before it is found to be no copy.
        copies().write(copy(PROJECT, "c0ffee0"));
        final Path file = directory.resolve(PROJECT.file());
        final byte[] whole = Files.readAllBytes(file);
        final byte[] over = java.util.Arrays.copyOf(whole, BoardCopies.MOST_BYTES + 1);
        Files.write(file, over);

        assertThat(copies().read(PROJECT, "c0ffee0"))
                .as("a copy one byte over the bound")
                .isEmpty();
    }

    /** A number as Fory writes a length: seven bits a byte, the lowest first. */
    private static byte[] varint(final long value) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        long rest = value;
        while ((rest & ~0x7FL) != 0) {
            out.write((int) ((rest & 0x7F) | 0x80));
            rest >>>= 7;
        }
        out.write((int) rest);
        return out.toByteArray();
    }

    /** The head line of a copy in an encoding, of this planner's format. */
    private static String head(final String encoding) {
        return BoardCopies.HEAD + " " + encoding + " " + BoardCopies.FORMAT + "\n";
    }

    private static byte[] joined(final byte[] first, final byte[] second) {
        final byte[] both = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    @Test
    void aCopyThatIsNotTheAccountsOwnIsNotRead() throws IOException {
        // Harm: another account plants a board the planner shows and writes moves against.
        copies().write(copy(PROJECT, "c0ffee0"));
        refused.add(directory.resolve(PROJECT.file()));

        assertThat(copies().read(PROJECT, "c0ffee0")).as("another account's copy").isEmpty();
    }

    @Test
    void aLinkInPlaceOfTheCopyIsNotFollowed() throws IOException {
        // Harm: a link that makes the planner read, or overwrite, a file elsewhere.
        final Path elsewhere = Files.createDirectory(directory.resolve("elsewhere"));
        new BoardCopies(elsewhere, files).write(copy(PROJECT, "c0ffee0"));
        Files.createSymbolicLink(
                directory.resolve(PROJECT.file()), elsewhere.resolve(PROJECT.file()));

        assertThat(copies().read(PROJECT, "c0ffee0")).as("a copy behind a link").isEmpty();
    }

    @Test
    void aRemovedCopyIsGone() {
        // Harm: a board the planner stopped trusting after a failed save shown at the next start.
        copies().write(copy(PROJECT, "c0ffee0"));
        copies().remove(PROJECT);

        assertThat(Files.exists(directory.resolve(PROJECT.file()), LinkOption.NOFOLLOW_LINKS))
                .as("the copy's file after it was removed")
                .isFalse();
        assertThat(copies().read(PROJECT, "c0ffee0")).as("the copy after it was removed").isEmpty();
    }

    @Test
    void aProjectNamedOutsideTheDirectoryIsRefused() {
        // Harm: a project's owner that names a path writes the copy outside the directory.
        for (final String owner : List.of("../x", "a/b", "", ".hidden", "a..b")) {
            assertThatThrownBy(() -> new BoardCopy.Project("github.com", owner, 1))
                    .as("the owner %s", owner)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** A record as an older planner wrote it. */
    static final class Older {
        record Item(String id, int number) {}
    }

    /** The same record with one component's type changed. */
    static final class Newer {
        record Item(String id, long number) {}
    }

    @Test
    void theFormatChangesWithAComponentsType() {
        // Harm: a record changed without the format, so an old copy is read into the new one.
        final String older = BoardCopies.format(Older.Item.class);
        final String newer = BoardCopies.format(Newer.Item.class);

        assertThat(older.equals(newer)).as("one format for both").isFalse();
        assertThat(BoardCopies.FORMAT).as("the format").hasSize(16);
    }
}
