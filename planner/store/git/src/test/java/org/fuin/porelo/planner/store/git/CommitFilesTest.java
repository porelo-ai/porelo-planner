package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommitFilesTest {

    // Harm: planner.yml read past the commit's one listing, so its blob is not among those the
    // snapshot lists and its text is fetched alone, or a directory of that name taken for one.
    @Test
    void thePlannersOwnFileIsOneOfTheSnapshotsFilesAndNoDirectory() {
        assertThat(CommitFiles.PLANNING).as("listed with the commit").contains("planner.yml");
        assertThat(CommitFiles.file("planner.yml")).as("a file the snapshot answers for").isTrue();
        assertThat(CommitFiles.file("planner.yml/x")).as("nothing below it").isFalse();
        assertThat(CommitFiles.directory("planner.yml")).as("no directory").isFalse();
    }

    private static final String ONE = "1".repeat(40);

    private static final String TWO = "2".repeat(40);

    private static Map<String, Long> asked() {
        final Map<String, Long> asked = new LinkedHashMap<>();
        asked.put(ONE, 3L);
        asked.put(TWO, 2L);
        return asked;
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aBatchIsReadByItsHeadersByteForByte() {
        // A text cut at another place than its header names would show another file's bytes.
        final Map<String, byte[]> read =
                CommitFiles.blobs(
                        bytes(ONE + " blob 3\na\nb\n" + TWO + " blob 2\n\n\n\n"), asked());
        assertThat(read.get(ONE)).as("the first blob, its newlines kept").isEqualTo(bytes("a\nb"));
        assertThat(read.get(TWO)).as("the second blob").isEqualTo(bytes("\n\n"));
        assertThat(CommitFiles.printed(asked()))
                .as("the bytes the batch prints, for the output's bound")
                .isEqualTo(bytes(ONE + " blob 3\na\nb\n" + TWO + " blob 2\n\n\n\n").length);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1111111111111111111111111111111111111111 missing\n",
                "1111111111111111111111111111111111111111 tree 3\nabc\n",
                "1111111111111111111111111111111111111111 blob 4\nabcd\n",
                "1111111111111111111111111111111111111111 blob 3\nabc\n",
                "1111111111111111111111111111111111111111 blob 3\nabc\n"
                        + "2222222222222222222222222222222222222222 blob 2\nab\nmore"
            })
    void aBatchAnsweringOtherwiseThanAskedFails(final String out) {
        // A missing, other or cut object read as a file would put wrong text on the board.
        assertThatThrownBy(() -> CommitFiles.blobs(bytes(out), asked()))
                .as("the answer '%s'", out)
                .isInstanceOf(GitFailure.class)
                .hasMessageContaining("git cat-file answered");
    }
}
