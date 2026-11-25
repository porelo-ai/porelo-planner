package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A task's report, outside input, checked as Porelo keeps it and closer still. */
class TaskReportTest {

    private static final String AT = "2026-11-25T07:10:36.583604725Z";

    // Harm: a hidden or reordering character in a kind decides what the planner does with a
    // report while the person sees another kind, or breaks a log line.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "id​ea",
                "idea ",
                "idea ",
                "idea\n",
                "id‮ea",
                "󠁁",
                "id\uD800ea",
                "x123456789x123456789x123456789x123456789x123456789x123456789x1234"
            })
    void aKindThatIsNoPlainLineIsRefused(final String kind) {
        assertThat(catchThrowable(() -> new TaskReport(1, kind, "text", AT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("kind ");
    }

    // Harm: a bidi control or a line separator in a report's text reaches a story's text or a
    // log, where it reorders or hides what the person reads.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "a b",
                "a b",
                "a‪b",
                "a‮b",
                "a⁦b",
                "a⁩b",
                "a\tb",
                "a\u0000b",
                "a\u0085b",
                "a\uDC00b"
            })
    void aTextWithAControlASeparatorOrABidiControlIsRefused(final String text) {
        assertThat(catchThrowable(() -> new TaskReport(1, "idea", text, AT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("text ");
    }

    // Harm: a sound idea of several lines, or one with characters Porelo keeps, refused.
    @Test
    void aTextKeepsLineBreaksAndOtherFormatCharacters() {
        final String text = "{\n\"a\": 1\r\n}󠁁​";

        assertThat(new TaskReport(3, "idea", text, AT).text()).isEqualTo(text);
        assertThat(new TaskReport(1, "x".repeat(64), "y".repeat(TaskReport.MOST_TEXT), AT))
                .as("both at their bound")
                .isNotNull();
    }

    // Harm: a report numbered 0, a text past the bound or a time Porelo never writes taken.
    @Test
    void aNumberBelowOneATextTooLongAndATimeNotInUtcAreRefused() {
        assertThat(catchThrowable(() -> new TaskReport(0, "idea", "t", AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        catchThrowable(
                                () ->
                                        new TaskReport(
                                                1,
                                                "idea",
                                                "y".repeat(TaskReport.MOST_TEXT + 1),
                                                AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        catchThrowable(
                                () -> new TaskReport(1, "idea", "t", "2026-11-25T07:10:36+01:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new TaskReport(1, "idea", "t", "yesterday")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Harm: a page that contradicts itself makes the planner skip reports or read forever.
    @Test
    void aPageThatContradictsItselfIsRefused() {
        final TaskReport one = new TaskReport(1, "status", "a", AT);
        final TaskReport two = new TaskReport(2, "status", "b", AT);

        assertThat(new TaskReports.Page(List.of(one, two), 2, false).through()).isEqualTo(2);
        assertThat(new TaskReports.Page(List.of(), 0, false).reports()).isEmpty();
        assertThat(new TaskReports.Page(List.of(one), 2, true).through()).isEqualTo(1);
        for (final Runnable page :
                List.<Runnable>of(
                        () -> new TaskReports.Page(List.of(), 5, true),
                        () -> new TaskReports.Page(List.of(two, one), 2, false),
                        () -> new TaskReports.Page(List.of(one, one), 1, false),
                        () -> new TaskReports.Page(List.of(two), 1, false),
                        () -> new TaskReports.Page(List.of(two), 2, true),
                        () -> new TaskReports.Page(List.of(one), 2, false),
                        () -> new TaskReports.Page(List.of(), -1, false))) {
            assertThat(catchThrowable(page::run)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
