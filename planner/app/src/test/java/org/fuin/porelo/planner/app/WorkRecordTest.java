package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.core.HandedIdeas;
import org.fuin.porelo.planner.core.HandedResults;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.ReadingCycle;
import org.fuin.porelo.planner.core.WorkEnds;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.git.PrivateFiles;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WorkRecordTest {
    private static final String MADE_UP_ISSUE = "AB01"; // not-a-citation: a made-up issue

    @TempDir Path directory;

    @Test
    void aLatestReadSeesWholeGenerationsWhileTheWriterHoldsTheLock() throws Exception {
        // Prevents a busy planner from blocking details or serving a half-written work record.
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        final var replacing = new CountDownLatch(1);
        final var published = new CountDownLatch(1);
        final var replace = new CompletableFuture<Void>();
        final var release = new CompletableFuture<Void>();
        final var replacements = new AtomicInteger();
        final WorkRecord.Replace paused =
                (written, target) -> {
                    if (replacements.incrementAndGet() == 2) {
                        replacing.countDown();
                        replace.join();
                    }
                    Files.move(
                            written,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                };
        try (WorkRecord records =
                        new WorkRecord(environment, false, PrivateFiles.of(environment), paused);
                WorkRecord observer = new WorkRecord(environment, false);
                var executor = Executors.newFixedThreadPool(2)) {
            final var record = records.open("project", "https://example.org/store.git", null);
            final var reader = observer.open("project", "https://example.org/store.git", null);
            final var before = new WorkRun.Snapshot(7, Map.of());
            final var after = new WorkRun.Snapshot(8, Map.of());
            record.locked(
                    session -> {
                        session.write(before);
                        return null;
                    });
            final var saving =
                    executor.submit(
                            () ->
                                    record.locked(
                                            session -> {
                                                session.write(after);
                                                published.countDown();
                                                release.join();
                                                return true;
                                            }));
            try {
                assertThat(replacing.await(5, TimeUnit.SECONDS))
                        .as("the writer holds a new sibling before the atomic replacement")
                        .isTrue();
                assertThat(executor.submit(record::latest).get(1, TimeUnit.SECONDS))
                        .as("the complete previous generation answers while replacement waits")
                        .isEqualTo(before);
                assertThat(executor.submit(reader::latest).get(1, TimeUnit.SECONDS))
                        .as("an entry without a writer session reads despite the held file lock")
                        .isEqualTo(before);
                replace.complete(null);
                assertThat(published.await(5, TimeUnit.SECONDS))
                        .as("the replacement is published while the writer still holds the lock")
                        .isTrue();
                assertThat(executor.submit(record::latest).get(1, TimeUnit.SECONDS))
                        .as("the next complete generation is visible before the lock is released")
                        .isEqualTo(after);
            } finally {
                replace.complete(null);
                release.complete(null);
            }
            assertThat(saving.get(5, TimeUnit.SECONDS))
                    .as("the writer finishes after both generations have been read")
                    .isTrue();
        }
    }

    @Test
    void aReadingDecisionOverAnIssueHeldBackOrNamedTwiceIsRefusedBeforeItIsRecorded() {
        // A reading decision recorded over an issue the store could not fully read, or over one
        // of two issues with one id, would move whichever the planner found.
        final org.fuin.porelo.planner.store.Issue plain =
                new org.fuin.porelo.planner.store.Issue(
                        "AA1",
                        "alpha",
                        "Issue",
                        "Reading",
                        "S",
                        List.of(),
                        null,
                        null,
                        "",
                        org.fuin.porelo.planner.store.Worker.AGENT,
                        Risk.ORDINARY);
        final org.fuin.porelo.planner.store.Issue held =
                plain.withWarnings(List.of(org.fuin.porelo.planner.store.Warning.wrongParent()));
        final org.fuin.porelo.planner.store.Issue twice =
                plain.withWarnings(List.of(org.fuin.porelo.planner.store.Warning.idTwice()));
        final org.fuin.porelo.planner.store.Issue other =
                new org.fuin.porelo.planner.store.Issue(
                                "AA1",
                                "beta",
                                "Issue",
                                "Reading",
                                "S",
                                List.of(),
                                null,
                                null,
                                "",
                                org.fuin.porelo.planner.store.Worker.AGENT,
                                Risk.ORDINARY)
                        .withWarnings(List.of(org.fuin.porelo.planner.store.Warning.idTwice()));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(
                                                    session -> {
                                                        session.preflightIssue(
                                                                session.read(),
                                                                readingBoard(List.of(held)),
                                                                "AA1");
                                                        return null;
                                                    }))
                    .as("an issue held back")
                    .isInstanceOf(WorkRefusal.class)
                    .hasMessage(
                            "AA1: " + org.fuin.porelo.planner.store.Warning.wrongParent().text());
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(
                                                    session -> {
                                                        session.preflightIssue(
                                                                session.read(),
                                                                readingBoard(List.of(twice, other)),
                                                                "AA1");
                                                        return null;
                                                    }))
                    .as("an id naming two issues")
                    .isInstanceOf(WorkRefusal.class)
                    .hasMessage(
                            "AA1 names an issue in alpha and beta; it can be neither worked nor"
                                    + " moved until one id is changed.");
        }
    }

    private static org.fuin.porelo.planner.store.Board readingBoard(
            final List<org.fuin.porelo.planner.store.Issue> issues) {
        return new org.fuin.porelo.planner.store.Board(
                new org.fuin.porelo.planner.store.Revision("r1"),
                new org.fuin.porelo.planner.store.Workflow(
                        List.of("Backlog", "Reading", "Finished"), List.of(), List.of("Finished")),
                List.of(),
                issues,
                new org.fuin.porelo.planner.store.Work(Map.of(), List.of()),
                List.of("alpha", "beta"));
    }

    @Test
    void aTasksMachineNameSurvivesRestartVerbatim() {
        // Prevents a restart from losing or normalizing the machine needed to open a task.
        final String machine = "WSL Ubuntu Äpfel";
        final WorkRun.Run saved = writeTaskRecord(machine);
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Run restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().runs().values().iterator().next());
            assertThat(restored)
                    .as("the task binding survives a new record service")
                    .isEqualTo(saved);
            assertThat(restored.offers().getFirst().machine())
                    .as("spaces and non-ASCII letters are retained verbatim")
                    .isEqualTo(machine);
            assertThat(WorkAnswers.knownWork(restored, issue -> false).tasks().getFirst().machine())
                    .as("the restored story answers the original machine name")
                    .isEqualTo(machine);
        }
    }

    @Test
    void whereABuildLandedSurvivesRestartAndARecordWrittenBeforeLandingsReads() throws Exception {
        // A landing lost on restart starts no reader; an older record that cannot open loses the
        // person's running work.
        final NextStep.Landing landing =
                new NextStep.Landing(
                        "porelo/task",
                        "refs/landed/task/porelo/task",
                        "origin/landed/task/porelo/task",
                        "0123456789abcdef0123456789abcdef01234567");
        writeTaskRecord(
                "workstation",
                "ENDED",
                new WorkRun.Ended("2026-01-01T00:00:00Z", true, "", "Built", 0, List.of(landing)));
        final Path file = directory.resolve("porelo-planner/project/work.json");
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Run restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().runs().values().iterator().next());
            assertThat(restored.offers().getFirst().ended().landed())
                    .as("the build's end keeps where its work landed across a restart")
                    .containsExactly(landing);
        }
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        assertThat(((ObjectNode) offer.path("arguments")).remove("reads"))
                .as("the reference record holds the tasks a reader reads")
                .isNotNull();
        assertThat(((ObjectNode) offer.path("ended")).remove("landed"))
                .as("the reference record holds where the work landed")
                .isNotNull();
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Offer older =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(
                                    session ->
                                            session.read()
                                                    .runs()
                                                    .values()
                                                    .iterator()
                                                    .next()
                                                    .offers()
                                                    .getFirst());
            assertThat(older.ended().landed())
                    .as("an end recorded before landings were kept has not landed")
                    .isEmpty();
            assertThat(older.arguments().reads())
                    .as("a task recorded before reads were kept reads no other task")
                    .isEmpty();
        }
    }

    @Test
    void aDeclaredOutcomeSurvivesRestartAndAnEndRecordedWithoutOneReadsAsNone() throws Exception {
        // A blocked end forgotten on restart replays as another end; an older record that cannot
        // open loses the person's running work.
        writeTaskRecord(
                "workstation",
                "ENDED",
                new WorkRun.Ended(
                        "2026-01-01T00:00:00Z",
                        true,
                        "A key is missing",
                        "Built",
                        0,
                        List.of(),
                        WorkEnds.Declared.BLOCKED));
        final Path file = directory.resolve("porelo-planner/project/work.json");
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Ended restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(
                                    session ->
                                            session.read()
                                                    .runs()
                                                    .values()
                                                    .iterator()
                                                    .next()
                                                    .offers()
                                                    .getFirst()
                                                    .ended());
            assertThat(restored.outcome())
                    .as("the end keeps the outcome its task declared across a restart")
                    .isEqualTo(WorkEnds.Declared.BLOCKED);
        }
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        assertThat(((ObjectNode) offer.path("ended")).remove("outcome"))
                .as("the reference record holds the declared outcome")
                .isNotNull();
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Ended older =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(
                                    session ->
                                            session.read()
                                                    .runs()
                                                    .values()
                                                    .iterator()
                                                    .next()
                                                    .offers()
                                                    .getFirst()
                                                    .ended());
            assertThat(older.outcome())
                    .as("an end recorded before outcomes were kept declared none")
                    .isNull();
            assertThat(older.why())
                    .as("the rest of the end reads as it was")
                    .isEqualTo("A key is missing");
        }
    }

    @Test
    void aRecordWithoutAMachineKeepsTheTaskAndAnswersNull() throws Exception {
        // Prevents older records from losing task bindings or inventing a machine on restart.
        writeTaskRecord("WSL Ubuntu Äpfel");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        assertThat(offer.has("machine")).as("the reference record contains the new field").isTrue();
        assertThat(offer.has("rework")).as("the reference record holds rework settings").isTrue();
        offer.remove("machine");
        // A record written before rework settings were kept must still open.
        offer.remove("rework");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Run restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().runs().values().iterator().next());
            assertThat(restored.offers()).as("the older record retains its bound task").hasSize(1);
            assertThat(restored.offers().getFirst().machine())
                    .as("an absent machine remains unknown")
                    .isNull();
            final var answer =
                    json.readTree(
                            json.writeValueAsString(
                                    WorkAnswers.knownWork(restored, issue -> false)));
            assertThat(answer.at("/tasks/0").has("machine"))
                    .as("the story's task always includes the machine field")
                    .isTrue();
            assertThat(answer.at("/tasks/0/machine").isNull())
                    .as("the wire answer preserves the unknown machine as null")
                    .isTrue();
            offer.remove("containerId");
            Files.writeString(file, json.writeValueAsString(document));
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()))
                    .as("backward compatibility does not excuse other missing binding fields")
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    @Test
    void aRecordWrittenBeforeRisksOpensAndAnEffortThatIsNoNameDoesNot() throws Exception {
        // An upgrade must not make a running story's work record unreadable; a forged effort in
        // the record must not reach a task.
        writeTaskRecord("box");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        final ObjectNode arguments = (ObjectNode) offer.get("arguments");
        final ObjectNode rule = (ObjectNode) offer.path("binding").get("rule");
        assertThat(arguments.has("effort")).as("the reference record holds the effort").isTrue();
        assertThat(rule.has("risks")).as("the reference record holds the rule's risks").isTrue();
        arguments.remove("effort");
        rule.remove("risks");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Run restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().runs().values().iterator().next());
            assertThat(restored.offers()).as("the older record retains its bound task").hasSize(1);
            assertThat(restored.offers().getFirst().arguments().effort())
                    .as("an absent effort stays none")
                    .isNull();
            assertThat(restored.offers().getFirst().binding().rule().risks())
                    .as("an absent rule's risks name none")
                    .isEmpty();
            arguments.put("effort", "max\nrm -rf /");
            Files.writeString(file, json.writeValueAsString(document));
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()))
                    .as("an effort holding a line break is refused")
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    @Test
    void aKeptMissingForgeAccessReadsWithItsRightAndAForgedOneDoesNot() throws Exception {
        // Harm: a refusal told again for another forge or right than the store's, so the window
        // hands the person's token on for an address a changed record slipped in.
        writeTaskRecord("box");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        final ObjectNode refusal = offer.putObject("refusal");
        refusal.put("name", "NoForgeAccess")
                .put("subject", "https://github.com")
                .put("right", "Projects")
                .putArray("pushed");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.SaveFailure kept =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(
                                    session ->
                                            session.read()
                                                    .runs()
                                                    .values()
                                                    .iterator()
                                                    .next()
                                                    .offers()
                                                    .getFirst()
                                                    .refusal());
            assertThat(kept).as("the kept refusal").isNotNull();
            assertThat(List.of(kept.name(), kept.subject(), String.valueOf(kept.right())))
                    .as("the refusal, its forge and its right")
                    .containsExactly("NoForgeAccess", "https://github.com", "Projects");
            for (final Map.Entry<String, String> forged :
                    Map.of(
                                    "subject", "https://person:secret@github.com",
                                    "right", "Projects\nforged line")
                            .entrySet()) {
                final ObjectNode changed = refusal.deepCopy();
                changed.put(forged.getKey(), forged.getValue());
                offer.set("refusal", changed);
                Files.writeString(file, json.writeValueAsString(document));
                assertThatThrownBy(
                                () ->
                                        records.open(
                                                        "project",
                                                        "https://example.org/store.git",
                                                        null)
                                                .locked(session -> session.read()))
                        .as("a kept refusal whose %s is %s", forged.getKey(), forged.getValue())
                        .isInstanceOf(WorkRefusal.class);
            }
            final ObjectNode other = refusal.deepCopy();
            other.put("name", "Unreachable");
            offer.set("refusal", other);
            Files.writeString(file, json.writeValueAsString(document));
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()))
                    .as("another refusal that names a right")
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    @Test
    void aRefusalKeptBeforeRightsWereToldStillReads() throws Exception {
        // Harm: an upgrade makes a work record with a kept save refusal unreadable, so the story's
        // work stops until the record is removed by hand.
        writeTaskRecord("box");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        offer.putObject("refusal")
                .put("name", "PartlySaved")
                .put("subject", "alpha")
                .putArray("pushed")
                .add("beta");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.SaveFailure kept =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(
                                    session ->
                                            session.read()
                                                    .runs()
                                                    .values()
                                                    .iterator()
                                                    .next()
                                                    .offers()
                                                    .getFirst()
                                                    .refusal());
            assertThat(kept).as("the refusal kept by an older planner").isNotNull();
            assertThat(List.of(kept.name(), kept.subject(), String.valueOf(kept.right())))
                    .as("the refusal, its subject and its right")
                    .containsExactly("PartlySaved", "alpha", "null");
            assertThat(kept.pushed()).as("the repositories pushed").containsExactly("beta");
        }
    }

    // Prevents an upgrade from making a running story's work record unreadable, and a forged
    // given file name from reaching a task's start.
    @Test
    void aRecordWrittenBeforeStageResultsOpensAndAGivenNameOutsideItsIssuesDoesNot()
            throws Exception {
        writeTaskRecord("box");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        final ObjectNode arguments = (ObjectNode) offer.get("arguments");
        final ObjectNode rule = (ObjectNode) offer.path("binding").get("rule");
        assertThat(arguments.has("given")).as("the reference record holds given files").isTrue();
        assertThat(rule.has("result")).as("the reference record holds the rule's result").isTrue();
        arguments.remove("result");
        arguments.remove("given");
        rule.remove("result");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Run restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().runs().values().iterator().next());
            assertThat(restored.offers().getFirst().arguments().result())
                    .as("an absent result is none")
                    .isNull();
            assertThat(restored.offers().getFirst().arguments().given())
                    .as("absent given files are none")
                    .isEmpty();
            assertThat(restored.offers().getFirst().binding().rule().result())
                    .as("an absent rule's result pushes its work")
                    .isNull();
            final String issue = "AA1";
            arguments.putArray("issues").add(issue);
            ((ObjectNode) offer.get("binding")).putArray("issues").add(issue);
            final ObjectNode sound = arguments.deepCopy();
            given(sound, "analysis.json", issue + ".md");
            offer.set("arguments", sound);
            Files.writeString(file, json.writeValueAsString(document));
            assertThat(
                            records.open("project", "https://example.org/store.git", null)
                                    .locked(
                                            session ->
                                                    session.read()
                                                            .runs()
                                                            .values()
                                                            .iterator()
                                                            .next())
                                    .offers()
                                    .getFirst()
                                    .arguments()
                                    .given())
                    .as(
                            "a stage's own issue given once reads, so each refusal below is its forgery's")
                    .hasSize(1);
            final ObjectNode compacted = arguments.deepCopy();
            given(compacted, "analysis.json");
            offer.set("arguments", compacted);
            Files.writeString(file, json.writeValueAsString(document));
            assertThat(
                            records.open("project", "https://example.org/store.git", null)
                                    .locked(
                                            session ->
                                                    session.read()
                                                            .runs()
                                                            .values()
                                                            .iterator()
                                                            .next())
                                    .offers()
                                    .getFirst()
                                    .arguments()
                                    .result())
                    .as("an ended stage's offer, compacted to its result alone, still reads")
                    .isEqualTo("analysis.json");
            final Map<String, java.util.function.Consumer<ObjectNode>> forgeries =
                    new java.util.LinkedHashMap<>();
            forgeries.put(
                    "a given file named outside the task's issues",
                    forged -> given(forged, "analysis.json", "../escape.md"));
            forgeries.put(
                    "an idea's result on work in a repository",
                    forged -> given(forged, "idea.json", issue + ".md"));
            forgeries.put(
                    "given files on work that pushes",
                    forged -> given(forged, null, issue + ".md"));
            forgeries.put(
                    "one issue given twice",
                    forged -> given(forged, "analysis.json", issue + ".md", issue + ".md"));
            for (final Map.Entry<String, java.util.function.Consumer<ObjectNode>> forgery :
                    forgeries.entrySet()) {
                final ObjectNode forged = arguments.deepCopy();
                forgery.getValue().accept(forged);
                offer.set("arguments", forged);
                Files.writeString(file, json.writeValueAsString(document));
                assertThatThrownBy(
                                () ->
                                        records.open(
                                                        "project",
                                                        "https://example.org/store.git",
                                                        null)
                                                .locked(session -> session.read()))
                        .as(forgery.getKey() + " is refused")
                        .isInstanceOf(WorkRefusal.class);
            }
        }
    }

    @Test
    void aStageOverATrackersIssueReadsWithItsFileNamedWithADashAndNotWithAHash() throws Exception {
        // Harm: a stage started over a tracker's issue leaves a record the planner cannot reload,
        // so the run is lost on restart, or a name holding a '#' reaches what Porelo mounts.
        writeTaskRecord("box");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        final ObjectNode arguments = (ObjectNode) offer.get("arguments");
        arguments.putArray("issues").add("porelo-x#3");
        ((ObjectNode) offer.get("binding")).putArray("issues").add("porelo-x#3");
        given(arguments, "analysis.json", "porelo-x-3.md");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            assertThat(
                            records.open("project", "https://example.org/store.git", null)
                                    .locked(
                                            session ->
                                                    session.read()
                                                            .runs()
                                                            .values()
                                                            .iterator()
                                                            .next())
                                    .offers()
                                    .getFirst()
                                    .arguments()
                                    .given())
                    .as("the tracker issue's file, named with a dash")
                    .extracting(WorkOrder.Given::name)
                    .containsExactly("porelo-x-3.md");
            given(arguments, "analysis.json", "porelo-x#3.md");
            Files.writeString(file, json.writeValueAsString(document));
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()))
                    .as("a name holding a '#' is refused")
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    @Test
    void aTaskRecordedWithoutTheTrackersWorkReadsAsNoneAndAForgedOneIsRefused() throws Exception {
        // Harm: an upgrade makes a running story's record unreadable because its tasks were
        // recorded before a start named the tracker's issue, or an edited record hands Porelo's
        // Start an issue the task does not work.
        writeTaskRecord("box");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        final ObjectNode arguments = (ObjectNode) offer.get("arguments");
        arguments.remove("work");
        arguments.putArray("issues").add("porelo-x#3");
        ((ObjectNode) offer.get("binding")).putArray("issues").add("porelo-x#3");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            assertThat(work(records)).as("an older record's task names no tracker's work").isNull();
            arguments
                    .putObject("work")
                    .put("issue", "example-org/porelo-x#3")
                    .put("phase", "build");
            Files.writeString(file, json.writeValueAsString(document));
            assertThat(work(records))
                    .as("the tracker's work read back as recorded")
                    .isEqualTo(new WorkOrder.Tracked("example-org/porelo-x#3", "build"));
            arguments
                    .putObject("work")
                    .put("issue", "example-org/porelo-x#4")
                    .put("phase", "build");
            Files.writeString(file, json.writeValueAsString(document));
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()))
                    .as("work naming an issue the task does not work is refused")
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    private static WorkOrder.@Nullable Tracked work(final WorkRecord records) {
        return records.open("project", "https://example.org/store.git", null)
                .locked(session -> session.read().runs().values().iterator().next())
                .offers()
                .getFirst()
                .arguments()
                .work();
    }

    @Test
    void aRecordWrittenBeforeRisksOpensWithItsRepositoriesMainAndSubAgent() throws Exception {
        // An upgrade must not make a running story's work record unreadable when its run names
        // repositories, whose main and sub-agents were written without an effort and a basis, and
        // whose main without the offered agents.
        writeTaskRecord(
                "box",
                List.of(
                        new WorkProposal.Repository(
                                "alpha",
                                List.of(MADE_UP_ISSUE),
                                new WorkProposal.Main(
                                        "main",
                                        "MAIN",
                                        "codex",
                                        "careful",
                                        "brief",
                                        "max",
                                        "Building, risk harm"),
                                List.of(
                                        new WorkProposal.Assignment(
                                                "sub",
                                                MADE_UP_ISSUE,
                                                "helper",
                                                "BUILDER",
                                                "codex",
                                                "careful",
                                                "Building",
                                                "brief",
                                                "KEEP",
                                                "max",
                                                "Building, risk harm")))));
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode repository =
                (ObjectNode) document.path("runs").iterator().next().path("repositories").get(0);
        final ObjectNode main = (ObjectNode) repository.get("main");
        final ObjectNode sub = (ObjectNode) repository.path("subAgents").get(0);
        for (final ObjectNode older : List.of(main, sub)) {
            assertThat(older.has("effort") && older.has("basis"))
                    .as("the reference record holds the effort and the basis")
                    .isTrue();
            older.remove("effort");
            older.remove("basis");
        }
        assertThat(main.has("offered"))
                .as("the reference record holds the main's offered agents")
                .isTrue();
        main.remove("offered");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Run restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().runs().values().iterator().next());
            final WorkProposal.Repository read = restored.repositories().getFirst();
            assertThat(read.main().agent())
                    .as("the older record keeps its repository's main")
                    .isEqualTo("codex");
            assertThat(read.main().effort()).as("the main's absent effort stays none").isNull();
            assertThat(read.main().basis()).as("the main's absent basis stays empty").isEmpty();
            assertThat(read.main().offered())
                    .as("the main's absent offered agents stay none")
                    .isEmpty();
            assertThat(read.subAgents())
                    .as("the older record keeps its sub-agent")
                    .singleElement()
                    .satisfies(
                            helper -> {
                                assertThat(helper.effort())
                                        .as("the sub-agent's absent effort stays none")
                                        .isNull();
                                assertThat(helper.basis())
                                        .as("the sub-agent's absent basis stays empty")
                                        .isEmpty();
                            });
        }
    }

    @Test
    void handedResultsSurviveRestartAndARecordWrittenBeforeStagesReads() throws Exception {
        // Prevents a lost outcome saving a stage's result twice, and an older record that cannot
        // open losing the person's running work.
        final String offer = "00000000-0000-0000-0000-000000000002";
        final HandedResults.Handed handed =
                new HandedResults.Handed(
                        HandedResults.REFUSED,
                        "task",
                        List.of("AA1"),
                        List.of("issues[0].size: The size must be one of XS, S, M, L, XL."),
                        "",
                        2);
        final WorkRun.Run run = writeTaskRecord("workstation");
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false)) {
            records.open("project", "https://example.org/store.git", null)
                    .locked(
                            session -> {
                                session.write(
                                        new WorkRun.Snapshot(
                                                2,
                                                Map.of(run.story(), run),
                                                Map.of(),
                                                Map.of(offer, handed)));
                                return true;
                            });
        }
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final Map<String, HandedResults.Handed> restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().results());
            assertThat(restored)
                    .as("the offer's outcome after a restart")
                    .isEqualTo(Map.of(offer, handed));
        }
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final ObjectNode document = (ObjectNode) json.readTree(Files.readString(file));
        final ObjectNode written =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        assertThat(document.remove("results")).as("the record holds the outcomes").isNotNull();
        final ObjectNode arguments = (ObjectNode) written.path("arguments");
        assertThat(arguments.has("result") && arguments.has("given"))
                .as("the reference record names the task's result file and given files")
                .isTrue();
        arguments.remove(List.of("result", "given"));
        assertThat(((ObjectNode) written.path("binding").path("rule")).remove("result"))
                .as("the reference record names what the rule hands back")
                .isNotNull();
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final WorkRun.Snapshot older =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read());
            assertThat(older.results())
                    .as("a record written before stages handed in results holds none")
                    .isEmpty();
            final WorkRun.Offer olderOffer =
                    older.runs().values().iterator().next().offers().getFirst();
            assertThat(olderOffer.arguments().result())
                    .as("a task recorded before stages hands back no result")
                    .isNull();
            assertThat(olderOffer.binding().rule().result())
                    .as("a rule recorded before stages hands back no result")
                    .isNull();
        }
    }

    @Test
    void handedIdeasSurviveRestartAndARecordWrittenBeforeThemReads() throws Exception {
        // A lost offer would save a repeated hand-in as a second story; an older record must open.
        final String offer = "4f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f";
        final String story = String.format("ST%d", 7);
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false)) {
            records.open("project", "https://example.org/store.git", null)
                    .locked(
                            session -> {
                                session.write(
                                        new WorkRun.Snapshot(
                                                1,
                                                Map.of(),
                                                Map.of(
                                                        offer,
                                                        new HandedIdeas.Handed(
                                                                HandedIdeas.DONE,
                                                                story,
                                                                List.of(),
                                                                1))));
                                return true;
                            });
        }
        final Path file = directory.resolve("porelo-planner/project/work.json");
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final HandedIdeas.Handed restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().ideas().get(offer));
            assertThat(restored)
                    .as("the offer's story after a restart")
                    .isEqualTo(new HandedIdeas.Handed(HandedIdeas.DONE, story, List.of(), 1));
        }
        final JsonMapper json = JsonMapper.builder().build();
        final ObjectNode document = (ObjectNode) json.readTree(Files.readString(file));
        assertThat(document.has("ideas")).as("the record holds the handed ideas").isTrue();
        document.remove("ideas");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final Map<String, HandedIdeas.Handed> ideas =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().ideas());
            assertThat(ideas)
                    .as("a record written before ideas were handed in holds none")
                    .isEmpty();
        }
        document.putObject("ideas")
                .putObject(offer)
                .put("status", HandedIdeas.DONE)
                .put("story", "../" + story)
                .put("order", 1)
                .putArray("pushed");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final WorkRefusal refused =
                    catchThrowableOfType(
                            WorkRefusal.class,
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()));
            assertThat(refused).as("a handed idea that fails its check is refused").isNotNull();
            assertThat(reason(refused)).as("its words").doesNotContain("../" + story);
        }
    }

    private WorkRun.Run writeTaskRecord(final String machine) {
        return writeTaskRecord(machine, "STARTED", null, List.of());
    }

    private WorkRun.Run writeTaskRecord(
            final String machine, final String status, final WorkRun.@Nullable Ended ended) {
        return writeTaskRecord(machine, status, ended, List.of());
    }

    private WorkRun.Run writeTaskRecord(
            final String machine, final List<WorkProposal.Repository> repositories) {
        return writeTaskRecord(machine, "STARTED", null, repositories);
    }

    private WorkRun.Run writeTaskRecord(
            final String machine,
            final String status,
            final WorkRun.@Nullable Ended ended,
            final List<WorkProposal.Repository> repositories) {
        final String story = String.format("ST%d", 1);
        final var rule =
                new WorkRule(
                        List.of("Backlog"),
                        "Review",
                        null,
                        null,
                        WorkRule.Per.REPOSITORY,
                        List.of("AGENT"),
                        "Build",
                        Map.of("codex", "model"),
                        Map.of(Risk.HARM, new WorkRule.ForRisk(Map.of("codex", "careful"), "max")));
        final var task =
                new WorkOrder.Task(
                        "alpha", List.of(), "MAIN", "codex", "careful", "max", "AGENT", "Build");
        final String since = java.time.Instant.EPOCH.toString();
        final var offer =
                new WorkRun.Offer(
                        "00000000-0000-0000-0000-000000000002",
                        task,
                        new NextStep.Binding(
                                story, "alpha", List.of(), "MAIN", "Building", "AGENT", rule),
                        status,
                        "task",
                        "box",
                        machine,
                        since,
                        ended,
                        "",
                        List.of(),
                        List.of(),
                        null);
        final var run =
                new WorkRun.Run(
                        "00000000-0000-0000-0000-000000000001",
                        story,
                        "Building",
                        "AGENT",
                        "",
                        "START",
                        2,
                        List.of(),
                        repositories,
                        since,
                        List.of(offer),
                        Map.of(),
                        false,
                        false,
                        false);
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            records.open("project", "https://example.org/store.git", null)
                    .locked(
                            session -> {
                                session.write(new WorkRun.Snapshot(1, Map.of(story, run)));
                                return true;
                            });
        }
        return run;
    }

    @Test
    void aLatestReadOfAnotherSourcesRecordAnswersNoRunsAndLeavesTheFile() throws Exception {
        // Prevents a read without the lock from wiping another planning source's runs.
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false)) {
            records.open("project", "https://example.org/store.git", "main")
                    .locked(
                            session -> {
                                session.write(new WorkRun.Snapshot(9, Map.of()));
                                return null;
                            });
        }
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final byte[] before = Files.readAllBytes(file);
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final WorkRun.Snapshot read =
                    records.open("project", "https://example.org/other.git", "main").latest();
            assertThat(read.generation()).as("another source's record read").isZero();
        }
        assertThat(Files.readAllBytes(file))
                .as("the other source's record after a read without the lock")
                .isEqualTo(before);
    }

    @Test
    void aRecordSurvivesRestartAndIsReplacedWhenItsSourceChanges() throws Exception {
        // Prevents lost work on restart and runs inherited from another store.
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false)) {
            records.open("project", "https://example.org/store.git", "main")
                    .locked(
                            session -> {
                                session.write(new WorkRun.Snapshot(9, Map.of()));
                                return null;
                            });
        }
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final long generation =
                    records.open("project", "https://example.org/store.git", "main")
                            .locked(s -> s.read().generation());
            assertThat(generation).as("the durable generation survives a new service").isEqualTo(9);
            final long reset =
                    records.open("project", "https://example.org/other.git", "main")
                            .locked(s -> s.read().generation());
            assertThat(reset).as("a different planning source has no old runs").isZero();
        }
        final Path file = directory.resolve("porelo-planner/project/work.json");
        assertThat(Files.getPosixFilePermissions(file))
                .as("only the owner can read or write task reports")
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void completedReadingsFindingsAndGrantedAllowancesSurviveRestart() throws Exception {
        // Prevents lost findings or reset reading allowances after restart.
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        final var finding =
                new WorkEnds.Finding(
                        1, WorkEnds.Severity.BLOCKING, "src/A.java", 7, "Preserve data");
        final var progress =
                new NextStep.Progress(
                        new ReadingCycle.Rounds(13, 13, true),
                        List.of(finding),
                        true,
                        "ROUND_LIMIT",
                        "Findings:\n1. blocking | src/A.java | 7 | Preserve data");
        final var run =
                new WorkRun.Run(
                        "00000000-0000-0000-0000-000000000001",
                        "ST1",
                        "Building",
                        "AGENT",
                        "",
                        "START",
                        2,
                        List.of(),
                        List.of(),
                        "2026-01-01T00:00:00Z",
                        List.of(),
                        Map.of("AA1", progress),
                        false,
                        false,
                        false);
        try (WorkRecord records = new WorkRecord(environment, false)) {
            records.open("project", "https://example.org/store.git", null)
                    .locked(
                            session -> {
                                session.write(new WorkRun.Snapshot(2, Map.of("ST1", run)));
                                return null;
                            });
        }
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final WorkRun.Run restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(s -> s.read().runs().get("ST1"));
            assertThat(restored).as("the complete reading state is durable").isEqualTo(run);
            assertThat(restored.issues().get("AA1").rounds().roundsMost())
                    .as("extensions beyond the initial bound are retained")
                    .isEqualTo(13);
            final Path file = directory.resolve("porelo-planner/project/work.json");
            final String saved = Files.readString(file);
            assertThat(saved)
                    .as("the stopped reading is present in the reference record")
                    .contains("\"stopped\":true");
            for (final String invalid :
                    List.of(
                            saved.replace("\"stopped\":true", "\"stopped\":null"),
                            saved.replace("\"stopped\":true", "\"stopped\":\"false\""),
                            saved.replace("\"rounds\":13", "\"rounds\":1.5"))) {
                Files.writeString(file, invalid);
                assertThatThrownBy(
                                () ->
                                        records.open(
                                                        "project",
                                                        "https://example.org/store.git",
                                                        null)
                                                .locked(s -> s.read()))
                        .as("a missing primitive cannot silently clear the stop")
                        .isInstanceOf(WorkRefusal.class);
            }
        }
    }

    @Test
    void aSecondPlannerCannotWriteUntilTheFirstCloses() {
        // Prevents competing planner processes from overwriting each other's counts and offers.
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord first = new WorkRecord(environment, false);
                WorkRecord second = new WorkRecord(environment, false)) {
            first.open("project", "https://example.org/store.git", null).locked(s -> s.read());
            final WorkRefusal refused =
                    catchThrowableOfType(
                            WorkRefusal.class,
                            () ->
                                    second.open("project", "https://example.org/store.git", null)
                                            .locked(s -> s.read()));
            assertThat(refused)
                    .as("the stable file lock refuses a competing writer without waiting")
                    .isNotNull();
            assertThat(reason(refused))
                    .as("the person learns that another planner holds this project's record")
                    .contains("planner", "project");
        }
    }

    @Test
    void requestsInOnePlannerWaitForTheSameRecordAndThenSucceed() throws Exception {
        // Prevents concurrent requests from returning 503 after another request commits a write.
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false);
                var executor = Executors.newFixedThreadPool(2)) {
            final var record = records.open("project", "https://example.org/store.git", null);
            final var held = new CompletableFuture<Boolean>();
            final var release = new CompletableFuture<Boolean>();
            final var calling = new CountDownLatch(1);
            final var first =
                    executor.submit(
                            () ->
                                    record.locked(
                                            session -> {
                                                session.write(new WorkRun.Snapshot(1, Map.of()));
                                                held.complete(true);
                                                release.join();
                                                return session.read().generation();
                                            }));
            final java.util.concurrent.Future<Long> second;
            try {
                assertThat(held.get(5, TimeUnit.SECONDS))
                        .as("the first request has committed while holding the project lock")
                        .isTrue();
                second =
                        executor.submit(
                                () -> {
                                    calling.countDown();
                                    return record.locked(session -> session.read().generation());
                                });
                assertThat(calling.await(5, TimeUnit.SECONDS))
                        .as("the second request reaches the same record before release")
                        .isTrue();
                assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS))
                        .as("the second request waits instead of refusing the local writer")
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.complete(true);
            }
            assertThat(first.get(5, TimeUnit.SECONDS))
                    .as("the first request finishes its committed generation")
                    .isEqualTo(1);
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .as("the waiting request succeeds and observes the committed generation")
                    .isEqualTo(1);
            records.forget("project");
            assertThatThrownBy(() -> record.locked(session -> session.read()))
                    .as("forget closes the old record after the waiting requests finish")
                    .isInstanceOf(WorkRefusal.class);
            try (WorkRecord another = new WorkRecord(environment, false)) {
                final Long generation =
                        another.open("project", "https://example.org/store.git", null)
                                .locked(session -> Long.valueOf(session.read().generation()));
                assertThat(generation)
                        .as("forget releases the file lock for another planner")
                        .isEqualTo(1L);
            }
        }
    }

    @Test
    void linkedOrOversizedRecordsAreRefusedBeforeReading() throws Exception {
        // Prevents following a planted link and allocating an unbounded local record.
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false)) {
            records.open("project", "https://example.org/store.git", null)
                    .locked(
                            s -> {
                                s.write(new WorkRun.Snapshot(1, Map.of()));
                                return null;
                            });
        }
        final Path file = directory.resolve("porelo-planner/project/work.json");
        Files.delete(file);
        final Path secret = Files.writeString(directory.resolve("private"), "never read");
        Files.createSymbolicLink(file, secret);
        try (WorkRecord records = new WorkRecord(environment, false)) {
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(s -> s.read()))
                    .as("a symlink cannot become the record")
                    .isInstanceOf(WorkRefusal.class);
        }
        Files.delete(file);
        try (var out =
                java.nio.channels.FileChannel.open(
                        file,
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE)) {
            out.position(WorkRecord.MOST_RECORD);
            out.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        try (WorkRecord records = new WorkRecord(environment, false)) {
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(s -> s.read()))
                    .as("size is bounded before JSON is read")
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    @Test
    void relativeOrParentTraversingStatePathsAreRefused() {
        // Prevents environment paths from redirecting private records.
        for (final String path : new String[] {"", "relative", directory + "/../elsewhere"}) {
            try (WorkRecord records = new WorkRecord(Map.of("XDG_STATE_HOME", path), false)) {
                assertThatThrownBy(
                                () ->
                                        records.open(
                                                        "project",
                                                        "https://example.org/store.git",
                                                        null)
                                                .locked(s -> s.read()))
                        .as("state roots must be absolute without parent traversal")
                        .isInstanceOf(WorkRefusal.class);
            }
        }
    }

    @Test
    void aWritableAncestorCannotRedirectTheRecord() throws Exception {
        // Prevents another account from replacing a record ancestor during a save.
        final Path shared = Files.createDirectory(directory.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", shared.toString()), false)) {
            final WorkRefusal refused =
                    catchThrowableOfType(
                            WorkRefusal.class,
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(s -> s.read()));
            assertThat(refused)
                    .as("unprotected writable ancestors cannot hold private work")
                    .isNotNull();
            assertThat(reason(refused))
                    .as("the reason names the writable state directory by its variable alone")
                    .contains("$XDG_STATE_HOME")
                    .doesNotContain(directory.toString());
        }
        assertThat(shared.resolve("porelo-planner/project/work.json"))
                .as("no record is published through the unsafe path")
                .doesNotExist();
    }

    @Test
    void unreadableOrLostRecordsNeverInventAZeroCountRun() throws Exception {
        // Prevents record loss or corruption from silently granting a fresh reading allowance.
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final var record = records.open("project", "https://example.org/store.git", null);
            final Map<String, WorkRun.Run> runs = record.locked(s -> s.read().runs());
            assertThat(runs).as("a missing record means unknown work").isEmpty();
            record.locked(
                    s -> {
                        s.write(new WorkRun.Snapshot(1, Map.of()));
                        return null;
                    });
            Files.writeString(
                    directory.resolve("porelo-planner/project/work.json"),
                    "{\"version\":1,\"version\":1}");
            final WorkRefusal refused =
                    catchThrowableOfType(WorkRefusal.class, () -> record.locked(s -> s.read()));
            assertThat(refused)
                    .as("duplicate or unreadable JSON cannot reset the record")
                    .isNotNull();
            assertThat(reason(refused))
                    .as("the reason says the record is not valid JSON, quoting none of it")
                    .contains("JSON")
                    .doesNotContain("version");
        }
    }

    @Test
    void aGroupWritableHomeIsNamedFromTheHomeAndNeverByItsAbsolutePath() throws Exception {
        // Prevents a stopped project with no way to find what to fix, and the account's home
        // path reaching whatever reads or logs the answer.
        final Path home = Files.createDirectory(directory.resolve("home"));
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxrwx---"));
        try (WorkRecord records = new WorkRecord(Map.of("HOME", home.toString()), false)) {
            final WorkRefusal refused =
                    catchThrowableOfType(
                            WorkRefusal.class,
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(s -> s.read()));
            assertThat(refused).as("a home its group can write holds no record").isNotNull();
            assertThat(reason(refused))
                    .as("the reason names the home as ~ and says who can write it")
                    .contains("~", "group")
                    .doesNotContain(directory.toString());
            assertThat(refused.getMessage())
                    .as("the refusal's own words carry no absolute path either")
                    .doesNotContain(directory.toString());
        }
    }

    private static String reason(final WorkRefusal refused) {
        return String.valueOf(refused.parameters().get("reason"));
    }

    @Test
    void aHeldRecordIsNotCheckedAgainOnEveryOperation() {
        // On Windows each check of the directories starts PowerShell, which takes up to seconds.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final AtomicInteger checks = new AtomicInteger();
        final PrivateFiles real = PrivateFiles.of(environment);
        final PrivateFiles counted =
                new PrivateFiles() {
                    @Override
                    public boolean windows() {
                        return real.windows();
                    }

                    @Override
                    public Path base(final Kind kind, final Map<String, String> variables) {
                        return real.base(kind, variables);
                    }

                    @Override
                    public List<String> steps(final Kind kind) {
                        return real.steps(kind);
                    }

                    @Override
                    public Path directories(
                            final Path base,
                            final boolean create,
                            final Kind kind,
                            final List<String> steps) {
                        checks.incrementAndGet();
                        return real.directories(base, create, kind, steps);
                    }

                    @Override
                    public FileAttribute<?>[] ownerOnlyFile() {
                        return real.ownerOnlyFile();
                    }

                    @Override
                    public void requireOwnFile(final Path file, final boolean alone) {
                        real.requireOwnFile(file, alone);
                    }
                };
        try (WorkRecord records = new WorkRecord(environment, false, counted)) {
            final WorkRun.Record store =
                    records.open("project", "https://example.org/store.git", null);
            for (int i = 0; i < 3; i++) {
                store.locked(session -> session.read());
            }

            assertThat(checks.get())
                    .as(
                            "the directories are checked when the lock is taken, not again while it is held")
                    .isEqualTo(1);
        }
    }

    @Test
    void aRecordOpenElsewhereForAMomentIsStillReplaced() {
        // A virus scanner or indexer on Windows holds a file open for a moment; the work would
        // fail.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final AtomicInteger refused = new AtomicInteger();
        final WorkRecord.Replace busyOnce =
                (written, target) -> {
                    if (refused.getAndIncrement() == 0) {
                        throw new FileSystemException(
                                target.toString(),
                                null,
                                "The process cannot access the file because it is being used by"
                                        + " another process");
                    }
                    Files.move(
                            written,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                };
        try (WorkRecord records =
                new WorkRecord(environment, false, PrivateFiles.of(environment), busyOnce)) {
            final long generation =
                    records.open("project", "https://example.org/store.git", "main")
                            .locked(
                                    session -> {
                                        session.write(new WorkRun.Snapshot(7, Map.of()));
                                        return session.read().generation();
                                    });

            assertThat(generation)
                    .as("the record is written once the file is free again")
                    .isEqualTo(7);
        }
    }

    /** Sets a forged offer's result and given files, each holding a line of text. */
    private static void given(
            final ObjectNode arguments, final @Nullable String result, final String... names) {
        arguments.put("result", result);
        final var given = arguments.putArray("given");
        for (final String name : names) {
            given.addObject().put("name", name).put("text", "x");
        }
    }
}
