package org.fuin.porelo.planner.store;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An item's own stored edit basis, opaque to every caller: two equal versions of one item mean its
 * own stored state is unchanged, and nothing else may be concluded from it. A value the item takes
 * from another item is not part of it.
 *
 * @param value the store's own text for the item's state
 */
public record Version(String value) {

    /** Refuses an empty version. */
    public Version {
        if (value.isBlank()) {
            throw new IllegalArgumentException("A version must not be empty");
        }
    }

    /**
     * The version over the given values, a SHA-256 of their fixed encoding: each value its kind and
     * its length before it, so no two lists of values share one encoding. A list counts as one
     * value of its elements; null stands apart from every text.
     *
     * @param values the item's own stored values, in a fixed order
     * @return their version
     */
    public static Version of(final @Nullable Object... values) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("No SHA-256 in this runtime", e);
        }
        for (final Object value : values) {
            add(digest, value);
        }
        return new Version(HexFormat.of().formatHex(digest.digest()));
    }

    private static void add(final MessageDigest digest, final @Nullable Object value) {
        if (value == null) {
            digest.update((byte) 'n');
        } else if (value instanceof List<?> list) {
            digest.update((byte) 'l');
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(list.size()).array());
            for (final Object element : list) {
                add(digest, element);
            }
        } else {
            final byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            digest.update((byte) 't');
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }
}
