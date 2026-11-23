package org.fuin.porelo.planner.store.git.stories;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A session file opened for one read: the file itself, never what a link points to, held to a
 * regular file's behavior on the opened channel and read only up to the size it had when opened.
 */
final class SessionFile {

    /** The refusal of a session that is no readable regular file. */
    static final String NOT_REGULAR = "session is not a readable regular file";

    /** The refusal of a session that took more bytes while it was read. */
    static final String GREW = "session grew while it was read";

    private SessionFile() {}

    /**
     * Opens a session for reading as UTF-8 text. Opening a FIFO waits for a writer, so a FIFO or a
     * link is refused by its name before it is opened; what is opened is then held to a regular
     * file itself.
     *
     * @param file the session
     * @return its text, up to the size it had when opened
     * @throws IOException when it cannot be read
     * @throws IllegalArgumentException when it is no readable regular file
     */
    static Reader open(final Path file) throws IOException {
        StageCommand.readable(file);
        return opened(file);
    }

    /**
     * Opens a session without looking at its name first: the file itself, never a link's target,
     * and only a channel that reads by position, as a regular file does and a pipe, a socket or a
     * directory does not. The text ends at the size the file had when opened; a byte past it is
     * refused with {@link #GREW} when the end is read.
     *
     * @param file the session
     * @return its text
     * @throws IOException when it cannot be read
     * @throws IllegalArgumentException when it is no readable regular file
     */
    static Reader opened(final Path file) throws IOException {
        final FileChannel channel;
        try {
            channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (final IOException ex) {
            // A link refused by the opening, or a file not there or not readable; the words name
            // the private path, so only the refusal leaves.
            throw new IllegalArgumentException(NOT_REGULAR);
        }
        try {
            final long size = channel.size();
            channel.read(ByteBuffer.allocate(1), 0);
            return new InputStreamReader(
                    new Bounded(channel, size), StandardCharsets.UTF_8.newDecoder());
        } catch (final IOException ex) {
            channel.close();
            throw new IllegalArgumentException(NOT_REGULAR);
        }
    }

    /** The bytes of a channel up to a size, which refuses a byte past it at its end. */
    private static final class Bounded extends InputStream {

        private final FileChannel channel;

        private long left;

        private boolean ended;

        Bounded(final FileChannel channel, final long size) {
            this.channel = channel;
            this.left = size;
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
            if (ended) {
                return -1;
            }
            if (left == 0) {
                ended = true;
                if (channel.read(ByteBuffer.allocate(1)) > 0) {
                    throw new IllegalArgumentException(GREW);
                }
                return -1;
            }
            final int read =
                    channel.read(ByteBuffer.wrap(bytes, offset, (int) Math.min(length, left)));
            if (read < 0) {
                // The file shrank while read: its text ends where its bytes do.
                ended = true;
                return -1;
            }
            left -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
