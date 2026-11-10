package org.fuin.porelo.planner.app;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.git.CloneUrl;
import org.fuin.porelo.planner.store.git.GitProjects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

/** The projects the window gives the planner, each read from Git with the person's own login. */
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
     * The registry of projects, each opened from a checked source.
     *
     * @param git opens a project from Git
     * @return the registry
     */
    @Bean
    public Projects projects(final GitProjects git) {
        return new Projects(
                (project, repository, branch) -> {
                    final CloneUrl url = cloneUrl(project, repository);
                    if (branch != null && !GitProjects.isBranch(branch)) {
                        throw Refusal.invalidName(
                                "Not a branch name the planner takes", Map.of("project", project));
                    }
                    try {
                        return git.open(project, url, branch);
                    } catch (final IllegalArgumentException e) {
                        throw notACloneUrl(project, String.valueOf(e.getMessage()));
                    }
                },
                MOST_PROJECTS);
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
