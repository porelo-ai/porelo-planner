package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LogTextTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Title\nINFO forged line",
                "Title\r\nINFO forged line",
                "Title\rINFO forged line",
                "Title INFO forged line",
                "Title\u0085INFO forged line",
                "Title\u001b[2KINFO forged line",
                "Title‮enil degrof"
            })
    void aValueWithALineBreakOrAControlCharacterStaysOneLine(final String title) {
        // A title from a repository could forge a line of its own in the person's log.
        final String line = LogText.of(title);
        assertThat(line.lines().count()).as("the lines of %s", line).isEqualTo(1);
        assertThat(line.codePoints().filter(LogTextTest::controlOrFormat))
                .as("control and format characters left in %s", line)
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {LogText.MOST + 1, 100_000})
    void aLongValueIsCutToTheBound(final int length) {
        // A loop of long values would fill the log's bound with one entry's text.
        assertThat(LogText.of("x".repeat(length)).length())
                .as("the length of a value of %d characters", length)
                .isLessThanOrEqualTo(LogText.MOST + 3);
    }

    private static boolean controlOrFormat(final int point) {
        return Character.getType(point) == Character.CONTROL
                || Character.getType(point) == Character.FORMAT
                || Character.getType(point) == Character.LINE_SEPARATOR;
    }
}
