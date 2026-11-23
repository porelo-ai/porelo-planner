package org.fuin.porelo.planner.store.cache;

import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.apache.fory.logging.LogLevel;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;

/**
 * A copy in Apache Fory's Java format: only the copy's own records and the JDK's types are read,
 * each record through its own constructor, so a part it requires that is missing refuses the copy.
 *
 * <p>The native image initializes this class when it is built ({@code --initialize-at-build-time}),
 * so Fory's serializers are compiled then and the executable compiles none at run time.
 */
public final class ForyEncoding implements CopyEncoding {

    /**
     * The deepest nesting Fory reads: a copy nests its records and lists eight deep, and a tampered
     * file that nests deeper is refused rather than run into the stack's end.
     */
    static final int MOST_DEPTH = 16;

    /** The most fields of one record Fory reads; the copy's largest record has fourteen. */
    static final int MOST_FIELDS = 64;

    /**
     * The most memory the objects of one decoded copy may take, as Fory counts it before it makes
     * them: twice the largest file, as a string compressed to one byte a character may take two in
     * memory.
     */
    static final long MOST_GRAPH_BYTES = 2L * BoardCopies.MOST_BYTES;

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
                    TrackedUnlisted.class);

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
                        .withCodegen(true)
                        .withStringCompressed(true)
                        // Every length Fory reads from the file is held to these bounds, or to the
                        // bytes left, before it allocates for it.
                        .withMaxDepth(MOST_DEPTH)
                        .withMaxTypeFields(MOST_FIELDS)
                        .withMaxGraphMemoryBytes(MOST_GRAPH_BYTES)
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
    public byte[] encode(final BoardCopy copy) {
        try {
            synchronized (LOCK) {
                return FORY.serialize(copy);
            }
        } catch (final RuntimeException e) {
            throw new IllegalStateException(
                    "the copy could not be written by Fory (" + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public BoardCopy decode(final byte[] bytes) {
        final Object read;
        final MemoryBuffer buffer = MemoryBuffer.fromByteArray(bytes);
        try {
            synchronized (LOCK) {
                read = FORY.deserialize(buffer);
            }
        } catch (final RuntimeException | StackOverflowError | OutOfMemoryError e) {
            // The last guard for a length no bound above caught: the allocation it asked for
            // failed, so nothing of it is held. Fory's own words may quote the file; only their
            // kind is named.
            throw new IllegalArgumentException(
                    "the bytes are no copy (" + e.getClass().getSimpleName() + ")");
        }
        if (!(read instanceof BoardCopy copy)) {
            throw new IllegalArgumentException("the bytes hold no copy");
        }
        if (buffer.readerIndex() != bytes.length) {
            throw new IllegalArgumentException("the bytes go on after the copy");
        }
        return copy;
    }
}
