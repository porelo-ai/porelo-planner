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
        return tokenOf(TOKEN);
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

    @Test
    void theFormsAreTheOnesTheAgentsToolServerSigns() throws Exception {
        // Harm: the agents' tool server signs by these same values; a field moved on either side
        // makes every agent call fail on the person's computer alone.
        final AccessToken token = tokenOf("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
        final byte[] nonce = from(100, 16);
        assertThat(CallSignature.base64(nonce)).as("the nonce").isEqualTo("ZGVmZ2hpamtsbW5vcHFycw");
        assertThat(
                        CallSignature.header(
                                1_764_000_000L,
                                nonce,
                                CallSignature.request(
                                        token,
                                        "POST",
                                        "/projects/p/moves?x=1",
                                        "127.0.0.1:41234",
                                        "application/json",
                                        "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                                        1_764_000_000L,
                                        nonce)))
                .as("the request's header")
                .isEqualTo(
                        "porelo-planner-request-1;t=1764000000;n=ZGVmZ2hpamtsbW5vcHFycw;"
                                + "s=mzk3aNeJvNxWu2bLGrFJnBzwo-0Hizqcanwo41mhO_c");
        assertThat(
                        CallSignature.answerHeader(
                                CallSignature.answer(
                                        token,
                                        nonce,
                                        200,
                                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8))))
                .as("the answer's header")
                .isEqualTo("porelo-planner-answer-1;s=C4oEI-9hOCKwI6pKOnvr0qt-xkx0KVZg0J0M6_yIcU8");
        assertThat(CallSignature.base64(CallSignature.proof(token, 41_234, from(200, 32))))
                .as("the proof")
                .isEqualTo("i_GGT6A769tJy81GkQoj2SAFmcJafS8Wu8ik66-BsOA");
    }

    private static byte[] from(final int first, final int bytes) {
        final byte[] counted = new byte[bytes];
        for (int at = 0; at < bytes; at++) {
            counted[at] = (byte) (first + at);
        }
        return counted;
    }

    private static AccessToken tokenOf(final String text) throws Exception {
        final Constructor<AccessToken> made =
                AccessToken.class.getDeclaredConstructor(byte[].class);
        made.setAccessible(true);
        return made.newInstance((Object) text.getBytes(StandardCharsets.US_ASCII));
    }
}
