package org.fuin.porelo.planner.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The forms a caller and the planner sign under the token: a request, an answer and a proof, each
 * behind a label of its own, so one can never stand for another.
 *
 * <p>A request's and an answer's fields are each prefixed by their length as four bytes, big
 * endian, so no two different requests sign the same bytes.
 */
final class CallSignature {

    /** The header a signed request carries instead of the token. */
    static final String HEADER = "Porelo-Signature";

    /** The header an answer to a signed request carries. */
    static final String ANSWER_HEADER = "Porelo-Answer";

    /** The label of a request's form, and the first word of its header. */
    static final String REQUEST = "porelo-planner-request-1";

    /** The label of an answer's form, and the first word of its header. */
    static final String ANSWER = "porelo-planner-answer-1";

    /** The label of a proof's form. */
    static final String PROOF = "porelo-planner-proof-1";

    /** The bytes of a nonce. */
    static final int NONCE = 16;

    /** The bytes of a proof's challenge. */
    static final int CHALLENGE = 32;

    private static final Pattern REQUEST_HEADER =
            Pattern.compile(
                    "^"
                            + REQUEST
                            + ";t=(0|[1-9][0-9]{0,11});n=([A-Za-z0-9_-]{22});s=([A-Za-z0-9_-]{43})$");

    private CallSignature() {}

    /** A request header's parts, its time in seconds, its nonce and its HMAC. */
    record Signed(long time, byte[] nonce, byte[] mac) {}

    /**
     * Reads a request's header strictly: its label, a time in seconds, the nonce and the HMAC, each
     * in its canonical form.
     *
     * @param header The header's value.
     * @return Its parts, or {@code null} when it is not of that form.
     */
    static @Nullable Signed read(final String header) {
        final Matcher parts = REQUEST_HEADER.matcher(header);
        if (!parts.matches()) {
            return null;
        }
        final byte[] nonce = canonical(parts.group(2), NONCE);
        final byte[] mac = canonical(parts.group(3), 32);
        if (nonce == null || mac == null) {
            return null;
        }
        return new Signed(Long.parseLong(parts.group(1)), nonce, mac);
    }

    /**
     * The bytes a base64url text without padding stands for, only when it is their one canonical
     * form.
     *
     * @param text The text.
     * @param bytes How many bytes it must stand for.
     * @return The bytes, or {@code null}.
     */
    static byte @Nullable [] canonical(final String text, final int bytes) {
        final byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(text);
        } catch (final IllegalArgumentException malformed) {
            return null;
        }
        if (decoded.length != bytes || !base64(decoded).equals(text)) {
            return null;
        }
        return decoded;
    }

    /**
     * The base64url text of bytes, without padding.
     *
     * @param bytes The bytes.
     * @return Their text.
     */
    static String base64(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The header a signed request carries.
     *
     * @param time The UTC time in seconds it was signed at.
     * @param nonce Its nonce.
     * @param mac Its HMAC.
     * @return The header's value.
     */
    static String header(final long time, final byte[] nonce, final byte[] mac) {
        return REQUEST + ";t=" + time + ";n=" + base64(nonce) + ";s=" + base64(mac);
    }

    /**
     * The HMAC of a request.
     *
     * @param token The planner's token.
     * @param method The method.
     * @param target The raw request target as sent: the path, and {@code ?} and the raw query when
     *     there is one.
     * @param host The {@code Host} header.
     * @param contentType The {@code Content-Type} header, empty when absent.
     * @param body The body's bytes as read, empty when absent.
     * @param time The UTC time in seconds.
     * @param nonce The nonce.
     * @return The HMAC.
     */
    static byte[] request(
            final AccessToken token,
            final String method,
            final String target,
            final String host,
            final String contentType,
            final byte[] body,
            final long time,
            final byte[] nonce) {
        final ByteArrayOutputStream form = labeled(REQUEST);
        field(form, method.getBytes(StandardCharsets.UTF_8));
        field(form, target.getBytes(StandardCharsets.UTF_8));
        field(form, host.getBytes(StandardCharsets.UTF_8));
        field(form, contentType.getBytes(StandardCharsets.UTF_8));
        field(form, sha256(body));
        field(form, Long.toString(time).getBytes(StandardCharsets.US_ASCII));
        field(form, nonce);
        return token.mac(form.toByteArray());
    }

    /**
     * The HMAC an answer to a signed request carries.
     *
     * @param token The planner's token.
     * @param nonce The request's nonce.
     * @param status The answer's status.
     * @param body The answer's body, or {@code null} for the opening of a stream, whose events are
     *     not part of it.
     * @return The HMAC.
     */
    static byte[] answer(
            final AccessToken token,
            final byte[] nonce,
            final int status,
            final byte @Nullable [] body) {
        final ByteArrayOutputStream form = labeled(ANSWER);
        field(form, nonce);
        field(form, Integer.toString(status).getBytes(StandardCharsets.US_ASCII));
        field(form, body == null ? new byte[0] : sha256(body));
        return token.mac(form.toByteArray());
    }

    /**
     * The header an answer to a signed request carries.
     *
     * @param mac The answer's HMAC.
     * @return The header's value.
     */
    static String answerHeader(final byte[] mac) {
        return ANSWER + ";s=" + base64(mac);
    }

    /**
     * The proof that the planner listening on a port holds the token.
     *
     * @param token The planner's token.
     * @param port The port the planner listens on.
     * @param challenge The caller's 32 random bytes.
     * @return The HMAC.
     */
    static byte[] proof(final AccessToken token, final int port, final byte[] challenge) {
        final ByteArrayOutputStream form = labeled(PROOF);
        form.write(port >>> 8);
        form.write(port);
        form.writeBytes(challenge);
        return token.mac(form.toByteArray());
    }

    /**
     * The SHA-256 of bytes.
     *
     * @param bytes The bytes.
     * @return Their digest.
     */
    static byte[] sha256(final byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static ByteArrayOutputStream labeled(final String label) {
        final ByteArrayOutputStream form = new ByteArrayOutputStream();
        form.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
        form.write(0);
        return form;
    }

    private static void field(final ByteArrayOutputStream form, final byte[] bytes) {
        form.write(bytes.length >>> 24);
        form.write(bytes.length >>> 16);
        form.write(bytes.length >>> 8);
        form.write(bytes.length);
        form.writeBytes(bytes);
    }
}
