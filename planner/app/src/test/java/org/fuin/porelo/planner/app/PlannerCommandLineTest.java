package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The limits line the planner takes first on its command line, before Spring or a work tool. */
class PlannerCommandLineTest {

    /** What the command line answered before the server would start. */
    private record Answer(OptionalInt code, String out, String err) {}

    private static Answer work(final String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final OptionalInt code =
                PlannerApplication.work(
                        args,
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Answer(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource({
        "issues, false",
        "stories, false",
        "stage, false",
        "check-pushes, false",
        "issues, true",
        "stories, true",
        "stage, true",
        "check-pushes, true"
    })
    void aRemovedCommandEndsBeforeTheServerStarts(final String command, final boolean withLimits) {
        // Harm: a script still calling a removed command starts a server and hangs.
        final Answer answer =
                withLimits ? work("--limits", "services=2.0", command) : work(command);

        assertThat(answer.code()).as("the removed command exits before Spring").hasValue(2);
        assertThat(answer.out()).as("no command or server output").isEmpty();
        assertThat(answer.err())
                .as("the removed command is named in the refusal")
                .contains(command, "no longer part of the planner");
    }

    @Test
    void aBadLineIsRefusedWith64AndWhatIsWrong() {
        // Harm: a planner started with a line it cannot read running with limits nobody chose.
        final Answer answer = work("--limits", "services=0.01", "--mock");

        assertThat(answer.code()).as("the exit code").hasValue(64);
        assertThat(answer.err())
                .as("the refusal")
                .startsWith("--limits: services 0.01 ")
                .contains("0.1 to 100");
    }

    @Test
    void theOptionWithoutItsLineIsRefusedWith64() {
        // Harm: a window that lost the line starting a planner at defaults it did not read.
        final Answer answer = work("--limits");

        assertThat(answer.code()).as("the exit code").hasValue(64);
        assertThat(answer.err()).as("the refusal").startsWith("--limits: ");
    }

    @Test
    void theOptionAfterTheModeIsRefusedWith64() {
        // Harm: a line given after the mode silently ignored, or read by Spring as a property.
        final Answer answer = work("--mock", "--limits", "services=2.0");

        assertThat(answer.code()).as("the exit code").hasValue(64);
        assertThat(answer.err())
                .as("the refusal")
                .isEqualTo("--limits: comes first, before the mode or command\n");
    }

    @Test
    void theOptionAfterAWorkCommandIsRefusedWith64() {
        // Harm: a build's check run at limits other than those its command line names.
        final Answer answer = work("check-commits", "HEAD", "--limits", "polling=2");

        assertThat(answer.code()).as("the exit code").hasValue(64);
        assertThat(answer.err())
                .as("the refusal")
                .isEqualTo("--limits: comes first, before the mode or command\n");
    }

    @Test
    void theOptionGivenTwiceIsRefusedWith64() {
        // Harm: two lines of which one is silently taken.
        final Answer answer =
                work("--limits", "services=2.0", "--mock", "--limits", "services=3.0");

        assertThat(answer.code()).as("the exit code").hasValue(64);
        assertThat(answer.err()).as("the refusal").isEqualTo("--limits: is given twice\n");
    }

    @Test
    void theOptionWithItsLineAfterAnEqualsSignIsRefusedWith64() {
        // Harm: a line in Spring's form passed on as a property, its factors never applied.
        final Answer answer = work("--limits=services=2.0", "--mock");

        assertThat(answer.code()).as("the exit code").hasValue(64);
        assertThat(answer.err())
                .as("the refusal")
                .isEqualTo("--limits: takes its line as the next argument, never after '='\n");
    }

    @Test
    void aWorkCommandAfterTheLineRuns() {
        // Harm: a build that hands its check the limits line starting a server instead.
        final Answer answer = work("--limits", "services=2.0,retries=1.5", "check-commits");

        assertThat(answer.code()).as("the check's own exit code, %s", answer.err()).hasValue(2);
        assertThat(answer.err()).as("the check's own words").startsWith("usage: check-commits ");
    }

    @Test
    void theServerArgumentsAfterTheLineStartThePlannerWithItsFactors() {
        // Harm: the factors lost between the command line and the planner's limits.
        assertThat(work("--limits", "services=2.0", "--mock").code())
                .as("no answer before the server starts")
                .isEmpty();
        final PlannerApplication.Launch launch =
                PlannerApplication.Launch.of("--limits", "services=2.0", "--mock");

        assertThat(launch.limits()).as("the factors").isEqualTo(LimitFactors.parse("services=2"));
        assertThat(launch.rest()).as("the server's arguments").containsExactly("--mock");
    }

    @Test
    void withoutTheOptionEveryFactorIsOne() {
        // Harm: a planner started by hand, without Porelo, refusing to start or running at
        // factors nobody set.
        final PlannerApplication.Launch launch = PlannerApplication.Launch.of("--mock");

        assertThat(launch.limits()).as("the factors").isEqualTo(LimitFactors.DEFAULT);
        assertThat(launch.rest()).as("the server's arguments").containsExactly("--mock");
    }
}
