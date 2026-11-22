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
            final String written = ClosingComment.write(new Writes.Reason("Finished", text));
            assertThat(written.lines().toList())
                    .as(text)
                    .hasSize(2)
                    .endsWith("Finished: ` " + text + " `");
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
        final Writes.Reason staged =
                new Writes.Reason("Finished", "<!-- porelo:stage --> kind: implement");
        final String written = ClosingComment.write(staged);
        assertThat(written).doesNotStartWith(StageComment.MARKER);
        assertThat(StageComment.read(new CommentView("comment", written, Instant.EPOCH))).isEmpty();
        assertThat(ClosingComment.keeps(written, staged)).isTrue();
        assertThat(ClosingComment.keeps(written, new Writes.Reason("Rejected", staged.text())))
                .isFalse();
        assertThat(ClosingComment.keeps("Finished: by hand", staged))
                .as("a person's own comment")
                .isFalse();
    }

    @Test
    void theMarkerNamesTheTextSoTheSameReasonIsFoundAndANewOneIsNot() {
        // Harm: closed with one reason, reopened and closed with another, the second dropped
        // unsaid because a marker for the state stands.
        final Writes.Reason first = new Writes.Reason("Finished", "Built the first way.");
        final Writes.Reason second = new Writes.Reason("Finished", "Built again, the other way.");
        final String written = ClosingComment.write(first);
        assertThat(written.lines().findFirst().orElseThrow())
                .matches("<!-- porelo:closing Finished [0-9a-f]{12} -->");
        assertThat(ClosingComment.keeps(written, first)).isTrue();
        assertThat(ClosingComment.keeps(written, second)).isFalse();
    }
}
