package org.fuin.porelo.planner.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.forge.github.model.Enterprise;
import org.fuin.porelo.forge.github.model.Integration;
import org.fuin.porelo.forge.github.model.IntegrationPermissions;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssueComment;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssueFieldOptionsInner;
import org.fuin.porelo.forge.github.model.IssueFieldValue;
import org.fuin.porelo.forge.github.model.IssueFieldValueMultiSelectOptionsInner;
import org.fuin.porelo.forge.github.model.IssueFieldValueSingleSelectOption;
import org.fuin.porelo.forge.github.model.IssueLabelsInner;
import org.fuin.porelo.forge.github.model.IssueType;
import org.fuin.porelo.forge.github.model.IssuesAddBlockedByDependencyRequest;
import org.fuin.porelo.forge.github.model.IssuesAddSubIssueRequest;
import org.fuin.porelo.forge.github.model.IssuesCreateRequest;
import org.fuin.porelo.forge.github.model.IssuesCreateRequestIssueFieldValuesInner;
import org.fuin.porelo.forge.github.model.IssuesRemoveSubIssueRequest;
import org.fuin.porelo.forge.github.model.IssuesReprioritizeSubIssueRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdate200Response;
import org.fuin.porelo.forge.github.model.IssuesUpdateCommentRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequestIssueFieldValuesInner;
import org.fuin.porelo.forge.github.model.PrivateUser;
import org.fuin.porelo.forge.github.model.PublicUser;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.fuin.porelo.forge.github.model.UsersGetAuthenticated200Response;
import org.fuin.porelo.planner.app.answers.ReadingReply;
import org.fuin.porelo.planner.app.answers.TaskEventReply;
import org.fuin.porelo.planner.app.mock.MockMode;
import org.fuin.porelo.planner.app.mock.MockStore;
import org.fuin.porelo.planner.store.TrackedStores;
import org.fuin.porelo.planner.store.cache.BoardCopies;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.CopyFiles;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.fuin.porelo.planner.store.cache.TrackedUnlisted;
import org.fuin.porelo.planner.store.cache.TrackedWait;
import org.fuin.porelo.planner.store.cache.TrackerBoard;
import org.fuin.porelo.planner.store.files.PrivateFiles;
import org.fuin.porelo.planner.store.git.CloneUrl;
import org.fuin.porelo.planner.store.git.ForgeTokens;
import org.fuin.porelo.planner.store.git.GitProjects;
import org.fuin.porelo.planner.store.github.ConditionalReads;
import org.fuin.porelo.planner.store.github.GitHubChanges;
import org.fuin.porelo.planner.store.github.GitHubStoreOpener;
import org.fuin.porelo.planner.store.github.ProjectQueries;
import org.fuin.porelo.planner.store.github.ProjectWrites;
import org.fuin.porelo.planner.store.github.RepositoryIssues;
import org.fuin.porelo.planner.store.local.LocalDirectoryStores;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;

/**
 * The projects the window gives the planner, each read from Git with the person's own login, or in
 * the mock mode each served from the mock board.
 */
@Configuration(proxyBeanMethods = false)
public class StoreConfiguration {

    /** The most projects the planner keeps while it runs. */
    static final int MOST_PROJECTS = 8;

