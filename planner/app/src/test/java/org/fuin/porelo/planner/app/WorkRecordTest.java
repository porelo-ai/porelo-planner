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
    void aRecordWrittenBeforeRisksOpensWithItsRepositoriesMainAndSubAgent() throws Exception {
        // An upgrade must not make a running story's work record unreadable when its run names
        // repositories, whose main and sub-agents were written without an effort and a basis.
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
}
