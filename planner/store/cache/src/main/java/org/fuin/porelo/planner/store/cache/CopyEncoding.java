package org.fuin.porelo.planner.store.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** How a board's copy is turned into the bytes of its file and back. */
public interface CopyEncoding {

    /**
     * The encoding's name, written at the head of each file, so a file of another encoding is not
     * read as this one's.
     *
     * @return a plain name of letters and digits
     */
    String name();

    /**
     * Encodes a copy onto a stream, which it neither ends nor closes.
     *
     * @param copy the copy
     * @param out where its bytes go
     * @throws IOException when the stream cannot be written
     * @throws IllegalStateException when the copy cannot be encoded
     */
    void encode(BoardCopy copy, OutputStream out) throws IOException;

    /**
     * Decodes a copy its own {@link #encode} wrote, reading the stream to its end.
     *
     * @param in the bytes after the file's head
     * @param length how many bytes follow the head, as the file's size gives them; no more are
     *     read, and the memory the decoding may take is bounded by them
     * @return the copy, its records checked as they are made
     * @throws IOException when the stream cannot be read
     * @throws IllegalArgumentException when the bytes are no copy, or go on after it
     */
    BoardCopy decode(InputStream in, long length) throws IOException;
}
