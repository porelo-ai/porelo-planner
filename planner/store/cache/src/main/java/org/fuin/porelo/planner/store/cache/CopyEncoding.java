package org.fuin.porelo.planner.store.cache;

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
     * Encodes a copy.
     *
     * @param copy the copy
     * @return its bytes
     * @throws IllegalStateException when it cannot be encoded
     */
    byte[] encode(BoardCopy copy);

    /**
     * Decodes a copy its own {@link #encode} wrote.
     *
     * @param bytes the bytes after the file's head
     * @return the copy, its records checked as they are made
     * @throws IllegalArgumentException when the bytes are no copy
     */
    BoardCopy decode(byte[] bytes);
}
