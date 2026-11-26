package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssueFieldOptionsInner;
import org.fuin.porelo.forge.github.model.IssueFieldValue;
import org.fuin.porelo.forge.github.model.IssueFieldValueSingleSelectOption;
import org.fuin.porelo.forge.github.model.IssuesCreateRequestIssueFieldValuesInner;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequestIssueFieldValuesInner;
import org.fuin.porelo.planner.store.cache.BoardCopies;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.TrackerBoard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/** The sources the planner refuses before it touches Git or the cache. */
class StoreConfigurationTest {

    private final Projects projects =
            StoreConfiguration.fromGit(StoreConfiguration.gitProjects(Map.of()));

    @Test
    void theReportReadingIsIdleInTheMockModeAndWhenNoExecutableIsGiven() {
        // Harm: the mock mode, which reaches no Porelo, runs Porelo's executable every minute; or
        // a refused or missing executable ends the start instead of leaving the reading idle.
        final Projects projects = new Projects((p, r, b) -> null);
        final ChangesRegistry changes =
                new ChangesRegistry(
                        projects,
                        java.time.Duration.ofHours(1),
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
        final java.nio.file.Path porelo = Path.of("/opt/porelo/bin/porelo");
        try {
            try (ReportReading mock =
                    StoreConfiguration.reading(
                            true,
                            () -> {
                                throw new AssertionError("the mock mode asks for no executable");
                            },
                            projects,
                            changes,
                            org.fuin.porelo.wire.LimitFactors.DEFAULT)) {
                assertThat(mock.scheduled()).as("mock mode").isFalse();
            }
            try (ReportReading refused =
                    StoreConfiguration.reading(
                            false,
                            () -> {
                                throw new IllegalStateException("refused");
                            },
                            projects,
                            changes,
                            org.fuin.porelo.wire.LimitFactors.DEFAULT)) {
                assertThat(refused.scheduled()).as("refused executable").isFalse();
            }
            try (ReportReading none =
                    StoreConfiguration.reading(
                            false,
                            () -> null,
                            projects,
                            changes,
                            org.fuin.porelo.wire.LimitFactors.DEFAULT)) {
                assertThat(none.scheduled()).as("no executable").isFalse();
            }
            try (ReportReading given =
                    StoreConfiguration.reading(
                            false,
                            () -> porelo,
                            projects,
                            changes,
                            org.fuin.porelo.wire.LimitFactors.DEFAULT)) {
                assertThat(given.scheduled()).as("a checked executable").isTrue();
            }
        } finally {
            changes.close();
        }
    }

    @Test
    void theNativeImageKeepsEveryRecordACopyIsMadeOf() {
        // Harm: a copy the native executable cannot read or write, found only on a person's
        // computer, since Fory makes a record the image does not reflect on in the JVM alone.
        final RuntimeHints runtime = new RuntimeHints();
        new BoundTypes.Hints().registerHints(runtime, null);

        assertThat(BoundTypes.COPY)
                .as("the records the native image reflects on")
                .containsAll(BoardCopies.records());
        assertThat(
                        BoardCopies.records().stream()
                                .filter(
                                        type ->
                                                !RuntimeHintsPredicates.reflection()
                                                        .onType(type)
                                                        .test(runtime))
                                .toList())
                .as("the copy's records the native image does not bind")
                .isEmpty();
    }

    @Test
    void theNativeImageKeepsEveryGitHubModelTheStoreSendsOrReads() {
        // Harm: a GitHub answer the native executable cannot bind, such as the organization's
        // issue fields, so every priority save fails on a person's computer alone.
        final RuntimeHints runtime = new RuntimeHints();
        new BoundTypes.Hints().registerHints(runtime, null);
        final Set<Class<?>> models = new LinkedHashSet<>();
        for (final Method method : GitHubClient.class.getMethods()) {
            if (STORE_CALLS.contains(method.getName())) {
                models(method.getGenericReturnType(), models);
                for (final Type parameter : method.getGenericParameterTypes()) {
                    models(parameter, models);
                }
            }
        }
        // The organization's issue fields and an issue's values of them, each part named.
        models.addAll(
                List.of(
                        IssueField.class,
                        IssueFieldOptionsInner.class,
                        IssueField.DataTypeEnum.class,
                        IssueFieldValue.class,
                        IssueFieldValue.DataTypeEnum.class,
                        IssueFieldValueSingleSelectOption.class,
                        IssuesCreateRequestIssueFieldValuesInner.class,
                        IssuesUpdateRequestIssueFieldValuesInner.class));

        assertThat(models).as("the models the store sends or reads").contains(IssueField.class);
        assertThat(
                        models.stream()
                                .filter(
                                        model ->
                                                !RuntimeHintsPredicates.reflection()
                                                        .onType(model)
                                                        .test(runtime))
                                .toList())
                .as("the models the native image does not bind")
                .isEmpty();
    }

    /** The methods of the forge's REST client the GitHub store calls. */
    private static final Set<String> STORE_CALLS =
            Set.of(
                    "addBlockedBy",
                    "addSubIssue",
                    "createIssue",
                    "createIssueComment",
                    "createLabel",
                    "deleteLabel",
                    "issue",
                    "issueComments",
                    "issueFields",
                    "issues",
                    "issuesIfChanged",
                    "labels",
                    "removeBlockedBy",
                    "removeSubIssue",
                    "reprioritizeSubIssue",
                    "updateIssue",
                    "updateLabel",
                    "user");

    /** Adds the forge's REST models a type names, type arguments included. */
    private static void models(final Type type, final Set<Class<?>> found) {
        if (type instanceof Class<?> plain) {
            if (plain.getPackageName().equals(Issue.class.getPackageName())) {
                found.add(plain);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            for (final Type argument : parameterized.getActualTypeArguments()) {
                models(argument, found);
            }
        }
    }

    @Test
    void aSourceThatIsNoCloneUrlIsRefusedByName() {
        // Git would read a local path or an option where the planner expects a remote.
        assertThatThrownBy(() -> projects.put("alpha", "/home/person/git/p", null))
                .as("a local path")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal -> {
                            assertThat(refusal.name()).as("its name").isEqualTo("NotACloneUrl");
                            assertThat(refusal.parameters())
                                    .as("what it names")
                                    .containsEntry("project", "alpha");
                        });
        assertThatThrownBy(
                        () -> projects.put("alpha", "https://github.com/porelo-ai/-my.git", null))
                .as("a URL whose last part is no repository name")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal ->
                                assertThat(refusal.name())
                                        .as("its name")
                                        .isEqualTo("NotACloneUrl"));
    }

