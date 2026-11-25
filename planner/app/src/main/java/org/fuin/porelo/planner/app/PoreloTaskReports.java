package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.app.PoreloTaskRecords.ProcessRunner;
import org.fuin.porelo.planner.app.PoreloTaskRecords.Result;
import org.fuin.porelo.planner.app.PoreloTaskRecords.Runner;
import org.fuin.porelo.planner.core.TaskRecords.Unanswered;
import org.fuin.porelo.planner.core.TaskReport;
import org.fuin.porelo.planner.core.TaskReports;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.wire.LimitFactors;

/**
 * The reports Porelo keeps of a task's name, read by running its executable's {@code task reports}
 * command and checking every field of its answer before any of it is used; a refusal names the
 * field, never a value, and never repeats Porelo's error output.
 */
public final class PoreloTaskReports implements TaskReports {

    /** How long one run of Porelo may take, before the factor {@code services}. */
    private static final Duration LIMIT = Duration.ofSeconds(60);

    private static final Set<String> PAGE = Set.of("reports", "last", "more");

    private static final Set<String> REPORT = Set.of("seq", "kind", "text", "at");

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Path executable;

    private final Runner runner;

    private final Map<String, String> environment;

    private final LimitFactors limits;

    /**
     * Runs Porelo's executable as a process of its own, handing it the factors and giving it as
     * long as the factor {@code services} says.
     *
     * @param executable the absolute path of Porelo's executable, checked
     * @param limits the factors
     */
    public PoreloTaskReports(final Path executable, final LimitFactors limits) {
        this(
                executable,
                new ProcessRunner(limits.services(LIMIT), "task reports"),
                System.getenv(),
                limits);
    }

    PoreloTaskReports(
            final Path executable,
            final Runner runner,
            final Map<String, String> source,
            final LimitFactors limits) {
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Porelo's executable must be given by an absolute path");
        }
        this.executable = executable;
        this.runner = runner;
        this.environment = PoreloTaskRecords.passed(source);
        this.limits = limits;
    }

    @Override
    public Page after(final String container, final long after) {
        // Checked before Porelo runs: a name is no option, and no number below 0 is sent.
        Ids.task(container);
        if (after < 0) {
            throw new IllegalArgumentException("The last number taken is 0 or more");
        }
        final Result result =
                runner.run(
                        List.of(
                                executable.toString(),
                                LimitFactors.OPTION,
                                limits.line(),
                                "task",
                                "reports",
                                container,
                                "--after",
                                Long.toString(after),
                                "--json"),
                        environment);
        if (result.code() == 74) {
            throw new Unanswered("Porelo cannot read the reports of " + container);
        }
        if (result.code() == 64) {
            throw new Unanswered("Porelo refused the name " + container + " or the number");
        }
        if (result.code() != 0) {
            // Its error output may echo a command or a token, so none of it is shown.
            throw new Unanswered("Porelo's reports failed with exit code " + result.code());
        }
        if (result.cutOff()) {
            throw new Unanswered(
                    "Porelo's reports were cut off after "
                            + PoreloTaskRecords.ANSWER_LIMIT
                            + " bytes");
        }
        final Page page = parse(result.out());
        if (!page.reports().isEmpty() && page.reports().getFirst().seq() <= after) {
            throw new Unanswered("Porelo's reports field reports[0].seq is not after " + after);
        }
        if (page.reports().isEmpty() && page.last() != after) {
            // No report after the number asked, yet Porelo holds others: some were left out.
            throw new Unanswered("Porelo's reports field last is not " + after + " for no report");
        }
        return page;
    }

    private static Page parse(final byte[] answer) {
        final JsonNode root;
        try {
            root = JSON.readTree(answer);
        } catch (final IOException ex) {
            // The parser's message quotes the answer, which may hold a task's text.
            throw new Unanswered("Porelo's reports are no single JSON document");
        }
        if (root == null || !root.isObject()) {
            throw new Unanswered("Porelo's reports are no JSON object");
        }
        exactly(root, PAGE, "");
        final long last = count(root.get("last"), "last");
        final JsonNode more = root.get("more");
        if (!more.isBoolean()) {
            throw refused("more", "is no boolean");
        }
        final JsonNode items = root.get("reports");
        if (!items.isArray()) {
            throw refused("reports", "is no array");
        }
        final List<TaskReport> reports = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            reports.add(report(items.get(i), "reports[" + i + "]"));
        }
        try {
            return new Page(reports, last, more.booleanValue());
        } catch (final IllegalArgumentException ex) {
            throw new Unanswered("Porelo's reports contradict themselves: " + ex.getMessage());
        }
    }

    private static TaskReport report(final JsonNode item, final String path) {
        if (!item.isObject()) {
            throw refused(path, "is no object");
        }
        exactly(item, REPORT, path + ".");
        final long seq = count(item.get("seq"), path + ".seq");
        for (final String name : List.of("kind", "text", "at")) {
            if (!item.get(name).isTextual()) {
                throw refused(path + "." + name, "is no string");
            }
        }
        try {
            return new TaskReport(
                    seq,
                    item.get("kind").textValue(),
                    item.get("text").textValue(),
                    item.get("at").textValue());
        } catch (final IllegalArgumentException ex) {
            // The report's own check names its field, never its value.
            throw refused(path, "fails its check: " + ex.getMessage());
        }
    }

    private static void exactly(final JsonNode object, final Set<String> names, final String at) {
        final Set<String> found = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(found::add);
        for (final String name : names) {
            if (!found.contains(name)) {
                throw refused(at + name, "is missing");
            }
        }
        if (!names.containsAll(found)) {
            throw refused(
                    at.isEmpty() ? "answer" : at.substring(0, at.length() - 1),
                    "holds a field the contract does not name");
        }
    }

    private static long count(final JsonNode value, final String path) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw refused(path, "is no whole number of at least zero");
        }
        return value.longValue();
    }

    private static Unanswered refused(final String path, final String what) {
        return new Unanswered("Porelo's reports field " + path + " " + what);
    }
}
