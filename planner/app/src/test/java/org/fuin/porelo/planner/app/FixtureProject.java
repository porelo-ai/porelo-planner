package org.fuin.porelo.planner.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.fuin.porelo.planner.store.git.GitHttpsServer;
import org.fuin.porelo.planner.store.git.ServedRepositories;

/**
 * A project of one repository served over HTTPS, and the environment a planner reads it with: its
 * own home, cache and Git configuration, trusting only this run's certificate.
 */
final class FixtureProject implements AutoCloseable {

    /** The project's name, as the window gives it. */
    static final String NAME = "alpha";

    private final ServedRepositories repositories;

    private final GitHttpsServer server;

    private final Map<String, String> environment;

    private FixtureProject(
            final ServedRepositories repositories,
            final GitHttpsServer server,
            final Map<String, String> environment) {
        this.repositories = repositories;
        this.server = server;
        this.environment = environment;
    }

    /**
     * Writes and serves the project.
     *
     * @param dir the test's own directory
     * @return the served project
     * @throws Exception when it cannot be served
     */
    static FixtureProject serve(final Path dir) throws Exception {
        final ServedRepositories repositories = new ServedRepositories(dir);
        repositories.repository(
                "porelo-store",
                Map.of(
                        "project.yml",
                        """
                        project:
                            name: "alpha"
                            central: "git@github.com:porelo-ai/porelo-store.git"
                        repositories: {}
                        """,
                        "stories/workflow.yml",
                        """
                        states:
                          - {name: Backlog, closed: false}
                          - {name: Implementing, closed: false}
                          - {name: Review, closed: false}
                          - {name: Finished, closed: true}
                        phases:
                          - {name: Open, states: [Backlog]}
                          - {name: Build, states: [Implementing, Review]}
                          - {name: Done, states: [Finished]}
                        """,
                        "stories/work.yml",
                        """
                        work:
                          Implementing:
                            from: [Backlog]
                            done: Review
                            per: repository
                            modes: [UNATTENDED]
                            order: "Build {issues}."
                            models: {claude: claude-opus-5-5}
                        """,
                        "stories/README.md",
                        "# Stories\n",
                        "stories/ST7-Served.md",
                        "# ST7 — Served\n\nA story served to the window.\n\n## Data\n\n```json\n{\n"
                                + "  \"$schema\": \"story-2.json\",\n  \"id\": \"ST7\",\n"
                                + "  \"title\": \"Served\",\n  \"gain\": \"Something.\",\n"
                                + "  \"test\": \"A test.\",\n  \"priority\": \"High\",\n"
                                + "  \"issues\": [\"porelo-store#XY07\"],\n  \"tested\": null\n}\n```\n",
                        "issues/README.md",
                        "# Issues\n",
                        "issues/XY07-Served.md",
                        "# XY07 — Served\n\n```json\n{\n  \"$schema\": \"issue-1.json\",\n"
                                + "  \"id\": \"XY07\",\n  \"title\": \"Served\",\n"
                                + "  \"state\": \"Implementing\",\n  \"size\": \"S\",\n"
                                + "  \"priority\": \"Medium\",\n  \"rank\": 1,\n"
                                + "  \"blockedBy\": [],\n  \"covers\": \"Something.\"\n}\n```\n\n"
                                + "**What must be true.** It is served.\n"));
        final Path home = Files.createDirectories(dir.resolve("home"));
        final GitHttpsServer server = GitHttpsServer.start(repositories.served(), dir, null);
        final Path config = dir.resolve("gitconfig");
        Files.writeString(config, "[http]\n\tsslCAInfo = " + server.ca() + "\n");
        final Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("HOME", home.toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", config.toString());
        environment.put("XDG_CACHE_HOME", Files.createDirectories(dir.resolve("cache")).toString());
        return new FixtureProject(repositories, server, environment);
    }

    /**
     * Answers the body of the {@code PUT} that gives the planner this project.
     *
     * @return the JSON body
     */
    String source() {
        return "{\"repository\": \"" + server.url("porelo-store.git") + "\"}";
    }

    /**
     * Pushes a change from elsewhere: the issue XY07 moves to Review in the served remote.
     *
     * @throws Exception when it cannot be committed
     */
    void pushElsewhere() throws Exception {
        repositories.repository(
                "porelo-store",
                Map.of(
                        "issues/XY07-Served.md",
                        "# XY07 — Served\n\n```json\n{\n  \"$schema\": \"issue-1.json\",\n"
                                + "  \"id\": \"XY07\",\n  \"title\": \"Served\",\n"
                                + "  \"state\": \"Review\",\n  \"size\": \"S\",\n"
                                + "  \"priority\": \"Medium\",\n  \"rank\": 1,\n"
                                + "  \"blockedBy\": [],\n  \"covers\": \"Something.\"\n}\n```\n\n"
                                + "**What must be true.** It is served.\n"));
    }

    /**
     * Answers the environment a planner reads the project with.
     *
     * @return a copy of it
     */
    Map<String, String> environment() {
        return new HashMap<>(environment);
    }

    @Override
    public void close() {
        server.close();
    }
}
