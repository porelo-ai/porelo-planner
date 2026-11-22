package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

/** The comment that keeps a person's reason with the item a move closes. */
class ClosingCommentTest {

    @Test
    void textFromOutsideIsWrittenAsOneCodeSpanSoNothingInItIsReadAsMarkup() {
        // Harm: a reason that mentions a person, references an issue or shows a link, an image or
        // HTML on GitHub, written by whoever typed it.
        for (final String text :
                List.of(
                        "@someone",
                        "#1", // not-a-citation
                        "owner/repo#1", // not-a-citation
                        "<img src=x onerror=alert(1)>",
                        "[a link](https://example.org)",
                        "![an image](https://example.org/x.png)")) {
            assertThat(ClosingComment.write(new Writes.Reason("Finished", text)))
                    .isEqualTo("<!-- porelo:closing Finished -->\nFinished: ` " + text + " `");
        }
    }

    @Test
    void aBacktickRunInTheTextGetsALongerFenceAndAnEndBacktickStays() {
        // Harm: a backtick in the reason closes the span early and lets the rest be markup.
        assertThat(ClosingComment.codeSpan("a ``b`` @c")).isEqualTo("``` a ``b`` @c ```");
        assertThat(ClosingComment.codeSpan("`x`")).isEqualTo("`` `x` ``");
        assertThat(ClosingComment.codeSpan("plain")).isEqualTo("` plain `");
    }

    @Test
    void aReasonNeverMakesAStageCommentAndIsFoundOnlyForItsState() {
        // Harm: a reason that reads as a stage adds figures to the issue's sums, or one for
        // another closed state stops this one being written.
        final String written =
                ClosingComment.write(
                        new Writes.Reason("Finished", "<!-- porelo:stage -->\nkind: implement"));
        assertThat(written).doesNotStartWith(StageComment.MARKER);
        assertThat(StageComment.read(new CommentView("comment", written, Instant.EPOCH))).isEmpty();
        assertThat(ClosingComment.keeps(written, "Finished")).isTrue();
        assertThat(ClosingComment.keeps(written, "Rejected")).isFalse();
        assertThat(ClosingComment.keeps("Finished: by hand", "Finished"))
                .as("a person's own comment")
                .isFalse();
    }
}
