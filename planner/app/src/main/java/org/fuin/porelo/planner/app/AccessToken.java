package org.fuin.porelo.planner.app;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The secret a caller proves it holds, never sends, to be the planner's owner: 32 random bytes,
 * made when the planner starts.
 *
 * <p>Made at run time on purpose: a value made while the native image is built would be the same in
 * every copy of the executable.
 */
public final class AccessToken {

    private static final int BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] value;

    private AccessToken(final byte[] value) {
        this.value = value;
    }

    /**
     * Makes a new token.
     *
     * @return A token no other start has.
     */
    public static AccessToken random() {
        final byte[] bytes = new byte[BYTES];
        new SecureRandom().nextBytes(bytes);
        return new AccessToken(
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(bytes)
                        .getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The HMAC-SHA256 of a message under this token's UTF-8 bytes, so a caller shows it holds the
     * token without sending it.
     *
     * @param message The bytes to sign.
     * @return The 32 bytes of the HMAC.
     */
    byte[] mac(final byte[] message) {
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(value, ALGORITHM));
            return mac.doFinal(message);
        } catch (final GeneralSecurityException e) {
            // Every Java runtime has HmacSHA256; without it no call can be checked.
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /**
     * The token as the address file writes it, for the owner alone.
     *
     * @return The token's text.
     */
    String text() {
        return new String(value, StandardCharsets.US_ASCII);
    }

    /** Never the token itself, so a log line or an error cannot show it. */
    @Override
    public String toString() {
        return "AccessToken[hidden]";
    }
}
