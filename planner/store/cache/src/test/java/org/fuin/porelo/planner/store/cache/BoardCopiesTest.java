package org.fuin.porelo.planner.store.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                        new TrackedItem.Links(List.of(), false, null, 0),
                        "Story",
                        List.of(new TrackedItem.Choice("Priority", "Urgent")));
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
                List.of(new TrackedItem("PVTI_held", false, null, List.of(), null, false)),
                List.of(
                        new TrackedWait("I_far", "other-org/elsewhere", 7, false),
                        new TrackedWait("I_hidden", "other-org/private", 8, null)),
                List.of(
                        new TrackedUnlisted("I_made", "porelo-planner", 4, "Made by hand", "Story"),
                        new TrackedUnlisted("I_plain", "porelo-planner", 5, "Plain", null)));
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
    void foryKnowsEveryRecordACopyIsMadeOf() {
        // Harm: a record added to the copy that Fory refuses, so no copy is written again and
        // every start reads the board whole.
        assertThat(ForyEncoding.RECORDS)
                .as("the records Fory registers")
                .containsExactlyInAnyOrderElementsOf(BoardCopies.records());
        assertThat(BoardCopies.records())
                .as("the records found from the copy")
                .contains(BoardCopy.class, TrackedUnlisted.class, TrackedWait.class);
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
    void aCopyWrittenBeforeWaitsWereKeptIsNotRead() throws IOException {
        // Harm: an older copy read with no waits, so an issue waiting on another repository's is
        // offered for work at the next start.
        copies().write(copy(PROJECT, "c0ffee0"));
        final Path file = directory.resolve(PROJECT.file());
        final byte[] bytes = Files.readAllBytes(file);
        final byte[] head = head("fory").getBytes(StandardCharsets.US_ASCII);
        final byte[] older =
                head("fory")
                        .replace(BoardCopies.FORMAT, "cc2d665c0746ef84")
                        .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(older, 0, bytes, 0, head.length);
        Files.write(file, bytes);

        assertThat(copies().read(PROJECT, "c0ffee0"))
                .as("a copy of the format before waits were kept")
                .isEmpty();
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

    /**
     * Lengths no small copy can hold, as Fory reads them: some below what Fory reserves by its own
     * default, so only a bound by the file's size refuses them before they are allocated, and some
     * of 2^30 and more.
     */
    private static final long[] HUGE = {
        1L << 16, 1L << 20, 1L << 24, 1L << 31, (1L << 31) - 1, 1L << 30, 1L << 33, 1L << 35
    };

    /** The most bytes reading a small copy may allocate, far below any length in {@link #HUGE}. */
    private static final long MOST_ALLOCATED = 2L * 1024 * 1024;

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

    /** The bytes the thread allocated so far, as the JVM counts them. */
    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    @Test
    void aLengthNoCopyCanHoldAnywhereInACopyIsRefusedBeforeItIsAllocated() throws IOException {
        // Harm: a small tampered copy whose one length asks for megabytes or gigabytes, and a
        // planner that allocates them, or stops at start with OutOfMemoryError, rather than
        // reading the board from its tracker.
        copies().write(copy(PROJECT, "c0ffee0"));
        final Path file = directory.resolve(PROJECT.file());
        final byte[] whole = Files.readAllBytes(file);
        final int head = head("fory").length();
        // Fory's first read of the JVM makes what it keeps; a tampered read is measured after.
        assertThat(copies().read(PROJECT, "c0ffee0")).as("the copy untouched").isPresent();
        for (final long value : HUGE) {
            for (int at = head; at < whole.length; at++) {
                for (int cut = 0; cut <= 5 && at + cut <= whole.length; cut++) {
                    final byte[] bytes = tampered(whole, at, cut, varint(value));
                    Files.write(file, bytes);
                    final AtomicReference<Optional<BoardCopy>> read = new AtomicReference<>();
                    final long before = allocated();

                    assertThatCode(() -> read.set(copies().read(PROJECT, "c0ffee0")))
                            .as("the copy with a length of %d at byte %d", value, at)
                            .doesNotThrowAnyException();
                    assertThat(allocated() - before)
                            .as("the bytes allocated for a length of %d at byte %d", value, at)
                            .isLessThan(MOST_ALLOCATED);
                    // Where the bytes stand for a number, not a length, they are a copy with
                    // another number. Such a copy is taken only when it holds no more than its
                    // bytes do, so no length of it was taken: every tampered length is refused.
                    if (read.get().isPresent()) {
                        final ByteArrayOutputStream again = new ByteArrayOutputStream();
                        new ForyEncoding().encode(read.get().get(), again);
                        assertThat(again.size())
                                .as("the copy taken with %d at byte %d, written again", value, at)
                                .isLessThanOrEqualTo(whole.length + 16);
                    }
                }
            }
        }
        copies().write(copy(PROJECT, "c0ffee0"));
        assertThat(copies().read(PROJECT, "c0ffee0"))
                .as("a copy read after the tampered ones, in the same JVM")
                .contains(copy(PROJECT, "c0ffee0"));
    }

    @Test
    void aCopyWrittenToASlowDiskHoldsNoOtherCopysReading() throws Exception {
        // Harm: one project's copy written to a slow or stuck disk holds the shared encoding, so
        // every other project's start waits on that disk.
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final OutputStream stuck =
                new OutputStream() {
                    @Override
                    public void write(final int b) throws IOException {
                        write(new byte[] {(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(final byte[] b, final int off, final int len)
                            throws IOException {
                        writing.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("interrupted", e);
                        }
                    }
                };
        final ForyEncoding encoding = new ForyEncoding();
        final CompletableFuture<Void> slow =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                encoding.encode(copy(OTHER, "c0ffee0"), stuck);
                            } catch (final IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
        try {
            assertThat(writing.await(30, TimeUnit.SECONDS))
                    .as("the other copy's write under way")
                    .isTrue();
            final CompletableFuture<Optional<BoardCopy>> read =
                    CompletableFuture.supplyAsync(
                            () -> {
                                copies().write(copy(PROJECT, "c0ffee0"));
                                return copies().read(PROJECT, "c0ffee0");
                            });

            assertThat(read.get(10, TimeUnit.SECONDS))
                    .as("a copy written and read while another one's write waits on its disk")
                    .contains(copy(PROJECT, "c0ffee0"));
        } finally {
            release.countDown();
        }
        slow.get(30, TimeUnit.SECONDS);
    }

    @Test
    void aFileOfAnotherTypeWithALengthItCannotHoldIsRefusedBeforeItIsAllocated()
            throws IOException {
        // Harm: a file that names an array or a collection of the JDK in place of the copy, its
        // length set so the planner allocates megabytes or gigabytes for it.
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
            // Below and above what Fory reserves by its own default: an array of 2^23 longs
            // takes 64 MiB, which only a bound by the file's size refuses before it is made.
            for (final long length : List.of(1L << 20, 1L << 23, 1L << 31)) {
                for (int at = head.length; at < whole.length; at++) {
                    for (int cut = 0; cut <= 5 && at + cut <= whole.length; cut++) {
                        Files.write(file, tampered(whole, at, cut, varint(length)));
                        final long before = allocated();

                        assertThat(copies().read(PROJECT, "c0ffee0"))
                                .as(
                                        "a %s with a length of %d at byte %d",
                                        sample.getClass().getSimpleName(), length, at)
                                .isEmpty();
                        assertThat(allocated() - before)
                                .as(
                                        "the bytes allocated for a %s, %d at byte %d",
                                        sample.getClass().getSimpleName(), length, at)
                                .isLessThan(MOST_ALLOCATED);
                    }
                }
            }
        }
    }

    @Test
    void aListLongerThanItsFileCouldFillIsRefusedBeforeItIsMade() {
        // Harm: a few tampered bytes make the planner allocate a list of thousands of entries,
        // which a file of their size could never fill, before it finds the bytes missing.
        final Fory other =
                Fory.builder()
                        .withLanguage(Language.JAVA)
                        .requireClassRegistration(true)
                        .withRefTracking(false)
                        .withStringCompressed(true)
                        .build();
        final byte[] one = other.serialize(new ArrayList<>(List.of("a")));
        final byte[] two = other.serialize(new ArrayList<>(List.of("a", "a")));
        // The first byte the two differ in is the list's size.
        int at = 0;
        while (one[at] == two[at]) {
            at++;
        }
        // Below what Fory takes unbacked by its own default, far above what the bytes hold.
        final byte[] bytes = tampered(one, at, 1, varint(8000));

        assertThatThrownBy(
                        () ->
                                new ForyEncoding()
                                        .decode(new ByteArrayInputStream(bytes), bytes.length))
                .as("a list of 8000 entries in %d bytes", bytes.length)
                .isInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("maxGraphMemoryBytes");
    }

    @Test
    void aCopyOfAProjectOfThousandsOfFullIssuesIsReadBackAsItWas() throws Exception {
        // Harm: a large project of legal items, its copy written but never read, so every start
        // reads its whole board from the tracker again.
        final BoardCopy small = copy(PROJECT, "c0ffee0");
        final TrackedItem.Issue one =
                java.util.Objects.requireNonNull(small.items().getFirst().issue(), "issue");
        final String title = "T".repeat(256);
        final String body = "x".repeat(65536);
        final List<TrackedItem> items = new ArrayList<>();
        for (int number = 1; number <= 1100; number++) {
            items.add(
                    new TrackedItem(
                            "PVTI_" + number,
                            false,
                            new TrackedItem.Issue(
                                    "I_" + number,
                                    number,
                                    number,
                                    title,
                                    body,
                                    one.state(),
                                    null,
                                    one.updatedAt(),
                                    one.repository(),
                                    one.labels(),
                                    null,
                                    one.subIssues(),
                                    one.blockedBy(),
                                    one.type(),
                                    one.choices()),
                            List.of(),
                            one.updatedAt(),
                            false));
        }
        final BoardCopy large =
                new BoardCopy(
                        small.project(),
                        small.filesCommit(),
                        small.repositories(),
                        small.takenAt(),
                        small.board(),
                        items,
                        small.cursor(),
                        small.held(),
                        small.waits(),
                        small.unlisted());
        copies().write(large);
        assertThat(Files.size(directory.resolve(PROJECT.file())))
                .as("the copy's size, above the bound a copy once had")
                .isGreaterThan(64L * 1024 * 1024);

        // Compared whole, never printed: a failure would render tens of megabytes.
        final Optional<BoardCopy> read = copies().read(PROJECT, "c0ffee0");
        assertThat(read.isPresent()).as("a copy read back").isTrue();
        assertThat(read.get().equals(large)).as("the copy read back as it was").isTrue();
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
