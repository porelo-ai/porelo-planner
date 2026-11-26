package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A forge's own answer, cleaned so the person may read it as it stands. */
class ForgeAnswerTest {

    private static final String TOKEN = "s3cr3t-T0ken-of-the-call";

    @Test
    void theTokenNeverStaysInTheWordsNorAnyGitHubTokenByItsForm() {
        // Harm: the person's token kept in the outbox and shown on the board, copied with the rest.
        final ForgeAnswer answer =
                ForgeAnswer.of(
                        401,
                        "Bad credentials for " + TOKEN,
                        List.of("token " + TOKEN + " refused", "also ghp_ABCDEFGHIJKLMNOPQRSTUVWX"),
                        "C0DE:1234:ABCD",
                        List.of(TOKEN));

        assertThat(answer.text())
                .doesNotContain(TOKEN)
                .doesNotContain("ghp_")
                .isEqualTo(
                        "Bad credentials for [redacted]; token [redacted] refused; also"
                                + " [redacted]");
        assertThat(answer.status()).isEqualTo(401);
        assertThat(answer.requestId()).isEqualTo("C0DE:1234:ABCD");
    }

    @Test
    void controlCharactersAreEscapedAndTheWordsAreOtherwiseKeptAsTheyStand() {
        // Harm: a forge's words that move the cursor or end the line in the person's view or copy.
        final ForgeAnswer answer =
                ForgeAnswer.of(
                        422, "Validation\nFailed:\t\u001b[2J \"x\"", List.of(), null, List.of());

        assertThat(answer.text()).isEqualTo("Validation\\nFailed:\\t\\u001b[2J \"x\"");
    }

    @Test
    void theWordsAreBoundedAtWholeCharacters() {
        // Harm: a forge's answer of megabytes fills the outbox, or a cut splits a character.
        final ForgeAnswer answer =
                ForgeAnswer.of(500, "x" + "😀".repeat(2000), List.of(), null, List.of());

        final byte[] bytes = answer.text().getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isLessThanOrEqualTo(ForgeAnswer.MOST_BYTES);
        assertThat(answer.text()).startsWith("x😀").endsWith("😀…");
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(answer.text());
    }

    @Test
    void aRequestIdOrStatusNotOfItsFormIsLeftOutAndAForgedAnswerRefused() {
        // Harm: words of any form shown as the forge's request id, which the person copies to it.
        final ForgeAnswer answer =
                ForgeAnswer.of(1000, "", List.of(), "id with blanks\n", List.of());

        assertThat(answer.requestId()).isNull();
        assertThat(answer.status()).isZero();
        assertThatThrownBy(() -> new ForgeAnswer(200, "a\u0007", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForgeAnswer(200, "", "a b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTokenGluedToAWordOrAnUnderscoreIsTakenOutToo() {
        // Harm: a token GitHub wrote right after a word or an underscore kept in the outbox and
        // shown on the board.
        final String token = "ghp_ABCDEFGHIJKLMNOPQRSTUVWX";

        assertThat(ForgeAnswer.of(401, "Bearer_" + token, List.of(), null, List.of()).text())
                .isEqualTo("Bearer_[redacted]");
        assertThat(ForgeAnswer.of(401, "x" + token, List.of(), null, List.of()).text())
                .isEqualTo("x[redacted]");
    }

    @Test
    void formatCharactersSeparatorsAndLoneSurrogatesAreEscaped() {
        // Harm: a bidi override or a zero-width character turns or hides what the person reads,
        // a separator ends the line in their view, and a lone surrogate makes the outbox's write
        // throw, so the save is lost.
        final ForgeAnswer answer =
                ForgeAnswer.of(
                        422,
                        "a\u202eb\u200bc\u2028d\u2029e\ud800f\udfff",
                        List.of(),
                        null,
                        List.of());

        assertThat(answer.text()).isEqualTo("a\\u202eb\\u200bc\\u2028d\\u2029e\\ud800f\\udfff");
        assertThatThrownBy(() -> new ForgeAnswer(200, "a\u202e", null))
                .as("an unescaped format character")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void escapedWordsStayWithinTheBound() {
        // Harm: escaping turns 4096 bytes of control characters or lone surrogates into six
        // times as many, past what the outbox and the board take.
        for (final String one : List.of("\u0007", "\ud800")) {
            final ForgeAnswer answer =
                    ForgeAnswer.of(
                            500, one.repeat(ForgeAnswer.MOST_BYTES), List.of(), null, List.of());

            assertThat(answer.text().getBytes(StandardCharsets.UTF_8).length)
                    .as(one)
                    .isLessThanOrEqualTo(ForgeAnswer.MOST_BYTES);
        }
    }

    @Test
    void aTokenAtTheBoundLeavesNoTokenForm() {
        // Harm: the bound cuts a token short of the form the scrub knows, so its first part stays
        // in the words.
        final ForgeAnswer answer =
                ForgeAnswer.of(
                        401,
                        "x".repeat(ForgeAnswer.MOST_BYTES - 16) + "ghp_" + "A".repeat(30),
                        List.of(),
                        null,
                        List.of());

        assertThat(answer.text()).doesNotContain("ghp_");
    }
}
