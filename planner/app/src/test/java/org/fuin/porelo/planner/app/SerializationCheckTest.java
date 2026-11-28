package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * Guards the executable's check of its own image: it must write and read back every type the
 * planner binds, so the native executable's answer can be held to the JVM's.
 */
class SerializationCheckTest {

    /** Counts include optional and nullable fields, so a sample that leaves one out fails. */
    private static final Map<Class<?>, Integer> AGENT_ANSWERS =
            Map.ofEntries(
                    Map.entry(org.fuin.porelo.planner.app.answers.Outline.class, 4),
                    Map.entry(org.fuin.porelo.planner.app.answers.StoryList.class, 4),
                    Map.entry(org.fuin.porelo.planner.app.answers.IssueList.class, 3),
                    Map.entry(org.fuin.porelo.planner.app.answers.OutlineStory.class, 5),
                    Map.entry(org.fuin.porelo.planner.app.answers.OutlineIssue.class, 5),
                    Map.entry(org.fuin.porelo.planner.app.answers.IssueParts.class, 14),
                    Map.entry(org.fuin.porelo.planner.app.answers.TextParts.class, 8),
                    Map.entry(org.fuin.porelo.planner.app.answers.Breaches.class, 2),
                    Map.entry(org.fuin.porelo.planner.app.answers.Breach.class, 4),
                    Map.entry(org.fuin.porelo.planner.app.answers.SearchAnswer.class, 4),
                    Map.entry(org.fuin.porelo.planner.app.answers.SearchMatch.class, 5),
                    Map.entry(org.fuin.porelo.planner.app.answers.BatchSaved.class, 2),
                    Map.entry(org.fuin.porelo.planner.app.answers.ItemResult.class, 6),
                    Map.entry(org.fuin.porelo.planner.app.answers.Problem.class, 5),
                    Map.entry(org.fuin.porelo.planner.app.answers.ProblemParameters.class, 31));

    @Test
    void everyBoundTypeIsWrittenAndReadBackAsItWas() {
        // Harm: a type the check leaves out, so a native image that cannot bind it passes.
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();

        final int exit =
                SerializationCheck.run(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));

