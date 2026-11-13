package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The planner's signed forms against fixed values computed apart from both the planner and the
 * screen; the screen's tests hold its own forms to the same values.
 */
class CallSignatureTest {

    private static final String TOKEN = "tOkEn-0123456789_abcdefghijklmnopqrstuvwxyz";

    private static byte[] count(final int bytes) {
        final byte[] counted = new byte[bytes];
        for (int at = 0; at < bytes; at++) {
            counted[at] = (byte) at;
        }
        return counted;
    }

    private static AccessToken token() throws Exception {
        final Constructor<AccessToken> made =
                AccessToken.class.getDeclaredConstructor(byte[].class);
        made.setAccessible(true);
        return made.newInstance((Object) TOKEN.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void theRequestAnswerAndProofFormsAreTheOnesTheWindowSigns() throws Exception {
        // Harm: a planner and a window that sign differently refuse each other, and a form that
        // drifts on one side alone is found only on the person's computer.
        final AccessToken token = token();
        assertThat(
                        CallSignature.base64(
                                CallSignature.request(
                                        token,
                                        "POST",
                                        "/projects/demo/ideas?x=1",
                                        "127.0.0.1:4711",
                                        "application/json; charset=utf-8",
                                        "{\"idea\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                                        1_800_000_000L,
                                        count(16))))
                .as("the request's HMAC")
                .isEqualTo("4Vur7C18Dz3uxlbrMbxTrL7VIRRQBCRjx-CBrJxuzaE");
        assertThat(
                        CallSignature.base64(
                                CallSignature.answer(
                                        token,
                                        count(16),
                                        201,
                                        "{\"id\":\"ST1\"}".getBytes(StandardCharsets.UTF_8))))
                .as("the answer's HMAC")
                .isEqualTo("nP2ZBcDt4afnTDrHz8reZCBadY-KOUD1Lkjil65U2Cc");
        assertThat(CallSignature.base64(CallSignature.answer(token, count(16), 200, null)))
                .as("the stream opening's HMAC")
                .isEqualTo("w3qfSAGgHu79JuGttleQ9q8npIjDKswo5wYO4D-LIZw");
        assertThat(CallSignature.base64(CallSignature.proof(token, 4711, count(32))))
                .as("the proof")
                .isEqualTo("oODykTsTVjhY8BiPkAiMsEQ9--YGs0x_nHExTNHyrXk");
    }
}
