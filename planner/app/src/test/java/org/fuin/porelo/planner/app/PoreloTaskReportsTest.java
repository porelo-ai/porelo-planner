package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.planner.app.PoreloTaskRecords.Result;
import org.fuin.porelo.planner.app.PoreloTaskRecords.Runner;
import org.fuin.porelo.planner.core.TaskRecords.Unanswered;
import org.fuin.porelo.planner.core.TaskReports;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Porelo's reports of a task's name, read from its executable's answer and checked field by field.
 */
class PoreloTaskReportsTest {

    private static final Path PORELO = Path.of("/opt/porelo/bin/porelo");

    private static final String TASK = "porelo-uc-work";

    private static final String SECRET = "ghp_SECRET123";

    private static final String AT = "2026-11-25T07:43:09.209888029Z";

    private Result answer = answer("{\"reports\":[],\"last\":0,\"more\":false}");

    private final List<List<String>> commands = new ArrayList<>();

    private final List<Map<String, String>> environments = new ArrayList<>();

    private final Runner fake =
            (command, environment) -> {
                commands.add(command);
                environments.add(environment);
                return answer;
            };

    private static Result answer(final String text) {
        return new Result(0, text.getBytes(StandardCharsets.UTF_8), false, "");
    }

    private static byte[] recorded(final String name) throws IOException {
        try (InputStream in = PoreloTaskReportsTest.class.getResourceAsStream("/porelo/" + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }

    private PoreloTaskReports reports() {
        return new PoreloTaskReports(
                PORELO,
                fake,
                Map.of("PATH", "/usr/bin", "HOME", "/home/user", "GITHUB_TOKEN", SECRET),
                LimitFactors.parse("services=2.0"));
    }

    private String refusal(final long after) {
        final Unanswered refused =
                catchThrowableOfType(Unanswered.class, () -> reports().after(TASK, after));
        assertThat(refused).as("the answer refused").isNotNull();
        return refused.getMessage();
    }

    // Harm: a page as Porelo writes it, a text at its bound of tag characters among it, refused,
    // so no task's report is ever taken.
    @Test
    void recordedAnswersAreReadWithEveryValue() throws Exception {
        answer = new Result(0, recorded("task-reports-more.json"), false, "");

        final TaskReports.Page page = reports().after(TASK, 0);

        assertThat(page.last()).isEqualTo(3);
        assertThat(page.more()).isTrue();
        assertThat(page.reports())
                .singleElement()
                .satisfies(
                        report -> {
                            assertThat(report.seq()).isEqualTo(1);
                            assertThat(report.kind()).isEqualTo("status");
                            assertThat(report.text().codePointCount(0, report.text().length()))
                                    .isEqualTo(65536);
                        });

        answer = new Result(0, recorded("task-reports-empty.json"), false, "");
        assertThat(reports().after(TASK, 0)).isEqualTo(new TaskReports.Page(List.of(), 0, false));
    }

    // Harm: a secret of the planner's environment, or a shell, reaches Porelo; or Porelo answers
    // from another number than the mark.
    @Test
    void poreloIsAskedWithEachArgumentItsOwnAndOnlyThePassedEnvironment() {
        answer = answer("{\"reports\":[],\"last\":41,\"more\":false}");
        reports().after(TASK, 41);

        assertThat(commands)
                .containsExactly(
                        List.of(
                                PORELO.toString(),
                                LimitFactors.OPTION,
                                LimitFactors.parse("services=2.0").line(),
                                "task",
                                "reports",
                                TASK,
                                "--after",
                                "41",
                                "--json"));
        assertThat(environments).containsExactly(Map.of("PATH", "/usr/bin", "HOME", "/home/user"));
    }

    // Harm: a name read as an option by Porelo, or a number it refuses, run at all.
    @Test
    void aNameThatIsNoTaskOrANumberBelowZeroIsRefusedBeforePoreloRuns() {
        for (final String name : List.of("--json", "", "a b", "../x")) {
            assertThat(catchThrowable(() -> reports().after(name, 0)))
                    .as("the name %s", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(catchThrowable(() -> reports().after(TASK, -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).as("Porelo not run").isEmpty();
    }

    // Harm: a failed run read as no reports, or Porelo's error output, which may hold a token,
    // shown.
    @Test
    void eachExitCodeOtherThanZeroFailsWithoutItsErrorOutput() {
        for (final int code : List.of(74, 64, 1)) {
            answer = new Result(code, new byte[0], false, SECRET);
            assertThat(refusal(0)).as("exit %s", code).doesNotContain(SECRET);
        }
        answer = new Result(0, "{}".getBytes(StandardCharsets.UTF_8), true, "");
        assertThat(refusal(0)).as("cut off").contains("cut off");
    }

    // Harm: an answer that skips, repeats or forges reports taken, so an idea is saved twice or
    // lost; a refusal quotes a task's text.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"reports\":[],\"last\":0,\"more\":false,\"more\":true}",
                "{\"reports\":[],\"last\":0}",
                "{\"reports\":[],\"last\":0,\"more\":false,\"secret\":1}",
                "{\"reports\":[{\"seq\":1,\"kind\":\"idea\",\"text\":\"secret\",\"at\":\""
                        + AT
                        + "\",\"x\":1}],\"last\":1,\"more\":false}",
                "{\"reports\":[{\"seq\":1,\"kind\":\"idea\",\"text\":\"secret\"}],\"last\":1,"
                        + "\"more\":false}",
                "{\"reports\":[{\"seq\":2,\"kind\":\"idea\",\"text\":\"secret\",\"at\":\""
                        + AT
                        + "\"},{\"seq\":1,\"kind\":\"idea\",\"text\":\"secret\",\"at\":\""
                        + AT
                        + "\"}],\"last\":2,\"more\":false}",
                "{\"reports\":[{\"seq\":3,\"kind\":\"idea\",\"text\":\"secret\",\"at\":\""
                        + AT
                        + "\"}],\"last\":2,\"more\":false}",
                "{\"reports\":[],\"last\":4,\"more\":true}",
                "{\"reports\":[],\"last\":5,\"more\":false}",
                "{\"reports\":[{\"seq\":1,\"kind\":\"idea\",\"text\":\"secret\",\"at\":"
                        + "\"2026-11-25T07:43:09+01:00\"}],\"last\":1,\"more\":false}",
                "{\"reports\":[{\"seq\":1,\"kind\":\"idea\",\"text\":\"secret\\ud800\",\"at\":\""
                        + AT
                        + "\"}],\"last\":1,\"more\":false}",
                "{\"reports\":[{\"seq\":1,\"kind\":\"idea\",\"text\":\"secret\\u202e\",\"at\":\""
                        + AT
                        + "\"}],\"last\":1,\"more\":false}",
                "{\"reports\":[{\"seq\":1.5,\"kind\":\"idea\",\"text\":\"secret\",\"at\":\""
                        + AT
                        + "\"}],\"last\":1,\"more\":false}",
                "{\"reports\":[{\"seq\":\"1\",\"kind\":\"idea\",\"text\":\"secret\",\"at\":\""
                        + AT
                        + "\"}],\"last\":1,\"more\":false}",
                "{\"reports\":[],\"last\":-1,\"more\":false}",
                "{\"reports\":[],\"last\":99999999999999999999,\"more\":false}",
                "{\"reports\":[],\"last\":0,\"more\":\"false\"}",
                "{\"reports\":{},\"last\":0,\"more\":false}",
                "{\"reports\":[],\"last\":0,\"more\":false} {}",
                "[\"secret\"]",
                "secret"
            })
    void anAnswerThatBreaksTheContractFailsWithoutQuotingIt(final String text) {
        answer = answer(text);

        assertThat(refusal(0)).doesNotContain("secret");
    }

    // Harm: Porelo answering from before the mark makes the planner take a report twice.
    @Test
    void aFirstReportNotAfterTheNumberAskedFails() {
        answer =
                answer(
                        "{\"reports\":[{\"seq\":5,\"kind\":\"status\",\"text\":\"t\",\"at\":\""
                                + AT
                                + "\"}],\"last\":5,\"more\":false}");

        assertThat(refusal(5)).contains("reports[0].seq");
        assertThat(reports().after(TASK, 4).through()).isEqualTo(5);
    }
}