    @Test
    void aBranchGitWouldReadAsSomethingElseIsRefusedByName() {
        // A branch like '--upload-pack=x' or '../x' would reach git's command line.
        assertThatThrownBy(
                        () ->
                                projects.put(
                                        "alpha",
                                        "https://github.com/porelo-ai/porelo-project.git",
                                        "--upload-pack=x"))
                .as("a branch that is an option")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal -> {
                            assertThat(refusal.name()).as("its name").isEqualTo("InvalidName");
                            assertThat(refusal.parameters())
                                    .as("what it names")
                                    .containsEntry("project", "alpha");
                        });
    }

    @Test
    void theBoardsCopiesLieInTheCachesOwnDirectoryClosedToOthers(@TempDir final Path home)
            throws IOException {
        // Harm: a board copy another account can read, or one written where it is never found.
        final Path cache = Files.createDirectories(home.resolve("cache"));
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------"));
        final Map<String, String> environment =
                Map.of("HOME", home.toString(), "XDG_CACHE_HOME", cache.toString());
        final BoardCopy.Project project = new BoardCopy.Project("github.com", "example-org", 7);
        final BoardCopy copy =
                new BoardCopy(
                        project,
                        "c0ffee0",
                        List.of("example-project"),
                        Instant.parse("2026-11-21T10:00:00Z"),
                        new TrackerBoard(
                                "PVT_7", Map.of(), Map.of(), Map.of(), List.of(), Map.of(),
                                List.of()),
                        List.of(),
                        ChangeCursor.NONE,
                        List.of());

        StoreConfiguration.boardCopies(environment).write(copy);

        final Path directory = cache.resolve("porelo-planner").resolve(StoreConfiguration.BOARDS);
        assertThat(Files.getPosixFilePermissions(directory))
                .as("the copies' directory")
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE);
        try (Stream<Path> files = Files.list(directory)) {
            final List<Path> written = files.toList();
            assertThat(written).as("the copies written").hasSize(1);
            assertThat(Files.getPosixFilePermissions(written.get(0)))
                    .as("the copy's file")
                    .containsExactlyInAnyOrder(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
        assertThat(StoreConfiguration.boardCopies(environment).read(project, "c0ffee0"))
                .as("the copy read back")
                .contains(copy);
    }
}
