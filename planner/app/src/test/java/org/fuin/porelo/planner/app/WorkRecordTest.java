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
import java.time.Duration;
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
import org.fuin.porelo.planner.core.IssuePlace;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.ReadingCycle;
import org.fuin.porelo.planner.core.ReportTaking;
import org.fuin.porelo.planner.core.ReviewOpen;
import org.fuin.porelo.planner.core.RunPlace;
import org.fuin.porelo.planner.core.RunRole;
import org.fuin.porelo.planner.core.WorkEnds;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkProposal;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.files.PrivateFiles;
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
                var executor = Executors.newFixedThreadPool(2)) {
            final var record = records.open("project", "https://example.org/store.git", null);
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
                org.fuin.porelo.planner.store.ProjectRepository.named(List.of("alpha", "beta")));
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
            // The record is read once, at open, so it is opened anew to read the change.
            records.forget("project");
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
            // The record is read once, at open, so it is opened anew to read the change.
            records.forget("project");
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
                // The record is read once, at open, so it is opened anew to read the change.
                records.forget("project");
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
            // The record is read once, at open, so it is opened anew to read the change.
            records.forget("project");
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()))
                    .as("another refusal that names a right")
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    @Test
    void aKeptRefusalReadsWithItsSavesGroupAndAGroupOfAnotherFormDoesNot() throws Exception {
        // Harm: the owner account's resave of a task's refused event lost at a restart, as the
        // group it names is not kept, or a forged group read back as one the planner made.
        writeTaskRecord("box");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode offer =
                (ObjectNode) document.path("runs").iterator().next().path("offers").get(0);
        final String group = java.util.UUID.randomUUID().toString();
        final ObjectNode refusal = offer.putObject("refusal");
        refusal.put("name", "NotSavable").put("subject", "AA1").put("group", group);
        refusal.putArray("pushed");
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
            assertThat(kept.group()).as("the save's group").isEqualTo(group);

            refusal.put("group", group.toUpperCase(java.util.Locale.ROOT));
            Files.writeString(file, json.writeValueAsString(document));
            // The record is read once, at open, so it is opened anew to read the change.
            records.forget("project");
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()))
                    .as("a group of another form")
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
            // The record is read once, at open, so it is opened anew to read the change.
            records.forget("project");
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
            // The record is read once, at open, so it is opened anew to read the change.
            records.forget("project");
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
                // The record is read once, at open, so it is opened anew to read the change.
                records.forget("project");
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
            // The record is read once, at open, so it is opened anew to read the change.
            records.forget("project");
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
            // The record is read once, at open, so it is opened anew to read the change.
            records.forget("project");
            assertThat(work(records))
                    .as("the tracker's work read back as recorded")
                    .isEqualTo(new WorkOrder.Tracked("example-org/porelo-x#3", "build"));
            arguments
                    .putObject("work")
                    .put("issue", "example-org/porelo-x#4")
                    .put("phase", "build");
            Files.writeString(file, json.writeValueAsString(document));
            // The record is read once, at open, so it is opened anew to read the change.
            records.forget("project");
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()))
                    .as("work naming an issue the task does not work is refused")
                    .isInstanceOf(WorkRefusal.class);
        }
    }

    @Test
    void aTasksRunRoleAndPlaceAreReadBackAndARecordWithoutThemOpens() throws Exception {
        // Harm: an upgrade makes a running story's record unreadable because its tasks were
        // recorded before runs named a role and a place; or a kept role and place come back
        // other than written, so a run is started at the wrong place.
        writeTaskRecord("box");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final var document = json.readTree(Files.readString(file));
        final ObjectNode arguments =
                (ObjectNode)
                        document.path("runs")
                                .iterator()
                                .next()
                                .path("offers")
                                .get(0)
                                .get("arguments");
        arguments.remove("runRole");
        arguments.remove("place");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            assertThat(arguments(records).place())
                    .as("an older record's task names no place")
                    .isNull();
            assertThat(arguments(records).runRole()).isNull();
            arguments.put("runRole", "REVIEWER");
            // As the planner writes a place: every member, the unused ones null.
            final ObjectNode place = arguments.putObject("place");
            place.putObject("gatePush").put("repository", "docs").put("name", "cart");
            place.putNull("pullRequest");
            place.putNull("ownPullRequest");
            Files.writeString(file, json.writeValueAsString(document));
            records.forget("project");
            assertThat(arguments(records).runRole()).isEqualTo(RunRole.REVIEWER);
            assertThat(arguments(records).place()).isEqualTo(RunPlace.gate("docs", "cart"));
            arguments
                    .putObject("place")
                    .put("ownPullRequest", true)
                    .putNull("gatePush")
                    .putNull("pullRequest");
            Files.writeString(file, json.writeValueAsString(document));
            records.forget("project");
            assertThat(arguments(records).place()).isEqualTo(new RunPlace(null, null, true));
            arguments
                    .putObject("place")
                    .put("ownPullRequest", true)
                    .putNull("gatePush")
                    .put("pullRequest", "b");
            Files.writeString(file, json.writeValueAsString(document));
            records.forget("project");
            assertThatThrownBy(
                            () ->
                                    records.open("project", "https://example.org/store.git", null)
                                            .locked(session -> session.read()))
                    .as("a place naming two places is refused")
                    .isNotNull();
        }
    }

    @Test
    void anIssuesKeptPlaceIsReadBackWithItsTimesAndARecordWithoutPlacesOpens() throws Exception {
        // Harm: an upgrade makes a running story's record unreadable because it was written
        // before places were kept; or a kept place comes back other than written, losing where
        // the issue's work waits or the times the people's waits are measured from.
        final WorkRun.Run written = writePlaceRecord();
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            assertThat(run(records).places())
                    .as("the place read back as written, its answer's times included")
                    .isEqualTo(written.places());
            final var document = json.readTree(Files.readString(file));
            ((ObjectNode) document.path("runs").iterator().next()).remove("places");
            Files.writeString(file, json.writeValueAsString(document));
            records.forget("project");
            assertThat(run(records).places()).as("an older record keeps no place").isEmpty();
        }
    }

    @Test
    void theOffersAReadOfAPlaceMadeAreReadBackAndARecordWithoutThemOpens() throws Exception {
        // Harm: after a restart the planner no longer knows which offer a read made, so the
        // window never starts the author's rework; an upgrade makes an older record unreadable;
        // or a record changed on disk names an offer no read made, which the window then starts.
        writePlaceRecord();
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final String rework = "00000000-0000-0000-0000-000000000003";
        final var document = json.readTree(Files.readString(file));
        final ObjectNode run = (ObjectNode) document.path("runs").iterator().next();
        final ObjectNode offer = ((ObjectNode) run.get("offers").get(0)).deepCopy();
        offer.put("id", rework).put("status", "OFFERED");
        for (final String unbound : List.of("task", "containerId", "machine", "since", "ended")) {
            offer.putNull(unbound);
        }
        final ObjectNode arguments = (ObjectNode) offer.get("arguments");
        arguments.put("runRole", "AUTHOR");
        final ObjectNode place = arguments.putObject("place");
        place.putObject("gatePush").putNull("repository").put("name", "task");
        place.putNull("pullRequest");
        place.putNull("ownPullRequest");
        ((tools.jackson.databind.node.ArrayNode) run.get("offers")).add(offer);
        run.putArray("placeOffers").add(rework);
        final String sound = json.writeValueAsString(document);
        Files.writeString(file, sound);
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            assertThat(run(records).placeOffers()).as("read back").containsExactly(rework);
            run.remove("placeOffers");
            Files.writeString(file, json.writeValueAsString(document));
            records.forget("project");
            assertThat(run(records).placeOffers()).as("an older record names none").isEmpty();
            final String main = "00000000-0000-0000-0000-000000000002";
            for (final List<String> named :
                    List.of(
                            List.of(rework, rework),
                            List.of("00000000-0000-0000-0000-000000000009"),
                            List.of(main))) {
                final var tampered = json.readTree(sound);
                final ObjectNode at = (ObjectNode) tampered.path("runs").iterator().next();
                final var list = at.putArray("placeOffers");
                named.forEach(list::add);
                Files.writeString(file, json.writeValueAsString(tampered));
                records.forget("project");
                assertThatThrownBy(() -> run(records))
                        .as("refused: " + named)
                        .isInstanceOf(WorkRefusal.class);
            }
        }
    }

    @Test
    void aKeptPlaceThatDoesNotFitItsRunIsRefusedAsTheRecordOpens() throws Exception {
        // Harm: a record changed on disk trusted: a place of an issue the run does not hold, an
        // author run that is no ended or void builder's offer of the issue in a container under
        // the place's task, one that stages a result or reviews, or a kept answer whose count says
        // fewer points stand open than it keeps.
        writePlaceRecord();
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final String sound = Files.readString(file);
        final List<java.util.function.Consumer<ObjectNode>> tampers =
                List.of(
                        run -> {
                            final ObjectNode places = (ObjectNode) run.get("places");
                            places.set("AA9", places.remove("AA1"));
                        },
                        run ->
                                place(run)
                                        .putArray("authorRuns")
                                        .add("00000000-0000-0000-0000-000000000009"),
                        run -> place(run).put("task", "other"),
                        run ->
                                ((ObjectNode) run.get("offers").get(0).get("binding"))
                                        .put("role", "READER"),
                        run -> ((ObjectNode) run.get("offers").get(0)).putNull("containerId"),
                        run -> ((ObjectNode) place(run).get("last")).put("pointsTotal", 0),
                        run ->
                                ((ObjectNode) run.get("offers").get(0))
                                        .put("status", "VOID")
                                        .putNull("containerId"),
                        run ->
                                ((ObjectNode) run.get("offers").get(0).get("binding").get("rule"))
                                        .put("result", "ANALYSIS"),
                        run ->
                                ((ObjectNode) run.get("offers").get(0).get("arguments"))
                                        .put("runRole", "REVIEWER"),
                        run ->
                                ((ObjectNode) run.get("offers").get(0))
                                        .put("status", "VOID")
                                        .putNull("ended"),
                        run ->
                                ((ObjectNode) run.get("offers").get(0))
                                        .put("status", "STARTED")
                                        .putNull("ended"),
                        run -> {
                            final ObjectNode offer = (ObjectNode) run.get("offers").get(0);
                            ((ObjectNode) offer.get("binding")).putArray("issues").add("AA2");
                            ((ObjectNode) offer.get("arguments")).putArray("issues").add("AA2");
                        });
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            for (int i = 0; i < tampers.size(); i++) {
                final var document = json.readTree(sound);
                tampers.get(i).accept((ObjectNode) document.path("runs").iterator().next());
                Files.writeString(file, json.writeValueAsString(document));
                records.forget("project");
                assertThatThrownBy(() -> run(records))
                        .as("tamper " + i)
                        .isInstanceOf(WorkRefusal.class);
            }
        }
    }

    @Test
    void aCarriedVoidAuthorRunWithItsEndOpensAgainAndAnOfferEndsAsItsStatusSays() throws Exception {
        // Harm: a run that replaced another carries that one's author run void with its end, and
        // the project's work record no longer opens after a restart; or an offer whose end
        // contradicts its status is trusted.
        final WorkRun.Run written = writePlaceRecord("VOID");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final String sound = Files.readString(file);
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            assertThat(run(records))
                    .as("the run with its void author run read back as written")
                    .isEqualTo(written);
            final List<java.util.function.Consumer<ObjectNode>> tampers =
                    List.of(
                            offer -> offer.put("status", "STARTED"),
                            offer -> offer.put("status", "ENDED").putNull("ended"));
            for (int i = 0; i < tampers.size(); i++) {
                final var document = json.readTree(sound);
                final ObjectNode run = (ObjectNode) document.path("runs").iterator().next();
                // No place holds the offer to its status here, only the offer's own check.
                run.putObject("places");
                tampers.get(i).accept((ObjectNode) run.get("offers").get(0));
                Files.writeString(file, json.writeValueAsString(document));
                records.forget("project");
                assertThatThrownBy(() -> run(records))
                        .as("tamper " + i)
                        .isInstanceOf(WorkRefusal.class);
            }
        }
    }

    private static ObjectNode place(final ObjectNode run) {
        return (ObjectNode) run.get("places").get("AA1");
    }

    private static WorkRun.Run run(final WorkRecord records) {
        return records.open("project", "https://example.org/store.git", null)
                .locked(session -> session.read().runs().values().iterator().next());
    }

    /** A run whose builder of AA1 ended with its work waiting, kept with an answer read there. */
    private WorkRun.Run writePlaceRecord() {
        return writePlaceRecord("ENDED");
    }

    /**
     * A run whose builder of AA1, its offer in the status given, ended with its work waiting, kept
     * with an answer read there.
     */
    private WorkRun.Run writePlaceRecord(final String status) {
        final String story = "ST1";
        final var rule =
                new WorkRule(
                        List.of("Backlog"),
                        "Review",
                        null,
                        null,
                        WorkRule.Per.REPOSITORY,
                        List.of("AGENT"),
                        "Build",
                        Map.of("codex", "model"));
        final String id = "00000000-0000-0000-0000-000000000002";
        final var offer =
                new WorkRun.Offer(
                        id,
                        new WorkOrder.Task(
                                "alpha",
                                List.of("AA1"),
                                "MAIN",
                                "codex",
                                "model",
                                null,
                                "AGENT",
                                "Build"),
                        new NextStep.Binding(
                                story, "alpha", List.of("AA1"), "MAIN", "Building", "AGENT", rule),
                        status,
                        "task",
                        "box",
                        "workstation",
                        java.time.Instant.EPOCH.toString(),
                        new WorkRun.Ended("2026-01-01T00:00:00Z", true, "", "Built", 0),
                        "",
                        List.of(),
                        List.of(),
                        null);
        final java.time.Instant since = java.time.Instant.parse("2026-01-01T00:00:00Z");
        final IssuePlace place =
                IssuePlace.atGate("task", id)
                        .read(
                                new ReviewOpen(
                                        "0123456789abcdef0123456789abcdef01234567",
                                        ReviewOpen.Decision.RETURNED,
                                        null,
                                        null,
                                        since,
                                        List.of(
                                                new ReviewOpen.Point(
                                                        "thread",
                                                        "a.txt",
                                                        3,
                                                        "owner",
                                                        since.plusSeconds(5))),
                                        0,
                                        0,
                                        true,
                                        null,
                                        "v1"));
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
                        List.of(),
                        java.time.Instant.EPOCH.toString(),
                        List.of(offer),
                        Map.of("AA1", NextStep.Progress.start(2)),
                        false,
                        false,
                        null,
                        Map.of("AA1", place));
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

    private static WorkOrder.Task arguments(final WorkRecord records) {
        return records.open("project", "https://example.org/store.git", null)
                .locked(session -> session.read().runs().values().iterator().next())
                .offers()
                .getFirst()
                .arguments();
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
                        2,
                        "");
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
    void aRecordWrittenBeforeSavesWereNamedOpensAndTheRepairAtOpenSettlesItsMarks()
            throws Exception {
        // Harm: a record that a save left pending before saves were named refused as malformed,
        // so the owner account's runs are lost, or its marks kept pending for good.
        final String offer = "00000000-0000-0000-0000-000000000003";
        final WorkRun.Run run = writeTaskRecord("workstation");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final ObjectNode document = (ObjectNode) json.readTree(Files.readString(file));
        ((ObjectNode) document.path("runs").path(run.story())).put("pending", true);
        final ObjectNode handed = document.putObject("results").putObject(offer);
        handed.put("status", HandedResults.PENDING);
        handed.put("task", "task");
        handed.putArray("issues").add("AA1");
        handed.putArray("faults");
        handed.put("reason", "");
        handed.put("order", 1);
        Files.writeString(file, json.writeValueAsString(document));
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final WorkRun.Record record =
                    records.open("project", "https://example.org/store.git", null);
            final WorkRun.Snapshot older = record.locked(WorkRun.Session::read);
            assertThat(older.runs().get(run.story()).pending())
                    .as("the bare mark, read as a mark naming no save")
                    .isEqualTo(new WorkRun.PendingSave("", List.of()));
            assertThat(older.results().get(offer).save())
                    .as("the pending result, naming no save")
                    .isEmpty();

            new WorkRun(new NoStore(), record, java.time.Clock.systemUTC()).repair();

            final WorkRun.Snapshot repaired = record.latest();
            assertThat(repaired.runs().get(run.story()).halted()).as("the run halted").isTrue();
            assertThat(repaired.runs().get(run.story()).pending()).as("no mark left").isNull();
            assertThat(repaired.results().get(offer).status())
                    .as("the result refused")
                    .isEqualTo(HandedResults.REFUSED);
        }
    }

    @Test
    void aForgedMarkIsRefusedAtOpenAndAMarkTheRecordWritesOpens() throws Exception {
        // Harm: a work.json edited by hand or another program carrying a mark the planner never
        // writes, named in logs and answers as if it were a save of its own.
        final WorkRun.Run run = writeTaskRecord("workstation");
        final Path file = directory.resolve("porelo-planner/project/work.json");
        final JsonMapper json = JsonMapper.builder().build();
        final String written = Files.readString(file);
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        final String uuid = "00000000-0000-0000-0000-00000000000b";
        final java.util.function.BiFunction<String, List<String>, Boolean> opens =
                (id, items) -> {
                    try {
                        final ObjectNode document = (ObjectNode) json.readTree(written);
                        final ObjectNode mark =
                                ((ObjectNode) document.path("runs").path(run.story()))
                                        .putObject("pending");
                        if (id == null) {
                            mark.putNull("id");
                        } else {
                            mark.put("id", id);
                        }
                        final var array = mark.putArray("items");
                        items.forEach(array::add);
                        Files.writeString(file, json.writeValueAsString(document));
                        try (WorkRecord records = new WorkRecord(environment, false)) {
                            records.open("project", "https://example.org/store.git", null)
                                    .locked(WorkRun.Session::read);
                            return true;
                        } catch (final WorkRefusal refused) {
                            assertThat(refused.getMessage())
                                    .as("the refusal")
                                    .contains("fails its check");
                            return false;
                        }
                    } catch (final java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                };

        assertThat(opens.apply(uuid, List.of("AA1", "ST1"))).as("a save's mark").isTrue();
        assertThat(opens.apply("", List.of())).as("a mark from before saves were named").isTrue();
        assertThat(opens.apply(null, List.of("AA1"))).as("no id").isFalse();
        assertThat(opens.apply("save-1", List.of("AA1"))).as("an id that is no UUID").isFalse();
        assertThat(opens.apply("", List.of("AA1"))).as("no id, but items").isFalse();
        assertThat(opens.apply(uuid, List.of("AA\u00071"))).as("a control character").isFalse();
    }

    @Test
    void aReaderSeesEveryRunButOneWhoseSaveIsInFlight() throws Exception {
        // Harm: one save in flight freezing what readers see of every other story's run.
        final WorkRun.Run run = writeTaskRecord("workstation");
        final WorkRun.Run other =
                new WorkRun.Run(
                        "00000000-0000-0000-0000-000000000009",
                        "ST9",
                        run.to(),
                        run.mode(),
                        run.comment(),
                        run.coordinator(),
                        run.readingRounds(),
                        run.issueReadingRounds(),
                        run.repositories(),
                        run.since(),
                        List.of(),
                        Map.of(),
                        false,
                        false,
                        null);
        final WorkRun.Run marked =
                new WorkRun.Run(
                        run.id(),
                        run.story(),
                        run.to(),
                        run.mode(),
                        run.comment(),
                        run.coordinator(),
                        run.readingRounds(),
                        run.issueReadingRounds(),
                        run.repositories(),
                        run.since(),
                        run.offers(),
                        run.issues(),
                        run.stopped(),
                        run.halted(),
                        new WorkRun.PendingSave(
                                "00000000-0000-0000-0000-00000000000a", List.of("AA1")));
        try (WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false)) {
            final WorkRun.Record record =
                    records.open("project", "https://example.org/store.git", null);
            record.locked(
                    session -> {
                        session.read();
                        session.write(
                                new WorkRun.Snapshot(2, Map.of(run.story(), marked, "ST9", other)));
                        return true;
                    });

            final WorkRun.Snapshot seen = record.latest();
            assertThat(seen.runs().get("ST9"))
                    .as("the other story's run, as written")
                    .isEqualTo(other);
            assertThat(seen.runs().get(run.story()))
                    .as("the run whose save is in flight, as last settled")
                    .isEqualTo(run);
        }
    }

    /** A store no call of these tests reaches. */
    private static final class NoStore implements org.fuin.porelo.planner.store.PlanningStore {
        @Override
        public org.fuin.porelo.planner.store.Board board() {
            throw new UnsupportedOperationException("not read");
        }

        @Override
        public org.fuin.porelo.planner.store.Board refresh() {
            throw new UnsupportedOperationException("not read");
        }
    }

    @Test
    void takenReportsSurviveRestartAndARecordWrittenBeforeThemReads() throws Exception {
        // Harm: a lost mark or key saves a report's idea a second time after a restart; an older
        // record that no longer opens stops all work.
        final String offer = "4f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f";
        final String key = "porelo-project_talk#3";
        final HandedIdeas.Handed done =
                new HandedIdeas.Handed(HandedIdeas.DONE, "ST7", List.of(), 1);
        final ReportTaking.Followed followed =
                new ReportTaking.Followed("2026-11-25T08:00:00Z", 2, false);
        final ReportTaking.Mark mark = new ReportTaking.Mark(3, 3);
        final var environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false)) {
            records.open("project", "https://example.org/store.git", null)
                    .locked(
                            session -> {
                                session.write(
                                        new WorkRun.Snapshot(
                                                1,
                                                Map.of(),
                                                Map.of(key, done),
                                                Map.of(),
                                                Map.of(offer, followed),
                                                Map.of("porelo-project_talk", mark)));
                                return true;
                            });
        }
        final Path file = directory.resolve("porelo-planner/project/work.json");
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final WorkRun.Snapshot restored =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read());
            assertThat(restored.ideas())
                    .as("the report's story")
                    .containsExactly(Map.entry(key, done));
            assertThat(restored.followed())
                    .as("the followed offer")
                    .containsExactly(Map.entry(offer, followed));
            assertThat(restored.reports())
                    .as("the mark")
                    .containsExactly(Map.entry("porelo-project_talk", mark));
        }
        final JsonMapper json = JsonMapper.builder().build();
        final ObjectNode document = (ObjectNode) json.readTree(Files.readString(file));
        assertThat(document.has("followed")).as("the record holds the followed offers").isTrue();
        document.remove(List.of("ideas", "followed", "reports"));
        document.putObject("ideas")
                .putObject(offer)
                .put("status", HandedIdeas.DONE)
                .put("story", "ST7")
                .put("order", 1)
                .putArray("pushed");
        Files.writeString(file, json.writeValueAsString(document));
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final WorkRun.Snapshot read =
                    records.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read());
            assertThat(read.ideas())
                    .as("an idea kept under a discussion's offer, from the hand-in, is dropped")
                    .isEmpty();
            assertThat(read.followed()).as("a record from before follows nothing").isEmpty();
            assertThat(read.reports()).as("and has taken no report").isEmpty();
        }
        final Map<String, java.util.function.Consumer<ObjectNode>> forgeries =
                new java.util.LinkedHashMap<>();
        forgeries.put(
                "an idea whose story fails its check",
                forged ->
                        forged.putObject("ideas")
                                .putObject(key)
                                .put("status", HandedIdeas.DONE)
                                .put("story", "../ST7")
                                .put("order", 1)
                                .putArray("pushed"));
        forgeries.put(
                "an idea under a key of no report",
                forged ->
                        forged.putObject("ideas")
                                .putObject("../escape")
                                .put("status", HandedIdeas.DONE)
                                .put("story", "ST7")
                                .put("order", 1)
                                .putArray("pushed"));
        forgeries.put(
                "a followed offer that is no offer",
                forged ->
                        forged.putObject("followed")
                                .putObject("../escape")
                                .put("since", "2026-11-25T08:00:00Z")
                                .put("order", 1)
                                .put("closed", false));
        forgeries.put(
                "a followed offer since a time of no zone",
                forged ->
                        forged.putObject("followed")
                                .putObject(offer)
                                .put("since", "2026-11-25T08:00:00")
                                .put("order", 1)
                                .put("closed", false));
        forgeries.put(
                "a mark of no task's name",
                forged ->
                        forged.putObject("reports")
                                .putObject("../escape")
                                .put("seq", 1)
                                .put("order", 1));
        forgeries.put(
                "a mark below one",
                forged ->
                        forged.putObject("reports")
                                .putObject("porelo-project_talk")
                                .put("seq", 0)
                                .put("order", 1));
        for (final Map.Entry<String, java.util.function.Consumer<ObjectNode>> forgery :
                forgeries.entrySet()) {
            final ObjectNode forged = document.deepCopy();
            forgery.getValue().accept(forged);
            Files.writeString(file, json.writeValueAsString(forged));
            try (WorkRecord records = new WorkRecord(environment, false)) {
                final WorkRefusal refused =
                        catchThrowableOfType(
                                WorkRefusal.class,
                                () ->
                                        records.open(
                                                        "project",
                                                        "https://example.org/store.git",
                                                        null)
                                                .locked(session -> session.read()));
                assertThat(refused).as("%s is refused", forgery.getKey()).isNotNull();
                assertThat(reason(refused)).as("its words").doesNotContain("escape");
            }
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
                        null);
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
                        null);
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
                // The record is read once, at open, so it is opened anew to read the change.
                records.forget("project");
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
        }
        try (WorkRecord records = new WorkRecord(environment, false)) {
            final WorkRefusal refused =
                    catchThrowableOfType(
                            WorkRefusal.class,
                            () -> records.open("project", "https://example.org/store.git", null));
            assertThat(refused)
                    .as("duplicate or unreadable JSON cannot reset the record")
                    .isNotNull();
            assertThat(reason(refused))
                    .as("the reason says the record is not valid JSON, quoting none of it")
                    .contains("JSON")
                    .doesNotContain("version");
            assertThat(
                            catchThrowableOfType(
                                    WorkRefusal.class,
                                    () ->
                                            records.open(
                                                    "project",
                                                    "https://example.org/store.git",
                                                    null)))
                    .as("a refused open lets its file guard go, so the same refusal comes again")
                    .isNotNull()
                    .satisfies(again -> assertThat(reason(again)).contains("JSON"));
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

    @Test
    void aSecondRecordOnTheSameStateDirectoryIsRefusedWhenItOpens() {
        // Prevents two planners serving one project from both writing work.json and losing runs.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord first = new WorkRecord(environment, false);
                WorkRecord second = new WorkRecord(environment, false)) {
            first.open("project", "https://example.org/store.git", null);
            final WorkRefusal refused =
                    catchThrowableOfType(
                            WorkRefusal.class,
                            () -> second.open("project", "https://example.org/store.git", null));
            assertThat(refused)
                    .as("the second record is refused at open, before it answers anything")
                    .isNotNull();
            assertThat(reason(refused))
                    .as("the refusal names the planner holding the record")
                    .contains("Another planner holds the work record of project");
            assertThat(directory.resolve("porelo-planner/project/work.json"))
                    .as("the refused record wrote nothing")
                    .doesNotExist();
            first.forget("project");
            final Map<String, WorkRun.Run> runs =
                    second.open("project", "https://example.org/store.git", null)
                            .locked(s -> s.read().runs());
            assertThat(runs).as("the guard is let go when the first record closes").isEmpty();
        }
    }

    @Test
    void aSaveInFlightIsRecordedBeforeTheGuardIsLetGo() throws Exception {
        // Prevents a save cut off by the close, its run lost, and a guard let go while the old
        // record still writes.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(environment, false, Duration.ofSeconds(10));
                var executor = Executors.newFixedThreadPool(3)) {
            final WorkRun.Record record =
                    records.open("project", "https://example.org/store.git", null);
            final var saving = executor.submit(() -> heldSave(record, entered, release, 4));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            final var forgetting = ThreadPoints.run(() -> records.forget("project"));
            forgetting.reaches("drain");
            try (WorkRecord second = new WorkRecord(environment, false)) {
                assertThat(
                                catchThrowableOfType(
                                        WorkRefusal.class,
                                        () ->
                                                second.open(
                                                        "project",
                                                        "https://example.org/store.git",
                                                        null)))
                        .as("the guard is held while the save is in flight")
                        .isNotNull();
            }
            assertThat(forgetting.task().isDone()).as("the close waits for the save").isFalse();
            release.countDown();
            assertThat(saving.get(5, TimeUnit.SECONDS)).as("the save was recorded").isTrue();
            forgetting.task().get(5, TimeUnit.SECONDS);
        }
        try (WorkRecord after = new WorkRecord(environment, false)) {
            assertThat(heldGeneration(after.open("project", "https://example.org/store.git", null)))
                    .as("the save's generation is on the disk once the guard is let go")
                    .isEqualTo(4);
        }
    }

    @Test
    void anotherProjectOpensWhileOneWaitsForItsSave() throws Exception {
        // Prevents one project's slow save stopping every other project's opening.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(environment, false, Duration.ofSeconds(10));
                var executor = Executors.newFixedThreadPool(3)) {
            final WorkRun.Record record =
                    records.open("slow", "https://example.org/store.git", null);
            final var saving = executor.submit(() -> heldSave(record, entered, release, 2));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            final var forgetting = ThreadPoints.run(() -> records.forget("slow"));
            forgetting.reaches("drain");
            try {
                final long other =
                        executor.submit(
                                        () -> {
                                            final WorkRun.Record opened =
                                                    records.open(
                                                            "other",
                                                            "https://example.org/store.git",
                                                            null);
                                            opened.locked(
                                                    session -> {
                                                        session.write(
                                                                new WorkRun.Snapshot(3, Map.of()));
                                                        return null;
                                                    });
                                            return heldGeneration(opened);
                                        })
                                .get(2, TimeUnit.SECONDS);
                assertThat(other)
                        .as("the other project opened and answered meanwhile")
                        .isEqualTo(3);
                assertThat(forgetting.task().isDone()).as("the slow one still waits").isFalse();
            } finally {
                release.countDown();
            }
            saving.get(5, TimeUnit.SECONDS);
            forgetting.task().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aSaveThatDoesNotEndRefusesTheCloseNamingTheProjectAndKeepsTheGuard() throws Exception {
        // Prevents a hung save making the close let go of the guard while the save still writes,
        // an entry being closed handed out again, and a refusal that does not say which project.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(environment, false, Duration.ofMillis(200));
                var executor = Executors.newFixedThreadPool(2)) {
            final WorkRun.Record record =
                    records.open("project", "https://example.org/store.git", null);
            final var saving = executor.submit(() -> heldSave(record, entered, release, 4));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                final WorkRefusal refused =
                        catchThrowableOfType(WorkRefusal.class, () -> records.forget("project"));
                assertThat(refused).as("the close gives up after its bound").isNotNull();
                assertThat(refused.parameters()).containsEntry("project", "project");
                assertThat(reason(refused))
                        .as("the refusal says the save did not end in time")
                        .contains("A save of project did not end within 0.2 s");
                assertThat(
                                catchThrowableOfType(
                                        WorkRefusal.class,
                                        () ->
                                                records.open(
                                                        "project",
                                                        "https://example.org/store.git",
                                                        null)))
                        .as("the entry being closed is not handed out again")
                        .isNotNull();
                try (WorkRecord second = new WorkRecord(environment, false)) {
                    assertThat(
                                    catchThrowableOfType(
                                            WorkRefusal.class,
                                            () ->
                                                    second.open(
                                                            "project",
                                                            "https://example.org/store.git",
                                                            null)))
                            .as("the guard is kept while the save may still write")
                            .isNotNull();
                }
            } finally {
                release.countDown();
            }
            assertThat(saving.get(5, TimeUnit.SECONDS))
                    .as("the save given up on still ends and is recorded")
                    .isTrue();
            records.forget("project");
        }
        try (WorkRecord after = new WorkRecord(environment, false)) {
            assertThat(heldGeneration(after.open("project", "https://example.org/store.git", null)))
                    .isEqualTo(4);
        }
    }

    @Test
    void aCallerWaitingForTheLockIsRefusedOnceClosingBegins() throws Exception {
        // Prevents a caller that waits for the lock, holding monitors of its own, from waiting
        // for a save the close gave up on, and a new caller from queueing behind it.
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofMillis(200));
                var executor = Executors.newFixedThreadPool(3)) {
            final WorkRun.Record record =
                    records.open("project", "https://example.org/store.git", null);
            final var saving = executor.submit(() -> heldSave(record, entered, release, 4));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                final var waiting = ThreadPoints.start(() -> heldGeneration(record));
                waiting.reaches("enter");
                assertThat(catchThrowableOfType(WorkRefusal.class, () -> records.forget("project")))
                        .isNotNull();
                assertThat(
                                catchThrowableOfType(
                                        java.util.concurrent.ExecutionException.class,
                                        () -> waiting.task().get(2, TimeUnit.SECONDS)))
                        .as("the waiting caller is refused, not left waiting")
                        .hasCauseInstanceOf(WorkRefusal.class);
                assertThat(catchThrowableOfType(WorkRefusal.class, () -> heldGeneration(record)))
                        .as("a new caller is refused at once")
                        .isNotNull();
            } finally {
                release.countDown();
            }
            saving.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anOpenDuringACloseWaitsForItAndAnswersANewEntry() throws Exception {
        // Prevents an open slipping past a close under way and serving the project beside the
        // old entry, or failing on the guard the old one still holds.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(environment, false, Duration.ofSeconds(10));
                var executor = Executors.newFixedThreadPool(3)) {
            final WorkRun.Record record =
                    records.open("project", "https://example.org/store.git", null);
            final var saving = executor.submit(() -> heldSave(record, entered, release, 4));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            final var forgetting = ThreadPoints.run(() -> records.forget("project"));
            forgetting.reaches("drain");
            final var opening =
                    ThreadPoints.start(
                            () -> records.open("project", "https://example.org/store.git", null));
            try {
                opening.reaches("open");
                assertThat(opening.task().isDone())
                        .as("the open waits for the close under way")
                        .isFalse();
            } finally {
                release.countDown();
            }
            saving.get(5, TimeUnit.SECONDS);
            forgetting.task().get(5, TimeUnit.SECONDS);
            final WorkRun.Record reopened = opening.task().get(5, TimeUnit.SECONDS);
            assertThat(reopened).as("a new entry, never the closed one").isNotSameAs(record);
            assertThat(heldGeneration(reopened))
                    .as("the new entry reads what the save recorded")
                    .isEqualTo(4);
        }
    }

    /** A save that writes its generation once released, answering whether it was recorded. */
    private static boolean heldSave(
            final WorkRun.Record record,
            final CountDownLatch entered,
            final CountDownLatch release,
            final long generation) {
        return record.locked(
                session -> {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    session.write(new WorkRun.Snapshot(generation, Map.of()));
                    return true;
                });
    }

    @Test
    void aStepIsAnsweredWhileAWriterWorksBetweenItsWrites() throws Exception {
        // Prevents every record step waiting behind one caller's long work, such as a store save.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(environment, false);
                var executor = Executors.newFixedThreadPool(2)) {
            final WorkRecord.Entry record = entry(records);
            final var working =
                    executor.submit(
                            () ->
                                    record.locked(
                                            session -> {
                                                session.write(new WorkRun.Snapshot(1, Map.of()));
                                                entered.countDown();
                                                await(release);
                                                return true;
                                            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                final long generation =
                        executor.submit(
                                        () -> {
                                            final WorkRecord.Mark mark =
                                                    record.mark("save-1", with(run("ST1", true)));
                                            record.outcome(mark, with(run("ST1", false)));
                                            return record.latest().generation();
                                        })
                                .get(2, TimeUnit.SECONDS);
                assertThat(generation)
                        .as("a mark and its outcome are written while the writer works")
                        .isEqualTo(3);
            } finally {
                release.countDown();
            }
            assertThat(
                            catchThrowableOfType(
                                    java.util.concurrent.ExecutionException.class,
                                    () -> working.get(5, TimeUnit.SECONDS)))
                    .as("the writer ends; it wrote before the steps and not after")
                    .isNull();
        }
    }

    @Test
    void aStepChangesTheRecordAsItNowStandsAndMovesItsGenerationOn() {
        // Prevents an outcome overwriting a write made between its mark and itself, a judgment
        // made from a generation that lags a pending run, and a step writing a generation that
        // does not move on.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false, Duration.ofMillis(200))) {
            final WorkRecord.Entry record = entry(records);
            final WorkRecord.Mark first = record.mark("save-1", with(run("ST1", true)));
            final WorkRecord.Mark second = record.mark("save-2", with(run("ST2", true)));
            final WorkRun.Snapshot after =
                    record.outcome(
                            first,
                            snapshot ->
                                    new WorkRun.Snapshot(
                                            0,
                                            withRun(snapshot, run("ST1", false)).runs(),
                                            snapshot.ideas(),
                                            snapshot.results()));
            assertThat(after.runs())
                    .as("the other save's mark, written after this one's, stands")
                    .containsKeys("ST1", "ST2");
            assertThat(after.runs().get("ST1").pending()).isNull();
            assertThat(after.generation())
                    .as("each step writes the next generation, whatever its change says")
                    .isEqualTo(3);
            record.abandon(second);
        }
        try (WorkRecord again = new WorkRecord(environment, false)) {
            assertThat(heldGeneration(again.open("project", "https://example.org/store.git", null)))
                    .as("the next generation is the one on the disk")
                    .isEqualTo(3);
        }
    }

    @Test
    void aWriterIsRefusedOnlyWhenAStepWroteItsStorySinceItEnteredOrWrote() throws Exception {
        // Prevents a writer overwriting a step made on its story while it worked, without
        // refusing a writer that writes the same or a lower generation, as a rollback does.
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofMillis(200));
                var executor = Executors.newFixedThreadPool(2)) {
            final WorkRecord.Entry record = entry(records);
            record.locked(
                    session -> {
                        session.write(new WorkRun.Snapshot(5, Map.of()));
                        session.write(new WorkRun.Snapshot(5, Map.of()));
                        session.write(new WorkRun.Snapshot(4, Map.of()));
                        return null;
                    });
            assertThat(record.latest().generation())
                    .as("a writer's own same and lower generations are taken")
                    .isEqualTo(4);
            final var working =
                    executor.submit(
                            () ->
                                    record.locked(
                                            session -> {
                                                final WorkRun.Snapshot read = session.read();
                                                entered.countDown();
                                                await(release);
                                                session.write(withRun(read, run("ST1", false)));
                                                return null;
                                            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            record.mark("save-1", with(run("ST1", true)));
            release.countDown();
            final Throwable refused =
                    catchThrowableOfType(
                                    java.util.concurrent.ExecutionException.class,
                                    () -> working.get(5, TimeUnit.SECONDS))
                            .getCause();
            assertThat(refused).isInstanceOf(WorkRefusal.class);
            assertThat(((WorkRefusal) refused).name()).isEqualTo("RunChanged");
            assertThat(((WorkRefusal) refused).parameters())
                    .as("the refusal names the run the step changed")
                    .containsEntry("story", "ST1");
            assertThat(record.latest().runs()).as("the step's mark stands").containsOnlyKeys("ST1");
        }
    }

    @Test
    void aStepOnAnotherStoryIsNoConflictAndBothChangesStand() throws Exception {
        // Harm: a task's report refused as changed because another story's save was marked or
        // settled while the report was judged.
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofMillis(200));
                var executor = Executors.newFixedThreadPool(2)) {
            final WorkRecord.Entry record = entry(records);
            final var working =
                    executor.submit(
                            () ->
                                    record.locked(
                                            session -> {
                                                final WorkRun.Snapshot read = session.read();
                                                entered.countDown();
                                                await(release);
                                                session.write(withRun(read, run("ST9", false)));
                                                return null;
                                            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            final WorkRecord.Mark mark = record.mark("save-1", with(run("ST1", true)));
            record.outcome(mark, with(run("ST1", false)));
            final long settled = record.latest().generation();
            release.countDown();
            working.get(5, TimeUnit.SECONDS);
            assertThat(record.latest().runs())
                    .as("the writer's run and the other story's settled run both stand")
                    .containsOnlyKeys("ST1", "ST9");
            assertThat(record.latest().runs().get("ST1").pending())
                    .as("the other story as its outcome left it")
                    .isNull();
            assertThat(record.latest().generation())
                    .as("the write comes after the steps")
                    .isGreaterThan(settled);
        }
    }

    private static final String OFFER = "4f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f";

    private static WorkRun.Snapshot withReading(final WorkRun.Snapshot snapshot) {
        return snapshot.withReading(
                snapshot.generation() + 1,
                Map.of(OFFER, new ReportTaking.Followed("2026-11-25T08:00:00Z", 1, false)),
                Map.of("porelo-project_talk", new ReportTaking.Mark(3, 1)));
    }

    private static java.util.function.UnaryOperator<WorkRun.Snapshot> runOnly(
            final WorkRun.Run run) {
        return snapshot -> {
            final Map<String, WorkRun.Run> runs = new java.util.HashMap<>(snapshot.runs());
            runs.put(run.story(), run);
            return snapshot.withRuns(snapshot.generation(), runs);
        };
    }

    @Test
    void aRunSavedAfterReportsWereTakenKeepsTheFollowedOffersAndMarks() throws Exception {
        // Harm: a save of a run drops the followed offers and report marks, so reported ideas
        // are read again and an offer's task is never read.
        try (WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofMillis(200))) {
            final WorkRecord.Entry record = entry(records);
            record.locked(
                    session -> {
                        session.write(withReading(session.read()));
                        return null;
                    });

            final WorkRecord.Mark mark = record.mark("save-1", runOnly(run("ST1", true)));
            record.outcome(mark, runOnly(run("ST1", false)));

            assertThat(record.latest().followed()).as("the offer").containsKey(OFFER);
            assertThat(record.latest().reports()).as("the mark").containsKey("porelo-project_talk");
            assertThat(record.latest().runs()).as("and the run").containsKey("ST1");
        }
    }

    @Test
    void aReportTakingsWriteStandsBesideARunSavedWhileItWasJudged() throws Exception {
        // Harm: the followed offers and marks a reading wrote are lost to a run's save made while
        // it read, or the run's save is lost to the reading's write.
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofMillis(200));
                var executor = Executors.newFixedThreadPool(2)) {
            final WorkRecord.Entry record = entry(records);
            final var reading =
                    executor.submit(
                            () ->
                                    record.locked(
                                            session -> {
                                                final WorkRun.Snapshot read = session.read();
                                                entered.countDown();
                                                await(release);
                                                session.write(withReading(read));
                                                return null;
                                            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            final WorkRecord.Mark mark = record.mark("save-1", runOnly(run("ST1", true)));
            record.outcome(mark, runOnly(run("ST1", false)));
            release.countDown();
            reading.get(5, TimeUnit.SECONDS);

            assertThat(record.latest().runs()).as("the run saved meanwhile").containsKey("ST1");
            assertThat(record.latest().followed()).as("the offer written").containsKey(OFFER);
            assertThat(record.latest().reports())
                    .as("the mark written")
                    .containsKey("porelo-project_talk");
        }
    }

    @Test
    void aCloseWaitsForOpenMarksAndRefusesNewOnes() throws Exception {
        // Prevents a save between its steps being cut off by the close, its outcome lost.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord records = new WorkRecord(environment, false, Duration.ofSeconds(10));
                var executor = Executors.newFixedThreadPool(2)) {
            final WorkRecord.Entry record = entry(records);
            final WorkRecord.Mark mark = record.mark("save-1", with(run("ST1", true)));
            final var forgetting = executor.submit(() -> records.forget("project"));
            assertThat(
                            catchThrowableOfType(
                                    TimeoutException.class,
                                    () -> forgetting.get(300, TimeUnit.MILLISECONDS)))
                    .as("the close waits for the open mark")
                    .isNotNull();
            assertThat(
                            catchThrowableOfType(
                                    WorkRefusal.class,
                                    () -> record.mark("save-2", with(run("ST2", true)))))
                    .as("no new mark while closing")
                    .isNotNull();
            record.outcome(mark, with(run("ST1", false)));
            forgetting.get(5, TimeUnit.SECONDS);
        }
        try (WorkRecord again = new WorkRecord(environment, false)) {
            final Map<String, WorkRun.Run> runs =
                    again.open("project", "https://example.org/store.git", null)
                            .locked(session -> session.read().runs());
            assertThat(runs.get("ST1").pending())
                    .as("the outcome recorded before the guard was let go")
                    .isNull();
        }
    }

    @Test
    void anEndedMarkEndsNothingMoreAndAFailedOutcomeLeavesItStanding() throws Exception {
        // Prevents an outcome written twice, a close counting a mark ended twice, and an outcome
        // lost on the disk but taken as ended.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final AtomicInteger moves = new AtomicInteger();
        final WorkRecord.Replace failsThird =
                (written, target) -> {
                    if (moves.incrementAndGet() == 3) {
                        throw new java.io.IOException("the disk is full");
                    }
                    Files.move(
                            written,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                };
        try (WorkRecord records =
                new WorkRecord(
                        environment,
                        false,
                        PrivateFiles.of(environment),
                        failsThird,
                        org.fuin.porelo.wire.LimitFactors.DEFAULT,
                        Duration.ofMillis(200))) {
            final WorkRecord.Entry record = entry(records);
            final WorkRecord.Mark abandoned = record.mark("save-1", with(run("ST1", true)));
            record.abandon(abandoned);
            record.abandon(abandoned);
            assertThat(record.outcome(abandoned, with(run("ST1", false))).generation())
                    .as("an ended mark writes no outcome")
                    .isEqualTo(1);
            final WorkRecord.Mark failing = record.mark("save-2", with(run("ST2", true)));
            assertThat(
                            catchThrowableOfType(
                                    WorkRefusal.class,
                                    () -> record.outcome(failing, with(run("ST2", false)))))
                    .as("the outcome's write fails")
                    .isNotNull();
            assertThat(heldGeneration(record))
                    .as("memory stays at the generation on the disk")
                    .isEqualTo(2);
            assertThat(catchThrowableOfType(WorkRefusal.class, () -> records.forget("project")))
                    .as("the failed outcome's mark still stands, so the close waits for it")
                    .isNotNull();
        }
    }

    @Test
    void aCloseWaitsForTheWriterAndTheMarksWithinOneBound() throws Exception {
        // Prevents a close waiting its bound once for the writer and again for each mark.
        final Duration bound = Duration.ofSeconds(2);
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (WorkRecord records = new WorkRecord(Map.of(), true, bound);
                var executor = Executors.newFixedThreadPool(2)) {
            final WorkRecord.Entry record = entry(records);
            record.mark("save-1", with(run("ST1", true)));
            final var working =
                    executor.submit(
                            () ->
                                    record.locked(
                                            session -> {
                                                entered.countDown();
                                                await(release);
                                                return null;
                                            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                final long started = System.nanoTime();
                final ThreadPoints<Void> closing =
                        ThreadPoints.run(() -> records.forget("project"));
                closing.reaches("drain");
                // The writer holds at least three quarters of the bound; a load that delays this
                // thread only makes it hold longer, which a restarted deadline shows the more.
                final long held = started + bound.toNanos() * 3 / 4 - System.nanoTime();
                if (held > 0) {
                    TimeUnit.NANOSECONDS.sleep(held);
                }
                final long releasedAt = System.nanoTime() - started;
                release.countDown();
                assertThat(
                                catchThrowableOfType(
                                        java.util.concurrent.ExecutionException.class,
                                        () -> closing.task().get(10, TimeUnit.SECONDS)))
                        .as("the close is refused once its bound ran out")
                        .hasCauseInstanceOf(WorkRefusal.class);
                final long elapsed = System.nanoTime() - started;
                // One bound in all ends near the bound; a bound per wait ends no earlier than the
                // writer's release plus a whole bound. Half the release time lies between.
                assertThat(TimeUnit.NANOSECONDS.toMillis(elapsed))
                        .as("one bound in all, not one per wait")
                        .isLessThan(TimeUnit.NANOSECONDS.toMillis(bound.toNanos() + releasedAt / 2))
                        .isLessThan(2 * bound.toMillis());
            } finally {
                release.countDown();
            }
            working.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aMarkNeedsItsOwnIdAndAnOutcomeItsOwnRecord() {
        // Prevents two saves sharing one mark, and a save ending another project's mark.
        try (WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofMillis(200))) {
            final WorkRecord.Entry record = entry(records);
            final WorkRecord.Entry other =
                    (WorkRecord.Entry) records.open("other", "https://example.org/store.git", null);
            assertThatThrownBy(() -> record.mark(" ", with(run("ST1", true))))
                    .isInstanceOf(IllegalArgumentException.class);
            final WorkRecord.Mark mark = record.mark("save-1", with(run("ST1", true)));
            assertThatThrownBy(() -> record.mark("save-1", with(run("ST2", true))))
                    .as("a second mark with an id in flight")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> other.outcome(mark, with(run("ST1", false))))
                    .as("another record's mark")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(other.latest().generation()).isZero();
        }
    }

    @Test
    void aCrashBetweenMarkAndOutcomeLeavesAWholePendingRunThatTheNextOpenRepairs()
            throws Exception {
        // Prevents a record half-written by a crash, and a pending run lost and never repaired.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final FileAttribute<?> own =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        final Path crashed = Files.createDirectory(directory.resolve("crashed"), own);
        try (WorkRecord records = new WorkRecord(environment, false, Duration.ofMillis(200))) {
            entry(records).mark("save-1", with(run("ST1", true)));
            // What a crash leaves: the record on the disk, with no outcome after the mark.
            Files.createDirectories(crashed.resolve("porelo-planner/project"), own);
            Files.copy(
                    directory.resolve("porelo-planner/project/work.json"),
                    crashed.resolve("porelo-planner/project/work.json"));
        }
        final Map<String, String> after = Map.of("XDG_STATE_HOME", crashed.toString());
        try (WorkRecord records = new WorkRecord(after, false)) {
            final WorkRun.Record record =
                    records.open("project", "https://example.org/store.git", null);
            final WorkRun.PendingSave pending =
                    record.locked(session -> session.read().runs().get("ST1").pending());
            assertThat(pending).as("the pending run is in the record whole").isNotNull();
            new WorkRun(
                            new org.fuin.porelo.planner.store.PlanningStore() {
                                @Override
                                public org.fuin.porelo.planner.store.Board board() {
                                    throw new UnsupportedOperationException();
                                }

                                @Override
                                public org.fuin.porelo.planner.store.Board refresh() {
                                    throw new UnsupportedOperationException();
                                }
                            },
                            record,
                            java.time.Clock.systemUTC(),
                            why -> {})
                    .repair();
            final WorkRun.Run repaired = record.locked(session -> session.read().runs().get("ST1"));
            assertThat(repaired.pending()).isNull();
            assertThat(repaired.halted()).as("the open halts the save cut off").isTrue();
        }
    }

    private static WorkRecord.Entry entry(final WorkRecord records) {
        return (WorkRecord.Entry) records.open("project", "https://example.org/store.git", null);
    }

    /** A run of the story, pending under a save's mark when asked. */
    private static WorkRun.Run run(final String story, final boolean pending) {
        return new WorkRun.Run(
                "00000000-0000-0000-0000-000000000001",
                story,
                "Building",
                "AGENT",
                "",
                "START",
                2,
                List.of(),
                List.of(),
                "2026-01-01T00:00:00Z",
                List.of(),
                Map.of(),
                false,
                false,
                pending
                        ? new WorkRun.PendingSave(
                                "00000000-0000-0000-0000-00000000000a", List.of("AA1"))
                        : null);
    }

    private static WorkRun.Snapshot withRun(
            final WorkRun.Snapshot snapshot, final WorkRun.Run run) {
        final Map<String, WorkRun.Run> runs = new java.util.HashMap<>(snapshot.runs());
        runs.put(run.story(), run);
        return new WorkRun.Snapshot(
                snapshot.generation(), runs, snapshot.ideas(), snapshot.results());
    }

    private static java.util.function.UnaryOperator<WorkRun.Snapshot> with(final WorkRun.Run run) {
        return snapshot -> withRun(snapshot, run);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long heldGeneration(final WorkRun.Record record) {
        return record.locked(session -> session.read().generation());
    }

    private static String reason(final WorkRefusal refused) {
        return String.valueOf(refused.parameters().get("reason"));
    }

    @Test
    void readersAndWritersAnswerFromMemoryAndReadNoFileAfterOpen() {
        // Prevents every board answer, preview and change event paying a disk read and a strict
        // parse of a record of up to 16 MiB.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        try (WorkRecord earlier = new WorkRecord(environment, false)) {
            earlier.open("project", "https://example.org/store.git", null)
                    .locked(
                            session -> {
                                session.write(new WorkRun.Snapshot(3, Map.of()));
                                return null;
                            });
        }
        final CountedFiles counted = new CountedFiles(PrivateFiles.of(environment));
        try (WorkRecord records = new WorkRecord(environment, false, counted)) {
            final WorkRun.Record record =
                    records.open("project", "https://example.org/store.git", null);
            assertThat(counted.recordChecks.get())
                    .as("the record on the disk is read once, at open")
                    .isEqualTo(1);
            counted.recordChecks.set(0);
            for (int i = 0; i < 20; i++) {
                assertThat(record.latest().generation()).isEqualTo(3);
                assertThat(heldGeneration(record)).isEqualTo(3);
            }
            assertThat(counted.recordChecks.get())
                    .as("no reader and no writer's read reaches the file after open")
                    .isZero();
            for (long generation = 4; generation < 9; generation++) {
                final long next = generation;
                record.locked(
                        session -> {
                            session.write(
                                    new WorkRun.Snapshot(
                                            session.read().generation() + 1, Map.of()));
                            return null;
                        });
                assertThat(record.latest().generation()).isEqualTo(next);
            }
            assertThat(counted.recordChecks.get())
                    .as("each write checks the file it replaces once, and nothing reads it")
                    .isEqualTo(5);
        }
    }

    @Test
    void aWriteThatFailsLeavesMemoryAtTheGenerationOnTheDisk() {
        // Prevents a held generation running ahead of the disk, so a failed save is answered as
        // made and lost at the next start.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final AtomicInteger moves = new AtomicInteger();
        final WorkRecord.Replace failsSecond =
                (written, target) -> {
                    if (moves.incrementAndGet() == 2) {
                        throw new java.io.IOException("the disk is full");
                    }
                    Files.move(
                            written,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                };
        try (WorkRecord records =
                new WorkRecord(environment, false, PrivateFiles.of(environment), failsSecond)) {
            final WorkRun.Record record =
                    records.open("project", "https://example.org/store.git", null);
            record.locked(
                    session -> {
                        session.write(new WorkRun.Snapshot(5, Map.of()));
                        return null;
                    });
            final WorkRefusal refused =
                    catchThrowableOfType(
                            WorkRefusal.class,
                            () ->
                                    record.locked(
                                            session -> {
                                                session.write(new WorkRun.Snapshot(6, Map.of()));
                                                return null;
                                            }));
            assertThat(refused).as("the failed write is refused").isNotNull();
            assertThat(heldGeneration(record))
                    .as("a writer reads the generation on the disk, not the one that failed")
                    .isEqualTo(5);
            assertThat(record.latest().generation())
                    .as("a reader is answered the generation on the disk")
                    .isEqualTo(5);
        }
    }

    @Test
    void aHeldRecordIsNotCheckedAgainOnEveryOperation() {
        // On Windows each check of the directories starts PowerShell, which takes up to seconds.
        final Map<String, String> environment = Map.of("XDG_STATE_HOME", directory.toString());
        final CountedFiles counted = new CountedFiles(PrivateFiles.of(environment));
        try (WorkRecord records = new WorkRecord(environment, false, counted)) {
            final WorkRun.Record store =
                    records.open("project", "https://example.org/store.git", null);
            for (int i = 0; i < 3; i++) {
                store.locked(session -> session.read());
            }
            for (int i = 0; i < 3; i++) {
                store.latest();
            }

            assertThat(counted.directories.get())
                    .as(
                            "the directories are checked when the lock is taken, not again while it is held"
                                    + " nor by a reader")
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

    /** Counts the directory checks and the record file checks each read and write makes. */
    private static final class CountedFiles implements PrivateFiles {
        private final PrivateFiles real;
        private final AtomicInteger directories = new AtomicInteger();
        private final AtomicInteger recordChecks = new AtomicInteger();

        private CountedFiles(final PrivateFiles real) {
            this.real = real;
        }

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
                final Path base, final boolean create, final Kind kind, final List<String> steps) {
            directories.incrementAndGet();
            return real.directories(base, create, kind, steps);
        }

        @Override
        public FileAttribute<?>[] ownerOnlyFile() {
            return real.ownerOnlyFile();
        }

        @Override
        public void requireOwnFile(final Path file, final boolean alone) {
            if (file.getFileName().toString().equals("work.json")) {
                recordChecks.incrementAndGet();
            }
            real.requireOwnFile(file, alone);
        }

        @Override
        public byte[] readItemFile(final Path file, final int most) throws java.io.IOException {
            return real.readItemFile(file, most);
        }
    }
}
