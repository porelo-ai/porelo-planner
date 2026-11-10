package org.fuin.porelo.planner.app;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.app.mock.MockMode;
import org.fuin.porelo.planner.app.mock.MockStore;
import org.fuin.porelo.planner.store.git.CloneUrl;
import org.fuin.porelo.planner.store.git.GitProjects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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
    public GitProjects gitProjects() {
        return gitProjects(System.getenv());
    }

    /**
     * Opens projects with a given environment, as the planner does with its own.
     *
     * @param environment the environment, which holds the person's Git login
     * @return the opener
     */
    static GitProjects gitProjects(final Map<String, String> environment) {
        return new GitProjects(
                environment,
                new GitProjects.Settings(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(60),
                        16 << 20,
                        1 << 20,
                        10_000,
                        64,
                        Duration.ofSeconds(30),
                        4),
                Clock.systemUTC(),
                Executors.newVirtualThreadPerTaskExecutor());
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

    /**
     * The registry of projects, each opened from a checked source, or in the mock mode each served
     * from the mock board.
     *
     * @param mode present only in the mock mode
     * @param gits opens a project from Git, made only outside the mock mode
     * @param mocks the mock store, made only in the mock mode
     * @return the registry
     */
    @Bean
    public Projects projects(
            final ObjectProvider<MockMode> mode,
            final ObjectProvider<GitProjects> gits,
            final ObjectProvider<MockStore> mocks) {
        // Decided at run time, not by @Profile: a native image fixes its beans when it is built.
        if (mode.getIfAvailable() != null) {
            final MockStore mock = mocks.getObject();
            // The same refusals as a real start, so the window can test them against the mock.
            return new Projects(
                    (project, repository, branch) -> {
                        checkedSource(project, repository, branch);
                        return mock;
                    },
                    MOST_PROJECTS);
        }
        return fromGit(gits.getObject());
    }

    /**
     * The registry of projects, each opened from Git after its source is checked.
     *
     * @param git opens a project from Git
     * @return the registry
     */
    static Projects fromGit(final GitProjects git) {
        return new Projects(
                (project, repository, branch) -> {
                    final CloneUrl url = checkedSource(project, repository, branch);
                    try {
                        return git.open(project, url, branch);
                    } catch (final IllegalArgumentException e) {
                        throw notACloneUrl(project, String.valueOf(e.getMessage()));
                    }
                },
                MOST_PROJECTS);
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