        final List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(err.toString(StandardCharsets.UTF_8)).as("no type failed").isEmpty();
        assertThat(exit).as("every type read back").isZero();
        assertThat(lines.stream().map(line -> line.substring(0, line.indexOf(' '))).toList())
                .as("one line per JSON type, then the copy")
                .containsExactlyElementsOf(
                        java.util.stream.Stream.concat(
                                        java.util.stream.Stream.concat(
                                                BoundTypes.GITHUB.stream(),
                                                BoundTypes.OWN.stream()),
                                        java.util.stream.Stream.of(
                                                org.fuin.porelo.planner.store.cache.BoardCopy
                                                        .class))
                                .map(Class::getSimpleName)
                                .toList());
        assertThat(lines.getLast())
                .as("the copy read back holds every record Fory registers")
                .isEqualTo("BoardCopy " + BoundTypes.COPY.size() + " records");
        AGENT_ANSWERS.forEach(
                (type, count) ->
                        assertThat(lines)
                                .as("the check reads back every field of %s", type.getSimpleName())
                                .contains(type.getSimpleName() + " " + count + " values"));
    }

    @Test
    void everyAgentAnswerAndItsPartsAreBoundWithEveryFieldFilled() {
        // Harm: optional fields vanish from a native answer while an empty sample still passes.
        final RuntimeHints hints = new RuntimeHints();
        new BoundTypes.Hints().registerHints(hints, null);

        assertThat(BoundTypes.OWN)
                .as("each answer and its parts have their own check line")
                .containsAll(AGENT_ANSWERS.keySet());
        for (final Map.Entry<Class<?>, Integer> answer : AGENT_ANSWERS.entrySet()) {
            final Class<?> type = answer.getKey();
            assertThat(RuntimeHintsPredicates.reflection().onType(type).test(hints))
                    .as("%s kept for binding", type.getSimpleName())
                    .isTrue();
            assertThat(RuntimeHintsPredicates.reflection().onType(type.arrayType()).test(hints))
                    .as("%s kept as a list element", type.getSimpleName())
                    .isTrue();
            final tools.jackson.databind.JsonNode filled =
                    WorkRecord.JSON.valueToTree(SerializationCheck.sample(type));
            assertThat(filled.size())
                    .as("every field of %s is written", type.getSimpleName())
                    .isEqualTo(answer.getValue());
            for (final java.lang.reflect.RecordComponent field : type.getRecordComponents()) {
                assertThat(filled.path(field.getName()).isMissingNode())
                        .as("%s.%s is written", type.getSimpleName(), field.getName())
                        .isFalse();
                assertThat(filled.path(field.getName()).isNull())
                        .as("%s.%s holds a value", type.getSimpleName(), field.getName())
                        .isFalse();
            }
        }
    }

    @Test
    void aBatchRefusalReadsBackTheNewProblemParameters() {
        // Harm: a batch hides the text fault or new item's key that a caller needs to repair it.
        final Class<?> type = org.fuin.porelo.planner.app.answers.BatchSaved.class;
        final Object sample = SerializationCheck.sample(type);
        final tools.jackson.databind.json.JsonMapper json =
                tools.jackson.databind.json.JsonMapper.builder().build();
        final String written = json.writerFor(type).writeValueAsString(sample);
        final Object read = json.readerFor(type).readValue(written);
        final tools.jackson.databind.JsonNode parameters =
                json.valueToTree(read).at("/results/0/refusal/parameters");

        assertThat(read).as("the batch including its refusal reads back").isEqualTo(sample);
        for (final String field : List.of("part", "fault", "found", "key")) {
            assertThat(parameters.path(field).asString())
                    .as("the batch refusal keeps %s", field)
                    .isNotEmpty();
        }
    }

    @Test
    void theWorkRecordReadBackHoldsAKeptPlaceWithItsAnswerAndTimes() {
        // Harm: a native image that cannot read back an issue's kept place, its answer's points
        // or their times, which a filled sample leaves empty, so a planner after a restart cannot
        // open its work record.
        final Object sample = SerializationCheck.sample(WorkRecord.Document.class);
        final WorkRecord.Document record =
                WorkRecord.JSON.readValue(
                        WorkRecord.JSON.writeValueAsString(sample), WorkRecord.Document.class);

        assertThat(record).as("the record the check writes reads back as it was").isEqualTo(sample);
        assertThat(
                        record.runs().values().stream()
                                .flatMap(run -> run.places().values().stream())
                                .filter(
                                        place ->
                                                place.place().gatePush() != null
                                                        && place.last() != null
                                                        && !place.last().points().isEmpty())
                                .count())
                .as("one place at a gate push, with an answer holding a point and its time")
                .isEqualTo(1);
        assertThat(
                        record.runs().values().stream()
                                .flatMap(run -> run.places().values().stream())
                                .filter(
                                        place ->
                                                place.last() != null
                                                        && "main".equals(place.last().mergedHead()))
                                .count())
                .as("one place whose push was forwarded, naming the branch its commit landed on")
                .isEqualTo(1);
        assertThat(
                        record.runs().values().stream()
                                .flatMap(run -> run.places().values().stream())
                                .filter(place -> place.again() != null)
                                .count())
                .as("one place a run started by hand works at, with its start")
                .isEqualTo(1);
    }

    @Test
    void theWorkRecordReadBackHoldsAReworkAReadOfferedAtItsPlaceAndItsStart() throws Exception {
        // Harm: a native image that cannot read back the author's role and place of a rework a
        // read offered, or which offers a read made, so a planner after a restart offers the
        // window no start of it, or starts the author at a push of its own.
        final Object sample = SerializationCheck.sample(WorkRecord.Document.class);
        final WorkRecord.Document record =
                WorkRecord.JSON.readValue(
                        WorkRecord.JSON.writeValueAsString(sample), WorkRecord.Document.class);
        final org.fuin.porelo.planner.core.WorkRun.Run run = record.runs().get("ST1");

        assertThat(run.placeOffers())
                .as("the read's offers")
                .containsExactly(SerializationCheck.REWORK, SerializationCheck.REVIEW);
        final org.fuin.porelo.planner.core.WorkRun.Offer rework =
                run.offers().stream()
                        .filter(o -> o.id().equals(SerializationCheck.REWORK))
                        .findFirst()
                        .orElseThrow();
        assertThat(
                        List.of(
                                rework.arguments().role(),
                                rework.arguments().again(),
                                rework.arguments().place()))
                .as("its role, offered again, and its place")
                .containsExactly(
                        SerializationCheck.BUILDING,
                        true,
                        org.fuin.porelo.planner.core.RunPlace.gate(null, "task"));
        assertThat(
                        run.offers().stream()
                                .filter(o -> o.id().equals(SerializationCheck.REVIEW))
                                .map(o -> o.binding().role())
                                .toList())
                .as("the reading's role, read back by its name and parts")
                .containsExactly(SerializationCheck.READING);
        assertThat(run.places().values().stream().flatMap(place -> place.authorRuns().stream()))
                .as("no kept place counts the offered rework as an author run")
                .doesNotContain(SerializationCheck.REWORK);
        final var work = WorkAnswers.knownWork(run, issue -> false);
        assertThat(work.starts())
                .as("the window's starts of the rework and the reading")
                .extracting(
                        start -> start.offer(), start -> start.role().name(), start -> start.task())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                SerializationCheck.REWORK, "author", "task"),
                        org.assertj.core.groups.Tuple.tuple(
                                SerializationCheck.REVIEW, "reviewer", null));
        assertThat(
                        WorkRecord.JSON.writeValueAsString(
                                new org.fuin.porelo.planner.app.answers.TaskEventReply(
                                        "", work, List.of())))
                .as("the work answer with its start is written")
                .contains(SerializationCheck.REWORK, SerializationCheck.REVIEW);
    }

    @Test
    void theWorkRecordWrittenHoldsAFollowedOfferAndAMark() {
        // Harm: a native image that cannot read back the reports taken, so a planner after a
        // restart reads every report again and saves its idea twice.
        final WorkRecord.Document sample =
                (WorkRecord.Document) SerializationCheck.sample(WorkRecord.Document.class);

        assertThat(sample.followed()).as("a followed offer").isNotEmpty();
        assertThat(sample.reports()).as("a mark").isNotEmpty();
        assertThat(sample.ideas()).as("a report's idea").isNotEmpty();
    }

    @Test
    void theOutboxReadBackHoldsEveryRefusalKindAndEveryState() {
        // Harm: a native image that cannot read back an outbox holding a kind the check never
        // wrote, so a planner after a restart cannot open its saves.
        final CommandOutbox.Document outbox = SerializationCheck.outbox();

        assertThat(
                        java.util.stream.Stream.concat(
                                        outbox.entries().stream(),
                                        outbox.earlier().stream()
                                                .flatMap(kept -> kept.entries().stream()))
                                .map(entry -> entry.why().name())
                                .filter(name -> !name.isEmpty()))
                .as("every refusal kind the outbox keeps")
                .containsAll(CommandOutbox.REASONS);
        assertThat(outbox.entries().stream().map(CommandOutbox.Entry::state))
                .as("every state")
                .contains(CommandOutbox.State.values());
        assertThat(outbox.earlier()).as("a group kept from another source").isNotEmpty();
        assertThat(BoundTypes.OWN)
                .as("the outbox has its own check line")
                .contains(CommandOutbox.Document.class);
    }

    @Test
    void theExecutableRunsTheCheckBeforeAnythingElse() {
        // Harm: a check that starts the server, so the native test waits on a planner instead.
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final java.util.OptionalInt exit =
                PlannerApplication.work(
                        new String[] {SerializationCheck.NAME},
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertThat(exit).as("answered without the server").hasValue(0);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("the check's lines")
                .contains(" values");
    }
}