    /**
     * Opens projects over the person's cache directory with the planner's own environment.
     *
     * @return the opener
     */
    @Bean
    @Lazy
    // The forge client binds GitHub's REST answers to these by reflection, which a native image
    // keeps only for the types named.
    @RegisterReflectionForBinding({
        IssueComment.class,
        IssuesUpdateCommentRequest.class,
        IssuesUpdateRequest.class,
        IssuesUpdate200Response.class,
        Issue.class,
        // A story made outside the project is told by its issue type alone.
        IssueType.class,
        Integration.class,
        IntegrationPermissions.class,
        Enterprise.class,
        IssuesCreateRequest.class,
        IssuesAddSubIssueRequest.class,
        IssuesAddBlockedByDependencyRequest.class,
        IssuesRemoveSubIssueRequest.class,
        IssuesReprioritizeSubIssueRequest.class,
        IssueLabelsInner.class,
        // The organization's issue fields, which hold an issue's priority, and an issue's values.
        IssueField.class,
        IssueFieldOptionsInner.class,
        IssueFieldValue.class,
        IssueFieldValueSingleSelectOption.class,
        IssueFieldValueMultiSelectOptionsInner.class,
        IssuesCreateRequestIssueFieldValuesInner.class,
        IssuesUpdateRequestIssueFieldValuesInner.class,
        SimpleUser.class,
        PrivateUser.class,
        PublicUser.class,
        UsersGetAuthenticated200Response.class,
        // Apache Fory makes the board copy's records through their constructors by reflection.
        BoardCopy.class,
        BoardCopy.Project.class,
        TrackerBoard.class,
        TrackedItem.class,
        TrackedItem.Issue.class,
        TrackedItem.Links.class,
        TrackedItem.Link.class,
        TrackedItem.Value.class,
        TrackedItem.Choice.class,
        ChangeCursor.class,
        ChangeCursor.Page.class,
        TrackedWait.class,
        TrackedUnlisted.class
    })
    public GitProjects gitProjects(
            @Value("${planner.github.read-silence:60s}") final Duration readSilence,
            final ObjectProvider<LimitFactors> limits) {
        return gitProjects(
                System.getenv(),
                positive("planner.github.read-silence", readSilence),
                limits.getIfAvailable(() -> LimitFactors.DEFAULT));
    }

    /**
     * Opens projects with a given environment, as the planner does with its own.
     *
     * @param environment the environment, which holds the person's Git login
     * @return the opener
     */
    static GitProjects gitProjects(final Map<String, String> environment) {
        return gitProjects(environment, Duration.ofSeconds(60), LimitFactors.DEFAULT);
    }

    /**
     * Opens projects with a given environment, limit on GitHub's silence during a read, and the
     * factors every limit follows.
     *
     * @param environment the environment, which holds the person's Git login
     * @param readSilence how long GitHub may leave a tracked project's read without an answer,
     *     before the factor {@code services}
     * @param limits the factors
     * @return the opener
     */
    static GitProjects gitProjects(
            final Map<String, String> environment,
            final Duration readSilence,
            final LimitFactors limits) {
        return new GitProjects(
                environment,
                new GitProjects.Settings(
                        limits.services(Duration.ofSeconds(10)),
                        limits.services(Duration.ofSeconds(60)),
                        16 << 20,
                        1 << 20,
                        10_000,
                        64,
                        limits.polling(Duration.ofSeconds(30)),
                        4),
                Clock.systemUTC(),
                Executors.newVirtualThreadPerTaskExecutor(),
                tracked(environment, limits.services(readSilence), limits),
                limits);
    }

