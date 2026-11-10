package org.fuin.porelo.planner.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The secret a caller shows to be the planner's owner: 32 random bytes, made when the planner
 * starts.
 *
 * <p>Made at run time on purpose: a value made while the native image is built would be the same in
 * every copy of the executable.
 */
public final class AccessToken {

    private static final int BYTES = 32;

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
     * Says whether a caller showed this token, in a time that does not depend on how much of it
     * matched.
     *
     * @param given What the caller sent.
     * @return {@code true} when it is this token.
     */
    public boolean matches(final String given) {
        return MessageDigest.isEqual(value, given.getBytes(StandardCharsets.UTF_8));
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
