package org.fuin.porelo.planner.store.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.apache.fory.context.ReadContext;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.logging.LogLevel;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.jspecify.annotations.Nullable;

/**
 * A copy in Apache Fory's Java format: only the copy's own records and the JDK's types are read,
 * each record through its own constructor, so a part it requires that is missing refuses the copy.
 *
 * <p>Fory generates no serializer code: each record is read and written by Fory's own serializer,
 * so no file makes it compile one while it reads. The native image initializes this class when it
 * is built ({@code --initialize-at-build-time}), so Fory's serializers are made then.
 */
public final class ForyEncoding implements CopyEncoding {

    /**
     * The deepest nesting Fory reads: a copy nests its records and lists nine deep, and a tampered
     * file that nests deeper is refused rather than run into the stack's end.
     */
    static final int MOST_DEPTH = 16;

    /** The most fields of one record Fory reads; the copy's largest record has seventeen. */
    static final int MOST_FIELDS = 64;

    /**
     * The most memory, as Fory counts it before it makes an object, a list or an array, that one
     * byte of a copy's file may make Fory reserve: a copy of thousands of small items takes less
     * than two, so a copy is never refused for its size, while a length a tampered file names is
     * refused before Fory allocates for it.
     */
    static final long GRAPH_PER_BYTE = 16;

    /** The bytes a copy is encoded into first, grown as the copy needs. */
    private static final int FIRST_BUFFER = 64 * 1024;

    /**
     * Every record a copy is made of, registered in this order: a record's number in the file is
     * its place here.
     */
    static final List<Class<?>> RECORDS =
            List.of(
                    BoardCopy.class,
                    BoardCopy.Project.class,
                    TrackerBoard.class,
                    TrackedItem.class,
                    TrackedItem.Issue.class,
                    TrackedItem.Links.class,
                    TrackedItem.Link.class,
                    TrackedItem.Value.class,
                    TrackedItem.Choice.class,
                    ChangeCursor.class,
                    ChangeCursor.Page.class,
                    TrackedWait.class,
                    TrackedUnlisted.class,
                    OverlaidItem.class);

    /** The one Fory of the planner, made when the class is initialized. */
    private static final Fory FORY = make();

    /** Guards {@link #FORY}, which is not safe to use from two threads at once. */
    private static final Object LOCK = new Object();

    private static Fory make() {
        // Fory's own log writes to the standard output; the copy's faults are logged by its reader.
        LoggerFactory.setLogLevel(LogLevel.WARN_LEVEL);
        final Fory fory =
                Fory.builder()
                        .withLanguage(Language.JAVA)
                        .requireClassRegistration(true)
                        .withRefTracking(false)
                        // No serializer is compiled, at build or run time: a tampered byte that
                        // names a type none of the records is would have Fory compile one for it
                        // while it reads, allocating megabytes before it refuses the copy.
                        .withCodegen(false)
                        .withStringCompressed(true)
                        // The nesting and the fields a file names are held to these bounds. The
                        // memory Fory may reserve is held per read to the file's own size, by
                        // Budgeted below, so a project of any size keeps its copy.
                        .withMaxDepth(MOST_DEPTH)
                        .withMaxTypeFields(MOST_FIELDS)
                        .withMaxGraphMemoryBytes(Long.MAX_VALUE)
                        .build();
        RECORDS.forEach(fory::register);
        fory.ensureSerializersCompiled();
        return fory;
    }

    /** Makes the encoding; every instance shares the one Fory. */
    public ForyEncoding() {
        // Nothing of its own: the Fory is the class's.
    }

    @Override
    public String name() {
        return "fory";
    }

    @Override
    public void encode(final BoardCopy copy, final OutputStream out) throws IOException {
        // A buffer of this call's own: the one Fory is shared, and is let go before the disk is
        // written to, so a slow disk holds no other copy's reading or writing.
        final MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(FIRST_BUFFER);
        try {
            synchronized (LOCK) {
                FORY.serialize(buffer, copy);
            }
        } catch (final RuntimeException e) {
            throw new IllegalStateException(
                    "the copy could not be written by Fory (" + e.getClass().getSimpleName() + ")");
        }
        final byte[] heap = buffer.getHeapMemory();
        if (heap != null) {
            out.write(heap, 0, buffer.writerIndex());
        } else {
            out.write(buffer.getBytes(0, buffer.writerIndex()));
        }
    }

    @Override
    public BoardCopy decode(final InputStream in, final long length) throws IOException {
        // Read as Fory needs the bytes, so the file is never held a second time beside them.
        final Budgeted bytes =
                new Budgeted(
                        in,
                        length,
                        length > Long.MAX_VALUE / GRAPH_PER_BYTE
                                ? Long.MAX_VALUE
                                : length * GRAPH_PER_BYTE);
        final ForyInputStream stream = new ForyInputStream(bytes);
        bytes.reader = stream;
        final Object read;
        try {
            synchronized (LOCK) {
                read = FORY.deserialize(stream);
            }
        } catch (final RuntimeException | StackOverflowError e) {
            // Fory's own words may quote the file: only their kind is named, and the cause is
            // kept for the caller, never logged.
            throw new IllegalArgumentException(
                    "the bytes are no copy (" + e.getClass().getSimpleName() + ")", e);
        }
        if (!(read instanceof BoardCopy copy)) {
            throw new IllegalArgumentException("the bytes hold no copy");
        }
        if (stream.read() != -1) {
            throw new IllegalArgumentException("the bytes go on after the copy");
        }
        return copy;
    }

    /**
     * A copy's bytes as Fory reads them, which hold the memory Fory may reserve for this one read
     * to the copy's size, and end there.
     *
     * <p>Fory reads a copy's first byte before it prepares the read, and prepares it with the whole
     * memory its configuration allows; every later byte comes after. So the first read hands Fory
     * one byte only, and the second, before it hands over anything, reserves all but the copy's
     * budget of the prepared read. A read in another order is refused, never let through without
     * the budget.
     */
    private static final class Budgeted extends InputStream {

        private final InputStream in;

        private final long budget;

        private long left;

        private int reads;

        /** The stream Fory reads through, whose buffer is the one a prepared read holds. */
        private @Nullable ForyInputStream reader;

        Budgeted(final InputStream in, final long length, final long budget) {
            this.in = in;
            this.left = length;
            this.budget = budget;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(final byte[] bytes, final int offset, final int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (reads == 1) {
                reserve();
            }
            if (reads < 2) {
                reads++;
            }
            if (left == 0) {
                return -1;
            }
            final int most = reads == 1 ? 1 : (int) Math.min(length, left);
            final int read = in.read(bytes, offset, most);
            if (read > 0) {
                left -= read;
            }
            return read;
        }

        /** Leaves the prepared read only the copy's budget of the memory Fory may reserve. */
        private void reserve() {
            final ReadContext context = FORY.getReadContext();
            final ForyInputStream stream = reader;
            if (stream == null || context.getBuffer() != stream.getBuffer()) {
                throw new IllegalStateException("Fory read the copy before it prepared the read");
            }
            context.reserveGraphMemory(Long.MAX_VALUE - budget);
        }
    }
}