    /**
     * Answers a duration property, refused at start when it is zero or less, which would poll
     * without a pause or refuse every read at once.
     *
     * @param name the property's name
     * @param value its value
     * @return the value
     * @throws IllegalStateException when it is zero or less
     */
    static Duration positive(final String name, final Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalStateException(name + " must be longer than zero, not " + value);
        }
        return value;
    }

    /** GitHub's API, to which the client adds each call's path. */
    private static final URI GITHUB_API = URI.create("https://api.github.com");

    /**
     * Opens a project by its tracker's kind: one a GitHub project keeps, with the token Porelo's
     * vault lends for its forge, or one whose items lie in a directory on this computer.
     */
    private static TrackedStores tracked(
            final Map<String, String> environment,
            final Duration readSilence,
            final LimitFactors limits) {
        final ForgeTokens tokens = ForgeTokens.of(environment, limits);
        // GitHub's answers are never followed elsewhere, so the token goes to GitHub's API alone.
        final HttpClient http =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(limits.services(Duration.ofSeconds(10)))
                        .build();
        final GitHubGraphQl graphQl = new GitHubGraphQl(GITHUB_API, http);
        final GitHubClient rest = new GitHubClient(GITHUB_API, http);
        final TrackedStores github =
                new GitHubStoreOpener(
                                origin -> tokens.token(origin, origin.getHost()),
                                ProjectQueries.over(graphQl),
                                ProjectWrites.over(graphQl, rest),
                                ConditionalReads.over(rest),
                                RepositoryIssues.Pages.over(rest),
                                task -> Thread.ofVirtual().name("github-probe").start(task),
                                Clock.systemUTC(),
                                // A save waits for a whole read under way rather than being refused
                                // as busy.
                                limits.services(Duration.ofMinutes(2)),
                                readSilence,
                                limits)
                        .keepingCopies(() -> boardCopies(environment, limits))
                        .lookingForChanges(
                                GitHubChanges.ItemPages.over(rest), ProjectQueries.over(graphQl));
        return TrackedStores.byKind(
                Map.of(
                        GitHubStoreOpener.KIND,
                        github,
                        LocalDirectoryStores.KIND,
                        new LocalDirectoryStores(
                                PrivateFiles.of(environment, limits),
                                environment,
                                Clock.systemUTC())));
    }

    /**
     * The directory below the planner's cache the tracked boards' copies lie in, beside the
     * projects' clones; no project's name starts with an underscore, so none of their directories
     * is it, even where names are compared without case.
     */
    static final String BOARDS = "_boards";

    /**
     * The tracked boards' copies, under {@code $XDG_CACHE_HOME/porelo-planner/_boards/} on Linux
     * and {@code %LOCALAPPDATA%\porelo-planner\cache\_boards\} on Windows, the directory made or
     * checked as the person's alone each time a project is opened.
     *
     * @param environment the planner's environment
     * @return the copies
     * @throws IllegalStateException when the directory, or one on its way, is not the person's own
     */
    static BoardCopies boardCopies(final Map<String, String> environment) {
        return boardCopies(environment, LimitFactors.DEFAULT);
    }

    /**
     * The tracked boards' copies as above, a Windows PowerShell call given as long as the factor
     * {@code machines} says.
     *
     * @param environment the planner's environment
     * @param limits the factors
     * @return the copies
     * @throws IllegalStateException when the directory, or one on its way, is not the person's own
     */
    static BoardCopies boardCopies(
            final Map<String, String> environment, final LimitFactors limits) {
        final PrivateFiles files = PrivateFiles.of(environment, limits);
        final List<String> steps = new ArrayList<>(files.steps(PrivateFiles.Kind.CACHE));
        steps.add(BOARDS);
        final Path directory =
                files.directories(
                        files.base(PrivateFiles.Kind.CACHE, environment),
                        true,
                        PrivateFiles.Kind.CACHE,
                        steps);
        return new BoardCopies(
                directory,
                new CopyFiles() {
                    @Override
                    public FileAttribute<?>[] ownerOnlyFile() {
                        return files.ownerOnlyFile();
                    }

                    @Override
                    public void requireOwnFile(final Path file, final boolean alone) {
                        files.requireOwnFile(file, alone);
                    }
                });
    }

    /**
     * The projects followed by windows, each polled while one follows it.
     *
     * @param projects the projects the window has given
     * @param interval how often a followed project's repositories are fetched
     * @param probed how often a followed GitHub project is asked whether it changed, which costs no
     *     rate limit when it did not
     * @param limits the factors from the command line; both intervals and the longest wait between
     *     two polls follow {@code polling}
     * @return the registry
     */
    @Bean(destroyMethod = "close")
    public ChangesRegistry changesRegistry(
            final Projects projects,
            @Value("${planner.poll-interval:3m}") final Duration interval,
            @Value("${planner.github.poll-interval:30s}") final Duration probed,
            final ObjectProvider<LimitFactors> limits) {
        final LimitFactors factors = limits.getIfAvailable(() -> LimitFactors.DEFAULT);
        return new ChangesRegistry(
                projects,
                factors.polling(positive("planner.poll-interval", interval)),
                factors.polling(positive("planner.github.poll-interval", probed)),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual().name("poll-", 0).factory()),
                factors.polling(ChangesRegistry.LONGEST_WAIT));
    }

    /**
     * Ends the windows' streams before the web server stops, which waits for open requests.
     *
     * @param changes the projects followed
     * @return the lifecycle that ends them
     */
    @Bean
    public SmartLifecycle streamsEndFirst(final ChangesRegistry changes) {
        return new SmartLifecycle() {

            private volatile boolean running;

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                changes.close();
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    /**
     * The mock board, read only when the mock mode asks for it.
     *
     * @return the store serving it
     */
    @Bean
    @Lazy
    public MockStore mockStore() {
        return MockStore.fromClassPath();
    }

    /** Creates private disk records outside mock mode and memory records within it. */
    @Bean(destroyMethod = "close")
    @RegisterReflectionForBinding({
        WorkRecord.Document.class,
        TaskEventReply.class,
        ReadingReply.class
    })
    public WorkRecord workRecord(
            final ObjectProvider<MockMode> mode, final ObjectProvider<LimitFactors> limits) {
        final Map<String, String> environment = System.getenv();
        final LimitFactors factors = limits.getIfAvailable(() -> LimitFactors.DEFAULT);
        return new WorkRecord(
                environment,
                mode.getIfAvailable() != null,
                PrivateFiles.of(environment, factors),
                factors);
    }

    /**
     * The registry of projects, each opened from a checked source, or in the mock mode each served
     * from the mock board.
     *
     * @param mode present only in the mock mode
     * @param gits opens a project from Git, made only outside the mock mode
     * @param mocks the mock store, made only in the mock mode
     * @param records the project's durable or mock work records
     * @param readSilence how long a read of GitHub may wait for an answer, refused at start in the
     *     mock mode too, which makes no Git opener to check it
     * @return the registry
     */
    @Bean
    public Projects projects(
            final ObjectProvider<MockMode> mode,
            final ObjectProvider<GitProjects> gits,
            final ObjectProvider<MockStore> mocks,
            final WorkRecord records,
            @Value("${planner.github.read-silence:60s}") final Duration readSilence) {
        // Decided at run time, not by @Profile: a native image fixes its beans when it is built.
        if (mode.getIfAvailable() != null) {
            positive("planner.github.read-silence", readSilence);
            final MockStore mock = mocks.getObject();
            // The same refusals as a real start, so the window can test them against the mock.
            return new Projects(
                    (project, repository, branch) -> {
                        checkedSource(project, repository, branch);
                        // A board of its own, so a move in one project leaves another's as it was.
                        return mock.copy();
                    },
                    MOST_PROJECTS,
                    records);
        }
        return fromGit(gits.getObject(), records);
    }

    /**
     * The registry of projects, each opened from Git after its source is checked.
     *
     * @param git opens a project from Git
     * @return the registry
     */
    static Projects fromGit(final GitProjects git) {
        return fromGit(git, new WorkRecord(Map.of(), true));
    }

    private static Projects fromGit(final GitProjects git, final WorkRecord records) {
        return new Projects(
                (project, repository, branch) -> {
                    final CloneUrl url = checkedSource(project, repository, branch);
                    try {
                        return git.open(project, url, branch);
                    } catch (final IllegalArgumentException e) {
                        throw notACloneUrl(project, String.valueOf(e.getMessage()));
                    }
                },
                MOST_PROJECTS,
                records);
    }

    private static CloneUrl checkedSource(
            final String project, final String repository, final @Nullable String branch) {
        final CloneUrl url = cloneUrl(project, repository);
        if (branch != null && !GitProjects.isBranch(branch)) {
            throw Refusal.invalidName(
                    "Not a branch name the planner takes", Map.of("project", project));
        }
        return url;
    }

    private static CloneUrl cloneUrl(final String project, final String repository) {
        try {
            return new CloneUrl(repository);
        } catch (final IllegalArgumentException e) {
            throw notACloneUrl(project, String.valueOf(e.getMessage()));
        }
    }

    private static Refusal notACloneUrl(final String project, final String text) {
        return new Refusal(
                "NotACloneUrl", HttpStatus.BAD_REQUEST, text, Map.of("project", project));
    }
}
