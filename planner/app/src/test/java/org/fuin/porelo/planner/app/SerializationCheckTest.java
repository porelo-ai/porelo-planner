package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the executable's check of its own image: it must write and read back every type the
 * planner binds, so the native executable's answer can be held to the JVM's.
 */
class SerializationCheckTest {

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
                .as("the read's offer")
                .containsExactly(SerializationCheck.REWORK);
        final org.fuin.porelo.planner.core.WorkRun.Offer rework =
                run.offers().stream()
                        .filter(o -> o.id().equals(SerializationCheck.REWORK))
                        .findFirst()
                        .orElseThrow();
        assertThat(List.of(rework.arguments().runRole(), rework.arguments().place()))
                .as("its role and place")
                .containsExactly(
                        org.fuin.porelo.planner.core.RunRole.AUTHOR,
                        org.fuin.porelo.planner.core.RunPlace.gate(null, "task"));
        assertThat(run.places().values().stream().flatMap(place -> place.authorRuns().stream()))
                .as("no kept place counts the offered rework as an author run")
                .doesNotContain(SerializationCheck.REWORK);
        final var work = WorkAnswers.knownWork(run, issue -> false);
        assertThat(work.starts()).as("the window's start of it").hasSize(1);
        assertThat(
                        WorkRecord.JSON.writeValueAsString(
                                new org.fuin.porelo.planner.app.answers.TaskEventReply(
                                        "", work, List.of())))
                .as("the work answer with its start is written")
                .contains(SerializationCheck.REWORK);
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
        assertThat(BoundTypes.OWN).contains(CommandOutbox.Document.class);
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
